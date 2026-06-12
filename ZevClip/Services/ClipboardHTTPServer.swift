import Foundation
import Network

final class ClipboardHTTPServer {
    private let queue = DispatchQueue(label: "com.zevlink.receiver.http-server")
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: HTTPConnection] = [:]

    func start(
        port: UInt16,
        serviceName: String,
        serviceType: String,
        deviceId: String,
        tokenProvider: @escaping () -> String,
        onReady: @escaping () -> Void,
        onAdvertisingChanged: @escaping (Bool) -> Void,
        onFailure: @escaping (String) -> Void,
        onAndroidEndpointSeen: @escaping (AndroidReceiverEndpoint) -> Void,
        onText: @escaping (String) -> Void,
        onAndroidNotification: @escaping (Data) -> Void,
        onAndroidCall: @escaping (Data) -> Void,
        onAndroidNowPlaying: @escaping (Data) -> Void
    ) {
        queue.async { [weak self] in
            self?.startOnQueue(
                port: port,
                serviceName: serviceName,
                serviceType: serviceType,
                deviceId: deviceId,
                tokenProvider: tokenProvider,
                onReady: onReady,
                onAdvertisingChanged: onAdvertisingChanged,
                onFailure: onFailure,
                onAndroidEndpointSeen: onAndroidEndpointSeen,
                onText: onText,
                onAndroidNotification: onAndroidNotification,
                onAndroidCall: onAndroidCall,
                onAndroidNowPlaying: onAndroidNowPlaying
            )
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }

            self.listener?.cancel()
            self.listener = nil

            let activeConnections = Array(self.connections.values)
            self.connections.removeAll()
            activeConnections.forEach { $0.cancel() }
        }
    }

    private func startOnQueue(
        port: UInt16,
        serviceName: String,
        serviceType: String,
        deviceId: String,
        tokenProvider: @escaping () -> String,
        onReady: @escaping () -> Void,
        onAdvertisingChanged: @escaping (Bool) -> Void,
        onFailure: @escaping (String) -> Void,
        onAndroidEndpointSeen: @escaping (AndroidReceiverEndpoint) -> Void,
        onText: @escaping (String) -> Void,
        onAndroidNotification: @escaping (Data) -> Void,
        onAndroidCall: @escaping (Data) -> Void,
        onAndroidNowPlaying: @escaping (Data) -> Void
    ) {
        guard listener == nil else { return }
        guard let networkPort = NWEndpoint.Port(rawValue: port) else {
            onFailure("Port \(port) is invalid.")
            return
        }

        do {
            let newListener = try NWListener(using: .tcp, on: networkPort)
            let txtRecord = NWTXTRecord(["deviceId": deviceId])
            newListener.service = NWListener.Service(
                name: serviceName,
                type: serviceType,
                txtRecord: txtRecord
            )
            listener = newListener

            newListener.serviceRegistrationUpdateHandler = { [weak self, weak newListener] change in
                guard let self, let newListener, self.listener === newListener else {
                    return
                }

                switch change {
                case .add:
                    onAdvertisingChanged(true)
                case .remove:
                    onAdvertisingChanged(false)
                @unknown default:
                    break
                }
            }

            newListener.stateUpdateHandler = { [weak self, weak newListener] state in
                guard let self, let newListener, self.listener === newListener else {
                    return
                }

                switch state {
                case .ready:
                    onReady()
                case .failed(let error):
                    self.listener = nil
                    newListener.cancel()
                    onAdvertisingChanged(false)
                    let activeConnections = Array(self.connections.values)
                    self.connections.removeAll()
                    activeConnections.forEach { $0.cancel() }
                    onFailure(Self.userMessage(for: error, port: port))
                default:
                    break
                }
            }

            newListener.newConnectionHandler = { [weak self] connection in
                self?.accept(
                    connection,
                    tokenProvider: tokenProvider,
                    onAndroidEndpointSeen: onAndroidEndpointSeen,
                    onText: onText,
                    onAndroidNotification: onAndroidNotification,
                    onAndroidCall: onAndroidCall,
                    onAndroidNowPlaying: onAndroidNowPlaying
                )
            }

            newListener.start(queue: queue)
        } catch {
            listener = nil
            onAdvertisingChanged(false)
            onFailure("Could not start the server: \(error.localizedDescription)")
        }
    }

    private func accept(
        _ connection: NWConnection,
        tokenProvider: @escaping () -> String,
        onAndroidEndpointSeen: @escaping (AndroidReceiverEndpoint) -> Void,
        onText: @escaping (String) -> Void,
        onAndroidNotification: @escaping (Data) -> Void,
        onAndroidCall: @escaping (Data) -> Void,
        onAndroidNowPlaying: @escaping (Data) -> Void
    ) {
        let id = ObjectIdentifier(connection)
        let httpConnection = HTTPConnection(
            connection: connection,
            queue: queue,
            tokenProvider: tokenProvider,
            onAndroidEndpointSeen: onAndroidEndpointSeen,
            onText: onText,
            onAndroidNotification: onAndroidNotification,
            onAndroidCall: onAndroidCall,
            onAndroidNowPlaying: onAndroidNowPlaying,
            onClose: { [weak self] in
                self?.connections[id] = nil
            }
        )

        connections[id] = httpConnection
        httpConnection.start()
    }

    private static func userMessage(for error: NWError, port: UInt16) -> String {
        if case .posix(let code) = error, code == .EADDRINUSE {
            return "Port \(port) is already in use. Stop the other service or choose a different port."
        }

        return "Server stopped: \(error.localizedDescription)"
    }
}

