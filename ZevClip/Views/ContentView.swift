import SwiftUI

struct ContentView: View {
    @ObservedObject var receiver: ClipboardReceiver
    @ObservedObject var macClipboardWatcher: MacClipboardWatcher
    @ObservedObject var androidClipboardSender: AndroidClipboardSender
    @ObservedObject var appSettings: AppSettings

    private var isSyncRunning: Bool {
        receiver.status == .running && macClipboardWatcher.isRunning
    }

    private var syncColor: Color {
        if isSyncRunning {
            return .green
        }

        if receiver.status == .starting || androidClipboardSender.isSending || androidClipboardSender.isDiscovering {
            return .orange
        }

        if case .failed = receiver.status {
            return .red
        }

        return .secondary
    }

    private var syncTitle: String {
        isSyncRunning ? "Clipboard Sync On" : "Clipboard Sync Off"
    }

    private var androidStatusText: String {
        guard let endpoint = androidClipboardSender.resolvedEndpoint else {
            return androidClipboardSender.isDiscovering ? "Searching" : "Not connected"
        }

        if let battery = endpoint.batteryPercentage {
            return "\(battery)%"
        }

        return "Connected"
    }

    private var lastActivityText: String {
        let dates = [receiver.lastReceivedAt, androidClipboardSender.lastSentAt].compactMap { $0 }
        guard let latest = dates.max() else { return "None yet" }
        return latest.formatted(date: .omitted, time: .shortened)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center, spacing: 12) {
                Circle()
                    .fill(syncColor)
                    .frame(width: 11, height: 11)

                VStack(alignment: .leading, spacing: 2) {
                    Text("ZevLink")
                        .font(.title.bold())
                    Text(syncTitle)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button(isSyncRunning ? "Stop Sync" : "Start Sync") {
                    if isSyncRunning {
                        stopClipboardSync()
                    } else {
                        startClipboardSync()
                    }
                }
                .keyboardShortcut("r", modifiers: [.command])
                .controlSize(.large)
            }

            Divider()

            VStack(alignment: .leading, spacing: 12) {
                Text("Connection")
                    .font(.headline)

                statusRow("Mac", macStatusText, color: macStatusColor)
                statusRow("Android", androidStatusText, color: androidStatusColor)
                statusRow("Last activity", lastActivityText, color: .secondary)

                HStack {
                    Button("Reconnect Android") {
                        androidClipboardSender.rediscoverAndroidReceiver()
                    }
                    .disabled(androidClipboardSender.isDiscovering)

                    if androidClipboardSender.isDiscovering {
                        ProgressView()
                            .scaleEffect(0.65)
                    }
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 12) {
                Text("Pair Android")
                    .font(.headline)

                Text("Scan this once in the Android app.")
                    .foregroundStyle(.secondary)

                HStack(alignment: .top, spacing: 24) {
                    PairingQRCodeView(
                        token: receiver.pairingToken,
                        deviceId: receiver.deviceId,
                        showsDetails: false
                    )
                    .frame(width: 244, height: 244)

                    VStack(alignment: .leading, spacing: 16) {
                        Text(receiver.pairingToken.isEmpty ? "Token unavailable" : receiver.pairingToken)
                            .font(.system(size: 14, design: .monospaced))
                            .textSelection(.enabled)
                            .lineLimit(nil)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                            .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))

                        Button("Regenerate Token") {
                            receiver.regeneratePairingToken()
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(1)
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: 12) {
                Text("Preferences")
                    .font(.headline)

                Toggle(
                    "Show in menu bar",
                    isOn: Binding(
                        get: { appSettings.showMenuBarIcon },
                        set: { appSettings.setShowMenuBarIcon($0) }
                    )
                )

                Toggle(
                    "Launch at login",
                    isOn: Binding(
                        get: { appSettings.launchAtLoginEnabled },
                        set: { appSettings.setLaunchAtLoginEnabled($0) }
                    )
                )

                Text(appSettings.launchAtLoginStatus)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(minWidth: 500, minHeight: 560)
    }

    private var macStatusText: String {
        switch receiver.status {
        case .running:
            return "Ready"
        case .starting:
            return "Starting"
        case .stopped:
            return "Stopped"
        case .failed:
            return "Needs attention"
        }
    }

    private var macStatusColor: Color {
        switch receiver.status {
        case .running:
            return .green
        case .starting:
            return .orange
        case .failed:
            return .red
        case .stopped:
            return .secondary
        }
    }

    private var androidStatusColor: Color {
        if androidClipboardSender.resolvedEndpoint != nil {
            return .green
        }

        return androidClipboardSender.isDiscovering ? .orange : .secondary
    }

    @ViewBuilder
    private func statusRow(_ title: String, _ value: String, color: Color) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .foregroundStyle(color)
                .textSelection(.enabled)
        }
    }

    private func startClipboardSync() {
        receiver.startServer()
        macClipboardWatcher.start()
        androidClipboardSender.rediscoverAndroidReceiver()
    }

    private func stopClipboardSync() {
        receiver.stopServer()
        macClipboardWatcher.stop()
    }
}
