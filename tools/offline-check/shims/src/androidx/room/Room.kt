// Harness shim of androidx.room:room-runtime / room-ktx 2.8.x: Room, RoomDatabase, withTransaction.
@file:JvmName("ShimRoomKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.room

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.util.concurrent.Executor

abstract class RoomDatabase {
    open val openHelper: SupportSQLiteOpenHelper get() = throw UnsupportedOperationException("harness stub")
    open val queryExecutor: Executor get() = throw UnsupportedOperationException("harness stub")
    open val transactionExecutor: Executor get() = throw UnsupportedOperationException("harness stub")
    open val invalidationTracker: InvalidationTracker get() = throw UnsupportedOperationException("harness stub")
    open val isOpen: Boolean get() = false

    fun inTransaction(): Boolean = false

    fun runInTransaction(body: Runnable) {}

    fun <V> runInTransaction(body: java.util.concurrent.Callable<V>): V = body.call()

    open fun close() {}

    abstract fun clearAllTables()

    fun beginTransaction() {}

    fun setTransactionSuccessful() {}

    fun endTransaction() {}

    enum class JournalMode { AUTOMATIC, TRUNCATE, WRITE_AHEAD_LOGGING }

    abstract class Callback {
        open fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}

        open fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {}

        open fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
    }

    open class Builder<T : RoomDatabase> internal constructor() {
        fun addMigrations(vararg migrations: Migration): Builder<T> = this

        fun addCallback(callback: Callback): Builder<T> = this

        fun allowMainThreadQueries(): Builder<T> = this

        fun setJournalMode(journalMode: JournalMode): Builder<T> = this

        fun setQueryExecutor(executor: Executor): Builder<T> = this

        fun setTransactionExecutor(executor: Executor): Builder<T> = this

        fun setQueryCoroutineContext(context: kotlin.coroutines.CoroutineContext): Builder<T> = this

        fun fallbackToDestructiveMigration(dropAllTables: Boolean): Builder<T> = this

        @Deprecated("Replace by the variant with the dropAllTables parameter")
        fun fallbackToDestructiveMigration(): Builder<T> = this

        fun fallbackToDestructiveMigrationOnDowngrade(dropAllTables: Boolean): Builder<T> = this

        fun fallbackToDestructiveMigrationFrom(dropAllTables: Boolean, vararg startVersions: Int): Builder<T> = this

        fun createFromAsset(databaseFilePath: String): Builder<T> = this

        fun createFromFile(databaseFile: java.io.File): Builder<T> = this

        fun enableMultiInstanceInvalidation(): Builder<T> = this

        fun openHelperFactory(factory: SupportSQLiteOpenHelper.Factory?): Builder<T> = this

        fun build(): T = throw UnsupportedOperationException("harness stub")
    }
}

class InvalidationTracker internal constructor() {
    abstract class Observer(tables: Array<String>) {
        abstract fun onInvalidated(tables: Set<String>)
    }
}

object Room {
    const val MASTER_TABLE_NAME: String = "room_master_table"

    @JvmStatic
    fun <T : RoomDatabase> databaseBuilder(
        context: Context,
        klass: Class<T>,
        name: String?,
    ): RoomDatabase.Builder<T> = RoomDatabase.Builder()

    @JvmStatic
    fun <T : RoomDatabase> inMemoryDatabaseBuilder(
        context: Context,
        klass: Class<T>,
    ): RoomDatabase.Builder<T> = RoomDatabase.Builder()
}

suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()
