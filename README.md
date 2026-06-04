# ZevClip macOS Receiver

ZevClip is a native SwiftUI macOS app that accepts clipboard text over the
local network and writes it to `NSPasteboard.general`. While the receiver is
running, it advertises `ZevClip Mac Receiver` as `_zevclip._tcp` using Bonjour
so the Android app can find it without manual IP entry.

## Run

Open `ZevClip.xcodeproj` in Xcode and run the `ZevClip` scheme, or use:

```sh
./script/build_and_run.sh
```

The receiver starts automatically on TCP port `9876`. It can also be started
and stopped from the app window. The **Local Discovery** section shows whether
Bonjour is advertising, along with the service name, type, and port.

Both devices must be connected to the same Wi-Fi/LAN and normally need to be
on the same subnet for Bonjour discovery.

## Discover from Android

1. Start the Mac receiver and confirm it shows **Running** and
   **Advertising**.
2. Open ZevClip on Android.
3. Tap **Discover Mac**.
4. Send a manual test message or use one of the clipboard sync actions.

## Test locally

```sh
curl --data-binary 'Hello from Android' \
  -H 'Content-Type: text/plain; charset=utf-8' \
  http://localhost:9876/clipboard
```

For another device on the same network, replace `localhost` with the Mac's
local IP address. Manual IP entry remains available in the Android app if
Bonjour discovery is blocked by network isolation. The MVP has no
authentication or encryption, so only run it on a trusted local network.
