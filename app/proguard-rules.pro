# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles input property for a given build variant.

# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not kept.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Hilt
# Broad `dagger.**`/`javax.inject.**` keeps previously forced R8 to retain the entire DI runtime,
# preventing it from shrinking unused modules. Standard Hilt rules keep only what reflection and
# generated components actually need; R8 can then remove the rest.
-keepclasseswithmembernames class * {
    @dagger.hilt.* <methods>;
}
-keep class dagger.hilt.** { *; }
-keep class * extends java.lang.annotation.Annotation { *; }
-keep class com.artflow.studio.di.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Timber
-dontwarn timber.log.Timber$Tree
