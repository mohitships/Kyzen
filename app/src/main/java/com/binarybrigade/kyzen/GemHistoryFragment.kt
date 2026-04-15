package com.binarybrigade.kyzen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GemHistoryFragment — Page 2 (index 2) of ChildDashboardActivity ViewPager2.
 *
 * Shows the child's gem transaction history — credits and debits —
 * in reverse chronological order (newest first).
 *
 * SDT alignment:
 * - Celebrates every productive earn ("Focused on Coursera")
 * - Frames entertainment spend neutrally ("Entertainment time")
 * - Avoids penalty language — all labels are positive or neutral
 *
 * Data source: GemTransactionRepository → Room gem_transactions table
 * Capped at 50 most recent transactions.
 */
class GemHistoryFragment : Fragment() {

    private lateinit var repository: GemTransactionRepository
    private lateinit var adapter: GemTransactionAdapter
    private lateinit var rvGemHistory: RecyclerView
    private lateinit var cardTransactions: MaterialCardView
    private lateinit var layoutEmptyState: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_gem_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository       = GemTransactionRepository(requireContext().applicationContext)
        rvGemHistory     = view.findViewById(R.id.rvGemHistory)
        cardTransactions = view.findViewById(R.id.cardTransactions)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)

        adapter = GemTransactionAdapter(emptyList())
        rvGemHistory.layoutManager = LinearLayoutManager(requireContext())
        rvGemHistory.adapter = adapter
        rvGemHistory.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            val history = try {
                withContext(Dispatchers.IO) { repository.getHistory() }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }

            if (history.isEmpty()) {
                cardTransactions.visibility = View.GONE
                layoutEmptyState.visibility = View.VISIBLE
            } else {
                cardTransactions.visibility = View.VISIBLE
                layoutEmptyState.visibility = View.GONE
                adapter.updateData(history)
            }
        }
    }

    companion object {
        fun newInstance() = GemHistoryFragment()
    }
}
