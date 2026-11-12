package com.efemoto.motodiagpro

/**
 * EFE MOTO DTC lookup.
 * SAE/J1979 P-codes are generic powertrain descriptions.
 * Two-digit Yamaha-style codes are a separate manufacturer-code namespace;
 * exact meaning must be confirmed against the model/year ECU service manual.
 */
object DtcDatabase {
    private val generic = mapOf(
        "P0001" to "Yakıt hacim regülatörü kontrol devresi / açık devre",
        "P0002" to "Yakıt hacim regülatörü kontrol aralığı / performansı",
        "P0010" to "Emme eksantrik mili zamanlama aktüatörü devresi",
        "P0011" to "Emme eksantrik mili zamanlaması fazla ileride",
        "P0012" to "Emme eksantrik mili zamanlaması fazla geride",
        "P0014" to "Egzoz eksantrik mili zamanlaması fazla ileride",
        "P0016" to "Krank ve eksantrik mili konum korelasyon hatası",
        "P0030" to "Oksijen sensörü ısıtıcı devresi",
        "P0031" to "Oksijen sensörü ısıtıcı devresi düşük sinyal",
        "P0032" to "Oksijen sensörü ısıtıcı devresi yüksek sinyal",
        "P0100" to "Hava kütle akış sensörü (MAF) devresi",
        "P0101" to "MAF sensörü aralık / performans hatası",
        "P0102" to "MAF sensörü düşük giriş sinyali",
        "P0103" to "MAF sensörü yüksek giriş sinyali",
        "P0105" to "Manifold mutlak basınç (MAP) sensörü devresi",
        "P0107" to "MAP sensörü düşük giriş sinyali",
        "P0108" to "MAP sensörü yüksek giriş sinyali",
        "P0110" to "Emme havası sıcaklık sensörü devresi",
        "P0111" to "Emme havası sıcaklık sensörü aralık / performans hatası",
        "P0112" to "Emme havası sıcaklık sensörü düşük sinyal",
        "P0113" to "Emme havası sıcaklık sensörü yüksek sinyal",
        "P0115" to "Motor soğutma suyu sıcaklık sensörü devresi",
        "P0117" to "Motor soğutma suyu sıcaklık sensörü düşük sinyal",
        "P0118" to "Motor soğutma suyu sıcaklık sensörü yüksek sinyal",
        "P0120" to "Gaz kelebeği konum sensörü A devresi",
        "P0121" to "Gaz kelebeği konum sensörü A aralık / performans hatası",
        "P0122" to "Gaz kelebeği konum sensörü A düşük sinyal",
        "P0123" to "Gaz kelebeği konum sensörü A yüksek sinyal",
        "P0128" to "Motor soğutma sıcaklığı termostat düzenleme aralığının altında",
        "P0130" to "Oksijen sensörü devresi (Bank 1, Sensör 1)",
        "P0131" to "Oksijen sensörü düşük sinyal",
        "P0132" to "Oksijen sensörü yüksek sinyal",
        "P0133" to "Oksijen sensörü yavaş tepki",
        "P0134" to "Oksijen sensörü etkinliği / sinyali algılanmadı",
        "P0135" to "Oksijen sensörü ısıtıcı devresi arızası",
        "P0136" to "Oksijen sensörü devresi (Bank 1, Sensör 2)",
        "P0141" to "Oksijen sensörü ısıtıcı devresi (Bank 1, Sensör 2)",
        "P0171" to "Yakıt sistemi çok fakir (Bank 1)",
        "P0172" to "Yakıt sistemi çok zengin (Bank 1)",
        "P0174" to "Yakıt sistemi çok fakir (Bank 2)",
        "P0175" to "Yakıt sistemi çok zengin (Bank 2)",
        "P0183" to "Yakıt sıcaklık sensörü yüksek giriş sinyali",
        "P0190" to "Yakıt rayı / yakıt basınç sensörü devresi",
        "P0201" to "Enjektör 1 devresi arızası",
        "P0202" to "Enjektör 2 devresi arızası",
        "P0217" to "Motor aşırı sıcaklık durumu",
        "P0220" to "Gaz kelebeği / gaz pedalı konum sensörü B devresi",
        "P0222" to "Gaz kelebeği / gaz pedalı sensörü B düşük sinyal",
        "P0223" to "Gaz kelebeği / gaz pedalı sensörü B yüksek sinyal",
        "P0230" to "Yakıt pompası birincil devresi",
        "P0231" to "Yakıt pompası ikincil devresi düşük sinyal",
        "P0261" to "Enjektör 1 kontrol devresi düşük sinyal",
        "P0262" to "Enjektör 1 kontrol devresi yüksek sinyal",
        "P0299" to "Turbo / süperşarj basıncı beklenen seviyenin altında",
        "P0300" to "Rastgele / birden fazla silindirde ateşleme hatası",
        "P0301" to "1. silindirde ateşleme hatası",
        "P0302" to "2. silindirde ateşleme hatası",
        "P0303" to "3. silindirde ateşleme hatası",
        "P0304" to "4. silindirde ateşleme hatası",
        "P0325" to "Vuruntu sensörü 1 devresi",
        "P0327" to "Vuruntu sensörü düşük giriş sinyali",
        "P0335" to "Krank mili konum sensörü A devresi",
        "P0336" to "Krank mili konum sensörü A aralık / performans hatası",
        "P0340" to "Eksantrik mili konum sensörü A devresi",
        "P0341" to "Eksantrik mili konum sensörü A aralık / performans hatası",
        "P0351" to "Ateşleme bobini A birincil / ikincil devresi",
        "P0352" to "Ateşleme bobini B birincil / ikincil devresi",
        "P0401" to "Egzoz gazı geri dönüş (EGR) akışı yetersiz",
        "P0410" to "İkincil hava enjeksiyon sistemi arızası",
        "P0420" to "Katalizör verimliliği eşik altında (Bank 1)",
        "P0442" to "Yakıt buharı emisyon sistemi küçük kaçak",
        "P0443" to "Yakıt buharı tahliye valfi kontrol devresi",
        "P0455" to "Yakıt buharı emisyon sistemi büyük kaçak",
        "P0463" to "Yakıt seviye sensörü yüksek giriş sinyali",
        "P0500" to "Araç hız sensörü devresi",
        "P0501" to "Araç hız sensörü aralık / performans hatası",
        "P0505" to "Rölanti hava kontrol sistemi arızası",
        "P0506" to "Rölanti devri beklenenden düşük",
        "P0507" to "Rölanti devri beklenenden yüksek",
        "P0511" to "Rölanti hava kontrol (IAC) devresi",
        "P0520" to "Motor yağ basıncı sensörü / anahtarı devresi",
        "P0560" to "Sistem gerilimi arızası",
        "P0562" to "Sistem gerilimi düşük",
        "P0563" to "Sistem gerilimi yüksek",
        "P0600" to "Seri iletişim bağlantısı arızası",
        "P0601" to "ECU dahili bellek kontrol toplamı hatası",
        "P0606" to "ECU / PCM işlemci performans arızası",
        "P0615" to "Marş rölesi kontrol devresi",
        "P0620" to "Şarj jeneratörü kontrol devresi",
        "P0650" to "Arıza gösterge lambası (MIL) kontrol devresi",
        "P0700" to "Şanzıman kontrol sistemi arızası bildirimi",
        "P0705" to "Vites konum sensörü / aralık anahtarı devresi",
        "P0715" to "Şanzıman giriş / türbin hız sensörü devresi",
        "P0720" to "Şanzıman çıkış hız sensörü devresi",
        "P1000" to "OBD sistem izleme testleri henüz tamamlanmadı",
        "P1101" to "Hava akış sistemi aralık / performans hatası",
        "P1120" to "Gaz kelebeği konum sensörü devresi",
        "P1121" to "Gaz kelebeği konum sensörü aralık / performans hatası",
        "P1604" to "Üreticiye özel motor çalıştırma / kontrol sistemi kodu; kesin anlamı marka-model-yıla göre doğrulanmalıdır",
        "P2100" to "Gaz kelebeği aktüatör motoru A devresi / açık devre",
        "P2101" to "Gaz kelebeği aktüatör kontrol aralık / performans hatası",
        "P2135" to "Gaz kelebeği / pedal konum sensörleri A-B korelasyon hatası",
        "U0100" to "Motor kontrol modülü ile iletişim kaybı",
        "U0121" to "ABS kontrol modülü ile iletişim kaybı",
        "U0155" to "Gösterge paneli kontrol modülü ile iletişim kaybı",
        "P0013" to "Egzoz eksantrik mili zamanlama aktüatörü devresi",
        "P0014" to "Egzoz eksantrik mili zamanlaması fazla ileride",
        "P0015" to "Egzoz eksantrik mili zamanlaması fazla geride",
        "P0017" to "Krank ve egzoz eksantrik mili konum korelasyon hatası",
        "P0020" to "Emme eksantrik mili zamanlama aktüatörü B devresi",
        "P0021" to "Emme eksantrik mili zamanlaması B aralık / performans hatası",
        "P0022" to "Emme eksantrik mili zamanlaması B fazla geride",
        "P0036" to "Oksijen sensörü ısıtıcı kontrol devresi (Bank 1 Sensör 2)",
        "P0037" to "Oksijen sensörü ısıtıcı kontrol devresi düşük sinyal",
        "P0038" to "Oksijen sensörü ısıtıcı kontrol devresi yüksek sinyal",
        "P0068" to "Gaz kelebeği ve hava akış / MAP korelasyon hatası",
        "P0087" to "Yakıt rayı / sistem basıncı düşük",
        "P0088" to "Yakıt rayı / sistem basıncı yüksek",
        "P0090" to "Yakıt basınç regülatörü kontrol devresi",
        "P0093" to "Yakıt sisteminde büyük kaçak algılandı",
        "P0106" to "MAP sensörü aralık / performans hatası",
        "P0116" to "Soğutma suyu sıcaklık sensörü aralık / performans hatası",
        "P0125" to "Kapalı çevrim yakıt kontrolü için soğutma sıcaklığı yetersiz",
        "P0126" to "Kararlı çalışma için soğutma sıcaklığı yetersiz",
        "P0137" to "Oksijen sensörü düşük sinyal (Bank 1 Sensör 2)",
        "P0138" to "Oksijen sensörü yüksek sinyal (Bank 1 Sensör 2)",
        "P0139" to "Oksijen sensörü yavaş tepki (Bank 1 Sensör 2)",
        "P0140" to "Oksijen sensörü etkinliği / sinyali algılanmadı (Bank 1 Sensör 2)",
        "P0142" to "Oksijen sensörü devresi (Bank 1 Sensör 3)",
        "P0150" to "Oksijen sensörü devresi (Bank 2 Sensör 1)",
        "P0151" to "Oksijen sensörü düşük sinyal (Bank 2 Sensör 1)",
        "P0152" to "Oksijen sensörü yüksek sinyal (Bank 2 Sensör 1)",
        "P0160" to "Oksijen sensörü etkinliği / sinyali algılanmadı (Bank 2 Sensör 2)",
        "P0161" to "Oksijen sensörü ısıtıcı devresi (Bank 2 Sensör 2)",
        "P0167" to "Oksijen sensörü ısıtıcı devresi arızası",
        "P0170" to "Yakıt düzeltme sistemi arızası (Bank 1)",
        "P0180" to "Yakıt sıcaklık sensörü devresi",
        "P0181" to "Yakıt sıcaklık sensörü aralık / performans hatası",
        "P0191" to "Yakıt rayı basınç sensörü aralık / performans hatası",
        "P0192" to "Yakıt rayı basınç sensörü düşük sinyal",
        "P0193" to "Yakıt rayı basınç sensörü yüksek sinyal",
        "P0203" to "Enjektör 3 devresi arızası",
        "P0204" to "Enjektör 4 devresi arızası",
        "P0210" to "Enjektör 10 devresi arızası",
        "P0215" to "Motor durdurma solenoidi devresi",
        "P0221" to "Gaz kelebeği / pedal konum sensörü B aralık / performans hatası",
        "P0224" to "Gaz kelebeği / pedal konum sensörü B kesintili sinyal",
        "P0232" to "Yakıt pompası ikincil devresi yüksek sinyal",
        "P0234" to "Aşırı turbo basıncı",
        "P0243" to "Turbo basınç kontrol solenoidi A devresi",
        "P0263" to "Silindir 1 katkı / dengeleme hatası",
        "P0264" to "Enjektör 2 kontrol devresi düşük sinyal",
        "P0265" to "Enjektör 2 kontrol devresi yüksek sinyal",
        "P0270" to "Enjektör 4 kontrol devresi düşük sinyal",
        "P0271" to "Enjektör 4 kontrol devresi yüksek sinyal",
        "P0290" to "Silindir 10 katkı / dengeleme hatası",
        "P0305" to "5. silindirde ateşleme hatası",
        "P0306" to "6. silindirde ateşleme hatası",
        "P0313" to "Düşük yakıt seviyesinde ateşleme hatası",
        "P0315" to "Krank mili konum sistemi değişimi öğrenilmemiş",
        "P0320" to "Ateşleme / motor devri giriş devresi",
        "P0321" to "Motor devri / ateşleme referansı aralık-performans hatası",
        "P0322" to "Motor devri / ateşleme referansı sinyali yok",
        "P0328" to "Vuruntu sensörü yüksek giriş sinyali",
        "P0330" to "Vuruntu sensörü 2 devresi",
        "P0331" to "Vuruntu sensörü 2 aralık / performans hatası",
        "P0332" to "Vuruntu sensörü 2 düşük giriş sinyali",
        "P0333" to "Vuruntu sensörü 2 yüksek giriş sinyali",
        "P0342" to "Eksantrik mili konum sensörü A düşük sinyal",
        "P0343" to "Eksantrik mili konum sensörü A yüksek sinyal",
        "P0345" to "Eksantrik mili konum sensörü B devresi",
        "P0353" to "Ateşleme bobini C birincil / ikincil devresi",
        "P0354" to "Ateşleme bobini D birincil / ikincil devresi",
        "P0365" to "Eksantrik mili konum sensörü B devresi",
        "P0380" to "Kızdırma / ısıtma devresi arızası",
        "P0403" to "EGR kontrol devresi arızası",
        "P0411" to "İkincil hava enjeksiyon sistemi akışı hatalı",
        "P0412" to "İkincil hava enjeksiyon valfi A devresi",
        "P0421" to "Katalizör ısınma verimliliği eşik altında",
        "P0440" to "Yakıt buharı emisyon kontrol sistemi arızası",
        "P0441" to "Yakıt buharı tahliye akışı hatalı",
        "P0444" to "Yakıt buharı tahliye valfi açık devre",
        "P0445" to "Yakıt buharı tahliye valfi kısa devre",
        "P0450" to "Yakıt buharı basınç sensörü devresi",
        "P0451" to "Yakıt buharı basınç sensörü aralık / performans hatası",
        "P0452" to "Yakıt buharı basınç sensörü düşük sinyal",
        "P0453" to "Yakıt buharı basınç sensörü yüksek sinyal",
        "P0456" to "Yakıt buharı emisyon sisteminde çok küçük kaçak",
        "P0460" to "Yakıt seviye sensörü devresi",
        "P0461" to "Yakıt seviye sensörü aralık / performans hatası",
        "P0462" to "Yakıt seviye sensörü düşük giriş sinyali",
        "P0464" to "Yakıt seviye sensörü kesintili sinyal",
        "P0470" to "Egzoz basınç sensörü devresi",
        "P0480" to "Soğutma fanı 1 kontrol devresi",
        "P0481" to "Soğutma fanı 2 kontrol devresi",
        "P0496" to "Yakıt buharı tahliye akışı beklenenden yüksek",
        "P0508" to "Rölanti hava kontrol sistemi düşük devre",
        "P0509" to "Rölanti hava kontrol sistemi yüksek devre",
        "P0513" to "Hatalı immobilizer anahtarı",
        "P0521" to "Motor yağ basıncı sensörü aralık / performans hatası",
        "P0522" to "Motor yağ basıncı sensörü düşük sinyal",
        "P0523" to "Motor yağ basıncı sensörü yüksek sinyal",
        "P0530" to "Klima soğutucu basınç sensörü devresi",
        "P0561" to "Sistem gerilimi kararsız",
        "P0571" to "Fren anahtarı A devresi",
        "P0602" to "Kontrol modülü programlama hatası",
        "P0603" to "ECU kalıcı bellek (KAM) hatası",
        "P0604" to "ECU RAM bellek hatası",
        "P0605" to "ECU ROM bellek hatası",
        "P0610" to "Kontrol modülü araç seçenekleri hatası",
        "P0616" to "Marş rölesi kontrol devresi düşük sinyal",
        "P0617" to "Marş rölesi kontrol devresi yüksek sinyal",
        "P0621" to "Jeneratör lamba / L terminali kontrol devresi",
        "P0622" to "Jeneratör alan / F terminali kontrol devresi",
        "P0630" to "ECU içinde VIN programlanmamış veya uyumsuz",
        "P0641" to "Sensör referans voltajı A devresi açık",
        "P0642" to "Sensör referans voltajı A düşük",
        "P0643" to "Sensör referans voltajı A yüksek",
        "P0651" to "Sensör referans voltajı B devresi açık",
        "P0685" to "ECU güç rölesi kontrol devresi",
        "P0706" to "Vites konum sensörü aralık / performans hatası",
        "P0717" to "Şanzıman giriş hız sensörü sinyali yok",
        "P0722" to "Şanzıman çıkış hız sensörü sinyali yok",
        "P0730" to "Yanlış vites oranı",
        "P0740" to "Tork konvertör kavrama devresi",
        "P0850" to "Park / boş vites anahtarı giriş devresi",
        "P0900" to "Debriyaj aktüatörü devresi açık",
        "P1001" to "Üreticiye özgü motor / kontrol sistemi kodu",
        "P1100" to "Üreticiye özgü hava akış / emme sistemi kodu",
        "P1122" to "Üreticiye özgü gaz kelebeği konum kodu",
        "P1500" to "Üreticiye özgü şarj / jeneratör kontrol kodu",
        "P1600" to "Üreticiye özgü ECU / kontrol modülü kodu",
        "P2102" to "Gaz kelebeği aktüatör motoru A düşük sinyal",
        "P2103" to "Gaz kelebeği aktüatör motoru A yüksek sinyal",
        "P2111" to "Elektronik gaz kelebeği açık konumda takılı",
        "P2112" to "Elektronik gaz kelebeği kapalı konumda takılı",
        "P2118" to "Gaz kelebeği aktüatör akım aralık / performans hatası",
        "P2120" to "Gaz pedalı / gaz kelebeği konum sensörü D devresi",
        "P2122" to "Gaz pedalı konum sensörü D düşük sinyal",
        "P2123" to "Gaz pedalı konum sensörü D yüksek sinyal",
        "P2138" to "Gaz pedalı konum sensörleri D-E korelasyon hatası",
        "P2176" to "Elektronik gaz kelebeği rölanti konumu öğrenilmemiş",
        "P2185" to "Motor soğutma sıcaklığı sensörü 2 yüksek giriş",
        "P2195" to "Oksijen sensörü sinyali fakir durumda takılı",
        "P2196" to "Oksijen sensörü sinyali zengin durumda takılı",
        "P2227" to "Barometrik basınç sensörü aralık / performans hatası",
        "P2237" to "Oksijen sensörü pozitif akım kontrol devresi açık",
        "P2263" to "Turbo / süperşarj basınç sistemi performans hatası",
        "P2300" to "Ateşleme bobini A birincil kontrol devresi düşük",
        "P2301" to "Ateşleme bobini A birincil kontrol devresi yüksek",
        "P2503" to "Şarj sistemi düşük voltaj",
        "P2530" to "Kontak anahtarı çalışma konumu devresi",
        "P2563" to "Turbo basınç kontrol konum sensörü aralık / performans",
        "P2600" to "Soğutma sıvısı pompası kontrol devresi açık",
        "P2610" to "ECU motor kapatma zamanlayıcısı performans hatası",
        "P2614" to "Eksantrik mili konum çıkış sinyali devresi açık",
        "P2617" to "Krank mili konum çıkış sinyali devresi açık",
        "P2626" to "Oksijen sensörü pompa akımı trim devresi açık",
        "P2681" to "Motor soğutma sıvısı yönlendirme valfi A devresi açık",
        "P2A00" to "Oksijen sensörü aralık / performans hatası (Bank 1 Sensör 1)",
        "P2A01" to "Oksijen sensörü aralık / performans hatası (Bank 1 Sensör 2)",
        "P3400" to "Silindir devre dışı bırakma sistemi performans hatası",
        "P3425" to "Silindir 4 egzoz valfi kontrol devresi",
        "P3441" to "Silindir 6 emme valfi kontrol devresi",
        "B0001" to "Gövde güvenlik sistemi genel tetikleme kontrolü",
        "B1000" to "Gövde kontrol modülü üreticiye özel arıza",
        "C0035" to "Sol ön tekerlek hız sensörü devresi",
        "C0040" to "Sağ ön tekerlek hız sensörü devresi",
        "C0045" to "Sol arka tekerlek hız sensörü devresi",
        "C0050" to "Sağ arka tekerlek hız sensörü devresi",
        "C0110" to "ABS pompa motoru devresi",
        "C0121" to "ABS valf rölesi / solenoid besleme devresi",
        "C0131" to "ABS basınç sensörü devresi",
        "C0141" to "ABS hidrolik valf devresi",
        "U0001" to "Yüksek hızlı CAN haberleşme veri yolu",
        "U0101" to "Şanzıman kontrol modülü ile iletişim kaybı",
        "U0121" to "ABS kontrol modülü ile iletişim kaybı",
        "U0131" to "Servo direksiyon kontrol modülü ile iletişim kaybı",
        "U0140" to "Gövde kontrol modülü ile iletişim kaybı",
        "U0155" to "Gösterge paneli kontrol modülü ile iletişim kaybı",
        "U0401" to "Motor kontrol modülünden geçersiz veri alındı",
        "U0415" to "ABS kontrol modülünden geçersiz veri alındı"

    )

