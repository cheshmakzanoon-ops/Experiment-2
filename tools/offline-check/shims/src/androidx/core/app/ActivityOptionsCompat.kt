// Harness shim of androidx.core.app.ActivityOptionsCompat.
@file:Suppress("unused")

package androidx.core.app

import android.os.Bundle

open class ActivityOptionsCompat protected constructor() {
    open fun toBundle(): Bundle? = null

    open fun update(otherOptions: ActivityOptionsCompat) {}

    companion object {
        @JvmStatic
        fun makeBasic(): ActivityOptionsCompat = ActivityOptionsCompat()
    }
}
