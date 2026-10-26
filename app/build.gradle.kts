plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// El número de versión sube solo con cada compilación de GitHub (-PversionCode=NN)
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.example.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.iptv"
        minSdk = 21            // Android 5.0+ (TV boxes y celulares viejos)
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
    }

    // Firma fija: el archivo de la llave llega como secreto de GitHub (KEYSTORE_PATH).
    // Sin llave (compilación local) se firma con la llave de depuración.
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = "iptvplayer-store"
                keyAlias = "iptvplayer"
                keyPassword = "iptvplayer-key"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.work:work-runtime-ktx:2.9.1")   // actualización nocturna de listas
    implementation("com.google.zxing:core:3.5.3")             // código QR para entrar desde el celular
}
