package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.appblocker.databinding.ActivityBlockSetBinding
import java.util.Calendar
import java.util.Locale

class BlockSetActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockSetBinding
    private lateinit var storage: Storage

    private var blockSet: BlockSet? = null
    private var selectedApps: MutableList<String> = mutableListOf()
    private var timePeriods: MutableList<TimePeriod> = mutableListOf()

    companion object {
        const val EXTRA_BLOCK_SET_ID = "block_set_id"

        // Window options in minutes
        val WINDOW_OPTIONS = listOf(5, 10, 15, 20, 30, 60)
        val INTERVENTION_OPTIONS = listOf(
            BlockSet.INTERVENTION_NONE,
            BlockSet.INTERVENTION_RANDOM
        )
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
                binding.spinnerIntervention.setSelection(
                    INTERVENTION_OPTIONS.indexOf(it.intervention).coerceAtLeast(0)
                )
                if (it.interventionCodeLength > 0) {
                    binding.editInterventionCodeLength.setText(it.interventionCodeLength.toString())
                }
                binding.checkCombinedQuota.isChecked = it.combinedQuota
                binding.checkAllowOverride.isChecked = it.allowOverride
                selectedApps = it.apps.toMutableList()
                timePeriods = it.timePeriods.map { tp -> tp.copy(days = tp.days.toMutableList()) }.toMutableList()
                binding.switchSchedule.isChecked = it.scheduleEnabled
                binding.buttonDelete.visibility = View.VISIBLE
            }
        } else {
            val existingCount = storage.getBlockSets().size
            binding.editName.setText(getString(R.string.default_block_set_name, existingCount + 1))
            binding.editQuota.setText(formatQuotaMinutes(DEFAULT_QUOTA_MINUTES))
        }

        updateSelectedAppsText()
        setupScheduleUI()
        setupClickListeners()
    }

    private fun setupSpinners() {
        val windowLabels = resources.getStringArray(R.array.window_labels)
        val windowAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, windowLabels.toList())
        windowAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerWindow.adapter = windowAdapter
        binding.spinnerWindow.setSelection(5) // Default to 1 hour

        val interventionLabels = resources.getStringArray(R.array.intervention_options)
        val interventionAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            interventionLabels.toList()
        )
        interventionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerIntervention.adapter = interventionAdapter
        binding.spinnerIntervention.setSelection(0)

        binding.editInterventionCodeLength.setText("32")

        binding.spinnerIntervention.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = INTERVENTION_OPTIONS[position]
                binding.layoutInterventionCodeLength.visibility =
                    if (mode == BlockSet.INTERVENTION_RANDOM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }


    private fun setupScheduleUI() {
        binding.layoutSchedule.visibility = if (binding.switchSchedule.isChecked) View.VISIBLE else View.GONE
        binding.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutSchedule.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        refreshTimePeriodsList()
    }

    private fun refreshTimePeriodsList() {
        binding.layoutTimePeriods.removeAllViews()
        for (period in timePeriods) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_time_period, binding.layoutTimePeriods, false)
            itemView.findViewById<TextView>(R.id.textDays).text = period.formatDays()
            itemView.findViewById<TextView>(R.id.textTime).text = period.formatTime()
            itemView.findViewById<ImageButton>(R.id.buttonDelete).setOnClickListener {
                timePeriods.remove(period)
                refreshTimePeriodsList()
            }
            itemView.setOnClickListener {
                showTimePeriodDialog(period)
            }
            binding.layoutTimePeriods.addView(itemView)
        }
    }

    private fun showTimePeriodDialog(existingPeriod: TimePeriod? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_time_period, null)
        val period = existingPeriod?.copy(days = existingPeriod.days.toMutableList())
            ?: TimePeriod(days = mutableListOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
            ))

        val dayButtons = mapOf(
            Calendar.SUNDAY to dialogView.findViewById<MaterialButton>(R.id.btnSun),
            Calendar.MONDAY to dialogView.findViewById<MaterialButton>(R.id.btnMon),
            Calendar.TUESDAY to dialogView.findViewById<MaterialButton>(R.id.btnTue),
            Calendar.WEDNESDAY to dialogView.findViewById<MaterialButton>(R.id.btnWed),
            Calendar.THURSDAY to dialogView.findViewById<MaterialButton>(R.id.btnThu),
            Calendar.FRIDAY to dialogView.findViewById<MaterialButton>(R.id.btnFri),
            Calendar.SATURDAY to dialogView.findViewById<MaterialButton>(R.id.btnSat)
        )

        fun updateDayButtonStyles() {
            for ((day, button) in dayButtons) {
                if (period.days.contains(day)) {
                    button.setBackgroundColor(getColor(R.color.primary))
                    button.setTextColor(getColor(R.color.white))
                } else {
                    button.setBackgroundColor(getColor(R.color.white))
                    button.setTextColor(getColor(R.color.gray))
                }
            }
        }

        updateDayButtonStyles()

        for ((day, button) in dayButtons) {
            button.setOnClickListener {
                if (period.days.contains(day)) {
                    period.days.remove(day)
                } else {
                    period.days.add(day)
                }
                updateDayButtonStyles()
            }
        }

        val btnStartTime = dialogView.findViewById<MaterialButton>(R.id.buttonStartTime)
        val btnEndTime = dialogView.findViewById<MaterialButton>(R.id.buttonEndTime)

        fun formatTimeButton(hour: Int, minute: Int): String {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return String.format("%d:%02d %s", displayHour, minute, amPm)
        }

        btnStartTime.text = formatTimeButton(period.startHour, period.startMinute)
        btnEndTime.text = formatTimeButton(period.endHour, period.endMinute)

        btnStartTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(period.startHour)
                .setMinute(period.startMinute)
                .setTitleText(getString(R.string.time_period_start))
                .build()
            picker.addOnPositiveButtonClickListener {
                period.startHour = picker.hour
                period.startMinute = picker.minute
                btnStartTime.text = formatTimeButton(period.startHour, period.startMinute)
            }
            picker.show(supportFragmentManager, "start_time")
        }

        btnEndTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(period.endHour)
                .setMinute(period.endMinute)
                .setTitleText(getString(R.string.time_period_end))
                .build()
            picker.addOnPositiveButtonClickListener {
                period.endHour = picker.hour
                period.endMinute = picker.minute
                btnEndTime.text = formatTimeButton(period.endHour, period.endMinute)
            }
            picker.show(supportFragmentManager, "end_time")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existingPeriod != null) "Edit Time Period" else "Add Time Period")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                if (period.days.isEmpty()) {
                    Toast.makeText(this, "Select at least one day", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (existingPeriod != null) {
                    val index = timePeriods.indexOfFirst { it.id == existingPeriod.id }
                    if (index >= 0) {
                        timePeriods[index] = period
                    }
                } else {
                    timePeriods.add(period)
                }
                refreshTimePeriodsList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

        binding.buttonAddTimePeriod.setOnClickListener {
            showTimePeriodDialog()
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
            Toast.makeText(this, getString(R.string.enter_block_set_name), Toast.LENGTH_SHORT).show()
            return
        }

        val quotaText = binding.editQuota.text?.toString()?.trim().orEmpty()
        val normalizedQuotaText = quotaText.replace(',', '.')
        val quota = normalizedQuotaText.toDoubleOrNull()
        if (quota == null || quota <= 0) {
            Toast.makeText(this, getString(R.string.enter_quota_minutes), Toast.LENGTH_SHORT).show()
            return
        }

        val windowIndex = binding.spinnerWindow.selectedItemPosition
        val window = WINDOW_OPTIONS[windowIndex]
        val interventionIndex = binding.spinnerIntervention.selectedItemPosition
        val intervention = INTERVENTION_OPTIONS[interventionIndex]
        val interventionCodeLength = binding.editInterventionCodeLength.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 32
        val combinedQuota = binding.checkCombinedQuota.isChecked
        val allowOverride = binding.checkAllowOverride.isChecked
        val scheduleEnabled = binding.switchSchedule.isChecked

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
                    allowOverride = allowOverride,
                    intervention = intervention,
                    interventionCodeLength = interventionCodeLength,
                    apps = selectedApps,
                    scheduleEnabled = scheduleEnabled,
                    timePeriods = timePeriods
                )
            }
        } else {
            // Create new
            val newBlockSet = BlockSet(
                name = name,
                quotaMinutes = quota,
                windowMinutes = window,
                combinedQuota = combinedQuota,
                allowOverride = allowOverride,
                intervention = intervention,
                interventionCodeLength = interventionCodeLength,
                apps = selectedApps,
                scheduleEnabled = scheduleEnabled,
                timePeriods = timePeriods
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
