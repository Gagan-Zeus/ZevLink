# ZevLink Android

This native Kotlin Android app connects an Android phone to the ZevLink macOS app over the local network.

Current release version: **3.1.1**.

It includes:

- Two-way clipboard text sync, plus image clipboard sync from Mac to Android.
- AirPlay screen mirroring to the paired Mac.
- AirPlay audio streaming to the Mac.
- AirPlay audio broadcast to selected AirPlay receivers.
- Android notification mirroring to macOS.
- Android call controls from the Mac.
- Bonjour/mDNS discovery through Android NSD.
- QR pairing from the Mac settings window, including stable Mac identity.
- Shared-token pairing through the compatibility header `X-ZevClip-Token`.
- Quick Settings **Sync Clipboard** tile as a reliable manual fallback.

The app uses Android platform APIs, `HttpURLConnection`, and ZXing core for local QR pairing. Manual pairing remains available.

The app currently targets Android 16 / API 36. When ZevLink later targets Android 17 / API 37, it must add the new local-network runtime permission:
<https://developer.android.com/privacy-and-security/local-network-permission>

## Build and Install

Open the `android` folder in Android Studio, let Gradle sync, and run the `app` configuration on the Android phone.

From a terminal with an Android SDK configured and USB debugging enabled:

```sh
cd android
./gradlew installDebug
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Setup

### 1. Install the APK

With a device connected over ADB:

```sh
cd android
./gradlew installDebug
```

### 2. Start the Mac App

Connect the Mac and Android phone to the same Wi-Fi/LAN. A phone hotspot also works.

From the repository root:

```sh
./script/build_and_run.sh
```

Confirm the Mac app is running and showing the ZevLink settings/menu-bar UI. If macOS asks whether to allow incoming network connections, allow it.

### 3. Pair Android with Mac

1. Open ZevLink on the Android phone.
2. Open **Settings**.
3. Tap **Scan Mac QR**.
4. Scan the QR code shown in the Mac ZevLink settings window.
5. Confirm Android reports that it saved the Mac host, port, token, and paired identity.

QR pairing is intended to be one-time. If the saved Mac IP becomes stale, Android first tries the saved endpoint, then runs Bonjour discovery for `_zevclip._tcp`, selects the receiver whose TXT `deviceId` matches the paired Mac, saves the new host/port, and retries once.

Use **Reconnect Mac** in Android settings if the saved Mac address becomes stale after a network change.

### 4. Enable Clipboard Sync Permissions

For automatic Android to Mac clipboard sync:

1. Open ZevLink Settings on Android.
2. Tap **Accessibility**.
3. Find ZevLink and enable its Accessibility Service.

ZevLink cannot silently enable Accessibility. Android requires the user to grant that permission in system Settings.

Android may disable the service after reinstalling or updating the APK; if so, enable it again.

### 5. Enable Notification and Call Features

- Enable notification access for Android notification mirroring.
- Grant phone permissions for call controls.
- Grant Do Not Disturb access if you want the Mac to silence ringing/vibration.

### 6. Use AirPlay

The Android home screen has an **AirPlay** card:

- **AirPlay Screen to Mac** starts Android screen mirroring.
- **AirPlay Audio to Mac** streams Android audio.
- **AirPlay Audio Broadcast** streams audio to selected AirPlay receivers.

Screen mirroring flow:

1. Enable AirPlay Receiver on the Mac.
2. Tap **AirPlay Screen to Mac** on Android.
3. Enter the AirPlay one-time code shown on the Mac.
4. Approve Android screen capture.

AirPlay audio and audio broadcast start from Android without a separate Mac password prompt. If a receiver rejects an audio session, check the receiver's AirPlay settings on that device.

AirPlay capture requires Android 10+ and microphone/audio recording permission.

While AirPlay is active, ZevLink mutes local Android media playback so audio stays on the AirPlay target. Android volume keys are consumed during the session with a toast that says to stop AirPlay to listen locally, then the previous Android media volume is restored when AirPlay stops.

### 7. Add the Quick Settings Fallback

1. In ZevLink, tap **Add Sync Clipboard Tile** if available.
2. Approve the Android prompt if it appears.
3. If no prompt appears, swipe down twice, tap edit/pencil, and add **Sync Clipboard** manually.

The tile is the reliable fallback when Android emits a copy event but hides clipboard content from the background Accessibility Service.

## Clipboard Behavior

ZevLink watches accessibility events for likely copy actions, waits briefly, then reads and sends the current clipboard text.

Accessibility auto-send does not depend on `MainActivity`. After pairing and enabling Accessibility, closing ZevLink normally leaves the system-bound Accessibility Service available to receive copy events.

For Android/OEM builds that hide clipboard contents from a background Accessibility Service, ZevLink first uses selected text exposed by Accessibility. If selected text is also unavailable, ZevLink briefly opens a transparent focused clipboard reader, sends the text, and immediately returns to the app where the copy occurred.

Use the Quick Settings tile when automatic clipboard sync is blocked by Android/OEM policy.

## Diagnostics

Useful ADB commands:

```sh
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb logcat -s ZevClipAccessibility
```

Some internal package names, log tags, service types, and HTTP headers still use `zevclip` for compatibility with existing paired installs.

## Accessibility Limitations

- Android may not expose every copy action as an accessibility event.
- Some apps, custom context menus, browsers, or password/security-sensitive screens may not trigger automatic sending.
- Android may deny background clipboard reads in some situations or OS builds.
- Repeated copies of the same text are suppressed until different text is successfully auto-sent.
- Accessibility auto-send does not poll the Android clipboard. If no useful event is emitted, use the Quick Settings tile.

## iQOO / vivo Persistence Notes

If Accessibility is enabled but later disappears from ZevLink's status after closing or reopening the app:

1. Do not use **Force stop** / force quit for ZevLink.
2. Enable the ZevLink Accessibility service.
3. Set ZevLink battery usage to **Unrestricted** / allow background activity.
4. Enable **Auto start** for ZevLink if available.
5. Lock ZevLink in recent apps if using the system cleaner.
6. Keep the Quick Settings tile as the reliable fallback.

On the tested iQOO 12, force-stopping `com.zevclip.sender` immediately removed ZevLink from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which disables the Accessibility permission at the OS level. Closing the app normally does not revoke the permission.

## Security Note

The app allows cleartext HTTP traffic for local LAN communication. Pairing prevents random LAN devices from writing to the Mac clipboard, but it does not hide clipboard contents from network observers. Use ZevLink on trusted local networks.
