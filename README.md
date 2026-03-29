<div align="center">
  <img src="icon.svg" alt="Soduto (Android) Logo"/>
  <h1 style="font-weight: 700; font-size: 4em; margin: 0; padding-top: 0;">Soduto (Android)</h1>
  <div style="margin-bottom: 1em">
  <a href="https://github.com/sannidhyaroy/soduto-android/blob/main/LICENSE"><img src="https://img.shields.io/github/license/sannidhyaroy/soduto-android.svg?color=B0BB88&style=flat-square" alt="GNU Licensed"></a>
  <a href="https://github.com/sannidhyaroy/soduto-android/issues"><img alt="GitHub issues" src="https://img.shields.io/github/issues/sannidhyaroy/soduto-android?color=B0BB88&style=flat-square"></a>
  <a href="https://github.com/sannidhyaroy/soduto-android/commits/main"><img src="https://img.shields.io/github/last-commit/sannidhyaroy/soduto-android/main?color=B0BB88&style=flat-square" alt="Last Commit"></a>
  <a href="https://github.com/sannidhyaroy/soduto-android/releases/latest"><img alt="GitHub Release Date" src="https://img.shields.io/github/release-date/sannidhyaroy/soduto-android?color=B0BB88&style=flat-square"></a>
  <a href="https://github.com/sannidhyaroy/soduto-android/releases"><img alt="GitHub Release Downloads" src="https://img.shields.io/github/downloads/sannidhyaroy/soduto-android/total?color=B0BB88&style=flat-square"></a>
  </div>
  <p>
  Soduto is a KDE Connect compatible application. It allows better integration between your phones, desktops and tablets. This is a downstream of KDE Connect (Android). For more information take a look at <a href="https://invent.kde.org/network/kdeconnect-android">kdeconnect-android</a>.
  </p>
</div>

