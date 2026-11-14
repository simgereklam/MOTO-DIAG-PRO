"""MOTO DIAG PRO masaüstü için güvenli, araç-özel ABS veri katmanı.

Bu modül kendi başına hiçbir adaptör/ECU haberleşmesi başlatmaz.
Gerçek ABS iletişimi yalnızca OEM dokümanı ve donanım testiyle doğrulanan
araç profilleri eklendikten sonra ayrı bir taşıma katmanında uygulanmalıdır.
"""
import re
from datetime import datetime

# ==================== EVRENSEL ABS PROFİL ALTYAPISI ====================
# Protokol/soket/model yılı evrensel değildir. Yalnızca OEM kanıtlı profiller
# gelecekte gerçek ECU sorgularını etkinleştirebilir.
ABS_PROFILE_SCHEMA_VERSION = 1
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
              "ducati", "triumph", "bajaj", "tvs", "cf moto", "aprilia",
              "benelli", "royal enfield", "harley-davidson", "diğer")
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

def decode_abs_dtc(brand, model, year, code):
    """ABS DTC çözümü motor ECU DTC sözlüğünden tamamen ayrıdır."""
    key = abs_profile_key(brand, model, year)
    entry = ABS_DTC_DATABASE.get(key + (str(code or "").strip().upper(),))
    if not entry or not entry.get("verified"):
        return "Bu araç profili için doğrulanmış ABS arıza kodu açıklaması bulunamadı."
    return entry.get("description_tr") or "Doğrulanmış açıklama mevcut değil."
