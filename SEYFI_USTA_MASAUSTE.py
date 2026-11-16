import tkinter as tk
from tkinter import scrolledtext, messagebox
import webbrowser
import threading
import time
import socket
import os
import re
import queue
import json
import urllib.request
import urllib.error
import subprocess
import shutil
import sys
import tempfile
from datetime import datetime
import csv
import io
import traceback

# Web sunucusu kutuphaneleri eksikse masaustu uygulamasi sessizce kapanmasin.
try:
    from flask import Flask, render_template_string, request, jsonify
    from flask_socketio import SocketIO
    WEB_STACK_OK = True
    WEB_STACK_ERROR = ""
except Exception as _web_exc:
    WEB_STACK_OK = False
    WEB_STACK_ERROR = str(_web_exc)
    try:
        _err_root = tk.Tk()
        _err_root.withdraw()
        messagebox.showerror(
            "Seyfi Usta - Eksik kutuphane",
            "Program acilamadi. Web arayuzu icin gerekli Python kutuphaneleri yuklu degil.\\n\\n"
            "Komut Istemi'nde su komutu calistirin:\\n"
            "py -m pip install flask flask-socketio\\n\\n"
            "Ayrinti: " + WEB_STACK_ERROR
        )
        _err_root.destroy()
    except Exception:
        pass
    raise SystemExit(1)

# --- Kütüphane Yüklemeleri ---
try:
    import serial
    import serial.tools.list_ports
    PYSERIAL_OK = True
except ImportError: PYSERIAL_OK = False
try:
    import can
    import udsoncan
    from udsoncan.connections import PythonIsoTpConnection
    import isotp
    CAN_OK = True
except ImportError: CAN_OK = False
try:
    import obd
    OBD_OK = True
except ImportError: OBD_OK = False

# SEYFI USTA - guvenli OBD iyilestirme surumu
# Marka-ozel ABS/DCT/CAN islemleri dogrulanmis ECU profili olmadan kapali tutulur.
# ==================== GENİŞLETİLMİŞ DTC (ARIZA KODU) KÜTÜPHANESİ ====================
DTC_TR = {
    "13": "Pulsar Bobin Sinyali", "14": "Krank Konum Sensörü Sinyali",
    "15": "Motor Sıcaklık Sensörü", "16": "Gaz Kelebeği Sensörü (TPS) Takılı Kaldı",
    "17": "Vuruntu Sensörü", "18": "Gaz Kelebeği Konum Sensörü Sinyali",
    "19": "Akü Voltajı", "21": "Eksantrik Mili Konum Sensörü",
    "22": "Atmosferik Basınç Sensörü", "23": "Emme Havası Sıcaklık Sensörü",
    "24": "Oksijen (O2) Sensörü", "25": "Yakıt Basıncı Sensörü",
    "26": "Enjektör Sinyali (Genel)", "27": "Yakıt Filtresinde Su",
    "28": "Vites Konum Anahtarı", "29": "Emme Basınç Sensörü",
    "30": "ECU Kilitleme (Latch Up)", "33": "Silindir #1 Ateşleme Bobini",
    "34": "Silindir #2 Ateşleme Bobini", "39": "Yağ Basıncı Sensörü / Enjektör",
    "41": "Hız Sensörü Sinyali Yok", "42": "Vites Konum Anahtarı Açık/Kısa Devre",
    "43": "Yakıt Sistemi Voltajı", "44": "EEPROM Okuma/Yazma Hatası",
    "46": "Şarj Voltajı Anormal", "50": "ECU Bellek Arızası",
    "61": "Rölanti Hızı Kontrol (ISC) Valfi", "70": "Motor Rölanti Durdurma Hatası",
    "84": "VVA (Değişken Valf Aktüatörü) Selenoidi",
    "C0520": "Çok Eksenli İvme Sensörü Modülü", "C1000": "Direksiyon Amortisörü Selenoid Devresi",
    "P0030": "O2 Sensörü Isıtıcı Devresi", "P0107": "MAP Sensörü Düşük Voltaj",
    "P0117": "Motor Soğutma Suyu Sensörü Düşük Voltaj", "P0122": "Gaz Kelebeği Pozisyon Sensörü Düşük Voltaj",
    "P0335": "Krank Mili Pozisyon Sensörü", "P0500": "Araç Hız Sensörü",
    "P0560": "Sistem Voltajı", "P0601": "ECU Bellek Hatası",
    "P0700": "Şanzıman Kontrol Sistemi (CVT)", "P0705": "Vites Aralık Sensörü",
    "P0710": "Şanzıman Sıcaklık Sensörü", "P1600": "CAN Bus / K-Line İletişim Hatası",
    "P1601": "CAN Bus / K-Line İletişim Hatası", "P1604": "Immobilizer Kod Hatası",
    "P2101": "Gaz Kelebeği Aktüatörü", "P2135": "Gaz Kelebeği Sensörleri Korelasyon Hatası",
    "P2158": "CAN İletişim Hatası (SCU)", "U0100": "ECM ile İletişim Kaybı",
    "U0121": "ABS ECU ile İletişim Kaybı", "U0125": "IMU (Atalet Ölçüm Ünitesi) ile İletişim Kaybı",
    "U0155": "Gösterge Paneli İletişim Kaybı",
    "P0113": "Emme Hava Sıcaklık Sensörü Yüksek Voltaj (IAT)",
    "P0118": "Motor Soğutma Suyu Sıcaklık Sensörü Yüksek Voltaj (ECT)",
    "P0511": "Rölanti Hava Kontrol (IAC) Devresi",
}
def dtc_desc(code): return DTC_TR.get(code.upper().strip(), "Bilinmeyen arıza kodu")

# ABS teşhis veri katmanı tek dosyaya alındı; ECU komutu göndermez.
# ==================== HONDA DCT PROFİL / DTC KATMANI ====================
# Honda 61MKA04: 2016-2020 NC700X/XD ve NC750X/XA/XD servis kılavuzu kapsamı.
# Haberleşme pinleri, CAN hızı, adresleme ve komutlar doğrulanmadı.
DCT_PROFILE_REGISTRY = {("honda", "nc750x dct", 2020): {
    "profile_id": "HONDA-NC750XD-2020-61MKA04", "verified": True,
    "manual": "Honda 61MKA04 (2016-2020 NC700X/XD, NC750X/XA/XD)",
    "ecu_description": "DCT'li XD varyantında PCM üzerinden DCT öz tanılama",
    "diagnostic_tool": "Honda HDS / modele uygun teşhis aracı",
    "protocol": "Fiziksel taşıma ve teşhis adreslemesi henüz doğrulanmadı",
    "unknown": "DLC pin eşlemesi, CAN bit hızı, CAN ID, ISO-TP adresleri ve servis komutları",
    "capabilities": ["offline_dtc_dictionary"]}}
DCT_DTC_DATABASE = {
    ("honda", "nc750x dct", 2020, "21-1"): {
        "description_tr": "Vites mili açı sensörü voltajı belirtilen alt sınırın altında.",
        "source": "Honda 61MKA04, DCT sistem açıklaması, sayfa 12-11 (DTC 21-1 örneği)."},
    ("honda", "nc750x dct", 2020, "21-2"): {
        "description_tr": "Vites mili açı sensörü voltajı belirtilen üst sınırın üzerinde.",
        "source": "Honda 61MKA04, DCT sistem açıklaması, sayfa 12-11 (DTC 21-2 örneği)."}}

def normalize_dct_model(value):
    return normalize_abs_text(value).replace(" dual clutch transmission", " dct")

def evaluate_dct_profile(model, year):
    try:
        model_year = int(year)
    except (TypeError, ValueError):
        raise ValueError("Geçerli bir DCT model yılı girin.")
    model_key = normalize_dct_model(model)
    if not model_key or model_year < 1980 or model_year > datetime.now().year + 1:
        raise ValueError("DCT model ve model yılı bilgisi geçerli olmalı.")
    profile = DCT_PROFILE_REGISTRY.get(("honda", model_key, model_year))
    if not profile:
        return {"ok": True, "verified": False, "status": "DCT PROFİLİ DOĞRULANMADI",
                "profile_id": None, "message": "Bu Honda DCT model/yılı için doğrulanmış servis profili yok. ECU'ya komut gönderilmedi.",
                "manual": None, "ecu_description": "Bilinmiyor", "diagnostic_tool": "Bilinmiyor",
                "protocol": "Doğrulanmadı", "unknown": "Pinout, CAN hızı, adresleme ve komutlar bilinmiyor.", "profile_schema_version": DCT_PROFILE_SCHEMA_VERSION, "profile_shell": build_dct_profile_record(model, model_year), "model_family": DCT_MODEL_CATALOG.get(str(model).strip(), "Tanımlanmamış Honda DCT ailesi")}
    return {"ok": True, "status": "SERVİS KILAVUZU PROFİLİ BULUNDU · ECU BAĞLANTISI DOĞRULANMADI",
            **profile, "message": "Profil yalnızca servis dokümanı kapsamını ve çevrimdışı DTC sözlüğünü doğrular. Viecar motor-OBD bağlantısı DCT erişimi sayılmaz; ECU'ya komut gönderilmedi."}

def decode_dct_dtc(model, year, code):
    try:
        model_key = normalize_dct_model(model)
        model_year = int(year)
        code_key = re.sub(r"\s+", "", str(code or "").strip().upper()).replace("_", "-")
        if not re.fullmatch(r"\d{1,3}-\d{1,2}", code_key):
            raise ValueError("DCT kodunu ana-alt biçiminde girin (ör. 21-1).")
        entry = DCT_DTC_DATABASE.get(("honda", model_key, model_year, code_key))
        if not entry:
            return {"ok": True, "found": False, "code": code_key,
                    "description": "Bu model/yıl için çevrimdışı doğrulanmış açıklama bulunamadı.", "source": None}
        return {"ok": True, "found": True, "code": code_key, **entry}
    except (TypeError, ValueError) as exc:
        return {"ok": False, "found": False, "code": "", "description": str(exc), "source": None}


# ==================== HONDA DCT ARAŞTIRMA / REFERANS KATMANI ====================
# Bu kayıtlar DCT protokolünü veya araç üzerindeki uyumluluğu etkinleştirmez.
# Yalnızca üretici/uygulama açıklamalarından derlenen araştırma referanslarıdır.
DCT_TOOL_REFERENCES = {
    "biketune": {
        "name": "Biketune",
        "url": "https://biketune.app/bike/honda-nc-750-cc/",
        "category": "diagnostic_app",
        "status": "MODEL SAYFASINDA NC750S DCT / NC750X DCT LİSTELİ",
        "functions": ["Arıza kodu okuma", "Pro ile arıza kodu silme", "Canlı veri (yanıt veren üniteler)"],
        "adapter": "Honda K-Line için STN tabanlı, K-Line destekli adaptör; Honda 4/6 pin kablo gerekebilir.",
        "limitations": "Uygulama her kontrol ünitesini taradığını ve yalnızca yanıt veren üniteleri listelediğini belirtiyor. DCT TCU erişimi araç/yıl/ünite bazında ayrıca doğrulanmalı.",
        "evidence": "Üreticinin NC 750 model sayfası ve adaptör/uyumluluk açıklaması."
    },
    "fxtul_m3a": {
        "name": "FXTUL M3-A",
        "url": "https://fxtul.com/products/fxtul-m3-motorcycle-diagnostic-tool-obd2-code-reader-scan-tool-for-honda-yamaha-motorcycle",
        "category": "diagnostic_device",
        "status": "GENEL MOTOSİKLET TEŞHİS İŞLEVLERİ VAR; HONDA DCT TCU ERİŞİMİ DOĞRULANMADI",
        "functions": ["Genel ürün dokümanında DTC okuma/silme", "Canlı veri", "Freeze-frame ve servis/test işlevleri (model desteğine bağlı)"],
        "adapter": "Üreticinin motosiklet bağlantı kablo seti ve cihazı.",
        "limitations": "Motor ECU'sunda DTC okuması, DCT kontrol ünitesine erişim kanıtı değildir. Honda DCT modülü için bağımsız, model/yıl bazlı test sonucu gerekli.",
        "evidence": "Üretici ürün/kılavuz bilgisi; DCT TCU işlevi ayrıca teyit edilmedi."
    },
    "healtech_dct": {
        "name": "HealTech DCT Dyno Tool",
        "url": "https://www.healtech-electronics.com/products/dct/",
        "category": "service_function",
        "status": "DCT DİNAMOMETRE MODU ETKİNLEŞTİRME İŞLEVİ ÜRETİCİ TARAFINDAN TANIMLANMIŞ",
        "functions": ["DCT dyno mode etkinleştirme"],
        "variants": {
            "DCT-H01": "Eski nesil DCT uygulama listesi: NC700/NC750, Integra, CTX700, CRF1000L, VFR1200X, bazı Gold Wing ve X-ADV yılları.",
            "DCT-H02": "Yeni nesil uygulama listesi: CRF1100L, CMX1100, NT1100, Forza 750, yeni X-ADV ve Gold Wing yılları."
        },
        "limitations": "Arıza kodu okuma/silme, canlı veri veya DCT programlama cihazı değildir. Kesin parça numarası motosiklet/yıl için ürün uyumluluk seçicisinden doğrulanmalı.",
        "evidence": "HealTech ürün sayfası ve DCT kullanım kılavuzu; model/yıl listeleri ürün uygulama listesine göre değişebilir."
    },
    "josave_tuner": {
        "name": "JOSAVE TUNER",
        "url": "https://github.com/Sirawit45/josave-tuner",
        "category": "open_source_reference",
        "status": "HONDA ECM K-LINE KODU VAR; DCT TCM PROTOKOLÜ DOĞRULANMADI",
        "functions": ["Honda ECM K-Line haberleşme araştırması", "ECM DTC okuma/silme kodu", "ECM kimliği ve flash/servis altyapısı"],
        "adapter": "Kaynak kodunda FTDI/seri port K-Line yaklaşımı anlatılıyor; donanım ve hat seviyesi uyumluluğu ayrıca doğrulanmalı.",
        "limitations": "ECM için görülen DTC komutları DCT TCM'ye kopyalanamaz. Flash/yazma işlevleri bu teşhis uygulamasında otomatik kullanılmayacak.",
        "evidence": "Açık kaynak depo ve Honda ECU tuning dokümantasyonu; fiziksel DCT TCM testi bulunamadı."
    }
}

def get_dct_tool_research():
    """DCT araçları hakkındaki çevrimdışı araştırma notlarını döndürür; ECU'ya bağlanmaz."""
    return {key: dict(value) for key, value in DCT_TOOL_REFERENCES.items()}

DCT_DIAGNOSTIC_COMPARISON = {
 "biketune": {
  "models": ["NC750S DCT (2014+)", "NC750X DCT (2014+)"],
  "transport": "Honda K-Line; STN tabanlı K-Line adaptör ve uygun Honda 4/6 pin kablo",
  "functions": ["Yanıt veren bilinen ünitelerden DTC okuma", "Pro ile DTC silme", "Motor OBD-II canlı verisi (yanıt verirse)"],
  "dct_tcu_named_in_public_docs": False, "raw_dct_reply_published": False, "known_reply_bytes": None,
  "assessment": "DCT model varyantı listeli; gerçek TCU erişimi araç üzerinde doğrulanmalı.",
  "source": "https://biketune.app/bike/honda-nc-750-cc/"
 },
 "fxtul_m3a": {
  "models": [],
  "transport": "Honda adaptör kablosu ile motosiklet teşhisi; DCT protokolü açıklanmıyor",
  "functions": ["Quick Test", "Kontrol ünitesi seçimi", "Modül bilgisi", "DTC okuma/silme", "Canlı veri", "Freeze frame", "Aktüatör testi (desteklenirse)"],
  "dct_tcu_named_in_public_docs": False, "raw_dct_reply_published": False, "known_reply_bytes": None,
  "assessment": "Genel çoklu sistem işlevleri belgeli; Honda DCT uyumluluğu kanıtlanmadı.",
  "source": "https://manuals.plus/asin/B0C2CMGFS4.pdf"
 },
 "honda_mcs": {
  "models": ["Honda DCT donanımlı motosikletler"],
  "transport": "Honda OEM MCS/VCI ve modele özgü DLC bağlantısı",
  "functions": ["DCT dahil tüm ECU'larda DTC okuma/silme", "ECU verisi izleme", "Reset/güncelleme", "Aktüasyon", "Quick Doctor"],
  "dct_tcu_named_in_public_docs": True, "raw_dct_reply_published": False, "known_reply_bytes": None,
  "assessment": "Honda'nın resmî DCT teşhis referansı; ham protokol kamuya açık değil.",
  "source": "https://global.honda/en/motorcycle-aftersales/plusone/202506.html"
 },
 "gst_xadv_2020": {
  "models": ["2020 Honda X-ADV ADV750/ADV750M"],
  "transport": "Servis kitabı GST veya MCS üzerinden DCT DTC okuma/silme diyor; protokol tanımı yok",
  "functions": ["DCT DTC okuma", "DCT DTC silme"],
  "dct_tcu_named_in_public_docs": True, "raw_dct_reply_published": False, "known_reply_bytes": None,
  "assessment": "Yalnızca bu model/yıl servis prosedürü kanıtı; her GST'ye genellenemez.",
  "source": "https://www.scribd.com/document/852109922/2020-Honda-x-Adv750-Workshop-Manual"
 }
}

def get_dct_diagnostic_comparison():
    """Kaynaklı ürün karşılaştırması; fiziksel ECU yanıtı üretmez."""
    return {name: dict(data) for name, data in DCT_DIAGNOSTIC_COMPARISON.items()}

# DCT v2 profile/capture foundation. Passive only: no ECU requests are transmitted.
DCT_PROFILE_SCHEMA_VERSION = 2
DCT_MODEL_CATALOG = {
 "VFR1200F DCT":"VFR1200","VFR1200X Crosstourer DCT":"VFR1200",
 "NC700S DCT":"NC700","NC700X DCT":"NC700","NC750S DCT":"NC750",
 "NC750X DCT":"NC750","Integra DCT":"NC platform","CTX700 DCT":"CTX700",
 "NM4 Vultus DCT":"NM4","CRF1000L Africa Twin DCT":"Africa Twin CRF1000L",
 "CRF1100L Africa Twin DCT":"Africa Twin CRF1100L","X-ADV DCT":"X-ADV",
 "Forza 750 DCT":"Forza 750","NT1100 DCT":"NT1100",
 "CMX1100 Rebel DCT":"CMX1100","Gold Wing DCT":"Gold Wing"}
DCT_CAPTURE_MAX_BYTES = 5 * 1024 * 1024

def build_dct_profile_record(model, year, transport="unknown"):
    """Create an unverified profile shell for later evidence-backed completion."""
    y=int(year)
    if y < 1980 or y > datetime.now().year+1: raise ValueError("Model yılı geçersiz.")
    if transport not in ("unknown","kline","can","proprietary"): raise ValueError("Taşıma türü geçersiz.")
    key=normalize_dct_model(model)
    if not key: raise ValueError("DCT modeli zorunlu.")
    return {"schema_version":DCT_PROFILE_SCHEMA_VERSION,
      "profile_id":"HONDA-DCT-"+key.upper().replace(" ","-")+"-"+str(y),
      "brand":"honda","model":str(model).strip(),"model_key":key,"year":y,
      "model_family":DCT_MODEL_CATALOG.get(str(model).strip(),"Tanımlanmamış Honda DCT ailesi"),
      "transport":transport,"transport_verified":False,"ecu_architecture":"unknown",
      "ecu_address":None,"can_bitrate":None,"can_tx_id":None,"can_rx_id":None,"kline_init":None,
      "diagnostic_services":[],"capabilities":{k:"not_implemented" for k in
      ("identify","read_dtcs","read_live_data","clear_dtcs","actuator_test","adaptation")},
      "evidence":[],"verified":False,
      "safety":{"active_requests_enabled":False,"write_operations_enabled":False}}

def analyze_dct_capture(file_text, filename="dct_capture.log"):
    """Parse existing TX/RX or candump logs without opening an adapter."""
    frames=[]; ignored=0
    for n,raw in enumerate(str(file_text or "").splitlines(),1):
        line=raw.strip()
        if not line or line.startswith(("#",";","//")): continue
        m=re.match(r"^(?:(\d+(?:\.\d+)?)\s+)?(TX|RX|SEND|RECV)\s*[:=]\s*([0-9A-Fa-f ]+)$",line,re.I)
        if m:
            stamp,direction,payload=m.groups()
            try:
                b=bytes.fromhex(payload)
                frames.append({"line":n,"time":float(stamp) if stamp else None,
                 "direction":"tx" if direction.lower() in ("tx","send") else "rx",
                 "data":b.hex(" ").upper(),"length":len(b),"transport":"unclassified"})
            except ValueError: ignored+=1
            continue
        m=re.match(r"^\((\d+(?:\.\d+)?)\)\s+(\S+)\s+([0-9A-Fa-f]{1,8})#([0-9A-Fa-f]*)$",line)
        if m:
            stamp,ch,cid,payload=m.groups()
            try:
                b=bytes.fromhex(payload)
                frames.append({"line":n,"time":float(stamp),"direction":"unknown","channel":ch,
                 "can_id":int(cid,16),"data":b.hex(" ").upper(),"length":len(b),"transport":"can_capture"})
            except ValueError: ignored+=1
            continue
        ignored+=1
    tx=sum(x["direction"]=="tx" for x in frames); rx=sum(x["direction"]=="rx" for x in frames)
    ids=sorted({x["can_id"] for x in frames if "can_id" in x})
    return {"ok":True,"filename":os.path.basename(filename),"frame_count":len(frames),
      "tx_count":tx,"rx_count":rx,"ignored_lines":ignored,"can_ids":[f"0x{x:X}" for x in ids],
      "frames":frames[:500],"note":"Pasif kayıt özeti; tek başına ECU kimliğini, protokolü veya komut güvenliğini doğrulamaz."}



# ==================== J2534 / SCANMATIK UYUMLU TASIMA KATMANI ====================
# Genel SAE J2534 PassThru DLL arayuzu. Cihaz modeli/klon uyumlulugu calisma aninda
# DLL yukleme ve API cagri sonucuyla kontrol edilir. Bu katman ECU'ya istek gondermez.
import ctypes
from ctypes import wintypes

J2534_PROTOCOLS = {
    "can": {"id": 5, "default_baud": 500000, "label": "CAN 2.0"},
    "iso15765": {"id": 6, "default_baud": 500000, "label": "ISO15765 / CAN"},
    "iso9141": {"id": 3, "default_baud": 10400, "label": "ISO9141 K-Line"},
    "kwp2000": {"id": 4, "default_baud": 10400, "label": "ISO14230 KWP2000"},
}
J2534_STATUS = {
    0: "STATUS_NOERROR", 1: "ERR_NOT_SUPPORTED", 2: "ERR_INVALID_CHANNEL_ID",
    3: "ERR_INVALID_PROTOCOL_ID", 4: "ERR_NULL_PARAMETER", 5: "ERR_INVALID_IOCTL_VALUE",
    6: "ERR_INVALID_FLAGS", 7: "ERR_FAILED", 8: "ERR_DEVICE_NOT_CONNECTED",
    9: "ERR_TIMEOUT", 10: "ERR_INVALID_MSG", 11: "ERR_INVALID_TIME_INTERVAL",
    12: "ERR_EXCEEDED_LIMIT", 13: "ERR_INVALID_MSG", 14: "ERR_DEVICE_IN_USE",
}
class _J2534Msg(ctypes.Structure):
    _fields_ = [("ProtocolID", ctypes.c_uint32), ("RxStatus", ctypes.c_uint32),
                ("TxFlags", ctypes.c_uint32), ("Timestamp", ctypes.c_uint32),
                ("DataSize", ctypes.c_uint32), ("ExtraDataIndex", ctypes.c_uint32),
                ("Data", ctypes.c_ubyte * 4128)]

_j2534_lock = threading.RLock()
_j2534_state = {"dll": None, "device_id": None, "channel_id": None, "filter_id": None,
                "protocol": None, "dll_path": None, "last_error": None}

def _j2534_candidates():
    paths = []
    env_path = os.environ.get("SCANMATIK_J2534_DLL") or os.environ.get("J2534_DLL")
    if env_path:
        paths.append(env_path)
    if sys.platform == "win32":
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"),
                     os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")):
            paths.extend([os.path.join(base, "Scanmatik", "Drivers", "sm2j2534.dll"),
                          os.path.join(base, "Scanmatik", "sm2j2534.dll"),
                          os.path.join(base, "Scanmatik 2", "sm2j2534.dll"),
                          os.path.join(base, "Tactrix", "Drivers", "op20pt64.dll"),
                          os.path.join(base, "Tactrix", "Drivers", "op20pt32.dll"),
                          os.path.join(base, "OpenPort 2.0", "op20pt64.dll"),
                          os.path.join(base, "OpenPort 2.0", "op20pt32.dll")])
    return list(dict.fromkeys(p for p in paths if p))

def j2534_status():
    with _j2534_lock:
        connected = _j2534_state["channel_id"] is not None
        return {"ok": True, "connected": connected,
                "protocol": _j2534_state["protocol"],
                "dll_path": _j2534_state["dll_path"],
                "device_id": _j2534_state["device_id"],
                "channel_id": _j2534_state["channel_id"],
                "filter_id": _j2534_state.get("filter_id"),
                "last_error": _j2534_state["last_error"],
                "note": "PassThru API hazirligi veya kanal acilmasi ECU kimligini/DCT-ABS uyumlulugunu kanitlamaz."}

def j2534_open_passive(protocol="can", baud=None):
    """J2534 kanalini acar; PassThruWriteMsgs veya ECU sorgusu kullanmaz."""
    protocol = str(protocol or "can").lower().strip()
    if protocol not in J2534_PROTOCOLS:
        return False, "Desteklenen protokoller: CAN, ISO15765, ISO9141, KWP2000."
    if sys.platform != "win32":
        return False, "Scanmatik J2534 DLL katmani Windows gerektirir."
    with _j2534_lock:
        if _j2534_state["channel_id"] is not None:
            return False, "J2534 kanali zaten acik. Once baglantiyi kapatin."
        dll_path = next((p for p in _j2534_candidates() if os.path.isfile(p)), None)
        if not dll_path:
            return False, "Scanmatik J2534 DLL bulunamadi. SCANMATIK_J2534_DLL ortam degiskenine DLL tam yolunu tanimlayin."
        try:
            dll = ctypes.WinDLL(dll_path)
            open_fn = dll.PassThruOpen
            open_fn.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32)]
            open_fn.restype = ctypes.c_long
            connect_fn = dll.PassThruConnect
            connect_fn.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32,
                                   ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32)]
            connect_fn.restype = ctypes.c_uint32
            close_fn = dll.PassThruClose
            close_fn.argtypes = [ctypes.c_uint32]
            close_fn.restype = ctypes.c_uint32
            disconnect_fn = dll.PassThruDisconnect
            disconnect_fn.argtypes = [ctypes.c_uint32]
            disconnect_fn.restype = ctypes.c_uint32
            filter_fn = getattr(dll, "PassThruStartMsgFilter", None)
            if filter_fn is not None:
                filter_fn.argtypes = [ctypes.c_uint32, ctypes.c_uint32,
                    ctypes.POINTER(_J2534Msg), ctypes.POINTER(_J2534Msg),
                    ctypes.POINTER(_J2534Msg), ctypes.POINTER(ctypes.c_uint32)]
                filter_fn.restype = ctypes.c_uint32
            device = ctypes.c_uint32(0)
            rc = int(open_fn(None, ctypes.byref(device)))
            if rc != 0:
                return False, f"PassThruOpen basarisiz: {J2534_STATUS.get(rc, 'J2534 hata '+str(rc))}"
            spec = J2534_PROTOCOLS[protocol]
            channel = ctypes.c_uint32(0)
            speed = int(baud or spec["default_baud"])
            rc = int(connect_fn(device.value, spec["id"], 0, speed, ctypes.byref(channel)))
            if rc != 0:
                close_fn(device.value)
                return False, f"PassThruConnect basarisiz: {J2534_STATUS.get(rc, 'J2534 hata '+str(rc))}"
            filter_id = None
            filter_note = "Alim filtresi kurulmadan kanal acildi."
            # CAN/ISO15765 icin 4-byte CAN-ID alaninda sifir maskeli PASS_FILTER
            # tum ID'leri kabul eder. Bu yalnizca RX filtresidir; ECU'ya TX yapmaz.
            if protocol in ("can", "iso15765") and filter_fn is not None:
                mask = _J2534Msg(); pattern = _J2534Msg(); fid = ctypes.c_uint32(0)
                mask.ProtocolID = spec["id"]; mask.DataSize = 4
                pattern.ProtocolID = spec["id"]; pattern.DataSize = 4
                if protocol == "iso15765":
                    for i, value in enumerate((0x00, 0x00, 0x07, 0xF8)): mask.Data[i] = value
                    for i, value in enumerate((0x00, 0x00, 0x07, 0xE8)): pattern.Data[i] = value
                rc_filter = int(filter_fn(channel.value, 1, ctypes.byref(mask),
                                          ctypes.byref(pattern), None, ctypes.byref(fid)))
                if rc_filter == 0:
                    filter_id = fid.value
                    filter_note = "Tum CAN ID'leri icin pasif RX filtresi etkin."
                else:
                    filter_note = "PASS_FILTER kurulamadi: " + J2534_STATUS.get(rc_filter, str(rc_filter))
            _j2534_state.update({"dll": dll, "device_id": device.value,
                "channel_id": channel.value, "filter_id": filter_id, "protocol": protocol,
                "dll_path": dll_path, "last_error": None})
            return True, f"J2534 kanali acildi: {spec['label']} / {speed} baud. {filter_note} ECU istegi gonderilmedi."
        except Exception as exc:
            _j2534_state["last_error"] = str(exc)
            return False, f"J2534 DLL yukleme/API hatasi: {exc}"

def j2534_initialize_kline(init_type="five_baud", target_address=0x33, source_address=0xF1):
    """J2534 ISO9141 5-baud veya ISO14230 KWP fast-init; sadece acik K-Line kanalinda."""
    with _j2534_lock:
        dll = _j2534_state.get("dll")
        channel = _j2534_state.get("channel_id")
        protocol = _j2534_state.get("protocol")
        if dll is None or channel is None:
            return {"ok": False, "error": "J2534 K-Line kanali acik degil."}
        init_type = str(init_type or "").lower().strip()
        if init_type == "five_baud":
            if protocol != "iso9141":
                return {"ok": False, "error": "5-baud init icin ISO9141 kanali acik olmali."}
            class _J2534ByteArray(ctypes.Structure):
                _fields_ = [("NumOfBytes", ctypes.c_uint32),
                            ("BytePtr", ctypes.POINTER(ctypes.c_ubyte))]
            target = ctypes.c_ubyte(int(target_address) & 0xFF)
            keys = (ctypes.c_ubyte * 2)()
            inp = _J2534ByteArray(1, ctypes.cast(ctypes.pointer(target), ctypes.POINTER(ctypes.c_ubyte)))
            out = _J2534ByteArray(2, ctypes.cast(keys, ctypes.POINTER(ctypes.c_ubyte)))
            ioctl = dll.PassThruIoctl
            ioctl.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_void_p]
            ioctl.restype = ctypes.c_long
            rc = int(ioctl(channel, 0x04, ctypes.byref(inp), ctypes.byref(out)))
            if rc != 0:
                return {"ok": False, "error": "FIVE_BAUD_INIT: " + J2534_STATUS.get(rc, str(rc))}
            response = bytes(keys[:min(int(out.NumOfBytes), 2)])
            _ecu_lab_record("J2534-ISO9141", "INIT-RX", response.hex(" ").upper(),
                            "5-baud target 0x%02X" % int(target_address))
            return {"ok": True, "init": "five_baud", "target": "0x%02X" % int(target_address),
                    "keyword_bytes": response.hex(" ").upper(), "note": "Yalnizca ISO9141 baslatma anahtar baytlari alindi; OEM servis/protokol uyumlulugu ayrica gerekir."}
        if init_type == "fast_init":
            if protocol != "kwp2000":
                return {"ok": False, "error": "Fast init icin KWP2000 / ISO14230 kanali acik olmali."}
            target = int(target_address) & 0xFF
            source = int(source_address) & 0xFF
            msg_in = _J2534Msg()
            msg_out = _J2534Msg()
            msg_in.ProtocolID = J2534_PROTOCOLS["kwp2000"]["id"]
            msg_in.TxFlags = 0
            msg_in.DataSize = 4
            for i, value in enumerate((0xC1, target, source, 0x81)):
                msg_in.Data[i] = value
            ioctl = dll.PassThruIoctl
            ioctl.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_void_p]
            ioctl.restype = ctypes.c_long
            rc = int(ioctl(channel, 0x05, ctypes.byref(msg_in), ctypes.byref(msg_out)))
            if rc != 0:
                return {"ok": False, "error": "FAST_INIT: " + J2534_STATUS.get(rc, str(rc)),
                        "request": "C1 %02X %02X 81" % (target, source)}
            response = bytes(msg_out.Data[:min(int(msg_out.DataSize), 4128)])
            _ecu_lab_record("J2534-KWP2000", "INIT-RX", response.hex(" ").upper(),
                            "FAST_INIT target 0x%02X source 0x%02X" % (target, source))
            return {"ok": True, "init": "fast_init", "request": "C1 %02X %02X 81" % (target, source),
                    "response": response.hex(" ").upper(),
                    "note": "KWP StartCommunication cevabi alindi. Bu, Honda DCT veya ABS protokolunun dogrulandigi anlamina gelmez."}
        return {"ok": False, "error": "init_type five_baud veya fast_init olmali."}


def j2534_read_passive(timeout_ms=250, max_messages=16):
    """Yalnizca hattan gelen J2534 mesajlarini okur; TX API'si cagirilmaz."""
    with _j2534_lock:
        dll, channel = _j2534_state["dll"], _j2534_state["channel_id"]
        if dll is None or channel is None:
            return {"ok": False, "error": "J2534 kanali acik degil.", "frames": []}
        try:
            read_fn = dll.PassThruReadMsgs
            read_fn.argtypes = [ctypes.c_uint32, ctypes.POINTER(_J2534Msg),
                                ctypes.POINTER(ctypes.c_uint32), ctypes.c_uint32]
            read_fn.restype = ctypes.c_long
            count = max(1, min(int(max_messages), 64))
            messages = (_J2534Msg * count)()
            received = ctypes.c_uint32(count)
            rc = int(read_fn(channel, messages, ctypes.byref(received),
                             max(1, min(int(timeout_ms), 5000))))
            if rc not in (0, 9):
                return {"ok": False, "error": J2534_STATUS.get(rc, "J2534 hata "+str(rc)), "frames": []}
            frames = []
            for msg in list(messages)[:received.value]:
                size = min(int(msg.DataSize), 4128)
                raw_hex = bytes(msg.Data[:size]).hex(" ").upper()
                frames.append({"protocol_id": int(msg.ProtocolID),
                    "rx_status": int(msg.RxStatus), "timestamp": int(msg.Timestamp),
                    "data": raw_hex, "length": size})
                _ecu_lab_record("J2534-" + str(_j2534_state.get("protocol") or "RX"),
                                "RX", raw_hex, "ProtocolID=%s Timestamp=%s" % (msg.ProtocolID, msg.Timestamp))
            return {"ok": True, "frames": frames, "count": len(frames),
                    "note": "J2534 RX kaydi; ECU istegi gonderilmedi."}
        except Exception as exc:
            _j2534_state["last_error"] = str(exc)
            return {"ok": False, "error": str(exc), "frames": []}

def j2534_send_obd_mode03(timeout_ms=2500):
    """Standart OBD-II Mode 03 istegi; yalnizca ISO15765/CAN motor emisyon ECU'su icindir."""
    with _j2534_lock:
        dll = _j2534_state.get("dll")
        channel = _j2534_state.get("channel_id")
        if dll is None or channel is None or _j2534_state.get("protocol") != "iso15765":
            return None, "J2534 ISO15765 kanali acik degil."
        try:
            write_fn = dll.PassThruWriteMsgs
            write_fn.argtypes = [ctypes.c_uint32, ctypes.POINTER(_J2534Msg),
                                 ctypes.POINTER(ctypes.c_uint32), ctypes.c_uint32]
            write_fn.restype = ctypes.c_uint32
            msg = _J2534Msg()
            msg.ProtocolID = J2534_PROTOCOLS["iso15765"]["id"]
            msg.TxFlags = 0x40  # ISO15765_FRAME_PAD
            # SAE J1979 functional request CAN ID 7DF; ISO-TP SF: length=2, service=03.
            payload = bytes((0x00, 0x00, 0x07, 0xDF, 0x02, 0x03, 0x00))
            msg.DataSize = len(payload)
            for i, value in enumerate(payload):
                msg.Data[i] = value
            count = ctypes.c_uint32(1)
            rc = int(write_fn(channel, ctypes.byref(msg), ctypes.byref(count), 1000))
            _ecu_lab_record("J2534-ISO15765", "TX", payload.hex(" ").upper(), "SAE J1979 Mode 03 functional request")
            if rc != 0 or count.value != 1:
                return None, "PassThruWriteMsgs basarisiz: " + J2534_STATUS.get(rc, str(rc))
            deadline = time.monotonic() + max(0.5, min(int(timeout_ms), 10000) / 1000.0)
            collected = []
            while time.monotonic() < deadline:
                read_result = j2534_read_passive(timeout_ms=250, max_messages=32)
                if not read_result.get("ok"):
                    if "ERR_TIMEOUT" in str(read_result.get("error")):
                        continue
                    return None, read_result.get("error", "J2534 RX hatasi")
                for frame in read_result.get("frames", []):
                    raw = bytes.fromhex(frame.get("data", "").replace(" ", ""))
                    if len(raw) < 6:
                        continue
                    # ISO15765 J2534 Data alaninin ilk dort byte'i CAN ID'dir.
                    can_id = int.from_bytes(raw[:4], "big")
                    if 0x7E8 <= can_id <= 0x7EF:
                        body = raw[4:]
                        if body and body[0] <= len(body) - 1:
                            body = body[1:1 + body[0]]
                        if body and body[0] == 0x43:
                            collected.extend(body[1:])
                if collected:
                    codes = []
                    for offset in range(0, len(collected) - 1, 2):
                        pair = bytes(collected[offset:offset + 2])
                        if pair != bytes((0, 0)):
                            codes.append((ELM327TcpConnection._decode_dtc(pair), ""))
                    return codes, "J2534 ISO15765 Mode 03: " + str(len(codes)) + " DTC"
            return None, "J2534 kanali acik ancak standart OBD-II Mode 03 yaniti alinmadi. Bu, ABS/DCT ariza taramasi degildir."
        except Exception as exc:
            _j2534_state["last_error"] = str(exc)
            return None, "J2534 Mode 03 hatasi: " + str(exc)