    // Yamaha two-digit and ABS codes are model/ECU-specific.
    // Their verified definitions live in yamaha_fault_database.json.
    // Never fall back to a universal two-digit meaning here.
    private val yamahaTwoDigit = emptyMap<String, String>()

    fun title(code: String): String {
        val normalized = code.trim().uppercase()
        generic[normalized]?.let { return it }
        yamahaTwoDigit[normalized]?.let { return it }
        return when {
            normalized.matches(Regex("P[0-9A-F]{4}")) -> when (normalized[1]) {
                '0', '2' -> "Standart OBD-II güç aktarma sistemi kodu; ayrıntılı tanım bu sürümde kayıtlı değil"
                '1' -> "Üreticiye özel güç aktarma sistemi kodu; marka/model/yıl servis tablosu gerekli"
                '3' -> "Ateşleme / güç aktarma sistemi kodu; alt kodun üretici standardıyla doğrulanması gerekli"
                else -> "Geçerli P-kodu biçimi; alt kod tanımı doğrulanmalı"
            }
            normalized.matches(Regex("B[0-9A-F]{4}")) -> "Gövde / güvenlik sistemi B-kodu; marka-model servis tablosu gerekli"
            normalized.matches(Regex("C[0-9A-F]{4}")) -> "Şasi / ABS / fren sistemi C-kodu; marka-model servis tablosu gerekli"
            normalized.matches(Regex("U[0-9A-F]{4}")) -> "Kontrol modülleri arası haberleşme U-kodu; marka-model servis tablosu gerekli"
            normalized.matches(Regex("\\d{2}(_ABS)?")) -> "Üreticiye özel Yamaha kodu (model/yıl eşleştirmesi gerekli)"
            else -> "Bilinmeyen veya desteklenmeyen DTC biçimi; ham ECU yanıtı ve protokol doğrulanmalı"
        }
    }

