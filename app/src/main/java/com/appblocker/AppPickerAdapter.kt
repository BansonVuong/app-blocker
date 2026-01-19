package com.appblocker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.appblocker.databinding.ItemAppBinding

class AppPickerAdapter(
    private val onSelectionChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var apps: List<AppInfo> = emptyList()
    private val selectedPackages = mutableSetOf<String>()

    fun setApps(apps: List<AppInfo>) {
        this.apps = apps
        notifyDataSetChanged()
    }

    fun setSelectedPackages(packages: List<String>) {
        selectedPackages.clear()
        selectedPackages.addAll(packages)
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): List<String> = selectedPackages.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size

    inner class ViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            binding.imageIcon.setImageDrawable(app.icon)
            binding.textAppName.text = app.appName
            binding.textUsageTime.text = formatUsageTime(binding.root.context, app.usageSecondsLastWeek)
            binding.checkbox.isChecked = selectedPackages.contains(app.packageName)

            binding.root.setOnClickListener {
                if (selectedPackages.contains(app.packageName)) {
                    selectedPackages.remove(app.packageName)
                } else {
                    selectedPackages.add(app.packageName)
                }
                binding.checkbox.isChecked = selectedPackages.contains(app.packageName)
                onSelectionChanged(selectedPackages.toList())
            }
        }
    }

    private fun formatUsageTime(context: android.content.Context, usageSeconds: Int): String {
        val totalMinutes = usageSeconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val formatted = if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
        return context.getString(R.string.time_used, formatted)
    }
}
