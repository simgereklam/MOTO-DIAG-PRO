import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.efemoto.motodiagpro"
    compileSdk = 36

    androidResources {
        noCompress += "litertlm"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/seyfiAssets"))

    defaultConfig {
        applicationId = "com.efemoto.motodiagpro"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val downloadSeyfiModel by tasks.registering {
    val modelDir = layout.buildDirectory.dir("generated/seyfiAssets")
    val modelFile = modelDir.map { it.file("seyfi_model.litertlm") }
    outputs.file(modelFile)

    doLast {
        val target = modelFile.get().asFile
        val expectedSha256 = "96491bb0f78fa68098bf8f1d3628194c97041aff7894f8cc29303b3acdb877a9"
        val expectedBytes = 344686592L
        val modelUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        fun isValid(file: File): Boolean =
            file.isFile && file.length() == expectedBytes &&
                sha256(file).equals(expectedSha256, ignoreCase = true)

        if (isValid(target)) {
            logger.lifecycle("Seyfi Usta modeli doğrulandı ve APK assets klasöründe hazır.")
            return@doLast
        }

        target.parentFile.mkdirs()
        val partial = target.resolveSibling(target.name + ".part")
        var lastFailure = "Bilinmeyen indirme hatası"

        // Hugging Face Xet/CDN bazen eski veya eksik yanıt döndürebiliyor.
        // Her denemede önbelleği devre dışı bırak ve dosyanın boyutunu + SHA-256 değerini doğrula.
        for (attempt in 1..3) {
            if (partial.exists()) partial.delete()
            var connection: HttpURLConnection? = null
            try {
                logger.lifecycle("Seyfi Usta modeli indiriliyor (deneme $attempt/3)...")
                val activeConnection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 240000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "MOTO-DIAG-PRO-Build/1.0")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }
                connection = activeConnection

                val status = activeConnection.responseCode
                check(status in 200..299) {
                    "Hugging Face HTTP $status: ${activeConnection.responseMessage}"
                }

                val advertisedLength = activeConnection.contentLengthLong
                if (advertisedLength > 0L) {
                    check(advertisedLength == expectedBytes) {
                        "Sunucu beklenmeyen dosya boyutu bildirdi: $advertisedLength bayt; beklenen: $expectedBytes"
                    }
                }

                activeConnection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }

                val actualBytes = partial.length()
                check(actualBytes == expectedBytes) {
                    "Model indirme boyutu hatalı. Gelen: $actualBytes bayt; beklenen: $expectedBytes bayt."
                }

                val actualSha256 = sha256(partial)
                check(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    "Model SHA-256 doğrulaması başarısız. Gelen: $actualSha256; beklenen: $expectedSha256."
                }

                check(partial.renameTo(target)) {
                    "Doğrulanan model APK assets klasörüne taşınamadı."
                }

                logger.lifecycle("Seyfi Usta modeli doğrulandı: $actualBytes bayt, SHA-256 doğru.")
                return@doLast
            } catch (error: Exception) {
                lastFailure = error.message ?: error.javaClass.simpleName
                logger.warn("Seyfi Usta model indirme denemesi $attempt başarısız: $lastFailure")
            } finally {
                connection?.disconnect()
            }
        }

        if (partial.exists()) partial.delete()
        throw GradleException("Seyfi Usta modeli güvenli şekilde indirilemedi. 3 deneme yapıldı. Son hata: $lastFailure")
    }
}

tasks.named("preBuild") {
    dependsOn(downloadSeyfiModel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Seyfi Usta: cihaz üzerinde çalışan LiteRT-LM yerel model motoru.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
}
