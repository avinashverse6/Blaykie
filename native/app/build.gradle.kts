plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val cslInRelease = providers.gradleProperty("cslInRelease").map { value ->
    require(value == "true" || value == "false") { "cslInRelease must be true or false" }
    value == "true"
}.orElse(false).get()

android {
    namespace = "org.blaykie.nativeapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.avinashverse6.blaykie"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        manifestPlaceholders["appLabel"] = "Blaykie"
    }
    signingConfigs {
        create("release") {
            storeType = "PKCS12"
            storeFile = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { file(it) }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Blaykie (debug)"
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.isFile == true &&
                !releaseSigning.storePassword.isNullOrBlank() &&
                !releaseSigning.keyAlias.isNullOrBlank() &&
                !releaseSigning.keyPassword.isNullOrBlank()
            ) {
                signingConfig = releaseSigning
            }
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.all {
            it.maxHeapSize = "2048m"
            it.testLogging {
                events("failed", "standardOut")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
    sourceSets {
        getByName("debug") {
            java.srcDir("src/csl/java")
            res.srcDir("src/csl/res")
            assets.srcDir("src/csl/assets")
        }
        getByName("release") {
            if (cslInRelease) {
                // Replace the ordinary release adapters with the feature-aware implementations.
                java.setSrcDirs(listOf("src/csl/java"))
                res.srcDir("src/csl/res")
                assets.srcDir("src/csl/assets")
            }
        }
        getByName("test").resources.srcDirs("src/debug/assets", "src/csl/assets")
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    debugImplementation("org.mozilla:rhino:1.7.15")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
