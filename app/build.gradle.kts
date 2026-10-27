import com.android.build.api.variant.SigningConfigInfo
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Release signing: keystore.properties (not in git) names the keystore and the Keychain items
// that hold its passwords. See scripts/make-release-key.sh.
val signingProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

// A lazy Keychain read: `security` runs only when a release signing task needs the password. Debug builds
// never run it, and the configuration cache keeps the command, not the password.
fun keychain(service: String): Provider<String> =
    providers.exec { commandLine("security", "find-generic-password", "-s", service, "-w") }
        .standardOutput.asText.map { it.trim() }

android {
    namespace = "app.plico"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.plico"
        minSdk = 35
        targetSdk = 37
        versionCode = 6
        versionName = "0.2.4"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release builds never contain an OAuth client ID (privacy PS9); debug builds may preset them.
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"\"")
        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo { include = false }
            // No DSL signing config: androidComponents below adds the release key, so the debug key never signs.
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProps.getProperty("plico.googleClientId", "")}\"")
            buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"${localProps.getProperty("plico.microsoftClientId", "")}\"")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // JVM unit tests run against android.jar stubs; Log and friends return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        // arm64-v8a only on purpose: Plico targets phones, not ChromeOS.
        disable += "ChromeOsAbiSupport"
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // Without a release key, the release APK stays unsigned. Local values only in the lambda, so that
        // the configuration cache can store it without the build script.
        signingProps?.let { props ->
            val storeFile = file(props.getProperty("storeFile"))
            val alias = props.getProperty("keyAlias")
            variant.signingConfig.from(
                keychain(props.getProperty("storePasswordKeychain"))
                    .zip(keychain(props.getProperty("keyPasswordKeychain"))) { store, key ->
                        SigningConfigInfo(storeFile, store, alias, key, "PKCS12")
                    },
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(project(":mail"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.webkit)
    implementation(libs.jsoup)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json is part of Android; JVM unit tests need a real copy.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(kotlin("test-junit"))
}
