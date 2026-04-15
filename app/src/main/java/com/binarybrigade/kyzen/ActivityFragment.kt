package com.binarybrigade.kyzen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ActivityFragment — Page 1 (index 1) of the child dashboard ViewPager2.
 * Contains: "Your Activity" title + app usage RecyclerView.
 * Swiped to from DashboardFragment (Page 1).
 *
 * Data source: UsageTracker.getSanitizedUsageList() — fresh OS query every onResume.
 * This ensures the Activity tab always shows live, up-to-date usage even if the child
 * swipes directly here without DashboardFragment having refreshed first.
 *
 * The double-query concern (both fragments calling getSanitizedUsageList) is acceptable:
 * - getSanitizedUsageList() uses OnConflictStrategy.REPLACE so double-writes are safe
 * - The OS UsageStatsManager IPC is fast (~10ms) and non-blocking on Dispatchers.IO
 * - Stale data (reading only from Room) is a worse UX than a minor redundant query
 * - A 5-second dedup guard prevents back-to-back identical queries if both fragments
 *   happen to resume within the same second (e.g. app cold start with ViewPager at page 0)
 */
class ActivityFragment : Fragment() {

    private lateinit var usageTracker: UsageTracker
    private lateinit var appUsageAdapter: AppUsageAdapter
    private lateinit var rvChildAppUsage: RecyclerView

    // Dedup guard: skip the OS query if we ran it less than 5 seconds ago
    // (protects against simultaneous DashboardFragment + ActivityFragment onResume on cold start)
    private var lastLoadTimeMs: Long = 0L
    private val DEDUP_THRESHOLD_MS = 5_000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_activity, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usageTracker    = UsageTracker(requireContext().applicationContext)
        rvChildAppUsage = view.findViewById(R.id.rvChildAppUsage)

        appUsageAdapter = AppUsageAdapter(emptyList())
        rvChildAppUsage.layoutManager = LinearLayoutManager(requireContext())
        rvChildAppUsage.adapter       = appUsageAdapter

        loadActivityData()
    }

    override fun onResume() {
        super.onResume()
        loadActivityData()
    }

    private fun loadActivityData() {
        val now = System.currentTimeMillis()
        // Skip if we loaded very recently — DashboardFragment just wrote fresh data to Room
        // and we can read it from there instead of hitting the OS again immediately.
        if (now - lastLoadTimeMs < DEDUP_THRESHOLD_MS && lastLoadTimeMs > 0L) {
            viewLifecycleOwner.lifecycleScope.launch {
                val appList = try {
                    withContext(Dispatchers.IO) {
                        UsageRepository(requireContext().applicationContext).getTodayUsage()
                    }.sortedByDescending { it.usageDurationMillis }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
                appUsageAdapter.updateData(appList)
            }
            return
        }

        // Fresh OS query — authoritative, live data
        lastLoadTimeMs = now
        viewLifecycleOwner.lifecycleScope.launch {
            val appList = try {
                withContext(Dispatchers.IO) { usageTracker.getSanitizedUsageList() }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            appUsageAdapter.updateData(appList)
        }
    }

    companion object {
        fun newInstance() = ActivityFragment()
    }
}
