// Harness shim of androidx.lifecycle:lifecycle-viewmodel 2.9.x (ViewModel, ViewModelStore, ViewModelProvider, viewModelScope).
@file:JvmName("ShimViewModelKt")
@file:Suppress("unused")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

abstract class ViewModel {
    private val lock = Any()
    private val keyedCloseables = LinkedHashMap<String, AutoCloseable>()
    private val closeables = LinkedHashSet<AutoCloseable>()

    @Volatile
    private var isCleared = false

    constructor()

    constructor(viewModelScope: CoroutineScope) {
        addCloseable(VIEW_MODEL_SCOPE_KEY, viewModelScope.asCloseable())
    }

    constructor(vararg closeables: AutoCloseable) {
        this.closeables += closeables
    }

    constructor(viewModelScope: CoroutineScope, vararg closeables: AutoCloseable) {
        addCloseable(VIEW_MODEL_SCOPE_KEY, viewModelScope.asCloseable())
        this.closeables += closeables
    }

    protected open fun onCleared() {}

    @androidx.annotation.MainThread
    internal fun clear() {
        isCleared = true
        synchronized(lock) {
            for (c in keyedCloseables.values) c.close()
            for (c in closeables) c.close()
            closeables.clear()
        }
        onCleared()
    }

    fun addCloseable(
        key: String,
        closeable: AutoCloseable,
    ) {
        if (isCleared) {
            closeable.close()
            return
        }
        synchronized(lock) { keyedCloseables.put(key, closeable) }?.close()
    }

    fun addCloseable(closeable: AutoCloseable) {
        if (isCleared) {
            closeable.close()
            return
        }
        synchronized(lock) { closeables += closeable }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AutoCloseable> getCloseable(key: String): T? = synchronized(lock) { keyedCloseables[key] as T? }
}

private const val VIEW_MODEL_SCOPE_KEY = "androidx.lifecycle.ViewModelCoroutineScope.JOB_KEY"

private fun CoroutineScope.asCloseable(): AutoCloseable = (this as? AutoCloseable) ?: AutoCloseable { cancel() }

internal class CloseableCoroutineScope(
    override val coroutineContext: CoroutineContext,
) : AutoCloseable, CoroutineScope {
    constructor(coroutineScope: CoroutineScope) : this(coroutineScope.coroutineContext)

    override fun close() = coroutineContext.cancel()
}

val ViewModel.viewModelScope: CoroutineScope
    get() =
        getCloseable<CloseableCoroutineScope>(VIEW_MODEL_SCOPE_KEY)
            ?: CloseableCoroutineScope(
                try {
                    Dispatchers.Main.immediate + SupervisorJob()
                } catch (_: Throwable) {
                    EmptyCoroutineContext + SupervisorJob()
                },
            ).also { addCloseable(VIEW_MODEL_SCOPE_KEY, it) }

open class ViewModelStore {
    private val map = mutableMapOf<String, ViewModel>()

    internal fun put(
        key: String,
        viewModel: ViewModel,
    ) {
        map.put(key, viewModel)?.clear()
    }

    internal operator fun get(key: String): ViewModel? = map[key]

    internal fun keys(): Set<String> = HashSet(map.keys)

    fun clear() {
        for (vm in map.values) vm.clear()
        map.clear()
    }
}

interface ViewModelStoreOwner {
    val viewModelStore: ViewModelStore
}

interface HasDefaultViewModelProviderFactory {
    val defaultViewModelProviderFactory: ViewModelProvider.Factory
}

open class ViewModelProvider {
    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T = throw UnsupportedOperationException("Factory.create(Class) is not implemented")
    }

    open class NewInstanceFactory : Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.getDeclaredConstructor().newInstance()
    }

    private val store: ViewModelStore
    private val factory: Factory

    constructor(owner: ViewModelStoreOwner) : this(owner.viewModelStore, NewInstanceFactory())

    constructor(owner: ViewModelStoreOwner, factory: Factory) : this(owner.viewModelStore, factory)

    constructor(store: ViewModelStore, factory: Factory) {
        this.store = store
        this.factory = factory
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T : ViewModel> get(modelClass: Class<T>): T = get("androidx.lifecycle.ViewModelProvider.DefaultKey:" + modelClass.canonicalName, modelClass)

    @Suppress("UNCHECKED_CAST")
    operator fun <T : ViewModel> get(
        key: String,
        modelClass: Class<T>,
    ): T {
        val existing = store[key]
        if (modelClass.isInstance(existing)) return existing as T
        return factory.create(modelClass).also { store.put(key, it) }
    }
}
