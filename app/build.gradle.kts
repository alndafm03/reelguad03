plugins {
    id("com.android.application")
}

android {
    namespace = "com.reelguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reelguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "4.1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
