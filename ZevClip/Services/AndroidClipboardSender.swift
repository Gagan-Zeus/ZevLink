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
    private var statusTask: Task<Void, Never>?
    private var statusRefreshTimer: Timer?
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
        discoverAndroidReceiver()
    }

    func updateEndpointSeenFromAndroid(_ endpoint: AndroidReceiverEndpoint) {
        if let pairedDeviceId, let endpointDeviceId = endpoint.deviceId, endpointDeviceId != pairedDeviceId {
            return
        }

        discovery?.stop()
        discovery = nil
        isDiscovering = false
        resolvedEndpoint = endpoint
        saveConfirmedAndroidIdentity(endpoint.deviceId)
        status = "Connected to Android receiver at \(endpoint.displayAddress)."
        refreshAndroidStatus()
        sendPendingChangeIfPossible()
    }

    func startStatusMonitoring() {
        guard statusRefreshTimer == nil else { return }

        let timer = Timer(timeInterval: Self.statusRefreshInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.refreshConnectionStatus()
            }
        }
        timer.tolerance = Self.statusRefreshTolerance
        RunLoop.main.add(timer, forMode: .common)
        statusRefreshTimer = timer
        refreshConnectionStatus()
    }

    func dismissAndroidNotification(notificationKey: String) {
        let trimmedKey = notificationKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else { return }
        guard let endpoint = resolvedEndpoint else {
            discoverAndroidReceiver()
            return
        }

        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return }

        Task { [weak self] in
            let result = await AndroidClipboardHTTPClient.dismissNotification(
                notificationKey: trimmedKey,
                on: endpoint,
                token: token
            )

            await MainActor.run {
                switch result {
                case .success:
                    self?.status = "Dismissed Android notification from Mac."
                case .failure(let message, let isRetryable):
                    self?.status = message
                    if isRetryable {
                        self?.resolvedEndpoint = nil
                        self?.discoverAndroidReceiver()
                    }
                }
            }
        }
    }

    func sendAndroidCallAction(
        action: String,
        callId: String,
        completion: ((Bool, String) -> Void)? = nil
    ) {
        let trimmedAction = action.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedCallId = callId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAction.isEmpty, !trimmedCallId.isEmpty else {
            completion?(false, "Call action is missing.")
            return
        }
        guard let endpoint = resolvedEndpoint else {
            discoverAndroidReceiver()
            completion?(false, "Rediscovering Android receiver...")
            return
        }

        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            completion?(false, "Pairing token is empty.")
            return
        }

        Task { [weak self] in
            let result = await AndroidClipboardHTTPClient.sendCallAction(
                action: trimmedAction,
                callId: trimmedCallId,
                on: endpoint,
                token: token
            )

            await MainActor.run {
                switch result {
                case .success(let message):
                    self?.status = message
                    completion?(true, message)
                case .failure(let message, let isRetryable):
                    self?.status = message
                    completion?(false, message)
                    if isRetryable {
                        self?.resolvedEndpoint = nil
                        self?.discoverAndroidReceiver()
                    }
                }
            }
        }
    }

    func sendAndroidMediaCommand(action: String) {
        let trimmedAction = action.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAction.isEmpty else { return }
        guard let endpoint = resolvedEndpoint else {
            discoverAndroidReceiver()
            status = "Rediscovering Android receiver for media control..."
            return
        }

        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            status = "Pairing token is empty; cannot control Android media."
            return
        }

        Task { [weak self] in
            let result = await AndroidClipboardHTTPClient.sendMediaCommand(
                action: trimmedAction,
                on: endpoint,
                token: token
            )

            await MainActor.run {
                switch result {
                case .success(let message):
                    self?.status = message
                case .failure(let message, let isRetryable):
                    self?.status = message
                    if isRetryable {
                        self?.resolvedEndpoint = nil
                        self?.discoverAndroidReceiver()
                    }
                }
            }
        }
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
                self?.refreshAndroidStatus()
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

        if status.hasPrefix("No ZevLink Android receiver") ||
            status.hasPrefix("No ZevClip Android receiver") ||
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

    private func refreshConnectionStatus() {
        if resolvedEndpoint == nil {
            discoverAndroidReceiver()
        } else {
            refreshAndroidStatus()
        }
    }

    private func refreshAndroidStatus() {
        guard statusTask == nil else { return }
        guard let endpoint = resolvedEndpoint else { return }

        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return }

        statusTask = Task { [weak self] in
            let result = await AndroidClipboardHTTPClient.fetchStatus(
                from: endpoint,
                token: token
            )

            await MainActor.run {
                self?.handleStatusResult(result, endpoint: endpoint)
            }
        }
    }

    private func handleStatusResult(
        _ result: AndroidClipboardHTTPClient.StatusResult,
        endpoint: AndroidReceiverEndpoint
    ) {
        statusTask = nil

        switch result {
        case .success(let batteryPercentage, let confirmedDeviceId):
            let updatedEndpoint = endpoint.updatingBatteryPercentage(
                batteryPercentage ?? endpoint.batteryPercentage
            )
            resolvedEndpoint = updatedEndpoint
            saveConfirmedAndroidIdentity(confirmedDeviceId ?? endpoint.deviceId)

        case .failure(let message, let isRetryable):
            status = message
            if isRetryable {
                resolvedEndpoint = nil
                discoverAndroidReceiver()
            }
        }
    }

    private func saveConfirmedAndroidIdentity(_ deviceId: String?) {
        guard let deviceId else { return }
        AndroidReceiverIdentityStore.savePairedDeviceId(deviceId)
        pairedDeviceId = AndroidReceiverIdentityStore.pairedDeviceId()
    }

    private static let maximumBodyLength = 1_048_576
    private static let statusRefreshInterval: TimeInterval = 60
    private static let statusRefreshTolerance: TimeInterval = 15
}

