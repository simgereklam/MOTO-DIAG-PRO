# -*- coding: utf-8 -*-
"""Local, persistent ECU traffic learning for EFE MOTO.

Only observed traffic is learned automatically. No security key is guessed and no
ECU command is transmitted by this module.
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path


def _hex(data):
    return bytes(data).hex(" ").upper()


class IsoTpDecoder:
    """Small ISO-TP reassembler for captured CAN frames (per CAN ID/direction)."""
    def __init__(self):
        self.pending = {}

    def feed(self, can_id, direction, data):
        data = bytes(data or b"")
        if not data:
            return None
        key = (int(can_id), str(direction))
        pci = data[0] >> 4
        if pci == 0:
            size = data[0] & 0x0F
            return data[1:1 + size] if size and len(data) >= size + 1 else None
        if pci == 1 and len(data) >= 2:
            size = ((data[0] & 0x0F) << 8) | data[1]
            if not size or size > 4095:
                self.pending.pop(key, None)
                return None
            body = bytearray(data[2:])
            if len(body) >= size:
                return bytes(body[:size])
            self.pending[key] = {"size": size, "body": body, "seq": 1}
            return None
        if pci == 2:
            state = self.pending.get(key)
            if not state or (data[0] & 0x0F) != state["seq"]:
                self.pending.pop(key, None)
                return None
            state["body"].extend(data[1:])
            state["seq"] = (state["seq"] + 1) & 0x0F
            if len(state["body"]) >= state["size"]:
                self.pending.pop(key, None)
                return bytes(state["body"][:state["size"]])
        return None


class ECULearningEngine:
    """Persists observed ECU facts and Seed/Key exchanges in a local SQLite DB."""
    def __init__(self, db_path=None):
        default_dir = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "EFE_MOTO" / "learning"
        self.db_path = str(db_path or (default_dir / "ecu_learning.sqlite3"))
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.decoder = IsoTpDecoder()
        self.latest_seed = {}
        self.latest_key = {}
        self._init_db()

    def _connect(self):
        conn = sqlite3.connect(self.db_path, timeout=10)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self.lock, self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
            CREATE TABLE IF NOT EXISTS frames(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL,
                protocol TEXT NOT NULL, can_id INTEGER NOT NULL,
                direction TEXT NOT NULL, data_hex TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS profiles(
                ecu_key TEXT PRIMARY KEY, protocol TEXT NOT NULL,
                response_id INTEGER, request_id INTEGER,
                identity_json TEXT NOT NULL DEFAULT '{}',
                first_seen REAL NOT NULL, last_seen REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS observations(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ecu_key TEXT NOT NULL,
                ts REAL NOT NULL, direction TEXT NOT NULL, service INTEGER NOT NULL,
                subfunction INTEGER, did INTEGER, payload_hex TEXT NOT NULL,
                result TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS seed_exchanges(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ecu_key TEXT NOT NULL,
                security_level INTEGER NOT NULL, seed_hex TEXT NOT NULL,
                key_hex TEXT, status TEXT NOT NULL, nrc INTEGER,
                first_seen REAL NOT NULL, last_seen REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_observations_ecu ON observations(ecu_key);
            CREATE INDEX IF NOT EXISTS idx_seed_ecu ON seed_exchanges(ecu_key);
            """)
            try:
                os.chmod(self.db_path, 0o600)
            except OSError:
                pass

    @staticmethod
    def _address_pair(can_id, direction):
        # Conventional 11-bit diagnostic request/response pair. Other addressing
        # is retained as an observation but is not guessed into this mapping.
        can_id = int(can_id)
        if 0x7E0 <= can_id <= 0x7E7:
            return can_id + 8 if direction == "TX" else can_id - 8
        if 0x7E8 <= can_id <= 0x7EF:
            return can_id - 8 if direction == "RX" else can_id + 8
        return None

    def ingest_can_frame(self, can_id, data, direction="RX", timestamp=None,
                         protocol="ISO15765"):
        direction = str(direction).upper()
        if direction not in ("TX", "RX"):
            raise ValueError("direction TX veya RX olmalı")
        raw = bytes(data or b"")
        if not raw or len(raw) > 4128:
            return None
        stamp = float(timestamp if timestamp is not None else time.time())
        with self.lock, self._connect() as db:
            db.execute("INSERT INTO frames(ts,protocol,can_id,direction,data_hex) VALUES(?,?,?,?,?)",
                       (stamp, str(protocol), int(can_id), direction, _hex(raw)))
            response_id = self._address_pair(can_id, direction)
            if response_id is not None:
                ecu_key = f"{protocol}:0x{response_id:03X}"
                request_id = response_id - 8 if 0x7E8 <= response_id <= 0x7EF else None
                db.execute("""INSERT INTO profiles(ecu_key,protocol,response_id,request_id,first_seen,last_seen)
                    VALUES(?,?,?,?,?,?) ON CONFLICT(ecu_key) DO UPDATE SET last_seen=excluded.last_seen""",
                    (ecu_key, str(protocol), response_id, request_id, stamp, stamp))
        payload = self.decoder.feed(can_id, direction, raw)
        if payload:
            return self.ingest_uds_message(can_id, direction, payload, stamp, protocol)
        return None

    def ingest_uds_message(self, can_id, direction, payload, timestamp=None,
                           protocol="ISO15765"):
        direction = str(direction).upper()
        data = bytes(payload or b"")
        if not data:
            return None
        stamp = float(timestamp if timestamp is not None else time.time())
        sid = data[0]
        ecu_id = int(can_id) if direction == "RX" else self._address_pair(can_id, "TX")
        if ecu_id is None:
            ecu_id = int(can_id)
        if direction == "TX":
            pair = self._address_pair(can_id, "TX")
            if pair is not None:
                ecu_id = pair
        ecu_key = f"{protocol}:0x{ecu_id:03X}"
        service = sid
        sub = data[1] if len(data) > 1 else None
        did = ((data[1] << 8) | data[2]) if sid in (0x22, 0x62) and len(data) >= 3 else None
        result = "request_or_observation"
        event = {"ecu_key": ecu_key, "direction": direction, "service": sid,
                 "payload_hex": _hex(data), "event": "observed"}

        with self.lock, self._connect() as db:
            response_id = ecu_id if direction == "RX" else self._address_pair(can_id, "TX")
            request_id = self._address_pair(response_id, "RX") if response_id is not None else None
            db.execute("""INSERT INTO profiles(ecu_key,protocol,response_id,request_id,first_seen,last_seen)
                VALUES(?,?,?,?,?,?) ON CONFLICT(ecu_key) DO UPDATE SET last_seen=excluded.last_seen""",
                (ecu_key, str(protocol), response_id, request_id, stamp, stamp))
            if sid == 0x7F and len(data) >= 3:
                service = data[1]
                nrc = data[2]
                result = "negative_response"
                event.update({"event": "negative_response", "nrc": nrc})
                if service == 0x27:
                    level = self.latest_seed.get(ecu_key, {}).get("level")
                    seed_row = self.latest_seed.get(ecu_key, {}).get("row_id")
                    if seed_row:
                        status = "lockout_or_delay" if nrc in (0x36, 0x37) else "rejected"
                        db.execute("UPDATE seed_exchanges SET status=?,nrc=?,last_seen=? WHERE id=?",
                                   (status, nrc, stamp, seed_row))
                    event["security_status"] = ("lockout_or_delay" if nrc in (0x36,0x37)
                                                 else "key_rejected")
            elif sid == 0x67 and len(data) >= 2:
                level = data[1]
                if level & 1:
                    seed = data[2:]
                    if seed:
                        cur = db.execute("""INSERT INTO seed_exchanges
                            (ecu_key,security_level,seed_hex,status,first_seen,last_seen)
                            VALUES(?,?,?,?,?,?)""",
                            (ecu_key, level, _hex(seed), "seed_captured", stamp, stamp))
                        self.latest_seed[ecu_key] = {"level": level, "row_id": cur.lastrowid}
                        result = "seed_response"
                        event.update({"event": "seed_captured", "security_level": level,
                                      "seed_hex": _hex(seed), "seed_length": len(seed)})
                else:
                    pending = self.latest_seed.get(ecu_key)
                    if pending:
                        db.execute("UPDATE seed_exchanges SET status='accepted',last_seen=? WHERE id=?",
                                   (stamp, pending["row_id"]))
                        result = "key_accepted"
                        event.update({"event": "key_accepted", "security_level": level,
                                      "ecu_security_access": "accepted"})
                    else:
                        result = "security_positive_response_unpaired"
                        event.update({"event": result})
            elif sid == 0x27 and len(data) >= 2 and direction == "TX":
                level = data[1]
                if not (level & 1):
                    key = data[2:]
                    pending = self.latest_seed.get(ecu_key)
                    if pending:
                        db.execute("UPDATE seed_exchanges SET key_hex=?,status='key_submitted_observed',last_seen=? WHERE id=?",
                                   (_hex(key), stamp, pending["row_id"]))
                        self.latest_key[ecu_key] = {"level": level, "row_id": pending["row_id"]}
                        result = "key_submission_observed"
                        event.update({"event": result, "security_level": level,
                                      "key_length": len(key)})
            elif sid in (0x50, 0x51, 0x54, 0x59, 0x62, 0x67, 0x68, 0x71, 0x7E):
                result = "positive_response"
                event.update({"event": "positive_response"})
                if sid == 0x62 and did is not None:
                    event["did"] = "0x%04X" % did
            elif sid in (0x10,0x11,0x14,0x19,0x22,0x27,0x28,0x2E,0x2F,0x31,0x3E,0x85):
                result = "request_observed" if direction == "TX" else "service_observed"
            db.execute("""INSERT INTO observations
                (ecu_key,ts,direction,service,subfunction,did,payload_hex,result)
                VALUES(?,?,?,?,?,?,?,?)""",
                (ecu_key, stamp, direction, service, sub, did, _hex(data), result))
        return event

    def snapshot(self, limit=100):
        with self.lock, self._connect() as db:
            profiles = [dict(row) for row in db.execute(
                "SELECT * FROM profiles ORDER BY last_seen DESC LIMIT ?", (max(1,min(int(limit),500)),))]
            exchanges = [dict(row) for row in db.execute(
                "SELECT * FROM seed_exchanges ORDER BY last_seen DESC LIMIT ?", (max(1,min(int(limit),500)),))]
            observations = [dict(row) for row in db.execute(
                "SELECT * FROM observations ORDER BY ts DESC LIMIT ?", (max(1,min(int(limit),500)),))]
            frame_count = db.execute("SELECT COUNT(*) FROM frames").fetchone()[0]
        for row in profiles:
            row["identity"] = json.loads(row.pop("identity_json", "{}"))
        return {
            "ok": True, "mode": "passive_learning",
            "frame_count": frame_count, "ecu_profiles": profiles,
            "seed_key_exchanges": exchanges, "recent_observations": observations,
            "key_algorithm_status": "provider_or_verified_samples_required",
            "automatic_actions": {
                "capture_and_classify": True, "persist_profiles": True,
                "guess_or_bruteforce_key": False, "send_key_without_user_approval": False,
                "execute_unverified_abs_actuator": False
            }
        }


ECU_LEARNER = ECULearningEngine()
