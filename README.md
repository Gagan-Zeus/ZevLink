# ZevClip macOS Receiver

ZevClip is a native SwiftUI macOS app that accepts clipboard text over the
local network and writes it to `NSPasteboard.general`.

## Run

Open `ZevClip.xcodeproj` in Xcode and run the `ZevClip` scheme, or use:

```sh
./script/build_and_run.sh
```

The receiver starts automatically on TCP port `9876`. It can also be started
and stopped from the app window.

## Test locally

```sh
curl --data-binary 'Hello from Android' \
  -H 'Content-Type: text/plain; charset=utf-8' \
  http://localhost:9876/clipboard
```

For another device on the same network, replace `localhost` with the Mac's
local IP address. The MVP has no authentication or encryption, so only run it
on a trusted local network.
