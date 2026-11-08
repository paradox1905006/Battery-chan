plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tg.batterychan"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tg.batterychan"
        minSdk = 29
        targetSdk = 36
        versionCode = 110
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8: code shrinking, obfuscation and resource shrinking. Keep rules live in
            // app/src/main/keepRules/*.keep.
            optimization {
                enable = true
            }
        }
    }

    // The app UI is English only; drop the translated strings pulled in by AndroidX/Material.
    androidResources {
        localeFilters += listOf("en")
    }

    // Don't embed the dependency-metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
