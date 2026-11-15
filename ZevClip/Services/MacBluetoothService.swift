import CoreBluetooth
import Foundation
import IOBluetooth

@MainActor
final class MacBluetoothService: NSObject, ObservableObject {
    static let shared = MacBluetoothService()

    static let serviceUUID = CBUUID(string: "17C17210-9DF8-4C8E-A361-9EE85B9E7801")
    static let rxCharacteristicUUID = CBUUID(string: "17C17211-9DF8-4C8E-A361-9EE85B9E7801")
    static let txCharacteristicUUID = CBUUID(string: "17C17212-9DF8-4C8E-A361-9EE85B9E7801")

    @Published private(set) var statusText: String = "Bluetooth initializing..."
    @Published private(set) var isAdvertising: Bool = false
    @Published private(set) var isConnected: Bool = false
    @Published private(set) var phoneBatteryPercentage: Int?
    @Published private(set) var isPhoneRinging: Bool = false
    @Published private(set) var lastReceivedText: String?
    @Published private(set) var lastReceivedAt: Date?
    @Published private(set) var lastSentText: String?
    @Published private(set) var lastSentAt: Date?

    private static var cachedBluetoothAddress: String?

    static func localBluetoothAddress() -> String? {
        if let cached = cachedBluetoothAddress {
            return cached
        }

        if let controller = IOBluetoothHostController.default(),
           let raw = controller.addressAsString(),
           !raw.isEmpty {
            let formatted = raw.replacingOccurrences(of: "-", with: ":").uppercased()
            if formatted.count == 17 {
                cachedBluetoothAddress = formatted
                return formatted
            }
        }

        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/sbin/system_profiler")
        task.arguments = ["SPBluetoothDataType"]
        let pipe = Pipe()
        task.standardOutput = pipe
        do {
            try task.run()
            task.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8) {
                let pattern = #"Address:\s*([0-9A-Fa-f]{2}[:-][0-9A-Fa-f]{2}[:-][0-9A-Fa-f]{2}[:-][0-9A-Fa-f]{2}[:-][0-9A-Fa-f]{2}[:-][0-9A-Fa-f]{2})"#
                if let regex = try? NSRegularExpression(pattern: pattern),
                   let match = regex.firstMatch(in: output, range: NSRange(output.startIndex..., in: output)),
                   let range = Range(match.range(at: 1), in: output) {
                    let formatted = String(output[range]).replacingOccurrences(of: "-", with: ":").uppercased()
                    cachedBluetoothAddress = formatted
                    return formatted
                }
            }
        } catch {}
        return nil
    }

    var bluetoothAddress: String? {
        Self.localBluetoothAddress()
    }

    var tokenProvider: (() -> String)?
    var onClipboardReceived: ((String) -> Void)?
    var onNotificationReceived: ((Data) -> Void)?
    var onCallReceived: ((Data) -> Void)?
    var onRemoteReceived: ((Data) -> Void)?
    var onIntentBroadcastAcknowledgement: ((Data) -> Void)?
    var onPresenceReceived: (([String: Any]) -> Void)?
    var onPhoneIpsDiscovered: (([String], Int) -> Void)?
    var onTransportModeReceived: ((String) -> Void)?
    var onDisconnectReceived: (() -> Void)?

    private var peripheralManager: CBPeripheralManager?
    private var rxCharacteristic: CBMutableCharacteristic?
    private var txCharacteristic: CBMutableCharacteristic?
    private var subscribedCentrals = Set<CBCentral>()
    private var incomingBuffer = Data()
    private var pendingOutgoingChunks: [Data] = []
    private var advertisingWatchdogTimer: Timer?
    private var lastStatusRequestAt: Date?

    private static let maximumFramePayload = 1_048_576
    private static let batteryRefreshInterval: TimeInterval = 5

    override init() {
        super.init()
        NSLog("[MacBluetoothService] Authorization status: %ld", CBManager.authorization.rawValue)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            guard let self else { return }
            self.peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
            self.startWatchdog()
        }
    }

    func startAdvertisingIfNeeded() {
        guard let peripheralManager, peripheralManager.state == .poweredOn else { return }
        setupServiceAndCharacteristics()
        if !peripheralManager.isAdvertising && subscribedCentrals.isEmpty {
            startAdvertising()
        }
    }

    private func setupServiceAndCharacteristics() {
        guard let peripheralManager, peripheralManager.state == .poweredOn else { return }

        if rxCharacteristic == nil || txCharacteristic == nil {
            peripheralManager.removeAllServices()

            let rx = CBMutableCharacteristic(
                type: Self.rxCharacteristicUUID,
                properties: [.write, .writeWithoutResponse],
                value: nil,
                permissions: [.writeable]
            )

            let tx = CBMutableCharacteristic(
                type: Self.txCharacteristicUUID,
                properties: [.notify, .read],
                value: nil,
                permissions: [.readable]
            )

            let service = CBMutableService(type: Self.serviceUUID, primary: true)
            service.characteristics = [rx, tx]

            self.rxCharacteristic = rx
            self.txCharacteristic = tx

            peripheralManager.add(service)
        } else if !peripheralManager.isAdvertising && subscribedCentrals.isEmpty {
            startAdvertising()
        }
    }

    func startAdvertising() {
        guard let peripheralManager, peripheralManager.state == .poweredOn else { return }
        if peripheralManager.isAdvertising { return }
        let deviceId = DeviceIdentityStore.loadOrCreateDeviceId()
        let prefix = String(deviceId.prefix(8))
        let localName = "ZevLink:\(prefix)"
        let advertisementData: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: localName
        ]
        peripheralManager.startAdvertising(advertisementData)
        isAdvertising = true
        statusText = "Advertising as \(localName): Ready for phone"
    }

    func restartAdvertising() {
        guard let peripheralManager, peripheralManager.state == .poweredOn else { return }
        if peripheralManager.isAdvertising {
            peripheralManager.stopAdvertising()
        }
        isAdvertising = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            self?.startAdvertising()
        }
    }

    private func startWatchdog() {
        advertisingWatchdogTimer?.invalidate()
        advertisingWatchdogTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                guard self.peripheralManager?.state == .poweredOn else { return }

                // If not connected or no centrals, ensure advertising is active
                if !self.isConnected || self.subscribedCentrals.isEmpty {
                    if self.isConnected && self.subscribedCentrals.isEmpty {
                        self.isConnected = false
                        self.phoneBatteryPercentage = nil
                        self.statusText = "Advertising: Ready for phone"
                    }
                    if !(self.peripheralManager?.isAdvertising ?? false) {
                        self.startAdvertising()
                    }
                } else if self.isConnected {
                    // Keep Bluetooth battery updates close to the Wi-Fi status refresh rate.
                    let now = Date()
                    if self.lastStatusRequestAt == nil || now.timeIntervalSince(self.lastStatusRequestAt!) >= Self.batteryRefreshInterval {
                        self.lastStatusRequestAt = now
                        self.sendBluetoothMessage(path: "/status-request")
                    }
                }
            }
        }
    }

    func sendTestPing() {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let timestamp = formatter.string(from: Date())
        let pingText = "Hello from Mac via Bluetooth! [\(timestamp)]"
        _ = sendBluetoothMessage(path: "/ping", body: pingText)
    }

    private func requestPhoneStatusWithRetries() {
        let retryDelays: [TimeInterval] = [0.1, 0.5, 1.5, 3.0]
        for delay in retryDelays {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, self.isConnected else { return }
                self.lastStatusRequestAt = Date()
                _ = self.sendBluetoothMessage(path: "/status-request")
            }
        }
    }

    @discardableResult
    func sendBluetoothMessage(
        path: String,
        body: String? = nil,
        action: String? = nil,
        callId: String? = nil,
        notificationKey: String? = nil,
        actionId: String? = nil,
        replyText: String? = nil
    ) -> Bool {
        guard txCharacteristic != nil, !subscribedCentrals.isEmpty else {
            statusText = "Cannot send: No phone connected via Bluetooth"
            return false
        }

        guard let token = tokenProvider?().trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty else {
            statusText = "Cannot send: Pairing token is unavailable"
            return false
        }

        var dict: [String: Any] = ["path": path]
        dict["token"] = token
        dict["macIps"] = LocalNetworkHost.currentPairingHosts()
        dict["macPort"] = Int(ClipboardReceiver.port)
        let battery = MacBatteryStatus.current()
        if let percentage = battery.percentage {
            dict["macBattery"] = percentage
        }
        if let isCharging = battery.isCharging {
            dict["macCharging"] = isCharging
        }
        if let body { dict["body"] = body }
        if let action { dict["action"] = action }
        if let callId { dict["callId"] = callId }
        if let notificationKey { dict["notificationKey"] = notificationKey }
        if let actionId { dict["actionId"] = actionId }
        if let replyText { dict["replyText"] = replyText }

        guard let jsonData = try? JSONSerialization.data(withJSONObject: dict),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            return false
        }

        sendMessage(jsonString)
        return true
    }

    func sendMessage(_ text: String) {
        guard let payloadData = text.data(using: .utf8) else { return }
        guard !subscribedCentrals.isEmpty, txCharacteristic != nil else {
            statusText = "Cannot send: No phone connected via Bluetooth"
            return
        }
        guard payloadData.count <= Self.maximumFramePayload else {
            statusText = "Bluetooth message is too large to send"
            return
        }

        // Frame: 4-byte big-endian UInt32 length + payload
        var frame = Data()
        var length = UInt32(payloadData.count).bigEndian
        frame.append(Data(bytes: &length, count: MemoryLayout<UInt32>.size))
        frame.append(payloadData)

        // Split frame into chunks (max 180 bytes)
        let chunkSize = 180
        var offset = 0
        while offset < frame.count {
            let chunkLength = min(chunkSize, frame.count - offset)
            let chunk = frame.subdata(in: offset..<(offset + chunkLength))
            pendingOutgoingChunks.append(chunk)
            offset += chunkLength
        }

        lastSentText = text
        lastSentAt = Date()
        flushOutgoingChunks()
    }

    private func flushOutgoingChunks() {
        guard let txCharacteristic, let peripheralManager else { return }
        while !pendingOutgoingChunks.isEmpty {
            let chunk = pendingOutgoingChunks[0]
            let success = peripheralManager.updateValue(chunk, for: txCharacteristic, onSubscribedCentrals: nil)
            if success {
                pendingOutgoingChunks.removeFirst()
            } else {
                // Buffer is full, wait for peripheralManagerIsReady(toUpdateSubscribers:)
                break
            }
        }
    }

    private func handleIncomingData(_ data: Data) {
        incomingBuffer.append(data)

        while incomingBuffer.count >= 4 {
            let length = incomingBuffer.prefix(4).reduce(UInt32(0)) { partial, byte in
                (partial << 8) | UInt32(byte)
            }

            guard length <= Self.maximumFramePayload else {
                statusText = "Rejected oversized Bluetooth frame."
                resetSessionState()
                return
            }

            let totalExpectedSize = 4 + Int(length)
            if incomingBuffer.count >= totalExpectedSize {
                let payloadData = incomingBuffer.subdata(in: 4..<totalExpectedSize)
                incomingBuffer.removeSubrange(0..<totalExpectedSize)

                if let messageString = String(data: payloadData, encoding: .utf8) {
                    processReceivedMessage(messageString)
                }
            } else {
                break
            }
        }
    }

    private func processReceivedMessage(_ message: String) {
        lastReceivedText = message
        lastReceivedAt = Date()

        guard let data = message.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            statusText = "Received message from Android: \(message.prefix(30))..."
            return
        }

        guard let expectedToken = tokenProvider?().trimmingCharacters(in: .whitespacesAndNewlines), !expectedToken.isEmpty else {
            statusText = "Cannot verify Bluetooth message: pairing token not configured"
            return
        }

        let receivedToken = (json["token"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !receivedToken.isEmpty, receivedToken == expectedToken else {
            statusText = "Rejected unauthenticated Bluetooth message: token mismatch"
            return
        }

        // Treat missing/invalid battery data as unavailable instead of keeping
        // a previous percentage on screen.
        phoneBatteryPercentage = (json["battery"] as? Int).flatMap { battery in
            (0...100).contains(battery) ? battery : nil
        }

        if let transportMode = json["transportMode"] as? String {
            onTransportModeReceived?(transportMode)
        }

        // Check Phone Wi-Fi / Hotspot IPs shared via BLE
        if let phoneIps = json["phoneIps"] as? [String], let phonePort = json["phonePort"] as? Int, !phoneIps.isEmpty {
            onPhoneIpsDiscovered?(phoneIps, phonePort)
        }

        let path = json["path"] as? String ?? ""
        statusText = "Received \(path) via Bluetooth"

        switch path {
        case "/clipboard":
            if let body = json["body"] as? String {
                onClipboardReceived?(body)
            }
        case "/android-notification":
            if let body = json["body"] as? String, let bodyData = body.data(using: .utf8) {
                onNotificationReceived?(bodyData)
            }
        case "/android-call":
            if let body = json["body"] as? String, let bodyData = body.data(using: .utf8) {
                onCallReceived?(bodyData)
            }
        case "/android-presence":
            if let ringing = json["isRinging"] as? Bool {
                isPhoneRinging = ringing
            }
            onPresenceReceived?(json)
        case "/remote":
            if let body = json["body"] as? String, let bodyData = body.data(using: .utf8) {
                guard AppSettings.savedRemoteControlEnabled() else {
                    statusText = "Ignored remote command: remote control disabled in settings"
                    return
                }
                _ = onRemoteReceived?(bodyData)
            }
        case "/intent-broadcast-ack":
            if let body = json["body"] as? String, let bodyData = body.data(using: .utf8) {
                onIntentBroadcastAcknowledgement?(bodyData)
            }
        case "/ping":
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss"
            let timestamp = formatter.string(from: Date())
            let echoResponse = "Echo from Mac: \"\(json["body"] as? String ?? "ping")\" [\(timestamp)]"
            _ = sendBluetoothMessage(path: "/ping", body: echoResponse)
        case "/disconnect":
            subscribedCentrals.removeAll()
            resetSessionState()
            isConnected = false
            phoneBatteryPercentage = nil
            statusText = "Advertising: Phone stopped sync"
            onDisconnectReceived?()
            restartAdvertising()
        default:
            break
        }
    }

    private func resetSessionState() {
        incomingBuffer.removeAll(keepingCapacity: false)
        pendingOutgoingChunks.removeAll(keepingCapacity: false)
        lastStatusRequestAt = nil
    }
}

