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

    companion object {
        const val EXTRA_SELECTED_APPS = "selected_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preSelectedApps = (intent.getStringArrayListExtra(EXTRA_SELECTED_APPS) ?: arrayListOf()).toSet()

        setupRecyclerView(preSelectedApps.toList())
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
                .sortedWith(
                    compareByDescending<AppInfo> { preSelectedApps.contains(it.packageName) }
                        .thenByDescending { it.usageSecondsLastWeek }
                        .thenBy { it.appName.lowercase() }
                )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.setApps(apps)
                binding.progressBar.visibility = View.GONE
                binding.recyclerApps.visibility = View.VISIBLE
            }
        }
    }
}
