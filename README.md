# ZevClip macOS Receiver

ZevClip is a native SwiftUI menu bar app that syncs clipboard text with
Android over the local network. Android can push text to the Mac with `POST
/clipboard`, and Android can pull the current Mac clipboard with `GET
/clipboard`. While the receiver is running, it advertises `ZevClip Mac
Receiver` as `_zevclip._tcp` using Bonjour so the Android app can find it
without manual IP entry.

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
stored in macOS Keychain. Use **Regenerate Pairing Token** if you need to
invalidate previously paired Android installs.

Launch at Login is managed with the native macOS `SMAppService` API and the
preference is persisted in app defaults. The pairing token remains persisted in
Keychain.

Both devices must be connected to the same Wi-Fi/LAN and normally need to be
on the same subnet for Bonjour discovery.

## Discover from Android

1. Start the Mac receiver and confirm it shows **Running** and
   **Advertising**.
2. Open ZevClip on Android.
3. Tap **Discover Mac**.
4. Copy the Mac pairing token into Android and tap **Save Pairing Token**.
5. Send a manual test message, use one of the Android-to-Mac clipboard sync
   actions, or tap **Pull Mac Clipboard** to copy the Mac clipboard onto
   Android.

## Sync flows

### Android to Mac

Android sends plain UTF-8 text with:

```http
POST /clipboard
X-ZevClip-Token: <pairing-token>
Content-Type: text/plain; charset=utf-8
```

The Mac validates the token and writes the request body to
`NSPasteboard.general`.

### Mac to Android

Android fetches the current Mac clipboard with:

```http
GET /clipboard
X-ZevClip-Token: <pairing-token>
```

If the Mac clipboard contains text, the receiver returns HTTP `200` with
`text/plain; charset=utf-8`. If the clipboard is empty or non-text, it returns
HTTP `204 No Content`. Missing or wrong tokens return HTTP `401`.

This flow is intentionally manual/focused in the Android app because Android
background clipboard writes and reads are restricted on modern OS builds.

## Test locally

Android to Mac:

```sh
curl --data-binary 'Hello from Android' \
  -H 'Content-Type: text/plain; charset=utf-8' \
  -H 'X-ZevClip-Token: paste-token-from-mac' \
  http://localhost:9876/clipboard
```

Mac to Android protocol check:

```sh
curl -H 'X-ZevClip-Token: paste-token-from-mac' \
  http://localhost:9876/clipboard
```

For another device on the same network, replace `localhost` with the Mac's
local IP address and include the `X-ZevClip-Token` header. HTTP `401` means the
header is missing or the token does not match the Mac. Manual IP entry remains
available in the Android app if Bonjour discovery is blocked by network
isolation. The MVP has no encryption, so only run it on a trusted local
network.
