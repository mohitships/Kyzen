# Kyzen: Digital Wellbeing Coach

> *A paradigm shift from digital restriction to behavioral regulation.*

Kyzen is an Android-based digital wellbeing ecosystem designed to help adolescents build self-regulation and healthier screen habits. Moving away from the traditional "digital jailer" model that relies on punitive app blocking and rigid time limits, Kyzen utilizes an autonomy-supportive coaching system grounded in Self-Determination Theory (SDT) and the Kaizen philosophy of continuous improvement.

## The Philosophy

Current parental control solutions often operate on a binary "Allowed/Blocked" basis, failing to distinguish between productive engagement and passive consumption. This restrictive mediation frequently exacerbates family conflict and encourages secretive behavior.

Kyzen is built on a different set of core principles:
* **Autonomy Support:** Guides choices rather than forcing compliance, fostering intrinsic motivation.
* **Process Over Outcome:** Rewards constructive effort and regulation rather than punishing end-state behaviors.
* **Positive Reinforcement:** Incentivizes healthy digital habits through a transparent economy rather than unpredictable coercion.

## Core Features

* **Gamified Reward Engine:** Kyzen replaces fixed time limits with a dynamic economy. Users earn "gems" by engaging with productive applications and spend them when using entertainment apps.
* **Balanced Economy:** The system includes daily earning and spending caps to prevent excessive usage, while allowing unused gems to carry forward to encourage long-term saving habits.
* **Grace Enforcement:** To reduce the frustration of sudden app closures, Kyzen utilizes a staged intervention model. When the gem balance reaches zero, users receive a short grace period overlay, allowing them to save their work and mentally transition before the break is enforced.
* **Detox Breaks:** The application includes a persistent timer for voluntary offline breaks, rewarding users only after the detox period is successfully completed.
* **Smart Categorization:** The system evaluates installed packages and dynamically categorizes them into Productive, Entertainment, or Neutral tiers to ensure the economy reflects the true nature of the screen time.
* **Privacy-First Design:** Kyzen operates as a localized application, ensuring all usage data remains on the device. It strictly avoids invasive tracking practices, such as inspecting message content or browsing history.

## Technical Architecture

The application is built natively for Android, prioritizing battery efficiency, system stability, and user privacy.

* **Platform:** Android 10+ (API Level 29 minimum).
* **Language:** Kotlin.
* **Design Pattern:** The system follows a localized, event-driven model that cleanly separates background tracking logic from the presentation layer.
* **System Integration:** Operations are executed using standard OS interfaces. The architecture explicitly avoids elevated system privileges, such as Accessibility Services or Device Administrator policies, to minimize the application's privilege surface area and ensure compliance with modern OS constraints.
* **Local Storage:** State and usage data are persisted securely and locally on the device utilizing Room SQLite.

## Getting Started

### Prerequisites

* Android Studio (Latest Stable Version).
* A physical Android device running Android 10 or higher. 

*Note: Software emulators are not recommended for evaluation, as they fail to accurately replicate true OEM memory management, battery optimization policies, and background application restrictions.*

### Installation

1. Clone the repository to your local machine.
2. Open the project utilizing Android Studio.
3. Build the application and deploy it to your connected physical device.
4. Upon the initial launch, the application will prompt for the necessary system permissions required for the monitoring and overlay functions to operate.
5. Select the appropriate operational mode (Parent or Child) to configure the dashboard and establish usage boundaries.

## License

**Copyright (c) 2026 Binary Brigade. All Rights Reserved.**

This repository and its source code are provided strictly for view-only reference and academic evaluation. No open-source license is granted for reproduction, modification, distribution, or commercial use.