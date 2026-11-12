
package com.efemoto.motodiagpro

import java.text.Normalizer
import java.util.Locale

/**
 * SEYFİ USTA
 * Türkçe sesli komut çözümleyicisi.
 *
 * İnternet bağlantısı kullanmaz.
 * Ses tanıma işlemini OfflineVoiceAssistant sağlar.
 * Bu sınıf tanınan cümleyi uygulama komutuna dönüştürür.
 */
enum class SeyfiAction {
    WAKE_WORD,
    START_SCAN,
    READ_FAULTS,
    EXPLAIN_FAULTS,
    CLEAR_FAULTS,
    SHOW_LIVE_DATA,
    SHOW_RPM,
    SHOW_TEMPERATURE,
    SHOW_VOLTAGE,
    SHOW_TPS,
    SHOW_MAP,
    SHOW_CONNECTION_STATUS,
    SHOW_LOGS,
    SHOW_FAULT_SEARCH,
    SHOW_SEYFI_CHAT,
    SHOW_COMMUNICATION_REPORT,
    SHOW_HELP,
    GO_HOME,
    SHOW_TESTS,
    ANALYZE_ENGINE_DATA,
    EXPORT_REPORT,
    STOP,
    UNKNOWN
}

data class SeyfiCommand(
    val action: SeyfiAction,
    val originalText: String,
    val response: String
)

class SeyfiUstaCommandHandler {

    private fun normalize(text: String): String {
        val lower = text.lowercase(Locale("tr", "TR"))

        val normalized = Normalizer.normalize(
            lower,
            Normalizer.Form.NFD
        )

        return normalized
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("ı", "i")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            // Türkçe günlük konuşma ve Vosk'un sık yazım varyantları.
            .replace(Regex("\\b(taramayi|taramaya) basliycam\\b"), "taramaya basla")
            .replace(Regex("\\b(taramayi|taramaya) baslicam\\b"), "taramaya basla")
            .replace(Regex("\\b(canli verilerimi|canli veriyi|canli veriler)\\b"), "canli veri")
            .replace(Regex("\\b(canli veri ekrani|canli veriye)\\b"), "canli veri")
            .replace(Regex("\\b(testleri|testlerimi) ac\\b"), "testleri ac")
            .replace(Regex("\\b(test yapicam|test yapcam|test yapacagim)\\b"), "test yap")
            .replace(Regex("\\b(ariza kodunu|ariza kodu) verecem\\b"), "ariza kodu verecegim")
            .replace(Regex("\\b(ariza kodunu|ariza kodu) soyleyecem\\b"), "ariza kodu soyleyecegim")
    }

    private fun containsAny(
        text: String,
        vararg phrases: String
    ): Boolean {
        return phrases.any { phrase ->
            val normalizedPhrase = normalize(phrase)
            normalizedPhrase.isNotBlank() && text.contains(normalizedPhrase)
        }
    }