def j2534_send_obd_pid(pid, timeout_ms=1800):
    """SAE J1979 Mode 01 PID sorgusu; yalnizca standart motor/emisyon OBD-II CAN ECU'su."""
    pid = int(pid)
    if pid < 0 or pid > 255:
        return None, "PID 0-255 araliginda olmali."
    with _j2534_lock:
        dll = _j2534_state.get("dll")
        channel = _j2534_state.get("channel_id")
        if dll is None or channel is None or _j2534_state.get("protocol") != "iso15765":
            return None, "J2534 ISO15765 kanali acik degil."
        try:
            write_fn = dll.PassThruWriteMsgs
            write_fn.argtypes = [ctypes.c_uint32, ctypes.POINTER(_J2534Msg),
                                 ctypes.POINTER(ctypes.c_uint32), ctypes.c_uint32]
            write_fn.restype = ctypes.c_uint32
            msg = _J2534Msg()
            msg.ProtocolID = J2534_PROTOCOLS["iso15765"]["id"]
            msg.TxFlags = 0x40
            payload = bytes((0x00, 0x00, 0x07, 0xDF, 0x02, 0x01, pid))
            msg.DataSize = len(payload)
            for i, value in enumerate(payload):
                msg.Data[i] = value
            count = ctypes.c_uint32(1)
            rc = int(write_fn(channel, ctypes.byref(msg), ctypes.byref(count), 1000))
            _ecu_lab_record("J2534-ISO15765", "TX", payload.hex(" ").upper(), "SAE J1979 Mode 01 PID %02X" % pid)
            if rc != 0 or count.value != 1:
                return None, "Mode 01 PID yazma hatasi: " + J2534_STATUS.get(rc, str(rc))
            deadline = time.monotonic() + max(0.5, min(int(timeout_ms), 6000) / 1000.0)
            while time.monotonic() < deadline:
                result = j2534_read_passive(timeout_ms=200, max_messages=24)
                if not result.get("ok"):
                    if "ERR_TIMEOUT" in str(result.get("error")):
                        continue
                    return None, result.get("error", "J2534 RX hatasi")
                for frame in result.get("frames", []):
                    try:
                        raw = bytes.fromhex(frame.get("data", "").replace(" ", ""))
                    except ValueError:
                        continue
                    if len(raw) < 7:
                        continue
                    can_id = int.from_bytes(raw[:4], "big")
                    if not 0x7E8 <= can_id <= 0x7EF:
                        continue
                    body = raw[4:]
                    if body and body[0] <= len(body) - 1:
                        body = body[1:1 + body[0]]
                    if len(body) >= 3 and body[0] == 0x41 and body[1] == pid:
                        return bytes(body[2:]), "ECU 0x%03X PID %02X" % (can_id, pid)
                    if len(body) >= 3 and body[0] == 0x7F and body[1] == 0x01:
                        return None, "ECU negatif cevap verdi: " + body.hex(" ").upper()
            return None, "PID %02X icin ECU yaniti alinmadi." % pid
        except Exception as exc:
            _j2534_state["last_error"] = str(exc)
            return None, "Mode 01 PID hatasi: " + str(exc)


def j2534_read_obd_live_data():
    """Desteklenen temel SAE J1979 PID'lerini tek tek sorgular; standart OBD motor verisidir."""
    definitions = {
        0x0C: ("RPM", "rpm"), 0x0D: ("Hiz", "km/h"),
        0x05: ("Motor sogutma sicakligi", "°C"),
        0x0B: ("Emme manifoldu basinci", "kPa"),
        0x11: ("Gaz kelebegi", "%"), 0x0F: ("Emme havasi sicakligi", "°C"),
        0x42: ("Kontrol modulu voltaji", "V"), 0x06: ("Kisa yakit duzeltmesi", "%"),
        0x14: ("O2 B1S1", "V"),
    }
    values, errors = [], []
    for pid, (label, unit) in definitions.items():
        data, detail = j2534_send_obd_pid(pid)
        if not data:
            errors.append("%02X: %s" % (pid, detail))
            continue
        a = data[0]
        value = None
        if pid == 0x0C and len(data) >= 2: value = (a * 256 + data[1]) / 4.0
        elif pid == 0x0D: value = float(a)
        elif pid in (0x05, 0x0F): value = float(a - 40)
        elif pid == 0x0B: value = float(a)
        elif pid == 0x11: value = round(a * 100.0 / 255.0, 2)
        elif pid == 0x42 and len(data) >= 2: value = round((a * 256 + data[1]) / 1000.0, 3)
        elif pid == 0x06: value = round((a - 128) * 100.0 / 128.0, 2)
        elif pid == 0x14: value = round(a / 200.0, 3)
        if value is not None:
            values.append({"pid": "%02X" % pid, "name": label, "value": value, "unit": unit})
    return {"ok": bool(values), "values": values, "errors": errors,
            "scope": "standard_engine_emissions_obd_only",
            "note": "Yalnizca ECU'nun destekledigi standart OBD-II PID'leri. ABS/DCT/marka-ozel veri degildir."}


def j2534_clear_obd_mode04(timeout_ms=2500):
    """SAE J1979 Mode 04: yalnizca standart motor/emisyon OBD DTC hafizasini siler."""
    with _j2534_lock:
        dll = _j2534_state.get("dll")
        channel = _j2534_state.get("channel_id")
        if dll is None or channel is None or _j2534_state.get("protocol") != "iso15765":
            return False, "J2534 ISO15765 kanali acik degil."
        try:
            write_fn = dll.PassThruWriteMsgs
            write_fn.argtypes = [ctypes.c_uint32, ctypes.POINTER(_J2534Msg),
                                 ctypes.POINTER(ctypes.c_uint32), ctypes.c_uint32]
            write_fn.restype = ctypes.c_uint32
            msg = _J2534Msg()
            msg.ProtocolID = J2534_PROTOCOLS["iso15765"]["id"]
            msg.TxFlags = 0x40
            # SAE J1979 functional request: CAN 7DF, ISO-TP SF, service 04.
            payload = bytes((0x00, 0x00, 0x07, 0xDF, 0x01, 0x04, 0x00))
            msg.DataSize = len(payload)
            for i, value in enumerate(payload):
                msg.Data[i] = value
            count = ctypes.c_uint32(1)
            rc = int(write_fn(channel, ctypes.byref(msg), ctypes.byref(count), 1000))
            if rc != 0 or count.value != 1:
                return False, "Mode 04 yazma hatasi: " + J2534_STATUS.get(rc, str(rc))
            deadline = time.monotonic() + max(0.5, min(int(timeout_ms), 10000) / 1000.0)
            while time.monotonic() < deadline:
                read_result = j2534_read_passive(timeout_ms=250, max_messages=32)
                if not read_result.get("ok"):
                    if "ERR_TIMEOUT" in str(read_result.get("error")):
                        continue
                    return False, read_result.get("error", "J2534 RX hatasi")
                for frame in read_result.get("frames", []):
                    raw = bytes.fromhex(frame.get("data", "").replace(" ", ""))
                    if len(raw) < 6:
                        continue
                    can_id = int.from_bytes(raw[:4], "big")
                    if 0x7E8 <= can_id <= 0x7EF:
                        body = raw[4:]
                        if body and body[0] <= len(body)-1:
                            body = body[1:1+body[0]]
                        if body and body[0] == 0x44:
                            return True, "ECU 0x44 olumlu cevabi verdi. Standart OBD-II motor/emisyon DTC silme istegi kabul edildi. Arizalarin giderildigi anlamina gelmez; yeniden okuyun."
            return False, "Mode 04 istegi gonderildi ancak 0x44 olumlu ECU cevabi alinmadi. Silme basarili kabul edilmedi."
        except Exception as exc:
            _j2534_state["last_error"] = str(exc)
            return False, "J2534 Mode 04 hatasi: " + str(exc)


