// Harness shim of androidx.lifecycle (lifecycle-common/runtime/viewmodel 2.9.x).  Only the public API the app (and
// Coil/Hilt) can touch is declared; behaviour is just enough for JVM unit tests to run.
@file:Suppress("unused")

package androidx.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

abstract class Lifecycle {
    abstract fun addObserver(observer: LifecycleObserver)

    abstract fun removeObserver(observer: LifecycleObserver)

    abstract val currentState: State

    open val currentStateFlow: StateFlow<State>
        get() = MutableStateFlow(currentState)

    enum class Event {
        ON_CREATE,
        ON_START,
        ON_RESUME,
        ON_PAUSE,
        ON_STOP,
        ON_DESTROY,
        ON_ANY,
        ;

        val targetState: State
            get() =
                when (this) {
                    ON_CREATE, ON_STOP -> State.CREATED
                    ON_START, ON_PAUSE -> State.STARTED
                    ON_RESUME -> State.RESUMED
                    ON_DESTROY -> State.DESTROYED
                    ON_ANY -> throw IllegalArgumentException("$this has no target state")
                }

        companion object {
            @JvmStatic
            fun downFrom(state: State): Event? =
                when (state) {
                    State.CREATED -> ON_DESTROY
                    State.STARTED -> ON_STOP
                    State.RESUMED -> ON_PAUSE
                    else -> null
                }

            @JvmStatic
            fun downTo(state: State): Event? =
                when (state) {
                    State.DESTROYED -> ON_DESTROY
                    State.CREATED -> ON_STOP
                    State.STARTED -> ON_PAUSE
                    else -> null
                }

            @JvmStatic
            fun upFrom(state: State): Event? =
                when (state) {
                    State.INITIALIZED -> ON_CREATE
                    State.CREATED -> ON_START
                    State.STARTED -> ON_RESUME
                    else -> null
                }

            @JvmStatic
            fun upTo(state: State): Event? =
                when (state) {
                    State.CREATED -> ON_CREATE
                    State.STARTED -> ON_START
                    State.RESUMED -> ON_RESUME
                    else -> null
                }
        }
    }

    enum class State {
        DESTROYED,
        INITIALIZED,
        CREATED,
        STARTED,
        RESUMED,
        ;

        fun isAtLeast(state: State): Boolean = compareTo(state) >= 0
    }
}

interface LifecycleObserver

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    )
}

interface DefaultLifecycleObserver : LifecycleObserver {
    fun onCreate(owner: LifecycleOwner) {}

    fun onStart(owner: LifecycleOwner) {}

    fun onResume(owner: LifecycleOwner) {}

    fun onPause(owner: LifecycleOwner) {}

    fun onStop(owner: LifecycleOwner) {}

    fun onDestroy(owner: LifecycleOwner) {}
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

/** Minimal [Lifecycle] implementation (androidx.lifecycle.LifecycleRegistry). */
open class LifecycleRegistry private constructor(
    provider: LifecycleOwner,
    private val enforceMainThread: Boolean,
) : Lifecycle() {
    constructor(provider: LifecycleOwner) : this(provider, true)

    private val owner = java.lang.ref.WeakReference(provider)
    private val observers = LinkedHashSet<LifecycleObserver>()
    private var state: State = State.INITIALIZED

    override var currentState: State
        get() = state
        set(value) {
            state = value
        }

    open fun handleLifecycleEvent(event: Event) {
        state = event.targetState
        val o = owner.get() ?: return
        observers.toList().forEach { if (it is LifecycleEventObserver) it.onStateChanged(o, event) }
    }

    override fun addObserver(observer: LifecycleObserver) {
        observers.add(observer)
    }

    override fun removeObserver(observer: LifecycleObserver) {
        observers.remove(observer)
    }

    companion object {
        @JvmStatic
        fun createUnsafe(owner: LifecycleOwner): LifecycleRegistry = LifecycleRegistry(owner, false)
    }
}
