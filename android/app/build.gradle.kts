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
        versionCode = 15
        versionName = "1.5.0"
    }

    buildTypes {
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
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    testImplementation("junit:junit:4.13.2")
}
