import org.gradle.api.attributes.java.TargetJvmEnvironment

// Scratch resolver (NOT the app build).  It only asks Maven Central for the JVM flavour of the libraries the
// harness needs and prints the resolved jar paths; compile.sh/setup.sh copy them into ../libs.
plugins { base }

val kotlinPlatformType = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)

fun mk(name: String, usage: String) = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(usage))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(kotlinPlatformType, "jvm")
    }
}

// Compile classpath for the main sources: Compose Multiplatform's desktop (JVM) builds of the androidx.compose
// libraries carry the same common API as the Android builds; the "-android" artifacts on Maven Central are 6 KB
// redirects to Google Maven, which is not reachable from here.
val mainApi = mk("mainApi", Usage.JAVA_API)
// Test classpath (compile + runtime), resolved as runtime so the transitive jars come along.
val testRuntime = mk("testRuntime", Usage.JAVA_RUNTIME)

val cmp = "1.7.3"
dependencies {
    for (c in listOf(mainApi)) {
        c("org.jetbrains.compose.runtime:runtime:$cmp")
        c("org.jetbrains.compose.runtime:runtime-saveable:$cmp")
        c("org.jetbrains.compose.ui:ui:$cmp")
        c("org.jetbrains.compose.ui:ui-graphics:$cmp")
        c("org.jetbrains.compose.ui:ui-geometry:$cmp")
        c("org.jetbrains.compose.ui:ui-unit:$cmp")
        c("org.jetbrains.compose.ui:ui-text:$cmp")
        c("org.jetbrains.compose.ui:ui-util:$cmp")
        c("org.jetbrains.compose.ui:ui-tooling-preview:$cmp")
        c("org.jetbrains.compose.foundation:foundation:$cmp")
        c("org.jetbrains.compose.foundation:foundation-layout:$cmp")
        c("org.jetbrains.compose.animation:animation:$cmp")
        c("org.jetbrains.compose.animation:animation-core:$cmp")
        c("org.jetbrains.compose.material3:material3:$cmp")
        c("org.jetbrains.compose.material:material-icons-core:$cmp")
        c("org.jetbrains.compose.material:material-icons-extended:$cmp")
        c("org.jetbrains.compose.material:material-ripple:$cmp")
        c("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        c("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        c("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        c("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    }
    testRuntime("junit:junit:4.13.2")
    testRuntime("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntime("org.mockito:mockito-core:5.7.0")
    testRuntime("app.cash.turbine:turbine:1.1.0")
}

tasks.register("printResolved") {
    val out = providers.gradleProperty("outFile")
    doLast {
        val sb = StringBuilder()
        for (c in listOf(mainApi, testRuntime)) {
            c.files.forEach { sb.append(c.name).append('|').append(it).append('\n') }
            c.incoming.resolutionResult.allComponents.forEach { sb.append("# ").append(c.name).append(' ').append(it.id).append('\n') }
        }
        file(out.get()).writeText(sb.toString())
    }
}
