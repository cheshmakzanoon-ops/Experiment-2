// Harness shim of androidx.sqlite:sqlite 2.5.x -- the androidx.sqlite.db (SupportSQLite*) API.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.sqlite.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import java.io.Closeable

interface SupportSQLiteProgram : Closeable {
    fun bindNull(index: Int)

    fun bindLong(index: Int, value: Long)

    fun bindDouble(index: Int, value: Double)

    fun bindString(index: Int, value: String)

    fun bindBlob(index: Int, value: ByteArray)

    fun clearBindings()
}

interface SupportSQLiteStatement : SupportSQLiteProgram {
    fun execute()

    fun executeUpdateDelete(): Int

    fun executeInsert(): Long

    fun simpleQueryForLong(): Long

    fun simpleQueryForString(): String?
}

interface SupportSQLiteQuery {
    val sql: String

    fun bindTo(statement: SupportSQLiteProgram)

    val argCount: Int
}

interface SupportSQLiteDatabase : Closeable {
    fun compileStatement(sql: String): SupportSQLiteStatement

    fun beginTransaction()

    fun beginTransactionNonExclusive()

    fun endTransaction()

    fun setTransactionSuccessful()

    fun inTransaction(): Boolean

    val isDbLockedByCurrentThread: Boolean

    fun yieldIfContendedSafely(): Boolean

    var version: Int

    val maximumSize: Long

    fun setMaximumSize(numBytes: Long): Long

    var pageSize: Long

    fun query(query: String): Cursor

    fun query(query: String, bindArgs: Array<out Any?>): Cursor

    fun query(query: SupportSQLiteQuery): Cursor

    fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long

    fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int

    fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int

    fun execSQL(sql: String)

    fun execSQL(sql: String, bindArgs: Array<out Any?>)

    val isReadOnly: Boolean

    val isOpen: Boolean

    fun needUpgrade(newVersion: Int): Boolean

    val path: String?

    fun setLocale(locale: java.util.Locale)

    fun setMaxSqlCacheSize(cacheSize: Int)

    fun setForeignKeyConstraintsEnabled(enabled: Boolean)

    fun enableWriteAheadLogging(): Boolean

    fun disableWriteAheadLogging()

    val isWriteAheadLoggingEnabled: Boolean

    val attachedDbs: List<android.util.Pair<String, String>>?

    val isDatabaseIntegrityOk: Boolean
}

interface SupportSQLiteOpenHelper : Closeable {
    val databaseName: String?

    fun setWriteAheadLoggingEnabled(enabled: Boolean)

    val writableDatabase: SupportSQLiteDatabase

    val readableDatabase: SupportSQLiteDatabase

    override fun close()

    interface Factory {
        fun create(configuration: Configuration): SupportSQLiteOpenHelper
    }

    class Configuration private constructor(
        @JvmField val context: Context,
        @JvmField val name: String?,
    )
}
