import Darwin
import Foundation

struct AndroidReceiverEndpoint: Equatable {
    let name: String
    let deviceId: String?
    let batteryPercentage: Int?
    let host: String
    let port: Int

    var displayAddress: String {
        let displayHost = host.contains(":") ? "[\(host)]" : host
        return "\(displayHost):\(port)"
    }

    func updatingBatteryPercentage(_ batteryPercentage: Int?) -> AndroidReceiverEndpoint {
        AndroidReceiverEndpoint(
            name: name,
            deviceId: deviceId,
            batteryPercentage: batteryPercentage,
            host: host,
            port: port
        )
    }
}

@MainActor
final class AndroidReceiverDiscovery: NSObject {
    typealias StatusHandler = (String, Bool) -> Void
    typealias EndpointHandler = (AndroidReceiverEndpoint) -> Void

    private let preferredDeviceIdProvider: () -> String?
    private let onStatusChanged: StatusHandler
    private let onEndpointResolved: EndpointHandler

    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private var resolvingServices: Set<ObjectIdentifier> = []
    private var resolvedEndpoints: [AndroidReceiverEndpoint] = []
    private var selectionWorkItem: DispatchWorkItem?
    private var timeoutWorkItem: DispatchWorkItem?
    private var isDiscovering = false

    init(
        preferredDeviceIdProvider: @escaping () -> String? = { AndroidReceiverIdentityStore.pairedDeviceId() },
        onStatusChanged: @escaping StatusHandler,
        onEndpointResolved: @escaping EndpointHandler
    ) {
        self.preferredDeviceIdProvider = preferredDeviceIdProvider
        self.onStatusChanged = onStatusChanged
        self.onEndpointResolved = onEndpointResolved
        super.init()
    }

