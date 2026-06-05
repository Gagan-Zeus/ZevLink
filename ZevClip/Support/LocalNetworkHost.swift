import Darwin
import Foundation

enum LocalNetworkHost {
    static func currentPairingHost() -> String {
        currentPairingHosts().first ?? Host.current().localizedName ?? "localhost"
    }

    static func currentPairingHosts() -> [String] {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let firstInterface = interfaces else {
            return []
        }
        defer { freeifaddrs(interfaces) }

        var ipv6Candidates: [(rank: Int, address: String)] = []
        var privateCandidates: [(rank: Int, address: String)] = []
        var fallbackCandidates: [(rank: Int, address: String)] = []
        var interface = firstInterface

        while true {
            defer {
                if let next = interface.pointee.ifa_next {
                    interface = next
                }
            }

            let flags = Int32(interface.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) == IFF_UP
            let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK

            if
                isUp,
                !isLoopback,
                let address = interface.pointee.ifa_addr
            {
                let name = String(cString: interface.pointee.ifa_name)
                let family = Int32(address.pointee.sa_family)
                if family == AF_INET,
                   let ipv4Address = ipv4String(from: address),
                   !isLinkLocalAddress(ipv4Address),
                   let rank = interfaceRank(for: name)
                {
                    if isPrivateLANAddress(ipv4Address) {
                        privateCandidates.append((rank, ipv4Address))
                    } else {
                        fallbackCandidates.append((rank, ipv4Address))
                    }
                } else if family == AF_INET6,
                          let ipv6Address = ipv6String(from: address),
                          isUsableIPv6Address(ipv6Address),
                          let rank = interfaceRank(for: name)
                {
                    ipv6Candidates.append((rank, ipv6Address))
                }
            }

            guard interface.pointee.ifa_next != nil else {
                break
            }
        }

        return [
            ipv6Candidates.sorted(by: sortCandidate).map(\.address),
            privateCandidates.sorted(by: sortCandidate).map(\.address),
            fallbackCandidates.sorted(by: sortCandidate).map(\.address)
        ]
            .flatMap { $0 }
            .uniqued()
    }

    private static func ipv4String(from socketAddress: UnsafePointer<sockaddr>) -> String? {
        var address = socketAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            $0.pointee.sin_addr
        }

        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
            return nil
        }

        return String(cString: buffer)
    }

    private static func ipv6String(from socketAddress: UnsafePointer<sockaddr>) -> String? {
        var address = socketAddress.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
            $0.pointee.sin6_addr
        }

        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        guard inet_ntop(AF_INET6, &address, &buffer, socklen_t(INET6_ADDRSTRLEN)) != nil else {
            return nil
        }

        return String(cString: buffer)
    }

    private static func interfaceRank(for name: String) -> Int? {
        if name == "en0" || name == "en1" {
            return 0
        }

        if name.hasPrefix("en") {
            return 1
        }

        if name.hasPrefix("bridge") || name.hasPrefix("utun") || name.hasPrefix("awdl") || name.hasPrefix("llw") {
            return nil
        }

        return 2
    }

    private static func isPrivateLANAddress(_ address: String) -> Bool {
        guard let octets = octets(from: address) else {
            return false
        }

        return octets[0] == 10 ||
            (octets[0] == 172 && (16...31).contains(octets[1])) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private static func isLinkLocalAddress(_ address: String) -> Bool {
        guard let octets = octets(from: address) else {
            return true
        }

        return octets[0] == 169 && octets[1] == 254
    }

    private static func isUsableIPv6Address(_ address: String) -> Bool {
        let normalizedAddress = address.lowercased()
        return !normalizedAddress.hasPrefix("fe80:") &&
            normalizedAddress != "::1" &&
            normalizedAddress != "::"
    }

    private static func octets(from address: String) -> [Int]? {
        let octets = address
            .split(separator: ".")
            .compactMap { Int($0) }

        return octets.count == 4 ? octets : nil
    }

    private static func sortCandidate(
        _ lhs: (rank: Int, address: String),
        _ rhs: (rank: Int, address: String)
    ) -> Bool {
        lhs.rank == rhs.rank ? lhs.address < rhs.address : lhs.rank < rhs.rank
    }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
