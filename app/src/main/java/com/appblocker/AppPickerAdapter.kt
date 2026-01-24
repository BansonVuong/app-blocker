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

        private val basePaddingStart = binding.root.paddingLeft
        private val basePaddingTop = binding.root.paddingTop
        private val basePaddingEnd = binding.root.paddingRight
        private val basePaddingBottom = binding.root.paddingBottom
        private val baseIconSize = binding.imageIcon.layoutParams.width

        fun bind(app: AppInfo) {
            binding.imageIcon.setImageDrawable(app.icon)
            binding.textAppName.text = app.appName
            val context = binding.root.context
            binding.textUsageTime.text = if (app.isVirtual) {
                when (app.parentPackage) {
                    AppTargets.SNAPCHAT_PACKAGE -> context.getString(R.string.snapchat_tab_label)
                    AppTargets.INSTAGRAM_PACKAGE -> context.getString(R.string.instagram_reels_label)
                    else -> context.getString(R.string.snapchat_tab_label)
                }
            } else {
                formatUsageTime(context, app.usageSecondsLastWeek)
            }
            binding.checkbox.isChecked = selectedPackages.contains(app.packageName)

            val indent = if (app.isVirtual) dpToPx(context, 20) else 0
            binding.root.setPadding(
                basePaddingStart + indent,
                basePaddingTop,
                basePaddingEnd,
                basePaddingBottom
            )

            val iconParams = binding.imageIcon.layoutParams
            if (app.isVirtual) {
                val size = dpToPx(context, 32)
                iconParams.width = size
                iconParams.height = size
                binding.imageIcon.alpha = 0.7f
            } else {
                iconParams.width = baseIconSize
                iconParams.height = baseIconSize
                binding.imageIcon.alpha = 1f
            }
            binding.imageIcon.layoutParams = iconParams

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

    private fun dpToPx(context: android.content.Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
