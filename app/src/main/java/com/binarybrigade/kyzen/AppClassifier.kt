package com.binarybrigade.kyzen

/**
 * AppClassifier — Three-Layer On-Device Intelligent Classification Pipeline
 *
 * Academic Classification: Hybrid Rule-Based Expert System + Weighted Statistical
 * Text Classifier. This module implements a three-layer classification pipeline
 * that autonomously categorises installed applications into three behavioural
 * categories aligned with Self-Determination Theory (SDT) digital wellbeing principles.
 *
 * THREE CATEGORIES:
 *   PRODUCTIVE    — Actual work, study, learning, communication. EARNS credits.
 *   ENTERTAINMENT — Passive consumption, games, social media, streaming. SPENDS credits.
 *   NEUTRAL       — Neither work nor fun (Settings, Calculator, Camera etc). NO credit effect.
 *
 * LAYER 1 — Curated Expert Knowledge Base (Exact Package Signature Lookup)
 *   A hardcoded lookup table of ~100 globally dominant applications identified
 *   by their immutable package identifiers. Provides deterministic, 100% accurate
 *   classification for the apps that constitute the vast majority of screen time.
 *   Applied first; if matched, result is final (99% confidence).
 *
 * LAYER 2 — Weighted Keyword Scoring Classifier (Statistical Inference)
 *   A multi-class weighted keyword voting algorithm that analyses the composite
 *   corpus of package name + display name. Each keyword match contributes a
 *   weighted score to its target category. Highest aggregate score wins.
 *   Applied only when Layer 1 produces no match (max 94% confidence).
 *
 * LAYER 3 — Heuristic Fallback
 *   If neither Layer 1 nor Layer 2 yields a result, app is classified as
 *   NEUTRAL (0% confidence) — the safest default (no credit impact).
 *
 * Theoretical basis: Orben et al. (2022) — distinguishes "active/productive use"
 * from "passive/entertainment use", directly validating the Productive vs.
 * Entertainment split.
 *
 * Privacy: Operates entirely on-device. Zero network calls. Zero data egress.
 * Complexity: O(1) for Layer 1 (HashMap lookup), O(n) for Layer 2.
 */
object AppClassifier {

    // ─── Category Enum ────────────────────────────────────────────────────────

    enum class AppCategory {
        PRODUCTIVE,     // Earns entertainment credits
        ENTERTAINMENT,  // Spends entertainment credits
        NEUTRAL         // No effect on credit balance
    }

    // ─── Classification Result ────────────────────────────────────────────────

    data class ClassificationResult(
        val category: AppCategory,
        val confidence: Int  // 0–99 percentage
    )

    // ─── LAYER 1: Curated Expert Knowledge Base ───────────────────────────────
    // Exact package name → definitive category. O(1) lookup. Applied first.
    // Covers ~100 apps that constitute the vast majority of global screen time.

