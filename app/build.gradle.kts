plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.zxing:core:3.5.2")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}