package com.artflow.studio

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** UTP copies additional output before uninstalling the packages, even after an app crash. */
internal object InstalledApkEvidence {
    private const val BUFFER_BYTES = 64 * 1024
    private const val BYTE_MASK = 255
    private const val HEX_RADIX = 16
    private const val TOKEN_ARGUMENT = "artflowApkEvidenceToken"
    private val tokenPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    fun capture(instrumentation: Instrumentation) {
        val token = InstrumentationRegistry.getArguments().getString(TOKEN_ARGUMENT) ?: return
        require(tokenPattern.matches(token)) { "Invalid APK evidence attempt token" }
        val root = File(TestEvidence.directory(), "apk-provenance/$token")
        check(!root.exists() && root.mkdirs()) { "APK evidence attempt already exists or cannot be created" }
        val manifest =
            JSONObject()
                .put("schemaVersion", 1)
                .put("attemptToken", token)
                .put("capturedAtUtc", Instant.now().toString())
                .put("pid", Process.myPid())
                .put("packages", JSONObject())
                .put("errors", JSONArray())
        writeManifest(root, manifest)
        capturePackage(root, manifest, "com.artflow.studio", instrumentation.targetContext.applicationInfo)
        capturePackage(root, manifest, "com.artflow.studio.test", instrumentation.context.applicationInfo)
    }

    private fun capturePackage(
        root: File,
        manifest: JSONObject,
        expectedPackage: String,
        info: ApplicationInfo,
    ) {
        val record =
            JSONObject()
                .put("packageName", info.packageName)
                .put("apks", JSONArray())
                .put("errors", JSONArray())
        manifest.getJSONObject("packages").put(expectedPackage, record)
        try {
            check(info.packageName == expectedPackage) { "Unexpected installed package: ${info.packageName}" }
            val sources = listOf(requireNotNull(info.sourceDir)) + info.splitSourceDirs.orEmpty()
            check(sources.distinct().size == sources.size) { "Duplicate installed APK paths" }
            sources.forEachIndexed { index, source ->
                val relative = "installed/$expectedPackage/${index.toString().padStart(2, '0')}.apk"
                captureApk(root, manifest, record.getJSONArray("apks"), source, relative)
            }
        } catch (error: Exception) {
            record.getJSONArray("errors").put(errorRecord(error))
        }
        writeManifest(root, manifest)
    }

    private fun captureApk(
        root: File,
        manifest: JSONObject,
        apks: JSONArray,
        sourcePath: String,
        relative: String,
    ) {
        val record =
            JSONObject()
                .put("devicePath", sourcePath)
                .put("file", relative)
                .put("sizeBytes", JSONObject.NULL)
                .put("sha256", JSONObject.NULL)
                .put("captureComplete", false)
                .put("errors", JSONArray())
        apks.put(record)
        writeManifest(root, manifest)
        val destination = File(root, relative)
        val partial = File(root, "$relative.partial")
        try {
            val source = File(sourcePath)
            check(source.isFile) { "Installed APK is unavailable: $sourcePath" }
            val expectedBytes = source.length()
            check(expectedBytes > 0) { "Installed APK is empty: $sourcePath" }
            val parent = requireNotNull(destination.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Could not create APK evidence directory" }
            val hash = MessageDigest.getInstance("SHA-256")
            val copiedBytes = copy(source, partial, hash)
            record.put("sizeBytes", copiedBytes)
            record.put("sha256", hash.digest().joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') })
            check(copiedBytes == expectedBytes && source.length() == expectedBytes) { "Installed APK changed or copy was incomplete" }
            check(partial.renameTo(destination)) { "Could not finish APK evidence copy" }
            record.put("captureComplete", true)
        } catch (error: Exception) {
            record.getJSONArray("errors").put(errorRecord(error))
            partial.delete()
        }
        writeManifest(root, manifest)
    }

    private fun copy(
        source: File,
        destination: File,
        digest: MessageDigest,
    ): Long {
        var bytes = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                var count = input.read(buffer)
                while (count >= 0) {
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    bytes += count
                    count = input.read(buffer)
                }
            }
        }
        return bytes
    }

    private fun errorRecord(error: Exception) = JSONObject().put("error", "${error.javaClass.name}: ${error.message}")

    private fun writeManifest(
        root: File,
        manifest: JSONObject,
    ) {
        val temporary = File(root, "manifest.json.partial")
        temporary.writeText(manifest.toString(2))
        check(temporary.renameTo(File(root, "manifest.json"))) { "Could not preserve APK evidence metadata" }
    }
}
