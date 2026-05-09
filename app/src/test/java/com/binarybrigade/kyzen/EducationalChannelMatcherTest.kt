package com.binarybrigade.kyzen

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EducationalChannelMatcherTest — Unit Tests for YouTube Channel Matching
 *
 * Tests the tokenization and channel matching logic used by the
 * AccessibilityService to determine whether the currently viewed
 * YouTube content is educational.
 *
 * Uses initializeForTest() to bypass JSON file loading (no Android Context needed).
 */
class EducationalChannelMatcherTest {

    private val testChannels = listOf(
        "Khan Academy",
        "3Blue1Brown",
        "MIT OpenCourseWare",
        "CrashCourse",
        "Kurzgesagt",
        "Veritasium",
        "CS Dojo",
        "freeCodeCamp",
        "Unacademy",
        "Physics Wallah"
    )

    @After
    fun tearDown() {
        EducationalChannelMatcher.reset()
    }

    // ─── Tokenization Tests ───────────────────────────────────────────────────

    @Test
    fun `ECM-01 tokenize splits words correctly`() {
        val tokens = EducationalChannelMatcher.tokenize("Khan Academy")
        assertTrue("khan should be a token", "khan" in tokens)
        assertTrue("academy should be a token", "academy" in tokens)
    }

    @Test
    fun `ECM-02 tokenize is case-insensitive`() {
        val tokens = EducationalChannelMatcher.tokenize("KHAN ACADEMY")
        assertTrue("khan should be present", "khan" in tokens)
        assertTrue("academy should be present", "academy" in tokens)
    }

    @Test
    fun `ECM-03 tokenize filters short words`() {
        val tokens = EducationalChannelMatcher.tokenize("I am a Student")
        assertFalse("Short words (I, am, a) should be filtered", "am" in tokens)
        assertTrue("student should be present", "student" in tokens)
    }

    @Test
    fun `ECM-04 tokenize filters stop words`() {
        val tokens = EducationalChannelMatcher.tokenize("The Science of Physics")
        assertFalse("the should be filtered", "the" in tokens)
        assertTrue("science should be present", "science" in tokens)
        assertTrue("physics should be present", "physics" in tokens)
    }

    @Test
    fun `ECM-05 tokenize handles special characters`() {
        val tokens = EducationalChannelMatcher.tokenize("3Blue1Brown")
        assertTrue("3blue1brown should be a token", "3blue1brown" in tokens)
    }

    // ─── Channel Matching Tests ────────────────────────────────────────────────

    @Test
    fun `ECM-06 multi-token channel matches when both tokens present`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        // "Khan Academy" → tokens: {khan, academy}
        // Both tokens appear in visible text → match
        assertTrue("Khan Academy should match when both tokens present",
            EducationalChannelMatcher.isEducationalContent("Watching Khan Academy video"))
    }

    @Test
    fun `ECM-07 multi-token channel does not match with only one token`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        // "Khan Academy" needs BOTH "khan" AND "academy" in visible text
        assertFalse("Only 'khan' without 'academy' should not match Khan Academy",
            EducationalChannelMatcher.isEducationalContent("Khan is a great name"))
    }

    @Test
    fun `ECM-08 single-token channel matches when token appears`() {
        EducationalChannelMatcher.initializeForTest(listOf("Unacademy"))
        // "Unacademy" → single token: {unacademy}
        assertTrue("Unacademy should match when token appears",
            EducationalChannelMatcher.isEducationalContent("Welcome to Unacademy"))
    }

    @Test
    fun `ECM-09 non-educational content does not match`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertFalse("Random gaming content should not match",
            EducationalChannelMatcher.isEducationalContent("Epic Gaming Montage 2024"))
    }

    @Test
    fun `ECM-10 empty visible text does not match`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertFalse("Empty text should not match",
            EducationalChannelMatcher.isEducationalContent(""))
    }

    @Test
    fun `ECM-11 blank visible text does not match`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertFalse("Blank text should not match",
            EducationalChannelMatcher.isEducationalContent("   "))
    }

    @Test
    fun `ECM-12 uninitialized matcher returns false`() {
        // Don't call initializeForTest — matcher is in reset state
        assertFalse("Uninitialized matcher should return false",
            EducationalChannelMatcher.isEducationalContent("Khan Academy"))
    }

    // ─── Initialization Tests ─────────────────────────────────────────────────

    @Test
    fun `ECM-13 initializeForTest loads channels correctly`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertTrue("Matcher should be initialized",
            EducationalChannelMatcher.isInitialized())
        assertTrue("Should have loaded channels",
            EducationalChannelMatcher.getLoadedChannelCount() > 0)
    }

    @Test
    fun `ECM-14 reset clears matcher state`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertTrue(EducationalChannelMatcher.isInitialized())

        EducationalChannelMatcher.reset()
        assertFalse("Matcher should not be initialized after reset",
            EducationalChannelMatcher.isInitialized())
        assertEquals("Channel count should be 0 after reset",
            0, EducationalChannelMatcher.getLoadedChannelCount())
    }

    @Test
    fun `ECM-15 empty channel list initializes but never matches`() {
        EducationalChannelMatcher.initializeForTest(emptyList())
        assertTrue("Matcher should be initialized", EducationalChannelMatcher.isInitialized())
        assertFalse("No channels → no match",
            EducationalChannelMatcher.isEducationalContent("Khan Academy"))
    }

    // ─── Real-World Scenario Tests ─────────────────────────────────────────────

    @Test
    fun `ECM-16 Physics Wallah matches with both tokens`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertTrue("Physics Wallah should match",
            EducationalChannelMatcher.isEducationalContent("Physics Wallah Lecture 12"))
    }

    @Test
    fun `ECM-17 channel match is case-insensitive in visible text`() {
        EducationalChannelMatcher.initializeForTest(testChannels)
        assertTrue("Match should work regardless of case in visible text",
            EducationalChannelMatcher.isEducationalContent("WATCHING KHAN ACADEMY VIDEO"))
    }

    @Test
    fun `ECM-18 channel name with numbers matches`() {
        EducationalChannelMatcher.initializeForTest(listOf("3Blue1Brown"))
        // "3Blue1Brown" → token: {3blue1brown}
        assertTrue("3Blue1Brown should match",
            EducationalChannelMatcher.isEducationalContent("3Blue1Brown linear algebra"))
    }
}