    func discover() {
        stop()

        let browser = NetServiceBrowser()
        browser.delegate = self
        self.browser = browser
        services.removeAll()
        resolvingServices.removeAll()
        resolvedEndpoints.removeAll()
        isDiscovering = true

        updateStatus("Searching for ZevLink Android receivers...", true)
        browser.searchForServices(ofType: Self.serviceType, inDomain: Self.serviceDomain)

        let timeout = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                guard let self, self.isDiscovering else { return }
                if self.resolvedEndpoints.isEmpty {
                    self.stopBrowsingOnly()
                    self.updateStatus("No ZevLink Android receiver found.", false)
                } else {
                    self.selectResolvedEndpoint()
                }
            }
        }
        timeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.discoveryTimeout, execute: timeout)
    }

    func stop() {
        selectionWorkItem?.cancel()
        selectionWorkItem = nil
        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        stopBrowsingOnly()

        services.forEach {
            $0.stop()
            $0.delegate = nil
        }
        services.removeAll()
        resolvingServices.removeAll()
        resolvedEndpoints.removeAll()
    }

    private func stopBrowsingOnly() {
        browser?.stop()
        browser?.delegate = nil
        browser = nil
        isDiscovering = false
    }

    private func resolve(_ service: NetService) {
        let id = ObjectIdentifier(service)
        guard !resolvingServices.contains(id) else { return }

        resolvingServices.insert(id)
        service.delegate = self
        service.resolve(withTimeout: Self.resolveTimeout)
        updateStatus("Resolving \(service.name)...", true)
    }

    private func handleResolved(_ service: NetService) {
        resolvingServices.remove(ObjectIdentifier(service))
        guard let endpoint = endpoint(from: service) else {
            scheduleSelection()
            return
        }

        if !resolvedEndpoints.contains(endpoint) {
            resolvedEndpoints.append(endpoint)
        }

        let preferredDeviceId = normalizedDeviceId(preferredDeviceIdProvider())
        if preferredDeviceId != nil, endpoint.deviceId == preferredDeviceId {
            finish(with: endpoint)
            return
        }

        scheduleSelection()
    }

    private func scheduleSelection() {
        selectionWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            Task { @MainActor in
                self?.selectResolvedEndpoint()
            }
        }
        selectionWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.selectionDelay, execute: workItem)
    }

    private func selectResolvedEndpoint() {
        guard !resolvedEndpoints.isEmpty else { return }

        let preferredDeviceId = normalizedDeviceId(preferredDeviceIdProvider())
        let endpoint = resolvedEndpoints.sorted { lhs, rhs in
            let lhsRank = lhs.deviceId == preferredDeviceId ? 0 : 1
            let rhsRank = rhs.deviceId == preferredDeviceId ? 0 : 1
            if lhsRank != rhsRank {
                return lhsRank < rhsRank
            }
            return lhs.name < rhs.name
        }.first!

        finish(with: endpoint)
    }

    private func finish(with endpoint: AndroidReceiverEndpoint) {
        stopBrowsingOnly()
        selectionWorkItem?.cancel()
        timeoutWorkItem?.cancel()
        selectionWorkItem = nil
        timeoutWorkItem = nil

        updateStatus("Discovered \(endpoint.name) at \(endpoint.displayAddress).", false)
        onEndpointResolved(endpoint)
    }

    private func endpoint(from service: NetService) -> AndroidReceiverEndpoint? {
        guard service.port > 0 else { return nil }
        let hosts = endpointHosts(from: service)
        guard let host = hosts.first else { return nil }

        return AndroidReceiverEndpoint(
            name: service.name,
            deviceId: normalizedDeviceId(deviceId(from: service)),
            batteryPercentage: batteryPercentage(from: service),
            host: host,
            port: service.port
        )
    }

    private func endpointHosts(from service: NetService) -> [String] {
        let addressHosts = (service.addresses ?? []).compactMap(host(from:))
        if !addressHosts.isEmpty {
            return addressHosts.sorted(by: preferHost).uniqued()
        }

        return service.hostName.map { [$0] } ?? []
    }

    private func host(from data: Data) -> String? {
        data.withUnsafeBytes { rawBuffer -> String? in
            guard let baseAddress = rawBuffer.baseAddress else { return nil }
            let socketAddress = baseAddress.assumingMemoryBound(to: sockaddr.self)
            switch Int32(socketAddress.pointee.sa_family) {
            case AF_INET:
                return ipv4String(from: socketAddress)
            case AF_INET6:
                return ipv6String(from: socketAddress)
            default:
                return nil
            }
        }
    }

    private func ipv4String(from socketAddress: UnsafePointer<sockaddr>) -> String? {
        var address = socketAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            $0.pointee.sin_addr
        }

        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
            return nil
        }

        return String(cString: buffer)
    }

    private func ipv6String(from socketAddress: UnsafePointer<sockaddr>) -> String? {
        var address = socketAddress.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
            $0.pointee.sin6_addr
        }

        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        guard inet_ntop(AF_INET6, &address, &buffer, socklen_t(INET6_ADDRSTRLEN)) != nil else {
            return nil
        }

        let value = String(cString: buffer)
        return value.hasPrefix("fe80:") ? nil : value
    }

    private func deviceId(from service: NetService) -> String? {
        guard let data = service.txtRecordData() else { return nil }
        let txtRecord = NetService.dictionary(fromTXTRecord: data)
        guard let deviceData = txtRecord[Self.deviceIdTXTKey] else { return nil }
        return String(data: deviceData, encoding: .utf8)
    }

    private func batteryPercentage(from service: NetService) -> Int? {
        guard let data = service.txtRecordData() else { return nil }
        let txtRecord = NetService.dictionary(fromTXTRecord: data)
        guard
            let batteryData = txtRecord[Self.batteryPercentageTXTKey],
            let batteryText = String(data: batteryData, encoding: .utf8),
            let percentage = Int(batteryText),
            (0...100).contains(percentage)
        else {
            return nil
        }

        return percentage
    }

    private func normalizedDeviceId(_ value: String?) -> String? {
        let normalizedValue = value?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return normalizedValue?.isEmpty == false ? normalizedValue : nil
    }

    private func preferHost(_ lhs: String, _ rhs: String) -> Bool {
        let lhsRank = hostRank(lhs)
        let rhsRank = hostRank(rhs)
        if lhsRank != rhsRank {
            return lhsRank < rhsRank
        }
        return lhs < rhs
    }

    private func hostRank(_ host: String) -> Int {
        if host.contains(":") {
            return 0
        }
        if host.hasPrefix("10.") || host.hasPrefix("192.168.") || host.range(of: #"^172\.(1[6-9]|2[0-9]|3[0-1])\."#, options: .regularExpression) != nil {
            return 1
        }
        return 2
    }

    private func updateStatus(_ status: String, _ isDiscovering: Bool) {
        onStatusChanged(status, isDiscovering)
    }

    private static let serviceType = "_zevclip-android._tcp."
    private static let serviceDomain = "local."
    private static let deviceIdTXTKey = "deviceId"
    private static let batteryPercentageTXTKey = "battery"
    private static let discoveryTimeout: TimeInterval = 8
    private static let resolveTimeout: TimeInterval = 5
    private static let selectionDelay: TimeInterval = 1.2
}

extension AndroidReceiverDiscovery: NetServiceBrowserDelegate {
    nonisolated func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFind service: NetService,
        moreComing: Bool
    ) {
        Task { @MainActor in
            services.append(service)
            resolve(service)
        }
    }

    nonisolated func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didNotSearch errorDict: [String: NSNumber]
    ) {
        Task { @MainActor in
            stopBrowsingOnly()
            updateStatus("Could not start Android receiver discovery: \(errorDict).", false)
        }
    }
}

extension AndroidReceiverDiscovery: NetServiceDelegate {
    nonisolated func netServiceDidResolveAddress(_ sender: NetService) {
        Task { @MainActor in
            handleResolved(sender)
        }
    }

    nonisolated func netService(
        _ sender: NetService,
        didNotResolve errorDict: [String: NSNumber]
    ) {
        Task { @MainActor in
            resolvingServices.remove(ObjectIdentifier(sender))
            scheduleSelection()
        }
    }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
