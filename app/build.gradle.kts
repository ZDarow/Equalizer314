import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.bearinmind.equalizer314"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bearinmind.equalizer314"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 100
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Load signing credentials from keystore.properties (in .gitignore)
            val keystorePropsFile = rootProject.file("keystore.properties")
            val props = if (keystorePropsFile.exists()) {
                Properties().apply { load(keystorePropsFile.inputStream()) }
            } else {
                // Fallback to project properties (CI/CD secrets, env vars)
                null
            }

            val storeFilePath = props?.getProperty("RELEASE_STORE_FILE")
                ?: project.findProperty("RELEASE_STORE_FILE") as? String
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = props?.getProperty("RELEASE_STORE_PASSWORD")
                    ?: project.findProperty("RELEASE_STORE_PASSWORD") as? String
                keyAlias = props?.getProperty("RELEASE_KEY_ALIAS")
                    ?: project.findProperty("RELEASE_KEY_ALIAS") as? String
                keyPassword = props?.getProperty("RELEASE_KEY_PASSWORD")
                    ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only use release signing config when the keystore is configured
            // (via keystore.properties or CI secrets). Otherwise build unsigned.
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // F-Droid reproducibility: AGP 8.3+ embeds VCS info by default,
            // which breaks F-Droid's build reproducibility check.
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Room schema export directory (used by Room annotation processor)
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildFeatures {
        buildConfig = true
    }

    // F-Droid rejects APKs containing the "Dependency metadata" extra signing
    // block that AGP 8.1+ embeds by default. Disable both APK and AAB variants.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.ktx)

    // Baseline Profiles — Startup optimisation
    implementation(libs.androidx.profileinstaller)

    // LeakCanary — memory leak detection (debug only)
    debugImplementation(libs.leakcanary.android)

    // LocalBroadcastManager (for MediaNotificationListener → SessionDetector communication)
    implementation(libs.androidx.localbroadcastmanager)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Room — offline preset and binding database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // (Removed unused androidx.media3-exoplayer/ui/session dependencies.
    // They were an artefact of an earlier capture-based design that
    // never shipped. The current implementation uses Android's native
    // DynamicsProcessing API and EnvironmentalReverb directly, no
    // ExoPlayer involvement. Removing them also strips the transitively-
    // added android.permission.ACCESS_NETWORK_STATE — "View network
    // connections" on the Play Store / settings UI — which was the
    // only thing that permission was there for.)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.test.ext.junit)
    testImplementation(libs.robolectric)
    // org.json mock replacement for unit tests
    testImplementation(libs.json.mock)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.intents)
}

// ---- Kover test coverage --------------------------------------------
// Report and verification configuration is minimal; the XML/HTML report
// is generated by `koverXmlReport` / `koverHtmlReport` tasks.

// ---- Detekt static analysis -----------------------------------------
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
    jvmTarget = "17"
}

// F-Droid reproducible builds: disable baseline profile generation. The
// output of baseline.prof is non-deterministic across machines and would
// cause F-Droid's build reproducibility check to fail.
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}
