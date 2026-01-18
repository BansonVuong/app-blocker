package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.appblocker.databinding.ActivityBlockSetBinding
import java.util.Locale

class BlockSetActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockSetBinding
    private lateinit var storage: Storage

    private var blockSet: BlockSet? = null
    private var selectedApps: MutableList<String> = mutableListOf()

    companion object {
        const val EXTRA_BLOCK_SET_ID = "block_set_id"

        // Window options in minutes
        val WINDOW_OPTIONS = listOf(5, 10, 15, 20, 30, 60)
        val WINDOW_LABELS = listOf("5 minutes", "10 minutes", "15 minutes", "20 minutes", "30 minutes", "1 hour")
        private const val DEFAULT_QUOTA_MINUTES = 30.0
    }

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED_APPS)
            if (apps != null) {
                selectedApps = apps.toMutableList()
                updateSelectedAppsText()
                persistSelectedApps()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockSetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = App.instance.storage

        setupSpinners()

        val blockSetId = intent.getStringExtra(EXTRA_BLOCK_SET_ID)
        if (blockSetId != null) {
            blockSet = storage.getBlockSets().find { it.id == blockSetId }
            blockSet?.let {
                binding.toolbar.title = getString(R.string.edit_block_set)
                binding.editName.setText(it.name)
                binding.editQuota.setText(formatQuotaMinutes(it.quotaMinutes))
                binding.spinnerWindow.setSelection(WINDOW_OPTIONS.indexOf(it.windowMinutes).coerceAtLeast(0))
                binding.checkCombinedQuota.isChecked = it.combinedQuota
                selectedApps = it.apps.toMutableList()
                binding.buttonDelete.visibility = View.VISIBLE
            }
        } else {
            val existingCount = storage.getBlockSets().size
            binding.editName.setText(getString(R.string.default_block_set_name, existingCount + 1))
            binding.editQuota.setText(formatQuotaMinutes(DEFAULT_QUOTA_MINUTES))
        }

        updateSelectedAppsText()
        setupClickListeners()
    }

    private fun setupSpinners() {
        val windowAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, WINDOW_LABELS)
        windowAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerWindow.adapter = windowAdapter
        binding.spinnerWindow.setSelection(5) // Default to 1 hour
    }

    private fun setupClickListeners() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.buttonSelectApps.setOnClickListener {
            val intent = Intent(this, AppPickerActivity::class.java)
            intent.putStringArrayListExtra(
                AppPickerActivity.EXTRA_SELECTED_APPS,
                ArrayList(selectedApps)
            )
            appPickerLauncher.launch(intent)
        }

        binding.buttonCancel.setOnClickListener {
            finish()
        }

        binding.buttonSave.setOnClickListener {
            saveBlockSet()
        }

        binding.buttonDelete.setOnClickListener {
            deleteBlockSet()
        }
    }

    private fun updateSelectedAppsText() {
        binding.textSelectedApps.text = getString(R.string.apps_selected, selectedApps.size)
    }

    private fun persistSelectedApps() {
        val existingBlockSet = blockSet ?: return
        val blockSets = storage.getBlockSets()
        val index = blockSets.indexOfFirst { it.id == existingBlockSet.id }
        if (index >= 0) {
            val updated = existingBlockSet.copy(apps = selectedApps)
            blockSets[index] = updated
            storage.saveBlockSets(blockSets)
            blockSet = updated
        }
    }

    private fun saveBlockSet() {
        val name = binding.editName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
            return
        }

        val quotaText = binding.editQuota.text?.toString()?.trim().orEmpty()
        val normalizedQuotaText = quotaText.replace(',', '.')
        val quota = normalizedQuotaText.toDoubleOrNull()
        if (quota == null || quota <= 0) {
            Toast.makeText(this, "Please enter a valid quota in minutes", Toast.LENGTH_SHORT).show()
            return
        }

        val windowIndex = binding.spinnerWindow.selectedItemPosition
        val window = WINDOW_OPTIONS[windowIndex]
        val combinedQuota = binding.checkCombinedQuota.isChecked

        val blockSets = storage.getBlockSets()

        if (blockSet != null) {
            // Update existing - find it in the fresh list by ID
            val index = blockSets.indexOfFirst { it.id == blockSet!!.id }
            if (index >= 0) {
                blockSets[index] = blockSet!!.copy(
                    name = name,
                    quotaMinutes = quota,
                    windowMinutes = window,
                    combinedQuota = combinedQuota,
                    apps = selectedApps
                )
            }
        } else {
            // Create new
            val newBlockSet = BlockSet(
                name = name,
                quotaMinutes = quota,
                windowMinutes = window,
                combinedQuota = combinedQuota,
                apps = selectedApps
            )
            blockSets.add(newBlockSet)
        }

        storage.saveBlockSets(blockSets)
        finish()
    }

    private fun deleteBlockSet() {
        blockSet?.let {
            val blockSets = storage.getBlockSets()
            blockSets.removeIf { bs -> bs.id == it.id }
            storage.saveBlockSets(blockSets)
        }
        finish()
    }

    private fun formatQuotaMinutes(minutes: Double): String {
        return if (minutes % 1.0 == 0.0) {
            minutes.toInt().toString()
        } else {
            val formatted = String.format(Locale.US, "%.2f", minutes)
            formatted.trimEnd('0').trimEnd('.')
        }
    }
}
