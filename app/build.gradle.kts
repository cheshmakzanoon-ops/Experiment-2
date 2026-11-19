import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations
import java.util.Properties
import javax.inject.Inject

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

// Preserve the complete debug matrix unless an explicit optimized qualification is requested.
// `benchmark` inherits release shrinking and uses the disposable Android debug signing key only.
val instrumentationBuildType = providers.gradleProperty("artflowTestBuildType").orElse("debug").get()
check(instrumentationBuildType in setOf("debug", "benchmark")) {
    "artflowTestBuildType must be debug or benchmark"
}

android {
    namespace = "com.artflow.studio"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    testBuildType = instrumentationBuildType

    defaultConfig {
        applicationId = "com.artflow.studio"
        minSdk = 26
        // Google Play requires new apps and updates to target Android 16 (API 36) as of 2026-08-31.
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.1"

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
            // Test artifact only: publisher signing is qualified separately against release.
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Only the instrumentation APK needs these absent compile-time annotation types.
            testProguardFiles("proguard-android-test.pro")
        }
    }

    // androidTest sources compile together even when the runner selects only some classes.
    // Both tested variants therefore need this real-View host; publisher release excludes it.
    listOf("debug", "benchmark").forEach { variant ->
        sourceSets.getByName(variant) {
            java.srcDir("src/deviceTestHost/java")
            manifest.srcFile("src/deviceTestHost/AndroidManifest.xml")
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

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
    constraints {
        implementation("androidx.compose.foundation:foundation:1.9.0") {
            // UI 1.9 is selected by Lifecycle; debug tooling also lifts Foundation/Animation.
            // Keep the same input and layout implementations in optimized and publisher builds.
            because("Align release Foundation and Animation with the Compose UI and debug runtime")
        }
        implementation("com.google.guava:guava:33.8.0-android") {
            // Media3 otherwise selects 33.3.1-android; keep its Android-compatible flavor.
            because("Fix CVE-2026-102554 deserialization allocation and CVE-2026-118042 hash flooding")
        }
        implementation("com.squareup.okhttp3:okhttp:4.12.1") {
            // Coil uses local files today; keep its dormant HTTP dependency maintained.
            because("Block compromised eMudhra intermediates on devices without the Play Services trust update")
        }
    }
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
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Project file serialization (.artflow documents)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.10.2")
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
    // createComposeRule requires its host Activity in the tested APK for either selected variant.
    // Hilt test components continue to use androidTestImplementation / kspAndroidTest above.
    add("benchmarkImplementation", "androidx.compose.ui:ui-test-manifest")
}

/** Inputs come from AGP's public pre-R8 class artifact API, including test library callers. */
abstract class GenerateBenchmarkTestAbiRules : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDirectories: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testDirectories: ListProperty<Directory>

    @get:Classpath
    abstract val libraries: ListProperty<RegularFile>

    @get:Classpath
    abstract val generatorClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val arguments = mutableListOf("--output", outputDirectory.get().asFile.absolutePath)

        fun add(
            option: String,
            inputs: List<File>,
        ) {
            inputs.sortedBy { it.absolutePath }.forEach { file ->
                check(!file.absolutePath.contains('\n') && !file.absolutePath.contains('\r')) { "Invalid bytecode input path" }
                arguments.addAll(listOf(option, file.absolutePath))
            }
        }
        add("--app-input", appJars.get().map { it.asFile } + appDirectories.get().map { it.asFile })
        add("--test-input", testJars.get().map { it.asFile } + testDirectories.get().map { it.asFile })
        add("--library", libraries.get().map { it.asFile })
        val argumentFile = temporaryDir.resolve("arguments.txt")
        argumentFile.writeText(arguments.joinToString("\n", postfix = "\n"))
        execOperations
            .javaexec {
                classpath = generatorClasspath
                mainClass.set("artflow.build.TestAbiRules")
                args("--arguments", argumentFile.absolutePath)
                maxHeapSize = "2g"
            }.assertNormalExitValue()
    }
}

val components = extensions.getByType<ApplicationAndroidComponentsExtension>()
components.onVariants(components.selector().withBuildType("benchmark")) { variant ->
    // No test boundary is introduced into a publisher release or an unselected benchmark variant.
    val androidTest = variant.androidTest ?: return@onVariants
    val r8Jar =
        File(
            Class
                .forName("com.android.tools.r8.tracereferences.TraceReferences")
                .protectionDomain.codeSource.location
                .toURI(),
        )
    val compileGenerator =
        tasks.register<JavaCompile>("compileBenchmarkTestAbiGenerator") {
            source(rootProject.file("tools/optimized-test-abi/src"))
            classpath = files(r8Jar)
            destinationDirectory.set(layout.buildDirectory.dir("intermediates/benchmark-test-abi-generator"))
            options.release.set(17)
            options.encoding = "UTF-8"
        }
    tasks.register<Exec>("verifyBenchmarkTestAbiGenerator") {
        group = "verification"
        description = "Exercise boundary extraction and separate app/test R8 rewriting with executable fixtures"
        dependsOn(compileGenerator)
        commandLine(
            "python3",
            rootProject.file("tools/optimized-test-abi/verify.py"),
            "--java-home",
            System.getProperty("java.home"),
            "--r8-tool",
            r8Jar,
            "--generator-classes",
            compileGenerator.flatMap { it.destinationDirectory }.get().asFile,
        )
    }
    val generate =
        tasks.register<GenerateBenchmarkTestAbiRules>("generateBenchmarkTestAbiRules") {
            group = "verification"
            description = "Trace exact external instrumentation references into the optimized benchmark app"
            libraries.set(components.sdkComponents.bootClasspath)
            generatorClasspath.from(compileGenerator.flatMap { it.destinationDirectory }, r8Jar)
            outputDirectory.set(layout.buildDirectory.dir("reports/release-qualification/test-abi"))
        }
    tasks.named<Exec>("verifyBenchmarkTestAbiGenerator") {
        dependsOn(generate)
        args(
            "--application-input",
            generate.flatMap { it.outputDirectory.file("app-input.jar") }.get().asFile,
            "--report-directory",
            layout.buildDirectory
                .dir("reports/release-qualification/test-abi-fixtures")
                .get()
                .asFile,
        )
    }
    variant.artifacts
        .forScope(ScopedArtifacts.Scope.ALL)
        .use(generate)
        .toGet(ScopedArtifact.CLASSES, GenerateBenchmarkTestAbiRules::appJars, GenerateBenchmarkTestAbiRules::appDirectories)
    androidTest.artifacts
        .forScope(ScopedArtifacts.Scope.ALL)
        .use(generate)
        .toGet(ScopedArtifact.CLASSES, GenerateBenchmarkTestAbiRules::testJars, GenerateBenchmarkTestAbiRules::testDirectories)
    variant.proguardFiles.add(generate.flatMap { it.outputDirectory.file("keep-rules.pro") })
    variant.proguardFiles.add(layout.projectDirectory.file("proguard-benchmark-test-reflection.pro"))
}

