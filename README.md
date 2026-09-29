# ✈ Aviator Signal Lab

A production-grade Android scientific research application for client-side network observability, protocol discovery, multi-window feature extraction, and pre-crash signature analysis on the Aviator game available at `damansuperstar1.com`.

---

## 🔬 Core Scientific Hypothesis

The core research objective of this application is to answer one precise question:

> **"Does client-side network/application activity immediately before a crash (specifically within 0.1s to 5.0s before crash) contain a repeatable, statistically valid signature that is absent or significantly less frequent during comparable normal live periods?"**

The system is strictly designed with **zero predictive fabrication**:
- If no statistically repeatable pattern exists that survives chronological out-of-sample testing against matched control periods, the system honestly reports:
  `"NO RELIABLE PRE-CRASH ACTIVITY SIGNAL DETECTED"`
- It never manufactures misleading predictions, streak fallacies, or hot/cold heuristics.

---

## 🧱 Architecture Overview

```
                      ┌──────────────────────────────────────────────┐
                      │          damansuperstar1.com (WebView)       │
                      └──────────────────────┬───────────────────────┘
                                             │ Client-Side Hooks
                                             ▼
                      ┌──────────────────────────────────────────────┐
                      │            ScriptInjector (JS)               │
                      │  WebSocket • Fetch • XHR • postMessage • DOM │
                      └──────────────────────┬───────────────────────┘
                                             │ @JavascriptInterface
                                             ▼
                      ┌──────────────────────────────────────────────┐
                      │             GameProtocolBridge               │
                      └──────────────────────┬───────────────────────┘
                                             ▼
                      ┌──────────────────────────────────────────────┐
                      │          SensitiveDataRedactor               │
                      │    (Tokens, Passwords, Cookies -> REDACTED)  │
                      └──────────────────────┬───────────────────────┘
                                             ▼
                      ┌──────────────────────────────────────────────┐
                      │         ProtocolDiscoveryEngine              │
                      │    Dynamic Round Detection & State Machine   │
                      └──────────────┬───────────────────────────────┘
                                     │
         ┌───────────────────────────┴───────────────────────────┐
         ▼                                                       ▼
┌──────────────────┐                                   ┌──────────────────┐
│   Room Database  │                                   │ LeakageGuard &   │
│ • rounds         │                                   │ FeatureExtractor │
│ • live_events    │                                   │ (T-0.1s to 5.0s) │
│ • round_features │                                   └─────────┬────────┘
│ • patterns       │                                             │
└────────┬─────────┘                                             ▼
         │                                             ┌──────────────────┐
         │                                             │  Chronological   │
         │                                             │    Validator     │
         │                                             └─────────┬────────┘
         │                                                       │
         ▼                                                       ▼
┌──────────────────┐                                   ┌──────────────────┐
│ CsvExporter &    │                                   │ScientificAnalysis│
│ ZipExportManager │                                   │     Engine       │
└──────────────────┘                                   └──────────────────┘
```

---

## 🚀 Key Features

1. **Passive Observability**:
   - Intercepts client-visible `WebSocket` messages, `fetch()` calls, `XMLHttpRequest`, `EventSource`, and `postMessage` (cross-iframe communication).
   - Never injects wagers, alters server packets, or modifies game outcomes.
2. **Strict Sensitive Data Redaction**:
   - Automatically masks passwords, OTPs, auth tokens, session cookies, and payment information with `[REDACTED]`.
   - Never stores or exports private user secrets.
3. **Multi-Window Pre-Crash Analysis (T-0.1s to T-5.0s)**:
   - Evaluates:
     - Broad window: $T-5.0s$ to $T-0.1s$
     - Medium window: $T-3.0s$ to $T-0.1s$
     - Immediate window: $T-2.0s$ to $T-0.1s$
     - Sub-second window: $T-1.0s$ to $T-0.1s$
     - Micro window: $T-0.5s$ to $T-0.1s$
     - Ultrafast window: $T-0.25s$ to $T-0.1s$
   - Computes event rates, inter-event intervals (mean, median, min, max, std dev), burstiness index, quiet periods, and sequence n-grams.
4. **Matched Control Windows**:
   - Compares pre-crash windows against mid-flight normal live control periods of equivalent duration to eliminate false positives caused by generic round progression.
5. **Leakage-Proof Design**:
   - Automated assertions verify that any event occurring at or after crash ($T \ge T_{crash}$) is strictly excluded from pre-crash feature calculation.
