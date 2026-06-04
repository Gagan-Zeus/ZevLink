import SwiftUI

struct ContentView: View {
    @ObservedObject var receiver: ClipboardReceiver

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

    var body: some View {
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

                ScrollView {
                    Text(receiver.lastReceivedText ?? "No clipboard text received yet.")
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .foregroundStyle(receiver.lastReceivedText == nil ? .secondary : .primary)
                        .textSelection(.enabled)
                        .padding(12)
                }
                .frame(maxHeight: .infinity)
                .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
            }
            .frame(maxHeight: .infinity)
        }
        .padding(24)
        .frame(minWidth: 500, minHeight: 500)
    }
}