class J2534OBDConnection:
    """J2534 üzerinden standart motor OBD-II bağlantısı; ECU yanıtı doğrulanmadan ONLINE sayılmaz."""
    def __init__(self):
        ok, message = j2534_open_passive("iso15765")
        if not ok:
            raise ConnectionError(message)
        self._closed = False
        self.adapter_identity = os.path.basename(_j2534_state.get("dll_path") or "J2534")
        self.protocol = "ISO15765 / CAN 500 kbit/s"
        self._live_aux_index = 0
        self.supported_pids = set()
        try:
            # Kanalın açılması ECU bağlantısı değildir. Önce gerçek ECU'dan 0100 yanıtı iste.
            bitmap, detail = j2534_send_obd_pid(0x00, timeout_ms=3000)
            if bitmap is None or len(bitmap) < 4:
                raise ConnectionError("J2534 adaptörü/kanalı açıldı ancak motor ECU'su 0100 destek sorgusuna yanıt vermedi: " + str(detail))
            block_start = 1
            while True:
                for bit_index in range(32):
                    if bitmap[bit_index // 8] & (1 << (7 - (bit_index % 8))):
                        self.supported_pids.add(block_start + bit_index)
                continuation_pid = block_start + 31
                if block_start >= 61 or continuation_pid not in self.supported_pids:
                    break
                next_pid = block_start + 19
                bitmap, detail = j2534_send_obd_pid(next_pid, timeout_ms=2000)
                if bitmap is None or len(bitmap) < 4:
                    break
                block_start += 20
            write_log("J2534 ECU DOĞRULAMA",
                      "Gerçek SAE J1979 0100 yanıtı alındı; desteklenen PID sayısı: " + str(len(self.supported_pids)))
        except Exception:
            self._closed = True
            j2534_close()
            raise

    def is_connected(self):
        return (not self._closed and j2534_status().get("connected", False))

    def port_name(self):
        return "J2534: " + self.adapter_identity

    def protocol_name(self):
        return self.protocol

    def read_dtcs(self):
        return j2534_send_obd_mode03()

    @staticmethod
    def _decode_pid(pid, raw):
        if not raw:
            return None
        a = raw[0]
        if pid == 0x0C and len(raw) >= 2:
            return round((a * 256 + raw[1]) / 4.0, 2)
        if pid == 0x0D:
            return float(a)
        if pid == 0x05:
            return float(a - 40)
        if pid == 0x0B:
            return float(a)
        if pid == 0x11:
            return round(a * 100.0 / 255.0, 2)
        if pid == 0x0F:
            return float(a - 40)
        if pid == 0x42 and len(raw) >= 2:
            return round((a * 256 + raw[1]) / 1000.0, 3)
        if pid == 0x06:
            return round((a - 128) * 100.0 / 128.0, 2)
        if pid == 0x14:
            return round(a / 200.0, 3)
        return None

    def read_live_data(self):
        if not self.is_connected():
            raise ConnectionError("J2534 kanalı kapandı.")
        core = (0x0C, 0x11, 0x05, 0x0B, 0x42)
        auxiliary = (0x0D, 0x0F, 0x06, 0x14)
        selected = [pid for pid in core if pid in self.supported_pids]
        available_aux = [pid for pid in auxiliary if pid in self.supported_pids]
        if available_aux:
            selected.append(available_aux[self._live_aux_index % len(available_aux)])
            self._live_aux_index = (self._live_aux_index + 1) % len(available_aux)
        result = {}
        labels = {0x0C: "rpm", 0x0D: "speed", 0x05: "temp", 0x0B: "map",
                  0x11: "tps", 0x0F: "iat", 0x42: "volt", 0x06: "stft", 0x14: "o2"}
        for pid in selected:
            raw, detail = j2534_send_obd_pid(pid, timeout_ms=700)
            if raw is None:
                if any(code in str(detail) for code in ("ERR_DEVICE_NOT_CONNECTED", "ERR_INVALID_CHANNEL_ID")):
                    raise ConnectionError("J2534 adaptör/kanal bağlantısı koptu: " + str(detail))
                continue
            value = self._decode_pid(pid, raw)
            if value is not None:
                result[labels[pid]] = value
        if not result:
            # Hiç PID verisi gelmediyse ECU'nun hâlâ cevap verip vermediğini tekrar doğrula.
            ping, detail = j2534_send_obd_pid(0x00, timeout_ms=700)
            if ping is None:
                raise ConnectionError("J2534 kanalı açık görünüyor ancak ECU artık OBD sorgusuna yanıt vermiyor: " + str(detail))
        return result

    def close(self):
        if not self._closed:
            self._closed = True
            j2534_close()
def j2534_close():
    with _j2534_lock:
        dll, channel, device, filter_id = (_j2534_state["dll"], _j2534_state["channel_id"],
                                _j2534_state["device_id"], _j2534_state.get("filter_id"))
        if dll is None:
            return True, "J2534 zaten kapali."
        errors = []
        try:
            if channel is not None and filter_id is not None and hasattr(dll, "PassThruStopMsgFilter"):
                stop_filter = dll.PassThruStopMsgFilter
                stop_filter.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
                stop_filter.restype = ctypes.c_uint32
                rc_filter = int(stop_filter(ctypes.c_uint32(channel), ctypes.c_uint32(filter_id)))
                if rc_filter != 0: errors.append("StopMsgFilter: "+str(rc_filter))
        except Exception as exc: errors.append("Filter: "+str(exc))
        try:
            if channel is not None:
                rc = int(dll.PassThruDisconnect(ctypes.c_uint32(channel)))
                if rc != 0: errors.append("Disconnect: "+str(rc))
        except Exception as exc: errors.append(str(exc))
        try:
            if device is not None:
                rc = int(dll.PassThruClose(ctypes.c_uint32(device)))
                if rc != 0: errors.append("Close: "+str(rc))
        except Exception as exc: errors.append(str(exc))
        _j2534_state.update({"dll": None, "device_id": None, "channel_id": None, "filter_id": None,
                             "protocol": None, "dll_path": None})
        return not errors, ("J2534 kapatildi." if not errors else "Kapatma uyarisi: "+"; ".join(errors))

# ==================== ABS PROFIL TAMAMLAMA / GUVENLIK KONTROLU ====================
ABS_PROFILE_SCHEMA_VERSION = 2
ABS_SUPPORTED_TRANSPORTS = ("unknown", "can", "iso15765", "kline", "iso9141", "kwp2000", "proprietary")

def build_abs_profile_shell(brand, model, year, transport="unknown"):
    """Model/yil icin kanit bekleyen ABS profili; aktif islevleri acmaz."""
    key = abs_profile_key(brand, model, year)
    transport = str(transport or "unknown").lower().strip()
    if transport not in ABS_SUPPORTED_TRANSPORTS:
        raise ValueError("ABS tasima turu gecersiz.")
    b, m, y = key
    return {"schema_version": ABS_PROFILE_SCHEMA_VERSION,
        "profile_id": "ABS-"+b.upper().replace(" ","-")+"-"+m.upper().replace(" ","-")+"-"+str(y),
        "brand": str(brand).strip(), "model": str(model).strip(), "year": y,
        "transport": transport, "transport_verified": False, "protocol_verified": False,
        "connector": None, "pinout": None, "bitrate": None, "tx_id": None, "rx_id": None,
        "ecu_identity": None, "dtc_format": None, "evidence": [],
        "capabilities": {k: "not_implemented" for k in ABS_SAFETY_CAPABILITIES},
        "verified": False, "safety": {"active_requests_enabled": False,
        "clear_dtcs_enabled": False, "actuator_tests_enabled": False,
        "write_operations_enabled": False}}

def abs_preflight_check(battery_voltage=None, ignition_on=False, wheel_stationary=True,
                        hydraulic_work=False):
    """Servis on-kontrolu; fren hidroligini calistirmaz veya aktue etmez."""
    checks = []
    try:
        v = float(battery_voltage) if battery_voltage not in (None, "") else None
    except (TypeError, ValueError):
        v = None
    checks.append({"item": "Akü gerilimi", "ok": v is not None and v >= 12.8,
                   "detail": (f"{v:.2f} V" if v is not None else "Ölçüm girilmedi; 12.8 V ve üzeri hedeflenir.")})
    checks.append({"item": "Kontak", "ok": bool(ignition_on), "detail": "Açık" if ignition_on else "Kontak açık olarak doğrulanmadı."})
    checks.append({"item": "Tekerlekler", "ok": bool(wheel_stationary), "detail": "Araç sabit" if wheel_stationary else "Araç hareket ediyor; ABS teşhis işlemi başlatılmamalı."})
    checks.append({"item": "Hidrolik işlem", "ok": not bool(hydraulic_work),
                   "detail": "Yalnızca elektronik teşhis" if not hydraulic_work else "Hidrolik hava alma/aktüatör işlemi bu yazılımda desteklenmez."})
    passed = all(x["ok"] for x in checks)
    return {"ok": True, "ready": passed, "checks": checks,
            "note": "Bu kontrol yalnızca ön koşul listesidir; ECU bağlantısı veya fren sistemi güvenliğini onaylamaz."}


# ABS islem katmani: tum markalar icin ortak is akis semasi.
# Gercek ECU istekleri yalnizca model/yil/ECU ve adaptör bazinda OEM kanitli
# profil eklendiginde etkinlestirilebilir. Burada tahmini servis byte'i yoktur.
ABS_OPERATION_CATALOG = {
    "identify": {"label": "ABS ECU kimlik bilgisi", "kind": "read", "safety": "low"},
    "read_dtcs": {"label": "ABS arıza kodlarını oku", "kind": "read", "safety": "low"},
    "read_live_data": {"label": "ABS canlı verileri", "kind": "read", "safety": "low"},
    "read_freeze_frame": {"label": "Arıza anı / geçmiş verisi", "kind": "read", "safety": "low"},
    "clear_dtcs": {"label": "ABS arıza hafızasını sil", "kind": "write", "safety": "medium"},
    "wheel_sensor_check": {"label": "Tekerlek hız sensörü kontrolü", "kind": "guided", "safety": "medium"},
    "actuator_test": {"label": "Pompa / valf aktüatör testi", "kind": "active", "safety": "high"},
    "service_bleed": {"label": "ABS hidrolik hava alma", "kind": "active", "safety": "critical"},
    "calibration": {"label": "ABS/IMU kalibrasyonu", "kind": "active", "safety": "high"},
}
ABS_OPERATION_EVIDENCE_REQUIREMENTS = {
    "identify": ["exact_vehicle", "ecu_addressing", "transport", "adapter_validation"],
    "read_dtcs": ["exact_vehicle", "ecu_addressing", "dtc_service", "response_decode"],
    "read_live_data": ["exact_vehicle", "ecu_addressing", "pid_map", "scaling"],
    "read_freeze_frame": ["exact_vehicle", "ecu_addressing", "freeze_frame_format"],
    "clear_dtcs": ["exact_vehicle", "clear_service", "pre_clear_record", "adapter_validation"],
    "wheel_sensor_check": ["exact_vehicle", "oem_procedure", "safety_conditions"],
    "actuator_test": ["exact_vehicle", "oem_actuator_sequence", "safety_conditions", "adapter_validation"],
    "service_bleed": ["exact_vehicle", "oem_bleed_procedure", "hydraulic_safety", "adapter_validation"],
    "calibration": ["exact_vehicle", "oem_calibration_procedure", "safety_conditions", "adapter_validation"],
}
def abs_operation_matrix(brand, model, year):
    """Her ABS işlevinin gerçek kapsam durumunu açıkça döndürür; ECU'ya erişmez."""
    key = abs_profile_key(brand, model, year)
    profile = ABS_PROFILE_REGISTRY.get(key) or {}
    capabilities = profile.get("capabilities", {})
    rows = []
    for op, meta in ABS_OPERATION_CATALOG.items():
        raw = capabilities.get(op, capabilities.get({
            "identify": "read_ecu_id",
            "read_freeze_frame": "read_freeze_frame",
            "wheel_sensor_check": "wheel_sensor_check",
            "service_bleed": "service_bleed",
            "calibration": "calibration",
        }.get(op, op), "not_implemented"))
        enabled = (profile.get("verified") is True and
                   raw in (True, "verified", "enabled") and
                   bool(profile.get("evidence")) and
                   bool(profile.get("protocol")) and
                   bool(profile.get("transport")))
        rows.append({"operation": op, "label": meta["label"], "kind": meta["kind"],
                     "safety": meta["safety"], "status": "verified_profile_ready" if enabled else "profile_required",
                     "enabled": enabled,
                     "required_evidence": ABS_OPERATION_EVIDENCE_REQUIREMENTS[op]})
    return {"ok": True, "brand": brand, "model": model, "year": int(year),
            "profile_id": profile.get("profile_id"),
            "operations": rows,
            "message": "Bu liste işlev kapsamını gösterir. Profil doğrulaması gerçek ECU bağlantısı anlamına gelmez."}

def abs_operation_gate(brand, model, year, operation, confirmation=None):
    """Komut kapısı: doğrulanmamış profil ve tehlikeli testler kesin olarak reddedilir."""
    if operation not in ABS_OPERATION_CATALOG:
        raise ValueError("Bilinmeyen ABS işlemi.")
    matrix = abs_operation_matrix(brand, model, year)
    item = next(x for x in matrix["operations"] if x["operation"] == operation)
    if not item["enabled"]:
        return {"ok": False, "blocked": True, "operation": operation,
                "message": "Bu motosiklet/yıl için OEM kaynaklı ve adaptörle doğrulanmış ABS işlem profili yok. Komut gönderilmedi."}
    if item["safety"] in ("high", "critical"):
        return {"ok": False, "blocked": True, "operation": operation,
                "message": "Aktif fren/kalibrasyon işlemi bu sürümde uzaktan çalıştırılmaz. OEM servis prosedürü ve kontrollü servis düzeneği gerekir."}
    if operation == "clear_dtcs" and confirmation != "CLEAR_ABS_DTC_CONFIRMED":
        return {"ok": False, "blocked": True, "operation": operation,
                "message": "Silme öncesi arıza raporu alınmalı ve açık kullanıcı onayı verilmelidir. Komut gönderilmedi."}
    return {"ok": False, "blocked": True, "operation": operation,
            "message": "Profil kayıtlı olsa bile bu sürümde gerçek ABS taşıma sürücüsü/servis uygulaması tamamlanmadan komut gönderilmez."}


def decode_abs_dtc_record(brand, model, year, code):
    key = abs_profile_key(brand, model, year)
    token = re.sub(r"\s+", "", str(code or "").strip().upper())
    if not token:
        raise ValueError("ABS ariza kodunu girin.")
    entry = ABS_DTC_DATABASE.get(key + (token,))
    if not entry or entry.get("verified") is not True:
        return {"ok": True, "found": False, "code": token,
                "description": "Bu marka/model/yil icin kaynakla dogrulanmis ABS kod aciklamasi bulunamadi.",
                "source": None}
    return {"ok": True, "found": True, "code": token,
            "description": entry["description_tr"], "source": entry.get("source")}


# ==================== EVRENSEL ABS PROFİL ALTYAPISI ====================
# Protokol/soket/model yılı evrensel değildir. Yalnızca OEM kanıtlı profiller
# gelecekte gerçek ECU sorgularını etkinleştirebilir.
ABS_PROFILE_SCHEMA_VERSION = 2
ABS_PROFILE_REGISTRY = {}  # (marka, normalize_model, yil) -> doğrulanmış profil
ABS_DTC_DATABASE = {}      # (marka, normalize_model, yil, DTC) -> doğrulanmış açıklama

# Kaynak: Yamaha XMAX 300 CZD300-A (2019) servis el kitabı,
# ABS arıza teşhis bölümü, sayfa 8-85–8-110. Bu kodlar başka model/yıllara
# genellenmez. Haberleşme/ECU erişim profili değildir; yalnızca kod açıklamasıdır.
_XMAX300_2019_ABS_CODES = {
    "11": "Ön tekerlek hız sensörü sinyalinde anormallik.",
    "12": "Arka tekerlek hız sensörü sinyalinde anormallik.",
    "13": "Ön tekerlek sensörü darbe periyodu anormal.",
    "14": "Arka tekerlek sensörü darbe periyodu anormal.",
    "15": "Ön tekerlek hız sensörü devresinde açık devre veya kısa devre.",
    "16": "Arka tekerlek hız sensörü devresinde açık devre veya kısa devre.",
    "17": "Ön tekerlek sensöründen beklenen darbe sinyali alınamıyor.",
    "18": "Arka tekerlek sensöründen beklenen darbe sinyali alınamıyor.",
    "21": "ABS solenoid sürücü devresinde anormallik.",
    "24": "Fren müşürü / fren lambası sinyalinde anormallik.",
    "25": "Ön tekerlek hız sensörü sinyalinde anormallik.",
    "31": "Solenoid besleme devresinde anormallik.",
    "32": "Solenoid besleme devresinde anormallik.",
    "33": "ABS motor besleme devresinde anormallik.",
    "34": "ABS motor besleme devresinde anormallik.",
    "41": "Ön tekerlek ABS kontrol davranışında anormallik.",
    "42": "Arka tekerlek ABS kontrol davranışında anormallik.",
    "43": "Ön tekerlek sensöründe eksik darbe algılandı.",
    "44": "Arka tekerlek sensöründe eksik darbe algılandı.",
    "45": "Ön tekerlek sensöründe düşük hızda eksik darbe algılandı.",
    "46": "Arka tekerlek sensöründe düşük hızda eksik darbe algılandı.",
    "47": "Arka tekerlek ABS kontrol davranışında anormallik.",
    "51": "ABS ECU besleme gerilimi yüksek.",
    "52": "Tekerlek sensörü besleme gerilimi yüksek.",
    "53": "ABS ECU besleme gerilimi düşük.",
    "54": "Solenoid / ABS motor beslemesinde anormallik.",
    "55": "ABS hidrolik ünitesinden gelen veride anormallik.",
    "56": "ABS hidrolik ünitesi iç besleme devresinde anormallik.",
    "63": "Ön tekerlek sensörü besleme gerilimi düşük.",
    "64": "Arka tekerlek sensörü besleme gerilimi düşük.",
}
for _code, _description in _XMAX300_2019_ABS_CODES.items():
    ABS_DTC_DATABASE[("yamaha", "xmax 300", 2019, _code)] = {
        "verified": True,
        "description_tr": _description,
        "source": "Yamaha XMAX 300 CZD300-A 2019 servis el kitabı, ABS teşhis bölümü (8-85–8-110)",
    }
ABS_BRANDS = ("yamaha", "honda", "kawasaki", "suzuki", "bmw motorrad", "ktm",
              "ducati", "triumph", "cf moto", "aprilia", "benelli",
              "royal enfield", "harley-davidson", "diğer")
ABS_PROTOCOL_FAMILIES = {
    "uds_can": "UDS / ISO-TP over CAN (araç özel adresleme gerekir)",
    "kwp_kline": "KWP2000 / ISO 14230 over K-Line (başlatma ve adresleme gerekir)",
    "iso9141": "ISO 9141-2 (araç özel pin/prosedür gerekir)",
    "proprietary_can": "Üreticiye özel CAN (tanımlı servis profili gerekir)",
    "proprietary": "Üreticiye özel teşhis protokolü",
}
ABS_SAFETY_CAPABILITIES = ("read_dtcs", "read_live_data", "clear_dtcs", "actuator_test")

def normalize_abs_text(value):
    import unicodedata
    value = str(value or "").strip().casefold()
    value = "".join(ch for ch in unicodedata.normalize("NFKD", value) if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()

def abs_profile_key(brand, model, year):
    try:
        model_year = int(year)
    except (TypeError, ValueError):
        raise ValueError("Geçerli bir model yılı girin.")
    brand_key, model_key = normalize_abs_text(brand), normalize_abs_text(model)
    if not brand_key or not model_key:
        raise ValueError("ABS profili için marka ve model zorunludur.")
    if model_year < 1980 or model_year > datetime.now().year + 1:
        raise ValueError("Model yılı geçerli aralıkta değil.")
    return brand_key, model_key, model_year

def get_abs_hardware_evidence(brand, model, year):
    """OEM belgesinde açıkça bulunan fiziksel bilgileri verir; bağlantı profili değildir."""
    try:
        key = abs_profile_key(brand, model, year)
    except ValueError:
        return None
    if key == ("yamaha", "xmax 300", 2019):
        return {
            "title": "Yamaha XMAX 300 CZD300-A · 2019",
            "connector": "ABS için ayrı 4 pinli test soketi bulunduğu servis el kitabında gösteriliyor.",
            "can_h": "CAN-H: Mavi/Kırmızı kablo",
            "can_l": "CAN-L: Mavi/Siyah kablo",
            "unknown": "Soket pin numarası, CAN bit hızı, CAN kimlikleri ve teşhis adreslemesi bu belgede doğrulanmadı.",
            "next_step": "Pin numarası tahmin etmeyin. Uygun ara kablo ve donanımın gerçek listen-only modu doğrulanmadan hatta bağlanmayın.",
            "source": "Yamaha XMAX 300 CZD300-A 2019 servis el kitabı, bağlantı konumları 8-83 ve ABS devre açıklaması 8-87–8-88.",
            "verified": True,
        }
    return None


def evaluate_abs_profile(brand, model, year, connection=None):
    """Yerel kapsama kontrolü; hiçbir adaptör/ECU komutu göndermez."""
    key = abs_profile_key(brand, model, year)
    profile = ABS_PROFILE_REGISTRY.get(key)
    adapter_name = "Bağlantı yok"
    if connection is not None:
        adapter_name = ("ELM327 Wi-Fi / standart motor OBD bağlantısı"
                        if connection.__class__.__name__ == "ELM327TcpConnection"
                        else "USB/COM / python-OBD motor OBD bağlantısı")
    if not profile:
        return {"ok": True, "verified": False, "status": "PROFİL DOĞRULANMADI",
                "profile_id": None, "protocol": "Bilinmiyor", "adapter": adapter_name,
                "capabilities": [],
                "message": (f"{brand} {model} {year} için doğrulanmış ABS ECU profili henüz kayıtlı değil. "
                            "Soket tipi veya motor ECU bağlantısı ABS erişimini kanıtlamaz. "
                            "Bu nedenle ABS'ye hiçbir komut gönderilmedi.")}
    required = ("profile_id", "protocol", "evidence", "transport")
    missing = [field for field in required if not profile.get(field)]
    if missing or profile.get("verified") is not True:
        return {"ok": True, "verified": False, "status": "PROFİL EKSİK / DOĞRULANMAMIŞ",
                "profile_id": profile.get("profile_id"), "protocol": profile.get("protocol", "Bilinmiyor"),
                "adapter": adapter_name, "capabilities": [],
                "message": "ABS profili doğrulama için eksik alan içeriyor: " + ", ".join(missing or ["verified"])}
    allowed = [cap for cap in profile.get("capabilities", []) if cap in ABS_SAFETY_CAPABILITIES]
    return {"ok": True, "verified": True, "status": "PROFİL KAYITLI · TAŞIMA UYUMLULUĞU BEKLENİYOR",
            "profile_id": profile["profile_id"],
            "protocol": ABS_PROTOCOL_FAMILIES.get(profile["protocol"], profile["protocol"]),
            "adapter": adapter_name, "capabilities": allowed,
            "message": "Profil kaydı mevcut. Gerçek bağlantı için fiziksel arayüz, pinout, adresleme ve ECU yanıtı ayrıca doğrulanmalıdır. Bu kontrol bağlantı kurmaz."}

def _can_id_from_text(value):
    token = str(value or "").strip().lower()
    if not token:
        raise ValueError("CAN ID boş.")
    return int(token[2:], 16) if token.startswith("0x") else int(token, 16)


def analyze_abs_can_log(file_text, filename="can_log.txt"):
    """Önceden kaydedilmiş CAN günlüklerini inceler; CAN hattına bağlanmaz."""
    import csv
    import io
    import statistics
    frames, ignored = [], 0
    for raw_line in str(file_text or "").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("//", ";", "#")):
            continue
        try:
            match = re.match(r"^\((\d+(?:\.\d+)?)\)\s+(\S+)\s+([0-9A-Fa-f]{1,8})#(?:#)?([0-9A-Fa-f]*)", line)
            if match:
                stamp, channel, can_id, payload = match.groups()
                if len(payload) % 2:
                    ignored += 1
                    continue
                frames.append({"t": float(stamp), "channel": channel, "id": _can_id_from_text(can_id), "data": bytes.fromhex(payload)})
                continue
            match = re.match(r"^(\d+(?:\.\d+)?)\s+\d+\s+([0-9A-Fa-f]{1,8})\s+(Rx|Tx)\s+d\s+(\d+)\s+((?:[0-9A-Fa-f]{2}\s*)*)", line, re.IGNORECASE)
            if match:
                stamp, can_id, direction, dlc, payload = match.groups()
                if direction.lower() != "rx":
                    ignored += 1
                    continue
                data, length = bytes.fromhex(payload), int(dlc)
                if length > 8 or len(data) < length:
                    ignored += 1
                    continue
                frames.append({"t": float(stamp), "channel": "ASC", "id": _can_id_from_text(can_id), "data": data[:length]})
        except (ValueError, TypeError):
            ignored += 1

    if not frames and str(filename).lower().endswith(".csv"):
        try:
            sample = str(file_text or "")[:4096]
            dialect = csv.Sniffer().sniff(sample, delimiters=",;\t")
            reader = csv.DictReader(io.StringIO(str(file_text or "")), dialect=dialect)
            if reader.fieldnames:
                def norm(name):
                    return re.sub(r"[^a-z0-9]+", "", str(name or "").lower())
                cols = {norm(name): name for name in reader.fieldnames}
                time_col = next((cols[x] for x in ("timestamp", "time", "absolutetime", "seconds") if x in cols), None)
                id_col = next((cols[x] for x in ("canid", "id", "arbitrationid", "frameid") if x in cols), None)
                data_col = next((cols[x] for x in ("data", "payload", "databytes", "bytes") if x in cols), None)
                dlc_col = next((cols[x] for x in ("dlc", "length") if x in cols), None)
                bus_col = next((cols[x] for x in ("bus", "channel") if x in cols), None)
                if not id_col or not data_col:
                    raise ValueError("CSV başlıklarında CAN ID ve Data/Payload sütunları bulunamadı.")
                for row in reader:
                    try:
                        stamp = float(row.get(time_col, 0) or 0) if time_col else float(len(frames))
                        octets = re.findall(r"(?i)(?<![0-9a-f])[0-9a-f]{2}(?![0-9a-f])", str(row.get(data_col, "") or ""))
                        data = bytes.fromhex("".join(octets))
                        length = int(row.get(dlc_col) or len(data)) if dlc_col else len(data)
                        if length < 0 or length > 64 or len(data) < length:
                            ignored += 1
                            continue
                        frames.append({"t": stamp, "channel": str(row.get(bus_col, "CSV")) if bus_col else "CSV",
                                       "id": _can_id_from_text(row.get(id_col)), "data": data[:length]})
                    except (ValueError, TypeError):
                        ignored += 1
        except csv.Error as exc:
            raise ValueError("CSV biçimi okunamadı: " + str(exc)) from exc

    if not frames:
        raise ValueError("Desteklenen CAN satırı bulunamadı. candump, Vector ASC RX veya başlıklı CAN CSV dosyası seçin.")
    frames.sort(key=lambda item: item["t"])
    by_id, previous, periods, byte_values = {}, {}, {}, {}
    for frame in frames:
        can_id = frame["id"]
        entry = by_id.setdefault(can_id, {"count": 0, "lengths": set()})
        entry["count"] += 1
        entry["lengths"].add(len(frame["data"]))
        if can_id in previous:
            delta = frame["t"] - previous[can_id]
            if delta >= 0:
                periods.setdefault(can_id, []).append(delta)
        previous[can_id] = frame["t"]
        values = byte_values.setdefault(can_id, {})
        for index, value in enumerate(frame["data"]):
            values.setdefault(index, set()).add(value)
    summary = []
    for can_id, entry in sorted(by_id.items(), key=lambda item: (-item[1]["count"], item[0])):
        deltas = periods.get(can_id, [])
        summary.append({
            "id": f"{can_id:03X}" if can_id <= 0x7FF else f"{can_id:08X}",
            "count": entry["count"],
            "period_ms": round(statistics.mean(deltas) * 1000, 2) if deltas else None,
            "min_period_ms": round(min(deltas) * 1000, 2) if deltas else None,
            "max_period_ms": round(max(deltas) * 1000, 2) if deltas else None,
            "dlc": ", ".join(str(x) for x in sorted(entry["lengths"])),
            "changing_bytes": [i for i, vals in sorted(byte_values[can_id].items()) if len(vals) > 1],
        })
    return {
        "filename": os.path.basename(str(filename)), "frame_count": len(frames),
        "unique_ids": len(by_id), "ignored_lines": ignored,
        "duration_s": round(max(x["t"] for x in frames) - min(x["t"] for x in frames), 3),
        "ids": summary[:40],
        "note": "Bu çıktı ham CAN çerçeve istatistiğidir; ABS sinyali/DTC çözümü değildir. Hiçbir CAN mesajı gönderilmedi.",
    }


def decode_abs_dtc(brand, model, year, code):
    """ABS DTC çözümü motor ECU DTC sözlüğünden tamamen ayrıdır."""
    key = abs_profile_key(brand, model, year)
    entry = ABS_DTC_DATABASE.get(key + (str(code or "").strip().upper(),))
    if not entry or not entry.get("verified"):
        return "Bu araç profili için doğrulanmış ABS arıza kodu açıklaması bulunamadı."
    return entry.get("description_tr") or "Doğrulanmış açıklama mevcut değil."

# ==================== SERVİS LOG VE AKILLI TEŞHİS ====================
SERVICE_INFO = {"musteri": "", "plaka": "", "sikayet": ""}
LOG_FILE = "Servis_Loglari.txt"

def write_log(action, details):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    header = f"\n{'='*50}\nTARİH: {now} | İŞLEM: {action}\nMÜŞTERİ: {SERVICE_INFO['musteri']} | PLAKA: {SERVICE_INFO['plaka']}\nŞİKAYET: {SERVICE_INFO['sikayet']}\n{'-'*50}\n"
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f: f.write(header + details + "\n")
    except: pass

def akilli_teshis(dtc_codes, live_data):
    """Yalnizca bilinen DTC anlamlarini ve mevcut canli veriyi kullanir."""
    live_data = live_data if isinstance(live_data, dict) else {}
    codes = {str(code).strip().upper() for code in (dtc_codes or []) if str(code).strip()}
    teshisler = []
    if "P0171" in codes:
        details = []
        try:
            stft = live_data.get("stft")
            if stft is not None and float(stft) > 15:
                details.append("STFT yuksek; zayif karisim egilimini destekliyor")
            elif stft is not None and float(stft) < -15:
                details.append("STFT negatif; ayri incelenmeli")
        except (TypeError, ValueError):
            pass
        try:
            map_val = live_data.get("map")
            if map_val is not None:
                details.append("olculen emme basinci: {} kPa".format(float(map_val)))
        except (TypeError, ValueError):
            pass
        suffix = (" Canli veri: " + "; ".join(details) + ".") if details else ""
        teshisler.append("P0171 — Sistem cok fakir (Bank 1). Olası nedenler: emme/vakum kacagi, yakit beslemesi veya egzoz kacagi. Hortumlari, yakit basincini ve egzoz sizdirmazligini olcerek kontrol edin." + suffix)
    misfire_codes = sorted(code for code in codes if code in {"P0300", "P0301", "P0302", "P0303", "P0304"})
    for code in misfire_codes:
        meaning = "rastgele/coklu silindir teklemesi" if code == "P0300" else "silindir {} teklemesi".format(code[-1])
        teshisler.append("{} — {}. Bujiyi, bobin/buji basligini, enjektoru ve kompresyonu kontrol edin; kod tek basina parca degisimini kanitlamaz.".format(code, meaning))
    if "P0511" in codes:
        teshisler.append("P0511 — Rolanti hava kontrol devresi arizasi. Kablo/soket, aracta mevcutsa rolanti hava kontrol valfi ve gaz kelebegi hava kanalini servis prosedurune gore kontrol edin. Bu kod TPS yuzdesiyle tek basina teshis edilmez.")
    if "P0335" in codes:
        teshisler.append("P0335 — Krank mili konum sensoru A devresi/sinyali. Sensor soketi ve kablosu, sensor montaji, tetik carki ve mars sirasinda devir sinyalini kontrol edin. Kodu silmek arizayi onarmaz.")
    known = {"P0171", "P0511", "P0335", "P0300", "P0301", "P0302", "P0303", "P0304"}
    for code in sorted(codes - known):
        teshisler.append("{} — Bu kod icin dogrulanmis aciklama kutuphanede yok. Marka/model ECU servis dokumanina gore dogrulayin; tahmini parca onerisi verilmedi.".format(code))
    return teshisler

def anomali_kontrol(data):
    """Gerçek ECU PID'lerinden ihtiyatlı, açıklanabilir canlı uzman sistemi uyarıları üretir."""
    uyarilar = []
    def number(key):
        try:
            value = float(data.get(key))
            return value if value == value and abs(value) != float("inf") else None
        except (TypeError, ValueError):
            return None

    temp = number('temp')
    volt = number('volt')
    stft = number('stft')
    rpm = number('rpm')
    tps = number('tps')
    map_kpa = number('map')

    if temp is not None:
        if temp >= 115:
            uyarilar.append(f"ÇOK KRİTİK HARARET: Motor sıcaklığı {temp:.1f} derece. Güvenli yerde durun, motoru zorlamayın ve soğumasını bekleyin. Soğutma sistemi kontrol edilmeli.")
        elif temp > 105:
            uyarilar.append(f"HARARET UYARISI: Motor sıcaklığı {temp:.1f} derece. Fan, radyatör, soğutma sıvısı ve termostatı kontrol edin.")
        elif temp < -35 or temp > 150:
            uyarilar.append(f"SICAKLIK SİNYALİ ŞÜPHELİ: ECU {temp:.1f} derece bildirdi. Sensör ve kablo bağlantısını doğrulayın.")

    if volt is not None:
        if 0 < volt < 11.8:
            uyarilar.append(f"DÜŞÜK BESLEME VOLTAJI: {volt:.2f} volt. Akü, kutup başları ve şarj sistemi ölçülmeli.")
        elif volt > 15.0:
            uyarilar.append(f"YÜKSEK ŞARJ VOLTAJI: {volt:.2f} volt. Regülatör/doğrultucu ve şarj sistemi kontrol edilmeli; elektronik bileşenler risk altında olabilir.")

    if stft is not None:
        if stft > 25:
            uyarilar.append(f"YAKIT DÜZELTMESİ POZİTİF: STFT yüzde {stft:.1f}. Fakir karışım eğilimi var; emme kaçağı, yakıt beslemesi ve egzoz kaçağı ölçümle kontrol edilmeli.")
        elif stft < -20:
            uyarilar.append(f"YAKIT DÜZELTMESİ NEGATİF: STFT yüzde {stft:.1f}. Zengin karışım eğilimi var; enjektör, yakıt basıncı ve sensör verileri doğrulanmalı.")

    if tps is not None and not 0 <= tps <= 100:
        uyarilar.append(f"TPS VERİSİ SINIR DIŞI: ECU gaz kelebeğini yüzde {tps:.1f} bildirdi. PID ölçeği ve sensör sinyali doğrulanmalı.")

    if map_kpa is not None and not 0 <= map_kpa <= 255:
        uyarilar.append(f"MAP VERİSİ SINIR DIŞI: ECU emme basıncını {map_kpa:.1f} kPa bildirdi. PID ölçeği ve sensör sinyali doğrulanmalı.")

    return uyarilar

def risk_skoru_hesapla(data):
    score = 0
    if data.get('temp', 0) > 95: score += 30
    if data.get('volt', 12) < 12.0: score += 20
    if abs(data.get('stft', 0)) > 15: score += 25
    if data.get('tps', 0) > 10 and data.get('rpm', 0) < 1500: score += 25
    return min(100, max(0, score))

# ==================== GENİŞLETİLMİŞ ARAÇ YAPILANDIRMASI ====================
ARAC_CONFIG = {"marka": "yamaha", "model": "r25", "yil": 2020, "konnektor": "auto", "protokol": "can", "pin_sema": "", "notlar": ""}

def arac_konfigurasyonu_hesapla(marka, model, yil, konnektor, manuel_model=""):
    """Araç seçimini kaydeder; doğrulanmamış pin/protokol varsayımı üretmez."""
    selected_model = manuel_model.strip() if model == "diger" and manuel_model.strip() else model
    return {
        "marka": str(marka).lower().strip(),
        "model": str(selected_model).lower().strip(),
        "yil": int(yil),
        "konnektor": str(konnektor).lower().strip(),
        "protokol": "auto",
        "pin_sema": "",
        "notlar": (
            "Model seçimi kaydedildi. Protokol, ELM327 adaptörünün ATSP0 otomatik keşfi "
            "ve ECU'dan alınan gerçek yanıtla belirlenir. Soket tipi tek başına pinout/"
            "protokol kanıtı değildir. Üreticiye özel ECU/ABS işlemleri doğrulanmış "
            "model profili olmadan etkinleştirilmez."
        ),
    }



# ==================== ORTAK ECU / ABS PASIF CAN COZUMLEYICI ====================
# Bu katman motor ECU ve ABS ECU icin ayni CAN/ISO-TP gozlem motorunu kullanir.
# Yalnizca kullanicinin topladigi RX kaydini analiz eder; CAN TX yapmaz.
ECU_CAPTURE_MAX_FRAMES = 20000
ECU_UDS_POSITIVE_SERVICES = {0x50, 0x51, 0x59, 0x62, 0x67, 0x6E, 0x6F, 0x71, 0x74, 0x76, 0x77}
ECU_OBD_POSITIVE_SERVICES = {0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E}

def _ecu_hex_bytes(value):
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    if isinstance(value, list):
        try:
            return bytes(int(x) & 0xFF for x in value)
        except Exception:
            return b""
    raw = str(value or "").strip().replace(",", " ").replace("-", " ")
    try:
        return bytes(int(x, 16) for x in raw.split())
    except Exception:
        return b""

def _ecu_classify_payload(payload):
    if not payload:
        return None
    sid = payload[0]
    if sid in ECU_OBD_POSITIVE_SERVICES:
        return {"family": "SAE J1979 / OBD-II", "service": f"0x{sid:02X}",
                "meaning": {0x41:"Canli veri cevabi",0x42:"Freeze-frame cevabi",
                            0x43:"Kayitli motor DTC cevabi",0x49:"Arac bilgisi / VIN cevabi"}.get(sid,"OBD-II pozitif servis cevabi")}
    if sid in ECU_UDS_POSITIVE_SERVICES:
        return {"family": "UDS / ISO 14229", "service": f"0x{sid:02X}",
                "meaning": {0x50:"DiagnosticSessionControl cevabi",0x59:"DTC bilgisi cevabi",
                            0x62:"ReadDataByIdentifier cevabi",0x67:"SecurityAccess cevabi",
                            0x71:"RoutineControl cevabi",0x76:"TransferData cevabi"}.get(sid,"UDS pozitif servis cevabi")}
    if sid == 0x7F and len(payload) >= 3:
        return {"family":"UDS/KWP negatif cevap", "service":f"0x{payload[1]:02X}",
                "meaning":f"Negatif yanit NRC 0x{payload[2]:02X}"}
    if sid in (0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8):
        return {"family":"KWP / ISO 14230 adayi", "service":f"0x{sid:02X}",
                "meaning":"KWP oturum/servis cevabi adayi; hedef ECU ve checksum dogrulanmali"}
    return None

def analyze_ecu_can_capture(frames, module="general"):
    """J2534 CAN RX kaydinda ID, zamanlama, degisen bayt ve ISO-TP servislerini inceler."""
    module = str(module or "general").lower()
    if module not in ("engine", "abs", "general"):
        raise ValueError("Modul engine, abs veya general olmali.")
    if not isinstance(frames, list):
        raise ValueError("CAN cerceve listesi gecersiz.")
    frames = frames[-ECU_CAPTURE_MAX_FRAMES:]
    ids = {}
    iso = {}
    decoded = []
    malformed = 0
    for frame in frames:
        if not isinstance(frame, dict):
            malformed += 1
            continue
        can_id = str(frame.get("id", "UNKNOWN")).upper().strip()
        data = _ecu_hex_bytes(frame.get("data"))
        if not data:
            malformed += 1
            continue
        try:
            stamp = float(frame.get("t", 0.0))
        except (TypeError, ValueError):
            stamp = 0.0
        stat = ids.setdefault(can_id, {"count":0,"last_t":None,"periods":[],"last":None,
                                        "changed":set(),"dlc":set()})
        stat["count"] += 1
        if stat["last_t"] is not None and stamp >= stat["last_t"]:
            period = (stamp-stat["last_t"])*1000.0
            if period > 0 and len(stat["periods"]) < 3000:
                stat["periods"].append(period)
        stat["last_t"] = stamp
        stat["dlc"].add(len(data))
        if stat["last"] is not None:
            for index,(old,new) in enumerate(zip(stat["last"],data)):
                if old != new:
                    stat["changed"].add(index)
        stat["last"] = data

        # ISO-TP PCI: single frame / first frame / consecutive frame.
        pci_type = (data[0] >> 4) & 0x0F
        state = iso.setdefault(can_id, {"expected":0,"payload":bytearray(),"next_seq":None})
        message = None
        if pci_type == 0:
            size = data[0] & 0x0F
            if size and len(data) >= size+1:
                message = data[1:1+size]
            state.update(expected=0,payload=bytearray(),next_seq=None)
        elif pci_type == 1 and len(data) >= 2:
            size = ((data[0] & 0x0F) << 8) | data[1]
            if 0 < size <= 4095:
                state["expected"] = size
                state["payload"] = bytearray(data[2:])
                state["next_seq"] = 1
                if len(state["payload"]) >= size:
                    message = bytes(state["payload"][:size])
                    state.update(expected=0,payload=bytearray(),next_seq=None)
        elif pci_type == 2 and state["expected"]:
            seq = data[0] & 0x0F
            if seq == state["next_seq"]:
                state["payload"].extend(data[1:])
                state["next_seq"] = (seq+1) & 0x0F
                if len(state["payload"]) >= state["expected"]:
                    message = bytes(state["payload"][:state["expected"]])
                    state.update(expected=0,payload=bytearray(),next_seq=None)
            else:
                state.update(expected=0,payload=bytearray(),next_seq=None)
        # PCI=3 flow-control is transport metadata, not an ECU service payload.
        if message:
            info = _ecu_classify_payload(message)
            if info:
                decoded.append({"can_id":can_id,"payload":message.hex(" ").upper(),**info})
    rows=[]
    for can_id,st in sorted(ids.items(),key=lambda item:item[1]["count"],reverse=True):
        periods=st["periods"]
        rows.append({"can_id":can_id,"count":st["count"],
            "mean_period_ms":round(sum(periods)/len(periods),2) if periods else None,
            "min_period_ms":round(min(periods),2) if periods else None,
            "max_period_ms":round(max(periods),2) if periods else None,
            "dlc":sorted(st["dlc"]),
            "changing_bytes":sorted(st["changed"]),
            "last_data":st["last"].hex(" ").upper() if st["last"] else ""})
    return {"ok":True,"module":module,"frame_count":len(frames),
        "unique_can_ids":len(ids),"malformed_frames":malformed,
        "can_ids":rows[:100],"diagnostic_messages":decoded[-250:],
        "module_note":("Motor ECU secimi: bu etiket yalnizca kullanici secimidir; CAN ID tek basina motor ECU oldugunu kanitlamaz."
          if module=="engine" else
          "ABS ECU secimi: bu etiket yalnizca kullanici secimidir; CAN ID tek basina ABS ECU oldugunu kanitlamaz."
          if module=="abs" else
          "Genel kesif: moduller otomatik olarak kesin tanimlanmadi."),
        "limitations":"Yalnizca CAN RX kaydi. ISO-TP coklu cerceveler birlestirilir. Pasif dinleme tek basina ECU kimligi, DTC silme yetkisi veya aktüator test guvenligi saglamaz; K-Line ham zamanlama/ELM monitoru bu analiz endpointine dahil degildir."}

# ==================== FLASK & SOCKETIO ====================
app = Flask(__name__); app.config['SECRET_KEY'] = 'efe_moto_r25'
app.config['MAX_CONTENT_LENGTH'] = 5 * 1024 * 1024
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')
_connection = None; _mode = 'idle'; _conn_lock = threading.Lock(); _query_lock = threading.Lock()
_live_data_lock = threading.Lock(); _last_live_data = {}
_run_id = 0; _run_lock = threading.Lock(); _last_alert_time = 0
_last_spoken_alert = {'text': '', 'time': 0.0}

# ECU laboratory TX/RX capture and passive serial receive monitor.
_ECU_LAB_LOG = []
_ECU_LAB_LOCK = threading.RLock()
_ECU_SERIAL_LOCK = threading.RLock()
_ECU_SERIAL_STATE = {'thread': None, 'stop': None, 'port': None, 'baud': None, 'connected': False, 'error': ''}
_DYNO_LOCK = threading.RLock()
_DYNO_STATE = {'active': False, 'mode': 'standard', 'model': '', 'year': None,
               'started_at': None, 'start_monotonic': None, 'samples': [],
               'wheel_radius_m': 0.30, 'load_force_n': None,
               'dct_mode_confirmed': False, 'last_sample': None, 'source': None, 'worker': None, 'stop_event': None}
_DYNO_MAX_SAMPLES = 100000

def _dyno_record_ecu_sample(data):
    if not data:
        return
    with _DYNO_LOCK:
        if not _DYNO_STATE.get('active'):
            return
        now = time.monotonic()
        started = _DYNO_STATE.get('start_monotonic') or now
        radius = float(_DYNO_STATE.get('wheel_radius_m') or 0.30)
        force = _DYNO_STATE.get('load_force_n')
        try:
            speed = float(data.get('speed')) if data.get('speed') is not None else None
        except (TypeError, ValueError):
            speed = None
        sample = {'time': datetime.now().astimezone().isoformat(timespec='milliseconds'),
                  'elapsed_s': round(now - started, 3), 'source': data.get('_source', 'ECU telemetry'),
                  'rpm': data.get('rpm'), 'speed_kmh': speed, 'tps_pct': data.get('tps'),
                  'coolant_c': data.get('temp'), 'map_kpa': data.get('map'), 'voltage_v': data.get('volt'),
                  'load_force_n': force, 'roller_radius_m': radius, 'wheel_torque_nm': None,
                  'wheel_power_kw': None, 'wheel_power_hp': None, 'roller_rpm': None}
        if force is not None and speed is not None and radius > 0:
            speed_ms = speed / 3.6
            sample['wheel_torque_nm'] = round(float(force) * radius, 2)
            sample['wheel_power_kw'] = round(float(force) * speed_ms / 1000.0, 3)
            sample['wheel_power_hp'] = round(float(force) * speed_ms / 1000.0 * 1.341022, 3)
            sample['roller_rpm'] = round(speed_ms / (2.0 * 3.141592653589793 * radius) * 60.0, 1)
        _DYNO_STATE['last_sample'] = sample
        if len(_DYNO_STATE['samples']) < _DYNO_MAX_SAMPLES:
            _DYNO_STATE['samples'].append(sample)

def _dyno_j2534_worker(stop_event):
    """J2534 ISO15765 uzerinden standart motor OBD PID telemetrisi toplar."""
    pid_specs = (0x0C, 0x0D, 0x11, 0x05, 0x0B, 0x42)
    while not stop_event.is_set():
        values = {}
        for pid in pid_specs:
            if stop_event.is_set():
                break
            raw, detail = j2534_send_obd_pid(pid, timeout_ms=650)
            if not raw:
                continue
            a = raw[0]
            if pid == 0x0C and len(raw) >= 2: values['rpm'] = (a * 256 + raw[1]) / 4.0
            elif pid == 0x0D: values['speed'] = float(a)
            elif pid == 0x11: values['tps'] = round(a * 100.0 / 255.0, 2)
            elif pid == 0x05: values['temp'] = float(a - 40)
            elif pid == 0x0B: values['map'] = float(a)
            elif pid == 0x42 and len(raw) >= 2: values['volt'] = round((a * 256 + raw[1]) / 1000.0, 3)
        if values:
            values['_source'] = 'J2534 ISO15765 OBD'
            _dyno_record_ecu_sample(values)
        stop_event.wait(0.05)


def _dyno_public_state():
    with _DYNO_LOCK:
        return {'active': bool(_DYNO_STATE['active']), 'mode': _DYNO_STATE['mode'],
                'model': _DYNO_STATE['model'], 'year': _DYNO_STATE['year'],
                'started_at': _DYNO_STATE['started_at'], 'sample_count': len(_DYNO_STATE['samples']), 'recent_samples': list(_DYNO_STATE['samples'][-300:]),
                'last_sample': dict(_DYNO_STATE['last_sample']) if _DYNO_STATE['last_sample'] else None,
                'wheel_radius_m': _DYNO_STATE['wheel_radius_m'], 'load_force_n': _DYNO_STATE['load_force_n'],
                'dct_mode_confirmed': _DYNO_STATE['dct_mode_confirmed'], 'source': _DYNO_STATE.get('source'),
                'note': ('DCT dyno modu uygulama tarafindan etkinlestirilmedi; Honda HDS veya uyumlu DCT dyno araci ile etkinlestirilip operator tarafindan dogrulanmalidir.'
                         if _DYNO_STATE['mode'] == 'honda_dct' else
                         'Guc/tork yalnizca harici yuk kuvveti ve teker/roller hizi varsa hesaplanir. ECU RPM tek basina dinamometre gucu degildir.')}


def _ecu_lab_record(transport, direction, payload, detail=''):
    entry = {'time': datetime.now().astimezone().isoformat(timespec='milliseconds'),
             'transport': str(transport), 'direction': str(direction),
             'payload': str(payload), 'detail': str(detail)}
    with _ECU_LAB_LOCK:
        _ECU_LAB_LOG.append(entry)
        if len(_ECU_LAB_LOG) > 5000:
            del _ECU_LAB_LOG[:-5000]
    return entry

def _ecu_serial_worker(port_name, baud_rate, stop_event):
    try:
        if not PYSERIAL_OK:
            raise RuntimeError('pyserial kurulu degil. py -m pip install pyserial')
        with serial.Serial(port=port_name, baudrate=baud_rate, timeout=0.2) as ser:
            with _ECU_SERIAL_LOCK:
                _ECU_SERIAL_STATE.update({'connected': True, 'error': ''})
            _ecu_lab_record('SERIAL-RX', 'INFO', 'Port acildi', f'{port_name} / {baud_rate} baud; TX kapali')
            while not stop_event.is_set():
                data = ser.read(4096)
                if data:
                    _ecu_lab_record('SERIAL-RX', 'RX', data.hex(' ').upper(),
                                    data.decode('ascii', errors='replace').replace(chr(13), '<CR>').replace(chr(10), '<LF>'))
    except Exception as exc:
        with _ECU_SERIAL_LOCK:
            _ECU_SERIAL_STATE['error'] = str(exc)
        _ecu_lab_record('SERIAL-RX', 'ERROR', str(exc))
    finally:
        with _ECU_SERIAL_LOCK:
            _ECU_SERIAL_STATE['connected'] = False
            _ECU_SERIAL_STATE['thread'] = None
            _ECU_SERIAL_STATE['stop'] = None
        _ecu_lab_record('SERIAL-RX', 'INFO', 'Port kapandi')

def _is_current_run(rid):
    with _run_lock: return rid == _run_id
def _cancel_runs():
    global _run_id
    with _run_lock: _run_id += 1
def _next_run_id():
    global _run_id
    with _run_lock: _run_id += 1; return _run_id
def _get_mode():
    with _conn_lock: return _mode
def _set_mode(m):
    global _mode
    with _conn_lock: _mode = m
def _detach_connection(conn):
    global _connection, _mode
    with _conn_lock:
        if _connection is conn: _connection = None; _mode = 'idle'


class _DiagResponse:
    """Gerçek adaptör yanıtını mevcut arayüzün beklediği response şekline uyarlar."""
    def __init__(self, value=None, raw="", is_null=False):
        self.value = value
        self.raw = raw
        self._null = is_null
    def is_null(self):
        return self._null


class ELM327TcpConnection:
    """Viecar/ELM327 Wi-Fi için doğrudan TCP taşıması ve temel OBD-II servisleri."""
    PID_COMMANDS = {
        'RPM': '010C', 'SPEED': '010D', 'COOLANT_TEMP': '0105',
        'THROTTLE_POS': '0111', 'INTAKE_PRESSURE': '010B',
        'INTAKE_TEMP': '010F', 'CONTROL_MODULE_VOLTAGE': '0142',
        'SHORT_FUEL_TRIM_1': '0106', 'O2_B1S1': '0114',
        'O2_SENSOR_B1S1': '0114', 'O2_SENSOR_1': '0114',
        'TEMP': '0105', 'TPS': '0111', 'MAP': '010B', 'IAT': '010F',
        'VOLT': '0142', 'STFT': '0106', 'O2': '0114',
    }

    def __init__(self, host, port, timeout=4.0):
        self.host, self.port = host, int(port)
        self.timeout = timeout
        self.sock = socket.create_connection((host, self.port), timeout=4)
        self.sock.settimeout(0.25)
        self._lock = threading.RLock()
        self._closed = False
        try:
            # ELM327 başlatma. Her komutun cevabı gerçek adaptörden okunur.
            self._exchange('ATZ', wait=2.0)
            for command in ('ATE0', 'ATL0', 'ATS0', 'ATH0', 'ATSP0', 'ATAT1'):
                self._exchange(command)
            self.adapter_identity = self._exchange('ATI')
            self.protocol = self._exchange('ATDP')
            support = self._exchange('0100', wait=5.0)
            if not self._has_response(support, '4100'):
                raise ConnectionError('Adaptör yanıt verdi ancak ECU 0100 destek sorgusuna geçerli cevap vermedi: ' + self._short(support))
            self.last_support_response = support
            # ECU'nun desteklediği Mode 01 PID'lerini bağlantı kurulurken bir kez keşfet.
            # Bitmap okunamazsa None bırakılır; bu durumda mevcut sorgulama davranışı korunur.
            self.supported_pids = self._discover_supported_pids(support)
            self._live_aux_index = 0
            self._pid_retry_after = {}
        except Exception:
            self._closed = True
            try: self.sock.close()
            except OSError: pass
            raise

    @staticmethod
    def _short(value):
        return (value or '').replace('\r', ' ').replace('\n', ' ').strip()[:180]

    @staticmethod
    def _has_response(raw, prefix):
        compact = re.sub(r'[^0-9A-F]', '', (raw or '').upper())
        return prefix in compact

    def _discover_supported_pids(self, first_response):
        """0100/0120/0140/0160 bitmap zincirini okuyup desteklenen PID numaralarını döndürür."""
        supported = set()
        response = first_response
        for block_start in (1, 21, 41, 61):
            prefix = f'41{block_start:02X}'
            payload = self._hex_payload(response)
            pos = payload.find(prefix)
            if pos < 0:
                return None
            hex_data = payload[pos + len(prefix):]
            if len(hex_data) < 8:
                return None
            try:
                bitmap = bytes.fromhex(hex_data[:8])
            except ValueError:
                return None
            for bit_index in range(32):
                if bitmap[bit_index // 8] & (1 << (7 - (bit_index % 8))):
                    supported.add(block_start + bit_index)
            # Her bitmap'in son PID biti, bir sonraki bitmap'in mevcut olduğunu belirtir.
            continuation_pid = block_start + 31
            if block_start == 61 or continuation_pid not in supported:
                break
            next_start = block_start + 20
            response = self._exchange(f'01{next_start:02X}', wait=5.0)
        return supported

    def pid_supported(self, pid_hex):
        """PID destek bilgisi varsa kullan; yoksa geriye dönük uyumluluk için sorguya izin ver."""
        supported = getattr(self, 'supported_pids', None)
        if supported is None:
            return True
        try:
            return int(pid_hex, 16) in supported
        except (TypeError, ValueError):
            return False

    def _exchange(self, command, wait=None):
        if self._closed:
            raise ConnectionError('Adaptör soketi kapalı.')
        wait = self.timeout if wait is None else wait
        with self._lock:
            _ecu_lab_record('ELM327-TCP', 'TX', command.strip(), f'{self.host}:{self.port}')
            self.sock.sendall((command.strip() + '\r').encode('ascii'))
            deadline = time.monotonic() + wait
            chunks = []
            while time.monotonic() < deadline:
                try:
                    part = self.sock.recv(4096)
                    if not part:
                        raise ConnectionError('Adaptör TCP bağlantısını kapattı.')
                    chunks.append(part.decode('ascii', errors='ignore'))
                    if '>' in chunks[-1]:
                        break
                except socket.timeout:
                    if chunks:
                        # ELM prompt gelmemişse kısa bekleme sonunda alınan cevabı döndür.
                        continue
                except OSError as exc:
                    raise ConnectionError(f'Adaptör iletişim hatası: {exc}') from exc
            response = ''.join(chunks).replace('>', '').strip()
            _ecu_lab_record('ELM327-TCP', 'RX', response, command.strip())
            return response

    def is_connected(self):
        return not self._closed
    def port_name(self):
        return f'{self.host}:{self.port}'
    def protocol_name(self):
        value = self.protocol or 'Otomatik protokol'
        return self._short(value)
    def close(self):
        if not self._closed:
            self._closed = True
            try: self.sock.close()
            except OSError: pass

    def _hex_payload(self, raw):
        text = (raw or '').upper()
        if any(x in text for x in ('NO DATA', 'UNABLE TO CONNECT', 'BUS INIT: ERROR', 'STOPPED', '?')):
            return ''
        # Başlıklar kapalı; ELM yanıtındaki hex karakterleri normalize edilir.
        return re.sub(r'[^0-9A-F]', '', text)

    def _pid_bytes(self, command, response_prefix):
        raw = self._exchange(command, wait=5.0)
        payload = self._hex_payload(raw)
        pos = payload.find(response_prefix)
        if pos < 0:
            return None, raw
        data = payload[pos + len(response_prefix):]
        if len(data) < 2:
            return None, raw
        try:
            return bytes.fromhex(data), raw
        except ValueError:
            return None, raw

    def query_pid(self, key):
        command = self.PID_COMMANDS.get(key.upper())
        if not command:
            return None
        pid = command[2:]
        if not self.pid_supported(pid):
            return None
        now = time.monotonic()
        retry_after = getattr(self, '_pid_retry_after', {}).get(pid, 0.0)
        if now < retry_after:
            return None
        data, raw = self._pid_bytes(command, '41' + pid)
        if not data:
            # Destekli görünen ancak cevap vermeyen PID'yi her çevrimde bekletme.
            # 8 saniye sonra tekrar deneyerek geçici ECU/adaptör gecikmesine tolerans tanı.
            self._pid_retry_after[pid] = now + 8.0
            return None
        self._pid_retry_after.pop(pid, None)
        a = data[0]
        try:
            if key == 'rpm' or key.upper() == 'RPM':
                return round(((a * 256 + data[1]) / 4.0), 2) if len(data) >= 2 else None
            if key == 'speed' or key.upper() == 'SPEED': return float(a)
            if key == 'temp' or key.upper() == 'COOLANT_TEMP': return float(a - 40)
            if key == 'tps' or key.upper() == 'THROTTLE_POS': return round(a * 100.0 / 255.0, 2)
            if key == 'map' or key.upper() == 'INTAKE_PRESSURE': return float(a)
            if key == 'iat' or key.upper() == 'INTAKE_TEMP': return float(a - 40)
            if key == 'volt' or key.upper() == 'CONTROL_MODULE_VOLTAGE':
                return round((a * 256 + data[1]) / 1000.0, 3) if len(data) >= 2 else None
            if key == 'stft' or key.upper() == 'SHORT_FUEL_TRIM_1': return round((a - 128) * 100.0 / 128.0, 2)
            if key.lower() == 'o2': return round(a / 200.0, 3)
        except (IndexError, ValueError):
            return None
        return None

    def query(self, command):
        name = str(getattr(command, 'name', command)).upper()
        if name in ('GET_DTC', 'DTC', '03'):
            codes, raw = self.read_dtcs()
            return _DiagResponse(codes, raw, is_null=(codes is None))
        if name in ('CLEAR_DTC', 'CLEAR_DTC', '04'):
            ok, raw = self.clear_dtcs()
            return _DiagResponse(True if ok else None, raw, is_null=not ok)
        if name in ('VIN', 'GET_VIN'):
            vin, raw = self.read_vin()
            return _DiagResponse(vin, raw, is_null=(vin is None))
        for key, cmd in self.PID_COMMANDS.items():
            if name == key:
                value = self.query_pid(key)
                return _DiagResponse(value, is_null=(value is None))
        if name in ('PIDS_A', '0100'):
            raw = self._exchange('0100', wait=5.0)
            return _DiagResponse(raw, raw, is_null=not self._has_response(raw, '4100'))
        return _DiagResponse(None, '', is_null=True)

    @staticmethod
    def _decode_dtc(two_bytes):
        a, b = two_bytes
        letters = 'PCBU'
        letter = letters[(a >> 6) & 0x03]
        first = (a >> 4) & 0x03
        second = a & 0x0F
        third = (b >> 4) & 0x0F
        fourth = b & 0x0F
        return f'{letter}{first:X}{second:X}{third:X}{fourth:X}'

    def read_dtcs(self):
        raw = self._exchange('03', wait=6.0)
        payload = self._hex_payload(raw)
        pos = payload.find('43')
        if pos < 0:
            if 'NO DATA' in raw.upper(): return [], raw
            return None, raw
        data = payload[pos + 2:]
        # DTC cevabı iki baytlık kodlardan oluşur; 0000 dolgu kodları atılır.
        codes = []
        for i in range(0, len(data) - 3, 4):
            pair = bytes.fromhex(data[i:i+4])
            if pair != b'\x00\x00':
                codes.append((self._decode_dtc(pair), ''))
        return codes, raw

    def clear_dtcs(self):
        raw = self._exchange('04', wait=6.0)
        payload = self._hex_payload(raw)
        ok = '44' in payload or 'OK' in raw.upper()
        return ok, raw

    def read_vin(self):
        raw = self._exchange('0902', wait=7.0)
        payload = self._hex_payload(raw)
        pos = payload.find('490201')
        if pos < 0: return None, raw
        data = payload[pos + 6:]
        try:
            decoded = bytes.fromhex(data).decode('ascii', errors='ignore')
            vin = ''.join(ch for ch in decoded if ch.isalnum())
            return (vin if len(vin) >= 10 else None), raw
        except ValueError:
            return None, raw

    def send_read_only(self, command):
        return self._exchange(command, wait=5.0)


def _normalize_target(target):
    """ELM TCP hedefini normalize eder; Viecar'in /OBD servis eki de kabul edilir."""
    t = (target or "").strip()
    if t.startswith("socket://"):
        authority = t[len("socket://"):].split("/", 1)[0]
    else:
        authority = t.split("/", 1)[0]
    if ":" not in authority:
        authority += ":35000"
    return "socket://" + authority


def _tcp_probe(ip, port, timeout=0.7):
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def find_wifi_adapter():
    # Önce kullanıcının gerçek Viecar VP002 adresi; ardından bilinen yaygın ağlar.
    candidates = [("192.168.0.10", 35000), ("192.168.0.11", 35000),
                  ("192.168.1.5", 35000), ("192.168.1.10", 35000),
                  ("192.168.0.10", 35001), ("192.168.4.1", 35000)]
    for ip, port in candidates:
        if _tcp_probe(ip, port):
            return f"{ip}:{port}"
    return None


def open_obd(target, run_id):
    """Wi-Fi ELM327'de doğrudan TCP; USB/COM adaptörde python-OBD kullan."""
    global _connection, _mode
    raw_target = (target or '').strip()
    if raw_target.lower() in ("j2534", "j2534://", "scanmatik", "tactrix", "openport"):
        conn = J2534OBDConnection()
    elif raw_target.startswith('socket://') or (raw_target.count('.') == 3 and ':' in raw_target):
        # Bazı Viecar arayüzleri adresi 192.168.0.10:35000/OBD biçiminde gösterir.
        # TCP soketinde /OBD HTTP yolu değildir; bağlantı host:port'a yapılır.
        hostport = raw_target.replace('socket://', '', 1).split('/', 1)[0]
        host, port = hostport.rsplit(':', 1) if ':' in hostport else (hostport, '35000')
        conn = ELM327TcpConnection(host, int(port))
    else:
        if not OBD_OK:
            raise RuntimeError('USB/COM için python-OBD kurulu değil. requirements.txt yükleyin.')
        conn = obd.OBD(portstr=raw_target, baudrate=38400, protocol=None,
                       fast=True, timeout=6, check_voltage=False)
        if not conn.is_connected():
            raise ConnectionError('USB/COM adaptörü açıldı ancak ECU bağlantısı kurulamadı.')
        probe_cmd = getattr(obd.commands, 'PIDS_A', None)
        if probe_cmd is not None:
            probe = conn.query(probe_cmd)
            if probe.is_null():
                conn.close()
                raise ConnectionError('ECU 0100 destek sorgusuna yanıt vermedi.')
    with _conn_lock:
        _connection = conn
        _mode = 'real'
    write_log('ADAPTÖR BAĞLANTISI', f'Hedef: {raw_target}\nAdaptör: {getattr(conn, "adapter_identity", "USB/COM python-OBD")}\nProtokol: {conn.protocol_name()}')
    return conn


def close_obd():
    global _connection, _mode
    with _conn_lock:
        conn = _connection
        _connection = None
        _mode = 'idle'
    with _live_data_lock:
        _last_live_data.clear()
    if conn:
        try: conn.close()
        except Exception: pass
    with _live_data_lock:
        _last_live_data.clear()


def _obd_value(response):
    if response is None or response.is_null():
        return None
    value = response.value
    magnitude = getattr(value, 'magnitude', value)
    try:
        return round(float(magnitude), 2)
    except (TypeError, ValueError):
        return str(value)


def _query_live_values(conn):
    # J2534 kendi SAE J1979 sürücüsünü kullanır; python-OBD paketine bağımlı değildir.
    if isinstance(conn, J2534OBDConnection):
        return conn.read_live_data()
    if isinstance(conn, ELM327TcpConnection):
        # Temel göstergeler her turda; yardımcı PID'ler sırayla okunur.
        core_keys = ('rpm', 'tps', 'temp', 'map', 'volt')
        auxiliary_keys = ('speed', 'iat', 'stft', 'o2')
        result = {}
        keys = list(core_keys)
        if auxiliary_keys:
            index = getattr(conn, '_live_aux_index', 0) % len(auxiliary_keys)
            keys.append(auxiliary_keys[index])
            conn._live_aux_index = (index + 1) % len(auxiliary_keys)
        for key in keys:
            try:
                value = conn.query_pid(key)
                if value is not None:
                    result[key] = value
            except (ConnectionError, OSError):
                # Bağlantı hatasını yutma; gerçek_data_loop yeniden bağlanmayı başlatmalı.
                raise
            except Exception:
                continue
        return result

    candidates = {
        'rpm': 'RPM', 'speed': 'SPEED', 'temp': 'COOLANT_TEMP',
        'tps': 'THROTTLE_POS', 'map': 'INTAKE_PRESSURE', 'iat': 'INTAKE_TEMP',
        'volt': 'CONTROL_MODULE_VOLTAGE', 'stft': 'SHORT_FUEL_TRIM_1',
    }
    for name in ('O2_B1S1', 'O2_SENSOR_B1S1', 'O2_SENSOR_1'):
        if hasattr(obd.commands, name):
            candidates['o2'] = name
            break
    result = {}
    for key, command_name in candidates.items():
        command = getattr(obd.commands, command_name, None)
        if command is None:
            continue
        try:
            _ecu_lab_record('PYTHON-OBD', 'TX', command_name, 'USB/COM python-OBD logical PID request')
            with _query_lock:
                response = conn.query(command)
            value = _obd_value(response)
            _ecu_lab_record('PYTHON-OBD', 'RX', value if value is not None else 'NO DATA',
                            command_name + ' -> ' + key)
            if value is not None:
                result[key] = value
        except Exception as exc:
            try:
                alive = conn.is_connected()
            except Exception:
                alive = False
            if not alive:
                raise ConnectionError("USB/COM ECU bağlantısı koptu: " + str(exc)) from exc
            continue
    return result
def real_data_loop(target, run_id):
    global _last_alert_time
    conn = None
    while _is_current_run(run_id):
        try:
            conn = open_obd(target, run_id)
            proto = getattr(conn, 'protocol_name', lambda: 'Bilinmiyor')()
            socketio.emit('status', {'msg': f'ECU ONLINE - {proto}', 'color': '#00ff00'})
            write_log("ECU ONLINE", f"Adaptör: {_normalize_target(target)} | Protokol: {proto}")
            empty_cycles = 0
            while _is_current_run(run_id):
                if not conn.is_connected():
                    raise ConnectionError("Adaptör/ECU bağlantısı koptu.")
                data = _query_live_values(conn)
                if data:
                    empty_cycles = 0
                    # Son gerçek ECU ölçümlerini DTC yorumunda kullanmak üzere sakla.
                    with _live_data_lock:
                        _last_live_data.update(data)
                    _dyno_record_ecu_sample(data)
                    socketio.emit('telemetry', data)
                    socketio.emit('risk_score', {'score': risk_skoru_hesapla(data)})
                    now = time.time()
                    if now - _last_alert_time > 10:
                        alerts = anomali_kontrol(data)
                        if alerts:
                            _last_alert_time = now
                            socketio.emit('alert', {'msg': alerts[0]})
                            # Aynı arıza uyarısını her ölçüm turunda tekrarlama.
                            if (alerts[0] != _last_spoken_alert['text'] or
                                    now - _last_spoken_alert['time'] >= 60):
                                _last_spoken_alert.update({'text': alerts[0], 'time': now})
                                safe_speak(alerts[0])
                else:
                    empty_cycles += 1
                    if empty_cycles >= 5:
                        socketio.emit('status', {'msg': 'ECU BAĞLI - CANLI PID YANITI YOK', 'color': '#ffaa00'})
                time.sleep(0.25)
        except Exception as exc:
            if not _is_current_run(run_id):
                break
            socketio.emit('status', {'msg': f'BAĞLANTI HATASI: {exc}', 'color': '#ff5252'})
            write_log("BAĞLANTI HATASI", str(exc))
            if conn:
                try: conn.close()
                except Exception: pass
                _detach_connection(conn)
                conn = None
            # Otomatik yeniden bağlanma; kullanıcı kesene kadar devam eder.
            for _ in range(20):
                if not _is_current_run(run_id): break
                time.sleep(0.25)
    if conn:
        try: conn.close()
        except Exception: pass
        _detach_connection(conn)
    if _is_current_run(run_id):
        socketio.emit('status', {'msg': 'BAĞLANTI KESİLDİ', 'color': '#ff5252'})

# ==================== GELİŞMİŞ TEST MOTORLARI ====================
_kline_serial = None; _can_bus = None

def kline_baglan():
    global _kline_serial
    if not PYSERIAL_OK: return False, "pyserial eksik."
    ports = list(serial.tools.list_ports.comports())
    target_port = next((p.device for p in ports if "USB" in p.description or "FTDI" in p.description or "Serial" in p.description), None)
    if not target_port: return False, "K-Line adaptörü bulunamadı."
    try:
        _kline_serial = serial.Serial(target_port, baudrate=10400, timeout=1)
        _kline_serial.setRTS(True); time.sleep(0.025); _kline_serial.setRTS(False); time.sleep(0.025)
        cmd = bytearray([0x82, 0x11, 0xF1, 0x01, 0x81, 0x00]); cmd[-1] = sum(cmd[:-1]) & 0xFF
        _kline_serial.write(cmd); resp = _kline_serial.read(10)
        if len(resp) > 0 and resp[4] == 0xC1: return True, f"K-Line ({target_port}) bağlandı."
        return False, f"Fast-Init başarısız."
    except Exception as e: return False, f"Hata: {e}"

def can_baglan():
    return False, "Gerçek CAN/J2534 adaptörü tanımlı değil. Sanal vcan0 kullanılmayacak."

def kline_aktif_test(d_code):
    return False, "Aktüatör komutu devre dışı: seçili motosiklet/ECU için doğrulanmış komut profili yok."

def kline_d07_oku():
    return None, "ABS D07 genel bir standart komut değildir. Model/ABS ECU protokolü doğrulanmadan sorgu gönderilmeyecek."

def can_aktif_test(d_code):
    return False, "UDS RoutineControl isteği için gerçek CAN/J2534 taşıması ve ECU rutin kimliği doğrulanmadı."

def can_d07_oku():
    return None, "ABS CAN sorgusu için gerçek CAN/J2534 adaptörü ve model özelinde ABS adresi/protokolü gerekli."

# ==================== HTML ARAYÜZÜ ====================
HTML_CODE = r"""
<!DOCTYPE html><html lang="tr"><head><meta charset="UTF-8"><title>Efe Moto Diag</title>
<script src="https://cdnjs.cloudflare.com/ajax/libs/socket.io/4.0.1/socket.io.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
<style>
body{background:#0a0a0a;color:#fff;font-family:'Segoe UI',sans-serif;margin:0;padding-bottom:30px}
.top-bar{display:flex;justify-content:space-between;align-items:center;padding:10px 20px;border-bottom:2px solid #ff003c;background:#000}
.top-bar h1{margin:0;color:#ff003c;font-size:24px;letter-spacing:3px}
#status-bar{color:#ffaa00;font-weight:bold;font-family:monospace;font-size:16px}
.nav{display:flex;justify-content:center;background:#000;padding:10px;border-bottom:1px solid #333;flex-wrap:wrap}
.nav button{background:none;border:1px solid #333;color:#888;padding:8px 20px;margin:0 5px;cursor:pointer;font-weight:bold}
.nav button.active{border-color:#00e5ff;color:#00e5ff;background:rgba(0,229,255,0.1)}
.container{display:none;width:95%;max-width:1400px;margin:20px auto}
.container.active{display:block}
.panel{background:rgba(10,10,10,0.9);border:1px solid #444;padding:15px}
.title{color:#aaa;font-size:13px;text-transform:uppercase;font-weight:bold;margin-bottom:5px}
.value{font-size:60px;font-weight:900;font-family:'Consolas',monospace;line-height:1}
.rpm-bar-container{width:100%;margin-bottom:20px;padding:5px 15px;background:rgba(0,0,0,0.8);border:1px solid #333}
.rpm-numbers{display:flex;justify-content:space-between;font-family:monospace;color:#555;font-size:12px;margin-bottom:5px}
.rpm-numbers span{width:20px;text-align:center}
.rpm-leds{display:flex;gap:2px;width:100%;height:15px}
.led{flex-grow:1;background:#222;border-radius:2px}
.led.active{background:#00ff00;box-shadow:0 0 10px #00ff00}
.led.warning{background:#ffea00;box-shadow:0 0 10px #ffea00}
.led.danger{background:#ff003c;box-shadow:0 0 10px #ff003c}
.dash-grid{display:grid;grid-template-columns:2fr 1.5fr 1fr;gap:15px;margin-bottom:15px}
.panel.rpm{border-color:#ff003c}.panel.rpm .value{color:#ff003c;font-size:80px}
.panel.speed{border-color:#fff}.panel.speed .value{color:#fff;font-size:80px}
.panel.gear{border-color:#00ff00}.panel.gear .value{color:#00ff00;font-size:80px;text-align:center}
.sub-dash{display:grid;grid-template-columns:repeat(4,1fr);gap:15px}
.sub-panel{height:130px;display:flex;flex-direction:column;padding:10px}
.sub-panel .s-header{display:flex;justify-content:space-between}
.sub-panel .s-val{font-size:36px;font-weight:900;font-family:'Consolas',monospace}
.sub-panel .s-unit{font-size:14px;color:#888}
.sub-panel .graph-wrapper{height:60px}
.sensor-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:15px}
.sensor-box{height:110px;display:flex;padding:10px 15px}
.sensor-info{width:45%;display:flex;flex-direction:column;justify-content:center}
.sensor-info .s-title{font-size:12px;color:#aaa;font-weight:bold;text-transform:uppercase}
.sensor-info .s-val{font-size:34px;font-weight:900;font-family:'Consolas',monospace}
.sensor-info .s-unit{font-size:14px;color:#888}
.sensor-graph{width:55%;height:100%}
.c-cyan{border-color:#00e5ff}.c-cyan .s-val{color:#00e5ff}
.c-orange{border-color:#ff6600}.c-orange .s-val{color:#ff6600}
.c-green{border-color:#00ff00}.c-green .s-val{color:#00ff00}
.c-yellow{border-color:#ffea00}.c-yellow .s-val{color:#ffea00}
.c-purple{border-color:#d500ff}.c-purple .s-val{color:#d500ff}
.c-blue{border-color:#007bff}.c-blue .s-val{color:#007bff}
.dtc-panel{text-align:center;border:2px solid #ff003c;max-width:800px;margin:0 auto}
.dtc-icon{font-size:100px;margin:20px}
.btn{background:rgba(255,0,60,0.1);color:#ff003c;padding:15px 40px;font-size:20px;border:2px solid #ff003c;cursor:pointer;font-weight:bold;margin:10px}
.btn:hover{background:#ff003c;color:#000}
#graph-container{background:rgba(0,0,0,0.8);padding:20px;border:2px solid #333}
.test-block{margin-bottom:15px;padding:10px;background:#111;border:1px solid #333}
.test-block .title{margin-bottom:8px}
pre{color:#00ff00;margin:5px 0;font-family:'Consolas',monospace;font-size:12px;white-space:pre-wrap;word-break:break-all}
.test-input{width:60%;padding:8px;background:#222;color:#00e5ff;border:1px solid #444;font-family:Consolas}
.test-btn{font-size:14px;padding:8px 15px;margin:5px 0}
.service-box{display:flex;gap:10px;margin-bottom:15px}
.service-box input{flex:1;padding:8px;background:#222;border:1px solid #555;color:#fff}
#freeze-frame{display:none;background:#222;border:1px solid #ffaa00;padding:15px;margin-top:15px;text-align:left;}
.smart-diag{background:rgba(0,229,255,0.1);border-left:4px solid #00e5ff;padding:10px;margin-top:15px;text-align:left;font-family:monospace;color:#00e5ff;white-space:pre-wrap;font-size:14px;}
#toast-container{position:fixed;top:20px;right:20px;z-index:9999;display:flex;flex-direction:column;gap:10px;}
.toast{background:rgba(255,0,60,0.9);color:white;padding:15px 25px;border-radius:5px;font-weight:bold;box-shadow:0 4px 12px rgba(0,0,0,0.5);animation:slideIn 0.3s ease-out;max-width:350px;}
@keyframes slideIn{from{transform:translateX(100%);opacity:0}to{transform:translateX(0);opacity:1}}
.risk-box{text-align:center;margin-top:10px;}
.risk-val{font-size:50px;font-weight:900;font-family:monospace;}

.module-head{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px}
.module-head h2{margin:0;font-size:24px;letter-spacing:1px}
.module-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:14px 0}
.module-card{background:#101419;border:1px solid #343b45;border-radius:8px;padding:15px;min-height:110px}
.module-card .k{font-size:11px;color:#8d9aaa;font-weight:700;letter-spacing:1px;text-transform:uppercase}
.module-card .v{font-size:20px;color:#eaf3ff;font-weight:800;margin-top:9px}
.module-card .hint{font-size:12px;color:#8793a2;margin-top:7px;line-height:1.5}
.module-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}
.module-actions .btn{font-size:13px;padding:10px 14px;margin:0}
.module-actions .btn:disabled{opacity:.35;cursor:not-allowed;filter:grayscale(1)}
.module-note{background:#17130a;border:1px solid #6b511b;border-left:4px solid #ffaa00;padding:12px;color:#e8c77d;line-height:1.55;margin:14px 0}
.module-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
.module-item{background:#0d1116;border:1px solid #29313b;padding:12px;border-radius:6px}
.module-item b{display:block;color:#dbe8f5;margin-bottom:5px}
.module-item span{color:#8793a2;font-size:12px;line-height:1.5}
.module-status{display:inline-block;padding:6px 10px;border:1px solid #73591e;border-radius:4px;color:#ffc857;font-size:11px;font-weight:800;letter-spacing:.5px}
.module-field{background:#222;color:#e7f5ff;border:1px solid #46515e;padding:9px;width:100%;box-sizing:border-box}
@media(max-width:900px){.module-grid{grid-template-columns:1fr 1fr}.module-list{grid-template-columns:1fr}}
@media(max-width:600px){.module-grid{grid-template-columns:1fr}.nav{justify-content:flex-start}.nav button{margin:4px}}

</style></head><body><div id="toast-container"></div>
<div class="top-bar"><h1>SMART GAUGE <span style="font-size:12px;color:#555">YAMAHA DIAG</span></h1><div id="status-bar">BAĞLANTI BEKLENİYOR...</div></div>
<div class="nav">
<button id="nav-arac" class="active" onclick="switchTab('arac')">🚗 ARAÇ SEÇİMİ</button>
<button id="nav-dash" onclick="switchTab('dash')">KONTROL PANELİ</button>
<button id="nav-sens" onclick="switchTab('sens')">CANLI SENSÖRLER</button>
<button id="nav-graph" onclick="switchTab('graph')">CANLI GRAFİK</button>
<button id="nav-dtc" onclick="switchTab('dtc')">ARIZA KODLARI</button>
<button id="nav-test" onclick="switchTab('test')">🧪 TEST</button>
<button id="nav-gk" onclick="switchTab('gk')">🔧 GELİŞMİŞ TESTLER</button>
<button id="nav-abs" onclick="switchTab('abs')">🛑 ABS ECU</button>
<button id="nav-dct" onclick="switchTab('dct')">⚙️ HONDA DCT</button>
<button id="nav-dyno" onclick="switchTab('dyno')">📈 DYNO</button>
<button id="nav-ecu-lab" onclick="switchTab('ecu-lab')">📡 ECU ANALİZ LAB</button>
</div>
<div id="tab-arac" class="container active"><div class="panel" style="max-width:800px;margin:0 auto;">
<h3 style="color:#00e5ff;margin-top:0">🚗 ARAÇ SEÇİMİ VE OTOMATİK YAPILANDIRMA</h3>
<div style="display:grid;grid-template-columns:1fr 1fr;gap:15px;margin-bottom:15px;">
<div><label style="color:#aaa;font-size:12px;">MARKA</label><select id="sel_marka" style="width:100%;padding:10px;background:#222;color:#00e5ff;border:1px solid #444;font-size:16px;"><option value="yamaha">Yamaha</option><option value="honda">Honda</option></select></div>
<div>
<label style="color:#aaa;font-size:12px;">MODEL</label>
<select id="sel_model" style="width:100%;padding:10px;background:#222;color:#00e5ff;border:1px solid #444;font-size:16px;" onchange="toggleManualModel()">
    <optgroup label="Scooter / Küçük Hacim">
        <option value="nmax">NMAX 125/155</option>
        <option value="xmax">XMAX 125/250/300/400</option>
        <option value="aerox">Aerox 155</option>
        <option value="delight">D'elight 125</option>
    </optgroup>
    <optgroup label="Naked / Hyper Naked">
        <option value="mt15">MT-15</option>
        <option value="mt25">MT-25</option>
        <option value="mt03">MT-03</option>
        <option value="mt07">MT-07</option>
        <option value="mt09">MT-09</option>
        <option value="mt10">MT-10</option>
        <option value="mt125">MT-125</option>
    </optgroup>
    <optgroup label="Süpersport">
        <option value="r15">YZF-R15</option>
        <option value="r25">YZF-R25</option>
        <option value="r3">YZF-R3</option>
        <option value="r6">YZF-R6</option>
        <option value="r7">YZF-R7</option>
        <option value="r1">YZF-R1</option>
        <option value="r125">YZF-R125</option>
    </optgroup>
    <optgroup label="Retro / Adventure / Touring">
        <option value="xsr900">XSR900</option>
        <option value="xsr700">XSR700</option>
        <option value="tenere700">Ténéré 700</option>
        <option value="tracer9">Tracer 9</option>
        <option value="tracer7">Tracer 7</option>
        <option value="fz25">FZ25 / Fazer 250</option>
    </optgroup>
    <optgroup label="Off-Road / Enduro">
        <option value="wr250">WR 250F</option>
        <option value="wr450">WR 450F</option>
        <option value="xtz250">XTZ 250</option>
        <option value="yz250">YZ 250</option>
    </optgroup>
    <optgroup label="Cruiser">
        <option value="xv950">Bolt / XV950</option>
        <option value="vmax">VMAX 1700</option>
    </optgroup>
    <option value="diger" style="color:#ffaa00; font-weight:bold;">--- Listede Yok / Diğer ---</option>
</select>
</div>
<div id="manual_model_box" style="display:none; grid-column: 1 / -1;">
    <label style="color:#ffaa00;font-size:12px;">MODEL ADINI YAZIN</label>
    <input type="text" id="sel_model_manual" placeholder="örn: XSR155, XT660Z, Cygnus..." style="width:100%;padding:10px;background:#222;color:#00e5ff;border:1px solid #ffaa00;font-size:16px;">
</div>
<div><label style="color:#aaa;font-size:12px;">MODEL YILI</label><select id="sel_yil" style="width:100%;padding:10px;background:#222;color:#00e5ff;border:1px solid #444;font-size:16px;"><option value="2015">2015</option><option value="2018">2018</option><option value="2020" selected>2020</option><option value="2021">2021</option><option value="2022">2022</option><option value="2023">2023</option><option value="2024">2024</option><option value="2025">2025</option></select></div>
<div><label style="color:#aaa;font-size:12px;">KONNEKTÖR TİPİ</label><select id="sel_konnektor" style="width:100%;padding:10px;background:#222;color:#00e5ff;border:1px solid #444;font-size:16px;"><option value="auto">Otomatik Algıla</option><option value="3pin">3 Pin (K-Line)</option><option value="6pin">6 Pin (Euro5 / CAN)</option></select></div>
</div><button class="btn" style="width:100%;margin-top:10px;" onclick="aracSec()">✅ SEÇİMİ KAYDET VE YAPILANDIR</button>
<div id="arac_sonuc" style="margin-top:20px;padding:15px;background:#111;border:1px solid #333;border-radius:5px;display:none;"><div class="title">YAPILANDIRMA SONUCU</div><pre id="arac_sonuc_text" style="color:#00ff00;font-family:Consolas;font-size:14px;white-space:pre-wrap;"></pre></div>
</div></div>
<div id="tab-dash" class="container"><div class="rpm-bar-container"><div class="rpm-numbers"><span>0</span><span>1</span><span>2</span><span>3</span><span>4</span><span>5</span><span>6</span><span>7</span><span>8</span><span>9</span><span>10</span><span>11</span><span>12</span><span>13</span></div><div class="rpm-leds" id="rpm-leds"></div></div>
<div class="dash-grid"><div class="panel rpm"><div class="title">DEVİR (RPM)</div><div class="value" id="rpm">0</div></div><div class="panel speed"><div class="title">HIZ (KM/H)</div><div class="value" id="speed">0</div></div><div class="panel gear"><div class="title">VİTES</div><div class="value" id="gear">—</div></div></div>
<div class="sub-dash"><div class="panel sub-panel c-yellow"><div class="s-header"><div class="title">GAZ KELEBEĞİ</div><div class="s-val" id="dash_tps">0<span class="s-unit">%</span></div></div><div class="graph-wrapper"><canvas id="chart_dash_tps"></canvas></div></div><div class="panel sub-panel c-green"><div class="s-header"><div class="title">HAVA/YAKIT</div><div class="s-val" id="dash_afr"><span class="s-unit">AFR</span></div></div><div class="graph-wrapper"><canvas id="chart_dash_afr"></canvas></div></div><div class="panel sub-panel c-orange"><div class="s-header"><div class="title">MOTOR SICAKLIĞI</div><div class="s-val" id="dash_temp">0<span class="s-unit">°C</span></div></div><div class="graph-wrapper"><canvas id="chart_dash_temp"></canvas></div></div><div class="panel sub-panel c-green"><div class="s-header"><div class="title">AKÜ VOLTAJI</div><div class="s-val" id="dash_volt">0.0<span class="s-unit">V</span></div></div><div class="graph-wrapper"><canvas id="chart_dash_volt"></canvas></div></div></div>
<div class="panel" style="border-color:#ffaa00; margin-top:15px;"><div class="title" style="text-align:center;">ANLIK MOTOR RİSK SKORU</div><div class="risk-box"><div class="risk-val" id="risk-score" style="color:#00ff00">0</div><div style="color:#888;font-size:12px;">(0-30: İyi | 31-60: Dikkat | 61-100: Riskli)</div></div></div></div>
<div id="tab-sens" class="container"><div class="sensor-grid">
<div class="panel sensor-box c-cyan"><div class="sensor-info"><div class="s-title">DEVİR</div><div class="s-val" id="s_rpm">0<span class="s-unit">rpm</span></div></div><div class="sensor-graph"><canvas id="chart_rpm"></canvas></div></div><div class="panel sensor-box c-orange"><div class="sensor-info"><div class="s-title">MOTOR SICAKLIĞI</div><div class="s-val" id="s_temp">0<span class="s-unit">°C</span></div></div><div class="sensor-graph"><canvas id="chart_temp"></canvas></div></div><div class="panel sensor-box c-cyan"><div class="sensor-info"><div class="s-title">HAVA SICAKLIĞI</div><div class="s-val" id="s_iat">0<span class="s-unit">°C</span></div></div><div class="sensor-graph"><canvas id="chart_iat"></canvas></div></div><div class="panel sensor-box c-green"><div class="sensor-info"><div class="s-title">AKÜ VOLTAJI</div><div class="s-val" id="s_volt">0<span class="s-unit">V</span></div></div><div class="sensor-graph"><canvas id="chart_volt"></canvas></div></div><div class="panel sensor-box c-yellow"><div class="sensor-info"><div class="s-title">GAZ KELEBEĞİ</div><div class="s-val" id="s_tps">0<span class="s-unit">%</span></div></div><div class="sensor-graph"><canvas id="chart_tps"></canvas></div></div><div class="panel sensor-box c-purple"><div class="sensor-info"><div class="s-title">ENJEKTÖR</div><div class="s-val" id="s_inj">0<span class="s-unit">ms</span></div></div><div class="sensor-graph"><canvas id="chart_inj"></canvas></div></div><div class="panel sensor-box c-green"><div class="sensor-info"><div class="s-title">HAVA/YAKIT</div><div class="s-val" id="s_afr"><span class="s-unit">AFR</span></div></div><div class="sensor-graph"><canvas id="chart_afr"></canvas></div></div><div class="panel sensor-box c-cyan"><div class="sensor-info"><div class="s-title">OKSİJEN (O2)</div><div class="s-val" id="s_o2">0<span class="s-unit">V</span></div></div><div class="sensor-graph"><canvas id="chart_o2"></canvas></div></div><div class="panel sensor-box c-purple"><div class="sensor-info"><div class="s-title">YAKIT TRİMİ</div><div class="s-val" id="s_stft">0<span class="s-unit">%</span></div></div><div class="sensor-graph"><canvas id="chart_stft"></canvas></div></div><div class="panel sensor-box c-blue"><div class="sensor-info"><div class="s-title">EMİŞ BASINCI</div><div class="s-val" id="s_map">0<span class="s-unit">kPa</span></div></div><div class="sensor-graph"><canvas id="chart_map"></canvas></div></div>
</div></div>
<div id="tab-graph" class="container"><div id="graph-container"><canvas id="liveChart" height="120"></canvas></div></div>
<div id="tab-dtc" class="container"><div class="panel dtc-panel"><div class="dtc-icon" id="dtc-icon">⚙️</div><h1 id="dtc-title" style="color:#888;letter-spacing:5px">SİSTEM NORMAL</h1><div class="title" id="dtc-msg" style="color:#888;margin-bottom:30px">Taramak için butona basın...</div><div><button class="btn" onclick="scanDTC()">🔍 ARIZA TARA</button><button class="btn" style="color:#00e5ff;border-color:#00e5ff;background:rgba(0,229,255,0.1)" onclick="clearDTC()">🗑 ARIZALARI SİL</button></div><div id="freeze-frame"><strong>🛑 FREEZE FRAME:</strong><pre id="ff-data" style="color:#ffaa00"></pre></div><div class="smart-diag" id="smart-diag" style="display:none;"></div></div></div>
<div id="tab-test" class="container"><div class="panel"><h3 style="color:#00e5ff;margin-top:0">🧪 TEST / SERVİS</h3><div class="title" style="margin-top:15px; color:#fff;">MÜŞTERİ VE LOG BİLGİLERİ</div><div class="service-box"><input id="musteri" placeholder="Müşteri Adı" onchange="updateInfo()"><input id="plaka" placeholder="Plaka / Şase No" onchange="updateInfo()"><input id="sikayet" placeholder="Servis Şikayeti" onchange="updateInfo()"></div><div class="test-block"><div class="title">BAĞLANTI BİLGİSİ</div><pre id="test_info">-</pre><button class="btn test-btn" onclick="testGetInfo()">🔄 YENİLE</button><button class="btn test-btn" style="border-color:#00ff00;color:#00ff00" onclick="window.open('/log', '_blank')">📄 SERVİS LOGLARINI AÇ</button></div><div class="test-block"><div class="title">ECU BİLGİSİ</div><pre id="test_ecu" style="color:#ffea00;min-height:40px">-</pre><button class="btn test-btn" onclick="testGetEcu()">📋 ECU SORGULA</button></div><div class="test-block"><div class="title">RAW OBD KOMUTU</div><input id="test_cmd_input" class="test-input" placeholder="örn: 0100"><button class="btn test-btn" onclick="testSendRaw()">▶ GÖNDER</button><pre id="test_raw_out" style="min-height:40px">-</pre></div></div></div>
<div id="tab-abs" class="container">
 <div class="panel">
  <div class="module-head"><h2 style="color:#ff626f">🛑 ÇOK MARKALI ABS TEŞHİS MERKEZİ</h2><span class="module-status">EVRENSEL PROFİL ALTYAPISI · GÜVENLİ MOD</span></div>
  <p style="color:#aab5c2">ABS teşhis katmanı marka, model, yıl, ABS beyni ve fiziksel adaptör profiline göre çalışır. Motor ECU bağlantısı ABS bağlantısı yerine geçmez.</p>
  <div class="module-grid">
      <div class="test-block" id="j2534-panel">
    <div class="title">J2534 KÖPRÜSÜ · SCANMATIK / TACTRIX / UYUMLU DLL</div>
    <p style="color:#aab5c2">Windows J2534 DLL üzerinden CAN/K-Line kanalı açılır. Motor ECU için standart OBD-II Mode 03 arıza okuma ISO15765/CAN üzerinden denenebilir. Bu sorgu ABS/DCT taraması değildir. ABS özel sorgusu yalnızca doğrulanmış model protokolüyle açılır.</p>
    <div class="module-grid" style="grid-template-columns:repeat(3,minmax(0,1fr))">
     <div><label class="title">Protokol</label><select class="module-field" id="j2534_protocol"><option value="iso15765">ISO15765 · 500 kbit/s (OBD-II)</option><option value="can">CAN · 500 kbit/s (ham/pasif)</option><option value="iso9141">ISO9141 K-Line · 10400</option><option value="kwp2000">ISO14230 KWP2000 · 10400</option></select></div>
     <div><label class="title">Baud (isteğe bağlı)</label><input class="module-field" id="j2534_baud" type="number" placeholder="Varsayılan"></div>
     <div><label class="title">J2534 durumu</label><div class="module-field" id="j2534_state">Kontrol edilmedi</div></div>
    </div>
    <div class="module-actions"><button class="btn test-btn" onclick="j2534Status()">DURUMU KONTROL ET</button><button class="btn test-btn" onclick="j2534Open()">BAĞLAN</button><button class="btn test-btn" onclick="j2534Read()">HAT MESAJLARINI OKU</button><button class="btn test-btn" onclick="j2534ReadDtc()">MOTOR ECU OBD-II ARIZA OKU</button><button class="btn test-btn" onclick="j2534ClearDtc()">MOTOR ECU OBD-II ARIZA SİL</button><button class="btn test-btn" onclick="j2534Close()">KANALI KAPAT</button></div>
    <pre id="j2534_output" style="color:#00e5ff;white-space:pre-wrap;min-height:52px">DLL yolu SCANMATIK_J2534_DLL ortam değişkeniyle tanımlanabilir.</pre>
    <div class="test-block" id="can-sniffer-lab" style="margin-top:14px;border-color:#00e5ff">
     <div class="title">📡 CAN VERİ DİNLEME VE ÇÖZÜMLEME · SNIFFER</div>
     <p style="color:#aab5c2">Protokolü CAN · 500 kbit/s (ham/pasif) seçip J2534 kanalını açın. Ardından dinlemeyi başlatın. Uygulama CAN çerçevesi göndermez; yalnızca gelen kayıtları okur. Adaptörün elektriksel listen-only/ACK davranışı cihaz desteğine bağlıdır.</p>
     <div style="display:grid;grid-template-columns:minmax(220px,1fr) 2fr;gap:10px;margin:10px 0"><div><label class="title" for="can_profile">MOTOSİKLET CAN PROFİLİ</label><select id="can_profile" class="module-field" onchange="canSniffRenderDecoded()"><option value="none">Genel / bilinmeyen model</option><option value="yamaha_cp3_2021_2024">Yamaha CP3 · Tracer 9 / MT-09 / XSR900 (2021–2024)</option><option value="yamaha_r9_2026">Yamaha R9 (2026)</option><option value="yamaha_mt07_legacy">Yamaha MT-07 eski forum notları</option><option value="yamaha_mt07_2014_project">Yamaha MT-07 2014 açık kaynak profili</option><option value="yamaha_mt07_abs_notes">Yamaha MT-07 Euro 3 ABS · pasif kayıt</option><option value="ducati_panigale_mitsubishi">Ducati Panigale / Mitsubishi ECU CAN</option></select></div><div id="can_profile_note" class="module-field" style="white-space:normal;min-height:42px">Profil seçilmedi; ham kayıt devam eder.</div></div><div class="title" style="margin-top:12px">ARAŞTIRMA KAYNAKLARI</div><div style="font-size:12px;line-height:1.8;color:#b8c4d0"><a target="_blank" href="https://github.com/CSS-Electronics/can-bus-reverse-engineering-skills">CSS Electronics · CAN tersine mühendislik yöntemi</a> · <a target="_blank" href="https://github.com/collin80/SavvyCAN">SavvyCAN · CAN kayıt / DBC analiz aracı</a> · <a target="_blank" href="https://github.com/talss89/motorcycle-canbus-info">Motosiklet CAN bilgi koleksiyonu</a> · <a target="_blank" href="https://github.com/JackHat1/MT07_CAN_Project">MT-07 CAN projesi</a> · <a target="_blank" href="https://github.com/bratukh/yamaha-cp3-can">Yamaha CP3 CAN / DBC</a> · <a target="_blank" href="https://github.com/DeziderMesko/motocan">Yamaha R9 CAN / DBC kaynak</a> · <a href="/static/can_profiles/yamaha_r9_2026.dbc" download>Yamaha R9 2026 DBC dosyasını indir</a> · <a target="_blank" href="https://github.com/renatobo/DucatiPanigaleCanBus">Ducati Panigale CAN projesi</a> · <a target="_blank" href="https://zggtr.org/index.php?topic=20461.0">Kawasaki Concours CAN kayıt tartışması</a></div><div class="test-block" style="margin:12px 0;border-color:#ffcc80">
      <div class="title">MOTOR ECU / ABS ECU ORTAK KEŞİF VE PROTOKOL ÇÖZÜMLEME</div>
      <p style="color:#b8c4d0">Aynı pasif CAN kaydı motor ECU veya ABS incelemesi için seçilebilir. ISO-TP mesajları birleştirilir; OBD-II / UDS / KWP servis yanıtı adayları, CAN ID sıklığı ve değişen baytlar gösterilir. Bu analiz ECU'ya komut göndermez.</p>
      <div class="module-actions"><select id="ecu_capture_module" class="module-field" style="max-width:220px"><option value="engine">MOTOR ECU</option><option value="abs">ABS ECU</option><option value="general">GENEL / BİLİNMİYOR</option></select><button class="btn test-btn" onclick="canSniffAnalyzeProtocol()">🔬 SEÇİLİ MODÜLÜ ÇÖZÜMLE</button></div>
      <pre id="ecu_capture_analysis" style="white-space:pre-wrap;color:#ffcc80;max-height:300px;overflow:auto">Önce CAN dinleme kaydı alın, ardından Motor ECU veya ABS ECU seçerek analiz edin.</pre>
     </div>
     <div class="title" style="margin-top:12px">KAYNAKLI CAN SİNYAL ÇÖZÜMLEME</div><div id="can_sniff_decoded" style="overflow-x:auto;color:#ddd">Profil seçip dinlemeyi başlatın.</div><div class="module-actions"><button class="btn test-btn" id="can_sniff_start" onclick="canSniffStart()">▶ CAN DİNLEMEYİ BAŞLAT</button><button class="btn test-btn" id="can_sniff_stop" onclick="canSniffStop()" disabled>DİNLEMEYİ DURDUR</button><button class="btn test-btn" onclick="canSniffDownload()">⬇ KAYDI CSV İNDİR</button><button class="btn test-btn" onclick="canSniffClear()">KAYDI TEMİZLE</button></div>
     <div class="module-grid" style="grid-template-columns:repeat(4,minmax(0,1fr));margin-top:10px">
      <div class="module-card"><div class="k">Dinleme durumu</div><div class="v" id="can_sniff_status">Hazır değil</div></div><div class="module-card"><div class="k">Toplam çerçeve</div><div class="v" id="can_sniff_count">0</div></div><div class="module-card"><div class="k">Farklı CAN ID</div><div class="v" id="can_sniff_ids">0</div></div><div class="module-card"><div class="k">Kayıt süresi</div><div class="v" id="can_sniff_duration">0.0 sn</div></div>
     </div>
     <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px"><input id="can_sniff_marker_text" class="module-field" style="flex:1;min-width:190px" placeholder="Olay işareti: ön fren / arka fren / kontak..."><button class="btn test-btn" onclick="canSniffMark()">📍 OLAYI İŞARETLE</button></div>
     <pre id="can_sniff_markers" style="color:#ffcc80;white-space:pre-wrap;min-height:28px">Henüz olay işareti yok.</pre>
     <div class="title" style="margin-top:12px">EN ÇOK GÖRÜLEN CAN ID'LERİ · DEĞİŞEN BAYTLAR</div><div id="can_sniff_summary" style="overflow-x:auto;color:#ddd">Henüz veri alınmadı.</div>
     <div class="title" style="margin-top:12px">SON HAM CAN ÇERÇEVELERİ</div><div id="can_sniff_frames" style="overflow:auto;max-height:280px;color:#ddd">Dinleme başlatılmadı.</div><pre id="can_sniff_error" style="color:#ff8c96;white-space:pre-wrap"></pre>
    </div>
   </div>
<div class="module-card"><div class="k">ABS modül bağlantısı</div><div class="v" id="abs_conn_state">Bağlı değil</div><div class="hint">Motor ECU oturumundan tamamen ayrı</div></div>
   <div class="module-card"><div class="k">ABS ECU kimliği</div><div class="v" id="abs_ecu_id">Henüz okunmadı</div><div class="hint">Üretici / parça / yazılım kimliği</div></div>
   <div class="module-card"><div class="k">ABS iletişim profili</div><div class="v" id="abs_protocol">Henüz doğrulanmadı</div><div class="hint">CAN / K-Line / üreticiye özel taşıma</div></div>
  </div>
  <div class="test-block"><div class="title">MOTOSİKLET VE ABS PROFİLİ</div>
   <div class="module-grid" style="grid-template-columns:repeat(3,minmax(0,1fr))">
    <div><label class="title">Marka</label><select class="module-field" id="abs_brand"><option>Yamaha</option><option>Honda</option><option>Kawasaki</option><option>Suzuki</option><option>BMW Motorrad</option><option>KTM</option><option>Ducati</option><option>Triumph</option><option>CF Moto</option><option>Aprilia</option><option>Benelli</option><option>Royal Enfield</option><option>Harley-Davidson</option><option>Diğer</option></select></div>
    <div><label class="title">Model / kasa kodu</label><input class="module-field" id="abs_model" placeholder="Örn. XMAX 300 / NC750X / MT-09"></div>
    <div><label class="title">Model yılı</label><input class="module-field" id="abs_year" type="number" min="1980" max="2035" placeholder="2020"></div>
   </div>
   <div class="module-actions"><button class="btn test-btn" onclick="absCheckProfile()">🧭 ABS PROFİLİNİ KONTROL ET</button><button class="btn test-btn" onclick="absPassiveListenStart()">📡 ABS CAN PASİF DİNLEMEYİ BAŞLAT</button><button class="btn test-btn" disabled title="Araç ve adaptör için doğrulanmış ABS iletişim profili gerekli">🔌 ABS ECU'YA BAĞLAN</button><button class="btn test-btn" disabled title="Gerçek ABS ECU bağlantısı ve doğrulanmış okuma servisi gerekli">🔍 ABS ARIZA TARA</button><button class="btn test-btn" disabled title="Model özelinde silme servisi doğrulanmadan kapalı">🗑 ABS ARIZALARINI SİL</button></div>
   <div class="test-block" style="margin-top:12px;border-color:#ffb74d">
    <div class="title">ABS TAM İŞLEV KAPSAMI · MODEL BAZLI</div>
    <p style="color:#b8c4d0">Ortak bölüm: ECU kimliği, DTC okuma/silme, canlı veri, freeze-frame, sensör kontrolü, aktüatör testi ve servis prosedürleri. Her satır yalnızca seçilen motorun doğrulanmış profili destekliyorsa etkinleşir.</p>
    <div class="module-actions">
     <button class="btn test-btn" onclick="absLoadOperations()">İŞLEVLERİ / DESTEK DURUMUNU GÖSTER</button>
     <button class="btn test-btn" disabled title="OEM profili ve gerçek ABS haberleşme sürücüsü henüz doğrulanmadı">ABS CAN/K-LINE ECU OTURUMU</button>
     <button class="btn test-btn" disabled title="Araç özel DTC okuma servisi ve taşıma sürücüsü gerekir">ABS ECU'DAN DTC OKU</button>
     <button class="btn test-btn" disabled title="Önce DTC kaydı ve araç özel silme servisi gerekir">ABS DTC SİL</button>
     <button class="btn test-btn" disabled title="Aktif fren hidrolik testi güvenlik prosedürü ve OEM doğrulaması gerektirir">POMPA / VALF TESTİ</button>
    </div>
    <pre id="abs_operations_result" style="white-space:pre-wrap;color:#ffcc80">Marka/model/yıl seçip işlev kapsamını sorgulayın. Henüz doğrulanmayan işlemler kapalı kalır.</pre>
   </div>
   <pre id="abs_profile_result" style="color:#00e5ff;min-height:55px;white-space:pre-wrap">Marka, model ve yılı seçip profil kontrolü yapın. Bu işlem motosiklete komut göndermez.</pre>
    <pre id="abs_hardware_evidence" style="color:#b8d5ff;white-space:pre-wrap;min-height:0">Seçilen araç için belgelenmiş fiziksel ABS bağlantı bilgisi varsa burada gösterilir.</pre>
   <div class="module-grid" style="grid-template-columns:minmax(180px,1fr) auto;align-items:end;margin-top:12px">
    <div><label class="title">ABS arıza kodu (manuel açıklama)</label><input class="module-field" id="abs_dtc_code" placeholder="Örn. 11 / 25 / 53" maxlength="12"></div>
    <button class="btn test-btn" onclick="absDecodeDtc()">📖 KOD AÇIKLAMASINI BUL</button>
   </div>
   <pre id="abs_dtc_result" style="color:#ffcc80;white-space:pre-wrap;min-height:42px">Yalnızca veri tabanında doğrulanmış araç profilleri için açıklama gösterilir. ECU'dan kod okunmaz.</pre>
  </div>
  <div class="module-note"><b>Güvenlik kilidi:</b> Profil kontrolü yalnızca yerel veri tabanına bakar. Doğrulanmış OEM dokümanı, pinout, protokol/adresleme, adaptör uyumluluğu ve gerçek ECU yanıtı olmadan ABS'ye bağlantı, arıza silme veya pompa/valf komutu gönderilmez.</div>
  <div class="test-block" style="margin-top:16px">
    <div class="title">ABS PASİF CAN KAYDI İNCELEME</div>
    <p style="color:#aab5c2">SavvyCAN veya uyumlu bir cihazdan önceden kaydedilmiş candump / Vector ASC / başlıklı CSV dosyasını seçin. Program yalnızca dosyayı analiz eder; motosiklete bağlanmaz ve CAN mesajı göndermez.</p>
    <input id="abs_can_log_file" type="file" accept=".log,.txt,.asc,.csv" style="color:#ddd;max-width:100%">
    <button class="btn test-btn" onclick="absAnalyzeLog()">📂 KAYDI ANALİZ ET</button>
    <pre id="abs_can_log_result" style="color:#00e5ff;white-space:pre-wrap;min-height:70px">Henüz kayıt seçilmedi.</pre>
    <div id="abs_can_log_table" style="overflow-x:auto"></div>
   </div>
   <h3 style="color:#ff8c96">ABS canlı veri ekranı</h3>
  <div class="module-list">
   <div class="module-item"><b>Ön tekerlek hızı</b><span id="abs_front_speed">ABS ECU bağlantısı bekleniyor · km/h</span></div>
   <div class="module-item"><b>Arka tekerlek hızı</b><span id="abs_rear_speed">ABS ECU bağlantısı bekleniyor · km/h</span></div>
   <div class="module-item"><b>ABS pompa / valf durumu</b><span>Yalnızca doğrulanmış canlı veri parametresi varsa okunacak</span></div>
   <div class="module-item"><b>Fren müşürü / basınç</b><span>Seçilen ABS kontrol ünitesinin desteklediği parametrelere göre</span></div>
  </div>
  <h3 style="color:#ff8c96;margin-top:22px">PLANLANAN ABS TEŞHİS KAPSAMI</h3>
  <div class="module-list">
   <div class="module-item"><b>ABS DTC okuma / açıklama</b><span>Her marka-model-yıl ve ABS beyni için ayrı, doğrulanmış kod sözlüğü</span></div>
   <div class="module-item"><b>Arıza silme ve tekrar okuma</b><span>Yalnızca üretici prosedürü ve ECU yanıtı doğrulanmış profillerde</span></div>
   <div class="module-item"><b>Canlı tekerlek hızları</b><span>Ön/arka sensör verileri ve zaman damgalı karşılaştırma</span></div>
   <div class="module-item"><b>Sensör ve besleme kontrolleri</b><span>Ölçüm sınırları ilgili servis dokümanından alınacak</span></div>
   <div class="module-item"><b>Pompa / valf aktif testleri</b><span>Şimdilik kapalı; güvenli servis prosedürü ve onaylı rutin kimliği şart</span></div>
  </div>
 </div>
</div>
<div id="tab-dct" class="container">
 <div class="panel">
  <div class="module-head"><h2 style="color:#00d9f5">⚙️ HONDA DCT ŞANZIMAN KONTROLÜ</h2><span class="module-status">DCT PROFİLİ BEKLİYOR · KOMUT GÖNDERİLMİYOR</span></div>
  <p style="color:#aab5c2">DCT, motor ECU'sundan ayrı bir şanzıman kontrol modülü olarak ele alınır. Motor ECU bağlantısı DCT bağlantısı yerine geçmez.</p>
  <div class="module-grid">
   <div class="module-card"><div class="k">DCT modül bağlantısı</div><div class="v" id="dct_conn_state">Bağlı değil</div><div class="hint">Ayrı TCU / DCT iletişim oturumu</div></div>
   <div class="module-card"><div class="k">DCT ECU kimliği</div><div class="v" id="dct_ecu_id">Henüz okunmadı</div><div class="hint">Kontrol ünitesi ve kalibrasyon kimliği</div></div>
   <div class="module-card"><div class="k">Vites / kavrama durumu</div><div class="v" id="dct_gear_state">Veri yok</div><div class="hint">Yalnızca ECU'dan gerçek zamanlı okunursa</div></div>
  </div>
  <div class="test-block"><div class="title">HONDA DCT ARAÇ PROFİLİ</div>
   <div class="module-grid" style="grid-template-columns:repeat(3,minmax(0,1fr))">
    <div><label class="title">Model</label><select class="module-field" id="dct_model"><option value="">Model seçin</option><option>VFR1200F DCT</option><option>VFR1200X Crosstourer DCT</option><option>NC700S DCT</option><option>NC700X DCT</option><option>NC750S DCT</option><option>NC750X DCT</option><option>Integra DCT</option><option>CTX700 DCT</option><option>NM4 Vultus DCT</option><option>CRF1000L Africa Twin DCT</option><option>CRF1100L Africa Twin DCT</option><option>X-ADV DCT</option><option>Forza 750 DCT</option><option>NT1100 DCT</option><option>CMX1100 Rebel DCT</option><option>Gold Wing DCT</option><option>Diğer Honda DCT</option></select></div>
    <div><label class="title">Model yılı</label><input class="module-field" id="dct_year" type="number" min="2000" max="2035" placeholder="2020"></div>
    <div><label class="title">Şanzıman / ECU bilgisi</label><input class="module-field" id="dct_ecu_type" placeholder="Bilinmiyorsa boş bırakın"></div>
   </div>
   <div class="module-actions"><button class="btn test-btn" onclick="openDctDyno()">📈 DCT DİNAMOMETRE TESTİNE GEÇ</button><button class="btn test-btn" onclick="dctCheckProfile()">📚 DCT PROFİLİNİ KONTROL ET</button><button class="btn test-btn" disabled title="HDS uyumlu fiziksel taşıma ve adresleme doğrulanmadı">🔌 DCT BAĞLAN</button><button class="btn test-btn" disabled>🔍 DCT ARIZA TARA</button><button class="btn test-btn" disabled>🗑 DCT ARIZALARINI SİL</button></div>
   <pre id="dct_profile_result" style="color:#ffcc80;white-space:pre-wrap;margin-top:12px">Honda NC750X DCT 2020 servis profili yerel olarak kontrol edilebilir.</pre>
   <div class="module-actions"><input id="dct_dtc_code" class="module-field" style="max-width:180px" placeholder="DCT kodu (örn. 21-1)"><button class="btn test-btn" onclick="dctDecodeDtc()">KODU AÇIKLA</button></div>
   <pre id="dct_dtc_result" style="color:#aab5c2;white-space:pre-wrap">Yalnızca kaynakla doğrulanmış DCT kodları çevrimdışı açıklanır. ECU'ya bağlanmaz.</pre>
  </div>
  <div class="module-note"><b>Güvenlik kilidi:</b> DCT kavrama, vites seçici ve adaptasyon komutları model/yıl/TCU kimliğine bağlıdır. Doğrulanmamış reset veya kalibrasyon komutu gönderilmeyecek.</div>
  <div class="test-block" id="dct-capture-lab">
   <div class="title">KENDİ DCT CİHAZIMIZ – PASİF HABERLEŞME KAYDI</div>
   <p style="color:#aab5c2">TX/RX veya CAN kayıtlarını yükleyip inceleyin. Bu ekran ECU'ya komut göndermez.</p>
   <div class="module-actions"><input id="dct_capture_file" type="file" accept=".log,.txt,.asc,.csv" class="module-field"><button class="btn test-btn" onclick="dctAnalyzeCapture()">KAYDI ANALİZ ET</button></div>
   <pre id="dct_capture_result" style="color:#ffcc80;white-space:pre-wrap;max-height:300px;overflow:auto">Kayıt bekleniyor. Örnek: TX: 81 F1 ... / RX: 83 F1 ...</pre>
  </div>

  <div class="test-block" id="dct-tool-research">
   <div class="title">DCT TEŞHİS / SERVİS ARAÇLARI – ARAŞTIRMA KAYITLARI</div>
   <div class="module-list">
    <div class="module-item"><b><a href="https://biketune.app/bike/honda-nc-750-cc/" target="_blank" rel="noopener">Biketune – NC750 DCT</a></b><span>NC750S DCT / NC750X DCT model listesi. DTC okuma; Pro ile silme; STN + K-Line adaptör. DCT kontrol ünitesi yanıtı araçta ayrıca doğrulanmalı.</span></div>
    <div class="module-item"><b><a href="https://fxtul.com/products/fxtul-m3-motorcycle-diagnostic-tool-obd2-code-reader-scan-tool-for-honda-yamaha-motorcycle" target="_blank" rel="noopener">FXTUL M3-A</a></b><span>Genel motosiklet DTC okuma/silme ve canlı veri aracı. Honda DCT TCM erişimi henüz kanıtlanmadı; motor ECU taraması DCT taraması sayılmaz.</span></div>
    <div class="module-item"><b><a href="https://www.healtech-electronics.com/products/dct/" target="_blank" rel="noopener">HealTech DCT Dyno Tool</a></b><span>DCT-H01 / DCT-H02: dinamometre modunu etkinleştirir. DTC okuma veya silme cihazı değildir. Parça numarası model/yıla göre seçilmeli.</span></div>
    <div class="module-item"><b><a href="https://github.com/Sirawit45/josave-tuner" target="_blank" rel="noopener">JOSAVE TUNER – GitHub</a></b><span>Honda ECM K-Line haberleşmesi ve ECM DTC kodu için kaynak kodu. DCT TCM protokolü doğrulanmadı; ECM komutları DCT'ye gönderilmez.</span></div>
   </div>
   <div class="module-note"><b>Kanıt seviyesi:</b> Bu liste araştırma referansıdır, uyumluluk garantisi değildir. Gerçek DCT TCU/PCM kimliği ve yanıtı doğrulanmadan bağlanma, tarama, silme, adaptasyon veya aktüatör komutu etkinleştirilmez.</div>
  </div>

  <div class="test-block" id="dct-communication-comparison">
   <div class="title">BİKETUNE / FXTUL M3-A – DCT HABERLEŞME KARŞILAŞTIRMASI</div>
   <div class="module-list">
    <div class="module-item"><b>Biketune – NC750S/X DCT</b><span>Model sayfasında NC750S DCT ve NC750X DCT listeli. Honda K-Line için STN tabanlı K-Line adaptör ve Honda 4/6 pin kablo istiyor. Yanıt veren bilinen üniteleri tarıyor; fakat kamuya açık sayfada DCT TCU adı, kimliği veya ham yanıt baytları gösterilmiyor. <a href="https://biketune.app/bike/honda-nc-750-cc/" target="_blank" rel="noopener">Kaynak</a></span></div>
    <div class="module-item"><b>FXTUL M3-A</b><span>Kılavuz Quick Test ile erişilebilir sistemleri tarama; modül bilgisi, DTC okuma/silme, canlı veri, freeze-frame ve aktüatör testini belgeliyor. Örnek ekranlar ECM/ABS/BODY/IMMO; Honda DCT/TCM adı, model uyumluluğu veya ham TCM yanıtı yok. <a href="https://manuals.plus/asin/B0C2CMGFS4.pdf" target="_blank" rel="noopener">Kılavuz</a></span></div>
    <div class="module-item"><b>Honda MCS – referans</b><span>Honda, MCS'nin PGM-FI, ABS ve DCT dahil tüm ECU'ları teşhis ettiğini söylüyor. Aynı açıklama genel GST'leri normalde emisyonla ilgili PGM-FI ile sınırlar; 2020 X-ADV servis kitabı ise o modelin DCT DTC işlemleri için GST veya MCS diyor. GST kabiliyeti model/cihaz bazında ele alınmalı. <a href="https://global.honda/en/motorcycle-aftersales/plusone/202506.html" target="_blank" rel="noopener">Honda</a> · <a href="https://www.scribd.com/document/852109922/2020-Honda-x-Adv750-Workshop-Manual" target="_blank" rel="noopener">X-ADV servis kitabı</a></span></div>
   </div>
   <div class="module-note"><b>Gerçek yanıt karşılaştırması:</b> Biketune: yayımlanmış ham DCT TCU yanıtı yok. FXTUL M3-A: yayımlanmış ham DCT TCU yanıtı yok. Honda MCS: DCT ECU erişimi resmen doğrulanmış, ham protokol baytları kamuya açık değil. CAN ID, K-Line hedef adresi, init dizisi, servis ID veya checksum tahmin edilmedi.</div>
   <div class="module-note"><b>Servis kitabındaki DCT kod örnekleri (2020 X-ADV):</b> P0197/P0198 (EOT sensörü/TCM), P0775 (No.2 lineer solenoid/TCM), P170A/P170B (vites kontrol motoru/TCM). Bunlar ham ECU yanıtı değil, servis kitabındaki arıza kayıtlarıdır.</div>
  </div>
  <h3 style="color:#00d9f5">DCT canlı veri</h3>
  <div class="module-list">
   <div class="module-item"><b>Seçili vites</b><span id="dct_selected_gear">Veri bekleniyor</span></div>
   <div class="module-item"><b>Vites kolu / seçici konumu</b><span>ECU destekliyorsa okunacak</span></div>
   <div class="module-item"><b>Kavrama A sıcaklığı / durumu</b><span>Gerçek TCU parametresi bulunduğunda</span></div>
   <div class="module-item"><b>Kavrama B sıcaklığı / durumu</b><span>Gerçek TCU parametresi bulunduğunda</span></div>
   <div class="module-item"><b>Giriş / çıkış mili devri</b><span>Desteklenen canlı veri parametreleriyle</span></div>
   <div class="module-item"><b>Yağ / şanzıman sıcaklığı</b><span>TCU tarafından yayınlanıyorsa</span></div>
  </div>
  <h3 style="color:#00d9f5;margin-top:22px">DCT servis fonksiyonları</h3>
  <div class="module-list">
   <div class="module-item"><b>Kavrama adaptasyonu</b><span>Model özelinde resmi prosedür ve ön koşullarla.</span></div>
   <div class="module-item"><b>Vites seçici öğrenmesi</b><span>Yalnızca desteklenen TCU yazılımında.</span></div>
   <div class="module-item"><b>Aktüatör testleri</b><span>Güvenlik şartları ve doğrulanmış rutin kimlikleriyle.</span></div>
   <div class="module-item"><b>DTC geçmişi / rapor</b><span>Okunan kodlar, açıklamalar ve zaman damgaları.</span></div>
  </div>
 </div>
</div>
<div id="tab-ecu-lab" class="container">
 <div class="panel">
  <div class="module-head"><h2 style="color:#00e5ff">📡 GELİŞMİŞ ECU HABERLEŞME LABORATUVARI</h2><span class="module-status">ELM327 · K-LINE/SERIAL · J2534</span></div>
  <p style="color:#aab5c2">Bu bölüm gerçek adaptörlerden gelen haberleşmeyi kaydeder. ELM327 kaydı uygulamanın gönderdiği OBD komutları ve gerçek cevaplarıdır; tam CAN hattı dinlemesi değildir. Seri/K-Line ekranı yalnızca RX dinler ve ECU'ya komut göndermez. J2534 ekranı sürücünün verdiği mesajları okur.</p>
  <div class="test-block" style="border-color:#00e5ff"><div class="title">1 · VIECAR / ELM327 WI-FI TX/RX KAYDI</div>
   <div class="module-actions"><button class="btn test-btn" onclick="ecuLabRefreshLog()">TX/RX KAYDINI YENİLE</button><button class="btn test-btn" onclick="ecuLabExport()">KAYDI CSV İNDİR</button></div>
   <pre id="ecu_lab_elm_status" style="color:#00e5ff;white-space:pre-wrap">Gerçek ELM327 bağlantısı açıldığında gönderilen komutlar ve cevaplar burada görünür.</pre>
  </div>
  <div class="test-block" style="border-color:#ffaa00"><div class="title">2 · K-LINE / COM HAM RX DİNLEYİCİ · GÖNDERİM YOK</div>
   <p style="color:#aab5c2">COM portu ve baud hızını seçin. Bu mod yalnızca seri hattan gelen baytları kaydeder; 5-baud/fast-init veya ECU sorgusu yapmaz. K-Line hattında veri görmek için başka bir teşhis cihazının sorgu başlatması gerekebilir.</p>
   <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:8px"><select id="ecu_lab_com" class="module-field"><option value="">COM portları yükleniyor...</option></select><select id="ecu_lab_baud" class="module-field"><option>10400</option><option>9600</option><option>4800</option><option>19200</option><option>38400</option><option>115200</option></select></div>
   <div class="module-actions"><button class="btn test-btn" onclick="ecuLabSerialPorts()">PORTLARI TARA</button><button class="btn test-btn" onclick="ecuLabSerialStart()">RX DİNLEMEYİ BAŞLAT</button><button class="btn test-btn" onclick="ecuLabSerialStop()">DİNLEMEYİ DURDUR</button></div><pre id="ecu_lab_serial_status" style="color:#ffaa00;white-space:pre-wrap">Seri port bekleniyor.</pre>
  </div>
  <div class="test-block" style="border-color:#00ff99"><div class="title">3 · J2534 PASİF CAN / K-LINE / ISO15765 KANALI</div>
   <p style="color:#aab5c2">J2534 kanalını seçip açın. HAT MESAJI OKU yalnızca sürücünün RX kuyruğunu okur. CAN ham çerçeve; ISO15765 OBD/CAN taşıması; ISO9141/KWP2000 K-Line sürücü desteği gerektirir.</p>
   <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px"><select id="ecu_lab_j2534_protocol" class="module-field"><option value="can">CAN · ham RX</option><option value="iso15765">ISO15765 · OBD/CAN</option><option value="iso9141">ISO9141 · K-Line</option><option value="kwp2000">KWP2000 · K-Line</option></select><input id="ecu_lab_j2534_baud" class="module-field" type="number" placeholder="Baud (boş = varsayılan)"></div>
   <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;margin-top:8px"><input id="ecu_lab_j2534_target" class="module-field" value="33" placeholder="Hedef adres HEX"><input id="ecu_lab_j2534_source" class="module-field" value="F1" placeholder="Tester adres HEX"></div>
   <div class="module-actions"><button class="btn test-btn" onclick="ecuLabJ2534Open()">KANALI AÇ</button><button class="btn test-btn" onclick="ecuLabJ2534Init()">K-LINE / KWP INIT</button><button class="btn test-btn" onclick="ecuLabJ2534Read()">HAM RX OKU</button><button class="btn test-btn" onclick="ecuLabJ2534Live()">MOTOR CANLI PID OKU</button><button class="btn test-btn" onclick="ecuLabJ2534Close()">KANALI KAPAT</button></div><pre id="ecu_lab_j2534_status" style="color:#00ff99;white-space:pre-wrap">J2534 kanalı henüz açılmadı.</pre>
  </div>
  <div class="test-block"><div class="title">SON HABERLEŞME KAYITLARI</div><div class="module-actions"><button class="btn test-btn" onclick="ecuLabRefreshLog()">YENİLE</button><button class="btn test-btn" onclick="ecuLabClearLog()">BELLEK KAYDINI TEMİZLE</button></div><div id="ecu_lab_log_table" style="overflow:auto;max-height:520px;color:#ddd">Henüz kayıt yok.</div></div>
 </div>
</div>
<div id="tab-dyno" class="container"><div class="panel">
 <div class="module-head"><h2 style="color:#00ff00">📈 DYNO / PERFORMANS TESTİ</h2><span class="module-status">GERÇEK ECU TELEMETRİ KAYDI</span></div>
 <p style="color:#aab5c2">Canlı ECU verisi zaman damgasıyla kaydedilir. Teker gücü/tork hesabı için ayrıca gerçek dinamometre yük kuvveti ve teker/roller hızı gerekir. Yalnızca motor RPM'sinden beygir veya tork üretilmez.</p>
 <div class="module-grid">
  <div class="module-card"><div class="k">OTURUM</div><div class="v" id="dyno_state">Hazır değil</div><div class="hint">Gerçek ECU bağlantısı gerekir</div></div>
  <div class="module-card"><div class="k">KAYITLI ÖRNEK</div><div class="v" id="dyno_samples">0</div><div class="hint">Zaman damgalı telemetri</div></div>
  <div class="module-card"><div class="k">SON RPM / HIZ</div><div class="v" id="dyno_rpm_speed">—</div><div class="hint">ECU yanıtından</div></div>
  <div class="module-card"><div class="k">TEKER GÜCÜ / TORK</div><div class="v" id="dyno_power_torque">Ölçüm yok</div><div class="hint">Harici yük sensörü olmadan hesaplanmaz</div></div>
 </div>
 <div class="test-block"><div class="title">TEST PROFİLİ</div>
  <div class="module-grid" style="grid-template-columns:repeat(3,minmax(0,1fr))">
   <div><label class="title">DYNO TİPİ</label><select id="dyno_mode" class="module-field"><option value="standard">Standart motosiklet</option><option value="honda_dct">Honda DCT dinamometre</option></select></div>
   <div><label class="title">MODEL</label><input id="dyno_model" class="module-field" placeholder="NC750X DCT"></div>
   <div><label class="title">YIL</label><input id="dyno_year" class="module-field" type="number" placeholder="2020"></div>
  </div>
  <div class="module-grid" style="grid-template-columns:repeat(2,minmax(0,1fr))">
   <div><label class="title">ROLLER YARIÇAPI (metre)</label><input id="dyno_radius" class="module-field" type="number" step="0.001" value="0.300"></div>
   <div><label class="title">HARİCİ YÜK KUVVETİ (Newton)</label><input id="dyno_force" class="module-field" type="number" step="0.1" placeholder="Yük sensörü ölçümü"></div>
  </div>
  <p style="color:#aab5c2">Yük kuvveti girişi şimdilik manuel ölçümdür. Otomatik yük hücresi entegrasyonu için dinamometre cihazının seri veri protokolü gerekir.</p>
  <div id="dyno_dct_warning" class="module-note" style="display:none;border-color:#ff9800"><b>HONDA DCT GÜVENLİK KONTROLÜ:</b> DCT dyno modu bu uygulama tarafından henüz etkinleştirilmiyor. Honda HDS veya uyumlu DCT Dyno aracıyla modun etkin olduğunu doğrulamadan DCT testini başlatmayın. Üretici kılavuzundaki model özel talimatlarını izleyin. <a href="https://www.techmoto.de/anleitungen/DCT_Manual_en.pdf" target="_blank" rel="noopener">DCT Dyno Tool kılavuzu</a></div>
  <label style="display:flex;gap:10px;align-items:center;color:#ddd;margin:12px 0"><input type="checkbox" id="dyno_dct_confirm"> DCT dyno modunun uyumlu ekipmanla etkin olduğunu ve motosikletin dinamometreye güvenli bağlandığını doğruluyorum.</label>
  <div class="module-actions"><button class="btn test-btn" onclick="dynoStart()">▶ GERÇEK DYNO KAYDINI BAŞLAT</button><button class="btn test-btn" onclick="dynoStop()">■ KAYDI DURDUR</button><button class="btn test-btn" onclick="dynoLoadApply()">YÜK / ROLLER BİLGİSİNİ UYGULA</button><button class="btn test-btn" onclick="dynoExport()">CSV RAPORU İNDİR</button></div>
  <pre id="dyno_message" style="color:#00e5ff;white-space:pre-wrap">Önce motor ECU'suna gerçek bağlantı kurun. Sonra test profilini seçin.</pre>
 </div>
 <div class="test-block"><div class="title">DYNO GRAFİĞİ · RPM / HIZ / TEKER GÜCÜ</div><div style="height:300px"><canvas id="dynoChart"></canvas></div></div>
 <div class="test-block"><div class="title">SON ÖLÇÜM DETAYI</div><pre id="dyno_last_sample" style="color:#00ff99;white-space:pre-wrap">Henüz ölçüm yok.</pre></div>
</div></div>
<div id="tab-gk" class="container"><div class="panel"><h3 style="color:#ffaa00;margin-top:0">🔧 GELİŞMİŞ SERVİS TESTLERİ</h3><div class="test-block"><div class="title">BAĞLANTI (Araç Seçimine Göre)</div><button class="btn test-btn" onclick="gkConnect()">🔌 GELİŞMİŞ BAĞLANTI KUR</button><pre id="gk_conn_out" style="color:#00e5ff;min-height:30px">-</pre></div><div class="test-block"><div class="title">AKTÜATÖR TESTLERİ</div><pre id="gk_test_out" style="color:#ffaa00;min-height:30px">Güvenlik nedeniyle kapalı: model/ECU özel komut profili doğrulanınca etkinleştirilecek.</pre></div><div class="test-block"><div class="title">ABS MODÜLÜ</div><pre id="gk_d07_out" style="color:#ffaa00;min-height:30px">Ayrı ABS bağlantı katmanı hazırlanacak. Genel D07 komutu kullanılmayacak.</pre></div></div></div>
<script>
var socket = io();
function ecuLabEsc(v){return String(v==null?'':v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function ecuLabRefreshLog(){fetch('/api/ecu-lab/log?limit=300').then(r=>r.json()).then(d=>{const rows=d.entries||[],out=document.getElementById('ecu_lab_log_table');if(!rows.length){out.textContent='Henüz TX/RX kaydı yok. Gerçek ELM327 bağlantısı açıp sorgu çalıştırın.';return;}out.innerHTML='<table style="width:100%;border-collapse:collapse;font:12px Consolas,monospace"><thead><tr><th>ZAMAN</th><th>TAŞIMA</th><th>YÖN</th><th>VERİ</th><th>DETAY</th></tr></thead><tbody>'+rows.slice().reverse().map(x=>'<tr style="border-bottom:1px solid #333"><td>'+ecuLabEsc(x.time)+'</td><td>'+ecuLabEsc(x.transport)+'</td><td>'+ecuLabEsc(x.direction)+'</td><td style="max-width:420px;overflow-wrap:anywhere">'+ecuLabEsc(x.payload)+'</td><td>'+ecuLabEsc(x.detail||'')+'</td></tr>').join('')+'</tbody></table>';document.getElementById('ecu_lab_elm_status').textContent='Toplam kayıt: '+d.total+' · Gösterilen: '+rows.length+' · ELM327 TX/RX + seri RX.';}).catch(e=>document.getElementById('ecu_lab_elm_status').textContent='Kayıt okunamadı: '+e);}
function ecuLabExport(){fetch('/api/ecu-lab/log?limit=5000').then(r=>r.json()).then(d=>{const cols=['time','transport','direction','payload','detail'];const csv=[cols.join(',')].concat((d.entries||[]).map(x=>cols.map(k=>'"'+String(x[k]||'').replace(/"/g,'""')+'"').join(','))).join('\r\n');const a=document.createElement('a');a.href=URL.createObjectURL(new Blob(['\ufeff'+csv],{type:'text/csv;charset=utf-8'}));a.download='seyfi_usta_ecu_haberlesme.csv';a.click();URL.revokeObjectURL(a.href);}).catch(e=>alert('CSV oluşturulamadı: '+e));}
function ecuLabClearLog(){if(!confirm('Bellekteki haberleşme kaydı temizlensin mi?'))return;fetch('/api/ecu-lab/log/clear',{method:'POST'}).then(()=>ecuLabRefreshLog());}
function ecuLabSerialPorts(){fetch('/api/ecu-lab/serial/ports').then(r=>r.json()).then(d=>{const sel=document.getElementById('ecu_lab_com');sel.innerHTML='';(d.ports||[]).forEach(p=>{const o=document.createElement('option');o.value=p.device;o.textContent=p.device+' · '+(p.description||'Seri port');sel.appendChild(o);});if(!d.ports||!d.ports.length){const o=document.createElement('option');o.value='';o.textContent=d.available?'COM port bulunamadı':'pyserial kurulu değil';sel.appendChild(o);}document.getElementById('ecu_lab_serial_status').textContent=d.available?'Bulunan port: '+(d.ports||[]).length:'pyserial bulunamadı. py -m pip install pyserial';}).catch(e=>document.getElementById('ecu_lab_serial_status').textContent='Port tarama hatası: '+e);}
function ecuLabSerialStart(){const port=document.getElementById('ecu_lab_com').value,baud=document.getElementById('ecu_lab_baud').value;if(!port){alert('Önce kullanılacak COM portunu seçin.');return;}fetch('/api/ecu-lab/serial/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({port:port,baud:baud})}).then(r=>r.json()).then(d=>document.getElementById('ecu_lab_serial_status').textContent=d.message||JSON.stringify(d)).catch(e=>document.getElementById('ecu_lab_serial_status').textContent='Başlatma hatası: '+e);}
function ecuLabSerialStop(){fetch('/api/ecu-lab/serial/stop',{method:'POST'}).then(r=>r.json()).then(d=>document.getElementById('ecu_lab_serial_status').textContent=d.message||JSON.stringify(d)).catch(e=>document.getElementById('ecu_lab_serial_status').textContent='Durdurma hatası: '+e);}
function ecuLabJ2534Open(){const protocol=document.getElementById('ecu_lab_j2534_protocol').value,baud=document.getElementById('ecu_lab_j2534_baud').value;fetch('/api/j2534/open',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({protocol:protocol,baud:baud||null})}).then(r=>r.json()).then(d=>document.getElementById('ecu_lab_j2534_status').textContent=d.message+'\n'+JSON.stringify(d.status||{},null,2)).catch(e=>document.getElementById('ecu_lab_j2534_status').textContent='J2534 hata: '+e);}
function ecuLabJ2534Init(){const p=document.getElementById('ecu_lab_j2534_protocol').value;const type=p==='iso9141'?'five_baud':(p==='kwp2000'?'fast_init':'');if(!type){alert('Önce ISO9141 veya KWP2000 kanalını açın. CAN/ISO15765 için K-Line init kullanılmaz.');return;}const payload={init_type:type,target_address:document.getElementById('ecu_lab_j2534_target').value,source_address:document.getElementById('ecu_lab_j2534_source').value};if(!confirm((type==='five_baud'?'ISO9141 5-baud init':'KWP2000 fast init')+' başlatılacak. Hedef: 0x'+payload.target_address+'. Bu işlem ECU ile haberleşme başlatır; model-özel DCT/ABS komutu değildir. Devam?'))return;fetch('/api/j2534/kline/init',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}).then(r=>r.json()).then(d=>document.getElementById('ecu_lab_j2534_status').textContent=JSON.stringify(d,null,2)).catch(e=>document.getElementById('ecu_lab_j2534_status').textContent='K-Line init hatası: '+e);}
function ecuLabJ2534Live(){fetch('/api/j2534/obd/live',{method:'POST'}).then(r=>r.json()).then(d=>{let lines=['KAPSAM: '+d.scope,d.note||'',...(d.values||[]).map(x=>x.name+': '+x.value+' '+x.unit),'',...(d.errors||[])];document.getElementById('ecu_lab_j2534_status').textContent=lines.join('\n')||JSON.stringify(d,null,2);}).catch(e=>document.getElementById('ecu_lab_j2534_status').textContent='Canlı PID hatası: '+e);}
function ecuLabJ2534Read(){fetch('/api/j2534/read?timeout_ms=1000').then(r=>r.json()).then(d=>document.getElementById('ecu_lab_j2534_status').textContent=JSON.stringify(d,null,2)).catch(e=>document.getElementById('ecu_lab_j2534_status').textContent='Okuma hatası: '+e);}
function ecuLabJ2534Close(){fetch('/api/j2534/close',{method:'POST'}).then(r=>r.json()).then(d=>document.getElementById('ecu_lab_j2534_status').textContent=d.message||JSON.stringify(d)).catch(e=>document.getElementById('ecu_lab_j2534_status').textContent='Kapatma hatası: '+e);}
let dynoChartInstance=null;
function openDctDyno(){document.getElementById('dyno_mode').value='honda_dct';document.getElementById('dyno_model').value=document.getElementById('dct_model').value||'Honda DCT';document.getElementById('dyno_year').value=document.getElementById('dct_year').value||'';document.getElementById('dyno_dct_warning').style.display='block';switchTab('dyno');dynoRefresh();}
function dynoRefresh(){
 fetch('/api/dyno/status').then(r=>r.json()).then(d=>{
  document.getElementById('dyno_state').textContent=d.active?'KAYIT AKTİF':'DURDURULDU / HAZIR';
  document.getElementById('dyno_samples').textContent=d.sample_count||0;
  const x=d.last_sample;
  if(x){
   document.getElementById('dyno_rpm_speed').textContent=(x.rpm==null?'—':x.rpm+' rpm')+' / '+(x.speed_kmh==null?'—':x.speed_kmh+' km/h');
   document.getElementById('dyno_power_torque').textContent=x.wheel_power_kw==null?'Yük verisi yok':x.wheel_power_kw+' kW / '+x.wheel_torque_nm+' Nm';
   document.getElementById('dyno_last_sample').textContent=JSON.stringify(x,null,2);
  }
  if(d.note)document.getElementById('dyno_message').textContent=d.note;
  const samples=d.recent_samples||[];
  const canvas=document.getElementById('dynoChart');
  if(canvas&&window.Chart){
   const labels=samples.map(x=>x.elapsed_s);
   const datasets=[
    {label:'RPM',data:samples.map(x=>x.rpm),borderColor:'#00e5ff',yAxisID:'y',pointRadius:0,tension:0.15},
    {label:'Hız km/h',data:samples.map(x=>x.speed_kmh),borderColor:'#ffea00',yAxisID:'y1',pointRadius:0,tension:0.15},
    {label:'Teker gücü kW',data:samples.map(x=>x.wheel_power_kw),borderColor:'#00ff88',yAxisID:'y1',pointRadius:0,tension:0.15}
   ];
   if(!dynoChartInstance){dynoChartInstance=new Chart(canvas.getContext('2d'),{type:'line',data:{labels:labels,datasets:datasets},options:{responsive:true,maintainAspectRatio:false,animation:false,interaction:{intersect:false,mode:'index'},scales:{x:{title:{display:true,text:'Süre (s)'}},y:{type:'linear',position:'left',title:{display:true,text:'RPM'}},y1:{type:'linear',position:'right',title:{display:true,text:'Hız / kW'},grid:{drawOnChartArea:false}}}}});}
   else{dynoChartInstance.data.labels=labels;dynoChartInstance.data.datasets.forEach((ds,i)=>ds.data=datasets[i].data);dynoChartInstance.update('none');}
  }
 }).catch(e=>document.getElementById('dyno_message').textContent='DYNO durum hatası: '+e);
}
function dynoStart(){
 const mode=document.getElementById('dyno_mode').value;
 const payload={mode:mode,model:document.getElementById('dyno_model').value,year:document.getElementById('dyno_year').value,
  wheel_radius_m:document.getElementById('dyno_radius').value,dct_mode_confirmed:document.getElementById('dyno_dct_confirm').checked};
 if(mode==='honda_dct'&&!payload.dct_mode_confirmed){alert('Honda DCT dyno modunun uyumlu ekipmanla etkin olduğunu doğrulamadan test başlatılamaz.');return;}
 fetch('/api/dyno/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}).then(async r=>{const d=await r.json();document.getElementById('dyno_message').textContent=d.message||d.error||JSON.stringify(d);if(!r.ok)throw new Error(d.error||'DYNO başlatılamadı');dynoRefresh();}).catch(e=>document.getElementById('dyno_message').textContent='DYNO başlatma hatası: '+e);
}
function dynoStop(){fetch('/api/dyno/stop',{method:'POST'}).then(r=>r.json()).then(d=>{document.getElementById('dyno_message').textContent=d.message||d.error||JSON.stringify(d);dynoRefresh();}).catch(e=>document.getElementById('dyno_message').textContent='DYNO durdurma hatası: '+e);}
function dynoLoadApply(){fetch('/api/dyno/load',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({force_n:document.getElementById('dyno_force').value,wheel_radius_m:document.getElementById('dyno_radius').value})}).then(r=>r.json()).then(d=>{document.getElementById('dyno_message').textContent=d.ok?'Yük/roller bilgisi uygulandı.':d.error;dynoRefresh();}).catch(e=>document.getElementById('dyno_message').textContent='Yük bilgisi hatası: '+e);}
function dynoExport(){window.location.href='/api/dyno/export';}
document.getElementById('dyno_mode')?.addEventListener('change',function(){document.getElementById('dyno_dct_warning').style.display=this.value==='honda_dct'?'block':'none';});
setInterval(dynoRefresh,1000);
function switchTab(t){document.querySelectorAll('.container').forEach(e=>e.classList.remove('active'));document.querySelectorAll('.nav button').forEach(e=>e.classList.remove('active'));document.getElementById('tab-'+t).classList.add('active');document.getElementById('nav-'+t).classList.add('active')}
function toggleManualModel() { var sel = document.getElementById('sel_model'); var box = document.getElementById('manual_model_box'); if (sel.value === 'diger') { box.style.display = 'block'; } else { box.style.display = 'none'; } }
const rpmLedsContainer=document.getElementById('rpm-leds');const totalLeds=40;for(let i=0;i<totalLeds;i++){let led=document.createElement('div');led.className='led';rpmLedsContainer.appendChild(led)}const leds=document.querySelectorAll('.led');
function updateRpmBar(rpm){let a=Math.floor((rpm/13000)*totalLeds);leds.forEach((led,i)=>{led.classList.remove('active','warning','danger');if(i<a){if(i>30)led.classList.add('danger');else if(i>25)led.classList.add('warning');else led.classList.add('active')}})}
const maxDP=20;
function createMiniChart(id,color){const c=document.getElementById(id).getContext('2d');return new Chart(c,{type:'line',data:{labels:[],datasets:[{data:[],borderColor:color,borderWidth:2.5,pointRadius:0,tension:0.4}]},options:{responsive:true,maintainAspectRatio:false,animation:false,plugins:{legend:{display:false}},scales:{x:{display:false},y:{display:false,min:0}}}})}
const dash_tps=createMiniChart('chart_dash_tps','#ffea00'),dash_afr=createMiniChart('chart_dash_afr','#00ff00'),dash_temp=createMiniChart('chart_dash_temp','#ff6600'),dash_volt=createMiniChart('chart_dash_volt','#00ff00');
const chartRpm=createMiniChart('chart_rpm','#00e5ff'),chartTemp=createMiniChart('chart_temp','#ff6600'),chartIat=createMiniChart('chart_iat','#00e5ff'),chartVolt=createMiniChart('chart_volt','#00ff00'),chartTps=createMiniChart('chart_tps','#ffea00'),chartInj=createMiniChart('chart_inj','#d500ff'),chartAfr=createMiniChart('chart_afr','#00ff00'),chartO2=createMiniChart('chart_o2','#00e5ff'),chartStft=createMiniChart('chart_stft','#d500ff'),chartMap=createMiniChart('chart_map','#007bff');
const mainChart=new Chart(document.getElementById('liveChart').getContext('2d'),{type:'line',data:{labels:[],datasets:[{label:'DEVİR (RPM)',borderColor:'#00e5ff',data:[],borderWidth:2,pointRadius:0,tension:0.3},{label:'HIZ (KM/H)',borderColor:'#ffea00',data:[],borderWidth:2,pointRadius:0,tension:0.3}]},options:{responsive:true,animation:false,scales:{y:{grid:{color:'#222'},ticks:{color:'#888'}},x:{display:false}},plugins:{legend:{labels:{color:'#fff'}}}}});
function updateChart(c,v){c.data.labels.push('');c.data.datasets[0].data.push(v);if(c.data.labels.length>maxDP){c.data.labels.shift();c.data.datasets[0].data.shift()}c.update('none')}
function updateInfo(){socket.emit('update_service_info',{musteri:document.getElementById('musteri').value,plaka:document.getElementById('plaka').value,sikayet:document.getElementById('sikayet').value})}
function showToast(msg){var container=document.getElementById('toast-container');var toast=document.createElement('div');toast.className='toast';toast.innerText=msg;container.appendChild(toast);setTimeout(()=>{toast.style.opacity='0';setTimeout(()=>toast.remove(),300)},5000)}
socket.on('alert',d=>{showToast(d.msg)});socket.on('risk_score',d=>{let el=document.getElementById('risk-score');el.innerText=d.score;if(d.score>60)el.style.color='#ff003c';else if(d.score>30)el.style.color='#ffea00';else el.style.color='#00ff00'});
socket.on('telemetry',d=>{if(d.rpm!==undefined){document.getElementById('rpm').innerText=d.rpm;updateRpmBar(d.rpm)}if(d.speed!==undefined)document.getElementById('speed').innerText=d.speed;if(d.gear!==undefined)document.getElementById('gear').innerText=d.gear;if(d.tps!==undefined){document.getElementById('dash_tps').innerHTML=d.tps+"<span class='s-unit'>%</span>";updateChart(dash_tps,d.tps)}if(Number.isFinite(Number(d.afr))&&Number(d.afr)>0){document.getElementById('dash_afr').innerHTML=Number(d.afr).toFixed(1)+"<span class='s-unit'>AFR</span>";updateChart(dash_afr,Number(d.afr))}if(d.temp!==undefined){document.getElementById('dash_temp').innerHTML=d.temp+"<span class='s-unit'>°C</span>";updateChart(dash_temp,d.temp)}if(d.volt!==undefined){document.getElementById('dash_volt').innerHTML=d.volt+"<span class='s-unit'>V</span>";updateChart(dash_volt,d.volt)}if(d.rpm!==undefined){document.getElementById('s_rpm').innerHTML=d.rpm+"<span class='s-unit'>rpm</span>";updateChart(chartRpm,d.rpm)}if(d.temp!==undefined){document.getElementById('s_temp').innerHTML=d.temp+"<span class='s-unit'>°C</span>";updateChart(chartTemp,d.temp)}if(d.iat!==undefined){document.getElementById('s_iat').innerHTML=d.iat+"<span class='s-unit'>°C</span>";updateChart(chartIat,d.iat)}if(d.volt!==undefined){document.getElementById('s_volt').innerHTML=d.volt+"<span class='s-unit'>V</span>";updateChart(chartVolt,d.volt)}if(d.tps!==undefined){document.getElementById('s_tps').innerHTML=d.tps+"<span class='s-unit'>%</span>";updateChart(chartTps,d.tps)}if(d.inj!==undefined){document.getElementById('s_inj').innerHTML=d.inj+"<span class='s-unit'>ms</span>";updateChart(chartInj,d.inj)}if(Number.isFinite(Number(d.afr))&&Number(d.afr)>0){document.getElementById('s_afr').innerHTML=Number(d.afr).toFixed(1)+"<span class='s-unit'>AFR</span>";updateChart(chartAfr,Number(d.afr))}if(d.o2!==undefined){document.getElementById('s_o2').innerHTML=d.o2+"<span class='s-unit'>V</span>";updateChart(chartO2,d.o2)}if(d.stft!==undefined){document.getElementById('s_stft').innerHTML=d.stft+"<span class='s-unit'>%</span>";updateChart(chartStft,d.stft)}if(d.map!==undefined){document.getElementById('s_map').innerHTML=d.map+"<span class='s-unit'>kPa</span>";updateChart(chartMap,d.map)}if(d.rpm!==undefined&&d.speed!==undefined){mainChart.data.labels.push('');mainChart.data.datasets[0].data.push(d.rpm);mainChart.data.datasets[1].data.push(d.speed);if(mainChart.data.labels.length>60){mainChart.data.labels.shift();mainChart.data.datasets[0].data.shift();mainChart.data.datasets[1].data.shift()}mainChart.update('none')}});
socket.on('status',d=>{let b=document.getElementById('status-bar');b.innerText=d.msg;b.style.color=d.color});
function scanDTC(){document.getElementById('dtc-icon').innerText="⏳";document.getElementById('dtc-title').innerText="TARANIYOR...";document.getElementById('dtc-title').style.color="#ffaa00";document.getElementById('dtc-msg').innerText="Lütfen bekleyin...";document.getElementById('dtc-msg').style.color="#ffaa00";document.getElementById('freeze-frame').style.display='none';document.getElementById('smart-diag').style.display='none';socket.emit('scan_dtc')}
function clearDTC(){if(confirm("Motor kontağı AÇIK mı?\n\nTüm arıza kodları silinecek. Devam?")){document.getElementById('dtc-title').innerText="SİLİNİYOR...";document.getElementById('dtc-title').style.color="#ffaa00";socket.emit('clear_dtc')}}
socket.on('dtc_result',d=>{if(d.ok===false){document.getElementById('dtc-icon').innerText="❌";document.getElementById('dtc-title').innerText="TARAMA BAŞARISIZ";document.getElementById('dtc-title').style.color="#ff5252";document.getElementById('dtc-msg').innerText=d.error||"Bilinmeyen hata";document.getElementById('dtc-msg').style.color="#ff5252";document.getElementById('dtc-msg').style.whiteSpace="pre-line";return}if(d.codes.length===0){document.getElementById('dtc-icon').innerText="⚙️";document.getElementById('dtc-title').innerText="SİSTEM NORMAL";document.getElementById('dtc-title').style.color="#00ff00";document.getElementById('dtc-msg').innerText="ARIZA BULUNAMADI";document.getElementById('dtc-msg').style.color="#00ff00"}else{document.getElementById('dtc-icon').innerText="⚠️";document.getElementById('dtc-title').innerText="ARIZA VAR!";document.getElementById('dtc-title').style.color="#ff003c";document.getElementById('dtc-msg').innerText="Kodlar:\n"+d.codes.join("\n");document.getElementById('dtc-msg').style.color="#ff003c";document.getElementById('dtc-msg').style.whiteSpace="pre-line";if(d.freeze_frame){document.getElementById('freeze-frame').style.display='block';document.getElementById('ff-data').innerText=d.freeze_frame}if(d.smart_diag&&d.smart_diag.length>0){document.getElementById('smart-diag').style.display='block';document.getElementById('smart-diag').innerText="🧠 AKILLI TEŞHİS YORUMU:\n"+d.smart_diag.join("\n")}}});
socket.on('dtc_cleared',d=>{if(d.ok){document.getElementById('dtc-icon').innerText="✅";document.getElementById('dtc-title').innerText="SİLİNDİ";document.getElementById('dtc-title').style.color="#00e5ff";document.getElementById('dtc-msg').innerText="Arıza hafızası temiz."+(d.msg?("\n\n"+d.msg):"");document.getElementById('dtc-msg').style.color="#00e5ff";document.getElementById('dtc-msg').style.whiteSpace="pre-line"}else{document.getElementById('dtc-icon').innerText="❌";document.getElementById('dtc-title').innerText="SİLME BAŞARISIZ";document.getElementById('dtc-title').style.color="#ff5252";document.getElementById('dtc-msg').innerText=d.msg||'Bilinmeyen hata';document.getElementById('dtc-msg').style.color="#ff5252";document.getElementById('dtc-msg').style.whiteSpace="pre-line"}});
function testGetInfo(){document.getElementById('test_info').innerText='Yükleniyor...';socket.emit('test_info')}function testGetEcu(){document.getElementById('test_ecu').innerText='Sorgulanıyor...';socket.emit('test_ecu_info')}function testSendRaw(){var c=document.getElementById('test_cmd_input').value;if(!c)return;document.getElementById('test_raw_out').innerText='Gönderiliyor...';socket.emit('test_raw',{cmd:c})}
socket.on('test_info_result',d=>{document.getElementById('test_info').innerText=JSON.stringify(d,null,2)});socket.on('test_ecu_result',d=>{document.getElementById('test_ecu').innerText=d.ok?JSON.stringify(d.data,null,2):('HATA: '+d.msg)});socket.on('test_raw_result',d=>{document.getElementById('test_raw_out').innerText=(d.ok?'✓ OK:\n':'✗ HATA:\n')+(d.msg||d.raw||'')});
function gkConnect(){document.getElementById('gk_conn_out').innerText='Bağlanıyor...';socket.emit('gkbus_connect')}function gkTest(code){document.getElementById('gk_test_out').innerText='Komut gönderiliyor...';socket.emit('gkbus_test',{code:code})}function gkD07(){document.getElementById('gk_d07_out').innerText='Okunuyor...';socket.emit('gkbus_d07')}
 function absAnalyzeLog(){
  var input=document.getElementById('abs_can_log_file'), out=document.getElementById('abs_can_log_result'), table=document.getElementById('abs_can_log_table');
  table.innerHTML=''; if(!input.files||!input.files.length){out.innerText='Önce bir CAN kayıt dosyası seçin.';return;}
  var file=input.files[0]; if(file.size>5*1024*1024){out.innerText='Dosya 5 MB sınırını aşıyor.';return;}
  out.innerText='Kayıt okunuyor ve pasif olarak analiz ediliyor...';
  var body=new FormData(); body.append('file',file);
  fetch('/abs/passive/analyze',{method:'POST',body:body}).then(function(r){return r.json();}).then(function(d){
    if(!d.ok){out.innerText='ANALİZ EDİLEMEDİ: '+(d.error||'Bilinmeyen hata');return;}
    out.innerText='DOSYA: '+d.filename+'\\nÇERÇEVE: '+d.frame_count+'\\nFARKLI CAN ID: '+d.unique_ids+'\\nKAYIT SÜRESİ: '+d.duration_s+' sn\\nATLANAN SATIR: '+d.ignored_lines+'\\n\\n'+d.note;
    var html='<table style="width:100%;border-collapse:collapse;color:#ddd;font-size:12px"><thead><tr><th>ID</th><th>Adet</th><th>Ort. periyot ms</th><th>Min/Max ms</th><th>DLC</th><th>Değişen byte</th></tr></thead><tbody>';
    d.ids.forEach(function(x){html+='<tr><td>'+x.id+'</td><td>'+x.count+'</td><td>'+(x.period_ms===null?'-':x.period_ms)+'</td><td>'+(x.min_period_ms===null?'-':x.min_period_ms+' / '+x.max_period_ms)+'</td><td>'+x.dlc+'</td><td>'+x.changing_bytes.join(', ')+'</td></tr>';});
    html+='</tbody></table>'; table.innerHTML=html;
  }).catch(function(e){out.innerText='Dosya analiz hatası: '+e;});
 }
 function j2534Status(){fetch('/api/j2534/status').then(r=>r.json()).then(d=>{document.getElementById('j2534_state').innerText=d.connected?'Kanal açık: '+d.protocol:'Kanal kapalı';document.getElementById('j2534_output').innerText=JSON.stringify(d,null,2);}).catch(e=>document.getElementById('j2534_output').innerText='Durum hatası: '+e);}
function j2534Open(){var p=document.getElementById('j2534_protocol').value,b=document.getElementById('j2534_baud').value;fetch('/api/j2534/open',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({protocol:p,baud:b||null})}).then(r=>r.json()).then(d=>{document.getElementById('j2534_state').innerText=d.status&&d.status.connected?'Kanal açık: '+p:'Kanal kapalı';document.getElementById('j2534_output').innerText=d.message+'\\n'+JSON.stringify(d.status||{},null,2);}).catch(e=>document.getElementById('j2534_output').innerText='Bağlantı hatası: '+e);}
function j2534Read(){fetch('/api/j2534/read?timeout_ms=500').then(r=>r.json()).then(d=>{document.getElementById('j2534_output').innerText=JSON.stringify(d,null,2);}).catch(e=>document.getElementById('j2534_output').innerText='Okuma hatası: '+e);}
 // J2534 ham CAN sniffer: yalnizca RX okur, uygulama TX yapmaz.
 let canSniffActive=false,canSniffBusy=false,canSniffStartMs=0,canSniffFrames=[],canSniffMarkers=[],canSniffStats={};
 const CAN_SIGNAL_PROFILES={
 yamaha_mt07_legacy:{applicability:'MT-07 forumundaki 2017 kullanıcı notları. 0x236 vites kodlaması açıkça paylaşılmıştır; 0x20A RPM bayt yorumu kesinleştirilmemiştir ve yalnız ham aday olarak gösterilir. 0x22E kokpit tuş durumlarıdır.',source:'https://www.mt07-forum.de/forum/thread/32532-can-bus-mt-07-daten',signals:{
 '236':[{name:'Gösterge vites bilgisi',fn:b=>b.length>=1?{v:({0:'N',32:'1',64:'2',96:'3',128:'4',160:'5',192:'6'})[b[0]]||('Bilinmeyen kod 0x'+b[0].toString(16).toUpperCase()),u:'forum kodlaması'}:null}],
 '22E':[{name:'Kokpit sol/sağ tuşları',fn:b=>b.length>=2?{v:'Sol: '+((b[0]&8)?'basılı':'—')+' · Sağ: '+((b[0]&4)?'basılı':'—'),u:'gösterge tuş mesajı'}:null}],
 '20A':[{name:'RPM mesaj adayı · ham byte 2',fn:b=>b.length>=4?{v:'0x'+b[2].toString(16).toUpperCase().padStart(2,'0')+' ('+b[2]+')',u:'RPM formülü kesin değil; rpm değeri değildir'}:null}]
 }},
 yamaha_mt07_2014_project:{sourceOnly:true,applicability:'2014 MT-07 için açık kaynak ESP32/MCP2515 projesi; 500 kbit/s bildiriyor ve hız, RPM, sıcaklık, vites verilerini çözdüğünü belirtiyor. README byte formüllerini vermediğinden otomatik sayısal çözümleme kapalıdır.',source:'https://github.com/JackHat1/MT07_CAN_Project',signals:{}},
 yamaha_mt07_abs_notes:{applicability:'Euro 3 MT-07 forumunda Bosch ABS 9 teşhis cihazıyla kaydedildiği bildirilen pasif CAN oturum gözlemleri. 0x07E3 üzerinde 10 03 isteği ve 0x07EB üzerinde 50 03 yanıtı anlatılır. Bu ekran yalnız kayıtta görülen oturum çerçevelerini işaretler; rutin kontrolü, pompa/valf sürme veya fren havası alma komutu içermez.',source:'https://www.mt07-forum.de/forum/thread/68226-yamaha-euro-3-can-bus-und-aktivierung-des-abs-moduls',signals:{
 '7DF':[{name:'Yayın teşhis oturumu isteği',fn:b=>b.length>=3&&b[1]===0x10?{v:'SID 0x10 / alt işlev 0x'+b[2].toString(16).toUpperCase().padStart(2,'0'),u:'pasif trace işareti'}:null}],
 '7E3':[{name:'ABS ECU teşhis oturumu isteği adayı',fn:b=>b.length>=3&&b[1]===0x10?{v:'DiagnosticSessionControl · alt işlev 0x'+b[2].toString(16).toUpperCase().padStart(2,'0'),u:'forum trace yorumu; ECU adresi doğrulanmadı'}:null}],
 '7EB':[{name:'ABS ECU pozitif oturum yanıtı adayı',fn:b=>b.length>=3&&b[1]===0x50?{v:'SID 0x50 · alt işlev 0x'+b[2].toString(16).toUpperCase().padStart(2,'0'),u:'forum trace yorumu; yanıt eşlemesi doğrulanmadı'}:null}]
 }},
 yamaha_cp3_2021_2024:{applicability:'2022 Tracer 9 üzerinde yayımlanmış; 2021+ MT-09 ve XSR900 aynı platform olarak belirtilmiş. Başka yıl/model için doğrulanmış sayılmaz.',source:'https://racechrono.com/forum/d/2853-yamaha-tracer-9-mt-09-2021-2024-can-ids-and-formulas',signals:{
 '209':[{name:'Motor devri',fn:b=>b.length>=2?{v:(((b[0]<<8)|b[1])*25/64).toFixed(0),u:'rpm'}:null},{name:'Vites',fn:b=>b.length>=5?{v:(b[4]&7)===7?'Vites geçişi':(b[4]&7)===0?'N':String(b[4]&7),u:''}:null}],
 '205':[{name:'Ön / arka teker hızı',fn:b=>b.length>=4?{v:(((b[0]<<8)|b[1])/101).toFixed(1)+' / '+(((b[2]<<8)|b[3])/101).toFixed(1),u:'km/h'}:null}],
 '215':[{name:'Gaz kolu ham değeri',fn:b=>b.length>=4?{v:String((b[2]<<8)|b[3]),u:'raw'}:null}],
 '260':[{name:'Ön fren basıncı',fn:b=>b.length>=2?{v:String((b[0]<<8)|b[1]),u:'raw'}:null}],
 '268':[{name:'ABS / arka fren durumu',fn:b=>b.length>=5?{v:'Arka ABS: '+((b[4]&1)?'aktif':'pasif')+' · Ön ABS: '+((b[4]&2)?'aktif':'pasif')+' · Arka pedal: '+((b[4]&4)?'basılı':'basılı değil'),u:''}:null}],
 '254':[{name:'Yaw rate',fn:b=>b.length>=6?{v:((((b[4]<<8)|b[5])-30000)/100).toFixed(2),u:'deg/s'}:null}]
 }},
 ducati_panigale_mitsubishi:{applicability:'DucatiPanigaleCanBus MIT açık kaynak projesi; test motosikleti Panigale 899. Panigale 899/959/1199/1299 aileleri, bazı Multistrada 1200 (2010–2014) ve Diavel varyantları için Mitsubishi ECU yayın çerçeveleri rapor edilmiştir. Diğer yıl/model uyumu ayrıca doğrulanmalıdır.',source:'https://github.com/renatobo/DucatiPanigaleCanBus',signals:{
 '080':[{name:'Gaz kelebeği konumu',fn:b=>b.length>=7?{v:(b[4]/2).toFixed(0),u:'% · kaynak BLE ölçeği'}:null},{name:'Motor devri',fn:b=>b.length>=7?{v:String(b[5]*256+b[6]),u:'rpm'}:null}],
 '018':[{name:'Arka teker hızı',fn:b=>b.length>=6?{v:String(((b[4]&31)<<4)+(b[5]>>4)),u:'kaynak ham hız birimi'}:null},{name:'Vites kodu',fn:b=>b.length>=5?{v:String(b[4]>>5),u:'ham kod; model/yıl doğrulaması gerekli'}:null}],
 '100':[{name:'Motor / ortam sıcaklığı',fn:b=>b.length>=6?{v:(b[3]-40)+' / '+(b[5]-40),u:'°C (su / hava)'}:null},{name:'Akü voltajı',fn:b=>b.length>=5?{v:(b[4]/10).toFixed(1),u:'V · kaynak BLE ölçeklemesi'}:null}]
 }},
 yamaha_r9_2026:{applicability:'2026 R9 üzerinde tek motosiklette yayımlanmış yayın verileri; tanımlı teşhis soketi hattına özgüdür. Üç ayrı CAN çifti olduğu bildirilmiştir.',source:'https://racechrono.com/forum/d/2840-yamaha-r9-2026-12-signals-decoded-captures-and-dbc-on-github',signals:{
 '27C':[{name:'Motor devri',fn:b=>b.length>=2?{v:(((b[0]<<8)|b[1])*100/256).toFixed(0),u:'rpm'}:null},{name:'Vites',fn:b=>b.length>=5?{v:(b[4]&7)===7?'Vites geçişi':(b[4]&7)===0?'N':String(b[4]&7),u:''}:null},{name:'ECU vites bilgisi',fn:b=>b.length>=4?{v:String((b[3]>>5)&7),u:''}:null},{name:'TCS seviyesi',fn:b=>b.length>=4?{v:String((b[3]>>1)&15),u:''}:null},{name:'PWR seviyesi',fn:b=>b.length>=5?{v:String((b[4]>>6)+1),u:''}:null},{name:'Marş sinyali',fn:b=>b.length>=4?{v:(b[3]&1)?'Aktif':'Pasif',u:''}:null}],
 '383':[{name:'Fren anahtarı',fn:b=>b.length>=5?{v:((b[4]>>3)&1)?'Fren basılı':'Fren basılı değil',u:''}:null}],
 '512':[{name:'Soğutma / hava sıcaklığı',fn:b=>b.length>=2?{v:(b[0]*0.625-30).toFixed(1)+' / '+(b[1]*0.625-30).toFixed(1),u:'°C (su / hava)'}:null}],
 '756':[{name:'Arka teker hızı',fn:b=>b.length>=4?{v:((((b[3]>>4)&15)*4096)+((b[1]&15)*256)+(((b[0]>>4)&15)*16)+((b[2]>>4)&15))/100,u:'km/h · DBC alan birleştirme'}:null}],
 '765':[{name:'Ön teker hızı',fn:b=>b.length>=7?{v:((((b[0]>>4)&15)*4096)+(((b[2]>>4)&15)*256)+(((b[6]>>4)&15)*16)+(b[3]&15))/100,u:'km/h · DBC alan birleştirme'}:null}],
 '751':[{name:'Nötr bilgisi',fn:b=>b.length>=7?{v:(b[6]===0x60||b[6]===0x70)?'Nötr':'Viteste / belirsiz',u:''}:null}],
 '758':[{name:'Gaz kelebeği (TPS)',fn:b=>b.length>=3?{v:((((b[0]>>4)<<8)|b[2])*100/2310.4).toFixed(1),u:'% · forumda sıfır noktası geçici'}:null},{name:'Gaz kolu (APS)',fn:b=>b.length>=8?{v:((((b[7]&15)<<12)|((b[6]&15)<<8)|b[5])*100/31672).toFixed(1),u:'% · DBC ham alan birleştirme'}:null}],
 '759':[{name:'Komutlanan gaz kelebeği',fn:b=>b.length>=3?{v:((((b[0]>>4)<<4)|(b[2]>>4))*100/144.4).toFixed(1),u:'%'}:null}],
 '76D':[{name:'Cruise düğmeleri',fn:b=>b.length>=6?{v:'RES+: '+((b[3]&1)?'basılı':'—')+' · SET−: '+((b[5]>>4&1)?'basılı':'—'),u:''}:null}],
 '76E':[{name:'PWR seviyesi',fn:b=>b.length>=2?{v:String(b[1]+1),u:''}:null},{name:'Sürüş modu ekranı',fn:b=>b.length>=3?{v:b[2]===0x10?'Track':b[2]===0x20?'Normal':'Bilinmiyor',u:''}:null}]
 }}
 };
 function canSniffRenderDecoded(){
  const profile=document.getElementById('can_profile')?.value||'none',box=document.getElementById('can_sniff_decoded'),note=document.getElementById('can_profile_note');if(!box)return;
  if(profile==='none'){note.textContent='Profil seçilmedi. Ham CAN kayıtları tutulur; sinyal isimleri atanmaz.';box.textContent='Genel / bilinmeyen model: çözümleme yapılmıyor.';return;}
  const p=CAN_SIGNAL_PROFILES[profile];note.textContent=p.applicability+' Kaynak: '+p.source;if(p.sourceOnly){box.textContent='Bu kayıt yalnızca araştırma notudur. Formül doğrulanmadığı için otomatik çözümleme kapalıdır.';return;}
  const latest={};canSniffFrames.forEach(f=>{const b=(f.data||'').split(/\s+/).filter(Boolean).map(x=>parseInt(x,16));(p.signals[f.id.toUpperCase()]||[]).forEach(sig=>{const v=sig.fn(b);if(v)latest[sig.name]={name:sig.name,value:v.v,unit:v.u};});});
  const rows=Object.values(latest);box.innerHTML=rows.length?'<table style="width:100%;border-collapse:collapse;font-size:13px"><thead><tr><th>Sinyal</th><th>Son değer</th><th>Birim / not</th></tr></thead><tbody>'+rows.map(x=>'<tr><td>'+x.name+'</td><td style="color:#00e5ff;font-weight:bold">'+x.value+'</td><td>'+x.unit+'</td></tr>').join('')+'</tbody></table>':'Seçili profile ait CAN ID henüz görülmedi.';
 }
 function canSniffRender(){
  const count=document.getElementById('can_sniff_count');if(!count)return;
  count.textContent=canSniffFrames.length;document.getElementById('can_sniff_ids').textContent=Object.keys(canSniffStats).length;
  document.getElementById('can_sniff_duration').textContent=(canSniffStartMs?((Date.now()-canSniffStartMs)/1000).toFixed(1):'0.0')+' sn';
  const rows=Object.keys(canSniffStats).map(k=>({id:k,...canSniffStats[k]})).sort((a,b)=>b.count-a.count).slice(0,24);
  document.getElementById('can_sniff_summary').innerHTML=rows.length?'<table style="width:100%;border-collapse:collapse;font-size:12px"><thead><tr><th>ID</th><th>Adet</th><th>Ort. periyot</th><th>DLC</th><th>Değişen byte</th><th>Son veri</th></tr></thead><tbody>'+rows.map(x=>'<tr><td>0x'+x.id+'</td><td>'+x.count+'</td><td>'+(x.periods.length?(x.periods.reduce((a,b)=>a+b,0)/x.periods.length).toFixed(2)+' ms':'-')+'</td><td>'+Array.from(x.dlcs).sort().join(',')+'</td><td>'+Array.from(x.changed).sort((a,b)=>a-b).join(', ')+'</td><td>'+x.lastData+'</td></tr>').join('')+'</tbody></table>':'Henüz veri alınmadı.';
  const recent=canSniffFrames.slice(-60).reverse();
  document.getElementById('can_sniff_frames').innerHTML=recent.length?'<table style="width:100%;border-collapse:collapse;font:12px Consolas,monospace"><thead><tr><th>Zaman</th><th>CAN ID</th><th>DLC</th><th>Ham veri</th><th>RX durum</th></tr></thead><tbody>'+recent.map(x=>'<tr><td>'+x.t.toFixed(3)+'</td><td>0x'+x.id+'</td><td>'+x.dlc+'</td><td>'+x.data+'</td><td>'+x.rx_status+'</td></tr>').join('')+'</tbody></table>':'CAN çerçevesi alınmadı.';
  document.getElementById('can_sniff_markers').textContent=canSniffMarkers.length?canSniffMarkers.map(x=>x.t.toFixed(3)+' sn  |  '+x.label).join('\n'):'Henüz olay işareti yok.';
  canSniffRenderDecoded();
 }
 async function canSniffAnalyzeProtocol(){
  const out=document.getElementById('ecu_capture_analysis');
  const module=document.getElementById('ecu_capture_module').value;
  if(!canSniffFrames.length){out.textContent='Analiz edilecek CAN kaydı yok. Önce dinleme başlatıp kayıt alın.';return;}
  out.textContent='ISO-TP / OBD-II / UDS / KWP adayları çözümleniyor...';
  try{
   const r=await fetch('/api/ecu-monitor/analyze',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({module:module,frames:canSniffFrames})});
   const d=await r.json();if(!r.ok||!d.ok)throw new Error(d.error||'Analiz başarısız');
   const lines=[(module==='engine'?'MOTOR ECU':module==='abs'?'ABS ECU':'GENEL')+' PASİF ANALİZİ',
    'Çerçeve: '+d.frame_count+' | CAN ID: '+d.unique_can_ids+' | Hatalı kayıt: '+d.malformed_frames,
    d.module_note,'','EN YOĞUN CAN ID / ZAMANLAMA / DEĞİŞEN BAYTLAR'];
   (d.can_ids||[]).slice(0,30).forEach(x=>lines.push(x.can_id+'  adet='+x.count+'  periyot='+((x.mean_period_ms===null)?'-':x.mean_period_ms+' ms')+'  DLC='+x.dlc.join('/')+'  değişen bayt='+x.changing_bytes.join(',')+'  son='+x.last_data));
   lines.push('','TANINAN SERVİS YANITI ADAYLARI');
   if(!(d.diagnostic_messages||[]).length)lines.push('Bu kayıtta ISO-TP üzerinden tanınan OBD/UDS servis cevabı bulunmadı. Bu, ECU arızalı veya erişilemez demek değildir.');
   (d.diagnostic_messages||[]).slice(-100).forEach(x=>lines.push(x.can_id+' | '+x.family+' | '+x.service+' | '+x.meaning+' | '+x.payload));
   lines.push('','SINIRLAR: '+d.limitations);
   out.textContent=lines.join('\n');
  }catch(e){out.textContent='Çözümleme hatası: '+e.message;}
 }
 function canSniffStart(){
  if(canSniffActive)return;
  fetch('/api/j2534/status').then(r=>r.json()).then(d=>{
   if(!d.connected||String(d.protocol).toLowerCase()!=='can'){document.getElementById('can_sniff_error').textContent='Önce Protokol alanından CAN · 500 kbit/s (ham/pasif) seçin ve BAĞLAN düğmesine basın. Mevcut kanal: '+(d.protocol||'kapalı');return;}
   canSniffActive=true;canSniffStartMs=Date.now();document.getElementById('can_sniff_status').textContent='DİNLİYOR';
   document.getElementById('can_sniff_start').disabled=true;document.getElementById('can_sniff_stop').disabled=false;
   document.getElementById('can_sniff_error').textContent='RX dinleme başladı. Uygulama CAN mesajı göndermiyor.';canSniffPoll();
  }).catch(e=>document.getElementById('can_sniff_error').textContent='J2534 durum hatası: '+e);
 }
 function canSniffStop(){canSniffActive=false;document.getElementById('can_sniff_status').textContent='DURDURULDU';document.getElementById('can_sniff_start').disabled=false;document.getElementById('can_sniff_stop').disabled=true;canSniffRender();}
 async function canSniffPoll(){
  if(!canSniffActive||canSniffBusy)return;canSniffBusy=true;
  try{
   const r=await fetch('/api/j2534/read?timeout_ms=200'),d=await r.json();if(!d.ok)throw new Error(d.error||'J2534 RX okuma hatası');
   (d.frames||[]).forEach(f=>{
    const oct=(f.data||'').trim().split(/\s+/).filter(Boolean).map(v=>parseInt(v,16));if(oct.length<4)return;
    const rawId=oct[0]*16777216+oct[1]*65536+oct[2]*256+oct[3],id=(rawId>>>0).toString(16).toUpperCase().padStart(rawId>2047?8:3,'0');
    const payload=oct.slice(4,12),stamp=(Date.now()-canSniffStartMs)/1000,data=payload.map(v=>v.toString(16).toUpperCase().padStart(2,'0')).join(' ');
    canSniffFrames.push({t:stamp,id:id,dlc:payload.length,data:data,rx_status:f.rx_status||0});if(canSniffFrames.length>20000)canSniffFrames.shift();
    const st=canSniffStats[id]||(canSniffStats[id]={count:0,lastMs:null,periods:[],dlcs:new Set(),changed:new Set(),lastBytes:[],lastData:''});
    st.count++;if(st.lastMs!==null&&st.periods.length<5000)st.periods.push(Date.now()-st.lastMs);st.lastMs=Date.now();st.dlcs.add(payload.length);
    payload.forEach((v,i)=>{if(st.lastBytes[i]!==undefined&&st.lastBytes[i]!==v)st.changed.add(i);});st.lastBytes=payload.slice();st.lastData=data;
   });
   if((d.frames||[]).length)canSniffRender();
  }catch(e){document.getElementById('can_sniff_error').textContent='CAN RX durdu / hata: '+e;canSniffStop();}
  finally{canSniffBusy=false;if(canSniffActive)setTimeout(canSniffPoll,80);}
 }
 function canSniffMark(){const label=(document.getElementById('can_sniff_marker_text').value||'').trim();if(!canSniffStartMs){document.getElementById('can_sniff_error').textContent='Önce CAN dinlemeyi başlatın.';return;}if(!label){document.getElementById('can_sniff_error').textContent='Olay işareti için kısa bir açıklama yazın.';return;}canSniffMarkers.push({t:(Date.now()-canSniffStartMs)/1000,label:label});document.getElementById('can_sniff_marker_text').value='';canSniffRender();}
 function canSniffClear(){if(canSniffActive)canSniffStop();canSniffFrames=[];canSniffMarkers=[];canSniffStats={};canSniffStartMs=0;document.getElementById('can_sniff_status').textContent='Hazır';document.getElementById('can_sniff_error').textContent='';canSniffRender();}
 function canSniffDownload(){
  if(!canSniffFrames.length){document.getElementById('can_sniff_error').textContent='İndirilecek CAN kaydı yok.';return;}
  const lines=['timestamp,can_id,data,dlc,bus,event'];canSniffFrames.forEach(x=>lines.push([x.t.toFixed(6),'0x'+x.id,'"'+x.data+'"',x.dlc,'J2534-CAN',''].join(',')));
  canSniffMarkers.forEach(x=>lines.push([x.t.toFixed(6),'','','','J2534-CAN','"'+x.label.replace(/"/g,'""')+'"'].join(',')));
  const blob=new Blob(['\uFEFF'+lines.join('\n')],{type:'text/csv;charset=utf-8;'}),url=URL.createObjectURL(blob),a=document.createElement('a');
  a.href=url;a.download='MOTO_DIAG_CAN_SNIFF_'+new Date().toISOString().replace(/[:.]/g,'-')+'.csv';document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url);
 }
function j2534Close(){fetch('/api/j2534/close',{method:'POST'}).then(r=>r.json()).then(d=>{document.getElementById('j2534_state').innerText='Kanal kapalı';document.getElementById('j2534_output').innerText=d.message;}).catch(e=>document.getElementById('j2534_output').innerText='Kapatma hatası: '+e);}
function j2534ClearDtc(){
 if(!confirm('UYARI: Bu işlem standart OBD-II motor/emisyon ECU arıza hafızasını siler ve hazır olma monitörlerini sıfırlayabilir. ABS arızalarını silmez. Önce arıza raporunu kaydettiniz mi?'))return;
 var out=document.getElementById('j2534_output');out.innerText='Motor ECU Mode 04 silme isteği gönderiliyor...';
 fetch('/api/j2534/obd/clear',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirmation:'CLEAR_ENGINE_OBD_CONFIRMED'})})
 .then(r=>r.json().then(d=>({ok:r.ok,data:d}))).then(x=>{out.innerText=JSON.stringify(x.data,null,2);})
 .catch(e=>out.innerText='Silme isteği hatası: '+e);
}
function j2534ReadDtc(){var out=document.getElementById('j2534_output');out.innerText='Standart OBD-II motor DTC sorgusu gönderiliyor...';fetch('/api/j2534/obd/dtc',{method:'POST'}).then(r=>r.json()).then(d=>{out.innerText=JSON.stringify(d,null,2);}).catch(e=>out.innerText='DTC okuma hatası: '+e);}
 function absLoadOperations(){
  var q=new URLSearchParams({brand:document.getElementById('abs_brand').value,model:document.getElementById('abs_model').value,year:document.getElementById('abs_year').value});
  var out=document.getElementById('abs_operations_result');out.textContent='ABS işlev kapsamı kontrol ediliyor...';
  fetch('/api/abs/operations?'+q.toString()).then(r=>r.json()).then(d=>{
   if(!d.ok)throw new Error(d.error||'Kapsam sorgusu başarısız');
   out.textContent=(d.brand+' '+d.model+' '+d.year+'\\n'+(d.profile_id||'Doğrulanmış profil yok')+'\\n\\n')+
    d.operations.map(x=>(x.enabled?'✓ ':'🔒 ')+x.label+' — '+(x.enabled?'Profilde tanımlı':'Model profili / doğrulama gerekli')+' ['+x.safety+']').join('\\n')+
    '\\n\\n'+d.message;
  }).catch(e=>out.textContent='ABS kapsam hatası: '+e.message);
 }
 function absCheckProfile(){var brand=document.getElementById('abs_brand').value;var model=document.getElementById('abs_model').value;var year=document.getElementById('abs_year').value;var out=document.getElementById('abs_profile_result');out.innerText='Yerel profil veri tabanı kontrol ediliyor...';socket.emit('abs_profile_check',{brand:brand,model:model,year:year})}

 function absPassiveListenStart(){
  const module=document.getElementById('ecu_capture_module');
  const protocol=document.getElementById('j2534_protocol');
  if(module)module.value='abs';
  if(protocol)protocol.value='can';
  const note=document.getElementById('ecu_capture_analysis');
  if(note)note.textContent='ABS pasif CAN dinleme seçildi. J2534 kanalını CAN protokolünde açın; ardından dinleme başlatılacaktır. Bu işlem CAN mesajı göndermez.';
  fetch('/api/j2534/status').then(r=>r.json()).then(d=>{
   if(!d.connected||String(d.protocol).toLowerCase()!=='can'){
    document.getElementById('can_sniff_error').textContent='ABS pasif dinleme hazırlandı. Üstte Protokol = CAN · 500 kbit/s seçildi. Şimdi BAĞLAN düğmesine basın; kanal açılınca ABS CAN PASİF DİNLEMEYİ BAŞLAT düğmesine tekrar basın.';
    document.getElementById('can_sniff_status').textContent='J2534 CAN kanalı bekleniyor';
    return;
   }
   canSniffStart();
  }).catch(e=>{document.getElementById('can_sniff_error').textContent='ABS dinleme başlatılamadı: '+e;});
 }
 function absDecodeDtc(){var data={brand:document.getElementById('abs_brand').value,model:document.getElementById('abs_model').value,year:document.getElementById('abs_year').value,code:document.getElementById('abs_dtc_code').value};var out=document.getElementById('abs_dtc_result');out.innerText='Yerel ABS kod sözlüğü aranıyor...';socket.emit('abs_decode_dtc',data)}
 socket.on('abs_decode_dtc_result',function(d){var out=document.getElementById('abs_dtc_result');out.innerText=(d.ok?'✓ ':'! ')+(d.code||'')+' — '+(d.description||d.message||'');out.style.color=d.found?'#00e5ff':'#ffcc80';if(d.source)out.innerText+='\\nKaynak: '+d.source;});
 socket.on('abs_profile_result',function(d){var out=document.getElementById('abs_profile_result');out.innerText=(d.status||'ABS PROFİLİ')+' | '+(d.message||'');document.getElementById('abs_conn_state').innerText=d.verified?'Profil mevcut · ECU bağlantısı henüz yok':'Bağlı değil';document.getElementById('abs_ecu_id').innerText=d.profile_id||'Henüz okunmadı';document.getElementById('abs_protocol').innerText=d.protocol||'Doğrulanmadı';out.style.color=d.verified?'#ffaa00':'#ffcc80';var ev=document.getElementById('abs_hardware_evidence');if(d.hardware_evidence){var h=d.hardware_evidence;ev.innerText=h.title+'\\n'+h.connector+'\\n'+h.can_h+'\\n'+h.can_l+'\\n\\nDOĞRULANMAYANLAR: '+h.unknown+'\\nGÜVENLİ SONRAKİ ADIM: '+h.next_step+'\\nKAYNAK: '+h.source;}else{ev.innerText='Bu marka/model/yıl için belgelenmiş fiziksel ABS soket ve hat bilgisi henüz eklenmedi.';}});

socket.on('gkbus_result',d=>{var el=document.getElementById('gk_conn_out');if(el)el.innerText=(d.ok?'✅ ':'❌ ')+d.msg});socket.on('gkbus_test_result',d=>{var el=document.getElementById('gk_test_out');if(el)el.innerText=(d.ok?'✅ ':'❌ ')+d.msg});socket.on('gkbus_d07_result',d=>{var el=document.getElementById('gk_d07_out');if(el)el.innerText=d.ok?('Pulse: '+d.data):('❌ '+d.msg)});
function aracSec(){var data={marka:document.getElementById('sel_marka').value,model:document.getElementById('sel_model').value,yil:parseInt(document.getElementById('sel_yil').value),konnektor:document.getElementById('sel_konnektor').value,manuel_model:document.getElementById('sel_model_manual').value};document.getElementById('arac_sonuc').style.display='block';document.getElementById('arac_sonuc_text').innerText='Yapılandırma hesaplanıyor...';socket.emit('arac_sec',data)}
socket.on('arac_sec_result',function(d){var sonucText=document.getElementById('arac_sonuc_text');if(d.ok){sonucText.innerText=d.msg;sonucText.style.color='#00ff00'}else{sonucText.innerText='HATA: '+d.msg;sonucText.style.color='#ff5252'}});

function dctCheckProfile(){socket.emit('dct_profile_check',{model:document.getElementById('dct_model').value,year:document.getElementById('dct_year').value});}
function dctDecodeDtc(){socket.emit('dct_decode_dtc',{model:document.getElementById('dct_model').value,year:document.getElementById('dct_year').value,code:document.getElementById('dct_dtc_code').value});}
socket.on('dct_profile_result',d=>{document.getElementById('dct_profile_result').innerText=JSON.stringify(d,null,2);});
socket.on('dct_decode_dtc_result',d=>{document.getElementById('dct_dtc_result').innerText=d.code+' – '+d.description+(d.source?'\\nKaynak: '+d.source:'');});
async function dctAnalyzeCapture(){const f=document.getElementById('dct_capture_file').files[0],o=document.getElementById('dct_capture_result');if(!f){o.innerText='Önce dosya seçin.';return;}const fd=new FormData();fd.append('file',f);o.innerText='Analiz ediliyor...';try{const r=await fetch('/dct/passive/analyze',{method:'POST',body:fd});o.innerText=JSON.stringify(await r.json(),null,2);}catch(e){o.innerText='Analiz hatası: '+e;}}
</script></body></html>
"""

# ==================== SOCKETIO EVENTS ====================
@socketio.on('update_service_info')
def handle_update_info(data):
    SERVICE_INFO['musteri'] = data.get('musteri', ''); SERVICE_INFO['plaka'] = data.get('plaka', ''); SERVICE_INFO['sikayet'] = data.get('sikayet', '')

@socketio.on('arac_sec')
def handle_arac_sec(data):
    global ARAC_CONFIG
    try:
        ARAC_CONFIG = arac_konfigurasyonu_hesapla(
            data.get('marka', 'yamaha').lower(), 
            data.get('model', 'r25').lower(), 
            int(data.get('yil', 2020)), 
            data.get('konnektor', 'auto').lower(),
            data.get('manuel_model', '')
        )
        msg = f"✅ ARAÇ: {ARAC_CONFIG['marka'].upper()} {ARAC_CONFIG['model'].upper()} ({ARAC_CONFIG['yil']})\nProtokol: {ARAC_CONFIG['protokol'].upper()}\nPin Şeması: {ARAC_CONFIG['pin_sema']}\nNot: {ARAC_CONFIG['notlar']}"
        socketio.emit('arac_sec_result', {'ok': True, 'msg': msg})
    except Exception as e: socketio.emit('arac_sec_result', {'ok': False, 'msg': str(e)})



@app.route('/api/dyno/status', methods=['GET'])
def api_dyno_status():
    return jsonify({'ok': True, **_dyno_public_state()})

@app.route('/api/dyno/start', methods=['POST'])
def api_dyno_start():
    payload = request.get_json(silent=True) or {}
    mode = str(payload.get('mode') or 'standard').strip().lower()
    if mode not in ('standard', 'honda_dct'):
        return jsonify({'ok': False, 'error': 'DYNO modu gecersiz.'}), 400
    with _conn_lock:
        conn = _connection if _mode == 'real' else None
    use_j2534 = bool(not (conn is not None and conn.is_connected()) and
                     _j2534_state.get('dll') is not None and
                     _j2534_state.get('channel_id') is not None and
                     _j2534_state.get('protocol') == 'iso15765')
    if not (conn is not None and conn.is_connected()) and not use_j2534:
        return jsonify({'ok': False, 'error': 'Gercek ECU telemetrisi icin ELM327/USB-COM baglantisi veya J2534 ISO15765 kanali acin.'}), 409
    try:
        radius = float(payload.get('wheel_radius_m') or 0.30)
        if not 0.05 <= radius <= 1.0:
            raise ValueError('Roller yaricapi 0.05-1.00 metre araliginda olmali.')
        model = str(payload.get('model') or '').strip()
        year = int(payload.get('year')) if payload.get('year') else None
        dct_confirmed = bool(payload.get('dct_mode_confirmed'))
        if mode == 'honda_dct' and not dct_confirmed:
            return jsonify({'ok': False, 'error': 'Honda DCT dyno modu henuz etkin oldugu dogrulanmadi. Uygun Honda HDS veya DCT Dyno araci ile modu etkinlestirip onaylayin. Bu uygulama dogrulanmamis DCT komutu gondermez.'}), 409
        with _DYNO_LOCK:
            if _DYNO_STATE['active']:
                return jsonify({'ok': False, 'error': 'Bir DYNO oturumu zaten calisiyor.'}), 409
            stop_event = threading.Event() if use_j2534 else None
            worker = None
            _DYNO_STATE.update({'active': True, 'mode': mode, 'model': model, 'year': year,
                'started_at': datetime.now().astimezone().isoformat(timespec='seconds'),
                'start_monotonic': time.monotonic(), 'samples': [], 'wheel_radius_m': radius,
                'load_force_n': None, 'dct_mode_confirmed': dct_confirmed, 'last_sample': None,
                'source': 'J2534 ISO15765' if use_j2534 else 'ELM327 / USB-COM',
                'worker': None, 'stop_event': stop_event})
            if use_j2534:
                worker = threading.Thread(target=_dyno_j2534_worker, args=(stop_event,),
                                          daemon=True, name='DYNO-J2534-OBD')
                _DYNO_STATE['worker'] = worker
                worker.start()
        write_log('DYNO OTURUMU BASLADI', f'{mode} | {model} {year or ""} | source={_DYNO_STATE["source"]} | radius={radius}m')
        return jsonify({'ok': True, 'message': 'Gercek ECU telemetri kaydi baslatildi.', **_dyno_public_state()})
    except Exception as exc:
        return jsonify({'ok': False, 'error': str(exc)}), 400

@app.route('/api/dyno/load', methods=['POST'])
def api_dyno_load():
    payload = request.get_json(silent=True) or {}
    try:
        force = payload.get('force_n')
        force = None if force in (None, '') else float(force)
        if force is not None and not 0 <= force <= 10000:
            raise ValueError('Yuk kuvveti 0-10000 N araliginda olmali.')
        radius = float(payload.get('wheel_radius_m') or 0.30)
        if not 0.05 <= radius <= 1.0:
            raise ValueError('Roller yaricapi 0.05-1.00 metre araliginda olmali.')
        with _DYNO_LOCK:
            if not _DYNO_STATE['active']:
                return jsonify({'ok': False, 'error': 'Aktif DYNO oturumu yok.'}), 409
            _DYNO_STATE['load_force_n'] = force
            _DYNO_STATE['wheel_radius_m'] = radius
        return jsonify({'ok': True, **_dyno_public_state()})
    except Exception as exc:
        return jsonify({'ok': False, 'error': str(exc)}), 400

@app.route('/api/dyno/stop', methods=['POST'])
def api_dyno_stop():
    with _DYNO_LOCK:
        if not _DYNO_STATE['active']:
            return jsonify({'ok': False, 'error': 'Aktif DYNO oturumu yok.'}), 409
        count = len(_DYNO_STATE['samples'])
        _DYNO_STATE['active'] = False
        _DYNO_STATE['start_monotonic'] = None
        stop_event = _DYNO_STATE.get('stop_event')
        worker = _DYNO_STATE.get('worker')
        if stop_event is not None:
            stop_event.set()
        _DYNO_STATE['stop_event'] = None
        _DYNO_STATE['worker'] = None
    if worker is not None:
        worker.join(timeout=2.0)
    write_log('DYNO OTURUMU TAMAMLANDI', f'{count} gercek ECU ornegi kaydedildi.')
    return jsonify({'ok': True, 'message': f'DYNO kaydi durduruldu. {count} ornek kaydedildi.', **_dyno_public_state()})

@app.route('/api/dyno/export', methods=['GET'])
def api_dyno_export():
    with _DYNO_LOCK:
        rows = list(_DYNO_STATE['samples'])
        mode = _DYNO_STATE['mode']
        year = _DYNO_STATE['year']
    output = io.StringIO()
    fields = ['time','elapsed_s','source','rpm','speed_kmh','tps_pct','coolant_c','map_kpa','voltage_v',
              'load_force_n','roller_radius_m','roller_rpm','wheel_torque_nm','wheel_power_kw','wheel_power_hp']
    writer = csv.DictWriter(output, fieldnames=fields, extrasaction='ignore')
    writer.writeheader()
    writer.writerows(rows)
    response = app.response_class(output.getvalue(), mimetype='text/csv; charset=utf-8')
    response.headers['Content-Disposition'] = 'attachment; filename="seyfi_dyno_%s_%s.csv"' % (mode, year or 'session')
    return response

@app.route('/api/ecu-lab/log', methods=['GET'])
def api_ecu_lab_log():
    try:
        limit = max(1, min(int(request.args.get('limit', 300)), 5000))
    except (TypeError, ValueError):
        limit = 300
    with _ECU_LAB_LOCK:
        entries = list(_ECU_LAB_LOG[-limit:])
        total = len(_ECU_LAB_LOG)
    return jsonify({'ok': True, 'total': total, 'entries': entries})

@app.route('/api/ecu-lab/log/clear', methods=['POST'])
def api_ecu_lab_log_clear():
    with _ECU_LAB_LOCK:
        _ECU_LAB_LOG.clear()
    return jsonify({'ok': True, 'message': 'Bellek kaydı temizlendi.'})

@app.route('/api/ecu-lab/serial/ports', methods=['GET'])
def api_ecu_lab_serial_ports():
    if not PYSERIAL_OK:
        return jsonify({'ok': False, 'available': False, 'ports': [], 'error': 'pyserial kurulu degil.'})
    ports = [{'device': p.device, 'description': p.description or ''} for p in serial.tools.list_ports.comports()]
    return jsonify({'ok': True, 'available': True, 'ports': ports})

@app.route('/api/ecu-lab/serial/start', methods=['POST'])
def api_ecu_lab_serial_start():
    payload = request.get_json(silent=True) or {}
    port_name = str(payload.get('port') or '').strip()
    try:
        baud_rate = int(payload.get('baud') or 10400)
        if not port_name or baud_rate < 300 or baud_rate > 1000000:
            raise ValueError('Gecerli COM portu ve baud hizi secin.')
        if not PYSERIAL_OK:
            raise RuntimeError('pyserial kurulu degil. py -m pip install pyserial')
        with _ECU_SERIAL_LOCK:
            if _ECU_SERIAL_STATE.get('thread') is not None:
                raise RuntimeError('Seri dinleyici zaten calisiyor.')
            stop_event = threading.Event()
            worker = threading.Thread(target=_ecu_serial_worker, args=(port_name, baud_rate, stop_event),
                                      daemon=True, name='ECU-Serial-RX')
            _ECU_SERIAL_STATE.update({'thread': worker, 'stop': stop_event, 'port': port_name,
                                      'baud': baud_rate, 'connected': False, 'error': ''})
            worker.start()
        return jsonify({'ok': True, 'message': f'{port_name} / {baud_rate} baud RX dinleyicisi baslatildi. TX gonderilmiyor.'})
    except Exception as exc:
        return jsonify({'ok': False, 'message': str(exc)}), 400

@app.route('/api/ecu-lab/serial/stop', methods=['POST'])
def api_ecu_lab_serial_stop():
    with _ECU_SERIAL_LOCK:
        stop_event = _ECU_SERIAL_STATE.get('stop')
        worker = _ECU_SERIAL_STATE.get('thread')
        if stop_event is not None:
            stop_event.set()
    if worker is not None:
        worker.join(timeout=1.5)
    return jsonify({'ok': True, 'message': 'Seri dinleyici durdurma istendi.'})

@app.route('/api/j2534/status', methods=['GET'])
def api_j2534_status():
    return jsonify(j2534_status())

@app.route('/api/j2534/open', methods=['POST'])
def api_j2534_open():
    payload = request.get_json(silent=True) or {}
    ok, msg = j2534_open_passive(payload.get("protocol", "can"), payload.get("baud"))
    write_log("J2534 PASIF KANAL", msg)
    return jsonify({"ok": ok, "message": msg, "status": j2534_status()}), (200 if ok else 400)

@app.route('/api/j2534/kline/init', methods=['POST'])
def api_j2534_kline_init():
    payload = request.get_json(silent=True) or {}
    try:
        target = int(str(payload.get('target_address') or '33'), 16)
        source = int(str(payload.get('source_address') or 'F1'), 16)
        result = j2534_initialize_kline(payload.get('init_type', 'five_baud'), target, source)
        write_log('J2534 K-LINE INIT', json.dumps(result, ensure_ascii=False))
        return jsonify(result), (200 if result.get('ok') else 400)
    except Exception as exc:
        return jsonify({'ok': False, 'error': str(exc)}), 400

@app.route('/api/j2534/read', methods=['GET'])
def api_j2534_read():
    result = j2534_read_passive(request.args.get("timeout_ms", 250))
    if result.get("frames"):
        write_log("J2534 RX", f"{result['count']} mesaj alindi.")
    return jsonify(result), (200 if result.get("ok") else 400)

@app.route('/api/j2534/obd/live', methods=['POST'])
def api_j2534_obd_live():
    result = j2534_read_obd_live_data()
    write_log("J2534 MOTOR OBD CANLI VERI", json.dumps(result, ensure_ascii=False))
    return jsonify(result), (200 if result.get("ok") else 400)

@app.route('/api/j2534/obd/dtc', methods=['POST'])
def api_j2534_obd_dtc():
    codes, detail = j2534_send_obd_mode03()
    if codes is None:
        write_log("J2534 OBD-II DTC", detail)
        return jsonify({"ok": False, "error": detail, "scope": "standard_engine_obd_only"}), 400
    result = {"ok": True, "codes": [{"code": code, "description": dtc_desc(code)} for code, _ in codes],
              "count": len(codes), "scope": "standard_engine_obd_only",
              "note": "Bu sonuç ABS veya Honda DCT arıza taraması değildir."}
    write_log("J2534 OBD-II DTC", detail + "\\n" + ("\\n".join(x["code"] for x in result["codes"]) or "Kod bulunamadi."))
    return jsonify(result)

@app.route('/api/ecu-monitor/analyze', methods=['POST'])
def api_ecu_monitor_analyze():
    payload = request.get_json(silent=True) or {}
    try:
        result = analyze_ecu_can_capture(payload.get("frames", []), payload.get("module", "general"))
        write_log("ECU/ABS PASIF CAN ANALIZ", f"{result['module']} | {result['frame_count']} frame | {result['unique_can_ids']} CAN ID")
        return jsonify(result)
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400

@app.route('/api/j2534/obd/clear', methods=['POST'])
def api_j2534_obd_clear():
    payload = request.get_json(silent=True) or {}
    if payload.get("confirmation") != "CLEAR_ENGINE_OBD_CONFIRMED":
        return jsonify({"ok": False, "error": "Açık kullanıcı onayı gerekli. Komut gönderilmedi."}), 409
    ok, detail = j2534_clear_obd_mode04()
    write_log("J2534 MOTOR OBD DTC SILME", detail)
    return jsonify({"ok": ok, "message": detail,
                    "scope": "standard_engine_emissions_obd_only"}), (200 if ok else 400)

@app.route('/api/j2534/close', methods=['POST'])
def api_j2534_close():
    ok, msg = j2534_close()
    write_log("J2534 KAPAT", msg)
    return jsonify({"ok": ok, "message": msg}), (200 if ok else 400)

@app.route('/api/abs/profile-shell', methods=['POST'])
def api_abs_profile_shell():
    payload = request.get_json(silent=True) or {}
    try:
        result = build_abs_profile_shell(payload.get("brand", ""), payload.get("model", ""),
                                         payload.get("year", ""), payload.get("transport", "unknown"))
        return jsonify({"ok": True, "profile": result})
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400


@app.route('/api/abs/operations', methods=['GET'])
def api_abs_operations():
    try:
        return jsonify(abs_operation_matrix(request.args.get("brand", ""),
            request.args.get("model", ""), request.args.get("year", "")))
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400

@app.route('/api/abs/operation', methods=['POST'])
def api_abs_operation():
    payload = request.get_json(silent=True) or {}
    try:
        result = abs_operation_gate(payload.get("brand", ""), payload.get("model", ""),
            payload.get("year", ""), payload.get("operation", ""),
            payload.get("confirmation"))
        write_log("ABS İŞLEM KAPISI", json.dumps(result, ensure_ascii=False))
        return jsonify(result), (200 if result.get("ok") else 409)
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400

@app.route('/api/abs/preflight', methods=['POST'])
def api_abs_preflight():
    payload = request.get_json(silent=True) or {}
    return jsonify(abs_preflight_check(payload.get("battery_voltage"),
        payload.get("ignition_on", False), payload.get("wheel_stationary", True),
        payload.get("hydraulic_work", False)))

@app.route('/api/abs/dtc/decode', methods=['POST'])
def api_abs_dtc_decode():
    payload = request.get_json(silent=True) or {}
    try:
        return jsonify(decode_abs_dtc_record(payload.get("brand", ""), payload.get("model", ""),
                                       payload.get("year", ""), payload.get("code", "")))
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400

@app.route('/dct/passive/analyze', methods=['POST'])
def dct_passive_capture_analyze():
    uploaded=request.files.get('file')
    if uploaded is None or not uploaded.filename:
        return jsonify({"ok":False,"error":"DCT kayıt dosyası seçilmedi."}),400
    filename=os.path.basename(uploaded.filename)
    if os.path.splitext(filename)[1].lower() not in (".log",".txt",".asc",".csv"):
        return jsonify({"ok":False,"error":"Yalnızca .log, .txt, .asc veya .csv kabul edilir."}),400
    content=uploaded.read(DCT_CAPTURE_MAX_BYTES+1)
    if len(content)>DCT_CAPTURE_MAX_BYTES:
        return jsonify({"ok":False,"error":"Dosya 5 MB sınırını aşıyor."}),413
    result=analyze_dct_capture(content.decode("utf-8-sig",errors="replace"),filename)
    write_log("DCT PASİF KAYIT",f"{filename}: {result['frame_count']} çerçeve; TX {result['tx_count']}; RX {result['rx_count']}")
    return jsonify(result)

@socketio.on('dct_profile_check')
def handle_dct_profile_check(data):
    """Yerel Honda DCT servis profili kontrolü; ECU'ya komut göndermez."""
    try:
        payload = data or {}
        result = evaluate_dct_profile(payload.get('model', ''), payload.get('year', ''))
        result.update({'model': str(payload.get('model', '')).strip(), 'year': int(payload.get('year', 0))})
        socketio.emit('dct_profile_result', result)
        write_log("DCT PROFİL KONTROLÜ", f"{result.get('model')} {result.get('year')}: {result.get('status')}")
    except Exception as exc:
        socketio.emit('dct_profile_result', {'ok': False, 'verified': False, 'status': 'DCT PROFİL KONTROLÜ BAŞARISIZ', 'message': str(exc)})

@socketio.on('dct_decode_dtc')
def handle_dct_decode_dtc(data):
    """Yalnızca yerel, kaynaklı DCT kod sözlüğünde arama yapar."""
    payload = data or {}
    result = decode_dct_dtc(payload.get('model', ''), payload.get('year', ''), payload.get('code', ''))
    socketio.emit('dct_decode_dtc_result', result)
    if result.get('ok'):
        write_log("DCT DTC SÖZLÜK SORGUSU", f"{payload.get('model')} {payload.get('year')} {result.get('code')}: {'bulundu' if result.get('found') else 'kayıt yok'}")

@socketio.on('abs_decode_dtc')
def handle_abs_decode_dtc(data):
    """Yalnızca yerel, araç özel ABS DTC sözlüğünde arama yapar; ECU'ya erişmez."""
    try:
        payload = data or {}
        brand = str(payload.get('brand', '')).strip()
        model = str(payload.get('model', '')).strip()
        year = payload.get('year', '')
        code = str(payload.get('code', '')).strip().upper()
        if not code:
            raise ValueError("ABS arıza kodunu girin.")
        key = abs_profile_key(brand, model, year)
        entry = ABS_DTC_DATABASE.get(key + (code,))
        found = bool(entry and entry.get('verified') is True)
        result = {
            'ok': True, 'found': found, 'code': code,
            'description': (entry.get('description_tr') if found else
                "Bu marka / model / yıl için doğrulanmış ABS kod açıklaması bulunamadı."),
            'source': entry.get('source') if found else None,
        }
        socketio.emit('abs_decode_dtc_result', result)
        write_log("ABS DTC SÖZLÜK SORGUSU",
                  f"{brand} {model} {year} kod {code}: {'bulundu' if found else 'kayıt yok'}")
    except Exception as exc:
        socketio.emit('abs_decode_dtc_result', {
            'ok': False, 'found': False, 'code': '',
            'description': str(exc), 'source': None
        })

@socketio.on('abs_profile_check')
def handle_abs_profile_check(data):
    """Yerel ABS profilini kontrol eder; adaptöre/ECU'ya hiçbir komut göndermez."""
    try:
        brand = str((data or {}).get('brand', '')).strip()
        model = str((data or {}).get('model', '')).strip()
        year = (data or {}).get('year', '')
        with _conn_lock:
            conn = _connection if _mode == 'real' else None
        result = evaluate_abs_profile(brand, model, year, conn)
        result['hardware_evidence'] = get_abs_hardware_evidence(brand, model, year)
        result.update({'brand': brand, 'model': model, 'year': int(year)})
        socketio.emit('abs_profile_result', result)
        write_log("ABS PROFİL KONTROLÜ", f"{brand} {model} {year}: {result['status']} | {result['message']}")
    except Exception as exc:
        socketio.emit('abs_profile_result', {'ok': False, 'verified': False,
            'status': 'PROFİL KONTROLÜ BAŞARISIZ', 'message': str(exc),
            'protocol': 'Doğrulanmadı', 'profile_id': None})

@socketio.on('scan_dtc')
def handle_scan():
    with _conn_lock:
        conn = _connection
        mode = _mode
    if mode != 'real' or conn is None or not conn.is_connected():
        socketio.emit('dtc_result', {'codes': [], 'ok': False, 'error': 'Gerçek ECU bağlantısı yok. Önce adaptöre bağlanın.'})
        return
    try:
        if isinstance(conn, (ELM327TcpConnection, J2534OBDConnection)):
            raw_codes, raw_response = conn.read_dtcs()
            if raw_codes is None:
                socketio.emit('dtc_result', {'codes': [], 'ok': False, 'error': 'ECU arıza sorgusuna geçerli yanıt vermedi: ' + ELM327TcpConnection._short(raw_response)})
                return
        else:
            with _query_lock:
                response = conn.query(obd.commands.GET_DTC)
            if response.is_null():
                socketio.emit('dtc_result', {'codes': [], 'ok': False,
                    'error': 'ECU arıza sorgusuna yanıt vermedi. Bu, arıza olmadığı anlamına gelmez.'})
                return
            raw_codes = response.value or []
        codes = []
        for code, description in raw_codes:
            code = str(code).upper().strip()
            # OBD decoder açıklaması varsa sakla; yerel Türkçe sözlük yalnızca eşleşen kodlarda kullanılır.
            tr = dtc_desc(code)
            if tr == 'Bilinmeyen arıza kodu' and description:
                tr = f"Üretici/OBD açıklaması: {description} (Türkçe karşılığı henüz tanımlı değil)"
            codes.append(f"{code}: {tr}")
        with _live_data_lock:
            live_snapshot = dict(_last_live_data)
        smart_diag = akilli_teshis([str(code) for code, _ in raw_codes], live_snapshot) if raw_codes else []
        log_details = "\n".join(codes) if codes else "ECU yanıt verdi; kayıtlı DTC bulunmadı."
        if smart_diag:
            log_details += "\n\nAKILLI TEŞHİS ÖN DEĞERLENDİRMESİ:\n" + "\n".join(smart_diag)
        write_log("GERÇEK ECU ARIZA TARAMASI", log_details)
        socketio.emit('dtc_result', {'codes': codes, 'ok': True, 'smart_diag': smart_diag,
            'live_data_used': sorted(live_snapshot.keys())})
        if codes:
            spoken = "ECU arıza taraması tamamlandı. " + ". ".join(codes[:4])
            if smart_diag: spoken += ". Akıllı değerlendirme: " + ". ".join(smart_diag[:2])
            safe_speak(spoken)
        else:
            safe_speak("ECU yanıt verdi. Kayıtlı arıza kodu bulunmadı.")
    except Exception as exc:
        socketio.emit('dtc_result', {'codes': [], 'ok': False, 'error': f"ECU arıza okuma hatası: {exc}"})


@socketio.on('clear_dtc')
def handle_clear():
    with _conn_lock:
        conn = _connection
        mode = _mode
    if mode != 'real' or conn is None or not conn.is_connected():
        socketio.emit('dtc_cleared', {'ok': False, 'msg': 'ECU bağlı değil. Silme komutu gönderilmedi.'})
        return
    try:
        # Önce mevcut kodları kaydet; sonra standart Mode 04 isteğini gönder.
        if isinstance(conn, (ELM327TcpConnection, J2534OBDConnection)):
            before, before_raw = conn.read_dtcs()
            if before is None:
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'Silme öncesi ECU arıza hafızası okunamadı. Silme yapılmadı.'})
                return
            before_codes = [str(c) for c, _ in before]
            if isinstance(conn, J2534OBDConnection):
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'J2534 köprüsünde arıza silme güvenlik nedeniyle etkin değil; yalnızca okuma desteklenir.'})
                return
            clear_ok, clear_raw = conn.clear_dtcs()
            if not clear_ok:
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'ECU Mode 04 isteğini doğrulamadı: ' + ELM327TcpConnection._short(clear_raw)})
                return
            time.sleep(1.0)
            after, after_raw = conn.read_dtcs()
            if after is None:
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'Silme komutu gönderildi ancak ECU yeniden okunamadı; başarılı kabul edilmedi.'})
                return
            after_codes = [str(c) for c, _ in after]
        else:
            with _query_lock:
                before = conn.query(obd.commands.GET_DTC)
            if before.is_null():
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'Silme öncesi arıza hafızası okunamadı. Güvenlik için silme yapılmadı.'})
                return
            before_codes = [str(c) for c, _ in (before.value or [])]
            with _query_lock:
                clear_response = conn.query(obd.commands.CLEAR_DTC)
            if clear_response.is_null():
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'ECU silme isteğine olumlu yanıt vermedi. Sonuç doğrulanamadı.'})
                return
            time.sleep(1.0)
            with _query_lock:
                after = conn.query(obd.commands.GET_DTC)
            if after.is_null():
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'Silme komutu gönderildi ancak ECU yeniden okunamadı; silme başarılı kabul edilmedi.'})
                return
            after_codes = [str(c) for c, _ in (after.value or [])]
        if not after_codes:
            write_log("DTC SİLME DOĞRULANDI", f"Önce: {before_codes}\nSonra: boş")
            socketio.emit('dtc_cleared', {'ok': True, 'msg': 'ECU yeniden okundu; kayıtlı arıza kodu kalmadı.'})
            safe_speak("Arıza silme işlemi tamamlandı. ECU yeniden okundu ve kayıtlı arıza kodu kalmadığı doğrulandı.")
        else:
            write_log("DTC SİLME SONRASI KOD KALDI", f"Önce: {before_codes}\nSonra: {after_codes}")
            socketio.emit('dtc_cleared', {'ok': False, 'msg': 'ECU yeniden okundu; bazı kodlar hâlâ mevcut: ' + ', '.join(after_codes)})
            safe_speak("Silme işleminden sonra ECU tekrar okundu. Bazı arıza kodları hâlâ mevcut: " + ", ".join(after_codes))
    except Exception as exc:
        socketio.emit('dtc_cleared', {'ok': False, 'msg': f"Silme işlemi başarısız: {exc}"})
        safe_speak("Arıza silme işlemi başarısız oldu: " + str(exc)[:180])


