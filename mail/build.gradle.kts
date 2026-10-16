// Pure Kotlin protocol code (no Android imports). It is an Android library only so that it compiles
// against android.jar and lint checks every JDK call against minSdk (NewApi).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.plico.mail"
    compileSdk = 37
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint { abortOnError = true }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
