package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
    private var mode: Int = MODE_QUOTA
    private var interventionMode: Int = BlockSet.INTERVENTION_NONE
    private var interventionCodeLength: Int = 32
    private var interventionDialogShown = false
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
        const val EXTRA_MODE = "mode"
        const val EXTRA_INTERVENTION_MODE = "intervention_mode"
        const val EXTRA_INTERVENTION_CODE_LENGTH = "intervention_code_length"
        const val MODE_QUOTA = 0
        const val MODE_INTERVENTION = 1
        private const val UPDATE_INTERVAL_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = App.instance.storage

        applyIntentState(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newMode = intent.getIntExtra(EXTRA_MODE, MODE_QUOTA)
        // If we're already showing an intervention dialog, ignore duplicate intervention intents
        if (newMode == MODE_INTERVENTION && mode == MODE_INTERVENTION && interventionDialogShown) {
            return
        }
        applyIntentState(intent)
        handler.removeCallbacks(updateRunnable)
        if (mode == MODE_INTERVENTION) {
            showInterventionDialogIfNeeded()
        } else {
            handler.post(updateRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_INTERVENTION) {
            showInterventionDialogIfNeeded()
            return
        }
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun applyIntentState(intent: Intent) {
        val blockSetName = intent.getStringExtra(EXTRA_BLOCK_SET_NAME) ?: "Unknown"
        blockSetId = intent.getStringExtra(EXTRA_BLOCK_SET_ID)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_QUOTA)
        interventionMode = intent.getIntExtra(EXTRA_INTERVENTION_MODE, BlockSet.INTERVENTION_NONE)
        interventionCodeLength = intent.getIntExtra(EXTRA_INTERVENTION_CODE_LENGTH, 32)
        interventionDialogShown = false

        binding.textTitle.text = if (mode == MODE_INTERVENTION) {
            getString(R.string.intervention_required_title)
        } else {
            getString(R.string.quota_exceeded)
        }
        binding.textMessage.text = if (mode == MODE_INTERVENTION) {
            getString(R.string.intervention_required_message)
        } else {
            getString(R.string.quota_exceeded_message)
        }
        binding.textBlockSetName.text = "\"$blockSetName\""

        if (mode == MODE_QUOTA) {
            updateUnblockTime(blockSetId)
        } else {
            binding.textUnblockTime.visibility = View.GONE
        }
    }

    private fun goHome() {
        val returnPackage = intent.getStringExtra(EXTRA_RETURN_PACKAGE)
        if (returnPackage != null && AppTargets.isVirtualPackage(returnPackage)) {
            val parentPackage = AppTargets.getParentPackage(returnPackage)
            val launchIntent = parentPackage?.let { packageManager.getLaunchIntentForPackage(it) }
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

    private fun showInterventionDialogIfNeeded() {
        if (interventionDialogShown) return
        interventionDialogShown = true
        val prompt = AuthFlow.promptForRandomCode(
            mode = interventionMode,
            randomMode = BlockSet.INTERVENTION_RANDOM,
            randomCodeLength = interventionCodeLength,
            randomCodeMessage = getString(R.string.enter_random_password_to_continue)
        )
        if (prompt == null) {
            goHome()
            return
        }
        showPasswordDialog(
            headerResId = R.string.intervention_required_title,
            message = prompt.message,
            expectedPassword = prompt.expectedPassword,
            displayPassword = prompt.displayPassword,
            incorrectToastResId = R.string.intervention_password_incorrect,
            positiveButtonResId = R.string.continue_label,
            inputType = InputType.TYPE_CLASS_TEXT,
            onAuthorized = {
                val returnPackage = intent.getStringExtra(EXTRA_RETURN_PACKAGE)
                if (!returnPackage.isNullOrBlank()) {
                    storage.grantInterventionBypass(returnPackage)
                }
                if (launchReturnApp()) {
                    finish()
                } else {
                    goHome()
                }
            },
            onCancelled = { goHome() }
        )
    }

    private fun launchReturnApp(): Boolean {
        val returnPackage = intent.getStringExtra(EXTRA_RETURN_PACKAGE) ?: return false
        val packageToLaunch = if (AppTargets.isVirtualPackage(returnPackage)) {
            AppTargets.getParentPackage(returnPackage)
        } else {
            returnPackage
        }
        val launchIntent = packageToLaunch?.let { packageManager.getLaunchIntentForPackage(it) } ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }

    private fun updateUnblockTime(blockSetId: String?) {
        val blockSet = blockSetId?.let { id ->
            storage.getBlockSets().find { it.id == id }
        }
        if (blockSet == null) {
            binding.textUnblockTime.visibility = View.GONE
            return
        }

        if (storage.isLockdownActive()) {
            val unblockAtMillis = storage.getLockdownEndMillis() ?: return
            val date = Date(unblockAtMillis)
            val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
            val time = formatter.format(date)
            binding.textUnblockTime.visibility = View.VISIBLE
            binding.textUnblockTime.text = getString(R.string.unblocked_at, time)
            return
        }

        if (storage.isOverrideActive(blockSet)) {
            finish()
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