    fun description(code: String): String = when {
        code.trim().uppercase().matches(Regex("B[0-9A-F]{4}")) ->
            "B kodu gövde ve güvenlik elektroniği alanındadır. Motosiklette bu sistemin ve kodun desteklenmesi ECU mimarisine bağlıdır; üretici servis tablosu gerekir."
        code.trim().uppercase().matches(Regex("C[0-9A-F]{4}")) ->
            "C kodu şasi, fren ve ABS alanındadır. ABS modülünden gerçekten okunup okunmadığı doğrulanmalı; üretici açıklaması olmadan parça teşhisi yapılmamalıdır."
        code.trim().uppercase().matches(Regex("U[0-9A-F]{4}")) ->
            "U kodu modüller arası haberleşme alanındadır. Besleme, şase, sigorta, CAN hattı ve ilgili modül servis prosedürüyle kontrol edilmelidir."
        generic.containsKey(code.trim().uppercase()) ->
            "Bu, standart OBD-II DTC tanımına dayalı genel bir açıklamadır. Motosiklet üreticisi bu kodu farklı kapsamda kullanabilir; ECU/protokol ve servis dokümanıyla doğrulayın."
        code.trim().uppercase().matches(Regex("P[0-9A-F]{4}")) ->
            "P kodu güç aktarma sistemi arıza ailesindedir. İlk hane 0/2 ise genel SAE alanı, 1 ise üreticiye özel alan, 3 ise ateşleme/güç aktarma alanı olarak değerlendirilir. Alt kodun kesin tanımı marka-model-yıl ile doğrulanmalıdır."
        code.matches(Regex("\\d{2}(_ABS)?")) ->
            "Bu Yamaha üretici kodu, model, yıl ve ECU sürümüne göre değişebilir. Doğru açıklama için Yamaha model/yıl servis tablosu eşleştirilmelidir."
        else ->
            "Kod ECU'dan alınmış olabilir ancak bu veri tabanında açıklaması bulunmuyor. Ham yanıtı, ECU kimliğini ve motosikletin marka-model-yıl bilgisini kaydedin."
    }

