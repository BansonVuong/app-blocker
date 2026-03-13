package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appblocker.databinding.ActivityAppPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var adapter: AppPickerAdapter
    private var preSelectedApps: Set<String> = emptySet()
    private var allApps: List<AppInfo> = emptyList()
    private var sortMode: SortMode = SortMode.TIME_SPENT

    companion object {
        const val EXTRA_SELECTED_APPS = "selected_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preSelectedApps = (intent.getStringArrayListExtra(EXTRA_SELECTED_APPS) ?: arrayListOf()).toSet()

        setupRecyclerView(preSelectedApps.toList())
        setupSortSpinner()
        setupClickListeners()
        loadApps()
    }

    private fun setupRecyclerView(preSelectedApps: List<String>) {
        adapter = AppPickerAdapter { _ -> }
        adapter.setSelectedPackages(preSelectedApps)
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        applySortAndShow()
    }

    private fun setupClickListeners() {
        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        binding.buttonDone.setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putStringArrayListExtra(
                EXTRA_SELECTED_APPS,
                ArrayList(adapter.getSelectedPackages())
            )
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun setupSortSpinner() {
        binding.spinnerSort.setSelection(0, false)
        binding.spinnerSort.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                sortMode = if (position == 1) SortMode.ALPHABETICAL else SortMode.TIME_SPENT
                applySortAndShow()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val pm = packageManager
            val apps = withContext(Dispatchers.IO) {
                val installedApps = pm.getInstalledApplications(0)
                    .filter { appInfo ->
                        // Only show launchable apps (not system services)
                        pm.getLaunchIntentForPackage(appInfo.packageName) != null &&
                            appInfo.packageName != packageName
                    }
                val packageNames = installedApps.map { it.packageName }.toSet()
                val usageSecondsByPackage = App.instance.storage.getUsageSecondsLastWeek(packageNames)
                installedApps.map { appInfo ->
                    val usageSeconds = usageSecondsByPackage[appInfo.packageName] ?: 0
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appInfo.loadLabel(pm).toString(),
                        icon = appInfo.loadIcon(pm),
                        usageSecondsLastWeek = usageSeconds
                    )
                }
            }

            if (isFinishing || isDestroyed) return@launch
            allApps = apps
            applySortAndShow()
            binding.progressBar.visibility = View.GONE
            binding.recyclerApps.visibility = View.VISIBLE
        }
    }

    private fun applySortAndShow() {
        if (allApps.isEmpty()) return
        val selected = adapter.getSelectedPackages().toSet()
        val comparator = when (sortMode) {
            SortMode.TIME_SPENT -> compareByDescending<AppInfo> { selected.contains(it.packageName) }
                .thenByDescending { it.usageSecondsLastWeek }
                .thenBy { it.appName.lowercase() }
            SortMode.ALPHABETICAL -> compareByDescending<AppInfo> { selected.contains(it.packageName) }
                .thenBy { it.appName.lowercase() }
        }
        adapter.setApps(allApps.sortedWith(comparator))
    }

    private enum class SortMode {
        TIME_SPENT,
        ALPHABETICAL
    }
}
