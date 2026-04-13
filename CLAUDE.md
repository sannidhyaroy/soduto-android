# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Soduto (Android)** — a downstream fork of [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android) maintained by Sannidhya Roy. Soduto is a KDE Connect-compatible cross-device communication app; the Android fork exists to ship bug fixes, reliability improvements, and new plugins faster than upstream, while staying close enough to upstream to keep rebasing tractable.

**Strategic direction:** minimal divergence. Every addition is designed so it can be stripped cleanly when upstream eventually ships an equivalent. Do not propose deep architectural changes. Always ask: will this be easy to remove when upstream catches up?

### Identity

| Key | Value |
|-----|-------|
| App name | Soduto |
| Application ID | `com.thenoton.soduto` |
| Namespace (R/BuildConfig) | `org.kde.kdeconnect_tp` (unchanged from upstream) |
| Upstream repository | <https://invent.kde.org/network/kdeconnect-android.git> |
| Upstream branch | `upstream/master` |
| Downstream repository | <https://github.com/sannidhyaroy/soduto-android.git> |
| Downstream branch | `main` |

## Versioning

Soduto Android uses **independent versioning**, decoupled from the upstream KDE Connect version number.

| Field | Value / Scheme |
|-------|----------------|
| `versionName` | Soduto's own semver: `MAJOR.MINOR.PATCH` (e.g. `4.0.0`) |
| `versionCode` | `MAJOR × 10000 + MINOR × 100 + PATCH` (e.g. `4.0.0 = 40000`) |
| `BuildConfig.KDE_VERSION` | Upstream KDE Connect base (e.g. `"1.35.5"`) |

Both values are defined as named variables at the top of `build.gradle.kts` (`sodutoVersion`, `sodutoVersionCode`, `kdeVersion`) so upstream versionCode/versionName bumps no longer conflict with our lines.

**Version bump rules:**
- Patch (`4.0.1`) — bug fix downstream releases
- Minor (`4.1.0`) — new downstream features
- Major (`5.0.0`) — significant milestone, coordinated with macOS Soduto at major level

The upstream KDE Connect base lives in `kdeVersion` in `build.gradle.kts` and `BuildConfig.KDE_VERSION` at runtime (displayed in the About screen). Update `kdeVersion` when rebasing onto a new upstream release.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run lint
./gradlew lintDebug

# Run all unit tests
./gradlew testDebug

# Run a single test class
./gradlew testDebug --tests "org.kde.kdeconnect.ClassName"

