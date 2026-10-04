# UltraDisplay v0.1 — Wired Android-to-Android prototype

One Android Studio application installed on both a Galaxy S25 Ultra (source) and a Galaxy Tab A8 (USB host / receiver). **Experimental developer prototype, not a proven working product or tested APK.** No Wi-Fi, cloud, server, or root needed *if* their firmware permits the AOA USB roles.

## What's implemented

- Tablet issues Android Open Accessory USB control requests (`GET_PROTOCOL`, `SEND_STRING`, `START`), reconnects when the phone re-enumerates, claims AOA bulk endpoints.
- Phone accepts AOA accessory attachment and uses `UsbManager.openAccessory`.
- Phone requests fresh, explicit Android MediaProjection consent and runs a foreground capture service.
- H.264 hardware encode -> framed bidirectional USB bulk channel -> hardware MediaCodec decode onto the tablet's `SurfaceView`.
- `ULTRA MAX` button selects **target** 1920×1200 / 60 fps / 14 Mbps. No untested performance guarantees.
- Drop-oldest compressed frame queue, no player-side intentional buffering; USB roundtrip timer (RTT) and **local** decoder queue metrics (not misleadingly labeled glass-to-glass latency).
- Optional tap / basic drag passthrough via explicit Accessibility Service enabling on the **phone**; no root. Remote control is optional, never silently enabled.
- In-app reconnect and foreground-service stop actions.

## Build

1. Open this folder in Android Studio (JDK 17, Android SDK 35).
2. Allow it to install Gradle/Android Gradle Plugin 8.7.3 and Kotlin 2.0.21. This environment has no Android SDK/Gradle cache, so code has **not** been compiled or physically tested here.
3. Build > Build APK(s); install the same resulting debug APK on both Android devices.
4. Open on **Tab A8** and choose `TAB A8 RECEIVE USB`; open on **S25 Ultra** and choose `S25 ULTRA SEND USB`.
5. Connect a **data-capable USB-C to USB-C cable**. Approve USB permissions when prompted on both devices. The tablet must be the **USB host**. If Android chooses another USB role or Samsung firmware disables AOA, the AOA negotiation will fail. This cannot be repaired solely with UI changes.
6. Once both indicate connected, press `ULTRA MAX` on the **S25** and accept the system screen-sharing prompt. On phone, enable UltraDisplay's accessibility service manually only if you want remote touch control.
7. Tablet: three-finger touch reveals reconnection controls.

## Known technical limitations / next iteration

- **AOA interop is hardware/firmware dependent** between *two Android devices*. Manufacturer specifications do not guarantee that this particular pairing works. A real-world cable test is mandatory.
- Current encoder tries 1920×1200@60 /14Mbps; it reports a helpful error rather than automatically falling back to lower resolutions or frame rates if the codec refuses the profile. The ULTRA MAX button is a fixed target in v0.1, **not yet an adaptive controller**.
- Tab A8 is USB 2.0: uncompressed native-resolution 60fps is not feasible. H.264 bitrate is below theoretical USB 2.0 transfer rate; real-world speed is unverified.
- `MediaProjection` shows the S25 screen; this is **mirroring, not DeX or true extended-display mode**. DRM-protected videos and secure windows will be black by Android design.
- Accessibility gestures implement basic tap and drag, not multi-touch/gamepad. Mapping is normalized to the full tablet SurfaceView and full captured phone display: UI aspect-ratio mismatches and letterboxing can misalign taps. Do not rely on remote touch to operate critical phone functions.
- Decoder configuration expects separate `csd-0`/`csd-1` from the phone encoder; codec-specific device quirks are possible.
- Video stream itself has **no audio** in v0.1. Capture service shows ongoing screen sharing notification. Runs only with explicit user permission.
- USB RTT != true input-to-photon latency. For measured glass-to-glass latency, film both screens with a 240fps camera while changing the displayed image, or attach synchronized high-speed optical instrumentation.
- Holding an `Activity` as a connection owner means rotation/Activity recreation can interrupt USB. For production: move USB ownership to a bound foreground service, recover after detach, persist settings and add an encoder fallback.
- USB bulk API uses blocking IO. USB physical detach or a 1.5s IO timeout can end the stream. Add dedicated connection reestablishment to harden it.

## Protocol

Big endian frame header: magic `0x554C5452` / type int / monotonic-or-presentation timestamp long / width int / height int / payload length int, followed by bytes (<=2 MB). `CONFIG=1` payload: 4-byte SPS length, SPS bytes, 4-byte PPS length, PPS bytes; `VIDEO=2` H.264 access unit; `TOUCH=3` 4 floats normalized 0..1 + duration int milliseconds; `PING=4`, `PONG=5`. **Do not send unknown inputs**; this prototype assumes both endpoints are trusted, physically connected personal devices.

## Security / privacy

No internet permission. No analytics, uploads or storage of captured images. Android requires per-session MediaProjection approval and user-directed Accessibility grant. Only connect trusted devices.