    fun causes(code: String): List<String> = when (code.uppercase()) {
        "P0335", "P0336" -> listOf("Krank konum sensörü veya tetikleyici çark", "Sensör kablosunda kopukluk / kısa devre", "Sokette oksitlenme veya gevşek pin", "Sensör hava aralığı veya mekanik hasar")
        "P0115", "P0117", "P0118" -> listOf("Soğutma suyu sıcaklık sensörü veya iç direnci", "Sensör soketi, kablo kopukluğu ya da kısa devre", "5 V referans / sensör şasesi sorunu", "ECU giriş devresi veya gerçek soğutma sistemi sıcaklık sorunu")
        "P0128" -> listOf("Termostat açık kalmış olabilir", "Motorun çalışma sıcaklığına ulaşmasını engelleyen soğutma sistemi sorunu", "ECT sensörü veya kablo ölçüm hatası")
        "P0120", "P0121", "P0122", "P0123", "P2135" -> listOf("TPS sensörü veya elektronik gaz kelebeği gövdesi", "Sensör referans voltajı / şase sorunu", "Soket pinleri ve kablo hattı", "Gaz kelebeğinde mekanik sıkışma veya adaptasyon sorunu")
        "P0105", "P0107", "P0108", "P0100", "P0101", "P0102", "P0103" -> listOf("MAP / MAF sensörü kirlenmesi veya arızası", "Emme hortumu kaçağı veya tıkanıklık", "Besleme, şase veya sinyal kablosu sorunu")
        "P0171", "P0172", "P0174", "P0175" -> listOf("Emme hattında hava kaçağı", "Yakıt basıncı / enjektör akış sorunu", "Oksijen sensörü veya yakıt düzeltme verisi", "MAF / MAP ölçüm hatası")
        "P0201", "P0202", "P0261", "P0262" -> listOf("Enjektör bobini veya mekanik tıkanma", "Enjektör kablosu ve soketi", "ECU sürücü devresi veya besleme")
        "P0300", "P0301", "P0302", "P0303", "P0304", "P0351", "P0352" -> listOf("Buji ve ateşleme bobini", "Enjektör veya yakıt beslemesi", "Kompresyon / mekanik durum", "Krank / eksantrik sinyal senkronu")
        "P0560", "P0562", "P0563", "P0620" -> listOf("Akü şarj durumu", "Regülatör / doğrultucu", "Şarj sistemi bağlantıları", "Şase ve ana besleme kabloları")
        "U0100", "U0121", "U0155" -> listOf("Modül besleme veya şase kesintisi", "CAN hattı veya soket bağlantısı", "Sigorta / röle", "İlgili kontrol modülünün kendisi")
        else -> listOf("İlgili sensör / aktüatör veya elektrik devresi", "Soket, kablo, besleme ve şase", "Mekanik bağlantı veya sistem koşulları", "ECU yazılımı / üreticiye özel kalibrasyon")
    }

