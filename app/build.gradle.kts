plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.efemoto.motodiagpro"
    compileSdk = 36

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


val voskAssetsDir = layout.buildDirectory.dir("generated/voskAssets")
android.sourceSets.getByName("main").assets.srcDir(voskAssetsDir)

val prepareVoskTurkishModel by tasks.registering {
    outputs.dir(voskAssetsDir)
    doLast {
        val root = voskAssetsDir.get().asFile
        val modelDir = root.resolve("model-tr")
        val uuid = modelDir.resolve("uuid")
        if (!uuid.exists()) {
            modelDir.deleteRecursively()
            modelDir.mkdirs()
            val archive = layout.buildDirectory.file("vosk-model-small-tr-0.3.zip").get().asFile
            if (!archive.exists()) {
                java.net.URL("https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip").openStream().use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            }
            java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.substringAfter("/", "")
                    if (relative.isNotBlank()) {
                        val target = modelDir.resolve(relative)
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output -> zip.copyTo(output) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!uuid.exists()) uuid.writeText("vosk-model-small-tr-0.3")
            check(modelDir.resolve("conf").exists() && modelDir.resolve("am").exists()) {
                "Türkçe Vosk modeli eksik veya arşiv yapısı beklenenden farklı."
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(prepareVoskTurkishModel) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")


}