    private val knowledgeBase: Map<String, AppCategory> = mapOf(

        // ── PRODUCTIVE: Email & Communication ────────────────────────────────
        "com.google.android.gm"                     to AppCategory.PRODUCTIVE, // Gmail
        "com.microsoft.office.outlook"              to AppCategory.PRODUCTIVE, // Outlook
        "com.yahoo.mobile.client.android.mail"      to AppCategory.PRODUCTIVE, // Yahoo Mail
        "com.zoho.mail"                             to AppCategory.PRODUCTIVE, // Zoho Mail
        "com.protonmail.protonmail"                 to AppCategory.PRODUCTIVE, // ProtonMail
        "ch.protonmail.android"                     to AppCategory.PRODUCTIVE, // ProtonMail alt

        // ── PRODUCTIVE: Office & Documents ───────────────────────────────────
        "com.google.android.apps.docs"              to AppCategory.PRODUCTIVE, // Google Docs
        "com.google.android.apps.spreadsheets"      to AppCategory.PRODUCTIVE, // Google Sheets
        "com.google.android.apps.presentations"     to AppCategory.PRODUCTIVE, // Google Slides
        "com.google.android.apps.docs.editors.docs" to AppCategory.PRODUCTIVE, // Google Docs alt
        "com.microsoft.office.word"                 to AppCategory.PRODUCTIVE, // MS Word
        "com.microsoft.office.excel"                to AppCategory.PRODUCTIVE, // MS Excel
        "com.microsoft.office.powerpoint"           to AppCategory.PRODUCTIVE, // MS PowerPoint
        "com.microsoft.teams"                       to AppCategory.PRODUCTIVE, // MS Teams
        "com.adobe.reader"                          to AppCategory.PRODUCTIVE, // Adobe Reader
        "com.google.android.apps.drive"             to AppCategory.PRODUCTIVE, // Google Drive
        "com.dropbox.android"                       to AppCategory.PRODUCTIVE, // Dropbox

        // ── PRODUCTIVE: Education & Learning ─────────────────────────────────
        "org.khanacademy.android"                   to AppCategory.PRODUCTIVE, // Khan Academy
        "com.duolingo"                              to AppCategory.PRODUCTIVE, // Duolingo
        "com.byjus.thelearningapp"                  to AppCategory.PRODUCTIVE, // BYJU'S
        "in.unacademy.learner"                      to AppCategory.PRODUCTIVE, // Unacademy
        "com.coursera.android"                      to AppCategory.PRODUCTIVE, // Coursera
        "com.udemy.android"                         to AppCategory.PRODUCTIVE, // Udemy
        "com.sololearn"                             to AppCategory.PRODUCTIVE, // SoloLearn
        "com.photomath"                             to AppCategory.PRODUCTIVE, // Photomath
        "com.google.android.apps.classroom"         to AppCategory.PRODUCTIVE, // Google Classroom
        "com.ankiapp.client"                        to AppCategory.PRODUCTIVE, // Anki flashcards
        "com.wolfram.android.alpha"                 to AppCategory.PRODUCTIVE, // Wolfram Alpha
        "com.chegg"                                 to AppCategory.PRODUCTIVE, // Chegg
        "com.brainly.base"                          to AppCategory.PRODUCTIVE, // Brainly
        "com.grammarly.keyboard"                    to AppCategory.PRODUCTIVE, // Grammarly
        "com.vedantu.student"                       to AppCategory.PRODUCTIVE, // Vedantu
        "com.toppr"                                 to AppCategory.PRODUCTIVE, // Toppr

        // ── PRODUCTIVE: Productivity & Focus Tools ────────────────────────────
        "com.todoist.android"                       to AppCategory.PRODUCTIVE, // Todoist
        "com.evernote"                              to AppCategory.PRODUCTIVE, // Evernote
        "com.notion.id"                             to AppCategory.PRODUCTIVE, // Notion
        "com.google.android.keep"                   to AppCategory.PRODUCTIVE, // Google Keep
        "com.any.do"                                to AppCategory.PRODUCTIVE, // Any.do
        "com.github.android"                        to AppCategory.PRODUCTIVE, // GitHub
        "com.termux"                                to AppCategory.PRODUCTIVE, // Termux (coding)
        "com.google.android.apps.tasks"             to AppCategory.PRODUCTIVE, // Google Tasks

        // ── PRODUCTIVE: Messaging (communication, not passive scrolling) ──────
        // Aligned with Orben et al. (2022): communicative use ≠ passive use
        "com.whatsapp"                              to AppCategory.PRODUCTIVE, // WhatsApp
        "com.whatsapp.w4b"                          to AppCategory.PRODUCTIVE, // WhatsApp Business
        "org.telegram.messenger"                    to AppCategory.PRODUCTIVE, // Telegram
        "com.skype.raider"                          to AppCategory.PRODUCTIVE, // Skype
        "com.viber.voip"                            to AppCategory.PRODUCTIVE, // Viber
        "com.google.android.apps.messaging"         to AppCategory.PRODUCTIVE, // Google Messages

        // ── PRODUCTIVE: Professional Networking & Career Development ─────────
        // Career-focused platforms for job searching, professional networking,
        // skill development, and business relationship building.
        "com.linkedin.android"                      to AppCategory.PRODUCTIVE, // LinkedIn

        // ── ENTERTAINMENT: Social Media (passive consumption) ─────────────────
        // Platforms primarily used for passive scrolling, entertainment content,
        // and leisure social interaction (not professional networking).
        "com.instagram.android"                     to AppCategory.ENTERTAINMENT, // Instagram
        "com.facebook.katana"                       to AppCategory.ENTERTAINMENT, // Facebook
        "com.facebook.messenger"                    to AppCategory.ENTERTAINMENT, // Messenger
        "com.twitter.android"                       to AppCategory.ENTERTAINMENT, // Twitter/X
        "com.zhiliaoapp.musically"                  to AppCategory.ENTERTAINMENT, // TikTok
        "com.reddit.frontpage"                      to AppCategory.ENTERTAINMENT, // Reddit
        "com.pinterest"                             to AppCategory.ENTERTAINMENT, // Pinterest
        "com.tumblr"                                to AppCategory.ENTERTAINMENT, // Tumblr
        "com.sharechat.app"                         to AppCategory.ENTERTAINMENT, // ShareChat
        "com.mxtakatak"                             to AppCategory.ENTERTAINMENT, // MX TakaTak
        "com.discord"                               to AppCategory.ENTERTAINMENT, // Discord
        "com.snapchat.android"                      to AppCategory.ENTERTAINMENT, // Snapchat
        "com.josh.short.video.status.app"           to AppCategory.ENTERTAINMENT, // Josh
        "in.mohalla.video"                          to AppCategory.ENTERTAINMENT, // Moj

        // ── ENTERTAINMENT: Video & Streaming ─────────────────────────────────
        "com.google.android.youtube"                to AppCategory.ENTERTAINMENT, // YouTube
        "com.google.android.apps.youtube.music"     to AppCategory.ENTERTAINMENT, // YouTube Music
        "com.netflix.mediaclient"                   to AppCategory.ENTERTAINMENT, // Netflix
        "com.amazon.avod.thirdpartyclient"          to AppCategory.ENTERTAINMENT, // Prime Video
        "com.hotstar.android"                       to AppCategory.ENTERTAINMENT, // Disney+ Hotstar
        "com.jio.media.jiocinema"                   to AppCategory.ENTERTAINMENT, // JioCinema
        "com.zee5.android"                          to AppCategory.ENTERTAINMENT, // ZEE5
        "in.sonyliv"                                to AppCategory.ENTERTAINMENT, // SonyLIV
        "com.voot.android"                          to AppCategory.ENTERTAINMENT, // Voot
        "com.mxtech.videoplayer.ad"                 to AppCategory.ENTERTAINMENT, // MX Player

        // ── ENTERTAINMENT: Music (passive listening) ──────────────────────────
        "com.spotify.music"                         to AppCategory.ENTERTAINMENT, // Spotify
        "com.jiosongsmusic"                         to AppCategory.ENTERTAINMENT, // JioSaavn
        "com.gaana"                                 to AppCategory.ENTERTAINMENT, // Gaana
        "com.wynk.music"                            to AppCategory.ENTERTAINMENT, // Wynk Music

        // ── ENTERTAINMENT: Games ──────────────────────────────────────────────
        "com.tencent.ig"                            to AppCategory.ENTERTAINMENT, // PUBG Mobile
        "com.tencent.bgmi"                          to AppCategory.ENTERTAINMENT, // BGMI
        "com.garena.game.freefire"                  to AppCategory.ENTERTAINMENT, // Free Fire
        "com.epicgames.fortnite"                    to AppCategory.ENTERTAINMENT, // Fortnite
        "com.mojang.minecraftpe"                    to AppCategory.ENTERTAINMENT, // Minecraft
        "com.roblox.client"                         to AppCategory.ENTERTAINMENT, // Roblox
        "com.king.candycrushsaga"                   to AppCategory.ENTERTAINMENT, // Candy Crush
        "com.imangi.templerun"                      to AppCategory.ENTERTAINMENT, // Temple Run
        "com.imangi.templerun2"                     to AppCategory.ENTERTAINMENT, // Temple Run 2
        "com.kiloo.subwaysurf"                      to AppCategory.ENTERTAINMENT, // Subway Surfers
        "com.supercell.clashofclans"                to AppCategory.ENTERTAINMENT, // Clash of Clans
        "com.supercell.clashroyale"                 to AppCategory.ENTERTAINMENT, // Clash Royale
        "com.supercell.brawlstars"                  to AppCategory.ENTERTAINMENT, // Brawl Stars
        "com.ea.game.nfs13_row"                     to AppCategory.ENTERTAINMENT, // NFS
        "com.activision.callofduty.shooter"         to AppCategory.ENTERTAINMENT, // COD Mobile
        "com.halfbrick.fruitninjafree"              to AppCategory.ENTERTAINMENT, // Fruit Ninja
        "com.miniclip.eightballpool"                to AppCategory.ENTERTAINMENT, // 8 Ball Pool
        "com.innersloth.impostor"                   to AppCategory.ENTERTAINMENT, // Among Us
        "com.mobile.legends"                        to AppCategory.ENTERTAINMENT, // Mobile Legends
        "com.dream11.d11"                           to AppCategory.ENTERTAINMENT, // Dream11
        "com.mpl.android"                           to AppCategory.ENTERTAINMENT, // MPL Gaming

        // ── NEUTRAL: System & Device Utilities ────────────────────────────────
        "com.android.chrome"                        to AppCategory.NEUTRAL,    // Chrome
        "com.brave.browser"                         to AppCategory.NEUTRAL,    // Brave
        "org.mozilla.firefox"                       to AppCategory.NEUTRAL,    // Firefox
        "com.opera.browser"                         to AppCategory.NEUTRAL,    // Opera
        "com.microsoft.bing"                        to AppCategory.NEUTRAL,    // Bing
        "com.google.android.apps.maps"              to AppCategory.NEUTRAL,    // Google Maps
        "com.google.android.calendar"               to AppCategory.NEUTRAL,    // Google Calendar
        "com.google.android.apps.photos"            to AppCategory.NEUTRAL,    // Google Photos
        "com.google.android.dialer"                 to AppCategory.NEUTRAL,    // Phone/Dialer
        "com.google.android.contacts"               to AppCategory.NEUTRAL,    // Contacts
        "com.android.settings"                      to AppCategory.NEUTRAL,    // Settings
        "com.android.camera2"                       to AppCategory.NEUTRAL,    // Camera
        "com.google.android.apps.cameralite"        to AppCategory.NEUTRAL,    // Camera Go
        "com.google.android.calculator"             to AppCategory.NEUTRAL,    // Calculator
        "com.google.android.deskclock"              to AppCategory.NEUTRAL,    // Clock
        "com.google.android.apps.translate"         to AppCategory.NEUTRAL,    // Google Translate
        "com.google.android.apps.walletnfcrel"      to AppCategory.NEUTRAL,    // Google Wallet
        "net.one97.paytm"                           to AppCategory.NEUTRAL,    // Paytm
        "com.phonepe.app"                           to AppCategory.NEUTRAL,    // PhonePe
        "com.google.android.apps.nbu.paisa.user"    to AppCategory.NEUTRAL,    // Google Pay
        "in.amazon.mShop.android.shopping"          to AppCategory.NEUTRAL,    // Amazon Shopping
        "com.flipkart.android"                      to AppCategory.NEUTRAL,    // Flipkart
        "com.myntra.android"                        to AppCategory.NEUTRAL,    // Myntra
        "com.android.vending"                       to AppCategory.NEUTRAL,    // Play Store
        "com.google.android.play.games"             to AppCategory.NEUTRAL     // Play Games
    )

