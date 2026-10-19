plugins {
    id("com.android.application")
}

val thorqolTrial = providers.gradleProperty("thorqolTrial").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.thor.instantreplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thor.instantreplay" + if (thorqolTrial) ".trial" else ""
        manifestPlaceholders["appLabel"] = "Android Replay" + if (thorqolTrial) " Trial" else ""
        buildConfigField("boolean", "THORQOL_TRIAL", thorqolTrial.toString())
        minSdk = 29
        targetSdk = 34
        versionCode = providers.gradleProperty("thorqolVersionCode").orNull?.toInt() ?: 15
        versionName = (providers.gradleProperty("thorqolVersionName").orNull ?: "2.9.3") + if (thorqolTrial) "-trial" else ""
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

    if (thorqolTrial) {
        sourceSets.getByName("debug").assets.srcDir("src/trial/assets")
        sourceSets.getByName("release").assets.srcDir("src/trial/assets")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
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

