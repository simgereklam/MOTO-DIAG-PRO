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
from flask import Flask, render_template_string
from flask_socketio import SocketIO

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
    "11": "Ön Tekerlek Hız Sensörü (ABS)", "12": "Arka Tekerlek Hız Sensörü (ABS)",
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
    uyarilar = []
    if data.get('temp', 0) > 105: uyarilar.append(f"KRİTİK HARARET: Motor {data['temp']}°C! Radyatör fanını ve termostatı kontrol edin.")
    if 0 < data.get('volt', 12) < 11.8: uyarilar.append(f"AKÜ UYARISI: Voltaj {data['volt']}V. Şarj dinamosu veya akü zayıf.")
    if data.get('stft', 0) > 25: uyarilar.append(f"YAKIT TRİMİ: STFT %{data['stft']}. Aşırı zayıf karışım, motor ısınabilir.")
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

# ==================== FLASK & SOCKETIO ====================
app = Flask(__name__); app.config['SECRET_KEY'] = 'efe_moto_r25'
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')
_connection = None; _mode = 'idle'; _conn_lock = threading.Lock(); _query_lock = threading.Lock()
_live_data_lock = threading.Lock(); _last_live_data = {}
_run_id = 0; _run_lock = threading.Lock(); _last_alert_time = 0

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
            return ''.join(chunks).replace('>', '').strip()

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
    """python-OBD/pyserial socket URL normalizasyonu."""
    t = (target or "").strip()
    if t.startswith("socket://"):
        return t if t.rsplit(":", 1)[-1].isdigit() else t + ":35000"
    if ":" not in t:
        t = t + ":35000"
    return "socket://" + t


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
    if raw_target.startswith('socket://') or (raw_target.count('.') == 3 and ':' in raw_target):
        hostport = raw_target.replace('socket://', '', 1)
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
    if isinstance(conn, ELM327TcpConnection):
        # Temel göstergeler her turda; daha yavaş değişen yardımcı PID'ler sırayla okunur.
        # Böylece tek çevrimde dokuz ardışık sorgu gönderilmez.
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
            with _query_lock:
                response = conn.query(command)
            value = _obd_value(response)
            if value is not None:
                result[key] = value
        except Exception:
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
                    socketio.emit('telemetry', data)
                    socketio.emit('risk_score', {'score': risk_skoru_hesapla(data)})
                    now = time.time()
                    if now - _last_alert_time > 10:
                        alerts = anomali_kontrol(data)
                        if alerts:
                            _last_alert_time = now
                            socketio.emit('alert', {'msg': alerts[0]})
                            # Sesli yorum yalnızca gerçek ECU verisinden üretilen uyarılarda çalışır.
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
  <div class="module-head"><h2 style="color:#ff626f">🛑 ABS KONTROL ÜNİTESİ</h2><span class="module-status">PROFİL BEKLİYOR · KOMUT GÖNDERİLMİYOR</span></div>
  <p style="color:#aab5c2">ABS, motor ECU'sundan bağımsız bir kontrol modülü olarak yönetilir. Bu sayfa kendi bağlantı, arıza tarama ve canlı veri alanlarına sahiptir.</p>
  <div class="module-grid">
   <div class="module-card"><div class="k">Modül bağlantısı</div><div class="v" id="abs_conn_state">Bağlı değil</div><div class="hint">Motor ECU bağlantısından ayrı durum göstergesi</div></div>
   <div class="module-card"><div class="k">ABS ECU kimliği</div><div class="v" id="abs_ecu_id">Henüz okunmadı</div><div class="hint">Üretici / parça no / yazılım kimliği</div></div>
   <div class="module-card"><div class="k">İletişim profili</div><div class="v" id="abs_protocol">Doğrulanmadı</div><div class="hint">CAN / K-Line / üreticiye özel servis protokolü</div></div>
  </div>
  <div class="test-block"><div class="title">ARAÇ VE MODÜL PROFİLİ</div>
   <div class="module-grid" style="grid-template-columns:repeat(3,minmax(0,1fr))">
    <div><label class="title">Marka</label><select class="module-field" id="abs_brand"><option>Yamaha</option><option>Honda</option><option>Kawasaki</option><option>Suzuki</option><option>BMW Motorrad</option><option>KTM</option><option>Diğer</option></select></div>
    <div><label class="title">Model</label><input class="module-field" id="abs_model" placeholder="Örn. MT-09 / NC750X"></div>
    <div><label class="title">Model yılı</label><input class="module-field" id="abs_year" type="number" min="1980" max="2035" placeholder="2020"></div>
   </div>
   <div class="module-actions"><button class="btn test-btn" disabled title="ECU protokolü doğrulanınca etkinleşecek">🔌 ABS BAĞLAN</button><button class="btn test-btn" disabled>🔍 ABS ARIZA TARA</button><button class="btn test-btn" disabled>🗑 ABS ARIZALARINI SİL</button></div>
  </div>
  <div class="module-note"><b>Güvenlik kilidi:</b> Bu aşamada ABS'ye rastgele CAN/K-Line komutu gönderilmiyor. ECU adresi, servis oturumu ve araç profili doğrulanmadan arıza silme veya pompa/valf çalıştırma açılmayacak.</div>
  <h3 style="color:#ff8c96">ABS canlı veri ekranı</h3>
  <div class="module-list">
   <div class="module-item"><b>Ön tekerlek hızı</b><span id="abs_front_speed">Veri bekleniyor · km/h</span></div>
   <div class="module-item"><b>Arka tekerlek hızı</b><span id="abs_rear_speed">Veri bekleniyor · km/h</span></div>
   <div class="module-item"><b>ABS pompa / valf durumu</b><span>ECU destekliyorsa okunacak; şu an sorgu yok</span></div>
   <div class="module-item"><b>Fren müşürü / basınç</b><span>Desteklenen sensör ve ECU profiline göre</span></div>
  </div>
  <h3 style="color:#ff8c96;margin-top:22px">ABS TEST VE TEŞHİS FONKSİYONLARI</h3>
  <div class="module-list">
   <div class="module-item"><b>Arıza geçmişini oku</b><span>Yalnızca seçili ABS ECU'sundan doğrulanmış yanıt alınırsa.</span></div>
   <div class="module-item"><b>Arıza kodlarını sil ve doğrula</b><span>Silme sonrası ABS ECU yeniden okunmadan işlem başarılı sayılmaz.</span></div>
   <div class="module-item"><b>Pompa ve valf aktif testi</b><span>Yalnızca model özelinde doğrulanmış prosedür ve güvenli servis koşullarında.</span></div>
   <div class="module-item"><b>Ön / arka sensör karşılaştırması</b><span>Gerçek tekerlek hızı verileri aynı zaman çizelgesinde gösterilecek.</span></div>
   <div class="module-item"><b>Arıza geçmişi ve rapor</b><span>Okunan DTC, durum bilgisi ve zaman damgası saklanacak.</span></div>
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
    <div><label class="title">Model</label><select class="module-field" id="dct_model"><option value="">Model seçin</option><option>NC750S DCT</option><option>NC750X DCT</option><option>Integra DCT</option><option>X-ADV</option><option>Forza 750</option><option>Diğer Honda DCT</option></select></div>
    <div><label class="title">Model yılı</label><input class="module-field" id="dct_year" type="number" min="2000" max="2035" placeholder="2020"></div>
    <div><label class="title">Şanzıman / ECU bilgisi</label><input class="module-field" id="dct_ecu_type" placeholder="Bilinmiyorsa boş bırakın"></div>
   </div>
   <div class="module-actions"><button class="btn test-btn" disabled title="DCT ECU profili doğrulanınca etkinleşecek">🔌 DCT BAĞLAN</button><button class="btn test-btn" disabled>🔍 DCT ARIZA TARA</button><button class="btn test-btn" disabled>🗑 DCT ARIZALARINI SİL</button></div>
  </div>
  <div class="module-note"><b>Güvenlik kilidi:</b> DCT kavrama, vites seçici ve adaptasyon komutları model/yıl/TCU kimliğine bağlıdır. Doğrulanmamış reset veya kalibrasyon komutu gönderilmeyecek.</div>
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
<div id="tab-dyno" class="container"><div class="panel"><h3 style="color:#00ff00">📈 DYNO / PERFORMANS KAYDI</h3><p>Bu bölüm ECU'ya evrensel bir "dyno modu" komutu göndermeyecek. Önce gerçek RPM, hız, gaz konumu ve mümkünse harici dinamometre yük/tork verisi kaydedilecek.</p><div class="test-block"><div class="title">DYNO DURUMU</div><pre style="color:#ffaa00">VERİ KAYDI ALTYAPISI BEKLİYOR — şu an dinamometre cihazı bağlantısı yok.</pre></div><div class="test-block"><div class="title">PLANLANAN ÖLÇÜMLER</div><pre>• RPM / hız / TPS zaman serisi
• Harici dinamometre varsa tekerlek gücü ve tork
• Aynı koşullarda önce/sonra karşılaştırması
• ECU'dan okunan veri ile harici ölçümü ayrı etiketleme</pre></div></div></div>
<div id="tab-gk" class="container"><div class="panel"><h3 style="color:#ffaa00;margin-top:0">🔧 GELİŞMİŞ SERVİS TESTLERİ</h3><div class="test-block"><div class="title">BAĞLANTI (Araç Seçimine Göre)</div><button class="btn test-btn" onclick="gkConnect()">🔌 GELİŞMİŞ BAĞLANTI KUR</button><pre id="gk_conn_out" style="color:#00e5ff;min-height:30px">-</pre></div><div class="test-block"><div class="title">AKTÜATÖR TESTLERİ</div><pre id="gk_test_out" style="color:#ffaa00;min-height:30px">Güvenlik nedeniyle kapalı: model/ECU özel komut profili doğrulanınca etkinleştirilecek.</pre></div><div class="test-block"><div class="title">ABS MODÜLÜ</div><pre id="gk_d07_out" style="color:#ffaa00;min-height:30px">Ayrı ABS bağlantı katmanı hazırlanacak. Genel D07 komutu kullanılmayacak.</pre></div></div></div>
<script>
var socket = io();
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
socket.on('gkbus_result',d=>{var el=document.getElementById('gk_conn_out');if(el)el.innerText=(d.ok?'✅ ':'❌ ')+d.msg});socket.on('gkbus_test_result',d=>{var el=document.getElementById('gk_test_out');if(el)el.innerText=(d.ok?'✅ ':'❌ ')+d.msg});socket.on('gkbus_d07_result',d=>{var el=document.getElementById('gk_d07_out');if(el)el.innerText=d.ok?('Pulse: '+d.data):('❌ '+d.msg)});
function aracSec(){var data={marka:document.getElementById('sel_marka').value,model:document.getElementById('sel_model').value,yil:parseInt(document.getElementById('sel_yil').value),konnektor:document.getElementById('sel_konnektor').value,manuel_model:document.getElementById('sel_model_manual').value};document.getElementById('arac_sonuc').style.display='block';document.getElementById('arac_sonuc_text').innerText='Yapılandırma hesaplanıyor...';socket.emit('arac_sec',data)}
socket.on('arac_sec_result',function(d){var sonucText=document.getElementById('arac_sonuc_text');if(d.ok){sonucText.innerText=d.msg;sonucText.style.color='#00ff00'}else{sonucText.innerText='HATA: '+d.msg;sonucText.style.color='#ff5252'}});
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

