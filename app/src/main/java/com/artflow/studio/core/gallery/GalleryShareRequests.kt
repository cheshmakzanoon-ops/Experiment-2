package com.artflow.studio.core.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll

/** A confirmed artwork deletion owns the source files after overlapping shares finish cleanup. */
internal class GalleryShareRequests {
    private val lock = Any()
    private val shares = mutableSetOf<Share>()
    private val deleting = mutableMapOf<Long, Deletion>()

    private class Share(
        val projectIds: Set<Long>,
        val job: Job,
    )

    /** The child scope lets deletion cancel this request without cancelling the gallery's scope. */
    suspend fun <T> run(
        projectIds: Collection<Long>,
        action: suspend () -> T,
    ): T =
        coroutineScope {
            val request = Share(projectIds.toSet(), coroutineContext.job)
            synchronized(lock) {
                if (request.projectIds.any(deleting::containsKey)) {
                    throw CancellationException("The shared artwork is being deleted")
                }
                shares += request
            }
            try {
                action()
            } finally {
                synchronized(lock) { shares -= request }
            }
        }

    /** Reserve synchronously, before the deletion coroutine can suspend or another share can start. */
    fun beginDeletion(projectId: Long): Deletion? {
        val deletion =
            synchronized(lock) {
                if (projectId in deleting) return null
                val pending = shares.filter { projectId in it.projectIds }.map(Share::job)
                Deletion(projectId, pending).also { deleting[projectId] = it }
            }
        deletion.cancelShares()
        return deletion
    }

    inner class Deletion internal constructor(
        private val projectId: Long,
        private val pending: List<Job>,
    ) {
        internal fun cancelShares() {
            pending.forEach { it.cancel(CancellationException("The shared artwork is being deleted")) }
        }

        /** Drain writers and unpublished-copy cleanup before removing their directories. */
        suspend fun complete(action: suspend () -> Unit) {
            try {
                pending.joinAll()
                action()
            } finally {
                release()
            }
        }

        /** Also called by job completion when cancellation prevents the coroutine body from starting. */
        fun release() {
            synchronized(lock) {
                if (deleting[projectId] === this) deleting.remove(projectId)
            }
        }
    }
}
