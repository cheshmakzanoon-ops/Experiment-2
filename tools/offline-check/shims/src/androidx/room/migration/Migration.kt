// Harness shim of androidx.room.migration.Migration (room-runtime 2.8.x).
@file:Suppress("unused")

package androidx.room.migration

import androidx.sqlite.db.SupportSQLiteDatabase

abstract class Migration(
    @JvmField val startVersion: Int,
    @JvmField val endVersion: Int,
) {
    open fun migrate(db: SupportSQLiteDatabase) {}
}
