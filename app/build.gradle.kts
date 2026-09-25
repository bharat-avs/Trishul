plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.trishul"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.trishul"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Hardware-backed TEE Cryptographic Storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}