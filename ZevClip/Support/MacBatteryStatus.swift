import Foundation
import IOKit.ps

struct MacBatteryStatus {
    let percentage: Int?
    let isCharging: Bool?

    var isAvailable: Bool {
        percentage != nil
    }

    static func current() -> MacBatteryStatus {
        guard
            let snapshot = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
            let sources = IOPSCopyPowerSourcesList(snapshot)?.takeRetainedValue() as? [CFTypeRef]
        else {
            return MacBatteryStatus(percentage: nil, isCharging: nil)
        }

        for source in sources {
            guard
                let description = IOPSGetPowerSourceDescription(snapshot, source)?
                    .takeUnretainedValue() as? [String: Any],
                description[kIOPSTypeKey] as? String == kIOPSInternalBatteryType
            else {
                continue
            }

            let currentCapacity = description[kIOPSCurrentCapacityKey] as? Int
            let maxCapacity = description[kIOPSMaxCapacityKey] as? Int
            let percentage = Self.percentage(
                currentCapacity: currentCapacity,
                maxCapacity: maxCapacity
            )
            let powerState = description[kIOPSPowerSourceStateKey] as? String
            let isCharging = powerState.map { $0 == kIOPSACPowerValue }

            return MacBatteryStatus(
                percentage: percentage,
                isCharging: isCharging
            )
        }

        return MacBatteryStatus(percentage: nil, isCharging: nil)
    }

    private static func percentage(currentCapacity: Int?, maxCapacity: Int?) -> Int? {
        guard let currentCapacity, let maxCapacity, maxCapacity > 0 else {
            return nil
        }

        let rawPercentage = Int((Double(currentCapacity) / Double(maxCapacity) * 100).rounded())
        return min(100, max(0, rawPercentage))
    }
}
