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
        "P0104" to "MAF hava kütle akış devresinde aralıklı / kesintili sinyal",
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
        "P1604" to "İmmobilizer / anahtar doğrulama sistemi hatası",
        "P2100" to "Gaz kelebeği aktüatör motoru A devresi / açık devre",
        "P2101" to "Gaz kelebeği aktüatör kontrol aralık / performans hatası",
        "P2135" to "Gaz kelebeği / pedal konum sensörleri A-B korelasyon hatası",
        "U0100" to "Motor kontrol modülü ile iletişim kaybı",
        "U0121" to "ABS kontrol modülü ile iletişim kaybı",
        "U0155" to "Gösterge paneli kontrol modülü ile iletişim kaybı"
    )

    // Yamaha-style two-digit fault code references transcribed from the supplied Python app.
    // Their exact application is ECU/model/year dependent; these are not generic SAE DTCs.
    private val yamahaTwoDigit = mapOf(
        "13" to "Pulsar bobin sinyali hatası",
        "14" to "Krank konum sensörü sinyali hatası",
        "15" to "Motor sıcaklık sensörü sinyali hatası",
        "17" to "Vuruntu sensörü hatası",
        "18" to "Gaz kelebeği konum sensörü sinyali hatası",
        "19" to "Akü voltajı hatası",
        "22" to "Atmosferik basınç sensörü sinyali hatası",
        "23" to "Emme havası sıcaklık sensörü sinyali hatası",
        "24" to "Eksantrik mili konum sensörü hatası",
        "25" to "Yakıt basıncı sensörü sinyali hatası",
        "26" to "Enjektör sinyali hatası",
        "27" to "Yakıt filtresinde su algılandı",
        "28" to "Vites konum anahtarı sinyali hatası",
        "29" to "Emme basınç sensörü sinyali hatası",
        "30" to "ECU kilitlenme / latch-up tespiti",
        "33" to "Silindir 1 ateşleme bobini arızası",
        "34" to "Silindir 2 ateşleme bobini arızası",
        "39" to "Yağ basıncı sensörü hatası",
        "41" to "Hız sensörü sinyali yok",
        "42" to "Vites konum anahtarı açık / kısa devre",
        "43" to "Yakıt sistemi voltajı anormal",
        "44" to "EEPROM okuma / yazma hatası",
        "46" to "Şarj voltajı anormal",
        "50" to "ECU bellek arızası",
        "70" to "Motor rölanti durdurma sistemi hatası"
    )

    fun title(code: String): String = generic[code.uppercase()] ?: yamahaTwoDigit[code]
        ?: "Üreticiye özel / henüz eşleştirilmemiş arıza kodu"

    fun description(code: String): String = when {
        generic.containsKey(code.uppercase()) ->
            "Bu, standart OBD arıza kodu ailesinde tanımlı bir güç aktarma sistemi kaydıdır. Kod tek başına arızalı parçayı kesinleştirmez; canlı veriler, kablo ölçümü ve araç servis dokümanıyla birlikte değerlendirilmelidir."
        yamahaTwoDigit.containsKey(code) ->
            "Bu iki haneli kod, sağlanan Python uygulamasındaki Yamaha tarzı üretici kod listesinde bulunur. Motosikletin model yılı ve ECU sürümüne ait servis tablosuyla teyit edilmelidir."
        else ->
            "Kod ECU'dan alınmış olabilir ancak bu veri tabanında açıklaması bulunmuyor. Ham yanıtı, ECU kimliğini ve motosikletin marka-model-yıl bilgisini kaydedin."
    }

    fun causes(code: String): List<String> = when (code.uppercase()) {
        "P0335", "P0336" -> listOf("Krank konum sensörü veya tetikleyici çark", "Sensör kablosunda kopukluk / kısa devre", "Sokette oksitlenme veya gevşek pin", "Sensör hava aralığı veya mekanik hasar")
        "P0115", "P0117", "P0118", "P0128" -> listOf("Sıcaklık sensörü arızası", "Soket / kablo temassızlığı", "Şase veya referans beslemesi", "Soğutma sistemi veya termostat sorunu")
        "P0120", "P0121", "P0122", "P0123", "P2135" -> listOf("TPS sensörü veya elektronik gaz kelebeği gövdesi", "Sensör referans voltajı / şase sorunu", "Soket pinleri ve kablo hattı", "Gaz kelebeğinde mekanik sıkışma veya adaptasyon sorunu")
        "P0104" -> listOf("MAF sensörü varsa sensör içinde aralıklı temas / sinyal kesintisi", "MAF soketi veya kablo demetinde titreşimle oluşan temassızlık", "Sensör beslemesi veya şasesinde kesinti", "Hava filtresi kutusu ve emiş kanalında gevşeklik / kaçak")
        "P0105", "P0107", "P0108", "P0100", "P0101", "P0102", "P0103" -> listOf("MAP / MAF sensörü kirlenmesi veya arızası", "Emme hortumu kaçağı veya tıkanıklık", "Besleme, şase veya sinyal kablosu sorunu")
        "P0171", "P0172", "P0174", "P0175" -> listOf("Emme hattında hava kaçağı", "Yakıt basıncı / enjektör akış sorunu", "Oksijen sensörü veya yakıt düzeltme verisi", "MAF / MAP ölçüm hatası")
        "P0201", "P0202", "P0261", "P0262" -> listOf("Enjektör bobini veya mekanik tıkanma", "Enjektör kablosu ve soketi", "ECU sürücü devresi veya besleme")
        "P0300", "P0301", "P0302", "P0303", "P0304", "P0351", "P0352" -> listOf("Buji ve ateşleme bobini", "Enjektör veya yakıt beslemesi", "Kompresyon / mekanik durum", "Krank / eksantrik sinyal senkronu")
        "P0560", "P0562", "P0563", "P0620" -> listOf("Akü şarj durumu", "Regülatör / doğrultucu", "Şarj sistemi bağlantıları", "Şase ve ana besleme kabloları")
        "U0100", "U0121", "U0155" -> listOf("Modül besleme veya şase kesintisi", "CAN hattı veya soket bağlantısı", "Sigorta / röle", "İlgili kontrol modülünün kendisi")
        else -> listOf("İlgili sensör / aktüatör veya elektrik devresi", "Soket, kablo, besleme ve şase", "Mekanik bağlantı veya sistem koşulları", "ECU yazılımı / üreticiye özel kalibrasyon")
    }

    fun checks(code: String): List<String> = when (code.uppercase()) {
        "P0104" -> listOf("Önce bu motosikletin gerçekten MAF sensörü kullanıp kullanmadığını servis şemasıyla doğrulayın", "MAF soketi ve kablo demetini motor titreşimi sırasında oluşabilecek temassızlık açısından inceleyin", "Sensör beslemesi, şasesi ve sinyalini modelin servis değerleriyle ölçün", "Hava filtresi kutusu ve emiş kanalını kontrol edin; onarımdan sonra kodu silip tekrar tarayın")
        "P0335", "P0336" -> listOf("Krank sensörü soketini ve kablo hattını inceleyin", "Sensör direncini / sinyalini servis değerleriyle karşılaştırın", "Tetik çarkı ve sensör aralığını kontrol edin", "Onarım sonrası arıza kodunu yeniden okuyun")
        "P0115", "P0117", "P0118", "P0128" -> listOf("Sensör soketini ve kabloyu kontrol edin", "Soğuk ve sıcak motor değerlerini karşılaştırın", "Soğutma suyu ve termostatı kontrol edin", "Servis dokümanındaki direnç / voltaj değerini doğrulayın")
        "P0120", "P0121", "P0122", "P0123", "P2135" -> listOf("TPS kapalı ve tam açık konum sinyallerini ölçün", "5 V referans ve sensör şasesini kontrol edin", "Kelebek gövdesinde sıkışma olup olmadığını kontrol edin", "Yalnızca araç prosedürü destekliyorsa adaptasyon uygulayın")
        "P0171", "P0172", "P0174", "P0175" -> listOf("Emme manifoldu ve vakum hortumlarında kaçak arayın", "Yakıt basıncını servis değerleriyle ölçün", "STFT / LTFT ve O2 verilerini birlikte inceleyin", "MAF / MAP verisini karşılaştırın")
        "P0201", "P0202", "P0261", "P0262" -> listOf("Kontak kapalıyken enjektör soketi ve kabloyu kontrol edin", "Enjektör bobin direncini servis değerleriyle ölçün", "Besleme ve ECU kontrol hattını uygun test prosedürüyle doğrulayın")
        "P0300", "P0301", "P0302", "P0303", "P0304" -> listOf("Buji, bobin ve bağlantılarını kontrol edin", "Enjektör ve yakıt beslemesini inceleyin", "Gerekirse kompresyon / kaçak testi yapın", "Arıza tekrarını canlı verilerle doğrulayın")
        "P0560", "P0562", "P0563", "P0620" -> listOf("Akü voltajını kontak kapalı ve motor çalışırken ölçün", "Şarj voltajını üretici aralığıyla karşılaştırın", "Regülatör, stator, sigorta ve şase bağlantılarını kontrol edin")
        else -> listOf("İlgili sensör soketini ve kablo hattını inceleyin", "Besleme, şase ve sinyal ölçümünü servis dokümanına göre yapın", "Gerekirse mekanik parçayı kontrol edin", "Onarım sonrası kodu yeniden okuyup doğrulayın")
    }
}
