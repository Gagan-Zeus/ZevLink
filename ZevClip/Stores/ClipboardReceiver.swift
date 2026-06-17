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
    static let serviceName = "ZevLink Mac Receiver"
    static let serviceType = "_zevclip._tcp"

    @Published private(set) var status: ServerStatus = .stopped
    @Published private(set) var detailMessage = "Server is stopped."
    @Published private(set) var isAdvertising = false
    @Published private(set) var pairingToken = ""
    @Published private(set) var pairingStatus = "Pairing token is not loaded."
    @Published private(set) var deviceId = ""
    @Published private(set) var lastReceivedText: String?
    @Published private(set) var lastReceivedAt: Date?
    @Published private(set) var lastMirroredNotification: AndroidMirroredNotification?
    @Published private(set) var lastMirroredNotificationAt: Date?
    @Published private(set) var lastMirroredCall: AndroidMirroredCall?
    @Published private(set) var lastMirroredCallAt: Date?

    var isRemoteControlEnabled: (() -> Bool)?
    var onPasteboardWrite: ((String, Int) -> Void)?
    var onAndroidEndpointSeen: ((AndroidReceiverEndpoint) -> Void)?
    var onAndroidNotification: ((AndroidMirroredNotification) -> Void)?
    var onAndroidCall: ((AndroidMirroredCall) -> Void)?

    private let server = ClipboardHTTPServer()
    private let tokenProvider = PairingTokenProvider(token: "")

    init() {
        deviceId = DeviceIdentityStore.loadOrCreateDeviceId()
        loadPairingToken()
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
        isAdvertising = false
        detailMessage = "Opening port \(Self.port)…"

        server.start(
            port: Self.port,
            serviceName: Self.serviceName,
            serviceType: Self.serviceType,
            deviceId: deviceId,
            tokenProvider: { [tokenProvider] in
                tokenProvider.currentToken()
            },
            isRemoteControlEnabled: { [weak self] in
                self?.isRemoteControlEnabled?() ?? false
            },
            onReady: { [weak self] in
                Task { @MainActor in
                    self?.status = .running
                    self?.detailMessage = "Listening for ZevLink requests."
                }
            },
            onAdvertisingChanged: { [weak self] isAdvertising in
                Task { @MainActor in
                    self?.isAdvertising = isAdvertising
                }
            },
            onFailure: { [weak self] message in
                Task { @MainActor in
                    self?.status = .failed(message)
                    self?.isAdvertising = false
                    self?.detailMessage = message
                }
            },
            onAndroidEndpointSeen: { [weak self] endpoint in
                Task { @MainActor in
                    self?.detailMessage = "Updated Android receiver at \(endpoint.displayAddress)."
                    self?.onAndroidEndpointSeen?(endpoint)
                }
            },
            onText: { [weak self] text in
                Task { @MainActor in
                    self?.receive(text)
                }
            },
            onAndroidNotification: { [weak self] data in
                Task { @MainActor in
                    self?.receiveAndroidNotification(data)
                }
            },
            onAndroidCall: { [weak self] data in
                Task { @MainActor in
                    self?.receiveAndroidCall(data)
                }
            },
            onRemoteControl: { [weak self] data in
                let result = MacRemoteController.handle(data)
                Task { @MainActor in
                    self?.detailMessage = result.body
                }
                return result
            }
        )
    }

    func stopServer() {
        guard canStop else { return }

        server.stop()
        status = .stopped
        isAdvertising = false
        detailMessage = "Server is stopped."
    }

    func regeneratePairingToken() {
        do {
            let token = try PairingTokenKeychain.regenerateToken()
            updatePairingToken(token, status: "Pairing token regenerated and saved in Keychain.")
            detailMessage = "Pairing token regenerated. Update Android before sending again."
        } catch {
            pairingStatus = error.localizedDescription
            detailMessage = error.localizedDescription
        }
    }

    private var isFailed: Bool {
        if case .failed = status {
            return true
        }
        return false
    }

    private func loadPairingToken() {
        do {
            let token = try PairingTokenKeychain.loadOrCreateToken()
            updatePairingToken(token, status: "Pairing token loaded from Keychain.")
        } catch {
            pairingStatus = error.localizedDescription
            detailMessage = error.localizedDescription
        }
    }

    private func updatePairingToken(_ token: String, status: String) {
        pairingToken = token
        pairingStatus = status
        tokenProvider.updateToken(token)
    }

    private func receive(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()

        guard pasteboard.setString(text, forType: .string) else {
            detailMessage = "Received text, but macOS rejected the pasteboard update."
            return
        }

        onPasteboardWrite?(text, pasteboard.changeCount)

        lastReceivedText = text
        lastReceivedAt = Date()
        detailMessage = "Received \(text.utf8.count) UTF-8 bytes."
    }

    private func receiveAndroidNotification(_ data: Data) {
        do {
            let notification = try JSONDecoder().decode(AndroidMirroredNotification.self, from: data)
            lastMirroredNotification = notification
            lastMirroredNotificationAt = Date()
            detailMessage = notification.isRemoval
                ? "Cleared Android notification from \(notification.appName)."
                : "Mirrored Android notification from \(notification.appName)."
            onAndroidNotification?(notification)
        } catch {
            detailMessage = "Received Android notification, but could not decode it."
        }
    }

    private func receiveAndroidCall(_ data: Data) {
        do {
            let call = try JSONDecoder().decode(AndroidMirroredCall.self, from: data)
            lastMirroredCall = call
            lastMirroredCallAt = Date()
            detailMessage = call.isRinging
                ? "Mirrored incoming Android call."
                : "Updated mirrored Android call: \(call.event)."
            onAndroidCall?(call)
        } catch {
            detailMessage = "Received Android call event, but could not decode it."
        }
    }

}

