package com.binarybrigade.kyzen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppClassifierTest — Unit Tests for the Three-Layer AI Classification Pipeline
 *
 * Tests validate all three layers:
 * Layer 1: Expert Knowledge Base (exact package lookup)
 * Layer 2: Weighted keyword scoring
 * Layer 3: Neutral fallback
 *
 * Three categories: PRODUCTIVE (earns credits), ENTERTAINMENT (spends credits), NEUTRAL (no effect)
 */
class AppClassifierTest {

    // ─── LAYER 1: Knowledge Base Tests ───────────────────────────────────────

    @Test
    fun `TC-01 Gmail classified as PRODUCTIVE via Layer 1`() {
        val result = AppClassifier.classify("com.google.android.gm", "Gmail")
        assertEquals(AppClassifier.AppCategory.PRODUCTIVE, result.category)
        assertEquals("Layer 1 must return 99% confidence", 99, result.confidence)
    }

    @Test
    fun `TC-02 YouTube classified as ENTERTAINMENT via Layer 1`() {
        val result = AppClassifier.classify("com.google.android.youtube", "YouTube")
        assertEquals(AppClassifier.AppCategory.ENTERTAINMENT, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-03 Instagram classified as ENTERTAINMENT via Layer 1`() {
        val result = AppClassifier.classify("com.instagram.android", "Instagram")
        assertEquals(AppClassifier.AppCategory.ENTERTAINMENT, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-04 PUBG Mobile classified as ENTERTAINMENT via Layer 1`() {
        val result = AppClassifier.classify("com.tencent.ig", "PUBG Mobile")
        assertEquals(AppClassifier.AppCategory.ENTERTAINMENT, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-05 Khan Academy classified as PRODUCTIVE via Layer 1`() {
        val result = AppClassifier.classify("org.khanacademy.android", "Khan Academy")
        assertEquals(AppClassifier.AppCategory.PRODUCTIVE, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-06 Brave browser classified as NEUTRAL via Layer 1`() {
        val result = AppClassifier.classify("com.brave.browser", "Brave")
        assertEquals(AppClassifier.AppCategory.NEUTRAL, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-07 WhatsApp classified as PRODUCTIVE via Layer 1`() {
        // WhatsApp is communicative use (Orben et al. 2022) — not passive entertainment
        val result = AppClassifier.classify("com.whatsapp", "WhatsApp")
        assertEquals(AppClassifier.AppCategory.PRODUCTIVE, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-08 Netflix classified as ENTERTAINMENT via Layer 1`() {
        val result = AppClassifier.classify("com.netflix.mediaclient", "Netflix")
        assertEquals(AppClassifier.AppCategory.ENTERTAINMENT, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-09 Duolingo classified as PRODUCTIVE via Layer 1`() {
        val result = AppClassifier.classify("com.duolingo", "Duolingo")
        assertEquals(AppClassifier.AppCategory.PRODUCTIVE, result.category)
        assertEquals(99, result.confidence)
    }

    @Test
    fun `TC-10 Candy Crush classified as ENTERTAINMENT via Layer 1`() {
        val result = AppClassifier.classify("com.king.candycrushsaga", "Candy Crush Saga")
        assertEquals(AppClassifier.AppCategory.ENTERTAINMENT, result.category)
        assertEquals(99, result.confidence)
    }

    // ─── LAYER 2: Keyword Scoring Tests ──────────────────────────────────────

    @Test
    fun `TC-11 Unknown study app classified as PRODUCTIVE by Layer 2 keywords`() {
        val result = AppClassifier.classify("com.unknown.mathsolver", "Math Solver")
        assertEquals(AppClassifier.AppCategory.PRODUCTIVE, result.category)
        assertTrue("Layer 2 confidence must be below 99", result.confidence < 99)
    }

    @Test
    fun `TC-12 Unknown game app classified as ENTERTAINMENT by Layer 2 keywords`() {
        val result = AppClassifier.classify("com.unknown.battlezone", "Battle Zone Shooter")
        assertEquals(AppClassifier.AppCategory.ENTERTAINMENT, result.category)
        assertTrue("Layer 2 confidence must be below 99", result.confidence < 99)
    }

    @Test
    fun `TC-13 Unknown utility classified as NEUTRAL by Layer 2 keywords`() {
        val result = AppClassifier.classify("com.unknown.flashlight", "Flashlight")
        assertEquals(AppClassifier.AppCategory.NEUTRAL, result.category)
        assertTrue("Layer 2 confidence must be below 99", result.confidence < 99)
    }

    // ─── LAYER 3: Fallback Tests ──────────────────────────────────────────────

    @Test
    fun `TC-14 Completely unknown app returns NEUTRAL with 0 percent confidence`() {
        val result = AppClassifier.classify("com.xyz.qwerty12345", "XyzQwerty")
        assertEquals(
            "Unknown app must fall back to NEUTRAL (safest default — no credit impact)",
            AppClassifier.AppCategory.NEUTRAL,
            result.category
        )
        assertEquals("Unknown app must have 0% confidence", 0, result.confidence)
    }

    // ─── General Validation Tests ─────────────────────────────────────────────

    @Test
    fun `TC-15 Confidence score is always between 0 and 99`() {
        val testApps = listOf(
            Pair("com.google.android.gm", "Gmail"),
            Pair("com.instagram.android", "Instagram"),
            Pair("com.tencent.ig", "PUBG Mobile"),
            Pair("com.brave.browser", "Brave"),
            Pair("com.unknown.randomapp", "SomeRandomApp")
        )
        for ((pkg, name) in testApps) {
            val result = AppClassifier.classify(pkg, name)
            assertTrue(
                "Confidence for $name must be 0–99, got ${result.confidence}",
                result.confidence in 0..99
            )
        }
    }

    @Test
    fun `TC-16 Classifier is case-insensitive`() {
        val lower = AppClassifier.classify("com.google.android.gm", "gmail")
        val upper = AppClassifier.classify("COM.GOOGLE.ANDROID.GM", "GMAIL")
        assertEquals("Classification must be identical regardless of input casing",
            lower.category, upper.category)
    }
}
