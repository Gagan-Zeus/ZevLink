import Foundation

@MainActor
final class AndroidClipboardSender: ObservableObject {
    @Published private(set) var status = "Android clipboard sender is idle."
    @Published private(set) var isDiscovering = false
    @Published private(set) var isSending = false
    @Published private(set) var resolvedEndpoint: AndroidReceiverEndpoint?
    @Published private(set) var pairedDeviceId: String?
    @Published private(set) var lastSentText: String?
    @Published private(set) var lastSentAt: Date?

    private let tokenProvider: () -> String
    private var discovery: AndroidReceiverDiscovery?
    private var pendingChange: MacClipboardTextChange?
    private var sendTask: Task<Void, Never>?
    private var retriedChangeIDs: Set<UUID> = []

    init(tokenProvider: @escaping () -> String) {
        self.tokenProvider = tokenProvider
        pairedDeviceId = AndroidReceiverIdentityStore.pairedDeviceId()
    }

    func send(_ change: MacClipboardTextChange) {
        guard change.text.utf8.count <= Self.maximumBodyLength else {
            status = "Mac clipboard text is too large to send to Android."
            return
        }

        pendingChange = change
        sendPendingChangeIfPossible()
    }

    func rediscoverAndroidReceiver() {
        resolvedEndpoint = nil
        discoverAndroidReceiver()
    }

    private func sendPendingChangeIfPossible() {
        guard sendTask == nil else { return }
        guard let change = pendingChange else { return }
        guard let endpoint = resolvedEndpoint else {
            discoverAndroidReceiver()
            return
        }

        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            status = "Pairing token is empty; cannot send to Android."
            return
        }

        pendingChange = nil
        isSending = true
        status = "Sending Mac clipboard to Android..."

        sendTask = Task { [weak self] in
            let result = await AndroidClipboardHTTPClient.send(
                text: change.text,
                to: endpoint,
                token: token
            )

            await MainActor.run {
                self?.handleSendResult(result, for: change, endpoint: endpoint)
            }
        }
    }

    private func discoverAndroidReceiver() {
        guard !isDiscovering else { return }

        let discovery = AndroidReceiverDiscovery(
            onStatusChanged: { [weak self] status, isDiscovering in
                self?.handleDiscoveryStatus(status, isDiscovering: isDiscovering)
            },
            onEndpointResolved: { [weak self] endpoint in
                self?.resolvedEndpoint = endpoint
                self?.isDiscovering = false
                self?.sendPendingChangeIfPossible()
            }
        )

        self.discovery = discovery
        discovery.discover()
    }

    private func handleDiscoveryStatus(_ status: String, isDiscovering: Bool) {
        self.status = status
        self.isDiscovering = isDiscovering

        guard !isDiscovering, resolvedEndpoint == nil, pendingChange != nil else {
            return
        }

        if status.hasPrefix("No ZevClip Android receiver") ||
            status.hasPrefix("Could not start Android receiver discovery") {
            pendingChange = nil
        }
    }

    private func handleSendResult(
        _ result: AndroidClipboardHTTPClient.Result,
        for change: MacClipboardTextChange,
        endpoint: AndroidReceiverEndpoint
    ) {
        sendTask = nil
        isSending = false

        switch result {
        case .success(let confirmedDeviceId):
            retriedChangeIDs.remove(change.id)
            lastSentText = change.text
            lastSentAt = Date()
            resolvedEndpoint = endpoint
            saveConfirmedAndroidIdentity(confirmedDeviceId ?? endpoint.deviceId)
            status = "Sent \(change.text.utf8.count) UTF-8 bytes to Android."

        case .failure(let message, let isRetryable):
            if isRetryable && !retriedChangeIDs.contains(change.id) {
                retriedChangeIDs.insert(change.id)
                pendingChange = change
                resolvedEndpoint = nil
                status = "\(message) Rediscovering Android receiver..."
                discoverAndroidReceiver()
            } else {
                retriedChangeIDs.remove(change.id)
                status = message
            }
        }

        sendPendingChangeIfPossible()
    }

    private func saveConfirmedAndroidIdentity(_ deviceId: String?) {
        guard let deviceId else { return }
        AndroidReceiverIdentityStore.savePairedDeviceId(deviceId)
        pairedDeviceId = AndroidReceiverIdentityStore.pairedDeviceId()
    }

    private static let maximumBodyLength = 1_048_576
}

private enum AndroidClipboardHTTPClient {
    enum Result {
        case success(confirmedDeviceId: String?)
        case failure(String, isRetryable: Bool)
    }

    static func send(
        text: String,
        to endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> Result {
        guard let url = clipboardURL(for: endpoint) else {
            return .failure("Android receiver URL is invalid.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(text.utf8)

        do {
            let (body, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("Android receiver returned an invalid response.", isRetryable: true)
            }

            guard httpResponse.statusCode == 200 else {
                let bodyText = String(data: body, encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let detail = bodyText?.isEmpty == false ? " \(bodyText!)" : ""
                return .failure(
                    "Android receiver rejected clipboard send (\(httpResponse.statusCode)).\(detail)",
                    isRetryable: isRetryableStatusCode(httpResponse.statusCode)
                )
            }

            return .success(confirmedDeviceId: httpResponse.value(forHTTPHeaderField: Self.androidDeviceIDHeader))
        } catch {
            return .failure(
                "Could not send clipboard to Android: \(error.localizedDescription)",
                isRetryable: true
            )
        }
    }

    private static func clipboardURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        if endpoint.host.contains(":") {
            return URL(string: "http://[\(endpoint.host)]:\(endpoint.port)/clipboard")
        }

        var components = URLComponents()
        components.scheme = "http"
        components.host = endpoint.host
        components.port = endpoint.port
        components.path = "/clipboard"
        return components.url
    }

    private static func isRetryableStatusCode(_ statusCode: Int) -> Bool {
        statusCode == 408 || statusCode == 429 || (500...599).contains(statusCode)
    }

    private static let androidDeviceIDHeader = "X-ZevClip-Android-Device-ID"
}
