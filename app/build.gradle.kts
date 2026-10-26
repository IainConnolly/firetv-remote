import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build installs as an update over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Release signing comes from key.properties at the repo root (git-ignored), which
// names a keystore kept outside the repo. Without it, release builds fall back to
// the debug key; scripts\resign.ps1 re-signs those with the release key.
val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.iainconnolly.firetvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iainconnolly.firetvremote"
        minSdk = 22
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (keyProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
