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
 * ParentUsageFragment — Page 1 (index 1) of ParentDashboardActivity ViewPager2.
 *
 * Shows today's app usage list via AppUsageAdapter — same adapter used by
 * ActivityFragment in the child dashboard, ensuring consistent display.
 *
 * Data source: UsageTracker.getSanitizedUsageList() — fresh OS query on resume.
 * Dedup guard: if loadUsageData() is called within 5 seconds of the last OS query,
 * reads from Room instead. This prevents two concurrent getSanitizedUsageList()
 * calls when ParentSettingsFragment and this fragment resume simultaneously
 * (both are alive with offscreenPageLimit = 1).
 *
 * All coroutines use viewLifecycleOwner.lifecycleScope — safe against view destruction.
 */
class ParentUsageFragment : Fragment() {

    private lateinit var usageTracker: UsageTracker
    private lateinit var appUsageAdapter: AppUsageAdapter
    private lateinit var rvParentAppUsage: RecyclerView

    // Dedup guard — skip OS query if called within 5 seconds
    private var lastLoadTimeMs: Long = 0L
    private val DEDUP_THRESHOLD_MS = 5_000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_parent_usage, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usageTracker     = UsageTracker(requireActivity().applicationContext)
        rvParentAppUsage = view.findViewById(R.id.rvParentAppUsage)

        appUsageAdapter = AppUsageAdapter(emptyList())
        rvParentAppUsage.layoutManager = LinearLayoutManager(requireContext())
        rvParentAppUsage.adapter       = appUsageAdapter

        loadUsageData()
    }

    override fun onResume() {
        super.onResume()
        loadUsageData()
    }

    private fun loadUsageData() {
        val now = System.currentTimeMillis()

        if (now - lastLoadTimeMs < DEDUP_THRESHOLD_MS && lastLoadTimeMs > 0L) {
            // Fast path — read already-classified data from Room
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
        fun newInstance() = ParentUsageFragment()
    }
}
