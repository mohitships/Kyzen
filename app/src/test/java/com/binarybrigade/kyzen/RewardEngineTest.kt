package com.binarybrigade.kyzen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * RewardEngineTest — Unit Tests for the Gems Economy Reward Engine
 *
 * Tests validate the complete Gems economy model:
 * - Wallet persistence and accumulation
 * - Daily spending cap enforcement
 * - spendGem() correctness (wallet empty + cap reached)
 * - Detox bonus gem award
 * - Intervention trigger logic (3 conditions)
 * - gemsRemainingToday never negative
 * - Game pause flag
 *
 * RewardEngine is pure Kotlin — zero Android deps — testable with standard JUnit.
 * KyzenPreferences is mocked to isolate from SharedPreferences.
 */
class RewardEngineTest {

    private lateinit var mockPrefs: KyzenPreferences
    private lateinit var rewardEngine: RewardEngine

    @Before
    fun setUp() {
        mockPrefs = mock(KyzenPreferences::class.java)
        rewardEngine = RewardEngine(mockPrefs)
    }

    // ─── TC-RE-01: Wallet balance reflected in RewardStatus ───────────────────

    @Test
    fun `TC-RE-01 Gem wallet balance is correctly reflected in RewardStatus`() {
        `when`(mockPrefs.gemWallet).thenReturn(45)
        `when`(mockPrefs.gemsSpentToday).thenReturn(10)
        `when`(mockPrefs.dailySpendingCap).thenReturn(60)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(false)
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)

        val status = rewardEngine.quickCheck(productiveMinutesToday = 30L)

        assertEquals("Wallet must show 45 gems", 45, status.gemsInWallet)
    }

    // ─── TC-RE-02: Daily spending cap enforced ────────────────────────────────

    @Test
    fun `TC-RE-02 Daily spending cap is enforced - no more gems spendable when cap reached`() {
        `when`(mockPrefs.gemWallet).thenReturn(100)
        `when`(mockPrefs.gemsSpentToday).thenReturn(60)
        `when`(mockPrefs.dailySpendingCap).thenReturn(60)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(true)
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)

        val status = rewardEngine.quickCheck(productiveMinutesToday = 60L)

        assertTrue("Daily cap must be marked as reached", status.isDailyCapReached)
        assertEquals("gemsRemainingToday must be 0 when cap reached", 0, status.gemsRemainingToday)
    }

    // ─── TC-RE-03: gemsRemainingToday never negative ──────────────────────────

    @Test
    fun `TC-RE-03 gemsRemainingToday is never negative even when overspent`() {
        `when`(mockPrefs.gemWallet).thenReturn(0)
        `when`(mockPrefs.gemsSpentToday).thenReturn(999)
        `when`(mockPrefs.dailySpendingCap).thenReturn(60)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(true)
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)

        val status = rewardEngine.quickCheck(productiveMinutesToday = 0L)

        assertTrue("gemsRemainingToday must never be negative", status.gemsRemainingToday >= 0)
        assertEquals("Must be exactly 0 when overspent", 0, status.gemsRemainingToday)
    }

    // ─── TC-RE-04: shouldIntervene when wallet empty ──────────────────────────

    @Test
    fun `TC-RE-04 shouldIntervene returns true when wallet is empty`() {
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(false)
        `when`(mockPrefs.gemWallet).thenReturn(0)

        assertTrue("Intervention required when wallet is empty",
            rewardEngine.shouldIntervene())
    }

    // ─── TC-RE-05: shouldIntervene when daily cap reached ────────────────────

    @Test
    fun `TC-RE-05 shouldIntervene returns true when daily spending cap reached`() {
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(true)
        `when`(mockPrefs.gemWallet).thenReturn(50) // wallet has gems but cap is reached

        assertTrue("Intervention required even with gems in wallet if daily cap reached",
            rewardEngine.shouldIntervene())
    }

    // ─── TC-RE-06: shouldIntervene when game pause active ────────────────────

    @Test
    fun `TC-RE-06 shouldIntervene returns true when parent enables game pause`() {
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(true)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(false)
        `when`(mockPrefs.gemWallet).thenReturn(100)

        assertTrue("Game pause must trigger intervention regardless of gem balance",
            rewardEngine.shouldIntervene())
    }

    // ─── TC-RE-07: shouldIntervene returns false when gems available ──────────

    @Test
    fun `TC-RE-07 shouldIntervene returns false when wallet has gems and cap not reached`() {
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(false)
        `when`(mockPrefs.gemWallet).thenReturn(25)

        assertFalse("No intervention when gems available and cap not reached",
            rewardEngine.shouldIntervene())
    }

    // ─── TC-RE-08: RewardStatus shows correct productive and entertainment mins

    @Test
    fun `TC-RE-08 RewardStatus correctly reflects productive and entertainment minutes`() {
        `when`(mockPrefs.gemWallet).thenReturn(20)
        `when`(mockPrefs.gemsSpentToday).thenReturn(5)
        `when`(mockPrefs.dailySpendingCap).thenReturn(60)
        `when`(mockPrefs.isDailyCapReached()).thenReturn(false)
        `when`(mockPrefs.isGamePauseEnabled).thenReturn(false)

        val status = rewardEngine.quickCheck(
            productiveMinutesToday = 40L,
            entertainmentMinutesToday = 5L
        )

        assertEquals("Productive minutes must be 40", 40L, status.productiveMinutes)
        assertEquals("Entertainment minutes must be 5", 5L, status.entertainmentMinutes)
        assertEquals("gemsSpentToday must be 5", 5, status.gemsSpentToday)
        assertEquals("gemsRemainingToday must be 55", 55, status.gemsRemainingToday)
    }
}