val qualificationVariantRules = objects.listProperty(RegularFile::class.java)
val publisherVariantRules = objects.listProperty(RegularFile::class.java)
androidComponents.onVariants { variant ->
    when (variant.buildType) {
        "benchmark" -> qualificationVariantRules.set(variant.proguardFiles)
        "release" -> publisherVariantRules.set(variant.proguardFiles)
    }
}

tasks.register("verifyReleaseQualificationBuildType") {
    group = "verification"
    description = "Rejects non-optimized or publisher-signed instrumentation qualification variants"
    val report = layout.buildDirectory.file("reports/release-qualification/build-contract.json")
    outputs.file(report)
    // This contract inspects live configuration, including calls made by an applied build script.
    outputs.upToDateWhen { false }
    if (instrumentationBuildType == "benchmark") dependsOn("generateBenchmarkTestAbiRules", "verifyBenchmarkTestAbiGenerator")
    doLast {
        val selected = android.buildTypes.getByName("benchmark")
        val production = android.buildTypes.getByName("release")
        check(android.testBuildType == "benchmark") { "Select -PartflowTestBuildType=benchmark for optimized qualification" }
        check(selected.isMinifyEnabled && selected.isShrinkResources && !selected.isDebuggable) {
            "Qualification requires release R8, resource shrinking and a non-debuggable application"
        }
        check(selected.signingConfig?.name == "debug") { "Qualification must use the disposable test signing configuration" }
        check(selected.proguardFiles == production.proguardFiles) { "Qualification must inherit the publisher's base R8 rules" }
        val testAbiRules =
            layout.buildDirectory
                .file("reports/release-qualification/test-abi/keep-rules.pro")
                .get()
                .asFile
        val reflectionRules = file("proguard-benchmark-test-reflection.pro")
        val expectedRules = publisherVariantRules.get().map { it.asFile } + listOf(testAbiRules, reflectionRules)
        check(qualificationVariantRules.get().map { it.asFile } == expectedRules) {
            "Benchmark may add only the generated test ABI and the audited reflection fields to publisher rules"
        }
        check(testAbiRules.isFile && testAbiRules.length() > 0) { "Missing traced instrumentation ABI rules" }
        val destination = report.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            """
            {
              "schemaVersion": 2,
              "buildType": "benchmark",
              "minifyEnabled": true,
              "shrinkResources": true,
              "debuggable": false,
              "signingConfig": "debug",
              "publisherReleaseRulesUnchanged": true,
              "benchmarkAddsGeneratedTestAbiRules": true,
              "benchmarkAddsAuditedReflectionFields": true,
              "testAbiAllowsObfuscation": false,
              "publisherQualified": false
            }
            """.trimIndent() + "\n",
        )
    }
}

// Local unit tests run on the host JDK. Keep its independent ImageIO decoders available to
// test exported PNG/GIF files; production and device-test compilation remain Android-only.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) {
        compilerOptions.noJdk.set(false)
    }
}

// Optional local corpora change the assertions that execute. Fingerprint configured inputs so a
// previous skipped result cannot satisfy a run with a corpus, and let Gradle reject missing paths.
// Only input fingerprints enter the task cache; reference archives are never task outputs.
tasks.withType<Test>().configureEach {
    val referenceIpa = providers.environmentVariable("ARTFLOW_REFERENCE_IPA").orNull?.takeIf { it.isNotBlank() }
    inputs.property("referenceIpaConfigured", referenceIpa != null)
    referenceIpa?.let {
        inputs.file(it).withPropertyName("referenceIpa").withPathSensitivity(PathSensitivity.NONE)
    }

    val procreateSamples = providers.environmentVariable("PROCREATE_SAMPLES").orNull?.takeIf { it.isNotBlank() }
    inputs.property("procreateSamplesConfigured", procreateSamples != null)
    procreateSamples?.let {
        inputs.dir(it).withPropertyName("procreateSamples").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.property("requireProcreateSamples", providers.environmentVariable("CI").map { it == "true" }.orElse(false))
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

// Freeze the resolved release runtime graph. Generate/review lock changes separately from
// inventory publication: :app:dependencies --configuration releaseRuntimeClasspath --write-locks.
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
dependencyLocking {
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

apply(from = rootProject.file("gradle/release-dependencies.gradle.kts"))
