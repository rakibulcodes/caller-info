package com.rakibulcodes.callerinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.databinding.ItemHistoryCardBinding

class HistoryAdapter(private var items: List<CallerInfoEntity>) :
    RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemHistoryCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        with(holder.binding.cardContent) {
            tvName.text = item.name ?: "Unknown"
            tvNumber.text = item.number

            val carrierText = listOfNotNull(item.carrier, item.country).joinToString(", ")
            tvCarrier.text = if (carrierText.isNotEmpty()) carrierText else "Unknown Carrier"

            // Show additional fields if they exist
            if (!item.email.isNullOrEmpty()) {
                tvEmail.text = item.email
                tvEmail.visibility = View.VISIBLE
            } else {
                tvEmail.visibility = View.GONE
            }

            if (!item.location.isNullOrEmpty()) {
                tvLocation.text = item.location
                tvLocation.visibility = View.VISIBLE
            } else {
                tvLocation.visibility = View.GONE
            }

            val fullAddress = listOfNotNull(item.address1, item.address2).joinToString("\n")
            if (fullAddress.isNotEmpty()) {
                tvAddress.text = fullAddress
                tvAddress.visibility = View.VISIBLE
            } else {
                tvAddress.visibility = View.GONE
            }

            tvTime.visibility = View.VISIBLE
            tvTime.text = getShortTimeSpan(item.timestamp)
        }
    }

    private fun getShortTimeSpan(time: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - time

        return when {
            diff < 60000 -> "now"
            diff < 3600000 -> "${diff / 60000}m"
            diff < 86400000 -> "${diff / 3600000}h"
            diff < 2592000000 -> "${diff / 86400000}d"
            diff < 31536000000 -> "${diff / 2592000000}mo"
            else -> "${diff / 31536000000}y"
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<CallerInfoEntity>) {
        items = newItems
        notifyDataSetChanged()
    }
}

