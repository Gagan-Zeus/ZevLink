# ZevClip Android Sender

This native Kotlin Android app sends UTF-8 text to the ZevClip macOS receiver
over the local network.

It includes:

- Manual Send as a reliable fallback.
- Best-effort, event-driven automatic sending through an Accessibility Service.
- No polling loop, foreground service, wakelock, cloud service, discovery,
  pairing, or encryption.

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

### 2. Find the Mac IP address

Connect the Mac and Android phone to the same Wi-Fi network.

On the Mac, open **System Settings > Wi-Fi > Details** for the connected
network and find the IP address. It will usually look like `192.168.1.10`.

You can also run:

```sh
ipconfig getifaddr en0
```

### 3. Start the Mac receiver

From the ZevClip repository root:

```sh
./script/build_and_run.sh
```

Confirm the Mac app shows **Running** on port `9876`. If macOS asks whether to
allow incoming network connections, allow them.

### 4. Configure ZevClip on Android

1. Open ZevClip on the Android phone.
2. Enter the Mac's IPv4 address.
3. Keep the port set to `9876`.
4. Tap **Open Accessibility Settings**.
5. Find ZevClip and enable its Accessibility Service.

The ZevClip app should then show **Accessibility status: Enabled**.
Android may disable the service after reinstalling or updating the APK; if so,
enable it again.

### 5. Test automatic sending

1. Open another Android app.
2. Select text and tap **Copy**.
3. Return to ZevClip to inspect the last auto-send status if needed.

ZevClip watches accessibility events for likely copy actions, waits briefly,
then reads and sends the current clipboard text.

### 6. Verify the Mac clipboard

Paste into any Mac text field, or run:

```sh
pbpaste
```

The output should match the text sent from Android.

## Manual Send fallback

Enter text in ZevClip and tap **Send to Mac**. The status message should show
`Sent successfully (HTTP 200).`

Manual Send remains available because Accessibility Service detection is
best-effort.

## Accessibility limitations

- Android may not expose every copy action as an accessibility event.
- Some apps, custom context menus, browsers, or password/security-sensitive
  screens may not trigger automatic sending.
- Android may deny background clipboard reads in some situations or OS builds.
- On the tested iQOO 12 Android 16 build, Chrome emits a useful `copied`
  accessibility event but may still hide the clipboard text from the service.
- Repeated copies of the same text are suppressed until different text is
  successfully auto-sent.
- No clipboard polling is used. If no useful event is emitted, use Manual Send.

## MVP security note

The app allows cleartext HTTP traffic for local LAN testing. There is no
authentication or encryption, so use it only on a trusted local network.
