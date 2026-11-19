plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 고정 서명 키. 환경 변수가 있으면 그 키로, 없으면 디버그 키로 서명한다.
//   KEYSTORE_FILE      키스토어 파일 경로 (PKCS12)
//   KEYSTORE_PASSWORD  키스토어 비밀번호 (키 비밀번호와 같다)
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasFixedKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "io.github.yushinjung.picklewhip"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yushinjung.picklewhip"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.1.6"
    }

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "aiwhip"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
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
