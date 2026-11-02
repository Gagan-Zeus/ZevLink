import Foundation
import Network

@MainActor
final class AndroidClipboardSender: ObservableObject {
    enum TransportMode: String {
        case wifi = "Wi-Fi"
        case bluetooth = "Bluetooth"
        case disconnected = "Disconnected"
    }

    @Published private(set) var status = "Android clipboard sender is idle."
    @Published private(set) var activeTransportMode: TransportMode = .disconnected
    @Published private(set) var isDiscovering = false
    @Published private(set) var isSending = false
    @Published private(set) var isFindingPhone = false
    @Published private(set) var resolvedEndpoint: AndroidReceiverEndpoint?
    @Published private(set) var pairedDeviceId: String?
    @Published private(set) var lastSentText: String?
    @Published private(set) var lastSentAt: Date?
    @Published private(set) var intentBroadcastStatus: IntentBroadcastDeliveryStatus = .idle
    @Published private(set) var intentBroadcastMessage = "No Intent has been sent."

    private let tokenProvider: () -> String
    private var discovery: AndroidReceiverDiscovery?
    private var pendingChange: MacClipboardChange?
    private var sendTask: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?
    private var statusRefreshTimer: Timer?
    private var pathMonitor: NWPathMonitor?
    private var lastKnownHosts: Set<String> = []
    private var retriedChangeIDs: Set<UUID> = []
    private var pendingIntentBroadcastCompletions: [String: (IntentBroadcastAcknowledgement) -> Void] = [:]
    private var pendingIntentBroadcastTimeouts: [String: Task<Void, Never>] = [:]

    init(tokenProvider: @escaping () -> String) {
        self.tokenProvider = tokenProvider
        pairedDeviceId = AndroidReceiverIdentityStore.pairedDeviceId()
    }

    func send(_ change: MacClipboardChange) {
        let maximumLength: Int
        switch change.content {
        case .text:
            maximumLength = Self.maximumTextBodyLength
        case .pngImage:
            maximumLength = Self.maximumImageBodyLength
        case .file:
            maximumLength = Self.maximumImageBodyLength
        }

        guard change.content.byteCount <= maximumLength else {
            status = "Mac clipboard content is too large to send to Android."
            return
        }

        pendingChange = change
        sendPendingChangeIfPossible()
    }

    func rediscoverAndroidReceiver() {
        discoverAndroidReceiver()
    }

    func sendIntentBroadcast(
        _ preset: IntentPreset,
        completion: ((IntentBroadcastAcknowledgement) -> Void)? = nil
    ) {
        let request: IntentBroadcastRequest
        do {
            request = try preset.makeRequest()
        } catch {
            intentBroadcastStatus = .invalidConfiguration
            intentBroadcastMessage = error.localizedDescription
            return
        }

        guard let requestData = try? JSONEncoder().encode(request),
              let requestBody = String(data: requestData, encoding: .utf8)
        else {
            intentBroadcastStatus = .invalidConfiguration
            intentBroadcastMessage = "Could not encode the Intent request."
            return
        }

        let requestId = request.requestId
        intentBroadcastStatus = .sending
        intentBroadcastMessage = "Sending Intent request…"

        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil), MacBluetoothService.shared.isConnected {
            guard MacBluetoothService.shared.sendBluetoothMessage(
                path: "/intent-broadcast",
                body: requestBody
            ) else {
                intentBroadcastStatus = .broadcastOperationFailed
                intentBroadcastMessage = "Bluetooth is connected but could not queue the Intent request."
                return
            }

            activeTransportMode = .bluetooth
            intentBroadcastStatus = .requestDelivered
            intentBroadcastMessage = "Intent request delivered over Bluetooth; waiting for Android acknowledgement…"
            registerIntentBroadcastWaiter(requestId: requestId, completion: completion)
            return
        }

        guard let endpoint = resolvedEndpoint else {
            intentBroadcastStatus = .notConnected
            intentBroadcastMessage = "Connect an Android device before sending an Intent."
            return
        }

        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else {
            intentBroadcastStatus = .notConnected
            intentBroadcastMessage = "Pairing token is unavailable."
            return
        }

