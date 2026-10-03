package com.artflow.studio.data.local

import android.content.Context

/** Remembers each artwork's reference image (a persistable document URI) between sessions. */
object ReferenceImages {
    private const val PREFERENCES = "reference_images"

    fun get(
        context: Context,
        projectId: Long,
    ): String? = preferences(context).getString(key(projectId), null)

    fun set(
        context: Context,
        projectId: Long,
        uri: String?,
    ) {
        val editor = preferences(context).edit()
        if (uri == null) editor.remove(key(projectId)) else editor.putString(key(projectId), uri)
        editor.apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun key(projectId: Long) = "project.$projectId"
}
