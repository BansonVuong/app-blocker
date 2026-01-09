package com.appblocker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.appblocker.databinding.ItemBlockSetBinding

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

            val remaining = storage.getRemainingMinutes(blockSet)
            val total = blockSet.quotaMinutes
            val used = total - remaining
            val progress = if (total > 0) (used * 100) / total else 0

            binding.progressQuota.progress = progress
            binding.textRemaining.text = binding.root.context.getString(R.string.time_remaining, remaining)

            // Color based on remaining time
            val color = when {
                remaining <= 0 -> binding.root.context.getColor(R.color.red)
                remaining <= 10 -> binding.root.context.getColor(R.color.accent)
                else -> binding.root.context.getColor(R.color.green)
            }
            binding.progressQuota.setIndicatorColor(color)

            binding.root.setOnClickListener {
                onItemClick(blockSet)
            }
        }
    }
}