---
## **Navigation**
- [Installation](#installation)
- [Features](#features)
- [Verifying Downloads](#verifying-downloads)
  - [Signing Identity](#-signing-identity)
  - [Verification Steps](#verification-steps)
- [Limitations](#limitations)
  - [Clipboard Sync (Android 10+)](#clipboard-sync-android-10)
  - [Storage Access (Android 11+)](#storage-access-android-11)
  - [Sensitive Notification Content (Android 15+)](#sensitive-notification-content-android-15)
- [Workarounds](#workarounds)
  - [Clipboard Sync (Android 10+)](#clipboard-sync-android-10-1)
  - [Storage Access (Android 11+)](#storage-access-android-11-1)
  - [Sensitive Notification Content (Android 15+)](#sensitive-notification-content-android-15-1)
- [Documentation & Resources](#documentation--resources)
- [Get in touch](#get-in-touch)
- [License](#license)
---

## Installation

You can install this app from the [Play Store](https://play.google.com/store/apps/details?id=com.thenoton.soduto) as well as [F-Droid](https://f-droid.org/repository/browse/?fdid=com.thenoton.soduto). Note you will also need to install the [Soduto (macOS) app](https://github.com/sannidhyaroy/Soduto) or any other KDE Connect client for it to work.

---

## Features

Soduto (Android) implements the following KDE Connect plugin features (compatible with any KDE Connect device):

### Notifications
- [x] Receive: Mirror remote device notifications on android
- [x] Send: Send android notifications to remote devices

### Clipboard
- [x] Receive: Receive clipboard content from remote devices
- [x] Send: Send android clipboard content to remote devices (needs manual action)

### File Sharing
- [x] Receive: Receive shared files, links, and text from remote devices
- [x] Send: Share files, links, and text to remote devices
- [ ] Browse storage: Access remote device filesystem via SFTP

### Battery Status
- [x] Receive: View remote device battery level and charging status
- [x] Send: Send android battery status to remote devices

### Media Control (MPRIS)
- [x] Receive: Control remote device music/video playback
- [x] Send: Control android music/video playback from remote devices

### System Volume
- [x] Receive: View and Control audio streams on remote device from android
- [ ] Send: View and Control audio streams on android from remote device

### Telephony
- [x] Send: View incoming call and SMS notifications on android from remote devices

### Remote Input
- [x] Receive: Use remote device as keyboard and touchpad for android
- [x] Send: Use android to control remote device input

### Ping
- [x] Receive: Receive ping messages from remote devices
- [x] Send: Send ping messages to remote devices

### Run Commands
- [x] Receive: Execute android commands from remote devices
- [x] Send: Execute predefined commands on remote devices

### Find My Device
- [x] Receive: Make android play an alarm sound to locate it
- [x] Send: Make remote device play an alarm sound to locate it

### Connectivity Report
- [x] Send: Monitor android's network connectivity status on remote device

### Presentation Remote
- [x] Send: Use android as presentation remote for remote device

### Contacts
- [x] Send: Synchronize contacts between android and remote devices

### Digitizer
- [x] Send: Use android as pressure-sensitive drawing tablets to draw on remote device

For the complete list of KDE Connect features and documentation, visit the [official KDE Connect Wiki](https://userbase.kde.org/KDEConnect).

---

## Verifying Downloads
All releases published in this repository are cryptographically signed.
This allows you to verify that a downloaded release was published by the Soduto project and was not modified after publication.

This step is **optional** and intended for users who want additional security guarantees.

- The signing key is **not stored in this repository**.
- The canonical source of truth for the signing key is the project domain.
- The same key is discoverable automatically via standard OpenPGP mechanisms.
- The APK itself is not signed with GPG. Instead, the checksum file is signed to verify authenticity and integrity.
- Play Store/F-Droid handles update security separately.
- Most users do not need to perform these steps, unless they wish to manually verify downloads.


### 🔑 Signing Identity
**Signed by**: _Soduto Releases (Soduto Release Signing Key)_ _`releases@soduto.thenoton.com`_

**Key fingerprint:** _`4951 D786 1266 F77E A86D  2B3C D952 D26C 5D6D 9D22`_

You can use the fingerprint above to manually verify that you have obtained the correct public key.

### Verification steps:
- The easiest way is to let GPG locate the key automatically.
  ```bash
  gpg --locate-keys releases@soduto.thenoton.com
  ```

  <details><summary>Not Working? 🥲</summary>

  #### Try any of the following methods:

  - Force **Web Key Directory (WKD)** (recommended):
    ```bash
    gpg --locate-keys --auto-key-locate wkd releases@soduto.thenoton.com
    ```
  - Force a **public keyserver lookup** (email-verified at [keys.openpgp.org](https://keys.openpgp.org)):
    ```bash
    gpg --locate-keys --keyserver hkps://keys.openpgp.org releases@soduto.thenoton.com
    ```
  - Final fallback - manual import:
    ```bash
    curl -o soduto-release-signing-key.asc https://raw.githubusercontent.com/sannidhyaroy/soduto-releases/refs/heads/main/.well-known/openpgpkey/hu/i4cdqgcarfjdjnba6y4jnf498asg8c6p
    gpg --import soduto-release-signing-key.asc
    ```

  </details>
- Download the [`.apk`](https://github.com/sannidhyaroy/Soduto/releases/latest/download/Soduto.apk) file, the [`.apk.sha256`](https://github.com/sannidhyaroy/Soduto/releases/latest/download/Soduto.apk.sha256) file and the [`.apk.sha256.asc`](https://github.com/sannidhyaroy/Soduto/releases/latest/download/Soduto.apk.sha256.asc) file.
- Open the directory where you downloaded the `.apk` file, and run the following commands:
  ```bash
  gpg --verify Soduto.apk.sha256.asc  # Enter correct path to the .apk.sha256.asc file
  shasum -a 256 -c Soduto.apk.sha256  # Enter correct path to the .apk.sha256 file
  ```
  Expected output includes:
  ```
  Good signature from "Soduto Releases <releases@soduto.thenoton.com>"
  ```
  This confirms that:
  - The checksum file (`.apk.sha256`) was signed by the Soduto Release Signing Key.
  - The downloaded `.apk` matches the published SHA-256 checksum.

---

## Limitations

#### Clipboard Sync (Android 10+)
Google introduced privacy restrictions on Android 10 and higher that prevent apps from accessing clipboard data unless the app is the default input method editor (IME) or is currently in focus. This affects seamless clipboard sync between Soduto (Android) and remote devices. Your clipboard will automatically sync to your other devices when you copy something on desktop platforms, however you will have to manually tap on `Send Clipboard` in Soduto (Android) app every time you want to sync your Android's clipboard to remote devices ([see the workaround](#clipboard-sync-android-10-1)).

#### Storage Access (Android 11+)
On Android 11 and higher, you may not be able to add the root location of your Internal Storage or your Download folder to Soduto's (Android) `Filesystem expose` locations due to Google's privacy changes ([see the workaround](#storage-access-android-11-1)).

#### Sensitive Notification Content (Android 15+)
On Android 15 and higher, the system hides sensitive notification content (such as passwords, OTPs, and other sensitive information) from applications by default. This means remote devices will display "Sensitive notification content hidden" instead of the actual content. This restriction also affects automatic OTP copying in Soduto (macOS). Soduto (Android) will not receive sensitive notifications unless you explicitly grant the `RECEIVE_SENSITIVE_NOTIFICATIONS` permission ([see the workaround](#sensitive-notification-content-android-15-1)).

---

## Workarounds

#### Clipboard Sync (Android 10+)
If you have `Riru` or `Zygisk`, you can bypass the clipboard restriction on Android 10 or higher by using [Kr328's Clipboard Whitelist](https://github.com/Kr328/Riru-ClipboardWhitelist) module and then tick `KDE Connect`/`Zorin Connect` from the `Clipboard Whitelist` app. If you're on Android 13 and the module isn't working for you, try [Xposed Clipboard Whitelist](https://github.com/GamerGirlandCo/xposed-clipboard-whitelist) (remember to select `System Framework` for the module scope). You need to have `Xposed Framework` for the `Xposed Clipboard Whitelist` module to work.

#### Storage Access (Android 11+)
[NoStorageRestrict](https://github.com/Xposed-Modules-Repo/com.github.dan.nostoragerestrict) is an `Xposed Module` that removes the restriction when selecting folders(like Internal Storage, Android, Download, data, obb) through file manager on Android 11 and higher. There is a [Magisk module](https://github.com/DanGLES3/NoStorageRestrict) for this as well but I haven't tested the Magisk Module version yet, so use it at your own risk ⚠️.

#### Sensitive Notification Content (Android 15+)
The recommended approach is to grant Soduto (Android) the `RECEIVE_SENSITIVE_NOTIFICATIONS` permission using ADB (Android Debug Bridge). This will allow sensitive notifications to be delivered normally and enable automatic OTP copying.

- **Set up ADB**: Follow the [official Android ADB setup guide](https://developer.android.com/tools/adb).

- **Connect your device**: Connect your phone via USB or wireless ADB.

- **Grant the permission**: Run the following command
  ```bash
  adb shell cmd appops set --user 0 com.thenoton.soduto RECEIVE_SENSITIVE_NOTIFICATIONS allow
  ```

- **If permission monitoring error occurs**: If you see this error, follow these steps:
  ```
  java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES
  ```
   - Go to Developer Options and enable "Disable permission monitoring"
   - Reboot your phone
   - Run the ADB command again

- **Reboot**: Reboot your phone for the changes to take effect.

After completing these steps, sensitive notifications (including OTPs) will be delivered to remote devices, and automatic OTP copying will work as expected.

---

## Documentation & Resources

For comprehensive information about KDE Connect features, limitations, configuration, and troubleshooting across all platforms, visit the [official KDE Connect Wiki](https://userbase.kde.org/KDEConnect). While the wiki primarily focuses on Linux environments, a lot of the information will still be useful for macOS and Android.

---

## Get in touch
To ask a question, offer suggestions or share an idea, please use the [discussions tab](https://github.com/sannidhyaroy/soduto-android/discussions) of this repository.

If you spot any bugs or vulnerabilities, please [create an issue](https://github.com/sannidhyaroy/soduto-android/issues/). It's always a good idea to make sure there aren't any similar issues open, before creating a new one!

Please know that all translations for Soduto (Android) is from KDE Connect (Android) upstream that are handled by the [localization team](https://l10n.kde.org/). If you would like to submit a translation, that should be done by working with the proper team for that language.

---

## License

Soduto is licensed under the [GNU General Public License v3.0](https://github.com/sannidhyaroy/soduto/blob/main/COPYING).
