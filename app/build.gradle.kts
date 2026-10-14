plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.karan.callassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.karan.callassistant"
        // Android 10 (API 29): the baseline where both-sides call recording was locked down,
        // which is the whole premise of this app, and where foreground-service types apply.
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    // One fixed key for every build, so a new APK installs over the old one and keeps your
    // settings and history. CI writes it from the SIGNING_KEYSTORE_B64 / SIGNING_PASSWORD
    // repository secrets; without them the build falls back to a throwaway debug key.
    val keystorePath = System.getenv("CA_KEYSTORE")
    val sharedSigning = if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs.create("shared") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("CA_STORE_PASS")
            keyAlias = "callassistant"
            keyPassword = System.getenv("CA_STORE_PASS")
        }
    } else null

    buildTypes {
        debug {
            if (sharedSigning != null) signingConfig = sharedSigning
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // Offline speech-to-text. JNA is needed as an AAR so its native libs are packaged.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
