#!/usr/bin/env python3
"""Ночной архив базы и фото. Хранит копии за последние 14 дней."""

import sqlite3
import tarfile
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

DATA = Path("/var/lib/zhitnevo")
DEST = Path("/var/backups/zhitnevo")
KEEP = timedelta(days=14)
MSK = ZoneInfo("Europe/Moscow")


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(MSK).strftime("%Y%m%d-%H%M")
    archive_path = DEST / f"zhitnevo-{stamp}.tar.gz"
    snapshot = DEST / f".park-{stamp}.db"
    source = sqlite3.connect(DATA / "park.db")
    copy = sqlite3.connect(snapshot)
    try:
        source.backup(copy)
    finally:
        copy.close()
        source.close()
    with tarfile.open(archive_path, "w:gz") as archive:
        archive.add(snapshot, arcname="park.db")
        fleet = DATA / "fleet.csv"
        if fleet.is_file():
            archive.add(fleet, arcname="fleet.csv")
        photos = DATA / "photos"
        if photos.is_dir():
            archive.add(photos, arcname="photos")
    snapshot.unlink(missing_ok=True)
    cutoff = time.time() - KEEP.total_seconds()
    for path in DEST.glob("zhitnevo-*.tar.gz"):
        if path.stat().st_mtime < cutoff:
            path.unlink()


if __name__ == "__main__":
    main()
