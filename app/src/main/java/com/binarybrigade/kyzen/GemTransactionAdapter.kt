package com.binarybrigade.kyzen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GemTransactionAdapter — RecyclerView adapter for the gem history list.
 *
 * Each row shows:
 *   [colour bar] [💎] [Label / Time]   [±amount]
 *                                       [gem]
 *
 * Colour bar + amount colour-coded:
 *   Green  — any credit (PRODUCTIVE_EARN, DETOX_BONUS, FIRST_LAUNCH, PARENT_GIFT)
 *   Amber  — entertainment spend (ENTERTAINMENT_SPEND)
 *   Red    — parent deduction (PARENT_DEDUCT)
 */
class GemTransactionAdapter(
    private var transactions: List<GemTransactionEntity>
) : RecyclerView.Adapter<GemTransactionAdapter.ViewHolder>() {

    private val timeOnlyFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateTimeFormat  = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
    private val dayFormat       = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar:  View     = view.findViewById(R.id.viewTxColorBar)
        val txtAmount: TextView = view.findViewById(R.id.txtTxAmount)
        val txtLabel:  TextView = view.findViewById(R.id.txtTxLabel)
        val txtTime:   TextView = view.findViewById(R.id.txtTxTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gem_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = transactions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tx  = transactions[position]
        val ctx = holder.itemView.context

        // Resolve colour based on transaction type
        val colorRes = when (tx.type) {
            GemTransactionRepository.TYPE_ENTERTAINMENT_SPEND ->
                R.color.kyzen_amber_dark
            GemTransactionRepository.TYPE_PARENT_DEDUCT ->
                R.color.kyzen_red_medium
            else ->
                R.color.kyzen_green_dark
        }
        val color = ContextCompat.getColor(ctx, colorRes)

        // Left colour bar
        holder.colorBar.setBackgroundColor(color)

        // Label
        holder.txtLabel.text = tx.label

        // Amount — always show sign explicitly e.g. "+1" or "-3"
        holder.txtAmount.text = if (tx.amount >= 0) "+${tx.amount}" else "${tx.amount}"
        holder.txtAmount.setTextColor(color)

        // Time — "10:32 AM" if today, "Mon, 10:32 AM" if older
        val txDate   = Date(tx.timestampMs)
        val todayStr = dayFormat.format(Date())
        val txDayStr = dayFormat.format(txDate)
        holder.txtTime.text = if (txDayStr == todayStr) {
            "Today · ${timeOnlyFormat.format(txDate)}"
        } else {
            dateTimeFormat.format(txDate)
        }
    }

    fun updateData(newTransactions: List<GemTransactionEntity>) {
        transactions = newTransactions
        notifyDataSetChanged()
    }
}
