// Kotlin plugins are declared once here so every module shares one Kotlin Gradle plugin.
// The Android Gradle plugin is versioned in :app only, so ':core' can still be built on its own
// (./gradlew -PcoreOnly :core:test) on machines without access to Google's Maven repository.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