@socketio.on('test_info')
def handle_test_info():
    with _conn_lock:
        conn = _connection
        mode = _mode
    connected = bool(conn and conn.is_connected())
    details = {'mode': mode, 'connected': connected}
    if connected:
        details['adapter'] = getattr(conn, 'port_name', lambda: 'Bilinmiyor')()
        details['protocol'] = getattr(conn, 'protocol_name', lambda: 'Bilinmiyor')()
    socketio.emit('test_info_result', details)


@socketio.on('test_ecu_info')
def handle_test_ecu_info():
    with _conn_lock:
        conn = _connection
        mode = _mode
    if mode != 'real' or conn is None or not conn.is_connected():
        socketio.emit('test_ecu_result', {'ok': False, 'msg': 'ECU bağlı değil.'})
        return
    data = {}
    # VIN yalnızca ECU gerçekten yanıt verirse gösterilir.
    if isinstance(conn, J2534OBDConnection):
        data['Not'] = 'J2534 motor ECU OBD-II Mode 03 okuma desteği açık; VIN ve canlı PID sorguları bu köprüde henüz uygulanmadı.'
    elif isinstance(conn, ELM327TcpConnection):
        vin, _raw = conn.read_vin()
        if vin: data['VIN'] = vin
    else:
        vin_cmd = getattr(obd.commands, 'VIN', None) or getattr(obd.commands, 'GET_VIN', None)
        if vin_cmd is not None:
            try:
                with _query_lock:
                    response = conn.query(vin_cmd)
                if not response.is_null(): data['VIN'] = str(response.value)
            except Exception:
                pass
    data['Protokol'] = getattr(conn, 'protocol_name', lambda: 'Bilinmiyor')()
    data['Adaptör'] = getattr(conn, 'port_name', lambda: 'Bilinmiyor')()
    socketio.emit('test_ecu_result', {'ok': True, 'data': data,
        'msg': 'Yalnızca ECU/adaptörün gerçekten yanıtladığı bilgiler listelendi.'})


