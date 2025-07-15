plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.remoteapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.remoteapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // Use Java 11 for compatibility with newer Android Gradle Plugin versions
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Standard AndroidX dependencies, assuming they are defined in libs.versions.toml
    // If you don't have a libs.versions.toml, you will need to replace these with direct versions.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout) // This is the ConstraintLayout dependency

    // Specific dependencies for this project not typically in default libs.versions.toml
    // Using the latest versions we've discussed
    implementation("org.java-websocket:Java-WebSocket:1.5.4") // Latest version from your logs
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Test dependencies, assuming they are defined in libs.versions.toml
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Removed:
    // - Duplicate and conflicting versions of core-ktx, appcompat, material, constraintlayout
    // - media3-exoplayer and media3-ui (unless explicitly needed for other video playback)
    // - ktor dependencies (unless explicitly needed for other network calls, not WebSockets here)
    // - okhttp (Java-WebSocket handles its own underlying HTTP client)
}
