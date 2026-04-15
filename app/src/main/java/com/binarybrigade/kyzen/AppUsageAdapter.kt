package com.binarybrigade.kyzen

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * AppUsageAdapter — RecyclerView Adapter (Phase 3 Upgraded)
 *
 * Phase 3 additions:
 * - Displays AI classifier category badge with colour coding per category
 * - Displays confidence percentage from the weighted keyword classifier
 * - Three-category system aligned with AppClassifier:
 *     PRODUCTIVE    → #2E7D32 (dark green)  📚  earns gems
 *     ENTERTAINMENT → #B71C1C (dark red)    🎮  spends gems
 *     NEUTRAL       → #757575 (grey)        ⚪  no gem effect
 */
class AppUsageAdapter(private var appList: List<AppUsageItem>) :
    RecyclerView.Adapter<AppUsageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView     = view as MaterialCardView
        val imgAppIcon: ImageView      = view.findViewById(R.id.imgAppIcon)
        val txtAppName: TextView       = view.findViewById(R.id.txtAppName)
        val txtUsageTime: TextView     = view.findViewById(R.id.txtUsageTime)
        val txtCategoryBadge: TextView = view.findViewById(R.id.txtCategoryBadge)
        val txtConfidence: TextView    = view.findViewById(R.id.txtConfidence)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = appList[position]

        // App name
        holder.txtAppName.text = item.appName

        // Convert raw milliseconds to a human-readable duration string.
        //
        // Bug A Fix: Previously used TimeUnit.toMinutes() which floor-divides — showing "1m"
        // for 1m 58s and "0m" for 45s. Now we round to nearest minute for cleaner display,
        // and show seconds explicitly for sub-60s usage so "0m" never appears.
        //
        // Bug C Fix: Apps between 30s–59s passed the threshold filter but showed "0m".
        // They now correctly show e.g. "45s".
        //
        // Format rules:
        //   < 60s          → "Xs"       e.g. "45s"
        //   60s–3599s      → "Xm"       e.g. "2m"  (rounded to nearest minute)
        //   ≥ 3600s        → "Xh Ym"    e.g. "1h 3m" (hours floor, remaining minutes rounded)
        val totalSeconds = item.usageDurationMillis / 1000L
        holder.txtUsageTime.text = when {
            totalSeconds < 60L -> {
                // Sub-minute: show exact seconds
                "${totalSeconds}s"
            }
            totalSeconds < 3600L -> {
                // Under 1 hour: round to nearest minute
                val roundedMinutes = (item.usageDurationMillis + 30_000L) / 60_000L
                "${roundedMinutes}m"
            }
            else -> {
                // 1 hour or more: floor hours, round remaining minutes
                val hours = totalSeconds / 3600L
                val remainingMs = item.usageDurationMillis - (hours * 3_600_000L)
                val roundedMinutes = (remainingMs + 30_000L) / 60_000L
                if (roundedMinutes > 0L) "${hours}h ${roundedMinutes}m" else "${hours}h"
            }
        }

        // App icon — safely resolved from PackageManager
        try {
            val pm = holder.itemView.context.packageManager
            val icon = pm.getApplicationIcon(item.packageName)
            holder.imgAppIcon.setImageDrawable(icon)
        } catch (e: PackageManager.NameNotFoundException) {
            holder.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Colour txtUsageTime + card stroke by category
        val (_, timeColor) = getCategoryDisplayInfo(item.category, holder.itemView.context)
        holder.txtUsageTime.setTextColor(timeColor)
        holder.card.strokeColor = timeColor

        // txtCategoryBadge is hidden — null-safe tint update for safety
        holder.txtCategoryBadge.background?.let { bg ->
            val badgeDrawable = DrawableCompat.wrap(bg).mutate()
            DrawableCompat.setTint(badgeDrawable, timeColor)
            holder.txtCategoryBadge.background = badgeDrawable
        }

        // Confidence hidden
        holder.txtConfidence.text = ""
    }

    override fun getItemCount(): Int = appList.size

    /**
     * Returns badge label and colour for each AI category.
     * Three-category system: Productive (green), Entertainment (red), Neutral (grey).
     * Emoji prefix makes the category instantly recognisable at a glance.
     */
    private fun getCategoryDisplayInfo(
        category: AppClassifier.AppCategory,
        context: android.content.Context
    ): Pair<String, Int> {
        return when (category) {
            AppClassifier.AppCategory.PRODUCTIVE    -> Pair("",
                ContextCompat.getColor(context, R.color.kyzen_green_medium))
            AppClassifier.AppCategory.ENTERTAINMENT -> Pair("",
                ContextCompat.getColor(context, R.color.kyzen_red_medium))
            AppClassifier.AppCategory.NEUTRAL       -> Pair("",
                ContextCompat.getColor(context, R.color.kyzen_grey_medium))
        }
    }

    // Replaces the full list and refreshes the RecyclerView
    fun updateData(newList: List<AppUsageItem>) {
        appList = newList
        notifyDataSetChanged()
    }
}
