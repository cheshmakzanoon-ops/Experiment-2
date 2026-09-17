package com.artflow.studio

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.presentation.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch smoke test: the single activity must start under Hilt, reach the resumed state and expose
 * composed content. This exercises the paths a JVM unit test cannot — Hilt graph construction,
 * the Room database open, the GL surface attach and the theme swap in `onCreate`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun activityLaunchesAndReachesResumed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertTrue(!activity.isFinishing)
            assertTrue(activity.window != null)
            assertTrue(activity.window.decorView.width > 0)
        }
        scenario.close()
    }
}
