package com.binarybrigade.kyzen

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

/**
 * ChildDashboardActivity — ViewPager2 host for the child-facing dashboard.
 *
 * Page 0 — DashboardFragment  : Hero wallet card + Action grid (GEMS + DETOX)
 * Page 1 — ActivityFragment   : Today's app usage list
 *
 * All business logic lives in the respective Fragment. This Activity is a
 * thin host: sets up the pager, adapter, and dot indicators only.
 */
class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var dot0: View
    private lateinit var dot1: View
    private lateinit var dot2: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_dashboard)

        // Welcome bonus is now awarded in ModeSelectionActivity — mode-agnostic.
        // Removed from here to prevent any possibility of double-award.

        viewPager = findViewById(R.id.viewPagerDashboard)
        dot0      = findViewById(R.id.dot0)
        dot1      = findViewById(R.id.dot1)
        dot2      = findViewById(R.id.dot2)

        viewPager.adapter = DashboardPagerAdapter(this)
        viewPager.offscreenPageLimit = 2 // keep all 3 pages alive for smooth swipe

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dot0.setBackgroundResource(
                    if (position == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
                )
                dot1.setBackgroundResource(
                    if (position == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
                )
                dot2.setBackgroundResource(
                    if (position == 2) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
                )
            }
        })
    }

    // ── Pager Adapter ─────────────────────────────────────────────────────────

    private inner class DashboardPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> DashboardFragment.newInstance()
            1    -> ActivityFragment.newInstance()
            else -> GemHistoryFragment.newInstance()
        }
    }
}
