package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.appblocker.databinding.ActivityBlockedBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedBinding
    private lateinit var storage: Storage

    companion object {
        const val EXTRA_BLOCK_SET_NAME = "block_set_name"
        const val EXTRA_BLOCK_SET_ID = "block_set_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = App.instance.storage

        val blockSetName = intent.getStringExtra(EXTRA_BLOCK_SET_NAME) ?: "Unknown"
        val blockSetId = intent.getStringExtra(EXTRA_BLOCK_SET_ID)

        binding.textMessage.text = getString(R.string.quota_exceeded_message)
        binding.textBlockSetName.text = "\"$blockSetName\""

        updateUnblockTime(blockSetId)

        binding.buttonGoBack.setOnClickListener {
            goHome()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        goHome()
    }

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
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

        val nowSeconds = System.currentTimeMillis() / 1000
        val windowSeconds = blockSet.windowMinutes * 60
        val windowStart = (nowSeconds / windowSeconds) * windowSeconds
        val unblockAtSeconds = windowStart + windowSeconds
        val date = Date(unblockAtSeconds * 1000)
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        val time = formatter.format(date)
        binding.textUnblockTime.visibility = View.VISIBLE
        binding.textUnblockTime.text = getString(R.string.unblocked_at, time)
    }
}
