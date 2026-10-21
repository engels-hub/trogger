import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: CI passes TROGGER_* env vars; locally ~/.android/trogger-release.properties is used.
val releaseSigning: Map<String, String>? = run {
    val env = System.getenv()
    if (env["TROGGER_KEYSTORE"] != null) return@run mapOf(
        "storeFile" to env.getValue("TROGGER_KEYSTORE"),
        "storePassword" to env.getValue("TROGGER_KEYSTORE_PASSWORD"),
        "keyAlias" to env.getValue("TROGGER_KEY_ALIAS"),
        "keyPassword" to env.getValue("TROGGER_KEY_PASSWORD"),
    )
    val f = File(System.getProperty("user.home"), ".android/trogger-release.properties")
    if (!f.exists()) return@run null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    p.stringPropertyNames().associateWith { p.getProperty(it) }
}

android {
    namespace = "lv.papart.trogger"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "lv.papart.trogger"
        minSdk = 26
        targetSdk = 37
        // CI sets these from the run number and the git tag.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getValue("storeFile"))
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 drops the unused bulk of material-icons-extended.
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.play.services.location)
}
