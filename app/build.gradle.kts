plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.sincro.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "es.sincro.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = 22
        versionName = "1.21"
    }
    // Firma: en GitHub Actions viene de los secretos del repositorio; en local, de app/sincro.keystore (no se sube)
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "sincro.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "sincrotv"
            keyAlias = System.getenv("KEY_ALIAS") ?: "sincro"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "sincrotv"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.all { it.environment("LIVE", System.getenv("LIVE") ?: "0"); System.getenv("COUNTRIES")?.let { c -> it.environment("COUNTRIES", c) }; listOf("CANALES_CSV", "ST_CACHE", "REPORT").forEach { k -> System.getenv(k)?.let { v -> it.environment(k, v) } }; it.maxHeapSize = "3g"; it.testLogging { showStandardStreams = true } } }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
