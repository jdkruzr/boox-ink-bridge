plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.booxinkbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.booxinkbridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
}

configurations.all {
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "support-annotations")
    exclude(group = "com.android.support", module = "support-v4")
}

dependencies {
    // Onyx's pen SDK, fetched from Boox's Maven repository at build time. Not part of this
    // project and not covered by its licence; see NOTICE.
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.2")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    // The Onyx SDK reaches hidden framework APIs; without this it fails on Android 14 and later.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    testImplementation("junit:junit:4.13.2")
    // The android.jar org.json is a stub that throws under JVM unit tests.
    testImplementation("org.json:json:20240303")
}
