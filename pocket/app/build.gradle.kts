import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Set these environment variables to produce a signed release build.
// GitHub Actions populates them from repository secrets.
val keystorePath: String? = System.getenv("POCKET_KEYSTORE_PATH")

android {
    namespace = "dev.rustbook.pocket"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "dev.rustbook.pocket"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        // Used by the "check for updates" action. Leave empty to hide it.
        val updateRepo = providers.gradleProperty("pocket.updateRepo").orElse("").get()
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("POCKET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKET_KEY_ALIAS")
                keyPassword = System.getenv("POCKET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false

        // This app is distributed as an APK from GitHub Releases, not through
        // Google Play. targetSdk 28 is deliberate: it keeps the legacy
        // behaviour that lets an app execute files it wrote itself, which is
        // what makes the on-device Rust toolchain possible. See
        // pocket/docs/local-execution.md.
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }

        // Only needed for debug builds: the exec probe checks whether native
        // libraries can be executed from the APK's extraction directory.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        noCompress += "zip"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json for JVM tests; the android.jar stubs throw at runtime.
    testImplementation(libs.json)
}

// The book bundle is generated, not committed. Warn early instead of shipping
// an app that cannot open its content.
val verifyBookBundle by tasks.registering {
    val index = layout.projectDirectory.file("src/main/assets/book/index.html").asFile
    outputs.upToDateWhen { false }
    doFirst {
        if (!index.exists()) {
            logger.warn(
                "Rust Book Pocket: no book bundle found at ${index.path}. " +
                    "Run `pocket/scripts/build-book.sh` (or the .ps1 equivalent) before installing.",
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyBookBundle)
}
