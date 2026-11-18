# -*- coding: utf-8 -*-
"""EFE MOTO ECU discovery and passive protocol analysis.

This module deliberately separates observed facts from inferred candidates. It never
turns a captured frame into an executable ECU command. OEM security algorithms are
loaded only through an explicitly installed provider implementing the documented
provider contract below.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import uuid
from importlib.metadata import entry_points
from collections import Counter, defaultdict
from datetime import datetime, timezone

UDS_SERVICES = {
    0x10: "DiagnosticSessionControl", 0x11: "ECUReset",
    0x14: "ClearDiagnosticInformation", 0x19: "ReadDTCInformation",
    0x22: "ReadDataByIdentifier", 0x27: "SecurityAccess",
    0x28: "CommunicationControl", 0x2E: "WriteDataByIdentifier",
    0x2F: "InputOutputControlByIdentifier", 0x31: "RoutineControl",
    0x3E: "TesterPresent", 0x85: "ControlDTCSetting",
}
KWP_SERVICES = {
    0x10: "StartDiagnosticSession", 0x11: "ECUReset", 0x14: "ClearDiagnosticTroubleCodes",
    0x17: "ReadDiagnosticTroubleCodes", 0x18: "ReadDiagnosticTroubleCodesByStatus",
    0x1A: "ReadECUIdentification", 0x21: "ReadDataByCommonIdentifier",
    0x22: "ReadDataByLocalIdentifier", 0x23: "ReadMemoryByAddress",
    0x27: "SecurityAccess", 0x28: "CommunicationControl", 0x29: "ReadDataByPeriodicIdentifier",
    0x30: "DeviceControl", 0x31: "StartRoutine", 0x3B: "WriteDataByLocalIdentifier",
    0x3D: "WriteMemoryByAddress", 0x3E: "TesterPresent", 0x85: "ControlDTCSetting",
}
NRC = {
    0x11: "ServiceNotSupported", 0x12: "SubFunctionNotSupported",
    0x13: "IncorrectMessageLengthOrInvalidFormat", 0x22: "ConditionsNotCorrect",
    0x31: "RequestOutOfRange", 0x33: "SecurityAccessDenied",
    0x35: "InvalidKey", 0x36: "ExceededNumberOfAttempts",
    0x37: "RequiredTimeDelayNotExpired", 0x78: "ResponsePending",
}

def _bytes(value):
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    if isinstance(value, list):
        return bytes(int(x) & 255 for x in value)
    text = str(value or "").strip().replace("0x", "").replace(",", " ")
    compact = re.sub(r"[^0-9a-fA-F]", "", text)
    if not compact or len(compact) % 2:
        return b""
    try:
        return bytes.fromhex(compact)
    except ValueError:
        return b""

def _hex(data):
    return bytes(data).hex(" ").upper()

class IsoTpAssembler:
    def __init__(self):
        self.pending = {}

    def feed(self, can_id, data):
        if not data:
            return None
        pci = data[0] >> 4
        if pci == 0:
            length = data[0] & 0x0F
            return data[1:1 + length] if length and len(data) >= length + 1 else None
        if pci == 1 and len(data) >= 2:
            length = ((data[0] & 0x0F) << 8) | data[1]
            if not 0 < length <= 4095:
                self.pending.pop(can_id, None)
                return None
            payload = bytearray(data[2:])
            if len(payload) >= length:
                return bytes(payload[:length])
            self.pending[can_id] = {"length": length, "payload": payload, "seq": 1}
            return None
        if pci == 2:
            state = self.pending.get(can_id)
            if not state:
                return None
            seq = data[0] & 0x0F
            if seq != state["seq"]:
                self.pending.pop(can_id, None)
                return None
            state["payload"].extend(data[1:])
            state["seq"] = (seq + 1) & 0x0F
            if len(state["payload"]) >= state["length"]:
                self.pending.pop(can_id, None)
                return bytes(state["payload"][:state["length"]])
        return None

def classify_payload(payload):
    if not payload:
        return None
    sid = payload[0]
    if sid == 0x7F and len(payload) >= 3:
        original = payload[1]
        return {"family": "UDS/KWP", "service_id": "0x7F",
                "service": UDS_SERVICES.get(original, "Bilinmeyen servis"),
                "meaning": "Negatif cevap: NRC 0x%02X (%s)" %
                           (payload[2], NRC.get(payload[2], "NRC tanımsız")),
                "negative_response_code": payload[2], "original_service": original}
    if sid >= 0x40 and ((sid - 0x40) in UDS_SERVICES or (sid - 0x40) in KWP_SERVICES):
        original = sid - 0x40
        service_map = UDS_SERVICES if original in UDS_SERVICES else KWP_SERVICES
        info = {"family": "UDS/KWP", "service_id": "0x%02X" % sid,
                "service": service_map[original], "meaning": "Pozitif servis cevabı"}
        if original == 0x27 and len(payload) >= 3:
            info["security_level"] = payload[1]
            info["security_exchange"] = "seed_response" if payload[1] & 1 else "key_response"
            if payload[1] & 1:
                info["seed_hex"] = _hex(payload[2:])
            else:
                info["key_response_hex"] = _hex(payload[2:])
        if original == 0x22 and len(payload) >= 3:
            info["did"] = "0x%02X%02X" % (payload[1], payload[2])
        return info
    if sid in UDS_SERVICES or sid in KWP_SERVICES:
        service_map = UDS_SERVICES if sid in UDS_SERVICES else KWP_SERVICES
        info = {"family": "UDS/KWP request/candidate", "service_id": "0x%02X" % sid,
                "service": service_map[sid], "meaning": "Servis isteği veya yakalanmış teşhis mesajı"}
        if sid == 0x27 and len(payload) >= 2:
            info["security_level"] = payload[1]
            info["security_exchange"] = "seed_request" if payload[1] & 1 else "key_submission"
        if sid == 0x22 and len(payload) >= 3:
            info["did"] = "0x%02X%02X" % (payload[1], payload[2])
        return info
    if sid in (0x01, 0x03, 0x04, 0x07, 0x09):
        return {"family": "SAE J1979/OBD-II", "service_id": "0x%02X" % sid,
                "service": {1:"CurrentData",3:"StoredDTC",4:"ClearDTC",
                            7:"PendingDTC",9:"VehicleInformation"}[sid],
                "meaning": "OBD-II servis mesajı"}
    if sid in (0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8):
        return {"family": "KWP/ISO14230 candidate", "service_id": "0x%02X" % sid,
                "service": "KWP service candidate", "meaning": "KWP mesaj adayı; çerçeveleme/checksum doğrulanmalı"}
    return None

class DiagnosticAnalyzer:
    def analyze(self, frames, module="general"):
        if module not in ("engine", "abs", "both", "general"):
            raise ValueError("module engine, abs veya general olmalı.")
        if not isinstance(frames, list):
            raise ValueError("CAN frame listesi geçersiz.")
        frames = frames[-30000:]
        stats = {}
        assembler = IsoTpAssembler()
        messages = []
        malformed = 0
        for frame in frames:
            if not isinstance(frame, dict):
                malformed += 1
                continue
            can_id = str(frame.get("id", frame.get("can_id", ""))).upper().replace("0X", "").strip()
            data = _bytes(frame.get("data"))
            if not can_id or not data or len(data) > 64:
                malformed += 1
                continue
            try:
                stamp = float(frame.get("t", frame.get("timestamp", 0)) or 0)
            except (TypeError, ValueError):
                stamp = 0.0
            row = stats.setdefault(can_id, {"count": 0, "last_t": None, "periods": [],
                                             "dlc": set(), "changed": set(), "last": None})
            row["count"] += 1
            if row["last_t"] is not None and stamp >= row["last_t"]:
                delta = (stamp - row["last_t"]) * 1000
                if delta > 0 and len(row["periods"]) < 5000:
                    row["periods"].append(delta)
            row["last_t"] = stamp
            row["dlc"].add(len(data))
            if row["last"] is not None:
                for index, (old, new) in enumerate(zip(row["last"], data)):
                    if old != new:
                        row["changed"].add(index)
            row["last"] = data
            payload = assembler.feed(can_id, data)
            if payload:
                info = classify_payload(payload)
                if info:
                    messages.append({"can_id": "0x" + can_id, "payload": _hex(payload), **info})
            # KWP2000 long-header frames: format, length, target, source, SID/data, checksum.
            if len(data) >= 6 and (data[0] & 0x80) and data[1] >= 3 and len(data) == int(data[1]) + 3:
                if (sum(data[:-1]) & 0xFF) == data[-1]:
                    kwp_payload = data[4:-1]
                    kwp_info = classify_payload(kwp_payload)
                    if kwp_info:
                        sid = kwp_payload[0]
                        if sid in KWP_SERVICES:
                            kwp_info["service"] = KWP_SERVICES[sid]
                        elif sid >= 0x40 and (sid - 0x40) in KWP_SERVICES:
                            kwp_info["service"] = KWP_SERVICES[sid - 0x40]
                        messages.append({"can_id": "0x" + can_id, "payload": _hex(kwp_payload),
                                         **kwp_info, "family": "KWP2000"})
        rows = []
        for can_id, row in sorted(stats.items(), key=lambda item: item[1]["count"], reverse=True):
            periods = row["periods"]
            rows.append({"can_id": "0x" + can_id, "count": row["count"],
                         "mean_period_ms": round(sum(periods)/len(periods), 2) if periods else None,
                         "dlc": sorted(row["dlc"]), "changing_bytes": sorted(row["changed"]),
                         "last_data": _hex(row["last"] or b"")})
        ids = {int(x, 16) for x in stats if re.fullmatch(r"[0-9A-Fa-f]{1,8}", x)}
        pairs = []
        for response in sorted(ids):
            if 0x7E8 <= response <= 0x7EF and (response - 8) in ids:
                pairs.append({"request_id": "0x%03X" % (response - 8),
                              "response_id": "0x%03X" % response,
                              "evidence": "Standart OBD fiziksel adres çifti adayı"})
        security = [m for m in messages if m.get("service") == "SecurityAccess"]
        return {
            "ok": True, "module": module, "frame_count": len(frames),
            "unique_can_ids": len(stats), "malformed_frames": malformed,
            "can_ids": rows[:150], "diagnostic_messages": messages[-500:],
            "request_response_pairs": pairs,
            "security_access_observations": security[-100:],
            "identified_ecu": False,
            "module_note": "Seçilen modül etikettir; yalnızca gerçek pozitif ECU yanıtı ile kimlik doğrulanır.",
            "limitations": "Pasif kayıt servis ve adres adaylarını gösterir; tek başına ECU kimliği, Seed-Key algoritması veya komut yetkisi kanıtlamaz."
        }




def decode_yamaha_cp3_frames(frames):
    """Decode the published CP3 DBC. Applicability is one tested RN70 2022 EU bike."""
    if not isinstance(frames, list):
        raise ValueError("CAN frame listesi geçersiz.")
    try:
        import cantools
    except ImportError as exc:
        raise RuntimeError("cantools kurulu değil; kurulum bağımlılıklarını yükleyin.") from exc
    dbc_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "profiles", "yamaha_cp3_2021_2024.dbc")
    if not os.path.isfile(dbc_path):
        raise FileNotFoundError("Yamaha CP3 DBC profili bulunamadı.")
    database = cantools.database.load_file(dbc_path, strict=False)
    decoded = []
    unknown = Counter()
    errors = []
    for frame in frames[-30000:]:
        if not isinstance(frame, dict):
            continue
        raw_value = frame.get("id", frame.get("can_id", ""))
        raw_id = str(raw_value).strip().lower()
        try:
            can_id = int(raw_value) if isinstance(raw_value, int) else int(raw_id.replace("0x", ""), 16)
        except (TypeError, ValueError):
            continue
        data = _bytes(frame.get("data"))
        if not data:
            continue
        try:
            message = database.get_message_by_frame_id(can_id)
        except KeyError:
            unknown["0x%03X" % can_id] += 1
            continue
        if len(data) != message.length:
            errors.append({"can_id": "0x%03X" % can_id, "error": "DLC mismatch",
                           "expected": message.length, "actual": len(data)})
            continue
        try:
            values = message.decode(data, decode_choices=False, scaling=True)
            decoded.append({"can_id": "0x%03X" % can_id, "message": message.name,
                            "signals": values, "raw": _hex(data)})
        except Exception as exc:
            errors.append({"can_id": "0x%03X" % can_id, "error": str(exc)})
    return {
        "ok": True, "profile_id": "YAMAHA-CP3-TRACER9-RN70-2022-EU",
        "applicability": "2022 Yamaha Tracer 9 base RN70, EU Euro 5 only; other models/years unverified",
        "source": "https://github.com/bratukh/yamaha-cp3-can",
        "license": "CC BY 4.0; attribution in third_party/yamaha-cp3-can/LICENSE-DATA",
        "frames_decoded": len(decoded), "unknown_ids": dict(unknown),
        "errors": errors[-100:], "decoded": decoded[-2000:],
        "note": "DBC decoding is read-only broadcast signal decoding; it does not provide ABS DTC, actuator commands or Seed-Key algorithms."
    }

class SeedKeyProviderRegistry:
    """Loads explicitly installed, model-aware Seed-Key providers.

    Provider contract:
      provider_id: stable string
      supports(ecu_identity: dict, security_level: int) -> bool
      calculate_key(seed: bytes, ecu_identity: dict, security_level: int) -> bytes
    Providers must be supplied by a trusted, separately licensed package through
    the 'efe_moto.seedkey_providers' Python entry-point group.
    """
    def __init__(self):
        self._providers = {}
        self._loaded = False
        self._lock = threading.RLock()

    def discover(self):
        with self._lock:
            if self._loaded:
                return list(self._providers.values())
            try:
                points = entry_points()
                selected = points.select(group="efe_moto.seedkey_providers") if hasattr(points, "select") else points.get("efe_moto.seedkey_providers", [])
                for point in selected:
                    try:
                        factory = point.load()
                        provider = factory() if callable(factory) else factory
                        provider_id = str(getattr(provider, "provider_id", point.name)).strip()
                        if provider_id and callable(getattr(provider, "supports", None)) and callable(getattr(provider, "calculate_key", None)):
                            self._providers[provider_id] = provider
                    except Exception:
                        continue
            finally:
                self._loaded = True
            return list(self._providers.values())

    def public_list(self):
        providers = self.discover()
        return [{"provider_id": str(getattr(p, "provider_id", "unknown")),
                 "description": str(getattr(p, "description", "")),
                 "available": True} for p in providers]

    def calculate(self, provider_id, seed_hex, ecu_identity, security_level):
        self.discover()
        if not isinstance(ecu_identity, dict) or not ecu_identity:
            raise ValueError("Seed-Key hesabı için ECU kimlik profili gerekli.")
        try:
            seed = bytes.fromhex(re.sub(r"[^0-9a-fA-F]", "", str(seed_hex or "")))
            level = int(security_level)
        except (TypeError, ValueError):
            raise ValueError("Seed veya güvenlik seviyesi geçersiz.")
        if not seed or len(seed) > 32 or not 1 <= level <= 0x7F:
            raise ValueError("Seed uzunluğu veya güvenlik seviyesi desteklenen aralıkta değil.")
        provider = self._providers.get(str(provider_id or ""))
        if provider is None:
            raise LookupError("İstenen Seed-Key sağlayıcısı kurulu değil.")
        if not provider.supports(ecu_identity, level):
            raise LookupError("Bu sağlayıcı seçilen ECU kimliği/güvenlik seviyesi için uygun değil.")
        key = provider.calculate_key(seed, ecu_identity, level)
        if not isinstance(key, (bytes, bytearray)) or not key or len(key) > 32:
            raise ValueError("Seed-Key sağlayıcısı geçerli bir anahtar döndürmedi.")
        return {"ok": True, "provider_id": str(provider_id), "key_hex": bytes(key).hex(" ").upper(),
                "key_length": len(key), "ecu_identity": ecu_identity,
                "note": "Anahtar hesaplandı; ECU'ya gönderim için ayrıca oturum, adresleme ve kullanıcı onayı gerekir."}

SEEDKEY_PROVIDERS = SeedKeyProviderRegistry()

class DiagnosticCoordinator:
    def __init__(self):
        self.lock = threading.RLock()
        self.analyzer = DiagnosticAnalyzer()
        self.session = {"id": None, "state": "idle", "module": None,
                        "analysis": None, "consent": None, "created_at": None,
                        "last_error": None}
    def analyze(self, frames, module):
        result = self.analyzer.analyze(frames, module)
        with self.lock:
            self.session = {"id": uuid.uuid4().hex, "state": "analyzed",
                            "module": module, "analysis": result, "consent": None,
                            "created_at": datetime.now(timezone.utc).isoformat(),
                            "last_error": None}
        return result
    def consent(self, session_id, approved):
        with self.lock:
            if not self.session.get("id") or session_id != self.session["id"]:
                raise ValueError("Oturum süresi dolmuş veya kimliği geçersiz.")
            if not approved:
                self.session["state"] = "declined"
                self.session["consent"] = False
                return {"ok": True, "state": "declined", "message": "Kullanıcı izin vermedi; ECU'ya komut gönderilmedi."}
            self.session["state"] = "approved_read_only"
            self.session["consent"] = True
            return {"ok": True, "state": self.session["state"],
                    "message": "İzin kaydedildi. Yalnızca mevcut sürücüde desteklenen salt-okunur teşhis adımları başlatılabilir."}
    def status(self):
        with self.lock:
            return {"ok": True, "session": dict(self.session)}

COORDINATOR = DiagnosticCoordinator()

def create_blueprint():
    from flask import Blueprint, jsonify, request
    bp = Blueprint("efe_moto_auto_diagnostics", __name__)
    @bp.post("/api/auto-diagnostics/yamaha/cp3/decode")
    def decode_cp3():
        payload = request.get_json(silent=True) or {}
        try:
            return jsonify(decode_yamaha_cp3_frames(payload.get("frames", [])))
        except Exception as exc:
            return jsonify({"ok": False, "error": str(exc)}), 400
    @bp.get("/api/auto-diagnostics/yamaha/cp3/profile")
    def cp3_profile():
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "profiles", "yamaha_cp3_2021_2024.json")
        try:
            with open(path, "r", encoding="utf-8") as fh:
                return jsonify(json.load(fh))
        except Exception as exc:
            return jsonify({"ok": False, "error": str(exc)}), 500
    @bp.get("/api/auto-diagnostics/seedkey/providers")
    def seedkey_providers():
        providers = SEEDKEY_PROVIDERS.public_list()
        return jsonify({"ok": True, "providers": providers, "count": len(providers),
                        "note": "Model/yıl/ECU kimliğiyle eşleşen, ayrıca kurulmuş sağlayıcılar listelenir."})
    @bp.post("/api/auto-diagnostics/seedkey/calculate")
    def seedkey_calculate():
        payload = request.get_json(silent=True) or {}
        try:
            result = SEEDKEY_PROVIDERS.calculate(payload.get("provider_id"), payload.get("seed_hex"),
                payload.get("ecu_identity"), payload.get("security_level"))
            return jsonify(result)
        except LookupError as exc:
            return jsonify({"ok": False, "error": str(exc)}), 409
        except Exception as exc:
            return jsonify({"ok": False, "error": str(exc)}), 400
    @bp.get("/api/auto-diagnostics/status")
    def status():
        return jsonify(COORDINATOR.status())
    @bp.post("/api/auto-diagnostics/analyze")
    def analyze():
        payload = request.get_json(silent=True) or {}
        try:
            result = COORDINATOR.analyze(payload.get("frames", []),
                                         str(payload.get("module") or "general").lower())
            return jsonify({"session_id": COORDINATOR.session["id"], **result})
        except Exception as exc:
            return jsonify({"ok": False, "error": str(exc)}), 400
    @bp.post("/api/auto-diagnostics/consent")
    def consent():
        payload = request.get_json(silent=True) or {}
        try:
            return jsonify(COORDINATOR.consent(str(payload.get("session_id") or ""),
                                               bool(payload.get("approved"))))
        except Exception as exc:
            return jsonify({"ok": False, "error": str(exc)}), 409
    return bp
