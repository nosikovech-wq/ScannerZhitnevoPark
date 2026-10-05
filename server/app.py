#!/usr/bin/env python3
"""Житнево Парк: общая база, учётки и справочник."""

import hashlib
import hmac
import io
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import zipfile
from calendar import monthrange
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response

DATA = Path(os.environ.get("DATA_DIR", "/var/lib/zhitnevo"))
DB_PATH = DATA / "park.db"
PHOTO_DIR = DATA / "photos"
FLEET_PATH = DATA / "fleet.csv"
TOKEN_TTL = 30 * 24 * 3600
REPEAT_LOCK_MS = 22 * 60 * 60 * 1000
MSK = ZoneInfo("Europe/Moscow")
lock = threading.Lock()

app = FastAPI()


def now_ms() -> int:
    return int(time.time() * 1000)


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def hash_password(password: str, salt: str | None = None) -> str:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 120_000).hex()
    return f"{salt}${digest}"


def check_password(password: str, stored: str) -> bool:
    salt, digest = stored.split("$", 1)
    trial = hash_password(password, salt).split("$", 1)[1]
    return hmac.compare_digest(trial, digest)


def init_db() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    if not FLEET_PATH.is_file():
        seed = Path(__file__).with_name("fleet.csv")
        FLEET_PATH.write_text(seed.read_text(encoding="utf-8") if seed.is_file() else "Номер тягача;Номер прицепа;ФИО\n", encoding="utf-8")
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL,
                comment TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS plates (
                uid TEXT PRIMARY KEY,
                number TEXT NOT NULL,
                ts INTEGER NOT NULL,
                note TEXT,
                unauthorized INTEGER NOT NULL DEFAULT 0,
                author TEXT,
                updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                photo_path TEXT,
                sheet_uploaded INTEGER NOT NULL DEFAULT 0
            );
            """
        )
        cols = [info[1] for info in conn.execute("PRAGMA table_info(plates)")]
        if "sheet_uploaded" not in cols:
            conn.execute("ALTER TABLE plates ADD COLUMN sheet_uploaded INTEGER NOT NULL DEFAULT 0")
        user_cols = [info[1] for info in conn.execute("PRAGMA table_info(users)")]
        if "comment" not in user_cols:
            conn.execute("ALTER TABLE users ADD COLUMN comment TEXT NOT NULL DEFAULT ''")
        admin = os.environ.get("ADMIN_USER", "admin").strip()
        password = os.environ.get("ADMIN_PASSWORD", "").strip()
        exists = conn.execute("SELECT id FROM users WHERE username = ?", (admin,)).fetchone()
        if exists is None and password:
            conn.execute(
                "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, 'admin', ?)",
                (admin, hash_password(password), now_ms()),
            )
        conn.commit()


def user_from_token(token: str) -> sqlite3.Row | None:
    with db() as conn:
        row = conn.execute(
            """
            SELECT users.id, users.username, users.role
            FROM sessions JOIN users ON users.id = sessions.user_id
            WHERE sessions.token = ? AND sessions.expires_at > ?
            """,
            (token, int(time.time())),
        ).fetchone()
    return row


def current_user(request: Request) -> sqlite3.Row:
    header = request.headers.get("authorization", "")
    token = header[7:].strip() if header.lower().startswith("bearer ") else ""
    if not token:
        token = request.query_params.get("token", "")
    user = user_from_token(token) if token else None
    if user is None:
        raise HTTPException(401, "Нужно войти")
    return user


def admin_only(user: sqlite3.Row = Depends(current_user)) -> sqlite3.Row:
    if user["role"] != "admin":
        raise HTTPException(403, "Только для администратора")
    return user


def plate_json(row: sqlite3.Row) -> dict:
    return {
        "uid": row["uid"],
        "number": row["number"],
        "timestamp": row["ts"],
        "note": row["note"] or "",
        "unauthorized": bool(row["unauthorized"]),
        "author": row["author"] or "",
        "updatedAt": row["updated_at"],
        "deleted": bool(row["deleted"]),
        "hasPhoto": bool(row["photo_path"]),
        "uploaded": bool(row["sheet_uploaded"]),
    }


def normalize_plate(raw: str) -> str:
    letters = str.maketrans({
        "A": "А", "B": "В", "E": "Е", "K": "К", "M": "М",
        "H": "Н", "O": "О", "P": "Р", "C": "С", "T": "Т", "Y": "У", "X": "Х",
    })
    text = str(raw or "").upper().translate(letters)
    return "".join(ch for ch in text if not ch.isspace() and ch not in "-._")


def fleet_directory() -> tuple[dict, list]:
    by_plate = {}
    tractors = []
    for row in read_fleet():
        tractor = normalize_plate(row["tractor"])
        trailer = normalize_plate(row["trailer"])
        if tractor:
            by_plate.setdefault(tractor, row)
            if len(tractor) >= 8:
                tractors.append(tractor)
        if trailer:
            by_plate.setdefault(trailer, row)
    return by_plate, tractors


def crew_for(number: str, by_plate: dict, tractors: list) -> dict | None:
    key = normalize_plate(number)
    if key in by_plate:
        return by_plate[key]
    if len(key) < 8:
        return None
    body, region = key[:6], key[6:]
    hits = [
        tractor for tractor in tractors
        if tractor.startswith(body)
        and len(tractor) - 6 == len(region) + 1
        and tractor[6:].endswith(region)
    ]
    if len(hits) == 1:
        return by_plate.get(hits[0])
    return None


def resolve_number(number: str, by_plate: dict, tractors: list) -> str:
    key = normalize_plate(number)
    crew = crew_for(number, by_plate, tractors)
    if crew is None:
        return key
    tractor = normalize_plate(crew["tractor"])
    return tractor or key


def read_fleet() -> list[dict]:
    if not FLEET_PATH.is_file():
        return []
    rows = []
    for line in FLEET_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip().lstrip("\ufeff")
        if not line or line.lower().startswith("номер"):
            continue
        parts = [part.strip() for part in line.split(";")]
        while len(parts) < 3:
            parts.append("")
        if not parts[0] and not parts[1]:
            continue
        rows.append({"tractor": parts[0], "trailer": parts[1], "driver": parts[2]})
    return rows


def write_fleet(rows: list[dict]) -> None:
    lines = ["Номер тягача;Номер прицепа;ФИО"]
    for row in rows:
        tractor = str(row.get("tractor", "")).strip()
        trailer = str(row.get("trailer", "")).strip()
        driver = str(row.get("driver", "")).strip()
        if tractor or trailer:
            lines.append(f"{tractor};{trailer};{driver}")
    FLEET_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


@app.on_event("startup")
def startup() -> None:
    init_db()


@app.get("/")
def panel_page():
    return FileResponse(Path(__file__).with_name("panel.html"), media_type="text/html")


@app.get("/api/journal")
def journal(q: str = "", user: sqlite3.Row = Depends(current_user)):
    text = q.strip()
    like = f"%{text}%"
    by_plate, tractors = fleet_directory()
    with db() as conn:
        rows = conn.execute(
            """
            SELECT * FROM plates
            WHERE deleted = 0 AND (
                ? = '' OR number LIKE ? OR IFNULL(note, '') LIKE ? OR IFNULL(author, '') LIKE ?
            )
            ORDER BY ts DESC
            LIMIT 500
            """,
            (text, like, like, like),
        ).fetchall()
    plates = []
    for row in rows:
        item = plate_json(row)
        crew = crew_for(row["number"], by_plate, tractors)
        item["driver"] = crew["driver"] if crew else ""
        item["trailer"] = crew["trailer"] if crew else ""
        plates.append(item)
    return {"plates": plates}


@app.get("/api/stats")
def stats(date: str = "", user: sqlite3.Row = Depends(current_user)):
    now = datetime.now(MSK)
    chosen = now
    if date:
        try:
            year, month, day = [int(part) for part in date.split("-")]
            chosen = datetime(year, month, day, tzinfo=MSK)
        except ValueError:
            raise HTTPException(400, "Неверная дата")
    day_start = chosen.replace(hour=0, minute=0, second=0, microsecond=0)
    next_day = day_start + timedelta(days=1)
    month_start = day_start.replace(day=1)
    next_month = month_start.replace(year=month_start.year + 1, month=1) if month_start.month == 12 else month_start.replace(month=month_start.month + 1)
    with db() as conn:
        rows = conn.execute(
            """
            SELECT ts, number, unauthorized, author, note
            FROM plates WHERE deleted = 0 AND ts >= ? AND ts < ?
            ORDER BY ts
            """,
            (int(month_start.timestamp() * 1000), int(next_month.timestamp() * 1000)),
        ).fetchall()
    day_ms = int(day_start.timestamp() * 1000)
    next_ms = int(next_day.timestamp() * 1000)
    last_day = (next_month - timedelta(days=1)).day
    by_day = {}
    hours = [0] * 24
    staff = {}
    day_numbers = set()
    month_numbers = set()
    day_count = 0
    day_unauth = 0
    month_unauth = 0
    day_plates = []
    for row in rows:
        moment = datetime.fromtimestamp(row["ts"] / 1000, MSK)
        by_day[moment.day] = by_day.get(moment.day, 0) + 1
        month_numbers.add(row["number"])
        if row["unauthorized"]:
            month_unauth += 1
        if day_ms <= row["ts"] < next_ms:
            day_count += 1
            day_numbers.add(row["number"])
            if row["unauthorized"]:
                day_unauth += 1
            hours[moment.hour] += 1
            who = (row["author"] or "").strip() or "без имени"
            staff[who] = staff.get(who, 0) + 1
            day_plates.append({
                "time": moment.strftime("%H:%M"),
                "number": row["number"],
                "author": row["author"] or "",
                "note": row["note"] or "",
                "unauthorized": bool(row["unauthorized"]),
            })
    return {
        "date": day_start.strftime("%Y-%m-%d"),
        "day": day_count,
        "dayUnique": len(day_numbers),
        "dayUnauthorized": day_unauth,
        "month": len(rows),
        "monthUnique": len(month_numbers),
        "monthUnauthorized": month_unauth,
        "monthNumber": day_start.month,
        "year": day_start.year,
        "days": [{"day": day, "count": by_day.get(day, 0)} for day in range(1, last_day + 1)],
        "hours": [{"hour": hour, "count": hours[hour]} for hour in range(24)],
        "staff": [{"name": person, "count": count} for person, count in sorted(staff.items(), key=lambda item: -item[1])],
        "plates": list(reversed(day_plates)),
    }


MONTHS = (
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)
PLATE_SHAPES = (
    re.compile(r"^([АВЕКМНОРСТУХ]\d{3}[АВЕКМНОРСТУХ]{2})(\d{2,3})$"),
    re.compile(r"^([АВЕКМНОРСТУХ]{2}\d{4})(\d{2,3})$"),
    re.compile(r"^(\d{4}[АВЕКМНОРСТУХ]{2})(\d{2,3})$"),
)
EXCEL_EPOCH = datetime(1899, 12, 30, tzinfo=timezone.utc)


def xml_text(value: str) -> str:
    return (
        str(value)
        .replace("&", "&" + "amp;")
        .replace("<", "&" + "lt;")
        .replace(">", "&" + "gt;")
        .replace('"', "&" + "quot;")
    )


def format_tractor(number: str) -> str:
    normalized = normalize_plate(number)
    for shape in PLATE_SHAPES:
        found = shape.match(normalized)
        if found:
            return f"{found.group(1)} {found.group(2)}"
    return normalized


def excel_serial(year: int, month: int, day: int) -> int:
    moment = datetime(year, month, day, tzinfo=timezone.utc)
    return (moment - EXCEL_EPOCH).days


def workbook_xml() -> str:
    by_plate, tractors = fleet_directory()
    with db() as conn:
        plates = conn.execute(
            "SELECT uid, number, ts FROM plates WHERE deleted = 0 ORDER BY ts"
        ).fetchall()

    def resolve(number: str) -> str:
        return resolve_number(number, by_plate, tractors)

    def export_key(row: sqlite3.Row) -> str:
        resolved = resolve(row["number"])
        stored = normalize_plate(row["number"])
        if stored == resolved:
            return resolved
        blocked = any(
            other["uid"] != row["uid"]
            and normalize_plate(other["number"]) == resolved
            and abs(other["ts"] - row["ts"]) < REPEAT_LOCK_MS
            for other in plates
        )
        return stored if blocked else resolved

    grouped: dict[tuple[int, int], list] = {}
    for row in plates:
        moment = datetime.fromtimestamp(row["ts"] / 1000, MSK)
        grouped.setdefault((moment.year, moment.month), []).append(row)
    if not grouped:
        now = datetime.now(MSK)
        grouped[(now.year, now.month)] = []

    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<?mso-application progid="Excel.Sheet"?>',
        '<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">',
        "<Styles>",
        '<Style ss:ID="Title"><Alignment ss:Horizontal="Left" ss:Vertical="Center"/><Font ss:FontName="Calibri" ss:Size="14" ss:Bold="1"/></Style>',
        '<Style ss:ID="Date"><Alignment ss:Horizontal="Center" ss:Vertical="Center"/><Font ss:FontName="Calibri" ss:Size="11" ss:Bold="1"/><NumberFormat ss:Format="dd.mmm"/></Style>',
        '<Style ss:ID="Plate"><Alignment ss:Horizontal="Left" ss:Vertical="Center"/><Font ss:FontName="Calibri" ss:Size="14" ss:Bold="1"/></Style>',
        '<Style ss:ID="Time"><Alignment ss:Horizontal="Center" ss:Vertical="Center"/><Font ss:FontName="Calibri" ss:Size="14"/><NumberFormat ss:Format="hh:mm:ss"/></Style>',
        '<Style ss:ID="Info"><Alignment ss:Horizontal="Left" ss:Vertical="Center"/><Font ss:FontName="Calibri" ss:Size="12"/></Style>',
        "</Styles>",
    ]
    for year, month in sorted(grouped):
        parts.append(month_sheet(year, month, grouped[(year, month)], by_plate, export_key))
    parts.append("</Workbook>")
    return "".join(parts)


def month_sheet(year: int, month: int, plates: list, by_plate: dict, export_key) -> str:
    days = monthrange(year, month)[1]
    buckets: dict[str, list] = {}
    for row in plates:
        buckets.setdefault(export_key(row), []).append(row)
    order = sorted(buckets, key=format_tractor)
    cells = [
        f'<Worksheet ss:Name="{xml_text(MONTHS[month - 1] + " " + str(year))}"><Table>',
        '<Column ss:AutoFitWidth="0" ss:Width="150"/>',
        '<Column ss:AutoFitWidth="0" ss:Width="140"/>',
        '<Column ss:AutoFitWidth="0" ss:Width="220"/>',
    ]
    cells.extend('<Column ss:AutoFitWidth="0" ss:Width="62"/>' for _ in range(days))
    cells.append("<Row>")
    cells.append('<Cell ss:StyleID="Title"><Data ss:Type="String">Номер тягача</Data></Cell>')
    cells.append('<Cell ss:StyleID="Title"><Data ss:Type="String">Номер прицепа</Data></Cell>')
    cells.append('<Cell ss:StyleID="Title"><Data ss:Type="String">ФИО</Data></Cell>')
    for day in range(1, days + 1):
        cells.append(f'<Cell ss:StyleID="Date"><Data ss:Type="Number">{excel_serial(year, month, day)}</Data></Cell>')
    cells.append("</Row>")
    for key in order:
        visits = buckets[key]
        crew = by_plate.get(key)
        by_day: dict[int, list] = {}
        for visit in visits:
            moment = datetime.fromtimestamp(visit["ts"] / 1000, MSK)
            by_day.setdefault(moment.day, []).append(visit)
        tractor = format_tractor((crew["tractor"] if crew and str(crew["tractor"]).strip() else "") or key)
        trailer = format_tractor(crew["trailer"]) if crew and str(crew["trailer"]).strip() else ""
        driver = crew["driver"] if crew else ""
        cells.append("<Row>")
        cells.append(f'<Cell ss:StyleID="Plate"><Data ss:Type="String">{xml_text(tractor)}</Data></Cell>')
        cells.append(excel_text(trailer))
        cells.append(excel_text(driver))
        for day in range(1, days + 1):
            times = sorted(by_day.get(day, []), key=lambda item: item["ts"])
            if not times:
                cells.append("<Cell/>")
            elif len(times) == 1:
                moment = datetime.fromtimestamp(times[0]["ts"] / 1000, MSK)
                seconds = moment.hour * 3600 + moment.minute * 60 + moment.second
                cells.append(
                    f'<Cell ss:StyleID="Time"><Data ss:Type="Number">{seconds / 86400:.8f}</Data></Cell>'
                )
            else:
                clocks = []
                for item in times:
                    moment = datetime.fromtimestamp(item["ts"] / 1000, MSK)
                    clocks.append(moment.strftime("%H:%M:%S"))
                cells.append(excel_text(", ".join(clocks)))
        cells.append("</Row>")
    cells.append("</Table></Worksheet>")
    return "".join(cells)


def excel_text(value: str) -> str:
    if not str(value).strip():
        return "<Cell/>"
    return f'<Cell ss:StyleID="Info"><Data ss:Type="String">{xml_text(value)}</Data></Cell>'


@app.get("/api/export.xls")
def export_excel(user: sqlite3.Row = Depends(current_user)):
    payload = workbook_xml().encode("utf-8")
    return Response(
        content=payload,
        media_type="application/vnd.ms-excel",
        headers={"Content-Disposition": 'attachment; filename="TransportnyyeTekhnologii.xls"'},
    )


@app.get("/api/backup.zip")
def download_backup(user: sqlite3.Row = Depends(admin_only)):
    buffer = io.BytesIO()
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM plates WHERE deleted = 0 ORDER BY ts"
        ).fetchall()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        payload = []
        for row in rows:
            photo_name = ""
            path = Path(row["photo_path"]) if row["photo_path"] else PHOTO_DIR / f"{row['uid']}.jpg"
            if not path.is_file():
                path = PHOTO_DIR / f"{row['uid']}.jpg"
            if path.is_file():
                photo_name = f"{row['uid']}.jpg"
                archive.write(path, f"photos/{photo_name}")
            payload.append({
                "uid": row["uid"],
                "number": row["number"],
                "timestamp": row["ts"],
                "note": row["note"] or "",
                "unauthorized": bool(row["unauthorized"]),
                "uploaded": bool(row["sheet_uploaded"]),
                "photo": photo_name,
            })
        archive.writestr("plates.json", json.dumps(payload, ensure_ascii=False).encode("utf-8"))
    stamp = datetime.now(MSK).strftime("%Y%m%d-%H%M")
    return Response(
        content=buffer.getvalue(),
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="ZhitnevoPark-{stamp}.zip"'},
    )


@app.post("/api/clear")
async def clear_database(request: Request, user: sqlite3.Row = Depends(admin_only)):
    body = await request.json()
    if str(body.get("confirm", "")).strip() != "ОЧИСТИТЬ":
        raise HTTPException(400, "Для очистки введите слово ОЧИСТИТЬ")
    removed = 0
    with lock, db() as conn:
        rows = conn.execute("SELECT uid, photo_path FROM plates WHERE deleted = 0").fetchall()
        updated = now_ms()
        for row in rows:
            path = Path(row["photo_path"]) if row["photo_path"] else PHOTO_DIR / f"{row['uid']}.jpg"
            if path.is_file():
                path.unlink()
            fallback = PHOTO_DIR / f"{row['uid']}.jpg"
            if fallback.is_file():
                fallback.unlink()
            conn.execute(
                "UPDATE plates SET deleted = 1, photo_path = NULL, updated_at = ? WHERE uid = ?",
                (updated, row["uid"]),
            )
            removed += 1
        conn.commit()
    return {"ok": True, "cleared": removed}


@app.get("/api/health")
def health():
    return {"ok": True}


@app.post("/api/login")
async def login(request: Request):
    body = await request.json()
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    with db() as conn:
        user = conn.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        if user is None or not check_password(password, user["password_hash"]):
            raise HTTPException(401, "Неверный логин или пароль")
        token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
            (token, user["id"], int(time.time()) + TOKEN_TTL),
        )
        conn.commit()
    return {"token": token, "username": user["username"], "role": user["role"]}


@app.get("/api/me")
def me(user: sqlite3.Row = Depends(current_user)):
    return {"username": user["username"], "role": user["role"]}


@app.get("/api/users")
def list_users(user: sqlite3.Row = Depends(admin_only)):
    with db() as conn:
        rows = conn.execute("SELECT id, username, role, comment, created_at FROM users ORDER BY username").fetchall()
    return {"users": [dict(row) for row in rows]}


@app.post("/api/users")
async def create_user(request: Request, actor: sqlite3.Row = Depends(admin_only)):
    body = await request.json()
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    role = "admin" if body.get("role") == "admin" else "operator"
    comment = str(body.get("comment", "")).strip()
    if len(username) < 2 or len(password) < 4:
        raise HTTPException(400, "Логин от 2 символов, пароль от 4")
    with db() as conn:
        try:
            conn.execute(
                "INSERT INTO users (username, password_hash, role, comment, created_at) VALUES (?, ?, ?, ?, ?)",
                (username, hash_password(password), role, comment, now_ms()),
            )
            conn.commit()
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Такой логин уже есть")
    return {"ok": True}


@app.delete("/api/users/{user_id}")
def delete_user(user_id: int, actor: sqlite3.Row = Depends(admin_only)):
    if user_id == actor["id"]:
        raise HTTPException(400, "Нельзя удалить свою учётку")
    with db() as conn:
        row = conn.execute("SELECT role FROM users WHERE id = ?", (user_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "Нет такого пользователя")
        if row["role"] == "admin":
            admins = conn.execute("SELECT COUNT(*) AS n FROM users WHERE role = 'admin'").fetchone()["n"]
            if admins <= 1:
                raise HTTPException(400, "Нельзя удалить последнего администратора")
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user_id,))
        conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
        conn.commit()
    return {"ok": True}


@app.get("/api/changes")
def changes(since: int = 0, user: sqlite3.Row = Depends(current_user)):
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM plates WHERE updated_at > ? ORDER BY updated_at",
            (since,),
        ).fetchall()
    return {
        "serverTime": now_ms(),
        "fleetRevision": int(FLEET_PATH.stat().st_mtime * 1000) if FLEET_PATH.is_file() else 0,
        "plates": [plate_json(row) for row in rows],
    }


def save_photo(uid: str, photo_b64: str) -> str | None:
    if not photo_b64:
        return None
    import base64

    raw = base64.b64decode(photo_b64)
    path = PHOTO_DIR / f"{uid}.jpg"
    path.write_bytes(raw)
    return str(path)


@app.post("/api/plates")
async def upsert_plate(request: Request, user: sqlite3.Row = Depends(current_user)):
    body = await request.json()
    uid = str(body.get("uid", "")).strip()
    number = str(body.get("number", "")).strip()
    if not uid or not number:
        raise HTTPException(400, "Нет номера")
    ts = int(body.get("timestamp") or now_ms())
    note = str(body.get("note") or "")
    unauthorized = 1 if body.get("unauthorized") else 0
    updated = now_ms()
    photo = save_photo(uid, str(body.get("photo") or ""))
    with lock, db() as conn:
        current = conn.execute("SELECT photo_path, author, sheet_uploaded FROM plates WHERE uid = ?", (uid,)).fetchone()
        photo_path = photo or (current["photo_path"] if current else None)
        author = current["author"] if current and current["author"] else user["username"]
        uploaded = 1 if body.get("uploaded") or (current and current["sheet_uploaded"]) else 0
        conn.execute(
            """
            INSERT INTO plates (uid, number, ts, note, unauthorized, author, updated_at, deleted, photo_path, sheet_uploaded)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT(uid) DO UPDATE SET
                number = excluded.number,
                ts = excluded.ts,
                note = excluded.note,
                unauthorized = excluded.unauthorized,
                updated_at = excluded.updated_at,
                deleted = 0,
                photo_path = excluded.photo_path,
                sheet_uploaded = excluded.sheet_uploaded
            """,
            (uid, number, ts, note, unauthorized, author, updated, photo_path, uploaded),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM plates WHERE uid = ?", (uid,)).fetchone()
    return plate_json(row)


@app.delete("/api/plates/{uid}")
def delete_plate(uid: str, user: sqlite3.Row = Depends(current_user)):
    path = PHOTO_DIR / f"{uid}.jpg"
    if path.is_file():
        path.unlink()
    with db() as conn:
        current = conn.execute("SELECT uid FROM plates WHERE uid = ?", (uid,)).fetchone()
        if current is None:
            conn.execute(
                "INSERT INTO plates (uid, number, ts, note, unauthorized, author, updated_at, deleted, photo_path) VALUES (?, '', 0, '', 0, ?, ?, 1, NULL)",
                (uid, user["username"], now_ms()),
            )
        else:
            conn.execute(
                "UPDATE plates SET deleted = 1, photo_path = NULL, updated_at = ? WHERE uid = ?",
                (now_ms(), uid),
            )
        conn.commit()
    return {"ok": True}


@app.post("/api/plates/{uid}/edit")
async def edit_plate(uid: str, request: Request, user: sqlite3.Row = Depends(current_user)):
    body = await request.json()
    note = str(body.get("note") or "").strip()
    unauthorized = 1 if body.get("unauthorized") else 0
    by_plate, tractors = fleet_directory()
    canonical = resolve_number(str(body.get("number") or ""), by_plate, tractors)
    if not canonical:
        raise HTTPException(400, "Номер не распознан")
    with lock, db() as conn:
        current = conn.execute("SELECT * FROM plates WHERE uid = ? AND deleted = 0", (uid,)).fetchone()
        if current is None:
            raise HTTPException(404, "Запись не найдена")
        same = resolve_number(current["number"], by_plate, tractors) == canonical
        nearby = None
        joined = False
        if not same:
            others = conn.execute(
                "SELECT uid, number, ts FROM plates WHERE deleted = 0 AND uid != ?",
                (uid,),
            ).fetchall()
            for other in others:
                if resolve_number(other["number"], by_plate, tractors) != canonical:
                    continue
                joined = True
                if abs(other["ts"] - current["ts"]) < REPEAT_LOCK_MS:
                    if nearby is None or abs(other["ts"] - current["ts"]) < abs(nearby["ts"] - current["ts"]):
                        nearby = other
        if nearby is not None:
            return {
                "ok": False,
                "conflict": True,
                "number": format_tractor(canonical),
                "previousAt": nearby["ts"],
            }
        conn.execute(
            "UPDATE plates SET number = ?, note = ?, unauthorized = ?, updated_at = ? WHERE uid = ?",
            (canonical, note, unauthorized, now_ms(), uid),
        )
        conn.commit()
    return {
        "ok": True,
        "conflict": False,
        "message": f"Запись добавлена к номеру {format_tractor(canonical)}" if joined else "Сохранено",
    }


@app.get("/api/photos/{uid}")
def photo(uid: str, user: sqlite3.Row = Depends(current_user)):
    path = PHOTO_DIR / f"{uid}.jpg"
    if not path.is_file():
        raise HTTPException(404, "Нет фото")
    return FileResponse(path, media_type="image/jpeg")


@app.get("/api/fleet")
def get_fleet(user: sqlite3.Row = Depends(current_user)):
    return {
        "revision": int(FLEET_PATH.stat().st_mtime * 1000) if FLEET_PATH.is_file() else 0,
        "rows": read_fleet(),
    }


@app.put("/api/fleet")
async def put_fleet(request: Request, user: sqlite3.Row = Depends(admin_only)):
    body = await request.json()
    rows = body.get("rows") or []
    if not isinstance(rows, list):
        raise HTTPException(400, "Неверный справочник")
    write_fleet(rows)
    return {"ok": True, "revision": int(FLEET_PATH.stat().st_mtime * 1000)}


@app.post("/api/import")
async def import_backup(request: Request, user: sqlite3.Row = Depends(admin_only)):
    raw = await request.body()
    try:
        archive = zipfile.ZipFile(io.BytesIO(raw))
    except zipfile.BadZipFile:
        raise HTTPException(400, "Это не архив бэкапа")
    plates = None
    photos = {}
    for name in archive.namelist():
        if name.endswith("plates.json"):
            plates = json.loads(archive.read(name).decode("utf-8"))
        elif "/photos/" in f"/{name}" and not name.endswith("/"):
            photos[Path(name).name] = archive.read(name)
    if not isinstance(plates, list):
        raise HTTPException(400, "В архиве нет базы")
    inserted = 0
    skipped = 0
    with lock, db() as conn:
        for row in plates:
            uid = str(row.get("uid") or "").strip()
            number = str(row.get("number") or "").strip()
            if not uid or not number:
                skipped += 1
                continue
            existing = conn.execute("SELECT deleted, sheet_uploaded FROM plates WHERE uid = ?", (uid,)).fetchone()
            sheet_uploaded = 1 if row.get("uploaded") else 0
            if existing is not None and not existing["deleted"]:
                if sheet_uploaded and not existing["sheet_uploaded"]:
                    conn.execute(
                        "UPDATE plates SET sheet_uploaded = 1, updated_at = ? WHERE uid = ?",
                        (now_ms(), uid),
                    )
                skipped += 1
                continue
            photo_name = str(row.get("photo") or "")
            photo_path = None
            if photo_name and photo_name in photos:
                dest = PHOTO_DIR / f"{uid}.jpg"
                dest.write_bytes(photos[photo_name])
                photo_path = str(dest)
            conn.execute(
                """
                INSERT INTO plates (uid, number, ts, note, unauthorized, author, updated_at, deleted, photo_path, sheet_uploaded)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                ON CONFLICT(uid) DO UPDATE SET
                    number = excluded.number,
                    ts = excluded.ts,
                    note = excluded.note,
                    unauthorized = excluded.unauthorized,
                    updated_at = excluded.updated_at,
                    deleted = 0,
                    photo_path = COALESCE(excluded.photo_path, plates.photo_path),
                    sheet_uploaded = MAX(excluded.sheet_uploaded, plates.sheet_uploaded)
                """,
                (
                    uid,
                    number,
                    int(row.get("timestamp") or now_ms()),
                    str(row.get("note") or ""),
                    1 if row.get("unauthorized") else 0,
                    "backup",
                    now_ms(),
                    photo_path,
                    sheet_uploaded,
                ),
            )
            inserted += 1
        conn.commit()
    return {"ok": True, "inserted": inserted, "skipped": skipped}


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    return JSONResponse({"ok": False, "error": exc.detail}, status_code=exc.status_code)