@socketio.on('test_raw')
def handle_test_raw(data):
    # Güvenlik: yalnızca salt-okunur ELM durum ve OBD servisleri kabul edilir.
    with _conn_lock:
        conn = _connection
        mode = _mode
    raw = str((data or {}).get('cmd', '')).strip().upper().replace(' ', '')
    allowed_at = {'ATI', 'ATDP', 'ATDPN', 'ATRV', 'AT@1'}
    allowed_services = ('01', '03', '07', '09')
    valid_hex = bool(raw) and len(raw) % 2 == 0 and all(ch in '0123456789ABCDEF' for ch in raw)
    if raw not in allowed_at and not (valid_hex and raw.startswith(allowed_services)):
        socketio.emit('test_raw_result', {'ok': False,
            'msg': 'Komut engellendi. Yalnızca ATI/ATDP/ATDPN/ATRV/AT@1 ve salt-okunur OBD 01/03/07/09 servisleri kullanılabilir.'})
        return
    if mode != 'real' or conn is None or not conn.is_connected():
        socketio.emit('test_raw_result', {'ok': False, 'msg': 'Gerçek adaptör bağlantısı yok.'})
        return
    if not isinstance(conn, ELM327TcpConnection):
        socketio.emit('test_raw_result', {'ok': False, 'msg': 'Ham komut testi şu anda Wi-Fi ELM327 taşımasında kullanılabilir; USB/COM için gönderilmedi.'})
        return
    try:
        response = conn.send_read_only(raw)
        socketio.emit('test_raw_result', {'ok': True, 'raw': response, 'msg': response})
    except Exception as exc:
        socketio.emit('test_raw_result', {'ok': False, 'msg': str(exc)})

