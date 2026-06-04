# ZevClip Android Sender

This native Kotlin Android app syncs UTF-8 clipboard text with the ZevClip
macOS receiver over the local network.

It includes:

- Manual Send as a reliable fallback.
- Best-effort, event-driven automatic sending through an Accessibility Service.
- User-invoked clipboard sending through a Quick Settings **Sync Clipboard**
  tile.
- Bonjour/mDNS discovery of the Mac receiver through Android NSD.
- QR pairing from the Mac settings window, including stable Mac identity.
- Simple pairing through a shared token sent as `X-ZevClip-Token`.
- No polling loop, foreground service, wakelock, cloud service, or encryption.

The app uses Android platform APIs and `HttpURLConnection`. It has no runtime
third-party networking dependencies. QR scanning uses Google Play services Code
Scanner, which provides the scanner UI without a ZevClip camera permission.

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

Confirm the Mac app shows **Running** and **Advertising** on port `9876`. Copy
the token shown in the Mac **Pairing** section. If macOS asks whether to allow
incoming network connections, allow them.

Both devices must be on the same Wi-Fi/LAN and normally need to be on the same
subnet. Guest Wi-Fi client isolation can block both discovery and sending.

### 3. Discover the Mac from Android

1. Open ZevClip on the Android phone.
2. Tap **Scan Pairing QR**.
3. Scan the QR code shown in the Mac ZevClip settings window.
4. Confirm the app reports that it saved `ZevClip Mac Receiver`, the Mac host,
   port, token, and paired identity.
5. Enter test text under **Manual send** and tap **Send to Mac**.
6. Paste on the Mac to verify the paired endpoint works.

QR pairing is one-time when the Mac QR includes `deviceId`. Android stores that
identity with the token. If the saved Mac IP becomes stale, Android first tries
the saved endpoint, then runs Bonjour discovery for `_zevclip._tcp`, selects the
receiver whose TXT `deviceId` matches the paired Mac, saves the new host/port,
and retries the send once. Older QR codes without `deviceId` remain supported,
but stale-IP rediscovery by identity requires scanning a newer QR code.

Manual discovery remains available:

1. Tap **Discover Mac**.
2. Confirm the discovery status shows `ZevClip Mac Receiver` and the resolved
   address and port.
3. Paste the Mac pairing token into **Pairing token** and tap
   **Save Pairing Token**.

If multiple ZevClip receivers are found, the app reports the count and prefers
the receiver named `ZevClip Mac Receiver`.

### 4. Enable Accessibility auto-send

1. Tap **Open Accessibility Settings**.
2. Find ZevClip and enable its Accessibility Service.

The ZevClip app should then show **Accessibility permission: Enabled**. The app
also shows whether the service is currently bound and the last lifecycle event
reported by Android. Tap **Recheck Permission** after returning from Settings if
the status looks stale.

ZevClip checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for its exact
component name. It does not and cannot silently enable Accessibility; Android
requires the user to grant that permission in Settings.

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

Accessibility auto-send does not depend on `MainActivity`. After pairing and
enabling Accessibility, closing ZevClip normally leaves the system-bound
Accessibility Service available to receive copy events. Preferences, duplicate
suppression, resilient sending, status persistence, and logging run through
app-level classes.

For Android/OEM builds that hide clipboard contents from a background
Accessibility Service, ZevClip first uses selected text exposed by
Accessibility. If selected text is also unavailable, ZevClip briefly opens a
transparent focused clipboard reader, sends the text, and immediately returns
to the app where the copy occurred. This remains event-driven and does not use
polling, a foreground service, or a wakelock.

Use `adb logcat -s ZevClipAccessibility` to verify closed-UI behavior. Copy-event
and send-result logs include `uiVisible=false` when no ZevClip activity is
resumed. If Android/OEM policy explicitly denies clipboard access, ZevClip logs
`Android denied clipboard access; uiVisible=false`. If Android silently hides
clipboard content and blocks the focused fallback, ZevClip records the failure.
Use the Quick Settings tile as the reliable manual fallback.

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
`Sent successfully (HTTP 200).` If the saved Mac IP has changed and Android has
a paired `deviceId` from QR pairing, ZevClip will attempt Bonjour rediscovery
and retry the send once.

Manual IP entry remains available if Bonjour discovery is blocked. To find the
Mac IP manually, open **System Settings > Wi-Fi > Details** on the Mac or run
`ipconfig getifaddr en0`.

If ZevClip reports HTTP `401`, the Android pairing token is missing or does not
match the token currently shown on the Mac. Paste the Mac token again and tap
**Save Pairing Token**. If you regenerate the Mac token, all Android installs
must be updated.

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
- Accessibility auto-send does not poll the Android clipboard. If no useful
  event is emitted, use Manual Send.

### iQOO / vivo persistence notes

If Accessibility is enabled but later disappears from ZevClip's status after
closing or reopening the app, check the iQOO/vivo power and security settings:

1. Do not use **Force stop** / force quit for ZevClip.
2. Enable the ZevClip Accessibility service.
3. Set ZevClip battery usage to **Unrestricted** / allow background activity.
4. Enable **Auto start** for ZevClip if available.
5. Lock ZevClip in recent apps if using the system cleaner.
6. Keep the Quick Settings tile as the reliable fallback.

The app's **Background Health** section shows Accessibility state, battery
optimization state, last service connection, and last automatic send. Its
buttons open the closest available Android or vivo/iQOO management screen.
Auto-start settings vary by firmware, so ZevClip shows manual instructions if
no supported screen is available.

On the tested iQOO 12, force-stopping `com.zevclip.sender` immediately removed
ZevClip from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which disables
the Accessibility permission at the OS level. Closing the app normally does not
revoke the permission.

These settings are OEM-specific. If `Settings.Secure` no longer lists
`com.zevclip.sender/com.zevclip.sender.ClipboardAccessibilityService`, Android
or the OEM layer has genuinely disabled the permission and ZevClip must be
re-enabled manually in Accessibility Settings.

The ZevClip app cannot silently re-enable Accessibility after a force stop or
OEM cleanup. Android intentionally requires the user to enable Accessibility
services from system Settings.

For ADB diagnostics:

```sh
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb logcat -s ZevClipAccessibility
```

## MVP security note

The app allows cleartext HTTP traffic for local LAN testing. There is no
encryption, so use it only on a trusted local network. Pairing prevents random
LAN devices from writing to the Mac clipboard, but it does not hide clipboard
contents from network observers.
