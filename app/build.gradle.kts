import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI env vars (base64 keystore from GitHub secrets) > local keystore > debug fallback.
// Reference: same signing strategy as PocketSite.
val ciKeystoreB64 = System.getenv("KEYSTORE_BASE64")
val ciStorePass = System.getenv("KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("KEY_ALIAS")
val ciKeyPass = System.getenv("KEY_PASSWORD")
val hasCiSigning = !ciKeystoreB64.isNullOrBlank() && !ciStorePass.isNullOrBlank()

val localKs = rootProject.file("app/local-audio-release.jks")
val localSecrets = rootProject.file(".secrets")
fun secret(name: String): String? =
    runCatching {
        val f = localSecrets.resolve("$name.txt")
        if (f.exists()) f.readText().trim().ifBlank { null } else null
    }.getOrNull()
val useLocalSigning = !hasCiSigning && localKs.exists() && secret("KEYSTORE_PASSWORD") != null

android {
    namespace = "com.samge.kcourseaudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.samge.kcourseaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "v1.0.0"
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                val tmp = File.createTempFile("local-audio", ".jks")
                tmp.writeBytes(Base64.getDecoder().decode(ciKeystoreB64))
                storeFile = tmp
                storePassword = ciStorePass
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPass ?: ciStorePass
            }
        } else if (useLocalSigning) {
            create("localRelease") {
                storeFile = localKs
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS") ?: "local-audio"
                keyPassword = secret("KEY_PASSWORD") ?: secret("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                hasCiSigning -> signingConfigs.getByName("ci")
                useLocalSigning -> signingConfigs.getByName("localRelease")
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            // local test builds use the release key when present — same signature as
            // GitHub releases, so they install as updates over release APKs
            if (useLocalSigning) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
