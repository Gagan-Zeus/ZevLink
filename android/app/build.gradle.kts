plugins {
    id("com.android.application")
}

android {
    namespace = "com.zevclip.sender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zevclip.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 36
        versionName = "4.0.3"
    }

    val releaseKeystore = file("release.jks")
    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = "zevlinkandroid"
                keyAlias = "zevlink"
                keyPassword = "zevlinkandroid"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    testImplementation("junit:junit:4.13.2")
}
