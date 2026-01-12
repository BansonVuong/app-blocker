package com.appblocker

import android.content.Intent
import android.content.pm.ApplicationInfo
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
                .map { appInfo ->
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appInfo.loadLabel(pm).toString(),
                        icon = appInfo.loadIcon(pm)
                    )
                }
                .sortedWith(compareBy(
                    { !preSelectedApps.contains(it.packageName) },
                    { it.appName.lowercase() }
                ))

            runOnUiThread {
                adapter.setApps(installedApps)
                binding.progressBar.visibility = View.GONE
                binding.recyclerApps.visibility = View.VISIBLE
            }
        }
    }
}
