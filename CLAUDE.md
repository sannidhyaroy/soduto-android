# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KDE Connect Android — a plugin-based app enabling cross-device communication between Android and desktop (KDE Plasma, etc.). Features include clipboard sync, notification forwarding, file sharing via SFTP, multimedia control, SMS/MMS, virtual touchpad, and more.

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

Three link provider implementations, each providing `BaseLink` instances to `Device`:
- `lan/` — WiFi/LAN via TCP + mDNS/NSD discovery
- `bluetooth/` — Bluetooth RFCOMM with a `ConnectionMultiplexer` for multiplexing virtual channels
- `loopback/` — in-process loopback for local testing

### Plugin System (`plugins/`)

Each feature is a `Plugin` subclass annotated with `@PluginFactory`. KSP (`ClassIndexKSP`) generates a registry at compile time so plugins are discovered without reflection at runtime. Plugins receive `NetworkPacket`s via `onPacketReceived()` and send packets via `device.sendPacket()`. Plugin preferences are split into device-specific and global.

Key plugins: `battery`, `clipboard`, `findmyphone`, `mousepad` (digitizer/trackpad), `mpris` (media control), `notifications`, `runcommand`, `share`, `sftp`, `sms`, `systemvolume`, `telephony`.

### Security

All device-to-device traffic is TLS-encrypted. `SslHelper` handles certificate generation (Bouncy Castle) and `RsaHelper` handles key exchange for pairing. `PairingHandler` drives the pairing state machine.

### UI

`MainActivity` + `DeviceFragment`/`PairingFragment` form the main flow. Material Design 3 with a mix of traditional Views and Jetpack Compose (`ui/compose/`). View Binding used throughout for type-safe layout access.

## Testing

Tests live in `src/test/java/` and use JUnit 4 + Robolectric + MockK. `MockSharedPreference` is a test double for `SharedPreferences`.

Coverage includes core classes (`Device`, `NetworkPacket`, `PairingHandler`) and most plugins.

## Key Dependencies

- **Apache SSHD + MINA** — SFTP server implementation
- **Bouncy Castle** — TLS certificate generation
- **RxJava 2** — reactive streams (legacy paths; new code uses coroutines)
- **Kotlinx Coroutines** — async operations
- **KSP + ClassIndexKSP** — compile-time plugin registry generation
- **Robolectric / MockK** — unit testing