# ==================== YÖNLENDİRİCİ (K-LINE vs CAN BUS) ====================
@socketio.on('gkbus_connect')
def handle_gkbus_connect():
    if ARAC_CONFIG['protokol'] in ['can', 'can_uds']: ok, msg = can_baglan()
    else: ok, msg = kline_baglan()
    socketio.emit('gkbus_result', {'ok': ok, 'msg': msg})

@socketio.on('gkbus_test')
def handle_gkbus_test(data):
    code = int(data.get('code', 0x36))
    if ARAC_CONFIG['protokol'] in ['can', 'can_uds']: ok, msg = can_aktif_test(code)
    else: ok, msg = kline_aktif_test(code)
    socketio.emit('gkbus_test_result', {'ok': ok, 'msg': msg})

@socketio.on('gkbus_d07')
def handle_gkbus_d07():
    if ARAC_CONFIG['protokol'] in ['can', 'can_uds']: d, m = can_d07_oku()
    else: d, m = kline_d07_oku()
    socketio.emit('gkbus_d07_result', {'ok': d is not None, 'data': str(d) if d else '', 'msg': m})

@app.route('/abs/passive/analyze', methods=['POST'])
def abs_passive_analyze():
    """CAN kaydını yerel analiz eder; adaptör veya CAN arayüzü açmaz."""
    uploaded = request.files.get('file')
    if uploaded is None or not uploaded.filename:
        return jsonify({"ok": False, "error": "CAN kayıt dosyası seçilmedi."}), 400
    filename = os.path.basename(uploaded.filename)
    if os.path.splitext(filename)[1].lower() not in (".log", ".txt", ".asc", ".csv"):
        return jsonify({"ok": False, "error": "Yalnızca .log, .txt, .asc veya .csv dosyaları kabul edilir."}), 400
    try:
        content = uploaded.read(5 * 1024 * 1024 + 1)
        if len(content) > 5 * 1024 * 1024:
            return jsonify({"ok": False, "error": "Dosya 5 MB sınırını aşıyor."}), 413
        result = analyze_abs_can_log(content.decode("utf-8-sig", errors="replace"), filename)
        result["ok"] = True
        write_log("ABS PASİF CAN KAYDI ANALİZİ", f"{filename}: {result['frame_count']} çerçeve, {result['unique_ids']} CAN ID")
        return jsonify(result)
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400