private final class HTTPConnection {
    private static let maximumBodyLength = 1_048_576
    private static let maximumHeaderLength = 16_384

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let tokenProvider: () -> String
    private let onAndroidEndpointSeen: (AndroidReceiverEndpoint) -> Void
    private let onText: (String) -> Void
    private let onAndroidNotification: (Data) -> Void
    private let onAndroidCall: (Data) -> Void
    private let onAndroidNowPlaying: (Data) -> Void
    private let onClose: () -> Void

    private var buffer = Data()
    private var expectedRequestLength: Int?
    private var headerEndIndex: Int?
    private var requestPath: String?
    private var responseStarted = false
    private var didFinish = false

    init(
        connection: NWConnection,
        queue: DispatchQueue,
        tokenProvider: @escaping () -> String,
        onAndroidEndpointSeen: @escaping (AndroidReceiverEndpoint) -> Void,
        onText: @escaping (String) -> Void,
        onAndroidNotification: @escaping (Data) -> Void,
        onAndroidCall: @escaping (Data) -> Void,
        onAndroidNowPlaying: @escaping (Data) -> Void,
        onClose: @escaping () -> Void
    ) {
        self.connection = connection
        self.queue = queue
        self.tokenProvider = tokenProvider
        self.onAndroidEndpointSeen = onAndroidEndpointSeen
        self.onText = onText
        self.onAndroidNotification = onAndroidNotification
        self.onAndroidCall = onAndroidCall
        self.onAndroidNowPlaying = onAndroidNowPlaying
        self.onClose = onClose
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.finish()
            default:
                break
            }
        }

        connection.start(queue: queue)
        receiveMoreData()
    }

    func cancel() {
        connection.cancel()
        finish()
    }

    private func receiveMoreData() {
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: 64 * 1024
        ) { [weak self] data, _, isComplete, error in
            guard let self, !self.didFinish else { return }

            if let data, !data.isEmpty {
                self.buffer.append(data)
            }

            if error != nil {
                self.finish()
                return
            }

            if self.processRequestIfPossible() {
                return
            }

            if isComplete {
                self.respond(status: "400 Bad Request", body: "Incomplete HTTP request.")
            } else {
                self.receiveMoreData()
            }
        }
    }

    @discardableResult
    private func processRequestIfPossible() -> Bool {
        if expectedRequestLength == nil {
            guard parseHeadersIfAvailable() else {
                if buffer.count > Self.maximumHeaderLength {
                    respond(status: "431 Request Header Fields Too Large", body: "Headers are too large.")
                    return true
                }
                return false
            }

            if responseStarted {
                return true
            }
        }

        guard
            let expectedRequestLength,
            let headerEndIndex,
            buffer.count >= expectedRequestLength
        else {
            return false
        }

        let bodyData = buffer.subdata(in: headerEndIndex..<expectedRequestLength)
        if let endpoint = androidEndpointSeenInRequest() {
            onAndroidEndpointSeen(endpoint)
        }

        switch requestPath {
        case "/clipboard":
            guard let text = String(data: bodyData, encoding: .utf8) else {
                respond(status: "400 Bad Request", body: "Request body must be valid UTF-8 text.")
                return true
            }

            onText(text)
            respond(status: "200 OK", body: "Clipboard updated.")

        case "/android-notification":
            onAndroidNotification(bodyData)
            respond(status: "200 OK", body: "Notification mirrored.")

        case "/android-call":
            onAndroidCall(bodyData)
            respond(status: "200 OK", body: "Android call mirrored.")

        case "/android-now-playing":
            onAndroidNowPlaying(bodyData)
            respond(status: "200 OK", body: "Android now playing updated.")

        case "/android-presence":
            respond(status: "200 OK", body: "Android presence updated.")

        default:
            respond(status: "404 Not Found", body: "Unknown path.")
        }
        return true
    }

    private func parseHeadersIfAvailable() -> Bool {
        let separator = Data("\r\n\r\n".utf8)
        guard let separatorRange = buffer.range(of: separator) else {
            return false
        }

        guard separatorRange.lowerBound <= Self.maximumHeaderLength else {
            respond(status: "431 Request Header Fields Too Large", body: "Headers are too large.")
            return true
        }

        let headerData = buffer.subdata(in: 0..<separatorRange.lowerBound)
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            respond(status: "400 Bad Request", body: "Headers must be valid UTF-8.")
            return true
        }

        let lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            respond(status: "400 Bad Request", body: "Missing request line.")
            return true
        }

        let requestParts = requestLine.split(separator: " ")
        guard requestParts.count == 3 else {
            respond(status: "400 Bad Request", body: "Invalid request line.")
            return true
        }

        guard requestParts[0] == "POST" else {
            respond(status: "405 Method Not Allowed", body: "Use POST.")
            return true
        }

        let path = String(requestParts[1])
        guard path == "/clipboard" ||
            path == "/android-notification" ||
            path == "/android-call" ||
            path == "/android-now-playing" ||
            path == "/android-presence"
        else {
            respond(status: "404 Not Found", body: "Unknown path.")
            return true
        }

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }

        let expectedToken = tokenProvider()
        let providedToken = headers["x-zevclip-token"]
        guard !expectedToken.isEmpty, providedToken == expectedToken else {
            respond(status: "401 Unauthorized", body: "Missing or invalid ZevLink pairing token.")
            return true
        }

        headerEndIndex = separatorRange.upperBound
        requestPath = path

        guard let contentLengthValue = headers["content-length"] else {
            respond(status: "411 Length Required", body: "Content-Length is required.")
            return true
        }

        guard let contentLength = Int(contentLengthValue), contentLength >= 0 else {
            respond(status: "400 Bad Request", body: "Content-Length is invalid.")
            return true
        }

        guard contentLength <= Self.maximumBodyLength else {
            respond(status: "413 Content Too Large", body: "Clipboard text is limited to 1 MB.")
            return true
        }

        expectedRequestLength = separatorRange.upperBound + contentLength
        requestHeaders = headers
        return true
    }

    private var requestHeaders: [String: String] = [:]

    private func androidEndpointSeenInRequest() -> AndroidReceiverEndpoint? {
        guard
            let host = peerHost,
            let portText = requestHeaders["x-zevclip-android-receiver-port"],
            let port = Int(portText),
            (1...65535).contains(port)
        else {
            return nil
        }

        let deviceId = requestHeaders["x-zevclip-android-device-id"]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .nonEmpty
        let name = requestHeaders["x-zevclip-android-receiver-name"]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty ?? "ZevLink Android Receiver"
        let batteryPercentage = requestHeaders["x-zevclip-android-battery"]
            .flatMap(Int.init)
            .flatMap { (0...100).contains($0) ? $0 : nil }

        return AndroidReceiverEndpoint(
            name: name,
            deviceId: deviceId,
            batteryPercentage: batteryPercentage,
            host: host,
            port: port
        )
    }

    private var peerHost: String? {
        guard case .hostPort(let host, _) = connection.endpoint else {
            return nil
        }

        return String(describing: host)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty
    }

    private func respond(status: String, body: String) {
        guard !responseStarted else { return }
        responseStarted = true

        let bodyData = Data(body.utf8)
        let header = [
            "HTTP/1.1 \(status)",
            "Content-Type: text/plain; charset=utf-8",
            "Content-Length: \(bodyData.count)",
            "Connection: close",
            "",
            ""
        ].joined(separator: "\r\n")

        var response = Data(header.utf8)
        response.append(bodyData)

        connection.send(content: response, completion: .contentProcessed { [weak self] _ in
            self?.connection.cancel()
            self?.finish()
        })
    }

    private func finish() {
        guard !didFinish else { return }
        didFinish = true
        connection.stateUpdateHandler = nil
        onClose()
    }
}

private extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }
}
