plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.efemoto.motodiagpro"
    compileSdk = 36

    androidResources {
        noCompress += listOf("litertlm", "onnx")
    }
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

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    // Seyfi Usta: cihaz üzerinde çalışan LiteRT-LM yerel model motoru.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")

    // Sherpa-ONNX 2.0.1; AAR SHA-256 doğrulaması CI iş akışında yapılır.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    testImplementation("junit:junit:4.13.2")
}