        Task { [weak self] in
            let result = await AndroidClipboardHTTPClient.sendIntentBroadcast(
                requestData: requestData,
                on: endpoint,
                token: token
            )
            await MainActor.run {
                self?.handleIntentBroadcastHTTPResult(result, completion: completion)
            }
        }
    }

    func handleIntentBroadcastAcknowledgement(_ data: Data) {
        guard let acknowledgement = try? JSONDecoder().decode(
            IntentBroadcastAcknowledgement.self,
            from: data
        ) else {
            intentBroadcastStatus = .broadcastOperationFailed
            intentBroadcastMessage = "Android returned an invalid Intent acknowledgement."
            return
        }

        pendingIntentBroadcastTimeouts[acknowledgement.requestId]?.cancel()
        pendingIntentBroadcastTimeouts[acknowledgement.requestId] = nil
        let completion = pendingIntentBroadcastCompletions.removeValue(forKey: acknowledgement.requestId)
        applyIntentBroadcastAcknowledgement(acknowledgement)
        completion?(acknowledgement)
    }

    private func registerIntentBroadcastWaiter(
        requestId: String,
        completion: ((IntentBroadcastAcknowledgement) -> Void)?
    ) {
        if let completion {
            pendingIntentBroadcastCompletions[requestId] = completion
        }
        pendingIntentBroadcastTimeouts[requestId]?.cancel()
        pendingIntentBroadcastTimeouts[requestId] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self else { return }
                let hadPendingRequest = self.pendingIntentBroadcastCompletions.removeValue(forKey: requestId) != nil ||
                    self.pendingIntentBroadcastTimeouts[requestId] != nil
                guard hadPendingRequest else { return }
                self.pendingIntentBroadcastTimeouts[requestId] = nil
                self.intentBroadcastStatus = .requestTimedOut
                self.intentBroadcastMessage = "Android did not acknowledge the Intent request within 8 seconds."
            }
        }
    }

    private func handleIntentBroadcastHTTPResult(
        _ result: AndroidClipboardHTTPClient.IntentBroadcastResult,
        completion: ((IntentBroadcastAcknowledgement) -> Void)?
    ) {
        switch result {
        case .success(let acknowledgement):
            activeTransportMode = .wifi
            intentBroadcastStatus = .intentBroadcastRequested
            intentBroadcastMessage = acknowledgement.message
            completion?(acknowledgement)
        case .failure(let acknowledgement):
            intentBroadcastStatus = acknowledgement.status == "timeout_or_transport_error"
                ? .requestTimedOut
                : .broadcastOperationFailed
            intentBroadcastMessage = acknowledgement.message
            completion?(acknowledgement)
        }
    }

    private func applyIntentBroadcastAcknowledgement(_ acknowledgement: IntentBroadcastAcknowledgement) {
        if acknowledgement.success {
            intentBroadcastStatus = .intentBroadcastRequested
            intentBroadcastMessage = acknowledgement.message
        } else {
            intentBroadcastStatus = .broadcastOperationFailed
            intentBroadcastMessage = acknowledgement.message
        }
    }

    func updateEndpointSeenFromAndroid(_ endpoint: AndroidReceiverEndpoint) {
        if let pairedDeviceId, let endpointDeviceId = endpoint.deviceId, endpointDeviceId != pairedDeviceId {
            return
        }

        discovery?.stop()
        discovery = nil
        isDiscovering = false
        resolvedEndpoint = endpoint
        activeTransportMode = .wifi
        if let findPhoneRinging = endpoint.findPhoneRinging {
            setFindingPhone(findPhoneRinging)
        }
        saveConfirmedAndroidIdentity(
            endpoint.deviceId,
            transferCertificateSHA256: endpoint.transferCertificateSHA256
        )
        status = "Connected to Android receiver at \(endpoint.displayAddress)."
        refreshAndroidStatus()
        sendPendingChangeIfPossible()
    }

    func clearResolvedEndpoint() {
        resolvedEndpoint = nil
        activeTransportMode = MacBluetoothService.shared.isConnected ? .bluetooth : .disconnected
        status = "Disconnected from Android."
    }

    func startStatusMonitoring() {
        guard statusRefreshTimer == nil else { return }

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.handleNetworkPathUpdate(path)
            }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        pathMonitor = monitor

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

    private func handleNetworkPathUpdate(_ path: NWPath) {
        let currentHosts = Set(LocalNetworkHost.currentPairingHosts())
        let hostsChanged = !lastKnownHosts.isEmpty && (currentHosts != lastKnownHosts)
        lastKnownHosts = currentHosts

        var endpointUnreachable = false
        if let endpoint = resolvedEndpoint {
            endpointUnreachable = !isEndpointSubnetReachable(endpoint, localHosts: currentHosts)
        }

        if path.status != .satisfied || currentHosts.isEmpty || hostsChanged || endpointUnreachable {
            if resolvedEndpoint != nil || activeTransportMode == .wifi {
                clearResolvedEndpoint()
            }
        }

        if path.status == .satisfied && !currentHosts.isEmpty {
            if resolvedEndpoint != nil {
                statusTask?.cancel()
                statusTask = nil
                refreshAndroidStatus()
            } else {
                discoverAndroidReceiver()
            }
        }
    }

    private func isEndpointSubnetReachable(_ endpoint: AndroidReceiverEndpoint, localHosts: Set<String>) -> Bool {
        if localHosts.isEmpty { return false }
        let target = endpoint.host
        guard let targetOctets = LocalNetworkHost.octets(from: target) else {
            return true
        }
        for localHost in localHosts {
            if let localOctets = LocalNetworkHost.octets(from: localHost) {
                if targetOctets[0] == localOctets[0] && targetOctets[1] == localOctets[1] {
                    if targetOctets[0] == 192 && targetOctets[1] == 168 {
                        if targetOctets[2] == localOctets[2] { return true }
                    } else if targetOctets[0] == 10 {
                        return true
                    } else if targetOctets[0] == 172 {
                        if targetOctets[1] == localOctets[1] { return true }
                    }
                }
            }
        }
        return false
    }

    func dismissAndroidNotification(notificationKey: String) {
        let trimmedKey = notificationKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else { return }

        // Fast-path: Bluetooth active or no Wi-Fi endpoint
        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil) && MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/notification-action",
                notificationKey: trimmedKey,
                actionId: "dismiss"
            )
            activeTransportMode = .bluetooth
            status = "Dismissed Android notification via Bluetooth."
            return
        }

        // Priority 1: Try Wi-Fi if resolvedEndpoint is available
        if let endpoint = resolvedEndpoint {
            let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
            if !token.isEmpty {
                Task { [weak self] in
                    let result = await AndroidClipboardHTTPClient.dismissNotification(
                        notificationKey: trimmedKey,
                        on: endpoint,
                        token: token
                    )

                    await MainActor.run {
                        switch result {
                        case .success:
                            self?.activeTransportMode = .wifi
                            self?.status = "Dismissed Android notification from Mac."
                        case .failure(let message, let isRetryable):
                            // Fallback to Bluetooth
                            if MacBluetoothService.shared.isConnected {
                                MacBluetoothService.shared.sendBluetoothMessage(
                                    path: "/notification-action",
                                    notificationKey: trimmedKey,
                                    actionId: "dismiss"
                                )
                                self?.activeTransportMode = .bluetooth
                                self?.resolvedEndpoint = nil
                                self?.status = "Dismissed notification via Bluetooth fallback."
                            } else {
                                self?.status = message
                                if isRetryable {
                                    self?.resolvedEndpoint = nil
                                    self?.discoverAndroidReceiver()
                                }
                            }
                        }
                    }
                }
                return
            }
        }

        // Priority 2: Bluetooth fallback if Wi-Fi endpoint unavailable
        if MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/notification-action",
                notificationKey: trimmedKey,
                actionId: "dismiss"
            )
            activeTransportMode = .bluetooth
            status = "Dismissed Android notification via Bluetooth."
        } else {
            discoverAndroidReceiver()
        }
    }

    func performAndroidNotificationAction(
        notificationKey: String,
        actionId: String,
        replyText: String? = nil,
        completion: ((Bool, String) -> Void)? = nil
    ) {
        let trimmedKey = notificationKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedActionId = actionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty, !trimmedActionId.isEmpty else {
            completion?(false, "Notification action is missing.")
            return
        }

        // Fast-path: Bluetooth active or no Wi-Fi endpoint
        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil) && MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/notification-action",
                notificationKey: trimmedKey,
                actionId: trimmedActionId,
                replyText: replyText
            )
            activeTransportMode = .bluetooth
            status = "Performed notification action via Bluetooth."
            completion?(true, "Action sent via Bluetooth.")
            return
        }

        // Priority 1: Try Wi-Fi
        if let endpoint = resolvedEndpoint {
            let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
            if !token.isEmpty {
                Task { [weak self] in
                    let result = await AndroidClipboardHTTPClient.performNotificationAction(
                        notificationKey: trimmedKey,
                        actionId: trimmedActionId,
                        replyText: replyText,
                        on: endpoint,
                        token: token
                    )

                    await MainActor.run {
                        switch result {
                        case .success:
                            self?.activeTransportMode = .wifi
                            self?.status = "Performed Android notification action from Mac."
                            completion?(true, "Android notification action sent.")
                        case .failure(let message, let isRetryable):
                            // Fallback to Bluetooth
                            if MacBluetoothService.shared.isConnected {
                                MacBluetoothService.shared.sendBluetoothMessage(
                                    path: "/notification-action",
                                    notificationKey: trimmedKey,
                                    actionId: trimmedActionId,
                                    replyText: replyText
                                )
                                self?.activeTransportMode = .bluetooth
                                self?.resolvedEndpoint = nil
                                self?.status = "Performed notification action via Bluetooth fallback."
                                completion?(true, "Action sent via Bluetooth.")
                            } else {
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
                return
            }
        }

        // Priority 2: Bluetooth fallback
        if MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/notification-action",
                notificationKey: trimmedKey,
                actionId: trimmedActionId,
                replyText: replyText
            )
            activeTransportMode = .bluetooth
            status = "Performed notification action via Bluetooth."
            completion?(true, "Action sent via Bluetooth.")
        } else {
            discoverAndroidReceiver()
            completion?(false, "Rediscovering Android receiver...")
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

        // Fast-path: Bluetooth active or no Wi-Fi endpoint
        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil) && MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/call-action",
                action: trimmedAction,
                callId: trimmedCallId
            )
            activeTransportMode = .bluetooth
            status = "Call action sent via Bluetooth."
            completion?(true, "Call action sent via Bluetooth.")
            return
        }

        // Priority 1: Try Wi-Fi
        if let endpoint = resolvedEndpoint {
            let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
            if !token.isEmpty {
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
                            self?.activeTransportMode = .wifi
                            self?.status = message
                            completion?(true, message)
                        case .failure(let message, let isRetryable):
                            if MacBluetoothService.shared.isConnected {
                                MacBluetoothService.shared.sendBluetoothMessage(
                                    path: "/call-action",
                                    action: trimmedAction,
                                    callId: trimmedCallId
                                )
                                self?.activeTransportMode = .bluetooth
                                self?.resolvedEndpoint = nil
                                self?.status = "Call action sent via Bluetooth fallback."
                                completion?(true, "Call action sent via Bluetooth.")
                            } else {
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
                return
            }
        }

        // Priority 2: Bluetooth fallback
        if MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/call-action",
                action: trimmedAction,
                callId: trimmedCallId
            )
            activeTransportMode = .bluetooth
            status = "Call action sent via Bluetooth."
            completion?(true, "Call action sent via Bluetooth.")
        } else {
            discoverAndroidReceiver()
            completion?(false, "Rediscovering Android receiver...")
        }
    }

    func sendAndroidMediaCommand(action: String) {
        let trimmedAction = action.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAction.isEmpty else { return }

        // Fast-path: Bluetooth active or no Wi-Fi endpoint
        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil) && MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/media-command",
                action: trimmedAction
            )
            activeTransportMode = .bluetooth
            status = "Media command sent via Bluetooth."
            return
        }

        // Priority 1: Try Wi-Fi
        if let endpoint = resolvedEndpoint {
            let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
            if !token.isEmpty {
                Task { [weak self] in
                    let result = await AndroidClipboardHTTPClient.sendMediaCommand(
                        action: trimmedAction,
                        on: endpoint,
                        token: token
                    )

                    await MainActor.run {
                        switch result {
                        case .success(let message):
                            self?.activeTransportMode = .wifi
                            self?.status = message
                        case .failure(let message, let isRetryable):
                            if MacBluetoothService.shared.isConnected {
                                MacBluetoothService.shared.sendBluetoothMessage(
                                    path: "/media-command",
                                    action: trimmedAction
                                )
                                self?.activeTransportMode = .bluetooth
                                self?.resolvedEndpoint = nil
                                self?.status = "Media command sent via Bluetooth fallback."
                            } else {
                                self?.status = message
                                if isRetryable {
                                    self?.resolvedEndpoint = nil
                                    self?.discoverAndroidReceiver()
                                }
                            }
                        }
                    }
                }
                return
            }
        }

        // Priority 2: Bluetooth fallback
        if MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/media-command",
                action: trimmedAction
            )
            activeTransportMode = .bluetooth
            status = "Media command sent via Bluetooth."
        } else {
            discoverAndroidReceiver()
            status = "Rediscovering Android receiver for media control..."
        }
    }

    func toggleFindMyPhone(completion: ((Bool, String) -> Void)? = nil) {
        let action = isFindingPhone ? "stop" : "start"
        status = action == "start" ? "Asking Android phone to ring..." : "Stopping Android phone ring..."

        // Fast-path: Bluetooth active or no Wi-Fi endpoint
        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil) && MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/find-phone",
                action: action
            )
            activeTransportMode = .bluetooth
            setFindingPhone(action == "start")
            status = "Find My Phone sent via Bluetooth."
            completion?(true, "Find My Phone sent via Bluetooth.")
            return
        }

        // Priority 1: Try Wi-Fi
        if let endpoint = resolvedEndpoint {
            let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
            if !token.isEmpty {
                Task { [weak self] in
                    let result = await AndroidClipboardHTTPClient.findMyPhone(
                        action: action,
                        on: endpoint,
                        token: token
                    )

                    await MainActor.run {
                        switch result {
                        case .success(let message):
                            self?.activeTransportMode = .wifi
                            self?.status = message
                            self?.setFindingPhone(action == "start")
                            completion?(true, message)
                        case .failure(let message, let isRetryable):
                            if MacBluetoothService.shared.isConnected {
                                MacBluetoothService.shared.sendBluetoothMessage(
                                    path: "/find-phone",
                                    action: action
                                )
                                self?.activeTransportMode = .bluetooth
                                self?.resolvedEndpoint = nil
                                self?.setFindingPhone(action == "start")
                                self?.status = "Find My Phone sent via Bluetooth fallback."
                                completion?(true, "Find My Phone sent via Bluetooth.")
                            } else {
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
                return
            }
        }

        // Priority 2: Bluetooth fallback
        if MacBluetoothService.shared.isConnected {
            MacBluetoothService.shared.sendBluetoothMessage(
                path: "/find-phone",
                action: action
            )
            activeTransportMode = .bluetooth
            setFindingPhone(action == "start")
            status = "Find My Phone sent via Bluetooth."
            completion?(true, "Find My Phone sent via Bluetooth.")
        } else {
            discoverAndroidReceiver()
            status = "Rediscovering Android receiver for Find My Phone..."
            completion?(false, status)
        }
    }

    func setFindingPhone(_ isFinding: Bool) {
        isFindingPhone = isFinding
    }

    private func sendPendingChangeIfPossible() {
        guard sendTask == nil else { return }
        guard let change = pendingChange else { return }

        // Fast-path: Bluetooth active or no Wi-Fi endpoint
        if (activeTransportMode == .bluetooth || resolvedEndpoint == nil) && MacBluetoothService.shared.isConnected, case .text(let text) = change.content {
            let sent = MacBluetoothService.shared.sendBluetoothMessage(path: "/clipboard", body: text)
            if sent {
                pendingChange = nil
                lastSentText = text
                lastSentAt = Date()
                activeTransportMode = .bluetooth
                status = "Sent clipboard via Bluetooth."
                return
            }
        }

        // Priority 1: Try Wi-Fi if resolvedEndpoint is available
        if let endpoint = resolvedEndpoint {
            let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
            guard !token.isEmpty else {
                status = "Pairing token is empty; cannot send to Android."
                return
            }

            pendingChange = nil
            isSending = true
            status = "Sending Mac clipboard to Android (Wi-Fi)..."

            sendTask = Task { [weak self] in
                let result = await AndroidClipboardHTTPClient.send(
                    content: change.content,
                    to: endpoint,
                    token: token
                )

                await MainActor.run {
                    self?.handleSendResult(result, for: change, endpoint: endpoint)
                }
            }
            return
        }

        // Priority 2: Fallback to Bluetooth if connected
        if MacBluetoothService.shared.isConnected, case .text(let text) = change.content {
            let sent = MacBluetoothService.shared.sendBluetoothMessage(path: "/clipboard", body: text)
            if sent {
                pendingChange = nil
                lastSentText = text
                lastSentAt = Date()
                activeTransportMode = .bluetooth
                status = "Sent clipboard via Bluetooth fallback."
                return
            }
        }

        // Otherwise start discovery
        discoverAndroidReceiver()
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
        for change: MacClipboardChange,
        endpoint: AndroidReceiverEndpoint
    ) {
        sendTask = nil
        isSending = false

        switch result {
        case .success(let confirmedDeviceId):
            retriedChangeIDs.remove(change.id)
            if case .text(let text) = change.content {
                lastSentText = text
            }
            lastSentAt = Date()
            resolvedEndpoint = endpoint
            activeTransportMode = .wifi
            saveConfirmedAndroidIdentity(
                confirmedDeviceId ?? endpoint.deviceId,
                transferCertificateSHA256: endpoint.transferCertificateSHA256
            )
            switch change.content {
            case .text:
                status = "Sent \(change.content.byteCount) UTF-8 bytes to Android."
            case .pngImage:
                status = "Sent \(change.content.byteCount) bytes of image data to Android."
            case .file(_, let fileName, _):
                status = "Sent clipboard file \(fileName) to Android."
            }

        case .failure(let message, let isRetryable):
            if MacBluetoothService.shared.isConnected, case .text(let text) = change.content {
                let sent = MacBluetoothService.shared.sendBluetoothMessage(path: "/clipboard", body: text)
                if sent {
                    retriedChangeIDs.remove(change.id)
                    lastSentText = text
                    lastSentAt = Date()
                    activeTransportMode = .bluetooth
                    resolvedEndpoint = nil
                    status = "Sent clipboard via Bluetooth fallback (Wi-Fi failed)."
                    return
                }
            }

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
            activeTransportMode = .wifi
            saveConfirmedAndroidIdentity(
                confirmedDeviceId ?? endpoint.deviceId,
                transferCertificateSHA256: endpoint.transferCertificateSHA256
            )

        case .failure(let message, let isRetryable):
            status = message
            if isRetryable {
                resolvedEndpoint = nil
                activeTransportMode = MacBluetoothService.shared.isConnected ? .bluetooth : .disconnected
                discoverAndroidReceiver()
            }
        }
    }

    private func saveConfirmedAndroidIdentity(
        _ deviceId: String?,
        transferCertificateSHA256: String?
    ) {
        guard let deviceId else { return }
        AndroidReceiverIdentityStore.savePairedDeviceId(deviceId)
        if let transferCertificateSHA256 {
            try? FileTransferPeerPinStore.savePinnedCertificateSHA256(
                transferCertificateSHA256,
                peerDeviceId: deviceId
            )
        }
        pairedDeviceId = AndroidReceiverIdentityStore.pairedDeviceId()
    }

    func tryConnectToPhoneIps(_ ips: [String], port: Int) {
        let token = tokenProvider().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { return }

        Task { [weak self] in
            for ip in ips {
                let endpoint = AndroidReceiverEndpoint(
                    name: "ZevLink Android Receiver",
                    deviceId: self?.pairedDeviceId,
                    batteryPercentage: nil,
                    findPhoneRinging: nil,
                    transferCertificateSHA256: nil,
                    host: ip,
                    port: port
                )
                let result = await AndroidClipboardHTTPClient.fetchStatus(from: endpoint, token: token)
                if case .success = result {
                    await MainActor.run {
                        self?.updateEndpointSeenFromAndroid(endpoint)
                    }
                    break
                }
            }
        }
    }

    private static let maximumTextBodyLength = 1_048_576
    private static let maximumImageBodyLength = 20 * 1_024 * 1_024
    private static let statusRefreshInterval: TimeInterval = 2
    private static let statusRefreshTolerance: TimeInterval = 0.5
}

private enum AndroidClipboardHTTPClient {
    private static let statusSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 1.0
        config.timeoutIntervalForResource = 1.0
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()
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

    enum IntentBroadcastResult {
        case success(IntentBroadcastAcknowledgement)
        case failure(IntentBroadcastAcknowledgement)
    }

    static func sendIntentBroadcast(
        requestData: Data,
        on endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> IntentBroadcastResult {
        guard let url = url(for: endpoint, path: "/intent-broadcast") else {
            return .failure(
                IntentBroadcastAcknowledgement(
                    requestId: "",
                    status: "invalid_request",
                    success: false,
                    message: "Android Intent receiver URL is invalid."
                )
            )
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        applyMacBatteryHeaders(to: &request)
        request.httpBody = requestData

        do {
            let (responseData, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure(
                    IntentBroadcastAcknowledgement(
                        requestId: "",
                        status: "invalid_response",
                        success: false,
                        message: "Android returned an invalid Intent response."
                    )
                )
            }

            let responsePreview = String(data: responseData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let acknowledgement = (try? JSONDecoder().decode(
                IntentBroadcastAcknowledgement.self,
                from: responseData
            )) ?? IntentBroadcastAcknowledgement(
                requestId: "",
                status: "invalid_response",
                success: false,
                message: responsePreview?.isEmpty == false ? responsePreview! :
                    "Android returned an invalid Intent response."
            )

            if httpResponse.statusCode == 200, acknowledgement.success {
                return .success(acknowledgement)
            }
            return .failure(acknowledgement)
        } catch {
            return .failure(
                IntentBroadcastAcknowledgement(
                    requestId: "",
                    status: "timeout_or_transport_error",
                    success: false,
                    message: "Could not deliver the Intent request to Android: \(error.localizedDescription)"
                )
            )
        }
    }

    static func send(
        content: MacClipboardContent,
        to endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> Result {
        let path: String
        let contentType: String
        let body: Data
        switch content {
        case .text(let text):
            path = "/clipboard"
            contentType = "text/plain; charset=utf-8"
            body = Data(text.utf8)
        case .pngImage(let data):
            path = "/clipboard-image"
            contentType = "image/png"
            body = data
        case .file(let data, _, let mimeType):
            path = "/clipboard-file"
            contentType = mimeType
            body = data
        }

        guard let url = url(for: endpoint, path: path) else {
            return .failure("Android receiver URL is invalid.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        if case .file(_, let fileName, _) = content {
            request.setValue(
                Data(fileName.utf8).base64EncodedString(),
                forHTTPHeaderField: "X-ZevClip-File-Name-Base64"
            )
        }
        applyMacBatteryHeaders(to: &request)
        request.httpBody = body

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
        request.timeoutInterval = 1.0
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        applyMacBatteryHeaders(to: &request)

        do {
            let (body, response) = try await statusSession.data(for: request)
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

    static func performNotificationAction(
        notificationKey: String,
        actionId: String,
        replyText: String?,
        on endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> Result {
        guard let url = notificationActionURL(for: endpoint) else {
            return .failure("Android notification action URL is invalid.", isRetryable: false)
        }

        var body: [String: String] = [
            "action": "perform",
            "notificationKey": notificationKey,
            "actionId": actionId
        ]
        if let replyText {
            body["replyText"] = replyText
        }

        guard let bodyData = try? JSONSerialization.data(withJSONObject: body) else {
            return .failure("Could not encode Android notification action.", isRetryable: false)
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
                "Could not perform Android notification action: \(error.localizedDescription)",
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

    static func findMyPhone(
        action: String,
        on endpoint: AndroidReceiverEndpoint,
        token: String
    ) async -> CallActionResult {
        guard let url = findPhoneURL(for: endpoint) else {
            return .failure("Android Find My Phone URL is invalid.", isRetryable: false)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue(token, forHTTPHeaderField: "X-ZevClip-Token")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        applyMacBatteryHeaders(to: &request)
        guard let requestBody = try? JSONSerialization.data(withJSONObject: ["action": action]) else {
            return .failure("Could not encode Find My Phone action.", isRetryable: false)
        }
        request.httpBody = requestBody

        do {
            let (responseBody, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return .failure("Find My Phone returned an invalid response.", isRetryable: true)
            }

            let bodyText = String(data: responseBody, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard httpResponse.statusCode == 200 else {
                let detail = bodyText?.isEmpty == false ? " \(bodyText!)" : ""
                return .failure(
                    "Find My Phone failed (\(httpResponse.statusCode)).\(detail)",
                    isRetryable: isRetryableStatusCode(httpResponse.statusCode)
                )
            }

            return .success(bodyText?.isEmpty == false ? bodyText! : "Phone is ringing.")
        } catch {
            return .failure(
                "Could not ring Android phone: \(error.localizedDescription)",
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

    private static func findPhoneURL(for endpoint: AndroidReceiverEndpoint) -> URL? {
        url(for: endpoint, path: "/find-phone")
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
