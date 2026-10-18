plugins {
    id("com.android.application")
}

android {
    namespace = "com.thor.instantreplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thor.instantreplay"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "2.8.0"
        testInstrumentationRunner = "com.thor.instantreplay.ReplayUiInstrumentation"
    }

    signingConfigs.getByName("debug") {
        System.getenv("KHOGAME_DEBUG_KEYSTORE")?.let { storeFile = file(it) }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
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


    buildFeatures {
        viewBinding = true
    }
    sourceSets.getByName("androidTest").java.srcDir(rootProject.file("tests/standalone"))
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.zxing:core:3.5.3")
}