@socketio.on('scan_dtc')
def handle_scan():
    with _conn_lock:
        conn = _connection
        mode = _mode
    if mode != 'real' or conn is None or not conn.is_connected():
        socketio.emit('dtc_result', {'codes': [], 'ok': False, 'error': 'Gerçek ECU bağlantısı yok. Önce adaptöre bağlanın.'})
        return
    try:
        if isinstance(conn, ELM327TcpConnection):
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
        if isinstance(conn, ELM327TcpConnection):
            before, before_raw = conn.read_dtcs()
            if before is None:
                socketio.emit('dtc_cleared', {'ok': False, 'msg': 'Silme öncesi ECU arıza hafızası okunamadı. Silme yapılmadı.'})
                return
            before_codes = [str(c) for c, _ in before]
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
        else:
            write_log("DTC SİLME SONRASI KOD KALDI", f"Önce: {before_codes}\nSonra: {after_codes}")
            socketio.emit('dtc_cleared', {'ok': False, 'msg': 'ECU yeniden okundu; bazı kodlar hâlâ mevcut: ' + ', '.join(after_codes)})
    except Exception as exc:
        socketio.emit('dtc_cleared', {'ok': False, 'msg': f"Silme işlemi başarısız: {exc}"})


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
    if isinstance(conn, ELM327TcpConnection):
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

