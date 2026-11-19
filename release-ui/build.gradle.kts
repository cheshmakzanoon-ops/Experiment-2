plugins {
    id("com.android.test")
}

android {
    namespace = "com.artflow.qualification"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    targetProjectPath = ":app"
    // The test runner instruments this APK, never the production application's process.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testApplicationId = "com.artflow.qualification"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("release") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "release" }
}

dependencies {
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
