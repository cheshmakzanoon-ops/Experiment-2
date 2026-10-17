// Harness shim of androidx.navigation:navigation-runtime/common 2.8.x -- the subset navigation-compose exposes.
@file:JvmName("ShimNavigationKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.navigation

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

open class NavDestination {
    val route: String? = null
    val id: Int = 0

    fun hasRoute(route: String, arguments: Bundle?): Boolean = false
}

class NavBackStackEntry private constructor() : LifecycleOwner, ViewModelStoreOwner {
    val destination: NavDestination get() = NavDestination()
    val arguments: Bundle? get() = null
    val id: String get() = ""
    override val lifecycle: Lifecycle get() = throw UnsupportedOperationException("harness stub")
    override val viewModelStore: ViewModelStore get() = throw UnsupportedOperationException("harness stub")
}

class NavOptions private constructor()

class NavOptionsBuilder {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false

    fun popUpTo(route: String, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
}

class PopUpToBuilder {
    var inclusive: Boolean = false
    var saveState: Boolean = false
}

open class NavController {
    val currentBackStackEntry: NavBackStackEntry? get() = null
    val previousBackStackEntry: NavBackStackEntry? get() = null
    val currentBackStackEntryFlow: Flow<NavBackStackEntry> get() = emptyFlow()
    val currentDestination: NavDestination? get() = null

    fun navigate(route: String, navOptions: NavOptions? = null, navigatorExtras: Navigator.Extras? = null) {}

    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) {}

    fun popBackStack(): Boolean = false

    fun popBackStack(route: String, inclusive: Boolean, saveState: Boolean = false): Boolean = false

    fun navigateUp(): Boolean = false

    fun getBackStackEntry(route: String): NavBackStackEntry = throw UnsupportedOperationException("harness stub")
}

open class NavHostController : NavController()

abstract class Navigator<D : NavDestination> {
    interface Extras
}

class NavGraphBuilder {
    fun addDestination(route: String) {}
}

abstract class NavType<T>(val isNullableAllowed: Boolean) {
    abstract fun get(bundle: Bundle, key: String): T?

    abstract fun parseValue(value: String): T

    abstract fun put(bundle: Bundle, key: String, value: T)

    companion object {
        @JvmField val IntType: NavType<Int> = object : NavType<Int>(false) {
            override fun get(bundle: Bundle, key: String): Int? = bundle.getInt(key)
            override fun parseValue(value: String): Int = value.toInt()
            override fun put(bundle: Bundle, key: String, value: Int) = bundle.putInt(key, value)
        }

        @JvmField val LongType: NavType<Long> = object : NavType<Long>(false) {
            override fun get(bundle: Bundle, key: String): Long? = bundle.getLong(key)
            override fun parseValue(value: String): Long = value.removeSuffix("L").toLong()
            override fun put(bundle: Bundle, key: String, value: Long) = bundle.putLong(key, value)
        }

        @JvmField val FloatType: NavType<Float> = object : NavType<Float>(false) {
            override fun get(bundle: Bundle, key: String): Float? = bundle.getFloat(key)
            override fun parseValue(value: String): Float = value.toFloat()
            override fun put(bundle: Bundle, key: String, value: Float) = bundle.putFloat(key, value)
        }

        @JvmField val BoolType: NavType<Boolean> = object : NavType<Boolean>(false) {
            override fun get(bundle: Bundle, key: String): Boolean? = bundle.getBoolean(key)
            override fun parseValue(value: String): Boolean = value.toBoolean()
            override fun put(bundle: Bundle, key: String, value: Boolean) = bundle.putBoolean(key, value)
        }

        @JvmField val StringType: NavType<String?> = object : NavType<String?>(true) {
            override fun get(bundle: Bundle, key: String): String? = bundle.getString(key)
            override fun parseValue(value: String): String = value
            override fun put(bundle: Bundle, key: String, value: String?) = bundle.putString(key, value)
        }
    }
}

class NavArgument private constructor()

class NamedNavArgument internal constructor(val name: String, val argument: NavArgument)

class NavArgumentBuilder {
    var type: NavType<*>? = null
    var nullable: Boolean = false

    var defaultValue: Any? = null
}

fun navArgument(name: String, builder: NavArgumentBuilder.() -> Unit): NamedNavArgument =
    throw UnsupportedOperationException("harness stub")

class NavDeepLink private constructor()