    // ─── LAYER 2: Weighted Keyword Vocabulary ────────────────────────────────
    // Each entry: keyword → weight (matched against lowercased packageName + appName)
    // Only applied when Layer 1 produces no match.

    private val productiveKeywords = mapOf(
        // Education
        "edu" to 4, "learn" to 4, "study" to 4, "school" to 4, "academy" to 4,
        "course" to 3, "lesson" to 3, "tutor" to 4, "lecture" to 3, "class" to 3,
        "exam" to 3, "quiz" to 3, "math" to 4, "science" to 3, "physics" to 4,
        "chemistry" to 4, "biology" to 4, "grammar" to 3, "dictionary" to 4,
        "vocabulary" to 3, "reading" to 3, "book" to 3, "library" to 3,
        // Productivity
        "mail" to 4, "email" to 4, "productivity" to 3, "notes" to 3,
        "task" to 3, "planner" to 3, "reminder" to 3, "focus" to 3,
        "pomodoro" to 4, "habit" to 3, "goal" to 3, "journal" to 3,
        "document" to 3, "docs" to 3, "office" to 3, "pdf" to 3,
        "code" to 3, "programming" to 4, "developer" to 3, "github" to 4,
        // Known productive app name fragments
        "khan" to 5, "duolingo" to 5, "byju" to 5, "unacademy" to 5,
        "coursera" to 5, "udemy" to 5, "sololearn" to 5, "photomath" to 5,
        "notion" to 4, "evernote" to 4, "todoist" to 4, "anki" to 5,
        "classroom" to 4, "workspace" to 3, "drive" to 3
    )

