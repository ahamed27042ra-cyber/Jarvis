> **Current verification status (2026-10-14):** GitHub Actions builds the Android APK and deploys a static web-console preview. The public web console is **not** a live admin backend; Firebase authentication, Gemini connectivity, remote phone control, and end-to-end automation have not yet been verified on a configured account and physical device. CI success means build/package checks passed, not that every feature works. The workflow-generated release APK currently uses the debug signing configuration and is **not a production-signed release**. See [JARVIS_PHASE_PLAN.md](JARVIS_PHASE_PLAN.md) for the tracked implementation phases and blockers.
>
# 🤖 JARVIS AI - Autonomous Android Intelligence Assistant

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.4-brightgreen.svg)](https://gradle.org)
[![Version](https://img.shields.io/badge/Release-v1.8.8-orange.svg)](version.json)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)](#)

JARVIS AI is an Android AI-assistant project with Kotlin-based voice, vision, accessibility, and automation components. Gemini Live, Firebase, and device-control integrations require valid project configuration, runtime permissions, and real-device testing before they can be considered operational.

---

## 🏛️ Architecture Overview

The project is structured into three primary components:
1. **Core Android Native Application (`/app`)**: Kotlin-based Android application orchestrating voice, vision, accessibility actions, and device-level operations.
2. **Web console (`/admin-panel`)**: A static web preview. Live screen mirroring, command dispatch, and remote monitoring are not verified as connected to a secure backend.
3. **Update flow (`version.json`)**: Update-related code is present; a secure production OTA release flow has not been independently verified.

```
Jarvis-Ai/
├── app/                                # Android Native Client Source
│   ├── src/main/
│   │   ├── java/com/jarvis/assistant/
│   │   │   ├── ai/                     # Gemini Live audio engine & streaming client
│   │   │   ├── firebase/               # Firebase Firestore & Realtime sync layer
│   │   │   ├── model/                  # Data structures & chat models
│   │   │   ├── security/               # Encrypted preferences & integrity verification
│   │   │   ├── service/                # Foreground, accessibility & floating orb services
│   │   │   ├── ui/                     # Presentation layer (Auth, Main, Settings, Legal)
│   │   │   ├── update/                 # OTA in-app update manager
│   │   │   ├── util/                   # Device automation, calling, SMS & web engines
│   │   │   ├── vision/                 # Real-time camera & screen streaming engines
│   │   │   ├── wake/                   # Hotword & wake word detection
│   │   │   └── youtube/                # YouTube playback & API integration
│   │   ├── assets/                     # 3D Three.js orb renderer & local web views
│   │   └── res/                        # Native vector drawables, layouts & styles
│   └── build.gradle                    # App module dependencies and build config
├── admin-panel/                        # Cloud Admin Web Control Center
│   ├── css/style.css                   # Glassmorphism dark UI theme
│   ├── js/app.js                       # Real-time stream consumer & command controller
│   ├── js/firebase-config.js           # Firebase configuration
│   └── index.html                      # Control console interface
├── Jarvis-AI-Release.apk               # Official signed release binary (v4.0.0)
├── version.json                        # OTA version manifest
├── build.gradle                        # Root build script
├── settings.gradle                     # Gradle project settings
└── .gitignore                          # Standardized clean repository ignore rules
```

---

## ⚡ Core Capabilities

- **🎙️ Gemini Live integration (unverified)**: WebSocket audio-streaming code is present; working credentials and on-device lifecycle tests are still required.
- **👁️ Vision and screen-streaming components (unverified)**: Camera/screen capture requires explicit Android permissions; a live, secure console connection has not been verified.
- **🦾 Accessibility automation components**: Device actions depend on user-enabled accessibility permissions and must be tested on a physical device. Consequential actions should require explicit user confirmation.
- **🔮 3D Reactive Holographic Orb**: Interactive Three.js WebGL particle orb with sound-wave reactive audio animations.
- **🛡️ Credential storage code**: Review configuration and verify runtime key handling before making production security claims.
- **🔄 Update components**: Update checks and installer flows require device testing; this repository does not currently establish a production-signed OTA release.

---

## 🚀 Setup & Build Guide

### Prerequisites
- **JDK**: Java 17+
- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Android SDK**: API 26 (Minimum) to API 34 (Target)
- **Gradle**: 8.4+

### Building APK
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

---

## 🔒 Security & Privacy

- All sensitive API keys are encrypted at rest using Android Keystore.
- Cloud telemetry is restricted to authenticated Firebase projects.
- In compliance with Google Play Developer policies regarding Accessibility APIs.

---

## 📄 License & Ownership
Copyright © 2026 Jarvis AI. All rights reserved.
