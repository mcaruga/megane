plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}

// passati da docker/rilascia.sh: -PappVersion=1.2.3 e chiave di firma stabile
val appVersion = (project.findProperty("appVersion") as String?) ?: "0.1.0"
val vParti = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val ksPath = project.findProperty("ksPath") as String?
val ksPass = project.findProperty("ksPass") as String?

android {
    namespace = "cafe.megane.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "cafe.megane.car"
        minSdk = 29          // Android Auto richiede almeno 23, 29 per BLE stabile
        targetSdk = 35
        // 1.2.3 -> 10203: cresce a ogni versione, serve ad Android per aggiornare
        versionCode = vParti.getOrElse(0) { 0 } * 10000 +
            vParti.getOrElse(1) { 0 } * 100 + vParti.getOrElse(2) { 0 }
        versionName = appVersion
    }

    // Senza una chiave stabile ogni build firma diverso e Android rifiuta l'aggiornamento.
    signingConfigs {
        create("rilascio") {
            val p = ksPath
            val w = ksPass
            if (p != null && w != null) {
                storeFile = file(p)
                storePassword = w
                keyAlias = "megane"
                keyPassword = w
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (ksPath != null) signingConfig = signingConfigs.getByName("rilascio")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // il catalogo vive in core/resources, lo serviamo come asset Android
    sourceSets["main"].assets.srcDir("${project(":core").projectDir}/src/main/resources")
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")   // parte specifica Android Auto
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
