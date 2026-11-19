package com.artflow.studio

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/** Keep this outside the Compose rule so failures from its setup and teardown are reported too. */
internal class UiFailureDiagnosticsRule : TestRule {
    @Volatile private var latestAction = "No marked action yet"

    fun <T> action(
        name: String,
        block: () -> T,
    ): T {
        latestAction = "Started: $name"
        val result = block()
        latestAction = "Completed: $name"
        return result
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                latestAction = "No marked action yet"
                runCatching { base.evaluate() }
                    .onFailure { failure ->
                        val diagnosticFailure = runCatching { writeReport(description, failure) }.exceptionOrNull()
                        if (diagnosticFailure != null && diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                    }.getOrThrow()
            }
        }

    private fun writeReport(
        description: Description,
        failure: Throwable,
    ) {
        // Capture before file I/O. These are Java IDs, matching SnapshotStateObserver's message.
        val threads = Thread.getAllStackTraces()
        val reportingThread = Thread.currentThread()
        val report =
            buildString {
                appendLine("Test: ${description.displayName}")
                appendLine("Latest action: $latestAction")
                appendLine("Reporting thread: id=${reportingThread.id}, name=${reportingThread.name}")
                appendLine("Original failure:")
                appendLine(failure.stackTraceToString())
                appendLine("Live Java threads at failure reporting (${threads.size}):")
                threads.entries.sortedBy { it.key.id }.forEach { (thread, stack) ->
                    appendLine("Thread id=${thread.id}, name=${thread.name}, state=${thread.state}, daemon=${thread.isDaemon}")
                    stack.forEach { appendLine("    at $it") }
                    appendLine()
                }
            }
        val name = "${description.className}.${description.methodName}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(TestEvidence.directory(), "$name-failure.txt").writeText(report)
    }
}
