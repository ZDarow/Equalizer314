import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.bearinmind.equalizer314"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bearinmind.equalizer314"
        minSdk = 28
        targetSdk = 35
        versionCode = 12
        versionName = "0.0.12-beta"

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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Baseline Profiles — Startup optimisation
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // LeakCanary — memory leak detection (debug only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // LocalBroadcastManager (for MediaNotificationListener → SessionDetector communication)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room — offline preset and binding database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // (Removed unused androidx.media3-exoplayer/ui/session dependencies.
    // They were an artefact of an earlier capture-based design that
    // never shipped. The current implementation uses Android's native
    // DynamicsProcessing API and EnvironmentalReverb directly, no
    // ExoPlayer involvement. Removing them also strips the transitively-
    // added android.permission.ACCESS_NETWORK_STATE — "View network
    // connections" on the Play Store / settings UI — which was the
    // only thing that permission was there for.)

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // org.json mock replacement for unit tests
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
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

