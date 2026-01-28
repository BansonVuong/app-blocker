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
import java.security.SecureRandom
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
    private val secureRandom = SecureRandom()

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
                refreshData()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(refreshRunnable!!, 1000L)
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
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        binding.buttonEnableUsageStats.setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }

        binding.buttonEnableOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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

        setupDebugTools()
    }

    // ===== Debug-only UI (easy to remove) =====
    private fun setupDebugTools() {
        if (!BuildConfig.DEBUG_TOOLS_ENABLED) {
            binding.switchDebugOverlay.visibility = View.GONE
            binding.switchDebugLogCapture.visibility = View.GONE
            binding.buttonExportLogs.visibility = View.GONE
            binding.buttonShareLogs.visibility = View.GONE
            return
        }
        binding.switchDebugOverlay.isChecked = storage.isDebugOverlayEnabled()
        binding.switchDebugOverlay.setOnCheckedChangeListener { _, isChecked ->
            storage.setDebugOverlayEnabled(isChecked)
        }

        binding.switchDebugLogCapture.isChecked = storage.isDebugLogCaptureEnabled()
        binding.switchDebugLogCapture.setOnCheckedChangeListener { _, isChecked ->
            storage.setDebugLogCaptureEnabled(isChecked)
        }

        binding.buttonExportLogs.setOnClickListener {
            val result = DebugLogStore.export(this)
            if (result == null) {
                Toast.makeText(this, getString(R.string.export_logs_missing), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val message = if (result.path != null) {
                getString(R.string.export_logs_saved_app_dir, result.path)
            } else {
                getString(R.string.export_logs_saved_downloads, result.displayName)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        binding.buttonShareLogs.setOnClickListener {
            val uri = DebugLogStore.exportForShare(this)
            if (uri == null) {
                Toast.makeText(this, getString(R.string.export_logs_missing), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_debug_logs)))
        }
    }

    private fun openBlockSetEditor(blockSet: BlockSet?) {
        showSettingsAccessFlow {
            val intent = Intent(this, BlockSetActivity::class.java)
            blockSet?.let {
                intent.putExtra(BlockSetActivity.EXTRA_BLOCK_SET_ID, it.id)
            }
            blockSetLauncher.launch(intent)
        }
    }

    private fun refreshData() {
        val blockSets = storage.getBlockSets()
        adapter.setData(blockSets, storage)

        binding.textEmpty.visibility = if (blockSets.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerBlockSets.visibility = if (blockSets.isEmpty()) View.GONE else View.VISIBLE

        val overrideEligible = blockSets.filter { it.allowOverride }
        val hasActiveOverride = overrideEligible.any { storage.isOverrideActive(it) }
        binding.buttonOverride.text = if (hasActiveOverride) {
            getString(R.string.cancel_override)
        } else {
            getString(R.string.override)
        }

        binding.buttonLockdown.text = if (storage.isLockdownActive()) {
            getString(R.string.cancel_lockdown)
        } else {
            getString(R.string.lockdown)
        }
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
        if (authMode == Storage.OVERRIDE_AUTH_PASSWORD) {
            showOverridePasswordDialog(overrideEligible)
            return
        }
        showRandomOverridePasswordDialog(overrideEligible, authMode)
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

    private fun showOverridePasswordDialog(blockSets: List<BlockSet>) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.override_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.override)
            .setView(container)
            .setPositiveButton(R.string.override) { _, _ ->
                val entered = input.text?.toString() ?: ""
                val stored = storage.getOverridePassword()
                if (entered != stored) {
                    Toast.makeText(this, getString(R.string.override_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showOverrideMinutesDialog(blockSets)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRandomOverridePasswordDialog(blockSets: List<BlockSet>, authMode: Int) {
        val randomPassword = generateRandomPassword(authMode)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.override_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.override)
            .setMessage(getString(R.string.override_random_password_message, randomPassword))
            .setView(container)
            .setPositiveButton(R.string.override) { _, _ ->
                val entered = input.text?.toString() ?: ""
                if (entered != randomPassword) {
                    Toast.makeText(this, getString(R.string.override_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showOverrideMinutesDialog(blockSets)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSettingsAccessFlow(onAuthorized: () -> Unit) {
        val authMode = storage.getSettingsAuthMode()
        if (authMode == Storage.OVERRIDE_AUTH_NONE) {
            onAuthorized()
            return
        }
        if (authMode == Storage.OVERRIDE_AUTH_PASSWORD) {
            showSettingsPasswordDialog(onAuthorized)
            return
        }
        showRandomSettingsPasswordDialog(onAuthorized, authMode)
    }

    private fun showSettingsPasswordDialog(onAuthorized: () -> Unit) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.settings_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_access_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString() ?: ""
                val stored = storage.getSettingsPassword()
                if (entered != stored) {
                    Toast.makeText(this, getString(R.string.settings_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onAuthorized()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRandomSettingsPasswordDialog(onAuthorized: () -> Unit, authMode: Int) {
        val randomPassword = generateRandomPassword(authMode)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.settings_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_access_title)
            .setMessage(getString(R.string.settings_random_password_message, randomPassword))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString() ?: ""
                if (entered != randomPassword) {
                    Toast.makeText(this, getString(R.string.settings_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onAuthorized()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateRandomPassword(mode: Int): String {
        val length = when {
            mode == Storage.OVERRIDE_AUTH_RANDOM_64 || mode == Storage.LOCKDOWN_CANCEL_RANDOM_64 -> 64
            mode == Storage.OVERRIDE_AUTH_RANDOM_128 || mode == Storage.LOCKDOWN_CANCEL_RANDOM_128 -> 128
            else -> 32
        }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = alphabet[secureRandom.nextInt(alphabet.length)]
        }
        return String(chars)
    }

    private fun showOverrideMinutesDialog(blockSets: List<BlockSet>) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.override_minutes)
            placeholderText = getString(R.string.override_minutes_hint)
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
            .setTitle(R.string.override)
            .setView(container)
            .setPositiveButton(R.string.override) { _, _ ->
                val minutes = input.text?.toString()?.trim()?.toIntOrNull()
                if (minutes == null || minutes <= 0) {
                    Toast.makeText(this, getString(R.string.enter_override_minutes), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                blockSets.forEach { blockSet ->
                    storage.setOverrideMinutes(blockSet.id, minutes)
                }
                refreshData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLockdownHoursDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.lockdown_hours)
            placeholderText = getString(R.string.lockdown_hours_hint)
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
            .setTitle(R.string.lockdown)
            .setView(container)
            .setPositiveButton(R.string.lockdown) { _, _ ->
                val hours = input.text?.toString()?.trim()?.toIntOrNull()
                if (hours == null || hours <= 0) {
                    Toast.makeText(this, getString(R.string.enter_lockdown_hours), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                storage.setLockdownHours(hours)
                refreshData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLockdownCancelFlow() {
        val authMode = storage.getLockdownCancelAuthMode()
        if (authMode == Storage.LOCKDOWN_CANCEL_DISABLED) {
            Toast.makeText(this, getString(R.string.lockdown_cannot_cancel), Toast.LENGTH_SHORT).show()
            return
        }
        if (authMode == Storage.LOCKDOWN_CANCEL_PASSWORD) {
            showLockdownPasswordDialog()
            return
        }
        showRandomLockdownPasswordDialog(authMode)
    }

    private fun showLockdownPasswordDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.lockdown_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cancel_lockdown)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString() ?: ""
                val stored = storage.getLockdownPassword()
                if (entered != stored) {
                    Toast.makeText(this, getString(R.string.lockdown_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                storage.clearLockdown()
                refreshData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRandomLockdownPasswordDialog(authMode: Int) {
        val randomPassword = generateRandomPassword(authMode)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.lockdown_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cancel_lockdown)
            .setMessage(getString(R.string.lockdown_random_password_message, randomPassword))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString() ?: ""
                if (entered != randomPassword) {
                    Toast.makeText(this, getString(R.string.lockdown_password_incorrect), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                storage.clearLockdown()
                refreshData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