private enum AndroidClipboardHTTPClient {
    enum Result {
        case success(confirmedDeviceId: String?)
        case failure(String, isRetryable: Bool)
    }

    enum StatusResult {
        case success(batteryPercentage: Int?, confirmedDeviceId: String?)
        case failure(String, isRetryable: Bool)
    }

    enum CallActionResult {
        case success(String)
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
        applyMacBatteryHeaders(to: &request)
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

    static func fetchStatus(
        from endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> StatusResult {
        guard let url = statusURL(for: endpoint) else {
            return .failure("Android receiver status URL is invalid.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        applyMacBatteryHeaders(to: &request)

        do {
            let (body, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("Android receiver returned an invalid status response.", isRetryable: true)
            }

            guard httpResponse.statusCode == 200 else {
                return .failure(
                    "Android receiver status failed (\(httpResponse.statusCode)).",
                    isRetryable: isRetryableStatusCode(httpResponse.statusCode)
                )
            }

            return .success(
                batteryPercentage: batteryPercentage(from: httpResponse, body: body),
                confirmedDeviceId: httpResponse.value(forHTTPHeaderField: Self.androidDeviceIDHeader)
            )
        } catch {
            return .failure(
                "Could not refresh Android status: \(error.localizedDescription)",
                isRetryable: true
            )
        }
    }

    static func dismissNotification(
        notificationKey: String,
        on endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> Result {
        guard let url = notificationActionURL(for: endpoint) else {
            return .failure("Android notification action URL is invalid.", isRetryable: false)
        }

        let body = [
            "action": "dismiss",
            "notificationKey": notificationKey
        ]

        guard let bodyData = try? JSONSerialization.data(withJSONObject: body) else {
            return .failure("Could not encode Android notification dismiss action.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        applyMacBatteryHeaders(to: &request)
        request.httpBody = bodyData

        do {
            let (responseBody, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("Android notification action returned an invalid response.", isRetryable: true)
            }

            guard httpResponse.statusCode == 200 else {
                let bodyText = String(data: responseBody, encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let detail = bodyText?.isEmpty == false ? " \(bodyText!)" : ""
                return .failure(
                    "Android notification action failed (\(httpResponse.statusCode)).\(detail)",
                    isRetryable: isRetryableStatusCode(httpResponse.statusCode)
                )
            }

            return .success(confirmedDeviceId: httpResponse.value(forHTTPHeaderField: Self.androidDeviceIDHeader))
        } catch {
            return .failure(
                "Could not dismiss Android notification: \(error.localizedDescription)",
                isRetryable: true
            )
        }
    }

    static func sendCallAction(
        action: String,
        callId: String,
        on endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> CallActionResult {
        guard let url = callActionURL(for: endpoint) else {
            return .failure("Android call action URL is invalid.", isRetryable: false)
        }

        let body = [
            "action": action,
            "callId": callId
        ]

        guard let bodyData = try? JSONSerialization.data(withJSONObject: body) else {
            return .failure("Could not encode Android call action.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        applyMacBatteryHeaders(to: &request)
        request.httpBody = bodyData

        do {
            let (responseBody, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("Android call action returned an invalid response.", isRetryable: true)
            }

            let bodyText = String(data: responseBody, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard httpResponse.statusCode == 200 else {
                let detail = bodyText?.isEmpty == false ? " \(bodyText!)" : ""
                return .failure(
                    "Android call action failed (\(httpResponse.statusCode)).\(detail)",
                    isRetryable: isRetryableStatusCode(httpResponse.statusCode)
                )
            }

            return .success(bodyText?.isEmpty == false ? bodyText! : "Android call action sent.")
        } catch {
            return .failure(
                "Could not send Android call action: \(error.localizedDescription)",
                isRetryable: true
            )
        }
    }

    static func sendMediaCommand(
        action: String,
        on endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> CallActionResult {
        guard let url = mediaControlURL(for: endpoint) else {
            return .failure("Android media control URL is invalid.", isRetryable: false)
        }

        let body = ["action": action]

        guard let bodyData = try? JSONSerialization.data(withJSONObject: body) else {
            return .failure("Could not encode Android media command.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        applyMacBatteryHeaders(to: &request)
        request.httpBody = bodyData

        do {
            let (responseBody, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("Android media control returned an invalid response.", isRetryable: true)
            }

            let bodyText = String(data: responseBody, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard httpResponse.statusCode == 200 else {
                let detail = bodyText?.isEmpty == false ? " \(bodyText!)" : ""
                return .failure(
                    "Android media control failed (\(httpResponse.statusCode)).\(detail)",
                    isRetryable: isRetryableStatusCode(httpResponse.statusCode)
                )
            }

            return .success(bodyText?.isEmpty == false ? bodyText! : "Android media command sent.")
        } catch {
            return .failure(
                "Could not control Android media: \(error.localizedDescription)",
                isRetryable: true
            )
        }
    }


    private static func clipboardURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        url(for: endpoint, path: "/clipboard")
    }

    private static func statusURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        url(for: endpoint, path: "/status")
    }

    private static func notificationActionURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        url(for: endpoint, path: "/notification-action")
    }

    private static func callActionURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        url(for: endpoint, path: "/call-action")
    }

    private static func mediaControlURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        url(for: endpoint, path: "/media-control")
    }

    private static func url(for endpoint: AndroidReceiverEndpoint, path: String) -> URL? {
        if endpoint.host.contains(":") {
            return URL(string: "http://[\(endpoint.host)]:\(endpoint.port)\(path)")
        }

        var components = URLComponents()
        components.scheme = "http"
        components.host = endpoint.host
        components.port = endpoint.port
        components.path = path
        return components.url
    }

    private static func batteryPercentage(
        from response: HTTPURLResponse,
        body: Data
    ) -> Int? {
        if
            let headerValue = response.value(forHTTPHeaderField: Self.androidBatteryHeader),
            let percentage = Int(headerValue),
            (0...100).contains(percentage)
        {
            return percentage
        }

        guard
            let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
            let percentage = json["batteryPercentage"] as? Int,
            (0...100).contains(percentage)
        else {
            return nil
        }

        return percentage
    }

    private static func isRetryableStatusCode(_ statusCode: Int) -> Bool {
        statusCode == 408 || statusCode == 429 || (500...599).contains(statusCode)
    }

    private static func applyMacBatteryHeaders(to request: inout URLRequest) {
        let battery = MacBatteryStatus.current()
        request.setValue(battery.isAvailable ? "true" : "false", forHTTPHeaderField: macBatteryAvailableHeader)

        if let percentage = battery.percentage {
            request.setValue(String(percentage), forHTTPHeaderField: macBatteryHeader)
        }

        if let isCharging = battery.isCharging {
            request.setValue(isCharging ? "true" : "false", forHTTPHeaderField: macChargingHeader)
        }
    }

    private static let androidDeviceIDHeader = "X-ZevClip-Android-Device-ID"
    private static let androidBatteryHeader = "X-ZevClip-Android-Battery"
    private static let macBatteryAvailableHeader = "X-ZevClip-Mac-Battery-Available"
    private static let macBatteryHeader = "X-ZevClip-Mac-Battery"
    private static let macChargingHeader = "X-ZevClip-Mac-Charging"
}
