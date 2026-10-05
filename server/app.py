#!/usr/bin/env python3
"""Житнево Парк: общая база, учётки и справочник."""

import hashlib
import hmac
import io
import json
import os
import secrets
import sqlite3
import threading
import time
import zipfile
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse

DATA = Path(os.environ.get("DATA_DIR", "/var/lib/zhitnevo"))
DB_PATH = DATA / "park.db"
PHOTO_DIR = DATA / "photos"
FLEET_PATH = DATA / "fleet.csv"
TOKEN_TTL = 30 * 24 * 3600
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
    return {"plates": [plate_json(row) for row in rows]}


@app.get("/api/stats")
def stats(user: sqlite3.Row = Depends(current_user)):
    now = datetime.now(MSK)
    day_start = int(now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
    month_start = int(now.replace(day=1, hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
    with db() as conn:
        rows = conn.execute(
            "SELECT ts, unauthorized FROM plates WHERE deleted = 0 AND ts >= ?",
            (month_start,),
        ).fetchall()
    by_day = {}
    day_count = 0
    unauthorized = 0
    for row in rows:
        moment = datetime.fromtimestamp(row["ts"] / 1000, MSK)
        by_day[moment.day] = by_day.get(moment.day, 0) + 1
        if row["ts"] >= day_start:
            day_count += 1
        if row["unauthorized"]:
            unauthorized += 1
    return {
        "day": day_count,
        "month": len(rows),
        "unauthorized": unauthorized,
        "monthNumber": now.month,
        "year": now.year,
        "days": [{"day": day, "count": by_day.get(day, 0)} for day in range(1, now.day + 1)],
    }


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
        rows = conn.execute("SELECT id, username, role, created_at FROM users ORDER BY username").fetchall()
    return {"users": [dict(row) for row in rows]}


@app.post("/api/users")
async def create_user(request: Request, actor: sqlite3.Row = Depends(admin_only)):
    body = await request.json()
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    role = "admin" if body.get("role") == "admin" else "operator"
    if len(username) < 2 or len(password) < 4:
        raise HTTPException(400, "Логин от 2 символов, пароль от 4")
    with db() as conn:
        try:
            conn.execute(
                "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)",
                (username, hash_password(password), role, now_ms()),
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