struct RemoteControlHTTPResult {
    let status: String
    let body: String

    static func success(_ body: String) -> RemoteControlHTTPResult {
        RemoteControlHTTPResult(status: "200 OK", body: body)
    }

    static func badRequest(_ body: String) -> RemoteControlHTTPResult {
        RemoteControlHTTPResult(status: "400 Bad Request", body: body)
    }

    static func failure(_ body: String) -> RemoteControlHTTPResult {
        RemoteControlHTTPResult(status: "409 Conflict", body: body)
    }
}

private enum MacRemoteController {
    private struct RemoteRequest: Decodable {
        let action: Action
        let url: String?
    }

    private enum Action: String, Decodable {
        case lock
        case sleepDisplay
        case sleep
        case restart
        case shutdown
        case logout
        case toggleMute
        case volumeUp
        case volumeDown
        case playPause
        case nextTrack
        case previousTrack
        case openURL
    }

    static func handle(_ data: Data) -> RemoteControlHTTPResult {
        do {
            let request = try JSONDecoder().decode(RemoteRequest.self, from: data)
            return execute(request)
        } catch {
            return .badRequest("Request body must be valid remote-control JSON.")
        }
    }

    private static func execute(_ request: RemoteRequest) -> RemoteControlHTTPResult {
        switch request.action {
        case .lock:
            return postKeyboardShortcut(
                keyCode: 12,
                flags: [.maskCommand, .maskControl],
                success: "Mac lock requested."
            )
        case .sleepDisplay:
            return run("/usr/bin/pmset", ["displaysleepnow"], success: "Display sleep requested.")
        case .sleep:
            return run("/usr/bin/pmset", ["sleepnow"], success: "Mac sleep requested.")
        case .restart:
            return runAppleScript("tell application \"System Events\" to restart", success: "Mac restart requested.")
        case .shutdown:
            return runAppleScript("tell application \"System Events\" to shut down", success: "Mac shutdown requested.")
        case .logout:
            return postKeyboardShortcut(
                keyCode: 12,
                flags: [.maskCommand, .maskAlternate, .maskShift],
                success: "Mac logout requested."
            )
        case .toggleMute:
            return postSystemKey(7, success: "Mac mute toggled.")
        case .volumeUp:
            return postSystemKey(0, success: "Mac volume increased.")
        case .volumeDown:
            return postSystemKey(1, success: "Mac volume decreased.")
        case .playPause:
            return postSystemKey(16, success: "Mac play/pause requested.")
        case .nextTrack:
            return postSystemKey(17, success: "Mac next track requested.")
        case .previousTrack:
            return postSystemKey(18, success: "Mac previous track requested.")
        case .openURL:
            return openURL(request.url)
        }
    }

    private static func openURL(_ value: String?) -> RemoteControlHTTPResult {
        guard
            let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
            let url = URL(string: value),
            let scheme = url.scheme?.lowercased(),
            ["http", "https"].contains(scheme),
            url.host?.isEmpty == false
        else {
            return .badRequest("Open URL requires a valid http or https URL.")
        }

        return run("/usr/bin/open", [value], success: "Opened URL on Mac.")
    }

    private static func runAppleScript(_ script: String, success: String) -> RemoteControlHTTPResult {
        run("/usr/bin/osascript", ["-e", script], success: success)
    }

    private static func run(
        _ executablePath: String,
        _ arguments: [String],
        success: String
    ) -> RemoteControlHTTPResult {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executablePath)
        process.arguments = arguments

        let errorPipe = Pipe()
        process.standardError = errorPipe

        do {
            try process.run()
            process.waitUntilExit()
        } catch {
            return .failure("Could not run Mac command: \(error.localizedDescription)")
        }

        guard process.terminationStatus == 0 else {
            let errorData = errorPipe.fileHandleForReading.readDataToEndOfFile()
            let errorText = String(data: errorData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return .failure(errorText?.isEmpty == false ? errorText! : "Mac command failed.")
        }

        return .success(success)
    }

    private static func postKeyboardShortcut(
        keyCode: CGKeyCode,
        flags: CGEventFlags,
        success: String
    ) -> RemoteControlHTTPResult {
        guard
            postKeyboardEvent(keyCode: keyCode, flags: flags, isDown: true),
            postKeyboardEvent(keyCode: keyCode, flags: flags, isDown: false)
        else {
            return .failure("Could not create Mac keyboard event.")
        }

        return .success(success)
    }

    private static func postKeyboardEvent(
        keyCode: CGKeyCode,
        flags: CGEventFlags,
        isDown: Bool
    ) -> Bool {
        let source = CGEventSource(stateID: .hidSystemState)
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: isDown) else {
            return false
        }

        event.flags = flags
        event.post(tap: .cghidEventTap)
        return true
    }

    private static func postSystemKey(_ key: Int32, success: String) -> RemoteControlHTTPResult {
        guard postSystemKeyEvent(key, isDown: true), postSystemKeyEvent(key, isDown: false) else {
            return .failure("Could not create Mac system key event.")
        }

        return .success(success)
    }

    private static func postSystemKeyEvent(_ key: Int32, isDown: Bool) -> Bool {
        let eventData = (Int(key) << 16) | ((isDown ? 0xA : 0xB) << 8)
        let event = NSEvent.otherEvent(
            with: .systemDefined,
            location: .zero,
            modifierFlags: NSEvent.ModifierFlags(rawValue: 0xA00),
            timestamp: 0,
            windowNumber: 0,
            context: nil,
            subtype: 8,
            data1: eventData,
            data2: -1
        )

        guard let cgEvent = event?.cgEvent else {
            return false
        }

        cgEvent.post(tap: .cghidEventTap)
        return true
    }
}
