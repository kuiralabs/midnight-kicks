plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.midnight.kicks"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.midnight.kicks"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
    }
    testOptions {
        unitTests {
            // Android stubs (e.g. android.util.Log) throw on JVM by default,
            // which makes any logging code unreachable in unit tests.
            // Returning default values lets pure-logic tests run without
            // Robolectric or static mocking.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Unity as a Library
    implementation(project(":unityLibrary"))

    // Kuira SDK — consumed as Maven artifacts published to mavenLocal by the
    // parent project (`./gradlew publishToMavenLocal`). POMs include all
    // transitive deps (zxing, bitcoinj, ktor, room, credentials, etc.) so
    // kicks no longer redeclares them. To pull in a new Kuira module, add a
    // single line below — no AAR copy, no transitive bookkeeping.
    implementation("com.midnight.kuira:common:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:midnight-sdk:0.1.0-SNAPSHOT")
    // SDK uses `implementation(project(":core:*"))` so those types aren't
    // exposed to its consumers transitively. Kicks references compact types
    // directly (MidnightContract, MidnightConfig, WitnessResult) and the
    // network enum, so declare them here.
    implementation("com.midnight.kuira:compact-engine:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:network:0.1.0-SNAPSHOT")

    // AndroidX directly used by Kicks's own code (FragmentActivity host,
    // Compose). Things Kuira pulls in transitively (biometric, credentials,
    // room, etc.) come through the SDK/common POMs and don't need to be here.
    implementation("androidx.fragment:fragment-ktx:1.8.4")  // FragmentActivity for biometric prompts
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Tests (JVM unit). MatchState / ContractStateSnapshot / pure helpers
    // live here. JSON parsing uses Android's org.json, so we need its
    // testing stub via robolectric OR we keep tests independent of org.json.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303") // shadow Android's org.json on JVM
}
