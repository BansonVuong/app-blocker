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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private val blockSetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val intent = Intent(this, BlockSetActivity::class.java)
        blockSet?.let {
            intent.putExtra(BlockSetActivity.EXTRA_BLOCK_SET_ID, it.id)
        }
        blockSetLauncher.launch(intent)
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
    }

    private fun showOverrideFlow() {
        val overrideEligible = storage.getBlockSets().filter { it.allowOverride }
        if (overrideEligible.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_override_block_sets), Toast.LENGTH_SHORT).show()
            return
        }
        showOverrideMinutesDialog(overrideEligible)
    }

    private fun showOverrideMinutesDialog(blockSets: List<BlockSet>) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.override_minutes)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.override_minutes_hint)
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
