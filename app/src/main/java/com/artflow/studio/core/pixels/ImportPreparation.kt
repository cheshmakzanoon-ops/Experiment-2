package com.artflow.studio.core.pixels

import java.util.concurrent.locks.ReentrantLock

/**
 * One photo/model preparation or PSD source read may own the import heap headroom at a time, starting before
 * encoded stream growth. Nested texture decoding uses the same admission on its calling thread.
 * This synchronous, non-inline boundary cannot suspend or queue behind a stalled provider.
 * It does not serialize editor work, document loading, saving, or native artwork decoding.
 */
internal object ImportPreparation {
    private val owner = ReentrantLock()

    fun <T> withAdmission(prepare: () -> T): T {
        check(owner.tryLock()) { "Another file is still being imported. Wait for it to finish, then try again." }
        return try {
            prepare()
        } finally {
            owner.unlock()
        }
    }
}
