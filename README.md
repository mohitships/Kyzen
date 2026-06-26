# Kyzen: AI-Driven Digital Wellbeing Coach

> *A paradigm shift from digital restriction to behavioral regulation.*

Kyzen is an Android digital wellbeing coach that replaces punitive parental controls with an autonomy-supportive system grounded in **Self-Determination Theory (SDT)** and the **Kaizen** philosophy of continuous improvement. Instead of blocking apps, Kyzen guides adolescents toward healthier screen habits through a transparent gem economy, staged interventions, and on-device content-aware YouTube classification.

**Team:** Binary Brigade | **Group:** 124 | **Capstone Project**

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Minimum SDK | API 29 (Android 10) |
| Target SDK | API 36 (Android 16) |
| Build System | Android Gradle Plugin 9.0.1, Gradle 8.x |
| Persistence | Room (SQLite) |
| UI | Material 3, ViewPager2 |
| ML Inference | ONNX Runtime Android, DeBERTa-v3-xsmall (INT8 quantized, 86 MB) |
| Video Playback | Media3 / ExoPlayer |
| HTTP Client | Ktor + OkHttp |
| Image Loading | Coil |

---

## Core Philosophy

Current parental control solutions operate on a binary "Allowed/Blocked" basis, failing to distinguish between productive engagement and passive consumption. This restrictive mediation frequently exacerbates family conflict.

Kyzen is built on four principles:

- **Autonomy Support:** Guides choices rather than forcing compliance, fostering intrinsic motivation.
- **Process Over Outcome:** Rewards constructive effort and regulation rather than punishing end-state behaviors.
- **Positive Reinforcement:** Incentivizes healthy digital habits through a transparent economy rather than unpredictable coercion.
- **Every Pause Is Progress:** Small, mindful moments of reflection are celebrated as steps toward better habits.

---

## Core Features

- **Gem Economy:** Earn +1 gem per 120 seconds of productive app usage; spend -1 gem per 60 seconds of entertainment usage. Daily caps and a 2:1 productive-to-entertainment ratio are fixed to maintain balance.
- **Grace-Based Enforcement:** When the gem wallet empties, a staged overlay system gives users a 15-second grace period to save work before an enforced break.
- **Detox Breaks:** Optional focus sessions with a persistent timer; gems are awarded only after successful completion.
- **YouTube Content Classification:** An in-app YouTube client (FlowPlayerActivity) classifies videos as Productive or Entertainment using a three-tier pipeline — keyword matching against 736 educational channels, a DeBERTa-v3-xsmall zero-shot NLI model on ONNX Runtime, and a Neutral fallback.
- **Parent Dashboard:** Configure spending caps, manual pauses, and view per-app usage breakdowns.
- **Privacy-First Design:** All data stays on-device. No browsing history, message content, or network calls to external servers.

---

## Project Structure

```
app/
├── src/main/java/com/binarybrigade/kyzen/
│   ├── UsageMonitorService.kt      # 2-second foreground polling loop
│   ├── AppClassifier.kt            # Three-tier app classification
│   ├── RewardEngine.kt             # Gem economy logic
│   ├── OverlayActivity.kt          # Intervention overlays
│   ├── ChildDashboardActivity.kt   # Child UI (3-page ViewPager2)
│   └── ParentDashboardActivity.kt  # Parent UI (2-page ViewPager2)
├── src/main/res/                   # Layouts, colors, strings, themes
└── build.gradle.kts

feature-youtube-flow/               # In-app YouTube client module
├── src/main/java/io/github/aedev/flow/
│   ├── classification/             # VideoClassifier, DebertaInferenceEngine
│   ├── innertube/                  # InnerTube API client
│   └── ui/player/                  # FlowPlayerActivity, FlowPlaybackState
└── src/main/assets/onnx/           # DeBERTa ONNX model + tokenizer

docs/                               # Project data and references
└── educational_channels_raw.json   # 736 educational YouTube channels
```

---

## Getting Started

### Prerequisites

- Android Studio (latest stable version).
- A physical Android device running Android 10 or higher.

> Emulators are not recommended — they do not accurately replicate OEM memory management, battery optimization, or background process restrictions, which are critical for Kyzen's monitoring loop.

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/mohitships/Kyzen.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and let the build complete (the ONNX model is ~86 MB and included in the repo).
4. Deploy to a connected physical device.
5. Grant the required system permissions when prompted:
   - Usage Access (`PACKAGE_USAGE_STATS`)
   - Display Overlay (`SYSTEM_ALERT_WINDOW`)
   - Notifications (`POST_NOTIFICATIONS`)
6. Select **Child** or **Parent** mode on first launch.

---

## Architecture Overview

The app uses a background monitoring loop (2-second polling cycle) to track foreground app usage, classify apps into Productive/Entertainment/Neutral tiers, evaluate the gem economy, and trigger interventions when needed. All decisions happen on-device with no network calls.

**Key design decisions:**
- Poll-confirmed foreground time only — no background wakes earn gems.
- Touch-shielded overlays with `FLAG_NOT_TOUCH_MODAL` prevent bypass.
- PiP / Audio defense blocks picture-in-picture and background audio exploits.
- Boot receiver restarts the monitoring service after device reboot.

---

## License

**Copyright (c) 2026 Binary Brigade. All Rights Reserved.**

This repository and its source code are provided for view-only reference and academic evaluation. No open-source license is granted for reproduction, modification, distribution, or commercial use.
