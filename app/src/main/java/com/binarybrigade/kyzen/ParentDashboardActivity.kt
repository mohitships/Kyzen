package com.binarybrigade.kyzen

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ParentDashboardActivity — Thin ViewPager2 host for the parent-facing dashboard.
 * Accessible only after successful PIN verification via PinEntryActivity.
 *
 * Page 0 — ParentSettingsFragment : Summary stats + all parent controls
 * Page 1 — ParentUsageFragment    : Today's app usage list
 *
 * All business logic lives in the respective Fragment. This Activity is a
 * thin host: sets up the pager, adapter, dot indicators, and the date header.
 *
 * Daily reset guard lives here (not in a fragment) so it always runs exactly
 * once regardless of which page is visible, and before any fragment resumes.
 * It never references any fragment views — safe to call before fragments attach.
 */
class ParentDashboardActivity : AppCompatActivity() {

    private lateinit var prefs: KyzenPreferences
    private lateinit var viewPager: ViewPager2
    private lateinit var dot0: View
    private lateinit var dot1: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_dashboard)

        prefs = KyzenPreferences(this)

        viewPager = findViewById(R.id.viewPagerParent)
        dot0      = findViewById(R.id.dot0parent)
        dot1      = findViewById(R.id.dot1parent)

        viewPager.adapter = ParentPagerAdapter(this)
        // Keep both pages alive — smooth swipe, no reload on swipe back
        viewPager.offscreenPageLimit = 1

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dot0.setBackgroundResource(
                    if (position == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
                )
                dot1.setBackgroundResource(
                    if (position == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
                )
            }
        })
    }

    override fun onResume() {
        super.onResume()

        // Daily reset guard — mirrors ChildDashboardActivity and DashboardFragment.
        // Runs here (not in a fragment) so it executes exactly once per resume,
        // before any fragment's onResume fires, and with no view references.
        // If the service was killed overnight, this ensures the full daily reset
        // (including txRepository.clearAll()) runs when the parent opens the app.
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        if (prefs.lastResetDate != today) {
            prefs.performDailyReset(today)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        UsageRepository(applicationContext).pruneOldRecords()
                        GemTransactionRepository(applicationContext).clearAll()
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Non-critical — log and continue
                }
            }
        }
    }

    // ── Pager Adapter ──────────────────────────────────────────────────────────

    private inner class ParentPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> ParentSettingsFragment.newInstance()
            else -> ParentUsageFragment.newInstance()
        }
    }
}