6. **Chronological Out-of-Sample Validation**:
   - Splits historical rounds chronologically (70% training, 30% testing) to verify whether any discovered pattern survives unseen rounds.
7. **Complete Dataset Export**:
   - Generates RFC 4180 compliant CSV files:
     - `rounds.csv`
     - `live_events.csv`
     - `round_features.csv`
     - `protocol_fields.csv`
     - `discovered_patterns.csv`
   - **EXPORT ALL (ZIP)** packages everything with a JSON manifest and shares via Android's native share sheet.
8. **In-App Update System**:
   - Compares installed `versionCode` against the latest published GitHub release metadata.
   - Downloads updated APK with live progress indicator and triggers Android's native `Intent.ACTION_VIEW` package installer via `FileProvider`.

---

## 🛠 Local Build Instructions

### Prerequisites
- Android Studio Ladybug / Meerkat (or newer)
- JDK 17
- Android SDK with `compileSdk 35` and `build-tools 35.0.0`

### Build Commands
```bash
# Clone the repository
git clone https://github.com/your-username/aviator-signal-lab.git
cd aviator-signal-lab

# Run unit tests
./gradlew test

# Build Debug APK
./gradlew :app:assembleDebug

# Build Release APK (requires release signing environment variables)
./gradlew :app:assembleRelease
```

Generated APKs are located at:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

---

## 🤖 GitHub Actions CI/CD & Automated Releases

The repository contains a fully automated workflow at `.github/workflows/build-and-release.yml`.

### How It Works:
1. Every `push` or `pull_request` on `main` runs unit tests and builds the debug and release APKs.
2. APK artifacts are uploaded to the GitHub Actions workflow run.
3. Publishing a release tag (e.g. `git tag v1.0.1 && git push origin v1.0.1`) or triggering the manual `workflow_dispatch` action will:
   - Build signed APK.
   - Generate `latest_release.json` metadata.
   - Create an official GitHub Release with downloadable APK asset.

---

## 🔑 Release Signing Setup (GitHub Secrets)

To enable seamless in-app updates, every release APK must be signed with the same private key and incremented `versionCode`.

### Step 1: Generate a Keystore
```bash
keytool -genkeypair -v -keystore release.keystore -alias aviatorlab -keyalg RSA -keysize 2048 -validity 10000
```

### Step 2: Encode Keystore to Base64
On Linux/macOS:
```bash
base64 -w 0 release.keystore > keystore_base64.txt
```
On Windows PowerShell:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Out-File -Encoding ASCII keystore_base64.txt
```

### Step 3: Add GitHub Repository Secrets
In your GitHub repository, navigate to **Settings > Secrets and variables > Actions** and add:
- `KEYSTORE_BASE64`: The full base64 string from `keystore_base64.txt`.
- `KEYSTORE_PASSWORD`: The keystore password.
- `KEY_ALIAS`: The key alias (e.g., `aviatorlab`).
- `KEY_PASSWORD`: The private key password.

---

## 🔄 Releasing Updates & In-App Update Flow

When you want to ship a new version to users:

1. Open `app/build.gradle.kts`.
2. Increment `versionCode` (e.g. from `1` to `2`).
3. Update `versionName` (e.g. from `"1.0.0"` to `"1.0.1"`).
4. Commit and push:
   ```bash
   git add app/build.gradle.kts
   git commit -m "Bump version to 1.0.1"
   git tag v1.0.1
   git push origin main --tags
   ```
5. GitHub Actions builds the signed APK and publishes the release.
6. When existing users tap **Update** or open the app, the in-app update dialog displays:
   - Current Version vs New Version Available
   - Release Notes
   - **UPDATE NOW**: Downloads the APK and launches the standard Android package installer.
   - Android updates the app in-place without losing research database history!

---

## 🔍 Diagnostics & Troubleshooting

- **Zero Events Captured**:
  If the Diagnostics dialog shows `Total Events: 0`, verify that the game has fully loaded into the WebView viewport. The instrumentation script automatically hooks `WebSocket`, `Fetch`, `XHR`, and `postMessage`.
- **Renderer Restarts**:
  If Chromium terminates due to memory pressure on low-RAM devices, `InstrumentedWebViewClient.onRenderProcessGone()` catches the crash, recreates the view, and restores collection without dropping stored database rounds.
- **Login & Cookies**:
  The application maintains standard WebView cookies and DOM storage so that legitimate account login functions normally.

---

## 📜 License
For scientific and educational network observability research only.
