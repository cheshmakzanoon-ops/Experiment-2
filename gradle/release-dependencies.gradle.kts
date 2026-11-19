import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

// Inventory the selected release inputs, before R8/shrinking. This is not an inventory of the
// final APK's bytes, build plugins, KSP processors, debug dependencies, or test dependencies.
fun dependencySha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun dependencyPomName(coordinate: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(coordinate.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".pom"

fun Element.dependencyChild(name: String): Element? =
    (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .firstOrNull { it.localName == name }

fun dependencyParentPom(file: File): String? {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
    val parent =
        factory
            .newDocumentBuilder()
            .parse(file)
            .documentElement
            .dependencyChild("parent") ?: return null
    val parts =
        listOf("groupId", "artifactId", "version").map {
            parent
                .dependencyChild(it)
                ?.textContent
                ?.trim()
                .orEmpty()
        }
    // Property expressions and dynamic parent versions require review; never guess their values.
    return parts.takeIf { values -> values.all { it.matches(Regex("[A-Za-z0-9_.-]+")) } }?.joinToString(":")
}

tasks.register("writeReleaseDependencyInventory") {
    group = "verification"
    description = "Record the resolved release runtime graph, artifact SHA-256s and raw Maven POM evidence."
    val reportDirectory = layout.buildDirectory.dir("reports/dependencies")
    val reportFile = reportDirectory.map { it.file("release-runtime.json") }
    val pomDirectory = reportDirectory.map { it.dir("release-poms") }
    outputs.file(reportFile)
    outputs.dir(pomDirectory)
    // Re-resolve on every request. A report from an earlier graph must never satisfy this gate.
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Resolves supplementary Maven POM evidence during task execution")

    doLast {
        val output = reportFile.get().asFile
        output.delete()
        val pomOutput = pomDirectory.get().asFile
        pomOutput.deleteRecursively()
        check(pomOutput.mkdirs()) { "Cannot create POM evidence directory" }

        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val resolution = configuration.incoming.resolutionResult
        val unresolved = resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
        check(unresolved.isEmpty()) {
            "Unresolved release dependencies: ${unresolved.map { it.requested.displayName }.sorted().joinToString()}"
        }
        val rootId = resolution.rootComponent.get().id
        val rootRef = "project:${project.path}"

        fun reference(id: ComponentIdentifier): String =
            when {
                id == rootId -> rootRef
                id is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                else -> error("Unsupported release component must be inventoried explicitly: $id")
            }

        val modules =
            resolution.allComponents
                .filter { it.id != rootId }
                .associateBy { reference(it.id) }
                .toSortedMap()
        check(modules.isNotEmpty()) { "Resolved release dependency graph is empty" }
        // ArtifactCollection is deliberately strict: unresolved artifacts fail, including artifacts
        // that are only reached transitively. Local/project artifacts cannot silently disappear.
        val artifacts =
            configuration.incoming.artifacts.artifacts
                .groupBy { reference(it.id.componentIdentifier) }
        check(artifacts.keys.all { it in modules }) { "Release artifacts exist outside the resolved module graph" }
        check(artifacts.isNotEmpty()) { "Resolved release artifact inventory is empty" }

        val poms = sortedMapOf<String, Map<String, Any>>()

        fun collectPom(
            coordinate: String,
            depth: Int = 0,
        ) {
            if (coordinate in poms) return
            poms[coordinate] = mapOf("id" to coordinate, "status" to "unresolved", "reason" to "POM unavailable")
            if (depth > 20) return
            val parts = coordinate.split(':')
            val query =
                dependencies
                    .createArtifactResolutionQuery()
                    .forModule(parts[0], parts[1], parts[2])
                    .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                    .execute()
            val files =
                query.resolvedComponents
                    .flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
                    .filterIsInstance<ResolvedArtifactResult>()
            if (files.size != 1) return
            val source = files.single().file
            val name = dependencyPomName(coordinate)
            source.copyTo(File(pomOutput, name), overwrite = true)
            poms[coordinate] =
                mapOf(
                    "id" to coordinate,
                    "status" to "resolved",
                    "path" to "release-poms/$name",
                    "sha256" to dependencySha256(source),
                    "size" to source.length(),
                )
            // Preserve malformed POM evidence, but do not infer its parent or a license. The Python
            // verifier parses the hashed copy independently and reports missing/invalid evidence.
            val parent = runCatching { dependencyParentPom(source) }.getOrNull()
            if (parent != null) collectPom(parent, depth + 1)
        }

        modules.keys.forEach { collectPom(it) }
        val components =
            modules.map { (coordinate, component) ->
                val id = component.id as ModuleComponentIdentifier
                mapOf(
                    "id" to coordinate,
                    "group" to id.group,
                    "name" to id.module,
                    "version" to id.version,
                    "pomId" to coordinate,
                    "artifacts" to
                        artifacts[coordinate]
                            .orEmpty()
                            .map { artifact ->
                                mapOf(
                                    "fileName" to artifact.file.name,
                                    "sha256" to dependencySha256(artifact.file),
                                    "size" to artifact.file.length(),
                                    "variant" to artifact.variant.displayName,
                                )
                            }.distinct()
                            .sortedBy { "${it["fileName"]}:${it["sha256"]}:${it["variant"]}" },
                )
            }
        val edges =
            resolution.allDependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .map {
                    mapOf(
                        "from" to reference(it.from.id),
                        "to" to reference(it.selected.id),
                        "requested" to it.requested.displayName,
                        "constraint" to it.isConstraint,
                    )
                }.distinct()
                .sortedBy { "${it["from"]}:${it["to"]}:${it["requested"]}:${it["constraint"]}" }
        val inputPaths =
            listOf(
                "build.gradle.kts",
                "settings.gradle.kts",
                "app/build.gradle.kts",
                "gradle.properties",
                "gradle/wrapper/gradle-wrapper.properties",
                "gradle/release-dependencies.gradle.kts",
                "gradle/libs.versions.toml",
                "gradle/verification-metadata.xml",
                "gradle.lockfile",
                "app/gradle.lockfile",
            )
        val buildInputs = inputPaths.filter { rootProject.file(it).isFile }.associateWith { dependencySha256(rootProject.file(it)) }
        val inventory =
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.name,
                "project" to project.path,
                "root" to rootRef,
                "buildInputs" to buildInputs.toSortedMap(),
                "components" to components,
                "edges" to edges,
                "poms" to poms.values.toList(),
                "unresolved" to emptyList<String>(),
            )
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(inventory)) + "\n", Charsets.UTF_8)
        logger.lifecycle("Recorded ${components.size} release components and ${artifacts.values.sumOf { it.size }} artifacts in $output")
    }
}
