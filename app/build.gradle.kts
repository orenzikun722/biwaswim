plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rencon.biwaswim"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rencon.biwaswim"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        versionCode =
            providers.gradleProperty("versionCode")
                .orElse("1")
                .get()
                .toInt()
        versionName =
            providers.gradleProperty("versionName")
                .orElse("1.0")
                .get()
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
    implementation("org.maplibre.gl:android-sdk:13.0.2")
}