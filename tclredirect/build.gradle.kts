plugins {
    id("com.android.application")
}

android {
    namespace = "ee.local.go3tvplus.tclredirect"
    compileSdk = 37

    defaultConfig {
        applicationId = "ee.local.go3tvplus.tclredirect"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.tananaev:adblib:1.3")
}
