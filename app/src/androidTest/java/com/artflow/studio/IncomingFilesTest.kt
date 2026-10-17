package com.artflow.studio

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.presentation.ui.IncomingFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingFilesTest {
    private val one = Uri.parse("content://example.test/one.procreate")
    private val two = Uri.parse("content://example.test/two.png")

    @Test
    fun openWithAndEditWithCarryTheDataUri() {
        assertEquals(listOf(one), IncomingFiles.uris(Intent(Intent.ACTION_VIEW, one)))
        assertEquals(listOf(two), IncomingFiles.uris(Intent(Intent.ACTION_EDIT, two)))
    }

    @Test
    fun shareCarriesTheStreamExtra() {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, one)
        assertEquals(listOf(one), IncomingFiles.uris(send))
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))
        assertEquals(listOf(one, two), IncomingFiles.uris(many))
    }

    @Test
    fun otherIntentsCarryNothing() {
        assertTrue(IncomingFiles.uris(null).isEmpty())
        assertTrue(IncomingFiles.uris(Intent(Intent.ACTION_MAIN)).isEmpty())
        assertTrue(IncomingFiles.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "just words")).isEmpty())
        assertTrue(IncomingFiles.uris(Intent(Intent.ACTION_VIEW)).isEmpty())
    }
}
