package com.mai.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @Test
    fun mainActivityStaysAliveAfterColdStart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity finished during launch", activity.isFinishing)
            }
            // Covers the animated splash transition and the point where background
            // speech-model preparation begins on a real Android runtime.
            SystemClock.sleep(10_000)
            scenario.onActivity { activity ->
                assertFalse("MainActivity crashed or finished after startup", activity.isFinishing)
            }
        }
    }
}
