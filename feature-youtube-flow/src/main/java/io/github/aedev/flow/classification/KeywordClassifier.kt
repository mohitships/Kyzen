package io.github.aedev.flow.classification

/**
 * KeywordClassifier — Graceful Degradation Fallback (Phase 4 §4.4)
 *
 * The 125+ keyword regex scoring engine from the YouTube content classification
 * case study (docs/youtube-content-classification-case-study.md §4.2, §11.2).
 * Repurposed as the primary classification layer when the DeBERTa ONNX model
 * fails the 500ms kill-switch or causes OOM on low-RAM devices.
 *
 * Scoring algorithm (from the case study):
 *   - Channel name match: not applicable here (VIP fast-path handles channels)
 *   - Title keywords: weighted scoring against productive vs entertainment vocabularies
 *   - Anti-pattern penalty: clickbait indicators reduce the productive score
 *   - Threshold: productive score > entertainment score AND productive score >= 3 → PRODUCTIVE
 *
 * This is a pure Kotlin object — no Android dependencies, fully unit-testable.
 */
object KeywordClassifier {

    // ── Productive keywords (educational/academic) ──────────────────────────
    // Weights from the case study's 125+ keyword list.
    private val productiveKeywords = mapOf(
        // Indian exam names
        "jee" to 5, "neet" to 5, "upsc" to 5, "ssc" to 4, "gate" to 4,
        "cat" to 3, "nda" to 4, "clat" to 4, "cgl" to 3,
        // Class/grade terms
        "class 6" to 4, "class 7" to 4, "class 8" to 4, "class 9" to 4,
        "class 10" to 5, "class 11" to 5, "class 12" to 5,
        "10th" to 4, "12th" to 4, "plus one" to 3, "plus two" to 3,
        "sslc" to 3, "hsc" to 3, "cbse" to 5, "icse" to 4,
        // Subjects
        "math" to 4, "maths" to 4, "physics" to 5, "chemistry" to 5,
        "biology" to 5, "science" to 3, "history" to 3, "geography" to 3,
        "economics" to 4, "political science" to 4, "accountancy" to 4,
        "business studies" to 4, "english grammar" to 3,
        "algebra" to 4, "trigonometry" to 4, "calculus" to 5, "geometry" to 4,
        "thermodynamics" to 5, "electromagnetism" to 5, "mechanics" to 4,
        "organic chemistry" to 5, "inorganic chemistry" to 5,
        "botany" to 4, "zoology" to 4, "genetics" to 4, "ecology" to 3,
        "statistics" to 4, "probability" to 3, "linear algebra" to 4,
        "differential" to 4, "integral" to 4, "equation" to 3,
        // Hindi educational terms
        "padhai" to 4, "pariksha" to 4, "gyaan" to 3, "vigyan" to 4,
        "ganit" to 4, "bhugol" to 3, "itihas" to 3, "rasayan" to 4,
        "bhautik" to 4, "jeev" to 3,
        // Study content
        "notes" to 3, "revision" to 4, "mock test" to 5, "sample paper" to 4,
        "formula sheet" to 4, "previous year" to 4, "pyq" to 4,
        "ncert" to 5, "solution" to 3, "derivation" to 4,
        "important questions" to 4, "important topics" to 4,
        "chapter" to 3, "concept" to 3, "explain" to 3, "explained" to 3,
        "problem solving" to 4, "numerical" to 4, "practice" to 3,
        // Coding/tech
        "dsa" to 5, "data structures" to 5, "leetcode" to 4, "system design" to 4,
        "placement" to 3, "coding" to 4, "programming" to 4, "algorithm" to 4,
        "python" to 3, "java" to 3, "c++" to 3, "javascript" to 3,
        "web development" to 4, "app development" to 4, "machine learning" to 4,
        "artificial intelligence" to 4, "data science" to 4,
        // Learning platforms
        "khan academy" to 5, "physics wallah" to 5, "unacademy" to 5,
        "byju" to 5, "vedantu" to 5, "coursera" to 4, "udemy" to 4,
        "vedantu" to 5, "toppr" to 4, "magnet brains" to 5,
        // General academic
        "lecture" to 4, "tutorial" to 3, "lesson" to 3, "course" to 3,
        "exam" to 3, "study" to 3, "learn" to 3, "education" to 4,
        "academic" to 4, "school" to 3, "college" to 2, "university" to 3,
        "teacher" to 3, "professor" to 3, "instructor" to 3, "teach" to 3,
        "classroom" to 3, "syllabus" to 4, "curriculum" to 3,
        "doubt" to 3, "doubts" to 3, "question" to 2, "answer" to 2,
        "introduction to" to 3, "basics of" to 3, "fundamentals" to 4,
        "how to solve" to 3, "step by step" to 3, "full chapter" to 4,
        "one shot" to 3, "crash course" to 4, "marathon" to 2
    )