extension MacBluetoothService: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            NSLog("[MacBluetoothService] peripheralManagerDidUpdateState: %ld", peripheral.state.rawValue)
            switch peripheral.state {
            case .poweredOn:
                statusText = "Bluetooth Powered On"
                setupServiceAndCharacteristics()
            case .poweredOff:
                statusText = "Bluetooth is turned off"
                resetSessionState()
                isAdvertising = false
                isConnected = false
                phoneBatteryPercentage = nil
            case .unauthorized:
                statusText = "Bluetooth access unauthorized"
                resetSessionState()
                isAdvertising = false
                isConnected = false
                phoneBatteryPercentage = nil
            case .unsupported:
                statusText = "Bluetooth unsupported on this Mac"
                resetSessionState()
                isAdvertising = false
                isConnected = false
                phoneBatteryPercentage = nil
            default:
                statusText = "Bluetooth state: \(peripheral.state.rawValue)"
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: (any Error)?) {
        Task { @MainActor in
            if let error {
                NSLog("[MacBluetoothService] didAdd service error: %@", error.localizedDescription)
                statusText = "Error adding Bluetooth service: \(error.localizedDescription)"
            } else {
                NSLog("[MacBluetoothService] didAdd service succeeded. Starting advertising...")
                startAdvertising()
            }
        }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: (any Error)?) {
        Task { @MainActor in
            if let error {
                isAdvertising = false
                NSLog("[MacBluetoothService] didStartAdvertising failed: %@", error.localizedDescription)
                statusText = "Advertising failed: \(error.localizedDescription)"
            } else {
                let deviceId = DeviceIdentityStore.loadOrCreateDeviceId()
                let prefix = String(deviceId.prefix(8))
                isAdvertising = true
                NSLog("[MacBluetoothService] didStartAdvertising SUCCESS: ZevLink:%@", prefix)
                statusText = "Advertising as ZevLink:\(prefix)"
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        Task { @MainActor in
            guard characteristic.uuid == Self.txCharacteristicUUID else { return }
            NSLog("[MacBluetoothService] central subscribed: %@", central.identifier.uuidString)
            resetSessionState()
            subscribedCentrals.insert(central)
            isConnected = true
            statusText = "Connected to Phone via Bluetooth"
            // Request immediate status/battery from phone
            requestPhoneStatusWithRetries()
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        Task { @MainActor in
            guard characteristic.uuid == Self.txCharacteristicUUID else { return }
            subscribedCentrals.remove(central)
            if subscribedCentrals.isEmpty {
                resetSessionState()
                isConnected = false
                phoneBatteryPercentage = nil
                statusText = "Advertising: Ready for phone"
                onDisconnectReceived?()
                startAdvertising()
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        Task { @MainActor in
            for request in requests {
                guard request.characteristic.uuid == Self.rxCharacteristicUUID else {
                    peripheral.respond(to: request, withResult: .writeNotPermitted)
                    continue
                }
                if !self.subscribedCentrals.contains(request.central) {
                    self.subscribedCentrals.insert(request.central)
                    self.requestPhoneStatusWithRetries()
                }
                self.isConnected = true
                self.statusText = "Connected to Phone via Bluetooth"

                if let value = request.value {
                    self.handleIncomingData(value)
                }
                peripheral.respond(to: request, withResult: .success)
            }
        }
    }

    nonisolated func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        Task { @MainActor in
            flushOutgoingChunks()
        }
    }
}