    fun checks(code: String): List<String> = when (code.uppercase()) {
        "P0335", "P0336" -> listOf("Krank sensörü soketini ve kablo hattını inceleyin", "Sensör direncini / sinyalini servis değerleriyle karşılaştırın", "Tetik çarkı ve sensör aralığını kontrol edin", "Onarım sonrası arıza kodunu yeniden okuyun")
        "P0115", "P0117", "P0118" -> listOf("Motor tamamen soğukken ECT değerini ortam sıcaklığıyla karşılaştırın", "Sensör soketi ve kablo hattında oksitlenme, kopukluk veya kısa devre arayın", "Sensör direncini sıcaklığa göre üretici servis tablosuyla karşılaştırın", "Referans besleme ve sensör şasesini ölçün; gerçek hararet şüphesinde soğutma sistemini güvenli şekilde kontrol edin")
        "P0128" -> listOf("Motorun ısınma süresini ve ECT değişimini izleyin", "Termostatın açık kalıp kalmadığını servis prosedürüyle kontrol edin", "ECT sensörü ve kablo ölçümünü doğrulayın")
        "P0120", "P0121", "P0122", "P0123", "P2135" -> listOf("TPS kapalı ve tam açık konum sinyallerini ölçün", "5 V referans ve sensör şasesini kontrol edin", "Kelebek gövdesinde sıkışma olup olmadığını kontrol edin", "Yalnızca araç prosedürü destekliyorsa adaptasyon uygulayın")
        "P0130" -> listOf("O2 sensörü sinyal kablosu, soketi ve ısıya maruz kalan tesisatı inceleyin", "Egzoz manifoldunda sensör öncesi kaçak olup olmadığını kontrol edin", "Motor çalışma koşullarında O2 sinyali ile STFT / LTFT düzeltmelerini birlikte izleyin", "Sensör beslemesi ve şasesini üretici şemasına göre doğrulayın")
        "P0171", "P0172", "P0174", "P0175" -> listOf("Emme manifoldu ve vakum hortumlarında kaçak arayın", "Yakıt basıncını servis değerleriyle ölçün", "STFT / LTFT ve O2 verilerini birlikte inceleyin", "MAF / MAP verisini karşılaştırın")
        "P0201", "P0202", "P0261", "P0262" -> listOf("Kontak kapalıyken enjektör soketi ve kabloyu kontrol edin", "Enjektör bobin direncini servis değerleriyle ölçün", "Besleme ve ECU kontrol hattını uygun test prosedürüyle doğrulayın")
        "P0300", "P0301", "P0302", "P0303", "P0304" -> listOf("Buji, bobin ve bağlantılarını kontrol edin", "Enjektör ve yakıt beslemesini inceleyin", "Gerekirse kompresyon / kaçak testi yapın", "Arıza tekrarını canlı verilerle doğrulayın")
        "P0560", "P0562", "P0563", "P0620" -> listOf("Akü voltajını kontak kapalı ve motor çalışırken ölçün", "Şarj voltajını üretici aralığıyla karşılaştırın", "Regülatör, stator, sigorta ve şase bağlantılarını kontrol edin")
        else -> listOf("İlgili sensör soketini ve kablo hattını inceleyin", "Besleme, şase ve sinyal ölçümünü servis dokümanına göre yapın", "Gerekirse mekanik parçayı kontrol edin", "Onarım sonrası kodu yeniden okuyup doğrulayın")
    }
}
