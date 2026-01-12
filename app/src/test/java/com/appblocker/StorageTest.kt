package com.appblocker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_blocker", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savesAndLoadsBlockSets() {
        val storage = Storage(context)
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.example.a"),
                quotaMinutes = 10,
                windowMinutes = 60
            ),
            BlockSet(
                name = "Games",
                apps = mutableListOf("com.example.b"),
                quotaMinutes = 5,
                windowMinutes = 30
            )
        )

        storage.saveBlockSets(blockSets)
        val loaded = storage.getBlockSets()

        assertEquals(2, loaded.size)
        assertEquals("Social", loaded[0].name)
        assertEquals("Games", loaded[1].name)
    }

    @Test
    fun findsBlockSetForApp() {
        val storage = Storage(context)
        val blockSets = listOf(
            BlockSet(
                name = "Social",
                apps = mutableListOf("com.example.a"),
                quotaMinutes = 10,
                windowMinutes = 60
            )
        )
        storage.saveBlockSets(blockSets)

        val found = storage.getBlockSetForApp("com.example.a")
        val missing = storage.getBlockSetForApp("com.example.missing")

        assertEquals("Social", found?.name)
        assertNull(missing)
    }

    @Test
    fun debugOverlayFlagPersists() {
        val storage = Storage(context)

        assertFalse(storage.isDebugOverlayEnabled())
        storage.setDebugOverlayEnabled(true)
        assertTrue(storage.isDebugOverlayEnabled())
    }
}