    fun handle(recognizedText: String): SeyfiCommand {
        val original = recognizedText.trim()
        val text = normalize(original)

        if (text.isBlank()) {
            return command(
                SeyfiAction.UNKNOWN,
                original,
                "Sizi anlayamadım. Tekrar söyler misiniz?"
            )
        }

        // Uyandırma kelimesi
        val wakeWords = listOf(
            "seyfi usta",
            "seyfi",
            "usta"
        )

        val wakeDetected = wakeWords.any {
            text.contains(normalize(it))
        }

        // "Seyfi ekranını aç" ifadesindeki Seyfi sözcüğü uyandırma kelimesi
        // olarak temizlenmeden önce ekran komutu olarak değerlendirilir.
        if (text.contains("seyfi ekranini ac") || text.contains("seyfi usta ekranini ac")) {
            return command(SeyfiAction.SHOW_SEYFI_CHAT, original, "Seyfi Usta ekranını açıyorum.")
        }

        val commandText = text
            .replace("seyfi usta", "")
            .replace("seyfi", "")
            .replace("usta", "")
            .trim()

        // Vosk konuşmayı kısa parçalara böldüğünde "dinliyorum" gibi
        // ara ifadeler komut oturumunu kapatmasın.
        if (commandText in listOf("dinliyorum", "buradayim", "hazirim", "beni dinle")) {
            return command(SeyfiAction.WAKE_WORD, original, "Dinliyorum.")
        }

        // Sadece uyandırma kelimesi söylendiyse
        if (commandText.isBlank() && wakeDetected) {
            return command(
                SeyfiAction.WAKE_WORD,
                original,
                "Dinliyorum."
            )
        }

        // Uyandırma kelimesi olmadan da komutları tanıyabilir.
        if (containsAny(
                commandText,
                "taramaya basla",
                "taramayi baslat",
                "ariza taramasini baslat",
                "ariza kontrolune basla",
                "motor ariza kontrolu",
                "motoru kontrol et",
                "tarama baslat",
                "tara",
                "basla taramayi",
                "ariza taramaya basla",
                "motoru taramaya basla",
                "ariza tara",
                "arizalari tara",
                "arizalari taramaya basla",
                "arizalari kontrol et",
                "ariza taramasi",
                "ariza taramasi yap",
                "ariza kontrolu yap",
                "motor ariza tara",
                "motor arizalarini tara",
                "ecu ariza tara",
                "ecu arizalarini tara",
                "hata tara",
                "hatalari tara",
                "ariza bul",
                "arizalari bul",
                "motoru tara",
                "ecu tara",
                "diagnostik baslat",
                "diagnostik tara",
                "tarama",
                "tara",
                "tarama yap",
                "motoru tara",
                "motorumu tara",
                "ariza kontrol",
                "ariza kontrol et",
                "motor kontrol",
                "motor kontrol et",
                "ecu kontrol et",
                "kontrol taramasi yap",
                "genel tarama",
                "ariza ara",
                "hata ara",
                "motor taramasi",
                "ecu taramasi",
                "taramayi calistir",
                "genel taramayi baslat",
                "motor saglik kontrolu",
                "saglik kontrolu yap",
                "ariza analizi yap",
                "motoru incele",
                "genel tarama yap",
                "genel ariza taramasi",
                "genel ariza kontrolu",
                "genel kontrol yap",
                "motorun durumunu kontrol et",
                "motoru kontrol et",
                "motorumu analiz et",
                "ariza tespiti yap",
                "ariza teshis",
                "ariza teshisi",
                "motor ariza teshis",
                "motor ariza teshisi",
                "teshis yap",
                "teshise basla",
                "ariza teşhis",
                "simdi tara",
                "tarama yap"
            )
        ) {
            return command(
                SeyfiAction.START_SCAN,
                original,
                "Arıza taraması başlatılıyor."
            )
        }

        if (containsAny(
                commandText,
                "arizalari sil",
                "arizayi sil",
                "ariza kodlarini temizle",
                "ariza sil",
                "arizalari temizle",
                "hatalari sil",
                "hata kodlarini sil",
                "dtc sil",
                "kodlari temizle"
            )
        ) {
            return command(
                SeyfiAction.CLEAR_FAULTS,
                original,
                "Arıza silme işlemi başlatılıyor. Sonucu ECU üzerinden kontrol edeceğim."
            )
        }

        if (containsAny(
                commandText,
                "arizalari oku",
                "arizayi oku",
                "ariza kodu oku",
                "hatalari oku",
                "ariza kodlarini oku",
                "hata kodlarini oku",
                "arizalar neler",
                "hangi arizalar var",
                "ariza listesi",
                "hata kodlari neler",
                "kayitli hatalar",
                "motorumdaki arizalar",
                "ecu arizalarini oku",
                "mevcut arizalari oku",
                "kayitli ariza kodlari",
                "hata kodlarini oku"
            )
        ) {
            return command(
                SeyfiAction.READ_FAULTS,
                original,
                "Kayıtlı arıza kodlarını okuyorum."
            )
        }

        if (containsAny(
                commandText,
                "hata dokumu",
                "hata dokum",
                "hatta dokumu",
                "hata dokumlerini goster",
                "hata raporlarini ac",
                "hata dokumunu ac",
                "hata dokumunu goster",
                "hata dokumunu getir",
                "hata dokumlerini ac",
                "hata dokumlerini goster",
                "hata dokumu ekrani",
                "hata kayitlari",
                "hata kaydini ac",
                "islem dokumu",
                "islem gecmisi",
                "gecmis kayitlari",
                "servis kaydi",
                "servis formu",
                "servis bilgisi kaydet",
                "servis kaydini ac",
                "servis bilgi ekranini ac",
                "kayitlari yenile",
                "son islemleri yenile",
                "islem kayitlari",
                "loglari goster",
                "loglari ac",
                "sistem gunlugu",
                "kayitlari goster",
                "son islemler"
            )
        ) {
            return command(
                SeyfiAction.SHOW_LOGS,
                original,
                "Hata dökümü ve işlem kayıtlarını açıyorum."
            )
        }

        // Uygulamadaki ana bölümler ve butonlar: sesli isimleri doğrudan ekrana bağla.
        if (containsAny(
                commandText,
                "seyfi ekrani",
                "seyfi usta ekrani",
                "seyfi ustayi ac",
                "sohbet ekrani",
                "sohbet bolumunu ac",
                "yapay zeka ekrani",
                "sesli asistan ekrani",
                "asistan ekranini ac",
                "yerel yapay zeka",
                "seyfi usta yerel yapay zeka",
                "yamaha teshis ekrani",
                "yamaha teshis ekranini ac",
                "seyfi usta sesli sohbet",
                "sesli cevabi test et",
                "durumu yenile",
                "seyfi durumunu yenile"
            )
        ) {
            return command(SeyfiAction.SHOW_SEYFI_CHAT, original, "Seyfi Usta ekranını açıyorum.")
        }

        if (containsAny(
                commandText,
                "ariza kodu ara",
                "hata kodu ara",
                "dtc ara",
                "ariza kodu sorgula",
                "kod aciklamasi ara",
                "ariza kutuphanesi",
                "ariza kodu kutuphanesi",
                "ariza kodlarini incele",
                "ariza ekranini ac",
                "arizalar ekranini ac",
                "ariza kodlarini incele",
                "ariza kodlari bolumu",
                "ariza kodlari ekrani"
            )
        ) {
            return command(SeyfiAction.SHOW_FAULT_SEARCH, original, "Arıza kodu arama bölümünü açıyorum.")
        }

        if (containsAny(
                commandText,
                "viecar iletisim raporu",
                "ecu iletisim raporu",
                "iletisim raporu",
                "tam iletisim raporu",
                "adaptor iletisim raporu",
                "adaptoru test et",
                "ecu iletisimini test et",
                "viecar raporu",
                "adaptor raporu",
                "adaptoru kontrol et",
                "tam viecar ecu iletisim raporu",
                "baglanti raporu"
            )
        ) {
            return command(SeyfiAction.SHOW_COMMUNICATION_REPORT, original, "Viecar ve ECU iletişim raporunu hazırlıyorum.")
        }

        if (containsAny(
                commandText,
                "arizalari acikla",
                "ariza kodlarini acikla",
                "arizalari anlat",
                "ariza detaylari",
                "arizalarin nedenleri",
                "bu arizalar ne anlama geliyor"
            )
        ) {
            return command(
                SeyfiAction.EXPLAIN_FAULTS,
                original,
                "Okunan ECU arızalarını ve olası nedenlerini açıklıyorum."
            )
        }

        if (containsAny(
                commandText,
                "motor verilerini yorumla",
                "motor verisini yorumla",
                "sensorleri yorumla",
                "sensor verilerini degerlendir",
                "motor verilerini analiz et",
                "verileri yapay zeka ile yorumla",
                "motor analizi yap"
            )
        ) {
            return command(
                SeyfiAction.ANALYZE_ENGINE_DATA,
                original,
                "Gerçek ECU verilerini yerel yapay zekâ ile yorumluyorum."
            )
        }

        if (containsAny(
                commandText,
                "canli verileri goster",
                "canli verileri oku",
                "canli veriyi ac",
                "canli verileri ac",
                "canli sensor verileri",
                "sensor ekranini ac",
                "anlik verileri goster",
                "motorun canli verileri",
                "sensorleri goster",
                "motor verileri",
                "canli veri",
                "tum sensorleri oku",
                "motorun anlik verileri",
                "sensor degerlerini oku",
                "sensor verilerini goster",
                "canli veri ekranini ac"
            )
        ) {
            return command(
                SeyfiAction.SHOW_LIVE_DATA,
                original,
                "Canlı motor verilerini kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "devir kac",
                "motor devri",
                "rpm kac",
                "motor kac devir"
            )
        ) {
            return command(
                SeyfiAction.SHOW_RPM,
                original,
                "Motor devrini kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "motor sicakligi",
                "hararet kac",
                "su sicakligi",
                "sicaklik kac"
            )
        ) {
            return command(
                SeyfiAction.SHOW_TEMPERATURE,
                original,
                "Motor sıcaklığını kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "aku voltaji",
                "sarj voltaji",
                "voltaj kac",
                "akunun durumu"
            )
        ) {
            return command(
                SeyfiAction.SHOW_VOLTAGE,
                original,
                "Akü voltajını kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "manifold basinci",
                "emme basinci",
                "map degeri",
                "map kac",
                "hava basinci"
            )
        ) {
            return command(
                SeyfiAction.SHOW_MAP,
                original,
                "Emme manifoldu basıncını kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "baglanti durumu",
                "viecar bagli mi",
                "ecu bagli mi",
                "adaptore bagli miyim",
                "motor beynine bagli mi"
            )
        ) {
            return command(
                SeyfiAction.SHOW_CONNECTION_STATUS,
                original,
                "ECU bağlantı durumunu kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "gaz konumu",
                "gaz kelebeginin durumu",
                "tps degeri",
                "gaz yuzdesi"
            )
        ) {
            return command(
                SeyfiAction.SHOW_TPS,
                original,
                "Gaz kelebeği konumunu kontrol ediyorum."
            )
        }

        if (containsAny(
                commandText,
                "yardim",
                "ses fonksiyonu",
                "ses fonksu",
                "ses fonks",
                "ses fonksiyonu nedir",
                "sesli fonksiyon",
                "sesli komut",
                "ses sistemi",
                "mikrofon nasil calisiyor",
                "ses nasil calisiyor",
                "neler yapabilirsin",
                "butonlari say",
                "butonlar neler",
                "bolumleri say",
                "bolumler neler",
                "ekranlari say",
                "hangi ekranlar var",
                "uygulama menuleri",
                "menuyu anlat",
                "bana programi anlat",
                "baska ne yapabilirsin",
                "baska neler yapabilirsin",
                "baska ne yapabiliyorsun",
                "komutlar neler",
                "ne yapabiliyorsun"
            )
        ) {
            return command(
                SeyfiAction.SHOW_HELP,
                original,
                "Gerçek ECU arıza taraması yapabilir, arıza kodlarını okuyup açıklayabilir, onayınızla silebilir, canlı sensör verilerini gösterebilir, hata dökümünü açabilir, bağlantıyı kontrol edebilir ve sistem testlerini çalıştırabilirim. ECU bağlı değilse ölçüm uydurmam."
            )
        }

        if (containsAny(
                commandText,
                "ana sayfa",
                "ana ekran",
                "ana menu",
                "ana ekrana don",
                "baslangic ekrani",
                "eve don"
            )
        ) {
            return command(
                SeyfiAction.GO_HOME,
                original,
                "Ana sayfaya dönüyorum."
            )
        }

        // Tek kelimelik sesli "test" komutunu sohbet modeline göndermeden
        // doğrudan Sistem Testleri ekranına yönlendir.
        if (commandText == "test" || containsAny(
                commandText,
                "test yap",
                "testi yap",
                "teste basla",
                "testi baslat",
                "test ekranini ac",
                "testler ekranini ac",
                "test bolumunu goster",
                "sistem kontrolu yap",
                "kendini kontrol et",
                "test et",
                "test yapalim",
                "kendini test et",
                "kendi kendini test et",
                "sistemi test et",
                "sistem testi yap",
                "ses testi",
                "mikrofon testi",
                "yapay zeka testi",
                "yapay zekayi test et",
                "komut testi",
                "komutlari test et",
                "ses sistemini test et",
                "test test yap",
                "testleri baslat",
                "ses tanima testi",
                "mikrofonu dene",
                "kendi testini yap",
                "testleri goster",
                "testleri ac",
                "sistem testi",
                "sistem testlerini ac",
                "testler",
                "iletisim testi",
                "durumlari yenile",
                "yapay zekayi gercekten test et",
                "sensorleri kontrol et",
                "yakit verilerini oku",
                "atesleme verisini oku",
                "ariza hafizasini oku",
                "ecu bilgisini oku",
                "sistemi kontrol et",
                "butun testleri goster",
                "test bolumunu ac"
            )
        ) {
            return command(
                SeyfiAction.SHOW_TESTS,
                original,
                "Sistem testleri ekranını açıyorum."
            )
        }

        if (containsAny(
                commandText,
                "rapor hazirla",
                "rapor al",
                "rapor kaydet",
                "raporu paylas",
                "servis raporu",
                "raporu disa aktar",
                "raporu disari aktar",
                "rapor paylas",
                "teshis raporu olustur",
                "raporu disa aktar paylas"
            )
        ) {
            return command(
                SeyfiAction.EXPORT_REPORT,
                original,
                "Teşhis raporunu hazırlıyorum."
            )
        }

        if (containsAny(
                commandText,
                "dinlemeyi durdur",
                "sus",
                "bekle",
                "dinlemeyi kapat"
            )) {
            return command(
                SeyfiAction.STOP,
                original,
                "Tamam, bekliyorum."
            )
        }

        return command(
            SeyfiAction.UNKNOWN,
            original,
            "Bu komutu henüz tanımıyorum. Yardım diyerek komutları öğrenebilirsiniz."
        )
    }

