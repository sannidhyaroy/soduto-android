Title: Protocol Extensions

# Protocol Extensions

This document describes extensions to the [KDE Connect protocol](protocol.md) introduced in **Soduto** (the macOS KDE Connect client) and its companion Android fork. Extensions are of two kinds:

- **Additive field extensions** — new optional fields on existing KDE Connect packet types. Standard implementations silently ignore unknown fields, so these are fully backwards-compatible.
- **New packet types** — packet types that have no upstream equivalent. Devices that do not support them simply do not declare the corresponding capabilities, so the feature is invisible to them.

All fields documented here are optional unless marked **required**.


## Table of Contents

- [Device Identity Extensions](#device-identity-extensions)
  - [`kdeconnect.identity` additions](#kdeconnectidentity-additions)
- [Lock Plugin Extensions](#lock-plugin-extensions)
  - [`kdeconnect.lock` additions](#kdeconnectlock-additions)
- [MPRIS Plugin Extensions](#mpris-plugin-extensions)
  - [`kdeconnect.mpris` additions](#kdeconnectmpris-additions)
- [MousePad Plugin Extensions](#mousepad-plugin-extensions)
  - [`kdeconnect.mousepad.request` additions](#kdeconnectmousepadrequest-additions)
- [Webcam Plugin](#webcam-plugin)
  - [Overview](#overview)
  - [`kdeconnect.webcam.request_stream`](#kdeconnectwebcamrequest_stream)
  - [`kdeconnect.webcam.stream_status`](#kdeconnectwebcamstream_status)
  - [`kdeconnect.webcam.camera_control`](#kdeconnectwebcamcamera_control)
  - [UDP stream format](#udp-stream-format)
  - [Multi-device behaviour](#multi-device-behaviour)
  - [Desk View](#desk-view)
- [Appendix](#appendix)


---


## Device Identity Extensions

### `kdeconnect.identity` additions

The following optional fields extend the post-TLS identity packet.

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

    The name of the KDE Connect client application. Examples: `"KDE Connect"`, `"Soduto"`, `"GSConnect"`.

    Useful for display (e.g. "Connected via Soduto") and for working around known client-specific behaviour.

* `clientVersion`: **`String`**

    The version string of the client application. No specific format is required; implementations should treat this as an opaque display string. Examples: `"1.27.0"`, `"24.08.3"`.

* `platformName`: **`String`**

    The name of the operating system or platform. Examples: `"Android"`, `"macOS"`, `"Windows"`, `"Linux"`, `"iOS"`.

    More specific than `deviceType`, which only conveys form factor. A desktop `deviceType` could be running macOS, Windows, or Linux — `platformName` removes the ambiguity and lets the remote end tailor its UI or behaviour accordingly.

* `platformVersion`: **`String`**

    The version of the operating system. Examples: `"15"` (Android 15), `"15.4.1"` (macOS), `"11"` (Windows 11), `"6.1"` (Linux kernel).


---


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

    Whether the sending device can be locked remotely. On Android this requires the Device Admin permission (`DevicePolicyManager.lockNow()`); `canLock` should be `true` only when that permission is currently granted. When absent, the receiving end should assume the capability is unknown and fall back to showing both controls.

* `canUnlock`: **`Boolean`**

    Whether the sending device can be unlocked remotely. Android does not expose a standard API for remote unlock (by design), so this will almost always be `false` on Android. On a desktop platform that supports programmatic unlock it may be `true`.

    When absent, the receiving end should treat the capability as unknown.

**Rationale:** Without these fields a remote control UI has no way to know whether a "Lock" or "Unlock" button will have any effect. Sending a `setLocked` request to a device that lacks the necessary permission silently fails, which is confusing.


---


## MPRIS Plugin Extensions

### `kdeconnect.mpris` additions

The base protocol already includes `canPlay`, `canPause`, `canGoNext`, `canGoPrevious`, and `canSeek`. The following fields extend that set to cover shuffle and loop state control.

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

    **Why this is needed:** The base protocol infers shuffle support from whether a `shuffle` field ever appears in a status update. A player that supports shuffle but has not yet reported its shuffle state would appear to have no shuffle support until the first update arrives. `canShuffle` removes this ambiguity.

* `canLoop`: **`Boolean`**

    Whether the player supports changing the loop/repeat status. When `true`, the remote end may send `setLoopStatus` in a `kdeconnect.mpris.request` packet. When `false` or absent, the loop control should be hidden or disabled.


---


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

    Identifies the gesture class. Must be present for any gesture fields to be meaningful. Unknown values must be silently ignored.

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

    | fingers | direction | macOS default action |
    |---|---|---|
    | 2 | up / down | scroll |
    | 3 | up | Mission Control |
    | 3 | down | App Exposé |
    | 3 | left / right | Switch Space |
    | 4 | up | Launchpad |
    | 4 | down | Show Desktop |
    | 4 | left / right | Switch full-screen apps |

* `scale`: **`Number`** (double)

    A multiplier representing the relative pinch magnitude. Values less than `1.0` indicate pinch-in (fingers moving together); values greater than `1.0` indicate pinch-out (fingers spreading apart). Required when `gestureType` is `"pinch"`.

    **`range`**: `> 0.0`, typically `0.1`–`5.0` in practice.

* `angle`: **`Number`** (double)

    The rotation angle in degrees at the end of the gesture. Positive values indicate clockwise rotation; negative values indicate counter-clockwise. Required when `gestureType` is `"rotate"`.

    **`range`**: `-360.0`–`360.0`

---


## Webcam Plugin

### Overview

The Webcam plugin is a Soduto-exclusive feature with no upstream KDE Connect equivalent. It streams the Android device's camera and microphone to the connected macOS desktop as a live video and audio feed.

**Architecture:**

The KDE Connect TLS channel carries only lightweight control packets (start/stop, camera settings, status reports). All media is delivered out-of-band over a direct UDP socket to minimise latency and avoid congestion on the shared connection. Multiple logical streams — primary video, Desk View video, and audio — share the same UDP socket and are distinguished by a `stream_type` byte in each datagram header.

**Camera model:**

Modern Android phones expose cameras through the logical multi-camera API. Rather than enumerating individual physical sensors, the plugin works with two logical cameras (`"back"` and `"front"`) and controls optical selection by setting a zoom ratio on the logical back camera. The OS transparently routes to the correct physical sensor (ultrawide, main, or telephoto) and blends across transition points. This matches how the stock camera app works on all major Android OEMs.

**Capabilities:**

```
Android outgoing:  kdeconnect.webcam.stream_status
Android incoming:  kdeconnect.webcam.request_stream
                   kdeconnect.webcam.camera_control

macOS outgoing:    kdeconnect.webcam.request_stream
                   kdeconnect.webcam.camera_control
macOS incoming:    kdeconnect.webcam.stream_status
```


### `kdeconnect.webcam.request_stream`

Sent macOS → Android to start or stop a stream. This packet initiates the control handshake; the media socket is separate.

**Start stream:**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.request_stream",
    "body": {
        "addresses": ["192.168.1.100", "10.0.0.1"],
        "port": 8554,
        "width": 1280,
        "height": 720,
        "fps": 30,
        "bitrate": -1,
        "codec": "h265"
    }
}
```

**Stop stream:**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.request_stream",
    "body": {
        "stop": true
    }
}
```

* `addresses`: **`Array`** of **`String`**

    Required when starting a stream. List of IPv4 or IPv6 addresses to which Android should send UDP datagrams. Android tries each in sequence and uses the first it can resolve. The macOS side should include all active non-loopback interface addresses to maximise routing success across different network topologies.

* `port`: **`Number`**

    Required when starting a stream. UDP port the macOS side is listening on.

* `width`: **`Number`**

    Requested frame width in pixels. Default: `1280`.

* `height`: **`Number`**

    Requested frame height in pixels. Default: `720`.

* `fps`: **`Number`**

    Requested frame rate in frames per second. Default: `30`.

* `bitrate`: **`Number`**

    Requested video bitrate in bits per second. `-1` (default) lets Android choose an appropriate bitrate for the given resolution and frame rate.

* `codec`: **`String`**

    **`enum`**: `'h265'` | `'h264'`

    Preferred video codec. `'h265'` requests H.265/HEVC; `'h264'` requests H.264/AVC. When absent, Android tries H.265 first and falls back to H.264 if HEVC hardware encoding is unavailable. The codec actually chosen is reported in the response `kdeconnect.webcam.stream_status`.

* `stop`: **`Boolean`**

    When `true`, signals Android to stop any active stream. All other fields are ignored. Android responds with `kdeconnect.webcam.stream_status { "streaming": false }`.

**Design note — no initial camera field:** Camera selection is intentionally absent from `request_stream`. The start handshake negotiates only stream parameters (resolution, codec, network); Android uses its stored preference for the initial camera. Camera, zoom, flash, and Desk View are all adjusted post-stream via `kdeconnect.webcam.camera_control`. This keeps stream negotiation simple and decouples it from UI state.


### `kdeconnect.webcam.stream_status`

Sent Android → macOS to report the current streaming state. Android sends this packet after any of the following events:

- `request_stream` processed (stream started, or rejected/error)
- `camera_control` processed (any setting changed)
- Device physical orientation changed (rotation update)
- Stream stopped for any reason (including device disconnect cleanup)

**Stream active — single camera:**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.stream_status",
    "body": {
        "streaming": true,
        "codec": "h265",
        "rotation": 90,
        "cameras": ["back", "front"],
        "activeCamera": "back",
        "zoomRange": [0.6, 10.0],
        "opticalZooms": [0.6, 1.0, 3.0],
        "activeZoom": 1.0,
        "flashAvailable": true,
        "flashActive": false,
        "deskViewAvailable": true,
        "deskViewActive": false
    }
}
```

**Stream active — Desk View (concurrent cameras):**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.stream_status",
    "body": {
        "streaming": true,
        "codec": "h265",
        "rotation": 0,
        "cameras": ["back", "front"],
        "activeCamera": "front",
        "flashAvailable": true,
        "flashActive": false,
        "deskViewAvailable": true,
        "deskViewActive": true,
        "deskTilt": 42.5
    }
}
```

**Stream stopped normally:**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.stream_status",
    "body": {
        "streaming": false
    }
}
```

**Stream stopped due to error:**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.stream_status",
    "body": {
        "streaming": false,
        "error": "Camera permission not granted"
    }
}
```

**Request rejected — another device is already streaming:**

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.stream_status",
    "body": {
        "streaming": false,
        "error": "Webcam already in use by another device"
    }
}
```

* `streaming`: **`Boolean`**

    Required. `true` if a stream is active and UDP datagrams are being sent; `false` otherwise.

* `codec`: **`String`**

    The video codec in use: `'h265'` or `'h264'`. Present only when `streaming` is `true`. The macOS decoder must be configured for the reported codec.

* `rotation`: **`Number`**

    **`enum`**: `0` | `90` | `180` | `270`

    Clockwise rotation in degrees that the receiver must apply to the video frame to display it upright, based on the device's current physical orientation. Omitted when `streaming` is `false`.

* `cameras`: **`Array`** of **`String`**

    The logical cameras available on this device. Will be some subset of `["back", "front"]`. Present only when `streaming` is `true`.

    Note: this lists logical cameras only. Physical sensors (ultrawide, telephoto) are not listed separately — they are accessed via the zoom ratio on the back logical camera.

* `activeCamera`: **`String`**

    **`enum`**: `'back'` | `'front'`

    The logical camera currently in use for the primary video stream. Present only when `streaming` is `true`.

* `zoomRange`: **`Array`** of **`Number`**

    `[minZoom, maxZoom]` — the full continuous zoom range of the active logical back camera. `minZoom` is the widest possible zoom (e.g., `0.6` when an ultrawide physical sensor is available); `maxZoom` is the digital zoom ceiling (e.g., `10.0` or `30.0`). The macOS side may expose this range as a continuous slider. Present only when `streaming` is `true` and `activeCamera` is `'back'`.

* `opticalZooms`: **`Array`** of **`Number`**

    The zoom ratios at which the device physically transitions between lenses, plus `1.0` (always included). For example: `[0.6, 1.0, 3.0]`. These are the points most worth exposing as discrete buttons in the UI — selecting them is a lossless quality transition with no digital interpolation. Values outside this list are still valid zoom targets (continuous zoom) but involve digital scaling. Present only when `streaming` is `true` and `activeCamera` is `'back'`.

* `activeZoom`: **`Number`**

    The current zoom ratio. Present only when `streaming` is `true` and `activeCamera` is `'back'`.

* `flashAvailable`: **`Boolean`**

    Whether the active camera supports torch/flash mode. Present only when `streaming` is `true`.

* `flashActive`: **`Boolean`**

    Whether the torch is currently on. Present only when `streaming` is `true` and `flashAvailable` is `true`.

* `deskViewAvailable`: **`Boolean`**

    Whether the device supports Desk View (concurrent front + back camera streaming). Requires Android 11+ and a device that reports `{front, back}` as a valid concurrent camera pair. Present only when `streaming` is `true`.

* `deskViewActive`: **`Boolean`**

    Whether Desk View is currently active. When `true`, a second video stream (stream type 1) is being sent alongside the primary. Present only when `streaming` is `true`.

* `deskTilt`: **`Number`**

    The current tilt of the device's long axis, in degrees from vertical (0° = phone standing fully upright; 90° = phone lying completely flat). Determined from the accelerometer. Present only when `deskViewActive` is `true`. The macOS side uses this value to compute the perspective homography for Desk View correction. Updated in subsequent `stream_status` packets whenever tilt changes significantly (threshold: ±5°).

* `error`: **`String`**

    Human-readable error description. Present only when `streaming` is `false` and the stream stopped due to an error, or was rejected (e.g., another device is already streaming).


### `kdeconnect.webcam.camera_control`

Sent macOS → Android to adjust camera settings mid-stream. All fields are optional; Android applies whichever fields are present and ignores the rest. Android responds with an updated `kdeconnect.webcam.stream_status` reflecting the new state after all changes are applied.

```js
{
    "id": 0,
    "type": "kdeconnect.webcam.camera_control",
    "body": {
        "camera": "front",
        "zoom": 3.0,
        "flash": "torch",
        "deskView": true
    }
}
```

* `camera`: **`String`**

    **`enum`**: `'back'` | `'front'`

    Switch the active logical camera. When the camera changes, `activeZoom` resets to `1.0` unless `zoom` is also specified in the same packet. Ignored during Desk View (use `deskView: false` first to exit Desk View).

* `zoom`: **`Number`**

    Set the zoom ratio on the active back camera. Values are clamped to `zoomRange`. Ignored when `activeCamera` is `'front'`. May be sent independently (without `camera`) for a zoom-only change.

* `flash`: **`String`**

    **`enum`**: `'torch'` | `'off'`

    Toggle the torch. `'torch'` turns continuous torch light on; `'off'` turns it off. Ignored when `flashAvailable` is `false`. Flash controls the back camera's torch even during Desk View.

* `deskView`: **`Boolean`**

    Enable or disable Desk View mode.

    - `true`: Android opens front and back cameras concurrently (requires `deskViewAvailable: true`). The front camera drives the primary video stream (stream type 0); the back camera at its minimum zoom level drives the desk view stream (stream type 1). Any `camera` or `zoom` field in the same packet is ignored during Desk View activation.
    - `false`: Android releases the concurrent camera session and returns to single-camera mode. The primary stream continues from the camera that was active before Desk View was enabled.

    Ignored when `deskViewAvailable` is `false`.


### UDP stream format

Media is sent out-of-band over UDP (not through the KDE Connect TLS channel). The macOS side opens a UDP socket and provides its port in `request_stream`; Android sends all datagrams to the first resolvable address in `addresses` at that port.

Each UDP datagram carries a fixed **18-byte header** followed by up to **1400 bytes** of payload:

```
Offset  Size    Field
──────────────────────────────────────────────────────────────────────────
0       4       sequence_number  (uint32, little-endian)
                    Monotonically increasing counter, independent per stream
                    type. Used to detect lost datagrams within each stream.

4       4       pts_ms  (uint32, little-endian)
                    Presentation timestamp in milliseconds since the stream
                    started, for the logical frame this datagram belongs to.

8       4       frame_total_size  (uint32, little-endian)
                    Total byte size of the complete logical frame across all
                    of its fragments. Used to allocate a reassembly buffer.

12      4       fragment_byte_offset  (uint32, little-endian)
                    Byte offset of this datagram's payload within the
                    logical frame. The first fragment always has offset 0.

16      1       flags  (uint8)
                    bit 0  is_keyframe      1 = this frame is a keyframe (video only)
                    bit 1  is_last_fragment 1 = this is the final fragment of the frame
                    bits 2–7               reserved; must be 0 on send, ignored on receive

17      1       stream_type  (uint8)
                    0 = primary video   H.264/H.265 Annex B; face camera or solo camera
                    1 = desk video      H.264/H.265 Annex B; Desk View back camera
                    2 = audio           AAC-LC with ADTS header, 44 100 Hz mono
                    3–255               reserved; receivers must ignore unknown values

18+     ≤1400   payload
                    Encoded media data for this fragment.
```

**Frame reassembly**

Frames larger than 1400 bytes are split across consecutive datagrams. The receiver assembles a frame by collecting all fragments with the same `stream_type` and `pts_ms` until `is_last_fragment` is set and total received bytes equal `frame_total_size`. Each stream type maintains its own reassembly state independently.

Stale buffers — caused by a lost `is_last_fragment` datagram — should be evicted after a reasonable timeout (suggested: 500 ms for video, 200 ms for audio). On severe packet loss (reassembly buffer count exceeds ~30 frames for any stream), purge all buffers and wait for the next keyframe to re-sync.

**Video format**

H.264/AVC or H.265/HEVC in Annex-B byte-stream format (start codes `00 00 00 01`). Parameter sets (SPS + PPS for H.264; VPS + SPS + PPS for H.265) are prepended to the first keyframe of each stream and re-sent on every subsequent keyframe. This allows the decoder to start or resume at any keyframe without requiring prior out-of-band configuration.

**Audio format**

AAC-LC at 44 100 Hz, mono (1 channel), 128 kbps, 1024 samples per frame. Each datagram payload begins with a standard 7-byte ADTS header containing the profile, sample rate index, and channel count, followed by the raw AAC frame data. The ADTS header is self-describing; no additional out-of-band audio configuration is required.


### Multi-device behaviour

Android supports exactly one active streaming session at a time — the phone has one camera system. If macOS device B sends `request_stream` while device A is already streaming, Android immediately sends device B a `stream_status { "streaming": false, "error": "Webcam already in use by another device" }` without altering device A's stream.

To take over from another device, device A must first send `request_stream { "stop": true }`. After Android confirms with `stream_status { "streaming": false }`, device B may request a new stream.


### Desk View

Desk View is a concurrent dual-camera mode. It simultaneously captures:

- **Primary stream (stream type 0):** Front camera — the user's face, for use as the main video call feed.
- **Desk stream (stream type 1):** Back wide-angle camera (minimum zoom) — a view of the desk below the phone, perspective-corrected to a top-down orientation on the macOS side.

**Physical setup:** The phone is placed near the edge of a desk, propped up at roughly 30–70° from vertical (leaning toward the user), facing forward. The front camera captures the user's face; the back camera, pointing downward and forward, captures the desk surface.

**Perspective correction:** The raw desk stream is captured at an angle determined by how the phone is propped. The macOS side applies a perspective homography to transform this angled view into a simulated overhead view, using the `deskTilt` angle from `stream_status` to parameterise the transform. The correction is computed as:

```
tilt_rad = deskTilt × π / 180
scale_y  = 1 / cos(tilt_rad)        // vertical compression factor
```

A `CIFilter.perspectiveTransform` (or equivalent) maps the four corners of the desk plane in the image to a rectangle, effectively un-projecting the perspective introduced by the phone's tilt.

**Activation:** macOS sends `camera_control { "deskView": true }`. Android checks whether the front and back cameras can run concurrently (`CameraManager.getConcurrentCameraIds()`, Android 11+). If they cannot, it sets `deskViewAvailable: false` in `stream_status` and takes no action. If they can, it opens both cameras and begins sending on stream types 0 and 1.

During Desk View, `zoom`, `zoomRange`, `opticalZooms`, and `activeZoom` are omitted from `stream_status` (zoom is not user-adjustable while both cameras are in use). `flashAvailable` and `flashActive` continue to reflect the back camera's torch state.


---


## Appendix

### New packet types

The following packet types are introduced by Soduto and do not exist in the base KDE Connect protocol. All other extensions in this document are additive fields on existing packet types.

| Packet type | Direction | Plugin |
|---|---|---|
| `kdeconnect.webcam.request_stream` | macOS → Android | Webcam |
| `kdeconnect.webcam.stream_status` | Android → macOS | Webcam |
| `kdeconnect.webcam.camera_control` | macOS → Android | Webcam |

### Compatibility

Unknown fields in any packet must be silently ignored. Devices that predate these extensions will receive packets with extra fields and must not change their behaviour. Devices that lack Webcam plugin support will simply not declare the webcam packet types in their capabilities, and the feature will be invisible to them.

### Feature detection

There is no separate extension version number. All features are detected per-field or per-packet-type at runtime:

| Feature | How to detect |
|---|---|
| Identity extensions | Presence of `clientName`, `platformName`, etc. in received `kdeconnect.identity` |
| Lock extensions | Presence of `canLock` / `canUnlock` in received `kdeconnect.lock` |
| MPRIS extensions | Presence of `canShuffle` / `canLoop` in received `kdeconnect.mpris` |
| MousePad gesture extensions | Presence of `gestureType` in received `kdeconnect.mousepad.request` |
| Webcam plugin | `kdeconnect.webcam.stream_status` in remote device `outgoingCapabilities` |
| Continuous zoom / optical zoom levels | Presence of `opticalZooms` in received `stream_status` |
| Desk View | `deskViewAvailable: true` in received `stream_status` |
| Flash control | `flashAvailable: true` in received `stream_status` |