def speak(phrase):
    engine = VOICE_ENGINE
    if engine is not None:
        engine.speak(phrase)

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
            phrase = "Merhaba, Seyfi Usta akıllı motosiklet teşhis sistemine hoş geldiniz. Bağlantı cihazınızı arıyorum."
        safe_speak(phrase)

    def startup_assistant(self):
        if self._startup_done: return
        self._startup_done = True
        self._ai_ready = True
        self.set_status("ÇEVRİMDIŞI TEŞHİS HAZIR")
        self.log("Yerel teşhis kuralları hazır; internet veya harici AI servisi kullanılmıyor.")
        try:
            global VOICE_ENGINE
            from seyfi_voice import SeyfiVoice
            VOICE_ENGINE = SeyfiVoice(
                on_command=lambda phrase: self.root.after(0, lambda p=phrase: self.handle_voice_command(p)),
                on_status=lambda message: self.root.after(0, lambda m=message: self.log(m)),
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
        self.btn_real.config(text="🔍 ARANIYOR...", state="disabled")
        self.set_status("BAĞLANTI CİHAZI ARANIYOR")
        self.log("Wi-Fi ELM327 ve USB/COM adaptörleri taranıyor...")
        safe_speak("Bağlantı cihazı aranıyor.")
        threading.Thread(target=self._search_and_connect, daemon=True).start()

    def _search_and_connect(self):
        candidates = discover_adapter_candidates()
        self.root.after(0, lambda c=candidates: self._search_done(c))

    def _search_done(self, candidates):
        self._searching = False
        self.btn_real.config(state="normal")
        self._candidate_queue = candidates or []
        if not candidates:
            self.addr_var.set("otomatik")
            self.set_status("BAĞLANTI BULUNAMADI", "#ff5252")
            self.log("BAĞLANTI BULUNAMADI. Wi-Fi ELM327 veya USB/COM adaptörü tespit edilemedi.")
            safe_speak("Bağlantı cihazı bulunamadı. Lütfen bağlantı cihazınızı bilgisayara ve motosiklete bağlayın. Bağladınız mı?")
            self.root.after(1200, self.ask_device_connected)
            return
        kind, target, desc = candidates[0]
        self.addr_var.set(target)
        self.log(f"ADAPTÖR TESPİT EDİLDİ: {desc} ({target}). Şimdi ECU yanıtı doğrulanacak.")
        self.set_status("ADAPTÖR BULUNDU · ECU BAĞLANTISI SINANIYOR")
        safe_speak("Bağlantı cihazı bulundu. Şimdi motosiklet kontrol ünitesiyle haberleşmeyi deniyorum.")
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
            _cancel_runs(); self._active_run_id = None
            self.btn_real.config(text="🔌 BAĞLAN", bg="#00e5ff", fg="black")
            self.set_status("ECU YANIT VERMEDİ", "#ff5252")
            self.log("Adaptör/port görüldü ancak ECU yanıtı doğrulanamadı. Kontak, kablo, soket ve doğru adaptör kontrol edilmeli.")
            safe_speak("Adaptör bulundu ancak motosiklet kontrol ünitesi yanıt vermedi. Kontak açık mı ve bağlantı kablosu takılı mı? Kontrol edip tekrar deneyelim.")
            self.root.after(500, lambda: messagebox.askyesno("ECU yanıt vermedi", "Adaptör bulundu fakat ECU yanıt vermedi.\n\nMotosiklet kontağı açık mı ve adaptör motosiklete takılı mı?\n\nKontrol ettiniz mi?", parent=self.root) and self.begin_adapter_search())
            return
        self.root.after(1000, lambda: self._watch_connection(run_id, tries - 1))

# ==================== MAIN ====================
if __name__ == "__main__":
    threading.Thread(target=run_flask, daemon=True).start()
    print("Web Arayüzü: http://localhost:5000")
    root = tk.Tk(); app_gui = App(root)
    def on_closing():
        app_gui.running = False
        _cancel_runs()
        if VOICE_ENGINE is not None:
            VOICE_ENGINE.stop()
        root.after(200, lambda: (close_obd(), root.destroy()))
    root.protocol("WM_DELETE_WINDOW", on_closing); root.mainloop()