package com.appblocker

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = App::class)
class BlockedActivityTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("app_blocker", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun updatesUiWhenReceivingNewIntentWithDifferentBlockMode() {
        val controller = Robolectric.buildActivity(
            BlockedActivity::class.java,
            blockedIntent(mode = BlockedActivity.MODE_QUOTA)
        ).setup()
        val activity = controller.get()

        val title = activity.findViewById<TextView>(R.id.textTitle)
        val unblockTime = activity.findViewById<TextView>(R.id.textUnblockTime)

        assertEquals(app.getString(R.string.quota_exceeded), title.text.toString())

        controller.newIntent(
            blockedIntent(
                mode = BlockedActivity.MODE_INTERVENTION,
                interventionMode = BlockSet.INTERVENTION_RANDOM
            )
        )

        assertEquals(app.getString(R.string.intervention_required_title), title.text.toString())
        assertEquals(View.GONE, unblockTime.visibility)

        controller.newIntent(blockedIntent(mode = BlockedActivity.MODE_QUOTA))

        assertEquals(app.getString(R.string.quota_exceeded), title.text.toString())
    }

    @Test
    fun showsNeverForZeroQuotaWithoutSchedule() {
        val storage = Storage(app)
        val blockSet = BlockSet(
            name = "Focus",
            quotaMinutes = 0.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(blockSet))

        val activity = Robolectric.buildActivity(
            BlockedActivity::class.java,
            blockedIntent(mode = BlockedActivity.MODE_QUOTA).apply {
                putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, blockSet.name)
                putExtra(BlockedActivity.EXTRA_BLOCK_SET_ID, blockSet.id)
            }
        ).setup().get()

        val unblockTime = activity.findViewById<TextView>(R.id.textUnblockTime)
        assertEquals(
            app.getString(R.string.unblocked_at, app.getString(R.string.never)),
            unblockTime.text.toString()
        )
    }

    @Test
    fun keepsQuotaScreenVisibleWhenDifferentOverlappingBlockSetIsStillBlocking() {
        val storage = Storage(app)
        val availableBlockSet = BlockSet(
            name = "Available",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 30.0,
            windowMinutes = 60
        )
        val blockingBlockSet = BlockSet(
            name = "Blocking",
            apps = mutableListOf("com.example.blocked"),
            quotaMinutes = 0.0,
            windowMinutes = 60
        )
        storage.saveBlockSets(listOf(availableBlockSet, blockingBlockSet))

        val activity = Robolectric.buildActivity(
            BlockedActivity::class.java,
            blockedIntent(mode = BlockedActivity.MODE_QUOTA).apply {
                putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, availableBlockSet.name)
                putExtra(BlockedActivity.EXTRA_BLOCK_SET_ID, availableBlockSet.id)
                putExtra(BlockedActivity.EXTRA_RETURN_PACKAGE, "com.example.blocked")
            }
        ).setup().get()

        assertFalse(activity.isFinishing)
        assertEquals(
            "\"${blockingBlockSet.name}\"",
            activity.findViewById<TextView>(R.id.textBlockSetName).text.toString()
        )
    }

    private fun blockedIntent(mode: Int, interventionMode: Int = BlockSet.INTERVENTION_NONE): Intent {
        return Intent(app, BlockedActivity::class.java).apply {
            putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, "Focus")
            putExtra(BlockedActivity.EXTRA_MODE, mode)
            putExtra(BlockedActivity.EXTRA_INTERVENTION_MODE, interventionMode)
        }
    }
}
