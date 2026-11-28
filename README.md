# Secure Voting System - Android App

Native **Java & Material Design 3** Android app for my Secure Voting System, configured with Kotlin DSL Gradle (`build.gradle.kts`). It connects to the Node.js and PostgreSQL backend to handle face-verified voting, SHA-256 ballot receipts, and election management.

- **Backend API Repo:** [secure-voting-system-backend](https://github.com/prs3112008-spec/secure-voting-system-backend)

---

## Contents

- [What It Does](#what-it-does)
- [How the App Is Locked Down](#how-the-app-is-locked-down)
- [Running the Project](#running-the-project)
- [Tech Stack](#tech-stack)

---

## What It Does

### For Voters
- **Account Registration & Face Opt-In:** Users register with explicit biometric consent, log in with JWT sessions, and can reset forgotten passwords using face verification.
- **On-Device Face Capture + Server Verification:** Captures live frames using **CameraX** and **Google ML Kit** (with blink liveness detection), extracts 192-float **MobileFaceNet** face embeddings locally, and sends them over HTTPS to get a single-use, user-bound token before unlocking the ballot.
- **Poll Dashboard & Live Countdowns:** Switch between active and upcoming polls with live countdown timers, candidate profiles, and duplicate-vote checks.
- **SHA-256 Vote Receipt:** Shows a 64-character SHA-256 receipt hash right after voting so the voter can verify their recorded ballot.
- **Live Results:** Displays real-time candidate vote counts and turnout breakdowns using Material 3 components.

### For Admins
- **Poll Scheduling:** Create elections, set start and end times, and open or close polls.
- **Candidate & Voter Management:** Add candidates with photos, review new voter sign-ups, and approve or revoke voting access.
- **Sub-Admin Permissions:** Assign specific permissions (`can_manage_polls`, `can_manage_candidates`, `can_approve_voters`) to sub-admins instead of sharing full super-admin access.
- **One-Click Hash Chain Check:** Triggers the backend to recompute and verify the SHA-256 hash chain across every vote in a poll to catch any database tampering.
- **Audit Logs & CSV Export:** View logs of admin actions and export election results or audit records to CSV.

---

## How the App Is Locked Down

- **Encrypted Token Storage (`SecurePrefs.java`):** Saves session JWTs in Jetpack `EncryptedSharedPreferences` backed by an **AES256-GCM** Android Keystore `MasterKey`. If encryption fails on a device, it fails closed instead of falling back to plaintext `SharedPreferences`.
- **Blocked Screenshots (`FLAG_SECURE`):** Sets `WindowManager.LayoutParams.FLAG_SECURE` on voting screens to block screenshots, screen recording, and recent-apps preview thumbnails.
- **No ADB or Cloud Backups:** Disables `android:allowBackup`, blocks cleartext HTTP traffic (`usesCleartextTraffic="false"`), and excludes local files in `data_extraction_rules.xml` and `backup_rules.xml`.
- **Zero-Retry Vote Requests:** Disables automatic retries on vote-submission network calls so a flaky mobile connection never fires duplicate requests.
- **Honest Trade-Offs / Known Limits:** While the app checks blink liveness and verifies face embeddings on the server over HTTPS, it does not yet use Google Play Integrity attestation to block rooted devices or emulators.

---

## Running the Project

### What You Need
- **Android Studio:** Ladybug, Meerkat, or newer
- **JDK:** Java 17
- **Android SDK:** Configured via `app/build.gradle.kts` (`targetSdk` 36)

### Setup Steps
1. Clone the repo:
   ```bash
   git clone [https://github.com/prs3112008-spec/secure-voting-system-android.git](https://github.com/prs3112008-spec/secure-voting-system-android.git)
