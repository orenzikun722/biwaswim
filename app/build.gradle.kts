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
        minSdk = 26
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
    signingConfigs {
        create("release") {
            val keystoreFile = file("${rootProject.projectDir}/release-key.jks")

            storeFile = keystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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
    implementation(libs.usb.serial.for1.android)
    implementation(libs.android.sdk)
    implementation(libs.jts.core)
    implementation(libs.jts.io.common)
}