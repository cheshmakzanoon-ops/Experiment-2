import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("com.google.devtools.ksp")
}

/**
 * Release signing.
 *
 * Create a git-ignored `keystore.properties` at the repository root with:
 * ```
 * storeFile=/absolute/path/to/artflow-release.keystore
 * storePassword=…
 * keyAlias=artflow
 * keyPassword=…
 * ```
 * Without it, `assembleRelease` still runs and produces an unsigned APK, so a fresh clone can
 * always build; with it, the release output is signed and installable.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }
val hasReleaseSigning =
    listOf(
        "storeFile",
        "storePassword",
        "keyAlias",
        "keyPassword",
    ).all { !keystoreProperties.getProperty(it).isNullOrBlank() }

// CI may build unsigned candidates, but a production request must never succeed unsigned.
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
check(!keystorePropertiesFile.exists() || hasReleaseSigning) {
    "keystore.properties is incomplete: storeFile, storePassword, keyAlias and keyPassword are required"
}
check(!requireReleaseSigning || hasReleaseSigning) {
    "Production signing is required. Configure the private keystore.properties file before release."
}
if (hasReleaseSigning) {
    check(rootProject.file(keystoreProperties.getProperty("storeFile")).isFile) {
        "The configured release keystore does not exist"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.artflow.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.artflow.studio"
        minSdk = 26
        // Google Play requires new apps and updates to target Android 16 (API 36) as of 2026-08-31.
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "com.artflow.studio.HiltTestRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
        aidl = false
        buildConfig = true
        renderScript = false
        resValues = false
    }

    // With Kotlin 2.0 the Compose compiler is configured by `org.jetbrains.kotlin.plugin.compose`
    // above; no `composeOptions` block is needed.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Portable MP4 container writing; no player, networking, or native codec dependency.
    implementation("androidx.media3:media3-muxer:1.10.1")
    // Update the transitive path library for current 16 KB native alignment.
    implementation("androidx.graphics:graphics-path:1.1.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Coroutines & Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Project file serialization (.artflow documents)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.54")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.54")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------------------------
// Static analysis
// ---------------------------------------------------------------------------------------------

ktlint {
    // Rules are configured through the root .editorconfig (Kotlin official style, Android
    // conventions, 140-column lines and the Compose wildcard-import exception).
    version.set("1.3.1")
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { element -> element.file.path.contains("generated/") }
    }
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    // Pre-existing findings (codec complexity, broad catches in IO boundaries, etc.) are
    // recorded in the baseline so `detekt` fails only on NEW violations.
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

// `./gradlew check` runs ktlint and detekt on the app module. The Kotlin/Android lint
// tasks (`lintDebug`, `lintRelease`) are appended when they exist because `check` already
// depends on them in plain Gradle; registering explicitly keeps the alias single-sourced.
tasks.named("check") {
    dependsOn(tasks.matching { it.name in setOf("ktlintKotlinScriptCheck", "ktlintMainSourceSetCheck", "ktlintTestSourceSetCheck") })
    dependsOn("detekt")
}
