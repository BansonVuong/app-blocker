package com.appblocker

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
                interventionMode = BlockSet.INTERVENTION_RANDOM_32
            )
        )

        assertEquals(app.getString(R.string.intervention_required_title), title.text.toString())
        assertEquals(View.GONE, unblockTime.visibility)

        controller.newIntent(blockedIntent(mode = BlockedActivity.MODE_QUOTA))

        assertEquals(app.getString(R.string.quota_exceeded), title.text.toString())
    }

    private fun blockedIntent(mode: Int, interventionMode: Int = BlockSet.INTERVENTION_NONE): Intent {
        return Intent(app, BlockedActivity::class.java).apply {
            putExtra(BlockedActivity.EXTRA_BLOCK_SET_NAME, "Focus")
            putExtra(BlockedActivity.EXTRA_MODE, mode)
            putExtra(BlockedActivity.EXTRA_INTERVENTION_MODE, interventionMode)
        }
    }
}