@app.route('/log')
def view_logs():
    if os.path.exists(LOG_FILE):
        with open(LOG_FILE, "r", encoding="utf-8") as f: return f"<pre style='color:black; background:white; padding:20px;'>{f.read()}</pre>"
    return "Log yok."

def run_flask(): socketio.run(app, host='0.0.0.0', port=5000, allow_unsafe_werkzeug=True)
@app.route('/')
def index_page(): return render_template_string(HTML_CODE)

# ==================== SEYFI USTA YEREL SES SISTEMI ====================
# TTS + Vosk ASR ayrı, tamamen yerel modellerle çalışır.
VOICE_ENGINE = None
_VOICE_UNAVAILABLE_REPORTED = False

def speak(phrase):
    """Konuşma isteğini yerel SeyfiVoice motoruna aktar; motor yoksa logla."""
    global _VOICE_UNAVAILABLE_REPORTED
    engine = VOICE_ENGINE
    if engine is None:
        if not _VOICE_UNAVAILABLE_REPORTED:
            _VOICE_UNAVAILABLE_REPORTED = True
            try:
                write_log("SEYFİ SES MOTORU", "Ses motoru başlatılmadı; çevrimdışı konuşma kullanılamıyor.")
            except Exception:
                print("SEYFİ SES MOTORU: Ses motoru başlatılmadı.")
        return False
    try:
        engine.speak(phrase)
        return True
    except Exception as exc:
        try:
            write_log("SEYFİ SES MOTORU HATASI", str(exc))
        except Exception:
            print("SEYFİ SES MOTORU HATASI:", exc)
        return False

