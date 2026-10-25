plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.my.recorder"
    compileSdk = 35

    // 고정 서명 키 (매 빌드마다 같은 서명 → 기존 앱 위에 덮어 설치 가능)
    signingConfigs {
        create("fixed") {
            storeFile = file("myrecorder.keystore")
            storePassword = "myrecorder"
            keyAlias = "myrecorder"
            keyPassword = "myrecorder"
        }
    }

    defaultConfig {
        applicationId = "com.my.recorder"
        minSdk = 29          // Android 10 이상
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