# Generate third-party license report
./gradlew generateLicenseReport
```

**Build config:** minSdk 23, targetSdk 35, compileSdk 36, Kotlin JVM target Java 11, core library desugaring enabled.

## Architecture

### Core Layers

**Application singleton (`KdeConnect.kt`)** — manages all `Device` instances, discovery callbacks, and global shared preferences.

**`BackgroundService.kt`** (foreground service) — owns `LinkProvider` instances, monitors network connectivity, manages the app lifecycle when not in the foreground.

**`Device.kt`** — central class representing a paired remote device. Holds active `BaseLink`s (transport connections), loaded `Plugin`s, and the `PairingHandler`. Dispatches incoming `NetworkPacket`s to the appropriate plugin.

**`NetworkPacket`** — JSON envelope for all inter-device communication. Every packet has a `type` string that routes it to the matching plugin.

### Transport Backends (`backends/`)

- `lan/` — WiFi/LAN via TCP + mDNS/NSD discovery
- `bluetooth/` — Bluetooth RFCOMM with a `ConnectionMultiplexer` for multiplexing virtual channels
- `loopback/` — in-process loopback for local testing

### Plugin System (`plugins/`)

Each feature is a `Plugin` subclass annotated with `@PluginFactory`. KSP (`ClassIndexKSP`) generates a registry at compile time so plugins are discovered without reflection at runtime. Plugins receive `NetworkPacket`s via `onPacketReceived()` and send packets via `device.sendPacket()`. Plugin preferences are split into device-specific and global.

**Upstream plugins:** `battery`, `clipboard`, `connectivityreport`, `contacts`, `digitizer`, `findmyphone`, `findremotedevice`, `inputdevicesreceiver`, `mousepad`, `mousereceiver`, `mpris`, `mprisreceiver`, `notifications`, `ping`, `presenter`, `receivenotifications`, `remotekeyboard`, `runcommand`, `sftp`, `share`, `sms`, `systemvolume`, `telephony`

**Soduto-added plugins:**
- `lock/` — `LockPlugin.kt` reads and controls the lock state of the remote device; sends `requestLocked` on connect, exposes `remoteIsLocked`, provides Lock/Unlock menu actions
- `lockreceiver/` — `LockReceiverPlugin.kt` handles remote lock commands on Android; reports lock state changes via screen broadcasts; executes `lockNow()` via `DevicePolicyManager` when Device Admin is active (requires `KdeConnectDeviceAdminReceiver` registration in the manifest)
- `systemvolumeprovider/` — `SystemVolumeProviderPlugin.kt` exposes Android audio streams to desktop via the `kdeconnect.systemvolume` packet type; `AndroidSinksProvider.kt` discovers streams and tracks volume changes via `ContentObserver`; settings UI in `SystemVolumeProviderSettingsFragment.kt`

### Security

All device-to-device traffic is TLS-encrypted. `SslHelper` handles certificate generation (Bouncy Castle) and `RsaHelper` handles key exchange for pairing. `PairingHandler` drives the pairing state machine.

### UI

`MainActivity` + `DeviceFragment`/`PairingFragment` form the main flow. Material Design 3 with a mix of traditional Views and Jetpack Compose (`ui/compose/`). View Binding used throughout for type-safe layout access.

## Testing

Tests live in `src/test/java/` and use JUnit 4 + Robolectric + MockK. `MockSharedPreference` is a test double for `SharedPreferences`.

## Key Dependencies

- **Apache SSHD + MINA** — SFTP server implementation
- **Bouncy Castle** — TLS certificate generation
- **RxJava 2** — reactive streams (legacy paths; new code uses coroutines)
- **Kotlinx Coroutines** — async operations
- **KSP + ClassIndexKSP** — compile-time plugin registry generation
- **Robolectric / MockK** — unit testing

## Downstream Conventions

### New-file naming

All new Kotlin files added by Soduto use the `Soduto*.kt` prefix (e.g. `SodutoNotificationsHelper.kt`, `SodutoRemoteKeyboardView.kt`). Place new files in the same package as the upstream plugin they relate to — no subdirectory. New files never conflict on rebase; only modified upstream files are conflict-prone.

Keep upstream file diffs minimal: extract improved logic into a `Soduto*.kt` helper, add a small call site (1–5 lines) in the upstream file. This limits conflict surface to the call site lines only.

### String resources

All Soduto-specific strings go in `res/values/strings_soduto.xml` — never in `res/values/strings.xml`. This keeps Soduto additions out of the conflict-prone upstream file. Translatable strings added here should also be added to the 6 supported locale overrides (`values-de`, `values-fr`, `values-ja`, `values-ko`, `values-ru`, `values-zh-rCN`).

### Key Soduto helper files

- `notifications/SodutoNotificationsHelper.kt` — notification icon extraction with a 3-level hierarchy (large → launcher → small), SharedPreferences-controlled master toggle and adaptive-icon compositing mode
- `notifications/SodutoNotificationFilterController.kt` — owns notification icon row UI (two-target toggle + bottom sheet) in `NotificationFilterActivity`
- `remotekeyboard/SodutoRemoteKeyboardView.kt` — Material 3 control strip replacing the deprecated `KeyboardView` in the Remote Keyboard IME
- `res/layout/list_item_two_target_toggle.xml` — shared two-target row layout (tappable label + divider + `MaterialSwitch`) used by SystemVolumeProvider settings and notification filter

### Commit message scopes

| Scope | Use for |
|-------|---------|
| `(patch)` | Fork infrastructure: CI workflows, `PATCH.yaml`, `SYNC_LOG.yaml`, `CLAUDE.md`, docs |
| `(app)` | App source changes: build config, plugin fixes, resource overrides |
| *(no scope)* | Upstream cherry-picks — match upstream commit style |

Always write a body listing the specific changes made. Do not use internal milestone references ("Phase N") in any commit message or public-facing file.

### Supported locales

Soduto maintains **6 languages**: `de`, `fr`, `ja`, `ko`, `ru`, `zh-rCN`. The 40+ upstream locales and the `po/` directory have been removed. Do not re-add them.

## Upstream Sync Workflow

### Tracking files

- **`PATCH.yaml`** — single source of truth for the current downstream state: upstream base commit, last rebase snapshot, and a patch manifest with `intent` + `resolution_strategy` for every modified/added/deleted file. Consult this file first when resolving rebase conflicts.
- **`SYNC_LOG.yaml`** — append-only rebase history. Records `pre_rebase_head` (the orphaned commit hash of `main` before each force-push) as an audit trail.

### Rebase strategy (not merge)

This fork uses **rebase** onto `upstream/master`. Single-maintainer repo — force-push (`--force-with-lease`) is safe. This keeps history linear and `git format-patch` trivially clean.

To check how far behind upstream we are:
```bash
git log <base_commit>..upstream/master --oneline      # pending upstream commits
git rev-list --count <base_commit>..upstream/master   # count
```
where `<base_commit>` is `upstream.base_commit` in `PATCH.yaml`.

### Bare-repo sync mechanism

A sibling bare repo (`kdeconnect-android.git`) mirrors all upstream refs into `upstream/*` branches/tags on GitHub via custom refspecs, keeping upstream and downstream namespaces separate. The working clone (`soduto-android/`) is a normal clone of the GitHub repo.

## CI Workflows

- **`.github/workflows/sync-upstream.yml`** — periodically fetches upstream refs and updates `PATCH.yaml` fields marked `[auto]`
- **`.github/workflows/notify-upstream.yml`** — opens a GitHub issue when new upstream commits are detected that haven't been integrated

## Patch Manifest Quick Reference

When resolving a rebase conflict:
1. Find the conflicting file in `patches.modified` in `PATCH.yaml`
2. Read `intent` — why this change exists
3. Read `resolution_strategy` — how to resolve
4. Record resolved conflicts in `SYNC_LOG.yaml`

Files marked `conflict_prone: true` (upstream actively touches them): `MprisReceiverPlugin.java`, `AndroidManifest.xml`, `res/values/strings.xml`, `ClipboardListener.kt`, `DeviceFragment.kt`, `NotificationFilterActivity.java`, `activity_notification_filter.xml`.
