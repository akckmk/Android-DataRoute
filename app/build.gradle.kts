plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.dataroute.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dataroute.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Builds are signed with the owner's private key when CI provides it through GitHub Secrets
    // (DATAROUTE_KEYSTORE_FILE / DATAROUTE_KEYSTORE_PASSWORD). The key never lives in this public repo: anyone
    // holding it could publish an "update" that inherits the app's VPN permission. Without it (local builds,
    // forks) Android's per-machine debug key is used, which is fine for testing.
    val ownerKeystore = System.getenv("DATAROUTE_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ownerKeystore != null) {
            create("owner") {
                storeFile = ownerKeystore
                storePassword = System.getenv("DATAROUTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DATAROUTE_KEY_ALIAS") ?: "dataroute"
                keyPassword = System.getenv("DATAROUTE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ownerKeystore != null) signingConfig = signingConfigs.getByName("owner")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ownerKeystore != null) "owner" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
