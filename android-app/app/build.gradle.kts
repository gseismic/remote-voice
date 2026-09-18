plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.remotevoice.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.remotevoice.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
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
}

// 设计文档 §6.3 修订（docs/design/qr-pairing-20260919-overview.md §4.1）：
// 主体保持零第三方依赖；唯一例外 zxing core（纯 Java、无传递依赖）用于扫码配对的 QR 解码
dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
