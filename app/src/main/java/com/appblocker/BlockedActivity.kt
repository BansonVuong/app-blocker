package com.appblocker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.appblocker.databinding.ActivityBlockedBinding

class BlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedBinding

    companion object {
        const val EXTRA_BLOCK_SET_NAME = "block_set_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val blockSetName = intent.getStringExtra(EXTRA_BLOCK_SET_NAME) ?: "Unknown"

        binding.textMessage.text = getString(R.string.quota_exceeded_message)
        binding.textBlockSetName.text = "\"$blockSetName\""

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
}
