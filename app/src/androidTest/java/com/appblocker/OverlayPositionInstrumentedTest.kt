package com.appblocker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayPositionInstrumentedTest {

    @Test
    fun overlayPositionPersistsAcrossStorageInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("app_blocker", 0).edit().clear().commit()

        val storage = Storage(context)
        assertNull(storage.getOverlayPosition("com.example.app"))

        storage.setOverlayPosition("com.example.app", 100, 200)

        val reloaded = Storage(context)
        assertEquals(100 to 200, reloaded.getOverlayPosition("com.example.app"))
    }
}
