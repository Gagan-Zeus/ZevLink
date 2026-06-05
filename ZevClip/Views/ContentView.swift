import SwiftUI

struct ContentView: View {
    @ObservedObject var receiver: ClipboardReceiver
    @ObservedObject var macClipboardWatcher: MacClipboardWatcher
    @ObservedObject var androidClipboardSender: AndroidClipboardSender
    @ObservedObject var appSettings: AppSettings

    private var statusColor: Color {
        switch receiver.status {
        case .running:
            return .green
        case .starting:
            return .orange
        case .stopped:
            return .secondary
        case .failed:
            return .red
        }
    }

    private var senderStatusColor: Color {
        if androidClipboardSender.isSending || androidClipboardSender.isDiscovering {
            return .orange
        }
        if androidClipboardSender.lastSentAt != nil {
            return .green
        }
        return .secondary
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 10) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 10, height: 10)

                    Text(receiver.status.title)
                        .font(.headline)

                    Spacer()

                    Text("Port \(ClipboardReceiver.port)")
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(.secondary)
                }

                Text(receiver.detailMessage)
                    .foregroundStyle(.secondary)

                HStack {
                    Button("Start Server") {
                        receiver.startServer()
                    }
                    .keyboardShortcut("r", modifiers: [.command])
                    .disabled(!receiver.canStart)

                    Button("Stop Server") {
                        receiver.stopServer()
                    }
                    .disabled(!receiver.canStop)
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Application")
                        .font(.headline)

                    Toggle(
                        "Show in menu bar",
                        isOn: Binding(
                            get: { appSettings.showMenuBarIcon },
                            set: { appSettings.setShowMenuBarIcon($0) }
                        )
                    )

                    if !appSettings.showMenuBarIcon {
                        Text("ZevClip will keep running. Reopen the app to show Settings again.")
                            .foregroundStyle(.orange)
                            .font(.caption)
                    }

                    Toggle(
                        "Launch at Login",
                        isOn: Binding(
                            get: { appSettings.launchAtLoginEnabled },
                            set: { appSettings.setLaunchAtLoginEnabled($0) }
                        )
                    )

                    Text(appSettings.launchAtLoginStatus)
                        .foregroundStyle(.secondary)

                    Text("ZevClip starts the receiver and Bonjour advertising automatically when the app launches.")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Pairing")
                        .font(.headline)

                    Text("Enter this token in the Android app. Requests without this token are rejected.")
                        .foregroundStyle(.secondary)

                    PairingQRCodeView(token: receiver.pairingToken, deviceId: receiver.deviceId)

                    Text(receiver.pairingToken.isEmpty ? "Token unavailable" : receiver.pairingToken)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                        .lineLimit(2)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))

                    HStack {
                        Button("Regenerate Pairing Token") {
                            receiver.regeneratePairingToken()
                        }

                        Spacer()

                        Text(receiver.pairingStatus)
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Local Discovery")
                        .font(.headline)

                    LabeledContent("Status") {
                        Text(receiver.isAdvertising ? "Advertising" : "Not advertising")
                            .foregroundStyle(receiver.isAdvertising ? .green : .secondary)
                    }

                    LabeledContent("Service") {
                        Text(ClipboardReceiver.serviceName)
                    }

                    LabeledContent("Type") {
                        Text(ClipboardReceiver.serviceType)
                            .font(.system(.body, design: .monospaced))
                    }

                    LabeledContent("Port") {
                        Text("\(ClipboardReceiver.port)")
                            .font(.system(.body, design: .monospaced))
                    }

                    LabeledContent("Device ID") {
                        Text(receiver.deviceId)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Circle()
                            .fill(senderStatusColor)
                            .frame(width: 10, height: 10)

                        Text("Mac to Android")
                            .font(.headline)

                        Spacer()
                    }

                    LabeledContent("Clipboard watcher") {
                        Text(macClipboardWatcher.isRunning ? "Watching" : "Stopped")
                            .foregroundStyle(macClipboardWatcher.isRunning ? .green : .secondary)
                    }

                    Text(macClipboardWatcher.status)
                        .foregroundStyle(.secondary)

                    LabeledContent("Sender") {
                        Text(androidClipboardSender.status)
                            .foregroundStyle(senderStatusColor)
                    }

                    LabeledContent("Android receiver") {
                        if let endpoint = androidClipboardSender.resolvedEndpoint {
                            Text("\(endpoint.name) at \(endpoint.displayAddress)")
                                .textSelection(.enabled)
                        } else {
                            Text("Not discovered")
                                .foregroundStyle(.secondary)
                        }
                    }

                    if let deviceId = androidClipboardSender.resolvedEndpoint?.deviceId {
                        LabeledContent("Android device ID") {
                            Text(deviceId)
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    }

                    if let pairedDeviceId = androidClipboardSender.pairedDeviceId {
                        LabeledContent("Paired Android") {
                            Text(pairedDeviceId)
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    }

                    HStack {
                        Button("Start Watcher") {
                            macClipboardWatcher.start()
                        }
                        .disabled(macClipboardWatcher.isRunning)

                        Button("Stop Watcher") {
                            macClipboardWatcher.stop()
                        }
                        .disabled(!macClipboardWatcher.isRunning)

                        Button("Rediscover Android") {
                            androidClipboardSender.rediscoverAndroidReceiver()
                        }
                        .disabled(androidClipboardSender.isDiscovering)
                    }

                    if let date = macClipboardWatcher.lastObservedAt {
                        LabeledContent("Last Mac clipboard") {
                            Text(date.formatted(date: .abbreviated, time: .standard))
                                .foregroundStyle(.secondary)
                        }
                    }

                    if let date = androidClipboardSender.lastSentAt {
                        LabeledContent("Last sent") {
                            Text(date.formatted(date: .abbreviated, time: .standard))
                                .foregroundStyle(.secondary)
                        }
                    }

                    Text(androidClipboardSender.lastSentText ?? "No Mac clipboard text sent yet.")
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .foregroundStyle(androidClipboardSender.lastSentText == nil ? .secondary : .primary)
                        .textSelection(.enabled)
                        .padding(12)
                        .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Last Received")
                            .font(.headline)

                        Spacer()

                        if let date = receiver.lastReceivedAt {
                            Text(date.formatted(date: .abbreviated, time: .standard))
                                .foregroundStyle(.secondary)
                        }
                    }

                    Text(receiver.lastReceivedText ?? "No clipboard text received yet.")
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .foregroundStyle(receiver.lastReceivedText == nil ? .secondary : .primary)
                        .textSelection(.enabled)
                        .padding(12)
                        .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(24)
        }
        .frame(minWidth: 560, minHeight: 720)
    }
}
