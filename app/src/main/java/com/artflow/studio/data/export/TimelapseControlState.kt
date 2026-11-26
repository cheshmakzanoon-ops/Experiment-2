package com.artflow.studio.data.export

import java.util.concurrent.ConcurrentHashMap

/** A requested change blocks capture until that exact request has been durably stored. */
internal class TimelapseControlState {
    class Request(
        val projectId: Long,
        val recording: Boolean,
    )

    private val requests = ConcurrentHashMap<Long, Request>()

    fun begin(
        projectId: Long,
        recording: Boolean,
    ): Request {
        require(projectId > 0) { "Invalid project id" }
        return Request(projectId, recording).also { requests[projectId] = it }
    }

    fun permitsRecording(projectId: Long): Boolean = !requests.containsKey(projectId)

    fun owns(request: Request): Boolean = requests[request.projectId] === request

    /** Failed or cancelled writes deliberately keep their block for the rest of this process. */
    fun persisted(request: Request) {
        requests.remove(request.projectId, request)
    }
}
