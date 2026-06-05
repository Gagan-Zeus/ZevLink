# ZevClip macOS Receiver

ZevClip is a native SwiftUI menu bar app that receives clipboard text from
Android over the local network with `POST /clipboard`. While the receiver is
running, it advertises `ZevClip Mac Receiver` as `_zevclip._tcp` using Bonjour
so the Android app can find it without manual IP entry.

The receiver also requires a shared pairing token. Android sends this token in
the `X-ZevClip-Token` header on every clipboard request; missing or wrong
tokens are rejected with HTTP `401`.

## Run

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