    private val entertainmentKeywords = mapOf(
        // Social media
        "social" to 3, "feed" to 3, "reel" to 4, "story" to 3,
        "follow" to 3, "like" to 2, "share" to 2, "viral" to 3,
        // Video & streaming
        "video" to 3, "stream" to 3, "watch" to 2, "tube" to 3,
        "movie" to 3, "series" to 2, "anime" to 3, "netflix" to 5,
        "hotstar" to 5, "prime" to 3,
        // Music (passive)
        "spotify" to 5, "music" to 2, "song" to 3, "playlist" to 3,
        // Games
        "game" to 4, "games" to 4, "gaming" to 4, "play" to 3,
        "arcade" to 4, "puzzle" to 3, "battle" to 4, "shoot" to 4,
        "shooter" to 4, "rpg" to 4, "quest" to 3, "adventure" to 3,
        "strategy" to 3, "chess" to 3, "ludo" to 4, "racing" to 3,
        "runner" to 3, "clash" to 5, "saga" to 4, "craft" to 3,
        "hero" to 3, "dragon" to 3, "zombie" to 4, "survival" to 3,
        "casino" to 5, "poker" to 5, "rummy" to 5, "slot" to 4,
        "pubg" to 5, "freefire" to 5, "fortnite" to 5, "minecraft" to 5,
        "roblox" to 5, "candy" to 4, "temple" to 4, "subway" to 4,
        "supercell" to 5, "garena" to 5, "miniclip" to 5, "gameloft" to 5,
        // Known social names
        "instagram" to 5, "facebook" to 5, "twitter" to 5, "snapchat" to 5,
        "tiktok" to 5, "discord" to 4, "youtube" to 4, "reddit" to 4,
        "pinterest" to 4, "telegram" to 3
    )

