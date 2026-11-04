plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rg406v.sampler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rg406v.sampler"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Clé fixe (fichier rggg-release.jks) : les mises à jour s'installent par-dessus l'ancienne version.
    val fixedKey = file("rggg-release.jks")
    signingConfigs {
        if (fixedKey.exists()) {
            create("rggg") {
                storeFile = fixedKey
                storePassword = "rggg4666"
                keyAlias = "rggg"
                keyPassword = "rggg4666"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signé avec la clé debug : l'APK release s'installe directement, sans keystore.
            signingConfig = signingConfigs.findByName("rggg") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
