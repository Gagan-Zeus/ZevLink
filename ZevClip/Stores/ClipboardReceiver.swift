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

    var onPasteboardWrite: ((String, Int) -> Void)?
    var onAndroidEndpointSeen: ((AndroidReceiverEndpoint) -> Void)?
    var onAndroidNotification: ((AndroidMirroredNotification) -> Void)?
    var onAndroidCall: ((AndroidMirroredCall) -> Void)?
    var onAndroidNowPlaying: ((AndroidNowPlayingPayload) -> Void)?

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
            onReady: { [weak self] in
                Task { @MainActor in
                    self?.status = .running
                    self?.detailMessage = "Listening for POST /clipboard requests."
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
            onAndroidNowPlaying: { [weak self] data in
                Task { @MainActor in
                    self?.receiveAndroidNowPlaying(data)
                }
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

    private func receiveAndroidNowPlaying(_ data: Data) {
        do {
            let payload = try JSONDecoder().decode(AndroidNowPlayingPayload.self, from: data)
            detailMessage = "Updated Android Now Playing."
            onAndroidNowPlaying?(payload)
        } catch {
            detailMessage = "Received Android Now Playing, but could not decode it."
        }
    }
}
