// Harness shim of androidx.room:room-common 2.8.x annotations.  KSP does not run in the harness, so only the
// annotation declarations (and RoomDatabase & co. below) matter for type checking.
@file:Suppress("unused")

package androidx.room

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val ignoredColumns: Array<String> = [],
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class ColumnInfo(
    val name: String = INHERIT_FIELD_NAME,
    val typeAffinity: Int = UNDEFINED,
    val index: Boolean = false,
    val collate: Int = UNSPECIFIED,
    val defaultValue: String = VALUE_UNSPECIFIED,
) {
    companion object {
        const val INHERIT_FIELD_NAME: String = "[field-name]"
        const val VALUE_UNSPECIFIED: String = "[value-unspecified]"
        const val UNDEFINED: Int = 1
        const val TEXT: Int = 2
        const val INTEGER: Int = 3
        const val REAL: Int = 4
        const val BLOB: Int = 5
        const val UNSPECIFIED: Int = 1
        const val BINARY: Int = 2
        const val NOCASE: Int = 3
        const val RTRIM: Int = 4
        const val LOCALIZED: Int = 5
        const val UNICODE: Int = 6
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Dao

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Query(val value: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class RawQuery(val observedEntities: Array<KClass<*>> = [])

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Insert(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Update(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Upsert(val entity: KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Delete(val entity: KClass<*> = Any::class)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Transaction

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Database(
    val entities: Array<KClass<*>> = [],
    val views: Array<KClass<*>> = [],
    val version: Int,
    val exportSchema: Boolean = true,
    val autoMigrations: Array<AutoMigration> = [],
)

@Target
@Retention(AnnotationRetention.BINARY)
annotation class AutoMigration(
    val from: Int,
    val to: Int,
    val spec: KClass<*> = Any::class,
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class TypeConverter

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class TypeConverters(vararg val value: KClass<*>)

@Target
@Retention(AnnotationRetention.BINARY)
annotation class Index(
    vararg val value: String,
    val orders: IntArray = [],
    val name: String = "",
    val unique: Boolean = false,
)

@Target
@Retention(AnnotationRetention.BINARY)
annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
    val deferred: Boolean = false,
) {
    companion object {
        const val NO_ACTION: Int = 1
        const val RESTRICT: Int = 2
        const val SET_NULL: Int = 3
        const val SET_DEFAULT: Int = 4
        const val CASCADE: Int = 5
    }
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class Embedded(val prefix: String = "")

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class Relation(
    val entity: KClass<*> = Any::class,
    val parentColumn: String,
    val entityColumn: String,
    val associateBy: Junction = Junction(Any::class),
    val projection: Array<String> = [],
)

@Target
@Retention(AnnotationRetention.BINARY)
annotation class Junction(
    val value: KClass<*>,
    val parentColumn: String = "",
    val entityColumn: String = "",
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class Ignore

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class DatabaseView(val value: String = "", val viewName: String = "")

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class SkipQueryVerification

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class RewriteQueriesToDropUnusedColumns

object OnConflictStrategy {
    const val NONE: Int = 0
    const val REPLACE: Int = 1

    @Deprecated("Does not work with Android's current SQLite version.")
    const val ROLLBACK: Int = 2
    const val ABORT: Int = 3

    @Deprecated("Does not work as expected. The transaction is rolled back.")
    const val FAIL: Int = 4
    const val IGNORE: Int = 5
}
