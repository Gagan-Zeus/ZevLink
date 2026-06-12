import AppKit
import SwiftUI

struct MenuBarContentView: View {
    @ObservedObject var receiver: ClipboardReceiver
    @ObservedObject var appSettings: AppSettings

    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Section {
            Label("Receiver: \(receiver.status.title)", systemImage: statusSystemImage)
            Text(receiver.detailMessage.truncatedForMenu())
            Text(receiver.isAdvertising ? "Bonjour: Advertising" : "Bonjour: Not advertising")
            Text("Port: \(ClipboardReceiver.port)")

            if let lastReceivedAt = receiver.lastReceivedAt {
                Text("Last sync: \(lastReceivedAt.formatted(date: .omitted, time: .shortened))")
            } else {
                Text("Last sync: None")
            }
        }

        if let lastReceivedText = receiver.lastReceivedText, !lastReceivedText.isEmpty {
            Section("Last Received") {
                Text(lastReceivedText.truncatedForMenu())
            }
        }

        Section {
            Button("Start Receiver") {
                receiver.startServer()
            }
            .disabled(!receiver.canStart)

            Button("Stop Receiver") {
                receiver.stopServer()
            }
            .disabled(!receiver.canStop)
        }

        Section {
            Toggle(
                "Launch at Login",
                isOn: Binding(
                    get: { appSettings.launchAtLoginEnabled },
                    set: { appSettings.setLaunchAtLoginEnabled($0) }
                )
            )

            Button("Open Settings…") {
                openWindow(id: ZevClipApp.settingsWindowID)
                NSApp.activate(ignoringOtherApps: true)
            }
        }

        Section {
            Button("Quit ZevLink") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
        }
    }

    private var statusSystemImage: String {
        switch receiver.status {
        case .running:
            return "checkmark.circle"
        case .starting:
            return "clock"
        case .stopped:
            return "pause.circle"
        case .failed:
            return "exclamationmark.triangle"
        }
    }
}

private extension String {
    func truncatedForMenu(limit: Int = 30) -> String {
        guard count > limit else { return self }

        let endIndex = index(startIndex, offsetBy: max(0, limit - 1))
        return String(self[..<endIndex]) + "…"
    }
}
