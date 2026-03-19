package com.appblocker

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.appblocker.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.text.InputType

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: Storage
    private lateinit var adapter: BlockSetAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var lastBlockSetIds: List<String> = emptyList()
    private var lastHasActiveOverride: Boolean = false
    private var lastLockdownActive: Boolean = false

    private val blockSetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.overflowIcon?.setTint(ContextCompat.getColor(this, android.R.color.white))

        storage = App.instance.storage

        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        refreshData()
        startPeriodicRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopPeriodicRefresh()
    }

    private fun startPeriodicRefresh() {
        refreshRunnable = object : Runnable {
            override fun run() {
                refreshDynamicState()
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
        handler.postDelayed(refreshRunnable!!, REFRESH_INTERVAL_MS)
    }

    private fun stopPeriodicRefresh() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        refreshRunnable = null
    }

    private fun setupRecyclerView() {
        adapter = BlockSetAdapter { blockSet ->
            openBlockSetEditor(blockSet)
        }
        binding.recyclerBlockSets.layoutManager = LinearLayoutManager(this)
        binding.recyclerBlockSets.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.fabAdd.setOnClickListener {
            openBlockSetEditor(null)
        }

        binding.buttonEnableAccessibility.setOnClickListener {
            openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }

        binding.buttonEnableUsageStats.setOnClickListener {
            openSystemSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }

        binding.buttonEnableOverlay.setOnClickListener {
            openSystemSettings(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        }

        binding.buttonOverride.setOnClickListener {
            val blockSets = storage.getBlockSets()
            val overrideEligible = blockSets.filter { it.allowOverride }
            if (overrideEligible.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_override_block_sets), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val hasActiveOverride = overrideEligible.any { storage.isOverrideActive(it) }
            if (hasActiveOverride) {
                overrideEligible.forEach { blockSet ->
                    storage.clearOverride(blockSet.id)
                }
                refreshData()
                return@setOnClickListener
            }
            showOverrideFlow()
        }

        binding.buttonLockdown.setOnClickListener {
            if (storage.isLockdownActive()) {
                showLockdownCancelFlow()
                return@setOnClickListener
            }
            showLockdownHoursDialog()
        }
    }

    private fun openBlockSetEditor(blockSet: BlockSet?) {
        val header = blockSet?.name ?: getString(R.string.settings_access_title)
        showSettingsAccessFlow(header) {
            val intent = Intent(this, BlockSetActivity::class.java)
            blockSet?.let {
                intent.putExtra(BlockSetActivity.EXTRA_BLOCK_SET_ID, it.id)
            }
            blockSetLauncher.launch(intent)
        }
    }

    private fun refreshData() {
        val blockSets = storage.getBlockSets()
        val blockSetIds = blockSets.map { it.id }
        adapter.setData(blockSets, storage)
        binding.textEmpty.visibility = if (blockSets.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerBlockSets.visibility = if (blockSets.isEmpty()) View.GONE else View.VISIBLE
        lastBlockSetIds = blockSetIds
        updateActionButtons(blockSets)
    }

    private fun refreshDynamicState() {
        val blockSets = storage.getBlockSets()
        val blockSetIds = blockSets.map { it.id }
        if (blockSetIds != lastBlockSetIds) {
            refreshData()
            return
        }
        adapter.refreshDynamicState()
        updateActionButtons(blockSets)
    }

    private fun updateActionButtons(blockSets: List<BlockSet>) {
        val overrideEligible = blockSets.filter { it.allowOverride }
        val hasActiveOverride = overrideEligible.any { storage.isOverrideActive(it) }
        if (hasActiveOverride != lastHasActiveOverride) {
            binding.buttonOverride.text = if (hasActiveOverride) {
                getString(R.string.cancel_override)
            } else {
                getString(R.string.override)
            }
            lastHasActiveOverride = hasActiveOverride
        }

        val lockdownActive = storage.isLockdownActive()
        if (lockdownActive != lastLockdownActive) {
            binding.buttonLockdown.text = if (lockdownActive) {
                getString(R.string.cancel_lockdown)
            } else {
                getString(R.string.lockdown)
            }
            lastLockdownActive = lockdownActive
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5_000L
    }

    private fun showOverrideFlow() {
        val overrideEligible = storage.getBlockSets().filter { it.allowOverride }
        if (overrideEligible.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_override_block_sets), Toast.LENGTH_SHORT).show()
            return
        }
        val authMode = storage.getOverrideAuthMode()
        if (authMode == Storage.OVERRIDE_AUTH_NONE) {
            showOverrideMinutesDialog(overrideEligible)
            return
        }
        val prompt = AuthFlow.promptForPasswordOrRandomCode(
            mode = authMode,
            passwordMode = Storage.OVERRIDE_AUTH_PASSWORD,
            randomMode = Storage.OVERRIDE_AUTH_RANDOM,
            password = storage.getOverridePassword(),
            randomCodeLength = storage.getOverrideRandomCodeLength(),
            passwordMessage = getString(R.string.enter_password_to_continue),
            randomCodeMessage = getString(R.string.override_random_password_message)
        ) ?: return
        showPasswordDialog(
            headerResId = R.string.override,
            message = prompt.message,
            expectedPassword = prompt.expectedPassword,
            displayPassword = prompt.displayPassword,
            incorrectToastResId = R.string.override_password_incorrect,
            positiveButtonResId = R.string.continue_label,
            inputType = if (prompt.displayPassword) {
                InputType.TYPE_CLASS_TEXT
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
            onAuthorized = { showOverrideMinutesDialog(overrideEligible) }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        binding.toolbar.overflowIcon?.setTint(ContextCompat.getColor(this, android.R.color.white))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsAccessFlow(header: CharSequence, onAuthorized: () -> Unit) {
        val authMode = storage.getSettingsAuthMode()
        if (authMode == Storage.OVERRIDE_AUTH_NONE) {
            onAuthorized()
            return
        }
        val prompt = AuthFlow.promptForPasswordOrRandomCode(
            mode = authMode,
            passwordMode = Storage.OVERRIDE_AUTH_PASSWORD,
            randomMode = Storage.OVERRIDE_AUTH_RANDOM,
            password = storage.getSettingsPassword(),
            randomCodeLength = storage.getSettingsRandomCodeLength(),
            passwordMessage = getString(R.string.enter_password_to_continue),
            randomCodeMessage = getString(R.string.settings_random_password_message)
        ) ?: return
        showPasswordDialog(
            header = header,
            message = prompt.message,
            expectedPassword = prompt.expectedPassword,
            displayPassword = prompt.displayPassword,
            incorrectToastResId = R.string.settings_password_incorrect,
            positiveButtonResId = R.string.continue_label,
            inputType = InputType.TYPE_CLASS_TEXT,
            onAuthorized = onAuthorized
        )
    }

    private fun showOverrideMinutesDialog(blockSets: List<BlockSet>) {
        showPositiveNumberDialog(
            titleResId = R.string.override,
            hintResId = R.string.override_minutes,
            placeholderResId = R.string.override_minutes_hint,
            positiveButtonResId = R.string.override,
            invalidValueToastResId = R.string.enter_override_minutes
        ) { minutes ->
            blockSets.forEach { blockSet ->
                storage.setOverrideMinutes(blockSet.id, minutes)
            }
            refreshData()
        }
    }

    private fun showLockdownHoursDialog() {
        showPositiveDecimalDialog(
            titleResId = R.string.lockdown,
            hintResId = R.string.lockdown_hours,
            placeholderResId = R.string.lockdown_hours_hint,
            positiveButtonResId = R.string.lockdown,
            invalidValueToastResId = R.string.enter_lockdown_hours
        ) { hours ->
            storage.setLockdownHours(hours)
            refreshData()
        }
    }

    private fun showPositiveDecimalDialog(
        titleResId: Int,
        hintResId: Int,
        placeholderResId: Int,
        positiveButtonResId: Int,
        invalidValueToastResId: Int,
        onValidValue: (Double) -> Unit
    ) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(hintResId)
            placeholderText = getString(placeholderResId)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(positiveButtonResId) { _, _ ->
                val value = input.text
                    ?.toString()
                    ?.trim()
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
                if (value == null || value <= 0.0) {
                    Toast.makeText(this, getString(invalidValueToastResId), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onValidValue(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPositiveNumberDialog(
        titleResId: Int,
        hintResId: Int,
        placeholderResId: Int,
        positiveButtonResId: Int,
        invalidValueToastResId: Int,
        onValidValue: (Int) -> Unit
    ) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(hintResId)
            placeholderText = getString(placeholderResId)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(positiveButtonResId) { _, _ ->
                val value = input.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value <= 0) {
                    Toast.makeText(this, getString(invalidValueToastResId), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onValidValue(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openSystemSettings(action: String, data: Uri? = null) {
        val intent = Intent(action).apply {
            data?.let { this.data = it }
        }
        startActivity(intent)
    }

    private fun showLockdownCancelFlow() {
        val authMode = storage.getLockdownCancelAuthMode()
        if (authMode == Storage.LOCKDOWN_CANCEL_DISABLED) {
            Toast.makeText(this, getString(R.string.lockdown_cannot_cancel), Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = AuthFlow.promptForPasswordOrRandomCode(
            mode = authMode,
            passwordMode = Storage.LOCKDOWN_CANCEL_PASSWORD,
            randomMode = Storage.LOCKDOWN_CANCEL_RANDOM,
            password = storage.getLockdownPassword(),
            randomCodeLength = storage.getLockdownRandomCodeLength(),
            passwordMessage = getString(R.string.enter_password_to_continue),
            randomCodeMessage = getString(R.string.lockdown_random_password_message)
        ) ?: return
        showPasswordDialog(
            headerResId = R.string.cancel_lockdown,
            message = prompt.message,
            expectedPassword = prompt.expectedPassword,
            displayPassword = prompt.displayPassword,
            incorrectToastResId = R.string.lockdown_password_incorrect,
            positiveButtonResId = R.string.continue_label,
            inputType = if (prompt.displayPassword) {
                InputType.TYPE_CLASS_TEXT
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
            onAuthorized = {
                storage.clearLockdown()
                refreshData()
            }
        )
    }

    private fun checkPermissions() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasUsageStats = hasUsageStatsPermission()
        val hasOverlay = hasOverlayPermission()

        if (hasAccessibility && hasUsageStats && hasOverlay) {
            binding.cardPermissions.visibility = View.GONE
        } else {
            binding.cardPermissions.visibility = View.VISIBLE
            binding.buttonEnableAccessibility.visibility =
                if (hasAccessibility) View.GONE else View.VISIBLE
            binding.buttonEnableUsageStats.visibility =
                if (hasUsageStats) View.GONE else View.VISIBLE
            binding.buttonEnableOverlay.visibility =
                if (hasOverlay) View.GONE else View.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${AppBlockerService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(serviceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }
}
