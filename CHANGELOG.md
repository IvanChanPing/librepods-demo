# Changelog

## 2026-08-12 — Bluetooth-free screen demo

- Added a separately installable `me.kavishdevar.librepods.demo` flavor named LibrePods Demo.
- Added a first-screen catalog that opens every production screen with deterministic AirPods, battery, control, proximity, microphone, Find My, heart-rate, workout, and Health Connect fixture data.
- Kept demo interactions inside fixture state instead of starting the AirPods service, Xposed/native Bluetooth hook, AACP, ATT, head-tracking, microphone, Health Connect, or billing operations.
- Removed Bluetooth permissions, connected-device foreground service, boot startup, widgets, Quick Settings tile, phone/location/health permissions, and billing permission from the Demo manifest.
- Added a GitHub Actions build that tests the Demo variant and rejects an APK containing the production package ID or any Bluetooth service, receiver, widget, tile, or permission.

## 2026-08-11 — Future Find My client and interaction repairs

- Included the explicitly authorized OpenBubbles client and typed device/location contract for future Find My Network support.
- Kept Find My Network disabled behind a single readiness flag; the greyed-out Not ready entry performs no provider binding, pairing, refresh, or launch actions while local nearby UWB/BLE routing remains available.
- Added a card-to-analytics Heart Rate bounds transition with explicit tween timing and reverse navigation behavior.
- Repaired the onboarding compatibility bypass with the existing themed confirmation, live support-state update, redacted diagnostics, and direct navigation to Permissions.
- Made redacted feature diagnostics debug-only and default-off, with a separate onboarding opt-in/decline prompt, an App Settings revocation toggle, corrected privacy disclosure, bounded payload fields, retained failed events, coalesced retry scheduling, validated-network routing, and connection cleanup.
- Added the missing Material surface container to the shared themed confirmation dialog so diagnostics and compatibility prompts remain legible over their source screens.
- Moved opted-in events to a POST-only private collector with strict field validation, no public read path or access logging, daily size limits, and automatic 14-day deletion.
- Prevented Directions from crashing when no map activity is installed; the UI now reports the failure and emits only a bounded redacted event when diagnostics are enabled.
- Aligned Find My, Spatial Audio, and Heart Rate analytics with LibrePods' Material/Apple card geometry, backdrop, and destination inset rules.
- Added `.github/workflows/build-side-by-side-apk.yml` to test, build, package-verify, hash, and upload the `me.kavishdevar.librepods.complete` debug APK without release secrets.
- Built canonical `librepods-debug.apk` (64,466,700 bytes, package `me.kavishdevar.librepods.complete`, version `1.0.0-rc2-debug`, SHA-256 `8270668fc7b45ce2cf1f28c71a791d1b6f83cb7289cdbdea6e98d66dc98188d6`).

## 2026-08-11 — Researched AirPods feature package

- Changed published FOSS builds to application ID `me.kavishdevar.librepods.complete` so this fork installs beside the original LibrePods app. Internal actions, Xposed preferences, resource URIs, companion metadata, log filtering, provider authority, and root-module permissions now follow the active variant identity.
- Added Spatial Audio controls beside the battery summary and under Audio settings, with Off, Fixed, and Head Tracked modes plus an app-scoped stereo preview. The screen explicitly reports that LibrePods cannot replace Android's system spatializer without a compatible privileged provider.
- Added Fitness-style Heart Rate sessions with workout/recovery charts, optional user-defined zones, ten-session history, bounded local storage, and separate Health Connect controls.
- Added an in-app high-resolution AirPods microphone path using the AACP AAC-ELD stream, including a live level meter, a bounded five-second local recording, and playback.
- Added a dedicated Find My page under App Settings with a local map, last-seen-near-phone state, directions, verified AirPods-advertisement RSSI guidance, and opt-in local separation notifications.
- Added capability-based nearby-finding routing: compatible phones report UWB availability, while BLE guidance remains active when UWB is absent or the AirPods owner-session contract is unavailable. Proprietary Find My network login, case-tone commands, and AirPods UWB ranging remain visibly disabled instead of being simulated.
- Added bounded, redacted auto-upload diagnostics for feature failures and lifecycle events. Diagnostics exclude location, biometric values, audio, device addresses, credentials, and protocol payloads.
- Built canonical side-by-side debug artifact `librepods-debug.apk` (59,870,238 bytes, package `me.kavishdevar.librepods.complete`, version `1.0.0-rc2-debug`, SHA-256 `a4eaa17fbbe244e9b4ce2b4919455e6c83067b30fc291bc22c13d67487dc1e1c`).

## 2026-08-11 — Fork APK workflow

- Updated `.github/workflows/ci-android.yml` so `IvanChanPing/librepods` builds and uploads the FOSS debug APK on pushes, pull requests, and manual dispatches without requiring upstream release-signing secrets.
- Preserved the signed release, root-module, bundle, release, and Discord paths for repositories other than the fork.
- Added the workflow file to its own path filter so CI runs when the workflow changes.
- Locally compile-verified version `1.0.0-rc2-debug` with `testFossDebugUnitTest assembleFossDebug`; the test task had no sources and the APK build completed successfully.
- Placed canonical artifact `librepods-debug.apk` at the repository top level: 54,029,818 bytes, SHA-256 `df9759322dac2ffbc12cfae16422c28e630fb8a74daff6dd3dc0ca478c9cd108`.
