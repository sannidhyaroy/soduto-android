Title: Protocol Extensions

# Protocol Extensions

This document describes extensions to the [KDE Connect protocol](protocol.md) introduced in **Soduto** (the macOS KDE Connect client) and its companion Android fork. These extensions are designed to be fully backwards-compatible — unrecognised fields are silently ignored by standard KDE Connect implementations.

All fields documented here are optional unless stated otherwise. Implementations that do not support an extension must not change their behaviour when encountering the new fields.

## Table of Contents

* [Device Identity Extensions](#device-identity-extensions)
    * [`kdeconnect.identity` additions](#kdeconnectidentity-additions)
* [Lock Plugin Extensions](#lock-plugin-extensions)
    * [`kdeconnect.lock` additions](#kdeconnectlock-additions)
* [MPRIS Plugin Extensions](#mpris-plugin-extensions)
    * [`kdeconnect.mpris` additions](#kdeconnectmpris-additions)
* [MousePad Plugin Extensions](#mousepad-plugin-extensions)
    * [`kdeconnect.mousepad.request` additions](#kdeconnectmousepadrequest-additions)


## Device Identity Extensions

### `kdeconnect.identity` additions

The following optional fields extend the post-TLS identity packet (the full-detail exchange described in the base protocol).

```js
{
    "id": 0,
    "type": "kdeconnect.identity",
    "body": {
        "deviceId": "740bd4b9b4184ee497d6caf1da8151be",
        "protocolVersion": 8,
        "deviceName": "Sannidhya's Phone",
        "deviceType": "phone",
        "incomingCapabilities": [],
        "outgoingCapabilities": [],

        "clientName": "Soduto",
        "clientVersion": "1.0.0",
        "platformName": "macOS",
        "platformVersion": "15.4.1"
    }
}
```

* `clientName`: **`String`**

    The name of the KDE Connect client application. Examples: `"KDE Connect"`, `"Soduto"`, `"GSConnect"`, `"KDE Connect iOS"`.

    Useful for display (e.g. "Connected via Soduto") and for working around known client-specific behaviour.

* `clientVersion`: **`String`**

    The version string of the client application. No specific format is required; implementations should treat this as an opaque display string. Examples: `"1.27.0"`, `"24.08.3"`.

* `platformName`: **`String`**

    The name of the operating system or platform. Examples: `"Android"`, `"macOS"`, `"Windows"`, `"Linux"`, `"iOS"`.

    More specific than `deviceType`, which only conveys form factor. A desktop `deviceType` could be running macOS, Windows, or Linux — `platformName` removes the ambiguity and lets the remote end tailor its UI or behaviour accordingly (e.g. which special-key mappings to expose, which gesture actions to bind).

* `platformVersion`: **`String`**

    The version of the operating system. Examples: `"15"` (Android 15), `"15.4.1"` (macOS), `"11"` (Windows 11), `"6.1"` (Linux kernel).


## Lock Plugin Extensions

### `kdeconnect.lock` additions

The base `kdeconnect.lock` status packet reports only `isLocked`. Two additional fields advertise what the sending device actually supports.

```js
{
    "id": 0,
    "type": "kdeconnect.lock",
    "body": {
        "isLocked": true,
        "canLock": true,
        "canUnlock": false
    }
}
```

* `canLock`: **`Boolean`**

    Whether the sending device can be locked remotely. On Android this requires the Device Admin permission (`DevicePolicyManager.lockNow()`); `canLock` should be `true` only when that permission is currently granted. When `canLock` is absent, the receiving end should assume the capability is unknown and fall back to showing both controls (existing behaviour).

* `canUnlock`: **`Boolean`**

    Whether the sending device can be unlocked remotely. Android does not expose a standard API for remote unlock (by design), so this will almost always be `false` on Android. On a desktop platform that supports programmatic unlock (e.g. clearing a screensaver lock) it may be `true`.

    When `canUnlock` is absent, the receiving end should treat the capability as unknown.

**Rationale:** Without these fields, a remote control UI has no way to know whether a "Lock" or "Unlock" button will have any effect. Sending a `setLocked` request to a device that lacks the necessary permission silently fails, which is confusing. `canLock`/`canUnlock` allow the UI to show only the controls that will actually work.


## MPRIS Plugin Extensions

### `kdeconnect.mpris` additions

The base protocol already includes `canPlay`, `canPause`, `canGoNext`, `canGoPrevious`, and `canSeek` as explicit boolean capability fields. The following fields extend that set to cover shuffle and loop state control.

```js
{
    "id": 0,
    "type": "kdeconnect.mpris",
    "body": {
        "player": "Spotify",
        "isPlaying": true,
        "shuffle": false,
        "canShuffle": true,
        "loopStatus": "None",
        "canLoop": true
    }
}
```

* `canShuffle`: **`Boolean`**

    Whether the player supports toggling shuffle. When `true`, the remote end may send `setShuffle` in a `kdeconnect.mpris.request` packet. When `false` or absent, the shuffle control should be hidden or disabled.

    **Why this is needed:** The base protocol infers shuffle support from whether a `shuffle` field ever appears in a status update. A player that supports shuffle but has not yet reported its shuffle state would appear to have no shuffle support until the first update arrives. `canShuffle` removes this ambiguity by letting the player declare support upfront.

* `canLoop`: **`Boolean`**

    Whether the player supports changing the loop/repeat status. When `true`, the remote end may send `setLoopStatus` in a `kdeconnect.mpris.request` packet. When `false` or absent, the loop control should be hidden or disabled.

    The same reasoning as `canShuffle` applies — the base protocol infers loop support from the presence of a `loopStatus` field, which may not arrive immediately.


## MousePad Plugin Extensions

### `kdeconnect.mousepad.request` additions

The base protocol supports cursor movement (`dx`/`dy`), scroll, clicks, and keyboard events. The following fields add multi-finger gesture support.

A gesture packet is identified by the presence of `gestureType`. All other existing fields (`dx`, `dy`, `singleclick`, etc.) remain unchanged and unaffected.

#### Swipe gesture

```js
{
    "id": 0,
    "type": "kdeconnect.mousepad.request",
    "body": {
        "gestureType": "swipe",
        "fingers": 3,
        "direction": "up"
    }
}
```

#### Pinch gesture

```js
{
    "id": 0,
    "type": "kdeconnect.mousepad.request",
    "body": {
        "gestureType": "pinch",
        "fingers": 2,
        "scale": 0.62
    }
}
```

#### Rotate gesture

```js
{
    "id": 0,
    "type": "kdeconnect.mousepad.request",
    "body": {
        "gestureType": "rotate",
        "fingers": 2,
        "angle": -45.0
    }
}
```

---

* `gestureType`: **`String`**

    **`enum`**: `'swipe'` | `'pinch'` | `'rotate'`

    Identifies the gesture class. Must be present for any of the gesture fields below to be meaningful. Unknown values should be silently ignored.

* `fingers`: **`Number`** (integer)

    **`range`**: `2–4`

    The number of fingers involved in the gesture. Required when `gestureType` is present.

    | `gestureType` | valid `fingers` values |
    |---|---|
    | `swipe` | `2`, `3`, `4` |
    | `pinch` | `2`, `4` |
    | `rotate` | `2` |

* `direction`: **`String`**

    **`enum`**: `'up'` | `'down'` | `'left'` | `'right'`

    The primary direction of a swipe gesture. Required when `gestureType` is `"swipe"`.

    Typical desktop bindings (host decides; these are conventions only):

    | fingers | direction | macOS default action |
    |---|---|---|
    | 2 | up / down | scroll (already handled by `scroll` field; use for semantic swipe) |
    | 3 | up | Mission Control |
    | 3 | down | App Exposé |
    | 3 | left / right | Switch Space (virtual desktop) |
    | 4 | up | Launchpad |
    | 4 | down | Show Desktop |
    | 4 | left / right | Switch full-screen apps |

* `scale`: **`Number`** (double)

    A multiplier representing the relative pinch magnitude at the end of the gesture. Values less than `1.0` indicate a pinch-in (fingers moving together); values greater than `1.0` indicate a pinch-out (fingers spreading apart). Required when `gestureType` is `"pinch"`.

    **`range`**: `> 0.0`, typically `0.1`–`5.0` in practice.

* `angle`: **`Number`** (double)

    The rotation angle in degrees at the end of the gesture. Positive values indicate clockwise rotation; negative values indicate counter-clockwise. Required when `gestureType` is `"rotate"`.

    **`range`**: `-360.0`–`360.0`

---

## Appendix

### Compatibility

Extensions defined in this document use field names that do not conflict with any field names in the base protocol. Implementations that predate these extensions will receive unknown fields and must ignore them (standard JSON behaviour). No new packet types are introduced; all extensions are additive fields on existing packet types.

### Versioning

There is no separate version number for these extensions. Feature detection is done per-field:

* For identity extensions: check for the presence of `clientName`, `platformName`, etc.
* For lock extensions: check for the presence of `canLock` / `canUnlock` in received `kdeconnect.lock` packets.
* For MPRIS extensions: check for the presence of `canShuffle` / `canLoop` in received `kdeconnect.mpris` packets.
* For mousepad gesture extensions: check that the remote device's `outgoingCapabilities` includes `kdeconnect.mousepad.request` and that received packets may contain `gestureType`.
