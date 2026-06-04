import AppKit
import Foundation

@MainActor
final class ClipboardReceiver: ObservableObject {
    enum ServerStatus: Equatable {
        case stopped
        case starting
        case running
        case failed(String)

        var title: String {
            switch self {
            case .stopped:
                return "Stopped"
            case .starting:
                return "Starting"
            case .running:
                return "Running"
            case .failed:
                return "Error"
            }
        }
    }

    static let port: UInt16 = 9876

    @Published private(set) var status: ServerStatus = .stopped
    @Published private(set) var detailMessage = "Server is stopped."
    @Published private(set) var lastReceivedText: String?
    @Published private(set) var lastReceivedAt: Date?

    private let server = ClipboardHTTPServer()

    init() {
        startServer()
    }

    var canStart: Bool {
        status == .stopped || isFailed
    }

    var canStop: Bool {
        status == .starting || status == .running
    }

    func startServer() {
        guard canStart else { return }

        status = .starting
        detailMessage = "Opening port \(Self.port)…"

        server.start(
            port: Self.port,
            onReady: { [weak self] in
                Task { @MainActor in
                    self?.status = .running
                    self?.detailMessage = "Listening for POST /clipboard requests."
                }
            },
            onFailure: { [weak self] message in
                Task { @MainActor in
                    self?.status = .failed(message)
                    self?.detailMessage = message
                }
            },
            onText: { [weak self] text in
                Task { @MainActor in
                    self?.receive(text)
                }
            }
        )
    }

    func stopServer() {
        guard canStop else { return }

        server.stop()
        status = .stopped
        detailMessage = "Server is stopped."
    }

    private var isFailed: Bool {
        if case .failed = status {
            return true
        }
        return false
    }

    private func receive(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()

        guard pasteboard.setString(text, forType: .string) else {
            detailMessage = "Received text, but macOS rejected the pasteboard update."
            return
        }

        lastReceivedText = text
        lastReceivedAt = Date()
        detailMessage = "Received \(text.utf8.count) UTF-8 bytes."
    }
}

