package com.binarybrigade.kyzen

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * EducationalChannelMatcher — On-Device Educational YouTube Channel Recognition Engine
 *
 * Loads the curated database of 736 Indian educational YouTube channels from
 * the bundled assets file and provides fast content classification.
 *
 * MATCHING ALGORITHM:
 *   1. Channel names are tokenized into significant words (≥3 chars, not stop words)
 *   2. Visible text from YouTube's UI is similarly tokenized
 *   3. A match occurs when enough tokens from a channel name appear in the visible text
 *      - For channel names with 1 significant token: require exact match (e.g., "Unacademy")
 *      - For channel names with 2+ significant tokens: require ≥2 token overlap
 *        (e.g., "Physics Wallah" matches if both "physics" and "wallah" appear)
 *
 * FALSE POSITIVE PREVENTION:
 *   - Stop words ("the", "and", "for", etc.) are excluded from matching
 *   - Single generic tokens like "study" or "academy" alone are NOT sufficient
 *   - Multi-token requirement ensures distinctive channel names match reliably
 *
 * PERFORMANCE:
 *   - JSON is loaded once on first access and cached in memory
 *   - Token sets are pre-computed during initialization
 *   - Lookup is O(n × m) where n = channels, m = tokens per channel
 *     With 736 channels averaging 2 tokens each, this is ~1500 comparisons — microseconds
 *
 * Privacy: Zero network calls. Zero data egress. All processing on-device.
 */
object EducationalChannelMatcher {

    private const val TAG = "EduChannelMatcher"
    private const val ASSET_FILE = "educational_channels.json"

    /** Minimum token length to be considered significant for matching. */
    private const val MIN_TOKEN_LENGTH = 3

    /** Minimum number of token overlaps required for a match (for multi-token channel names). */
    private const val MIN_OVERLAP_FOR_MATCH = 2

    /** Common stop words excluded from matching to prevent false positives. */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "from", "this", "that", "are", "was",
        "were", "has", "had", "been", "have", "will", "can", "not", "but",
        "all", "its", "our", "you", "your", "how", "what", "when", "where",
        "who", "why", "into", "over", "also", "just", "than", "them", "then",
        "now", "get", "got", "one", "two", "new", "way", "may", "say", "make",
        "made", "like", "only", "come", "use", "her", "his", "she", "they",
        "their", "there", "here", "each", "own", "about", "after", "before",
        "between", "through", "during", "without", "against", "other", "very",
        "most", "more", "some", "such", "any", "few", "does", "did", "being"
    )

    /**
     * Pre-computed channel signature: a set of normalized significant tokens
     * extracted from the channel name.
     */
    data class ChannelSignature(
        val originalName: String,
        val tokens: Set<String>,
        val isSingleToken: Boolean
    )

    // Loaded state
    @Volatile
    private var initialized = false

    private val lock = Any()

    /** Pre-computed channel signatures for fast matching. */
    private var channelSignatures: List<ChannelSignature> = emptyList()

    /**
     * Initializes the matcher by loading channel data from assets.
     * Thread-safe: double-checked locking pattern.
     * Must be called at least once before [isEducationalContent].
     *
     * @param context Application or activity context (for asset access)
     */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return

            try {
                val json = context.assets.open(ASSET_FILE)
                    .bufferedReader()
                    .use { it.readText() }

                val root = JSONObject(json)
                val channelsArray = root.optJSONArray("channels") ?: return

                val signatures = mutableListOf<ChannelSignature>()

                for (i in 0 until channelsArray.length()) {
                    val channelObj = channelsArray.optJSONObject(i) ?: continue
                    val name = channelObj.optString("name", "").trim()
                    if (name.isEmpty()) continue

                    val tokens = tokenize(name)
                    if (tokens.isEmpty()) continue

                    signatures.add(ChannelSignature(
                        originalName = name,
                        tokens = tokens,
                        isSingleToken = tokens.size == 1
                    ))
                }

                channelSignatures = signatures
                initialized = true

                Log.d(TAG, "Loaded ${signatures.size} educational channel signatures")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load educational channels", e)
                // Keep initialized = false so next call will retry
            }
        }
    }

    /**
     * Checks whether the visible text from YouTube's UI indicates
     * the user is watching educational content from a known channel.
     *
     * @param visibleText All text collected from YouTube's accessibility node tree
     * @return true if an educational channel is detected, false otherwise
     */
    fun isEducationalContent(visibleText: String): Boolean {
        if (!initialized) {
            Log.w(TAG, "Matcher not initialized — call initialize(context) first")
            return false
        }
        if (visibleText.isBlank()) return false

        val textTokens = tokenize(visibleText)
        if (textTokens.isEmpty()) return false

        // Check each channel signature against the visible text tokens
        for (signature in channelSignatures) {
            if (signature.isSingleToken) {
                // Single-token channel name (e.g., "Unacademy", "Toppr")
                // Require exact token match — the single token must appear in visible text
                if (signature.tokens.first() in textTokens) {
                    Log.d(TAG, "Educational channel detected: ${signature.originalName}")
                    return true
                }
            } else {
                // Multi-token channel name (e.g., "Physics Wallah", "Khan Academy")
                // Require at least MIN_OVERLAP_FOR_MATCH tokens to appear
                val overlap = signature.tokens.intersect(textTokens)
                if (overlap.size >= MIN_OVERLAP_FOR_MATCH) {
                    Log.d(TAG, "Educational channel detected: ${signature.originalName} " +
                            "(matched tokens: $overlap)")
                    return true
                }
            }
        }

        return false
    }

    /**
     * Tokenizes a string into normalized significant tokens.
     *
     * Normalization steps:
     *   1. Lowercase
     *   2. Replace non-alphanumeric chars with spaces
     *   3. Split on whitespace
     *   4. Filter: length ≥ MIN_TOKEN_LENGTH and not a stop word
     *
     * @param text Input text to tokenize
     * @return Set of normalized significant tokens
     */
    internal fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }
            .toSet()
    }

    /**
     * Returns the number of loaded channel signatures.
     * Useful for diagnostics and testing.
     */
    fun getLoadedChannelCount(): Int = channelSignatures.size

    /**
     * Returns true if the matcher has been initialized.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Initializes the matcher with a pre-defined list of channel names.
     * For testing only — bypasses JSON file loading so unit tests
     * can run without an Android Context.
     *
     * @param channelNames List of educational channel names to match against
     */
    internal fun initializeForTest(channelNames: List<String>) {
        synchronized(lock) {
            val signatures = channelNames.mapNotNull { name ->
                val tokens = tokenize(name)
                if (tokens.isEmpty()) null
                else ChannelSignature(
                    originalName = name,
                    tokens = tokens,
                    isSingleToken = tokens.size == 1
                )
            }
            channelSignatures = signatures
            initialized = true
        }
    }

    /**
     * Resets the matcher state. For testing only.
     */
    internal fun reset() {
        synchronized(lock) {
            channelSignatures = emptyList()
            initialized = false
        }
    }
}