    // ── Entertainment keywords ──────────────────────────────────────────────
    private val entertainmentKeywords = mapOf(
        // Gaming
        "gameplay" to 5, "gaming" to 5, "minecraft" to 5, "free fire" to 5,
        "bgmi" to 5, "pubg" to 5, "fortnite" to 5, "roblox" to 5,
        "gta" to 4, "valorant" to 4, "call of duty" to 4, "among us" to 4,
        "speedrun" to 4, "walkthrough" to 3, "let's play" to 4,
        // Entertainment/vlogs
        "vlog" to 5, "prank" to 5, "funny" to 4, "comedy" to 4,
        "reaction" to 4, "challenge" to 3, "meme" to 4, "compilation" to 3,
        "unboxing" to 3, "haul" to 3, "lifestyle" to 3,
        // Movies/shows
        "movie" to 4, "trailer" to 4, "web series" to 4, "anime" to 5,
        "netflix" to 4, "review" to 2, "breakdown" to 2,
        // Music
        "music video" to 4, "song" to 3, "lyrics" to 3, "remix" to 4,
        // Social media
        "shorts" to 3, "viral" to 3, "trending" to 2
    )

    // ── Anti-patterns (clickbait indicators that reduce productive score) ──
    private val antiPatterns = listOf(
        "you won't believe", "shocking", "insane", "crazy", "must watch",
        "epic", "mind blowing", "unreal", "impossible"
    )

    /**
     * Classifies a video title using weighted keyword scoring.
     *
     * @param title    The video title
     * @param channel  The channel name (for context, not VIP matching)
     * @return "PRODUCTIVE" or "ENTERTAINMENT" with a confidence 0.0–1.0
     */
    fun classify(title: String, channel: String): ClassificationResult {
        val corpus = "${title.lowercase()} ${channel.lowercase()}"

        var productiveScore = 0
        var entertainmentScore = 0

        for ((keyword, weight) in productiveKeywords) {
            if (corpus.contains(keyword)) productiveScore += weight
        }
        for ((keyword, weight) in entertainmentKeywords) {
            if (corpus.contains(keyword)) entertainmentScore += weight
        }

        // Anti-pattern penalty — reduces productive score for clickbait titles
        var penalty = 0
        for (pattern in antiPatterns) {
            if (corpus.contains(pattern)) penalty += 2
        }
        productiveScore = (productiveScore - penalty).coerceAtLeast(0)

        val total = productiveScore + entertainmentScore
        if (total == 0) {
            // No keywords matched — default to entertainment (safe default per Phase 1 §1.3)
            return ClassificationResult("ENTERTAINMENT", 0.3f)
        }

        // Lowered threshold from 3 to 2 to catch small educational creators
        // whose titles may only contain one subject keyword (e.g., "physics" = 5)
        val category = if (productiveScore > entertainmentScore && productiveScore >= 2) {
            "PRODUCTIVE"
        } else {
            "ENTERTAINMENT"
        }

        val confidence = (maxOf(productiveScore, entertainmentScore).toFloat() / total).coerceIn(0.3f, 0.95f)
        return ClassificationResult(category, confidence)
    }

    data class ClassificationResult(
        val category: String,
        val confidence: Float
    )
}