    /**
     * Açılışta ve test ekranında çalıştırılabilen komut sözlüğü öz testi.
     * Bu test mikrofonun gerçekten duyduğunu veya modelin telefonda açıldığını kanıtlamaz.
     */
    fun selfTest(): List<String> {
        val cases = listOf(
            "Seyfi Usta" to SeyfiAction.WAKE_WORD,
            "Seyfi" to SeyfiAction.WAKE_WORD,
            "usta" to SeyfiAction.WAKE_WORD,
            "Seyfi Usta test yap" to SeyfiAction.SHOW_TESTS,
            "usta seyfi usta test test yap" to SeyfiAction.SHOW_TESTS,
            "test" to SeyfiAction.SHOW_TESTS,
            "test yap" to SeyfiAction.SHOW_TESTS,
            "kendini test et" to SeyfiAction.SHOW_TESTS,
            "mikrofon testi" to SeyfiAction.SHOW_TESTS,
            "ses tanıma testi" to SeyfiAction.SHOW_TESTS,
            "testleri başlat" to SeyfiAction.SHOW_TESTS,
            "yapay zekayı test et" to SeyfiAction.SHOW_TESTS,
            "tarama" to SeyfiAction.START_SCAN,
            "tara" to SeyfiAction.START_SCAN,
            "tarama yap" to SeyfiAction.START_SCAN,
            "motorumu tara" to SeyfiAction.START_SCAN,
            "arızaları taramaya başla" to SeyfiAction.START_SCAN,
            "ariza ara" to SeyfiAction.START_SCAN,
            "motor sağlik kontrolü" to SeyfiAction.START_SCAN,
            "taramayı çalıştır" to SeyfiAction.START_SCAN,
            "ECU kontrol et" to SeyfiAction.START_SCAN,
            "arızaları oku" to SeyfiAction.READ_FAULTS,
            "arıza teşhis" to SeyfiAction.START_SCAN,
            "motor arıza teşhis" to SeyfiAction.START_SCAN,
            "teşhise başla" to SeyfiAction.START_SCAN,
            "ses fonksu" to SeyfiAction.SHOW_HELP,
            "ses fonksiyonu" to SeyfiAction.SHOW_HELP,
            "arızaları açıkla" to SeyfiAction.EXPLAIN_FAULTS,
            "arızaları sil" to SeyfiAction.CLEAR_FAULTS,
            "canlı verileri göster" to SeyfiAction.SHOW_LIVE_DATA,
            "hata dökümü" to SeyfiAction.SHOW_LOGS,
            "hatta dökümü" to SeyfiAction.SHOW_LOGS,
            "hata döküm" to SeyfiAction.SHOW_LOGS,
            "hata dökümünü aç" to SeyfiAction.SHOW_LOGS,
            "hata dökümünü göster" to SeyfiAction.SHOW_LOGS,
            "işlem kayıtlarını aç" to SeyfiAction.SHOW_LOGS,
            "son işlemleri göster" to SeyfiAction.SHOW_LOGS,
            "arıza kodu ara" to SeyfiAction.SHOW_FAULT_SEARCH,
            "hata kodu ara" to SeyfiAction.SHOW_FAULT_SEARCH,
            "Seyfi ekranını aç" to SeyfiAction.SHOW_SEYFI_CHAT,
            "sohbet ekranı" to SeyfiAction.SHOW_SEYFI_CHAT,
            "iletişim raporu" to SeyfiAction.SHOW_COMMUNICATION_REPORT,
            "tam Viecar ECU iletişim raporu" to SeyfiAction.SHOW_COMMUNICATION_REPORT,
            "adaptörü test et" to SeyfiAction.SHOW_COMMUNICATION_REPORT,
            "gerçek arıza taraması başlat" to SeyfiAction.START_SCAN,
            "hata dökümünü getir" to SeyfiAction.SHOW_LOGS,
            "hata raporlarını aç" to SeyfiAction.SHOW_LOGS,
            "servis kaydı" to SeyfiAction.SHOW_LOGS,
            "servis formu" to SeyfiAction.SHOW_LOGS,
            "servis bilgisi kaydet" to SeyfiAction.SHOW_LOGS,
            "servis kaydını aç" to SeyfiAction.SHOW_LOGS,
            "sistem testleri ekranını aç" to SeyfiAction.SHOW_TESTS,
            "sensör verilerini göster" to SeyfiAction.SHOW_LIVE_DATA,
            "Yamaha teşhis ekranını aç" to SeyfiAction.SHOW_SEYFI_CHAT,
            "adaptörü test et" to SeyfiAction.SHOW_COMMUNICATION_REPORT,
            "servis bilgisi kaydet" to SeyfiAction.SHOW_LOGS,
            "servis kaydı" to SeyfiAction.SHOW_LOGS,
            "raporu dışa aktar paylaş" to SeyfiAction.EXPORT_REPORT,
            "arıza kodlarını incele" to SeyfiAction.SHOW_FAULT_SEARCH,
            "canlı veri ekranını aç" to SeyfiAction.SHOW_LIVE_DATA,
            "gerçek arıza taraması başlat" to SeyfiAction.START_SCAN,
            "servis formu" to SeyfiAction.SHOW_LOGS,
            "servis kaydını aç" to SeyfiAction.SHOW_LOGS,
            "hata dökümünü getir" to SeyfiAction.SHOW_LOGS,
            "hata raporlarını aç" to SeyfiAction.SHOW_LOGS,
            "sistem testleri ekranını aç" to SeyfiAction.SHOW_TESTS,
            "sensör verilerini göster" to SeyfiAction.SHOW_LIVE_DATA,
            "Yamaha teşhis ekranını aç" to SeyfiAction.SHOW_SEYFI_CHAT,
            "başka ne yapabiliyorsun" to SeyfiAction.SHOW_HELP,
            "ana sayfa" to SeyfiAction.GO_HOME,
            "motor verilerini yorumla" to SeyfiAction.ANALYZE_ENGINE_DATA
        )
        return cases.mapNotNull { (phrase, expected) ->
            val actual = handle(phrase).action
            if (actual == expected) null else "\"$phrase\" bekleniyordu: $expected, bulunan: $actual"
        }
    }
    private fun command(
        action: SeyfiAction,
        original: String,
        response: String
    ) = SeyfiCommand(
        action = action,
        originalText = original,
        response = response
    )
}
