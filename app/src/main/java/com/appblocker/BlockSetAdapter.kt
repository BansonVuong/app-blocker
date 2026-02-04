package com.appblocker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.appblocker.databinding.ItemBlockSetBinding
import kotlin.math.roundToInt

class BlockSetAdapter(
    private val onItemClick: (BlockSet) -> Unit
) : RecyclerView.Adapter<BlockSetAdapter.ViewHolder>() {

    private var blockSets: List<BlockSet> = emptyList()
    private lateinit var storage: Storage
    private val timerPayload = Any()

    fun setData(blockSets: List<BlockSet>, storage: Storage) {
        this.blockSets = blockSets
        this.storage = storage
        notifyDataSetChanged()
    }

    fun refreshDynamicState() {
        if (blockSets.isEmpty()) return
        notifyItemRangeChanged(0, blockSets.size, timerPayload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockSetBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(blockSets[position], updateStatic = true)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(timerPayload)) {
            holder.bind(blockSets[position], updateStatic = false)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = blockSets.size

    inner class ViewHolder(private val binding: ItemBlockSetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(blockSet: BlockSet, updateStatic: Boolean) {
            if (updateStatic) {
                binding.textName.text = blockSet.name
                val appCount = blockSet.apps.size
                binding.textApps.text = binding.root.context.getString(R.string.apps_selected, appCount)
                binding.root.setOnClickListener {
                    onItemClick(blockSet)
                }
            }

            val remainingSeconds = storage.getRemainingSeconds(blockSet)
            val overrideSeconds = storage.getOverrideRemainingSeconds(blockSet)
            val displaySeconds = if (overrideSeconds > 0) overrideSeconds else remainingSeconds
            val totalSeconds = (blockSet.quotaMinutes * 60).roundToInt()
            val usedSeconds = totalSeconds - remainingSeconds
            val progress = if (totalSeconds > 0) (usedSeconds * 100) / totalSeconds else 0

            if (storage.isLockdownActive()) {
                val lockdownSeconds = storage.getLockdownRemainingSeconds()
                val hours = lockdownSeconds / 3600
                val minutes = (lockdownSeconds % 3600) / 60
                binding.textRemaining.text = binding.root.context.getString(
                    R.string.lockdown_time_left,
                    hours,
                    minutes
                )
                binding.progressQuota.progress = 100
                binding.progressQuota.setIndicatorColor(
                    binding.root.context.getColor(R.color.red)
                )
            } else {
                binding.progressQuota.progress = progress

                // Format as MM:SS
                val minutes = displaySeconds / 60
                val seconds = displaySeconds % 60
                binding.textRemaining.text = binding.root.context.getString(
                    R.string.time_left,
                    minutes,
                    seconds
                )

                // Color based on remaining time (in seconds)
                val color = when {
                    displaySeconds <= 0 -> binding.root.context.getColor(R.color.red)
                    displaySeconds <= 60 -> binding.root.context.getColor(R.color.accent)  // Last minute
                    else -> binding.root.context.getColor(R.color.green)
                }
                binding.progressQuota.setIndicatorColor(color)
            }

        }
    }
}
