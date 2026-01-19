package com.appblocker

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.appblocker.databinding.ActivityAppPickerBinding
import kotlin.concurrent.thread

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
        adapter = AppPickerAdapter { _ ->
            // Selection changed, nothing to do here
        }
        adapter.setSelectedPackages(preSelectedApps)
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter
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
        thread {
            val pm = packageManager
            val installedApps = pm.getInstalledApplications(0)
                .filter { appInfo ->
                    // Only show launchable apps (not system services)
                    pm.getLaunchIntentForPackage(appInfo.packageName) != null &&
                            appInfo.packageName != packageName
                }
            val packageNames = installedApps.map { it.packageName }.toSet()
            val usageSecondsByPackage = App.instance.storage.getUsageSecondsLastWeek(packageNames)
            val apps = installedApps.map { appInfo ->
                val usageSeconds = usageSecondsByPackage[appInfo.packageName] ?: 0
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = appInfo.loadLabel(pm).toString(),
                    icon = appInfo.loadIcon(pm),
                    usageSecondsLastWeek = usageSeconds
                )
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                applySortAndShow()
                binding.progressBar.visibility = View.GONE
                binding.recyclerApps.visibility = View.VISIBLE
            }
        }
    }

    private fun applySortAndShow() {
        if (allApps.isEmpty()) return
        val sorted = when (sortMode) {
            SortMode.TIME_SPENT -> allApps.sortedWith(
                compareByDescending<AppInfo> { it.usageSecondsLastWeek }
                    .thenBy { it.appName.lowercase() }
            )
            SortMode.ALPHABETICAL -> allApps.sortedBy { it.appName.lowercase() }
        }
        adapter.setApps(insertSnapchatVirtuals(sorted))
    }

    private fun insertSnapchatVirtuals(apps: List<AppInfo>): List<AppInfo> {
        val mutable = apps.toMutableList()
        val snapchatIndex = mutable.indexOfFirst { it.packageName == AppTargets.SNAPCHAT_PACKAGE }
        if (snapchatIndex < 0) return mutable

        val snapchatApp = mutable[snapchatIndex]
        val stories = snapchatApp.copy(
            packageName = AppTargets.SNAPCHAT_STORIES,
            appName = "Stories",
            usageSecondsLastWeek = 0,
            isVirtual = true,
            parentPackage = AppTargets.SNAPCHAT_PACKAGE
        )
        val spotlight = snapchatApp.copy(
            packageName = AppTargets.SNAPCHAT_SPOTLIGHT,
            appName = "Spotlight",
            usageSecondsLastWeek = 0,
            isVirtual = true,
            parentPackage = AppTargets.SNAPCHAT_PACKAGE
        )
        mutable.add(snapchatIndex + 1, stories)
        mutable.add(snapchatIndex + 2, spotlight)
        return mutable
    }

    private enum class SortMode {
        TIME_SPENT,
        ALPHABETICAL
    }
}