def start_speech_worker():
    # Geriye dönük uyumluluk: ses işçileri SeyfiVoice tarafından başlatılır.
    return True

def local_ai_greeting():
    return ("Merhaba, ben Seyfi Usta. MOTO DIAG PRO çevrimdışı teşhis sistemine hoş geldiniz. "
            "Şimdi bağlantı cihazınızı kontrol ediyorum. Bana seslenerek arıza taraması "
            "yapmamı, canlı verileri yorumlamamı veya test ekranını açmamı söyleyebilirsiniz.")

def discover_adapter_candidates():
    """Önce bilinen Wi-Fi ELM327 uçlarını, sonra görünen seri/USB portlarını bulur.
    Bulunma, ECU'nun cevap verdiği anlamına gelmez; ECU bağlantısı ayrıca doğrulanır.
    """
    found = []
    wifi = find_wifi_adapter()
    if wifi:
        found.append(('wifi', wifi, 'Wi-Fi ELM327'))
    if PYSERIAL_OK:
        try:
            for port in serial.tools.list_ports.comports():
                device = str(getattr(port, 'device', '') or '').strip()
                if device and all(x[1].lower() != device.lower() for x in found):
                    desc = str(getattr(port, 'description', '') or 'USB/Seri adaptör')
                    found.append(('serial', device, desc))
        except Exception as exc:
            write_log('USB PORT TARAMA', str(exc))
    # J2534 DLL varsa USB seri portlardan once dene; Scanmatik/Tactrix sanal COM
    # portu gorunse bile tasima DLL uzerinden acilir.
    if sys.platform == "win32" and any(os.path.isfile(p) for p in _j2534_candidates()):
        found.insert(0, ('j2534', 'j2534', 'J2534 arayuzu (Scanmatik / Tactrix / uyumlu)'))
    return found


def safe_speak(phrase):
    """Ses fonksiyonu herhangi bir nedenle yüklenemese bile uygulamanın akışını durdurmaz."""
    try:
        speaker = globals().get("speak")
        if callable(speaker):
            speaker(phrase)
            return
        q = globals().get("_speech_queue")
        if q is not None:
            q.put(str(phrase or ""))
        else:
            write_log("SES MOTORU", "Ses kuyruğu henüz hazır değil.")
    except Exception as exc:
        try:
            write_log("SES MOTORU HATASI", str(exc))
        except Exception:
            pass


# ==================== LAUNCHER (TKINTER) ====================
class App:
    def __init__(self, root):
        self.root = root; root.title("Seyfi Usta - Akıllı Motosiklet Teşhis"); root.geometry("1050x650"); root.configure(bg="#121212")
        self.running = True; self.log_buffer = []; self._active_run_id = None; self._searching = False
        self._candidate_queue = []; self._ai_ready = False; self._startup_done = False
        frm_top = tk.Frame(root, bg="#1e1e1e", pady=10); frm_top.pack(fill="x")
        tk.Label(frm_top, text="ADAPTÖR:", bg="#1e1e1e", fg="#ccc", font=("Arial", 10, "bold")).pack(side="left", padx=(15, 3))
        self.addr_var = tk.StringVar(value="192.168.0.10:35000")
        tk.Entry(frm_top, textvariable=self.addr_var, width=20, bg="#2a2a2a", fg="#00e5ff", insertbackground="#00e5ff").pack(side="left")
        self.btn_real = tk.Button(frm_top, text="🔌 BAĞLAN", bg="#00e5ff", fg="black", font=("Arial", 10, "bold"), command=self.toggle_real); self.btn_real.pack(side="left", padx=10)
        tk.Button(frm_top, text="🤖 AI / SESİ TEKRARLA", bg="#673ab7", fg="white", font=("Arial", 9, "bold"), command=self.repeat_greeting).pack(side="left", padx=5)
        tk.Button(frm_top, text="🌐 WEB ARAYÜZÜ", bg="#00ff00", fg="black", font=("Arial", 10, "bold"), command=lambda: webbrowser.open("http://localhost:5000")).pack(side="left", padx=10)
        tk.Button(frm_top, text="💾 LOG KAYDET", bg="#ffaa00", fg="black", font=("Arial", 10, "bold"), command=self.save_log).pack(side="left", padx=10)
        self.lbl_status = tk.Label(frm_top, text="DURUM: BAĞLANTI ARANIYOR", bg="#1e1e1e", fg="#ffaa00", font=("Arial", 10, "bold")); self.lbl_status.pack(side="right", padx=15)
        self.txt_log = scrolledtext.ScrolledText(root, height=20, bg="#000000", fg="#00ff00", font=("Consolas", 10)); self.txt_log.pack(fill="both", expand=True, padx=20, pady=10)
        self.log("Seyfi Usta hazır. Sesli asistan ve yerel AI kontrolü başlatılıyor.")
        start_speech_worker()
        self.root.after(500, self.startup_assistant)

    def log(self, msg):
        entry = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
        self.txt_log.insert(tk.END, entry + "\n"); self.txt_log.see(tk.END); self.log_buffer.append(entry)

    def set_status(self, text, color="#ffaa00"):
        self.lbl_status.config(text="DURUM: " + text, fg=color)

    def repeat_greeting(self):
        if self._ai_ready:
            phrase = "Seyfi Usta akıllı teşhis asistanı hazır. Adaptör bağlantısını kontrol ediyorum."
        else:
            phrase = "Merhaba, Seyfi Usta akıllı motosiklet teşhis sistemine hoş geldiniz. Ses ve komut motorları hazırlanıyor."
        safe_speak(phrase)

    def _handle_voice_status(self, message):
        self.log(message)
        if message.startswith("Türkçe çevrimdışı ses motoru hazır"):
            self._voice_tts_ready = True
        elif message.startswith("MİKROFON/KOMUT TANIMA HATASI"):
            self._voice_asr_ready = False
        elif message.startswith("Seyfi Usta mikrofonu dinliyor"):
            self._voice_asr_ready = True
        elif message.startswith("SES MOTORU HATASI"):
            self._voice_tts_ready = False
        self._ai_ready = self._voice_tts_ready and self._voice_asr_ready

    def startup_assistant(self):
        if self._startup_done: return
        self._startup_done = True
        self._voice_tts_ready = False
        self._voice_asr_ready = False
        self._ai_ready = False
        self.set_status("ÇEVRİMDIŞI TEŞHİS HAZIR")
        self.log("Yerel teşhis kuralları hazır; internet veya harici AI servisi kullanılmıyor.")
        try:
            global VOICE_ENGINE
            from seyfi_voice import SeyfiVoice
            VOICE_ENGINE = SeyfiVoice(
                on_command=lambda phrase: self.root.after(0, lambda p=phrase: self.handle_voice_command(p)),
                on_status=lambda message: self.root.after(0, lambda m=message: self._handle_voice_status(m)),
            )
            VOICE_ENGINE.start(local_ai_greeting())
            self.log("Seyfi Usta yerel ses ve Türkçe komut motorları başlatılıyor.")
        except Exception as exc:
            self.log("SEYFİ SES/KOMUT MOTORU BAŞLATILAMADI: " + str(exc)[:220])
        self.root.after(0, self.begin_adapter_search)

    def handle_voice_command(self, phrase):
        """Türkçe çevrimdışı ses komutlarını güvenli uygulama işlemlerine yönlendirir."""
        import unicodedata
        raw = str(phrase or "").strip().lower()
        normalized = "".join(ch for ch in unicodedata.normalize("NFKD", raw)
                             if not unicodedata.combining(ch))
        normalized = normalized.replace("ı", "i")
        self.log("SES KOMUTU: " + raw)
        if any(x in normalized for x in ("yardim", "komutlar")):
            safe_speak("Şu komutları anlayabiliyorum: bağlan, bağlantıyı kes, arıza tara, arızaları oku, arızaları sil, canlı verileri oku, test yap ve durumu söyle.")
        elif normalized in ("seyfi", "seyfi usta", "usta"):
            safe_speak("Dinliyorum. Buyurun.")
        elif "baglantiyi kes" in normalized or "baglantiyi durdur" in normalized:
            if self._active_run_id is not None: self._stop_real()
            else: safe_speak("Şu anda aktif ECU bağlantısı yok.")
        elif "baglan" in normalized or "tekrar baglan" in normalized or "yeniden baglan" in normalized:
            if self._active_run_id is None: self.begin_adapter_search()
            else: safe_speak("Adaptör bağlantısı zaten aktif.")
        elif any(x in normalized for x in ("arizalari sil", "ariza kodlarini sil")):
            if _get_mode() != "real":
                safe_speak("Arıza silebilmem için önce gerçek ECU bağlantısı kurulmalı.")
            elif messagebox.askyesno("Seyfi Usta", "ECU arıza hafızasını silmek istiyor musunuz?", parent=self.root):
                safe_speak("Arıza silme isteğini gönderiyorum ve sonucu ECU'dan doğrulayacağım.")
                threading.Thread(target=handle_clear, daemon=True).start()
            else:
                safe_speak("Arıza silme işlemi iptal edildi.")
        elif any(x in normalized for x in ("tara", "arizalari oku", "ariza kodlarini oku")):
            if _get_mode() == "real":
                safe_speak("Gerçek ECU arıza hafızasını okuyorum.")
                threading.Thread(target=handle_scan, daemon=True).start()
            else:
                safe_speak("Henüz ECU bağlantısı yok. Önce adaptöre bağlanıyorum.")
                self.begin_adapter_search()
        elif "canli verileri oku" in normalized or "motor verilerini oku" in normalized:
            with _live_data_lock: snapshot = dict(_last_live_data)
            if not snapshot:
                safe_speak("Henüz ECU'dan canlı veri gelmedi. Önce gerçek bağlantı kurulmalı.")
            else:
                parts = []
                labels = (("rpm", "motor devri", "devir"), ("tps", "gaz kelebeği", "yüzde"),
                          ("temp", "motor sıcaklığı", "derece"), ("map", "emme basıncı", "kilopaskal"),
                          ("volt", "akü voltajı", "volt"), ("stft", "kısa dönem yakıt düzeltmesi", "yüzde"))
                for key, label, unit in labels:
                    if key in snapshot: parts.append(f"{label} {snapshot[key]} {unit}")
                safe_speak("Canlı ECU ölçümleri: " + ". ".join(parts))
        elif "rapor olustur" in normalized:
            self.save_log()
            safe_speak("Servis kayıt raporunu kaydetme işlemini başlattım.")
        elif "test yap" in normalized or "testleri ac" in normalized:
            safe_speak("Test ve servis ekranını açıyorum.")
            webbrowser.open("http://localhost:5000")
        elif "durumu soyle" in normalized or "seyfi" == normalized or "seyfi usta" == normalized:
            if _get_mode() == "real":
                safe_speak("ECU bağlantısı aktif. Canlı veri akışını izliyorum.")
            else:
                safe_speak("Teşhis sistemi hazır, ancak ECU bağlantısı henüz doğrulanmadı.")
        else:
            safe_speak("Komutu tam anlayamadım. Yardım veya komutlar diyerek kullanabileceğiniz komutları dinleyebilirsiniz.")

    def _check_ai_then_scan(self):
        self.startup_assistant()

    def begin_adapter_search(self):
        if self._searching or self._active_run_id is not None: return
        self._searching = True
        self._candidate_queue = []
        self.btn_real.config(text="🔍 ARANIYOR...", state="disabled")
        self.set_status("BAĞLANTI CİHAZI ARANIYOR")
        self.log("Wi-Fi ELM327, J2534 ve USB/COM adaptörleri taranıyor...")
        safe_speak("Bağlantı cihazı aranıyor.")
        threading.Thread(target=self._search_and_connect, daemon=True).start()

    def _search_and_connect(self):
        candidates = discover_adapter_candidates()
        self.root.after(0, lambda c=candidates: self._search_done(c))

    def _search_done(self, candidates):
        self._searching = False
        self.btn_real.config(state="normal")
        self._candidate_queue = list(candidates or [])
        if not self._candidate_queue:
            self.addr_var.set("otomatik")
            self.set_status("BAĞLANTI BULUNAMADI", "#ff5252")
            self.log("BAĞLANTI BULUNAMADI. Wi-Fi ELM327, J2534 DLL/cihaz veya USB/COM adaptörü tespit edilemedi.")
            safe_speak("Bağlantı cihazı bulunamadı. Lütfen bağlantı cihazınızı bilgisayara ve motosiklete bağlayın. Bağladınız mı?")
            self.root.after(1200, self.ask_device_connected)
            return
        self.log(f"{len(self._candidate_queue)} olası bağlantı arayüzü bulundu. Her biri ECU yanıtı verene kadar sırayla denenecek.")
        self._try_next_candidate()

    def _try_next_candidate(self):
        if not self._candidate_queue:
            self.set_status("TÜM ADAYLARDA ECU YANITI YOK", "#ff5252")
            self.log("Tüm bulunan adaptör/port adayları denendi; hiçbirinden gerçek ECU yanıtı alınamadı.")
            safe_speak("Adaptör adaylarının hiçbiri motosiklet kontrol ünitesinden yanıt alamadı.")
            self.root.after(500, lambda: messagebox.askyesno(
                "ECU yanıt vermedi",
                "Bulunan tüm bağlantı arayüzleri denendi ancak ECU yanıt vermedi.\n\n"
                "Kontak açık mı, motosiklet soketi doğru mu ve adaptör kablosu tam takılı mı?\n\n"
                "Kontrol ettikten sonra yeniden tarayayım mı?",
                parent=self.root) and self.begin_adapter_search())
            return
        kind, target, desc = self._candidate_queue.pop(0)
        self.addr_var.set(target)
        self.log(f"ADAPTÖR ADAYI DENENİYOR: {desc} ({target}). Önce gerçek ECU yanıtı doğrulanacak.")
        self.set_status("ADAPTÖR BULUNDU · ECU YANITI BEKLENİYOR")
        safe_speak("Bağlantı arayüzü bulundu. ECU yanıtını kontrol ediyorum.")
        self._start_real(target)

    def ask_device_connected(self):
        answer = messagebox.askyesno("Seyfi Usta - Bağlantı cihazı", "Bağlantı cihazı bulunamadı.\n\nLütfen adaptörünüzü bilgisayara ve motosiklete bağlayın.\n\nBağladınız mı?", parent=self.root)
        if answer:
            self.log("Kullanıcı adaptörü bağladığını belirtti. Yeniden tarama başlıyor.")
            self.begin_adapter_search()
        else:
            self.log("Kullanıcı henüz adaptörü bağlamadı. Program beklemede.")
            self.set_status("ADAPTÖR BEKLENİYOR", "#ffaa00")
            safe_speak("Tamam. Adaptörünüzü bağladığınızda Bağlan düğmesine basın. Hazır bekliyorum.")
            self.btn_real.config(text="🔍 TEKRAR ARA", state="normal")

    def save_log(self):
        if not self.log_buffer: return
        fn = f"diag_log_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
        with open(fn, 'w', encoding='utf-8') as f: f.write("\n".join(self.log_buffer))
        messagebox.showinfo("OK", f"Kaydedildi: {fn}")

    def toggle_real(self):
        if self._active_run_id is not None:
            self._stop_real(); return
        if self._searching: return
        target = self.addr_var.get().strip()
        if not target or target.lower() == "otomatik":
            self.begin_adapter_search(); return
        self._candidate_queue = []
        self._start_real(target)

    def _start_real(self, target):
        run_id = _next_run_id(); self._active_run_id = run_id
        self.btn_real.config(text="🔌 KES", bg="#ffaa00", fg="black")
        self.set_status("ECU İLE HABERLEŞİYOR", "#ffaa00")
        threading.Thread(target=real_data_loop, args=(target, run_id), daemon=True).start()
        self._watch_connection(run_id, tries=30)

    def _stop_real(self):
        _cancel_runs(); self._active_run_id = None
        self.btn_real.config(text="🔌 BAĞLAN", bg="#00e5ff", fg="black")
        self.set_status("BAĞLANTI KESİLDİ", "#ff5252")
        socketio.emit('status', {'msg': 'KESİLDİ', 'color': '#ff5252'})
        safe_speak("Bağlantı kesildi.")

    def _watch_connection(self, run_id, tries=30):
        if not _is_current_run(run_id) or self._active_run_id != run_id: return
        if _get_mode() == 'real':
            self.set_status("ECU ONLINE", "#00ff00")
            self.log("GERÇEK ECU YANITI ALINDI. Bağlantı doğrulandı.")
            safe_speak("Motosiklet kontrol ünitesiyle bağlantı kuruldu. Teşhise hazırım.")
            return
        if tries == 0:
            _cancel_runs()
            self._active_run_id = None
            try:
                close_obd()
            except Exception:
                pass
            self.btn_real.config(text="🔌 BAĞLAN", bg="#00e5ff", fg="black")
            self.log("Bu adaptör adayından gerçek ECU yanıtı alınamadı; bağlantı kapatıldı.")
            if self._candidate_queue:
                self.log("Sıradaki adaptör/COM/J2534 adayı deneniyor...")
                self.root.after(250, self._try_next_candidate)
                return
            self.set_status("ECU YANIT VERMEDİ", "#ff5252")
            self.log("Tüm adaylar başarısız. Kontak, kablo, soket ve doğru adaptör kontrol edilmeli.")
            safe_speak("Bulunan bağlantı arayüzlerinden ECU yanıtı alamadım. Kontak ve kabloları kontrol edelim.")
            self.root.after(500, lambda: messagebox.askyesno(
                "ECU yanıt vermedi",
                "Bulunan bağlantı arayüzleri denendi ancak ECU yanıt vermedi.\n\n"
                "Motosiklet kontağı açık mı ve adaptör motosiklete takılı mı?\n\n"
                "Kontrol ettikten sonra yeniden tarayayım mı?",
                parent=self.root) and self.begin_adapter_search())
            return
        self.root.after(1000, lambda: self._watch_connection(run_id, tries - 1))


# ==================== COK MARKALI MOTOSIKLET BILGI KUTUPHANESI ====================
# Kaynakli ve kapsam sinirli veri: her profil yalnizca belirtilen model/yil icin gecerlidir.
# Forum verileri servis dokumani veya fiziksel dogrulama yerine gecmez.
MOTO_KNOWLEDGE_REGISTRY_VERSION = 2
MOTO_KNOWLEDGE_SOURCES = {
    "yamaha_cp3_racechrono": "https://racechrono.com/forum/d/2853-yamaha-tracer-9-mt-09-2021-2024-can-ids-and-formulas",
    "yamaha_r9_racechrono": "https://racechrono.com/forum/d/2840-yamaha-r9-2026-12-signals-decoded-captures-and-dbc-on-github",
    "yamaha_mt07_forum": "https://www.mt07-forum.de/forum/thread/32532-can-bus-mt-07-daten",
    "yamaha_mt07_abs_forum": "https://www.mt07-forum.de/forum/thread/68226-yamaha-euro-3-can-bus-und-aktivierung-des-abs-moduls",
    "yamaha_mt07_2014_project": "https://github.com/JackHat1/MT07_CAN_Project",
    "ducati_panigale_can": "https://github.com/renatobo/DucatiPanigaleCanBus",
    "yamaha_xt660z_manual": "https://www.manualslib.com/manual/814158/Yamaha-Xt660z-2008.html?page=362",
    "yamaha_fz8_manual": "https://biker.ee/manuals/yamaha/YAMAHA-FZ8-SA-Service-Manual.pdf",
    "kawasaki_z750s_manual": "https://sd8fb8245db0f2e8b.jimcontent.com/download/version/1648048271/module/16278494722/name/Kawasaki%20Z750S%20Service%20Manual.pdf",
    "honda_nc700_manual": "https://ipv4.cyclechaos.com/images/5/58/Honda_NC700_Service_Manual.pdf",
    "css_motorcycle_can": "https://www.csselectronics.com/pages/motorcycle-reverse-engineering-can-bus",
}
MOTO_CAN_PROFILES = [
    {
        "brand": "Yamaha", "models": ["Tracer 9", "MT-09", "XSR900"],
        "years": "2021-2024 (kaynakta 2022 Tracer 9 üzerinde doğrulanmış)",
        "network": {"bus": "CAN", "bitrate": 500000, "id_format": "11-bit"},
        "signals": [
            {"id": "0x209", "name": "Motor devri", "formula": "((B0 << 8) | B1) * 25 / 64", "unit": "rpm"},
            {"id": "0x209", "name": "Vites", "formula": "B4 & 0x07", "unit": "vites kodu"},
            {"id": "0x205", "name": "Ön teker hızı", "formula": "((B0 << 8) | B1) / 101", "unit": "km/h"},
            {"id": "0x205", "name": "Arka teker hızı", "formula": "((B2 << 8) | B3) / 101", "unit": "km/h"},
            {"id": "0x215", "name": "Gaz kelebeği kolu", "formula": "(B2 << 8) | B3", "unit": "ham değer; 0 kapalı, yaklaşık 690 tam açık"},
            {"id": "0x260", "name": "Ön fren basıncı", "formula": "(B0 << 8) | B1", "unit": "ham değer"},
            {"id": "0x268", "name": "ABS/fren durum bitleri", "formula": "bit0 arka ABS, bit1 ön ABS, bit2 arka fren pedalı", "unit": "bit alanı"},
            {"id": "0x254", "name": "Yaw rate", "formula": "ham = (B4 << 8) | B5; offset/ölçek henüz doğrulanmadı", "unit": "ham değer; fiziksel birime çevrilmez"},
        ],
        "source": MOTO_KNOWLEDGE_SOURCES["yamaha_cp3_racechrono"],
        "evidence": "Bir 2022 Tracer 9 üzerinde kullanıcı tarafından GPS/araç verisiyle karşılaştırılmış; diğer yıl ve modellerde otomatik genellenmez.",
        "validation": "community_single_vehicle"
    },
    {
        "brand": "Yamaha", "models": ["R9"],
        "years": "2026",
        "network": {"bus": "CAN", "bitrate": 500000, "id_format": "11-bit", "note": "Üç ayrı CAN çifti rapor edilmiş; teşhis soketi ECU'nun bulunduğu ağa bağlı."},
        "known_ids": ["0x27C", "0x383", "0x512", "0x751", "0x756", "0x758", "0x759", "0x765", "0x76D", "0x76E"],
        "signals": [
            {"id": "0x27C", "name": "Motor devri", "formula": "BE16 * 100 / 256", "unit": "rpm"},
            {"id": "0x756", "name": "Arka teker hızı", "formula": "((Top*4096)+(Hi*256)+(Mid*16)+Lo)/100", "unit": "km/h"},
            {"id": "0x765", "name": "Ön teker hızı", "formula": "((Top*4096)+(Hi*256)+(Mid*16)+Lo)/100", "unit": "km/h"},
            {"id": "0x758", "name": "Gaz kolu / ölçülen kelebek", "formula": "DBC alan birleştirme; ham değer / kaynak ölçeği", "unit": "%"},
            {"id": "0x512", "name": "Su ve hava sıcaklığı", "formula": "byte * 0.625 - 30", "unit": "°C"}
        ],
        "source": MOTO_KNOWLEDGE_SOURCES["yamaha_r9_racechrono"],
        "evidence": "Kimlikler ve ham kayıtlar paylaşılmış; bu kaynakta tüm ID'lerin anlamı çözülmüş değildir.",
        "validation": "community_capture_partial"
    },
    {
        "brand": "Yamaha", "models": ["MT-07", "FZ-07"], "years": "2014-2017 (forum kaynak kapsamı)",
        "network": {"bus": "CAN", "bitrate": 500000, "id_format": "11-bit"},
        "signals": [
            {"id": "0x236", "name": "Gösterge vites bilgisi", "mapping": {"0x00": "N", "0x20": "1", "0x40": "2", "0x60": "3", "0x80": "4", "0xA0": "5", "0xC0": "6"}},
            {"id": "0x22E", "name": "Kokpit tuşları", "mapping": {"0x08": "Sol tuş", "0x04": "Sağ tuş", "0x0C": "İki tuş"}},
            {"id": "0x20A", "name": "RPM aday mesajı", "note": "Byte 2 RPM ile ilişkili görünüyor; kesin formül doğrulanmadı."}
        ],
        "source": MOTO_KNOWLEDGE_SOURCES["yamaha_mt07_forum"],
        "evidence": "Forum kullanıcı bulgusu; vites kodlaması paylaşılmış, RPM yorumu kesin değil.",
        "validation": "community_partial"
    },
    {
        "brand": "Yamaha", "models": ["MT-07 Euro 3 ABS"], "years": "Euro 3 varyantları; kesin model/yıl eşlemesi yapılmadı",
        "network": {"bus": "CAN", "bitrate": 500000, "id_format": "11-bit"},
        "observed_diagnostic_ids": ["0x07DF", "0x07E3", "0x07EB"],
        "observed_services": ["0x10 DiagnosticSessionControl", "0x50 olumlu yanıt"],
        "source": MOTO_KNOWLEDGE_SOURCES["yamaha_mt07_abs_forum"],
        "evidence": "Bosch ABS 9 servis cihazı haberleşmesinin forumdaki pasif trace yorumu. Aktif rutin/aktüatör komutları uygulamaya alınmadı.",
        "validation": "community_trace_unverified"
    },
    {
        "brand": "Ducati", "models": ["Panigale 899", "959", "1199", "1299", "Multistrada 1200", "Diavel"],
        "years": "Kaynakta listelenen varyantlar; test Panigale 899 üzerinde",
        "network": {"bus": "CAN", "id_format": "11-bit", "bitrate": "Kaynakta belirtilmiyor"},
        "signals": [
            {"id": "0x080", "name": "Gaz kelebeği", "formula": "B4 / 2", "unit": "% (kaynak BLE ölçeği)"},
            {"id": "0x080", "name": "Motor devri", "formula": "B5 * 256 + B6", "unit": "rpm"},
            {"id": "0x018", "name": "Arka teker hızı", "formula": "((B4 & 0x1F) << 4) + (B5 >> 4)", "unit": "kaynak ham hız birimi"},
            {"id": "0x100", "name": "Sıcaklıklar / akü", "formula": "B3-40, B5-40, B4/10", "unit": "°C / °C / V"}
        ],
        "source": MOTO_KNOWLEDGE_SOURCES["ducati_panigale_can"],
        "evidence": "MIT açık kaynak; CAN ID ve byte dönüşümleri Panigale 899 üzerinde test edilen yazılımın kaynak kodundan.",
        "validation": "community_single_vehicle"
    },
]
MOTO_IMMOBILIZER_PROFILES = [
    {
        "brand": "Yamaha", "models": ["XT660Z"], "years": "2008 (kaynak doküman kapsamı)",
        "codes": {
            "51": "Anahtar ile immobilizer ünitesi arasında kod iletilemiyor.",
            "52": "Anahtar ve immobilizer ünitesi kodları eşleşmiyor veya tanınmayan transponder algılanıyor.",
            "53": "ECU ile immobilizer ünitesi arasında kod iletilemiyor.",
            "54": "ECU ile immobilizer ünitesi arasındaki kodlar eşleşmiyor.",
        },
        "source": MOTO_KNOWLEDGE_SOURCES["yamaha_xt660z_manual"],
        "note": "Belirti/kod açıklamaları servis dokümanından; çözüm için modele ait resmi prosedür izlenmelidir."
    },
    {
        "brand": "Yamaha", "models": ["FZ8", "FZ8-S"],
        "years": "Kaynak servis kılavuzu kapsamı",
        "codes": {
            "55": "Anahtar kodu kayıt işlemi hatası.",
            "56": "Tanımsız kod alındı; parazit veya bağlantı/kablo sorunu olası.",
        },
        "source": MOTO_KNOWLEDGE_SOURCES["yamaha_fz8_manual"],
        "note": "Bu kayıt anahtar programlama/bypass işlevi sağlamaz; yalnızca arıza açıklamasıdır."
    },
    {
        "brand": "Kawasaki", "models": ["Z750S"],
        "years": "Kaynak servis kılavuzu kapsamı",
        "codes": {"36": "Immobilizer anahtar/transponder tanıma hatası veya kayıtlı olmayan anahtar algılandı."},
        "source": MOTO_KNOWLEDGE_SOURCES["kawasaki_z750s_manual"],
        "note": "Servis dokümanındaki arıza tanımı; anahtar kodu okuma/kopyalama yapılmaz."
    },
    {
        "brand": "Honda", "models": ["NC700", "NC700X"],
        "years": "Kaynak servis kılavuzu kapsamı",
        "codes": {},
        "source": MOTO_KNOWLEDGE_SOURCES["honda_nc700_manual"],
        "note": "HISS için gösterge lambası, yedek anahtar ile karşılaştırma ve ECM/PCM-kombine gösterge haberleşme hattı kontrolleri dokümante edilmiş; evrensel HISS hata kodu varsayılmaz."
    },
]
def _moto_year_matches(requested_year, year_scope):
    """Yıl aralığını doğru değerlendir; belirsiz kapsamı yanlışlıkla reddetme."""
    if not requested_year:
        return True
    try:
        requested = int(requested_year)
    except (TypeError, ValueError):
        return False
    scope = str(year_scope or "")
    ranges = re.findall(r"((?:19|20)\\d{2})\\s*[-–]\\s*((?:19|20)\\d{2})", scope)
    if ranges:
        return any(int(start) <= requested <= int(end) for start, end in ranges)
    exact_years = [int(x) for x in re.findall(r"(?<!\\d)(?:19|20)\\d{2}(?!\\d)", scope)]
    return not exact_years or requested in exact_years


def moto_knowledge_lookup(brand="", model="", year=None):
    """Kaynakli pasif CAN ve immobilizer ariza bilgisini marka/model bazinda listeler."""
    b = str(brand or "").strip().casefold()
    m = str(model or "").strip().casefold()
    y = str(year or "").strip()
    can = []
    immobilizer = []
    for profile in MOTO_CAN_PROFILES:
        if b and profile["brand"].casefold() != b:
            continue
        if m and not any(m in x.casefold() or x.casefold() in m for x in profile["models"]):
            continue
        if not _moto_year_matches(y, profile.get("years")):
            continue
        can.append(profile)
    for profile in MOTO_IMMOBILIZER_PROFILES:
        if b and profile["brand"].casefold() != b:
            continue
        if m and not any(m in x.casefold() or x.casefold() in m for x in profile["models"]):
            continue
        if not _moto_year_matches(y, profile.get("years")):
            continue
        immobilizer.append(profile)
    return {
        "ok": True, "registry_version": MOTO_KNOWLEDGE_REGISTRY_VERSION,
        "brand": brand, "model": model, "year": year,
        "can_profiles": can, "immobilizer_profiles": immobilizer,
        "warning": "Kaynak kapsamı dışındaki model/yıllara veri uygulanmaz. Forum verileri bağımsız doğrulama gerektirir. Bu modül yalnızca bilgi/teşhis içindir; immobilizer bypass, anahtar kopyalama veya ECU'ya yazma yapmaz."
    }

@app.route('/api/knowledge/motorcycle', methods=['GET'])
def api_motorcycle_knowledge():
    return jsonify(moto_knowledge_lookup(
        request.args.get("brand", ""), request.args.get("model", ""),
        request.args.get("year", "")
    ))


# ==================== MAIN ====================
if __name__ == "__main__":
    root = None
    try:
        threading.Thread(target=run_flask, daemon=True).start()
        print("Web Arayuzu: http://localhost:5000")
        root = tk.Tk()
        app_gui = App(root)

        def on_closing():
            app_gui.running = False
            _cancel_runs()
            if VOICE_ENGINE is not None:
                VOICE_ENGINE.stop()
            root.after(200, lambda: (close_obd(), root.destroy()))

        root.protocol("WM_DELETE_WINDOW", on_closing)
        root.mainloop()
    except Exception:
        _details = traceback.format_exc()
        try:
            _log_path = os.path.join(tempfile.gettempdir(), "seyfi_usta_startup_error.txt")
            with open(_log_path, "w", encoding="utf-8") as _log_file:
                _log_file.write(_details)
            if root is None:
                root = tk.Tk()
                root.withdraw()
            messagebox.showerror(
                "Seyfi Usta - Baslatma hatasi",
                "Program baslatilirken hata olustu. Hata ayrintisi su dosyaya kaydedildi:\\n"
                + _log_path + "\\n\\n" + _details[-2500:]
            )
        except Exception:
            print(_details)
