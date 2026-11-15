plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.booxinkbridge.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.booxinkbridge.spike"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }

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
    implementation(project(":inkcontroller"))
    implementation("androidx.core:core-ktx:1.13.1")
}
