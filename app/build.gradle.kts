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

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }

        debug {
            isDebuggable = true
        }
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
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val expected = "96491bb0f78fa68098bf8f1d3628194c97041aff7894f8cc29303b3acdb877a9"
        if (target.exists() && target.length() >= 300L * 1024L * 1024L && hash(target) == expected) {
            logger.lifecycle("Seyfi Usta modeli APK için hazır.")
            return@doLast
        }
        target.parentFile.mkdirs()
        val partial = target.resolveSibling(target.name + ".part")
        val connection = (URL("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000; readTimeout = 180000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MOTO-DIAG-PRO-Build")
        }
        try {
            val status = connection.responseCode
            check(status in 200..299) { "Seyfi Usta model indirme HTTP hatası: $status" }
            connection.inputStream.use { input -> FileOutputStream(partial).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                output.fd.sync()
            } }
            check(partial.length() >= 300L * 1024L * 1024L) { "Seyfi Usta model dosyası eksik." }
            check(hash(partial) == expected) { "Seyfi Usta model SHA-256 doğrulaması başarısız." }
            check(partial.renameTo(target)) { "Seyfi Usta model dosyası hedefe taşınamadı." }
            logger.lifecycle("Seyfi Usta yerel modeli APK assets içine eklendi (" + target.length() + " bayt).")
        } finally { connection.disconnect(); if (partial.exists()) partial.delete() }
    }
}

tasks.named("preBuild") { dependsOn(downloadSeyfiModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // SEYFİ USTA - YEREL YAPAY ZEKÂ
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
}
