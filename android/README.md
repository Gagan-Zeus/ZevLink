# ZevClip Android Sender

This native Kotlin Android app sends UTF-8 text to the ZevClip macOS receiver
over the local network.

It includes:

- Manual Send as a reliable fallback.
- Best-effort, event-driven automatic sending through an Accessibility Service.
- User-invoked clipboard sending through a Quick Settings **Sync Clipboard**
  tile.
- Bonjour/mDNS discovery of the Mac receiver through Android NSD.
- No polling loop, foreground service, wakelock, cloud service, pairing, or
  encryption.

The app uses Android platform APIs and `HttpURLConnection`. It has no runtime
third-party dependencies.

The MVP intentionally targets Android 16 / API 36. When ZevClip later targets
Android 17 / API 37, it must add the new local-network runtime permission:
<https://developer.android.com/privacy-and-security/local-network-permission>

## Build and install

Open the `android` folder in Android Studio, let Gradle sync, and run the `app`
configuration on the Android phone.

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

With the iQOO 12 connected over ADB:

```sh
cd android
./gradlew installDebug
```

### 2. Start the Mac receiver

Connect the Mac and Android phone to the same Wi-Fi network.

From the ZevClip repository root:

```sh
./script/build_and_run.sh
```

Confirm the Mac app shows **Running** and **Advertising** on port `9876`. If
macOS asks whether to allow incoming network connections, allow them.

Both devices must be on the same Wi-Fi/LAN and normally need to be on the same
subnet. Guest Wi-Fi client isolation can block both discovery and sending.

### 3. Discover the Mac from Android

1. Open ZevClip on the Android phone.
2. Tap **Discover Mac**.
3. Confirm the discovery status shows `ZevClip Mac Receiver` and the resolved
   address and port.
4. Enter test text under **Manual send** and tap **Send to Mac**.
5. Paste on the Mac to verify the discovered endpoint works.

If multiple ZevClip receivers are found, the app reports the count and prefers
the receiver named `ZevClip Mac Receiver`.

### 4. Enable Accessibility auto-send

1. Tap **Open Accessibility Settings**.
2. Find ZevClip and enable its Accessibility Service.

The ZevClip app should then show **Accessibility status: Enabled**.
Android may disable the service after reinstalling or updating the APK; if so,
enable it again.

### 5. Add the Sync Clipboard Quick Settings tile

1. In ZevClip, tap **Add Sync Clipboard Tile**.
2. Approve the Android prompt if it appears.
3. If no prompt appears, swipe down twice, tap edit/pencil, and add
   **Sync Clipboard** manually.

The tile is the recommended reliable fallback when Android emits a copy event
but hides clipboard content from the background Accessibility Service.

### 6. Test automatic sending

1. Open another Android app.
2. Select text and tap **Copy**.
3. Return to ZevClip to inspect the last auto-send status if needed.

ZevClip watches accessibility events for likely copy actions, waits briefly,
then reads and sends the current clipboard text.

### 7. Use the Quick Settings fallback

1. Copy text in any Android app.
2. Swipe down to open Quick Settings.
3. Tap **Sync Clipboard**.
4. Paste on the Mac.

The tile briefly brings a ZevClip **Syncing clipboard…** screen to the
foreground so Android permits the explicit user-invoked clipboard read. It then
sends changed text to the saved Mac endpoint, immediately closes, and reports
`Sent`, `Empty`, `No Mac IP`, or `Failed`.

### 8. Verify the Mac clipboard

Paste into any Mac text field, or run:

```sh
pbpaste
```

The output should match the text sent from Android.

## Manual Send fallback

Enter text in ZevClip and tap **Send to Mac**. The status message should show
`Sent successfully (HTTP 200).`

Manual IP entry remains available if Bonjour discovery is blocked. To find the
Mac IP manually, open **System Settings > Wi-Fi > Details** on the Mac or run
`ipconfig getifaddr en0`.

Manual Send remains available because Accessibility Service detection and
Bonjour discovery are best-effort.

## Accessibility limitations

- Android may not expose every copy action as an accessibility event.
- Some apps, custom context menus, browsers, or password/security-sensitive
  screens may not trigger automatic sending.
- Android may deny background clipboard reads in some situations or OS builds.
- On the tested iQOO 12 Android 16 build, Chrome emits a useful `copied`
  accessibility event but may still hide the clipboard text from the service.
  Use the **Sync Clipboard** Quick Settings tile in this case. ZevClip uses a
  brief focused foreground activity after the tile tap because this build also
  hides clipboard content from a background `TileService`.
- Repeated copies of the same text are suppressed until different text is
  successfully auto-sent.
- No clipboard polling is used. If no useful event is emitted, use Manual Send.

## MVP security note

The app allows cleartext HTTP traffic for local LAN testing. There is no
authentication or encryption, so use it only on a trusted local network.
