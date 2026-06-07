# ZevClip macOS Receiver

ZevClip is a native SwiftUI menu bar app that receives clipboard text from
Android over the local network with `POST /clipboard`. While the receiver is
running, it advertises `ZevClip Mac Receiver` as `_zevclip._tcp` using Bonjour
so the Android app can find it without manual IP entry.

The receiver also requires a shared pairing token. Android sends this token in
the `X-ZevClip-Token` header on every clipboard request; missing or wrong
tokens are rejected with HTTP `401`.

## Run# ZevClip

Local-only Android ↔ Mac clipboard sync, notification mirroring, and call controls.

ZevClip is for people who use an Android phone with a Mac and want the small
cross-device conveniences Apple gives its own ecosystem, without sending
clipboard or notification data through a cloud server.

It works on the same Wi-Fi/LAN, including a phone hotspot. Pair once with a QR
code, turn sync on, and copy normally.

<table>
  <tr>
    <td><img src="docs/assets/android1.jpg" width="250"></td>
    <td><img src="docs/assets/android2.jpg" width="250"></td>
    <td><img src="docs/assets/android3.jpg" width="250"></td>
    <td><img src="docs/assets/mac.png" width="250"></td>
  </tr>
</table>

[![ZevClip demo](https://img.youtube.com/vi/ZQB1X4zhDOA/maxresdefault.jpg)](https://www.youtube.com/watch?v=ZQB1X4zhDOA)

## What It Does

- Syncs clipboard text from Android to Mac.
- Syncs clipboard text from Mac to Android.
- Mirrors Android notifications as native macOS notifications.
- Lets you accept, reject, silence, and end Android calls from the Mac.
- Shows Android battery percentage in the macOS menu bar when connected.
- Shows Mac connection/battery status in the Android notification.
- Reconnects across Wi-Fi/hotspot changes.
- Restarts Android sync after phone reboot when sync was already enabled.
- Uses local network communication only. No cloud account, no relay server.

## Why

Most clipboard tools either need a cloud account, only work one way, or feel
unreliable on Android + Mac. ZevClip keeps the boring path simple: same network,
paired devices, local HTTP, and a visible status on both sides.

## Demo Checklist

Use these clips/screenshots near the top of the README once you record them:

- Android copy → appears on Mac.
- Mac copy → appears on Android.
- Android notification → appears as native macOS notification.
- Android phone call → accept/reject/silence/end from Mac.
- Phone hotspot / Wi-Fi reconnect.

## Downloads

Download the latest builds from GitHub Releases.

Expected release files:

- `ZevClip-macOS-<version>.zip`
- `ZevClip-Android-<version>-signed.apk`

## Requirements

### Mac

- macOS 14 or newer recommended.
- Local network permission for ZevClip.
- Clipboard access through the normal macOS pasteboard APIs.

### Android

- Android 8.0+.
- Google Play services for QR code scanning.
- Accessibility permission for automatic Android → Mac clipboard sync.
- Notification access for notification mirroring.
- Phone permissions for call controls.
- Optional: Auto-start / unrestricted battery permission on some phones so
  ZevClip can restart after reboot.

## Quick Setup

1. Install ZevClip on Mac.
2. Open ZevClip Settings from the menu bar.
3. Install ZevClip on Android.
4. Open Android Settings inside ZevClip.
5. Tap **Scan Mac QR** and scan the QR code shown on the Mac.
6. Turn on clipboard sync on both devices.
7. Enable the Android permissions shown in the app.

Both devices must be on the same local network. A phone hotspot works too:
connect the Mac to the phone hotspot, then pair or reconnect.

## How It Works

ZevClip uses two small local HTTP receivers:

- Mac receiver listens for Android clipboard, notification, call, and presence
  messages.
- Android receiver listens for Mac clipboard and notification/call actions.

Discovery uses Bonjour/mDNS on the local network:

- Mac advertises `_zevclip._tcp`.
- Android advertises `_zevclip-android._tcp`.

Pairing uses a shared token:

- The Mac generates a pairing token and stores it in Keychain.
- Android stores the token in private app preferences.
- Requests include `X-ZevClip-Token`.
- Requests with a missing or wrong token are rejected.

## Privacy And Security

ZevClip is local-first:

- No ZevClip cloud server.
- No account.
- No analytics.
- Clipboard text is sent directly between your paired Android phone and Mac.

Security limitations:

- Traffic is plain HTTP on your local network.
- The pairing token prevents random local devices from sending accepted
  requests, but it is not end-to-end encryption.
- Use ZevClip on trusted networks.
- For remote use, a private VPN such as Tailscale can be added later as an
  optional fallback, but local Wi-Fi/hotspot remains the default path.

## Current Limitations

- Android to mac clipboard may not work at all times dues to android's limitations.
- Text clipboard sync is supported. Image clipboard sync is planned for a later
  version.
- macOS production builds need an Apple Developer ID certificate and
  notarization for the cleanest public distribution.
- Some Android brands may block boot autostart unless Auto-start or unrestricted
  battery is enabled manually.
- Bonjour discovery may fail on networks with client isolation enabled. Manual
  IP entry remains available.

## Build From Source

- Repo contains the xcode build for mac app
- /android folder contains the kotlin app for android

### macOS

Open `ZevClip.xcodeproj` in Xcode and run the `ZevClip` scheme.

The helper script can run debug builds when Xcode is configured:

```sh
./script/build_and_run.sh
```

### Android

Build the Android debug APK:

```sh
cd android
./gradlew :app:assembleDebug
```

Build the Android release APK:

```sh
cd android
./gradlew :app:assembleRelease
```

Release APKs must be signed with a release keystore before sharing as a final
build.

## Local API

Android → Mac clipboard:

```http
POST /clipboard
X-ZevClip-Token: <pairing-token>
Content-Type: text/plain; charset=utf-8

Hello from Android
```

Mac → Android uses the Android receiver endpoint saved during pairing and
presence updates.

## Roadmap

- Two-way image clipboard sync.
- Optional Tailscale fallback mode for remote sync when both devices are on the
  same tailnet.
- Cleaner signed/notarized macOS release pipeline.
- Better onboarding for Android battery/autostart settings.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, testing, issue, and pull
request guidelines. Issues, testing notes, and pull requests are welcome.

Helpful feedback includes:

- Phone model and Android version.
- macOS version.
- Whether you are on Wi-Fi, LAN, hotspot, or VPN.
- What permission or reconnect state failed.
- Logs or screenshots if available.


Open `ZevClip.xcodeproj` in Xcode and run the `ZevClip` scheme, or use:

```sh
./script/build_and_run.sh
```

ZevClip runs from the macOS menu bar instead of the Dock. The receiver starts
automatically on TCP port `9876`, and Bonjour advertising starts with it. The
menu bar item shows receiver, Bonjour, port, and last-sync status.

Use the menu bar item to:

- Start or stop the receiver.
- Enable or disable **Launch at Login**.
- Open the settings window.
- Quit ZevClip.

The settings window shows receiver status, pairing, Bonjour discovery, Launch
at Login, and the last received clipboard text. The **Local Discovery** section
shows whether Bonjour is advertising, along with the service name, type, and
port.

The **Pairing** section shows the current token. It is generated randomly and
stored in macOS Keychain. It also shows a QR code that Android can scan to save
the Mac device identity, host, port, and pairing token automatically. The device
identity is generated once and persisted in app defaults; Bonjour advertises the
same `deviceId` in its TXT record so Android can find this Mac again if its IP
address changes. Use **Regenerate Pairing Token** if you need to invalidate
previously paired Android installs.

Launch at Login is managed with the native macOS `SMAppService` API and the
preference is persisted in app defaults. The pairing token remains persisted in
Keychain.

Both devices must be connected to the same Wi-Fi/LAN, or the Mac can be
connected to the phone's hotspot. They normally need to be on the same subnet
for Bonjour discovery.

## Discover from Android

1. Start the Mac receiver and confirm it shows **Running** and
   **Advertising**.
2. Open ZevClip on Android.
3. Tap **Scan Pairing QR** and scan the QR code in the Mac settings window.
4. Confirm Android reports the saved Mac host, port, token, and paired identity.
5. Send a manual test message or use one of the Android-to-Mac clipboard sync
   actions.

QR pairing is intended to be one-time. If your router later gives the Mac a new
IP address, Android first tries the saved endpoint, then uses Bonjour to resolve
`_zevclip._tcp` and prefers the receiver whose TXT `deviceId` matches the paired
Mac. Older QR payloads without `deviceId` still work, but Android cannot
rediscover by identity until you scan a newer QR code.

Manual setup still works: tap **Discover Mac** or type the Mac IP/host, then
copy the Mac pairing token into Android and tap **Save Pairing Token**.

The Android QR scanner uses Google Play services Code Scanner. It does not
require ZevClip to request camera permission; Google Play services provides the
scanner UI and returns only the QR text.

## Android to Mac sync

Android sends plain UTF-8 text with:

```http
POST /clipboard
X-ZevClip-Token: <pairing-token>
Content-Type: text/plain; charset=utf-8
```

The Mac validates the token and writes the request body to
`NSPasteboard.general`.

## Test locally

Android to Mac:

```sh
curl --data-binary 'Hello from Android' \
  -H 'Content-Type: text/plain; charset=utf-8' \
  -H 'X-ZevClip-Token: paste-token-from-mac' \
  http://localhost:9876/clipboard
```

For another device on the same network, replace `localhost` with the Mac's
local IP address and include the `X-ZevClip-Token` header. HTTP `401` means the
header is missing or the token does not match the Mac. Manual IP entry remains
available in the Android app if Bonjour discovery is blocked by network
isolation. The MVP has no encryption, so only run it on a trusted local
network.
