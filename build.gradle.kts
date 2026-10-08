plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" apply false
    // Dev-only: renders Compose screens to PNG on the JVM (no emulator). Not in the APK.
    id("app.cash.paparazzi") version "1.3.4" apply false
}
