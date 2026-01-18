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

    fun setData(blockSets: List<BlockSet>, storage: Storage) {
        this.blockSets = blockSets
        this.storage = storage
        notifyDataSetChanged()
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
        holder.bind(blockSets[position])
    }

    override fun getItemCount() = blockSets.size

    inner class ViewHolder(private val binding: ItemBlockSetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(blockSet: BlockSet) {
            binding.textName.text = blockSet.name

            val appCount = blockSet.apps.size
            binding.textApps.text = binding.root.context.getString(R.string.apps_selected, appCount)

            val remainingSeconds = storage.getRemainingSeconds(blockSet)
            val totalSeconds = (blockSet.quotaMinutes * 60).roundToInt()
            val usedSeconds = totalSeconds - remainingSeconds
            val progress = if (totalSeconds > 0) (usedSeconds * 100) / totalSeconds else 0

            binding.progressQuota.progress = progress

            // Format as MM:SS
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            binding.textRemaining.text = String.format("%d:%02d left", minutes, seconds)

            // Color based on remaining time (in seconds)
            val color = when {
                remainingSeconds <= 0 -> binding.root.context.getColor(R.color.red)
                remainingSeconds <= 60 -> binding.root.context.getColor(R.color.accent)  // Last minute
                else -> binding.root.context.getColor(R.color.green)
            }
            binding.progressQuota.setIndicatorColor(color)

            binding.root.setOnClickListener {
                onItemClick(blockSet)
            }
        }
    }
}
