package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.appblocker.databinding.ActivityBlockedBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedBinding
    private lateinit var storage: Storage
    private val handler = Handler(Looper.getMainLooper())
    private var blockSetId: String? = null
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateUnblockTime(blockSetId)
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    companion object {
        const val EXTRA_BLOCK_SET_NAME = "block_set_name"
        const val EXTRA_BLOCK_SET_ID = "block_set_id"
        const val EXTRA_RETURN_PACKAGE = "return_package"
        private const val UPDATE_INTERVAL_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = App.instance.storage

        val blockSetName = intent.getStringExtra(EXTRA_BLOCK_SET_NAME) ?: "Unknown"
        blockSetId = intent.getStringExtra(EXTRA_BLOCK_SET_ID)

        binding.textMessage.text = getString(R.string.quota_exceeded_message)
        binding.textBlockSetName.text = "\"$blockSetName\""

        updateUnblockTime(blockSetId)

        binding.buttonGoBack.setOnClickListener {
            goHome()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goHome()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun goHome() {
        val returnPackage = intent.getStringExtra(EXTRA_RETURN_PACKAGE)
        if (returnPackage != null && AppTargets.isVirtualPackage(returnPackage)) {
            val launchIntent = packageManager.getLaunchIntentForPackage(AppTargets.SNAPCHAT_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                finish()
                return
            }
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun updateUnblockTime(blockSetId: String?) {
        val blockSet = blockSetId?.let { id ->
            storage.getBlockSets().find { it.id == id }
        }
        if (blockSet == null) {
            binding.textUnblockTime.visibility = View.GONE
            return
        }

        if (!storage.isQuotaExceeded(blockSet)) {
            finish()
            return
        }

        binding.textBlockSetName.text = "\"${blockSet.name}\""

        val unblockAtMillis = storage.getWindowEndMillis(blockSet)
        val date = Date(unblockAtMillis)
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        val time = formatter.format(date)
        binding.textUnblockTime.visibility = View.VISIBLE
        binding.textUnblockTime.text = getString(R.string.unblocked_at, time)
    }
}
