# ZevLink Intent Creator

## What was added

The macOS menu-bar app now has an **Intents** submenu. It opens **ZevLink Intent Creator**, where a user can:

- Create, save, edit, duplicate, load, delete, and send Intent presets.
- Configure an exact action string, optional target package, data URI, MIME type, categories, and safe broadcast flags.
- Add any number of typed extras: String, Boolean, Integer, Long, Float, Double, and String array.
- Copy the action string for use in an automation app.
- See connection state and the distinction between sending, delivery, broadcast request, failure, invalid configuration, and timeout.

Presets are Codable configuration data stored in `UserDefaults` under `intentPresets.v1`. No pairing credentials are included.

## Existing architecture reused

- macOS: Swift/AppKit menu-bar application with SwiftUI settings and editor windows.
- Wi-Fi/hotspot: the existing authenticated Android receiver HTTP server and `AndroidClipboardSender` endpoint discovery/fallback logic.
- Bluetooth: the existing framed JSON BLE transport and pairing-token validation in `MacBluetoothService` / `BluetoothClientManager`.
- Android background lifecycle: the existing `AndroidClipboardReceiverService` foreground service and `AndroidClipboardHttpReceiver` socket server.

No second networking listener or feature-specific Android automation controller was added.

## Wire protocol

Wi-Fi uses an authenticated `POST /intent-broadcast` request to the existing Android receiver port. The pairing token remains in `X-ZevClip-Token`. Bluetooth uses the existing length-prefixed JSON frame with `path: "/intent-broadcast"`, the existing pairing token, and the request JSON in `body`.

The request body is:

```json
{
  "type": "intent_broadcast",
  "version": 1,
  "requestId": "unique-request-id",
  "action": "com.zevlink.CUSTOM_COMMAND",
  "targetPackage": null,
  "dataUri": null,
  "mimeType": null,
  "categories": [],
  "flags": null,
  "extras": {
    "command": { "type": "string", "value": "set_brightness" },
    "value": { "type": "int", "value": 150 },
    "enabled": { "type": "boolean", "value": true }
  }
}
```

The Android response is JSON:

```json
{
  "type": "intent_broadcast_ack",
  "version": 1,
  "requestId": "unique-request-id",
  "status": "broadcast_requested",
  "success": true,
  "message": "Android invoked sendBroadcast() for com.zevlink.CUSTOM_COMMAND."
}
```

For Bluetooth, this acknowledgement is sent back through the existing BLE frame as `path: "/intent-broadcast-ack"`. The acknowledgement confirms that Android validated the request and invoked `sendBroadcast()`. It does **not** confirm that an external macro ran.

## Android behavior and safety

`IntentBroadcastHandler` validates request size, action, package, URI/MIME fields, categories, flags, extra count, extra keys, and each declared value type. It constructs a normal Android `Intent` and calls `Context.sendBroadcast(intent)`.

The handler is generic. It contains no brightness, hotspot, Bluetooth, volume, or app-specific behavior. An optional target package calls `Intent.setPackage()`; otherwise the broadcast is implicit. URI permissions, shell commands, root, Shizuku, and elevated privileges are not added.

The supported safe flags are `Intent.FLAG_INCLUDE_STOPPED_PACKAGES` (`0x20`) and `Intent.FLAG_RECEIVER_FOREGROUND` (`0x10000000`). Leave flags empty unless the receiving automation requires one.

Android's `sendBroadcast()` is asynchronous and has no receiver execution result. Android 8.0/API 26 and later restrict many manifest-declared implicit broadcast receivers, so MacroDroid/Tasker must provide a compatible registered receiver/event. A custom action is not guaranteed to reach arbitrary apps.

## MacroDroid setup

1. Create or edit a macro.
2. Add trigger **Device Events → Intent Received**.
3. Set **Action** to the exact action from Intent Creator, for example `com.zevlink.SET_BRIGHTNESS`.
4. Add optional extra matching rules if needed. MacroDroid can match extra parameter values and store them in variables.
5. Enable the macro.
6. In ZevLink, send the matching preset.

Use an empty target package for the normal MacroDroid trigger. If you set a target package, Android restricts the broadcast to that package and Tasker/MacroDroid will only receive it if that package is the intended receiver.

## Tasker setup

1. Create a Profile.
2. Choose **Event → System → Intent Received**.
3. Set **Action** to the exact Intent Creator action.
4. Match the same category, scheme, or MIME type only when the ZevLink preset includes those fields.
5. Use the received extras in the task. Tasker exposes the action and received values through its Intent Received event variables; array extras are exposed as local arrays.
6. Leave Tasker's package/component restriction empty for a normal implicit broadcast.

Tasker's documented limitations mean it receives broadcast receiver intents, not activity/service launches, and an intent targeted to a particular package/component may not be receivable by a generic Tasker event. Test the chosen receiver configuration on the target device.

## Example presets

### Action only

- Name: `Enable Hotspot`
- Action: `com.zevlink.HOTSPOT_ON`
- Extras: none

The receiving macro must decide what `com.zevlink.HOTSPOT_ON` means.

### String extra

- Action: `com.zevlink.CUSTOM_COMMAND`
- Extra key: `command`
- Type: `String`
- Value: `set_brightness`

### Integer and Boolean extras

- Action: `com.zevlink.SET_BRIGHTNESS`
- Extra `brightness`: `Integer` = `150`
- Extra `enabled`: `Boolean` = `true`

Android receives `brightness` as an `Int` and `enabled` as a `Boolean`, not as strings.

## Remaining limitations

- No device-backed MacroDroid or Tasker execution test is available in this repository environment; compatibility depends on the receiver app's installed version, trigger configuration, and Android device policy.
- ZevLink reports request delivery and Android's `sendBroadcast()` invocation only. It never claims that an external macro executed.
- The feature does not implement import/export or command history yet.
- Android application-specific restrictions, signature permissions, non-exported receivers, and system-protected actions remain enforced by Android and the receiving app.

## References

- Android Developers: Broadcasts overview and `Context.sendBroadcast()` API reference.
- MacroDroid Wiki: `Trigger: Intent Received`.
- Tasker Userguide: `Intents` and `Intent Received`.
