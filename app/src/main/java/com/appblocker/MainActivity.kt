package com.appblocker

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.appblocker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: Storage
    private lateinit var adapter: BlockSetAdapter

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
    }

    private fun checkPermissions() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasUsageStats = hasUsageStatsPermission()

        if (hasAccessibility && hasUsageStats) {
            binding.cardPermissions.visibility = View.GONE
        } else {
            binding.cardPermissions.visibility = View.VISIBLE
            binding.buttonEnableAccessibility.visibility =
                if (hasAccessibility) View.GONE else View.VISIBLE
            binding.buttonEnableUsageStats.visibility =
                if (hasUsageStats) View.GONE else View.VISIBLE
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
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