    private val neutralKeywords = mapOf(
        // Pure system utilities — neither productive nor entertaining
        "clock" to 5, "alarm" to 4, "calculator" to 5, "calendar" to 4,
        "camera" to 4, "gallery" to 4, "photos" to 3, "file" to 3,
        "files" to 4, "settings" to 5, "launcher" to 4, "dialer" to 5,
        "contacts" to 4, "sms" to 3, "browser" to 3, "chrome" to 3,
        "maps" to 4, "navigation" to 3, "weather" to 4, "flashlight" to 5,
        "scanner" to 4, "translator" to 3, "wallet" to 4, "pay" to 3,
        "bank" to 4, "upi" to 5, "vpn" to 4, "antivirus" to 4,
        "cleaner" to 4, "booster" to 3, "shopping" to 3, "store" to 3
    )

    // ─── Core Classification Function ─────────────────────────────────────────

    /**
     * Three-layer classification pipeline.
     *
     * Layer 1: Exact package lookup in Expert Knowledge Base → 99% confidence.
     * Layer 2: Weighted keyword scoring → max 94% confidence.
     * Layer 3: Fallback → NEUTRAL, 0% confidence (safest default, no credit impact).
     */
    fun classify(packageName: String, appName: String): ClassificationResult {

        // ── LAYER 1: Expert Knowledge Base ───────────────────────────────────
        val knownCategory = knowledgeBase[packageName.lowercase().trim()]
        if (knownCategory != null) {
            return ClassificationResult(knownCategory, 99)
        }

        // ── LAYER 2: Weighted Keyword Scoring ────────────────────────────────
        val corpus = "${packageName.lowercase()} ${appName.lowercase()}"

        val scores = mutableMapOf(
            AppCategory.PRODUCTIVE    to 0,
            AppCategory.ENTERTAINMENT to 0,
            AppCategory.NEUTRAL       to 0
        )

        for ((keyword, weight) in productiveKeywords) {
            if (corpus.contains(keyword))
                scores[AppCategory.PRODUCTIVE] = scores[AppCategory.PRODUCTIVE]!! + weight
        }
        for ((keyword, weight) in entertainmentKeywords) {
            if (corpus.contains(keyword))
                scores[AppCategory.ENTERTAINMENT] = scores[AppCategory.ENTERTAINMENT]!! + weight
        }
        for ((keyword, weight) in neutralKeywords) {
            if (corpus.contains(keyword))
                scores[AppCategory.NEUTRAL] = scores[AppCategory.NEUTRAL]!! + weight
        }

        val totalScore = scores.values.sum()

        // ── LAYER 3: Neutral Fallback ─────────────────────────────────────────
        // Default to NEUTRAL (safest fallback — no credit impact on unknown apps)
        if (totalScore == 0) {
            return ClassificationResult(AppCategory.NEUTRAL, 0)
        }

        val winner = scores.maxByOrNull { it.value }!!
        // Layer 2 confidence capped at 94% to distinguish from Layer 1 (99%) matches
        val confidence = minOf(((winner.value.toFloat() / totalScore) * 100).toInt(), 94)

        return ClassificationResult(winner.key, confidence)
    }
}
