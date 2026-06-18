import AppKit
import Darwin
import UserNotifications

struct AndroidMirroredNotification: Decodable {
    let event: String?
    let appName: String
    let packageName: String
    let appIconPngBase64: String?
    let notificationImagePngBase64: String?
    let title: String?
    let body: String?
    let subtext: String?
    let actions: [AndroidMirroredNotificationAction]?
    let notificationKey: String?
    let postedAtMillis: Int64?
    let isMediaNotification: Bool?
    let mediaDurationMillis: Int64?
    let mediaPositionMillis: Int64?
    let mediaIsPlaying: Bool?
    let mediaCanSeek: Bool?

    var displayTitle: String {
        let trimmedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedTitle?.isEmpty == false ? trimmedTitle! : "Notification"
    }

    var displayBody: String {
        let candidates = [body, subtext]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return candidates.first ?? "New notification"
    }

    var isRemoval: Bool {
        event == "removed"
    }

    var isMediaPinnedNotification: Bool {
        isMediaNotification == true
    }

    var macNotificationIdentifier: String {
        [
            "android",
            packageName,
            notificationKey ?? "unknown"
        ].joined(separator: ".")
    }
}

struct AndroidMirroredNotificationAction: Decodable {
    let id: String
    let title: String
    let requiresTextInput: Bool?
    let inputLabel: String?

    var cleanTitle: String {
        title
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}

struct AndroidMirroredCall: Decodable {
    let event: String
    let callId: String
    let title: String
    let body: String
    let callerName: String?
    let callerNumber: String?
    let direction: String?
    let timestampMillis: Int64?

    var isRinging: Bool {
        event == "ringing"
    }

    var isAnswered: Bool {
        event == "answered"
    }

    var isEnded: Bool {
        event == "ended"
    }

    var isIncoming: Bool {
        direction == nil || direction == "incoming"
    }

    var macNotificationIdentifier: String {
        "android.call.\(callId)"
    }

    var displayTitle: String {
        let candidates = [callerName, callerNumber, title]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return candidates.first ?? "Incoming Android call"
    }

    var displayBody: String {
        if
            let callerName,
            let callerNumber,
            !callerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            !callerNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            return callerNumber
        }

        let bodyText = body.trimmingCharacters(in: .whitespacesAndNewlines)
        return bodyText.isEmpty ? "Your phone is ringing." : bodyText
    }
}

final class MacNotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotificationPresenter()

    var onDismiss: ((String) -> Void)?
    var onNotificationAction: ((String, String, String?, @escaping (Bool, String) -> Void) -> Void)?
    var onCallAction: ((String, String, @escaping (Bool, String) -> Void) -> Void)?
    var onFileTransferCancel: ((String) -> Void)?

    private let center = UNUserNotificationCenter.current()
    private var authorizationRequested = false
    private var activeCallPanel: CallControlPanelController?
    private var activeFileTransferPanels: [String: FileTransferPanelController] = [:]
    private var fileTransferPanelOrder: [String] = []
    private var activeNotificationPanels: [String: AndroidNotificationPanelController] = [:]
    private var notificationPanelOrder: [String] = []
    private var notificationPanelSerial = 0
    private var retainedNotifications: [String: RetainedAndroidNotification] = [:]
    private var retainedNotificationOrder: [String] = []
    private var notificationShadeStack: AndroidNotificationShadeStackController?
    private var notificationShadeGlobalMonitor: Any?
    private var notificationShadeLocalMonitor: Any?
    private var notificationShadeClickGlobalMonitor: Any?
    private var notificationShadeClickLocalMonitor: Any?
    private var notificationShadeSwipeAmount: CGFloat = 0
    private var didTriggerNotificationShade = false
    private var notificationShadeSwipeStartedInTrackpadCorner = false
    private var globalHideSwipeAmount: CGFloat = 0
    private var didTriggerGlobalHide = false

    private override init() {
        super.init()
        center.delegate = self
        installNotificationShadeGestureMonitor()
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.androidNotificationCategory,
                actions: [],
                intentIdentifiers: [],
                options: [.customDismissAction]
            ),
            UNNotificationCategory(
                identifier: Self.androidCallCategory,
                actions: [
                    UNNotificationAction(
                        identifier: Self.callActionAccept,
                        title: "Accept",
                        options: []
                    ),
                    UNNotificationAction(
                        identifier: Self.callActionReject,
                        title: "Reject",
                        options: [.destructive]
                    ),
                    UNNotificationAction(
                        identifier: Self.callActionSilence,
                        title: "Silence",
                        options: []
                    )
                ],
                intentIdentifiers: [],
                options: []
            )
        ])
    }

    func requestAuthorizationIfNeeded() {
        guard !authorizationRequested else { return }
        authorizationRequested = true

        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func show(_ notification: AndroidMirroredNotification) {
        if notification.isRemoval {
            remove(notification)
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.remember(notification)
            if self.notificationShadeStack != nil {
                self.hideVisiblePanel(for: notification)
                return
            }
            if notification.isMediaPinnedNotification {
                self.hideVisiblePanel(for: notification)
                return
            }
            self.showNotificationPanel(notification)
        }
    }

    func show(_ call: AndroidMirroredCall) {
        requestAuthorizationIfNeeded()

        if call.isEnded {
            remove(call)
            return
        }

        guard call.isIncoming else { return }

        if call.isAnswered {
            center.removeDeliveredNotifications(withIdentifiers: [call.macNotificationIdentifier])
            center.removePendingNotificationRequests(withIdentifiers: [call.macNotificationIdentifier])
            DispatchQueue.main.async { [weak self] in
                self?.showCallPanel(call)
            }
            return
        }

        guard call.isRinging else { return }

        DispatchQueue.main.async { [weak self] in
            self?.showCallPanel(call)
        }
    }

    private func remove(_ notification: AndroidMirroredNotification) {
        let identifiers = [notification.macNotificationIdentifier]
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.forget(notification)
            let matchingPanelIds = self.notificationPanelOrder.filter { panelId in
                self.activeNotificationPanels[panelId]?.matches(notification) == true
            }
            matchingPanelIds.forEach { panelId in
                self.activeNotificationPanels[panelId]?.close()
                self.activeNotificationPanels[panelId] = nil
            }
            self.notificationPanelOrder.removeAll { matchingPanelIds.contains($0) }
            self.repositionNotificationPanels()
        }
    }

    private func remove(_ call: AndroidMirroredCall) {
        let identifiers = [call.macNotificationIdentifier]
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        DispatchQueue.main.async { [weak self] in
            guard self?.activeCallPanel?.callId == call.callId else { return }
            self?.activeCallPanel?.close()
            self?.activeCallPanel = nil
        }
    }

    private func showCallPanel(_ call: AndroidMirroredCall) {
        if activeCallPanel?.callId == call.callId {
            activeCallPanel?.update(call)
            return
        }

        let panel = CallControlPanelController(call: call) { [weak self] action, callId, completion in
            self?.onCallAction?(action, callId, completion)
        }
        activeCallPanel = panel
        panel.show()
    }

    func showFileTransferActive(
        transferId: String,
        fileName: String,
        status: String,
        transferredBytes: Int64,
        totalBytes: Int64
    ) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let panel = self.fileTransferPanel(for: transferId)
            panel.updateActive(
                fileName: fileName,
                status: status,
                transferredBytes: transferredBytes,
                totalBytes: totalBytes
            )
            self.repositionFileTransferPanels()
            panel.show()
        }
    }

    func showFileTransferSent(
        transferId: String,
        fileName: String
    ) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let panel = self.fileTransferPanel(for: transferId)
            panel.updateSent(fileName: fileName)
            self.repositionFileTransferPanels()
            panel.show()
        }
    }

    func showFileTransferReceived(
        transferId: String,
        fileName: String,
        fileURLs: [URL]
    ) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let panel = self.fileTransferPanel(for: transferId)
            panel.updateReceived(fileName: fileName, fileURLs: fileURLs)
            self.repositionFileTransferPanels()
            panel.show()
        }
    }

    func clearFileTransfer(transferId: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, let panel = self.activeFileTransferPanels[transferId] else { return }
            self.activeFileTransferPanels[transferId] = nil
            self.fileTransferPanelOrder.removeAll { $0 == transferId }
            panel.close(notify: false)
            self.repositionFileTransferPanels()
        }
    }

    private func fileTransferPanel(for transferId: String) -> FileTransferPanelController {
        if let panel = activeFileTransferPanels[transferId] {
            return panel
        }

        let panel = FileTransferPanelController(
            transferId: transferId,
            onCancel: { [weak self] transferId in
                self?.onFileTransferCancel?(transferId)
            },
            onCopy: { urls in
                Self.copyFileTransferURLs(urls)
            },
            onShowInFinder: { urls in
                Self.showFileTransferURLsInFinder(urls)
            },
            onClose: { [weak self] transferId in
                guard let self else { return }
                self.activeFileTransferPanels[transferId] = nil
                self.fileTransferPanelOrder.removeAll { $0 == transferId }
                self.repositionFileTransferPanels()
            }
        )
        activeFileTransferPanels[transferId] = panel
        fileTransferPanelOrder.insert(transferId, at: 0)
        return panel
    }

    private func repositionFileTransferPanels() {
        guard let screen = NSScreen.main else { return }
        var nextTopY = screen.visibleFrame.maxY - 18
        for transferId in fileTransferPanelOrder {
            guard let panel = activeFileTransferPanels[transferId] else { continue }
            panel.move(toTopY: nextTopY)
            nextTopY -= panel.currentHeight + 10
        }
    }

    private func showNotificationPanel(_ notification: AndroidMirroredNotification) {
        if let existingPanelId = existingNotificationPanelId(for: notification),
           let existingPanel = activeNotificationPanels[existingPanelId] {
            existingPanel.update(notification)
            existingPanel.refreshVisibility()
            repositionNotificationPanels()
            return
        }

        let panelId = nextNotificationPanelId(for: notification)

        let panel = AndroidNotificationPanelController(
            notification: notification,
            icon: Self.appIconImage(for: notification),
            onDismiss: { [weak self] notificationKey in
                self?.dismissAndroidNotificationFromMac(notificationKey)
            },
            onAction: { [weak self] notificationKey, actionId, replyText, completion in
                self?.onNotificationAction?(notificationKey, actionId, replyText, completion)
            },
            onHideAll: { [weak self] in
                self?.hideAllMacNotifications()
            },
            onClose: { [weak self] in
                guard let self else { return }
                self.activeNotificationPanels[panelId] = nil
                self.notificationPanelOrder.removeAll { $0 == panelId }
                self.repositionNotificationPanels()
            },
            onLayoutChange: { [weak self] in
                self?.repositionNotificationPanels()
            }
        )
        activeNotificationPanels[panelId] = panel
        notificationPanelOrder.append(panelId)
        repositionNotificationPanels()
        panel.show(atTopY: notificationPanelTopY(for: panelId))
        NSSound(named: "Glass")?.play()
    }

    private func hideVisiblePanel(for notification: AndroidMirroredNotification) {
        let matchingPanelIds = notificationPanelOrder.filter { panelId in
            activeNotificationPanels[panelId]?.matches(notification) == true
        }
        matchingPanelIds.forEach { panelId in
            activeNotificationPanels[panelId]?.close()
            activeNotificationPanels[panelId] = nil
        }
        notificationPanelOrder.removeAll { matchingPanelIds.contains($0) }
        if !matchingPanelIds.isEmpty {
            repositionNotificationPanels()
        }
    }

    private func remember(_ notification: AndroidMirroredNotification) {
        guard !notification.isRemoval else { return }

        let retainedId = retainedNotificationIdentifier(for: notification)
        if retainedNotifications[retainedId] == nil {
            retainedNotificationOrder.append(retainedId)
        }
        retainedNotifications[retainedId] = RetainedAndroidNotification(
            id: retainedId,
            notification: notification,
            icon: Self.appIconImage(for: notification),
            receivedAt: Date()
        )
        refreshNotificationShade()
    }

    private func forget(_ notification: AndroidMirroredNotification) {
        if let notificationKey = notification.notificationKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !notificationKey.isEmpty {
            forgetRetainedNotification(matchingKey: notificationKey)
            return
        }

        forgetRetainedNotification(withId: retainedNotificationIdentifier(for: notification))
    }

    private func forgetRetainedNotification(matchingKey notificationKey: String) {
        let matchingIds = retainedNotificationOrder.filter { retainedId in
            retainedNotifications[retainedId]?.notification.notificationKey == notificationKey
        }
        matchingIds.forEach { forgetRetainedNotification(withId: $0) }
        refreshNotificationShade()
    }

    private func forgetRetainedNotification(withId retainedId: String) {
        retainedNotifications[retainedId] = nil
        retainedNotificationOrder.removeAll { $0 == retainedId }
        refreshNotificationShade()
    }

    private func retainedNotificationIdentifier(for notification: AndroidMirroredNotification) -> String {
        if let notificationKey = notification.notificationKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !notificationKey.isEmpty {
            return notificationKey
        }

        let postedAt = notification.postedAtMillis.map(String.init) ?? "unknown"
        return "\(notification.macNotificationIdentifier).\(postedAt)"
    }

    private func retainedNotificationItems() -> [RetainedAndroidNotification] {
        let items = retainedNotificationOrder
            .reversed()
            .compactMap { retainedNotifications[$0] }
        let mediaItems = items.filter { $0.notification.isMediaPinnedNotification }
        let otherItems = items.filter { !$0.notification.isMediaPinnedNotification }
        return mediaItems + otherItems
    }

    private func showNotificationShade() {
        let items = retainedNotificationItems()
        guard !items.isEmpty else { return }

        if notificationShadeStack == nil {
            notificationShadeStack = AndroidNotificationShadeStackController(
                onDismiss: { [weak self] notificationKey in
                    self?.dismissAndroidNotificationFromMac(notificationKey)
                },
                onAction: { [weak self] notificationKey, actionId, replyText, completion in
                    self?.onNotificationAction?(notificationKey, actionId, replyText, completion)
                },
                onHideAll: { [weak self] in
                    self?.hideAllMacNotifications()
                },
                onClose: { [weak self] in
                    self?.notificationShadeStack = nil
                }
            )
        }
        notificationShadeStack?.show(items: items)
    }

    private func refreshNotificationShade() {
        let items = retainedNotificationItems()
        notificationShadeStack?.update(items: items)
        if items.isEmpty {
            notificationShadeStack?.close()
            notificationShadeStack = nil
        }
    }

    private func dismissAndroidNotificationFromMac(_ notificationKey: String) {
        let trimmedKey = notificationKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else { return }

        forgetRetainedNotification(matchingKey: trimmedKey)
        let matchingPanelIds = notificationPanelOrder.filter { panelId in
            activeNotificationPanels[panelId]?.hasNotificationKey(trimmedKey) == true
        }
        matchingPanelIds.forEach { panelId in
            activeNotificationPanels[panelId]?.close()
        }
        onDismiss?(trimmedKey)
    }

    private func hideAllMacNotifications() {
        Array(activeNotificationPanels.values).forEach { $0.close() }
        notificationShadeStack?.close()
        notificationShadeStack = nil
    }

    private func installNotificationShadeGestureMonitor() {
        TrackpadCornerGestureMonitor.shared.start()
        notificationShadeGlobalMonitor = NSEvent.addGlobalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            DispatchQueue.main.async {
                self?.handleNotificationShadeGesture(event)
            }
        }
        notificationShadeLocalMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            self?.handleNotificationShadeGesture(event)
            return event
        }
        let mouseEvents: NSEvent.EventTypeMask = [
            .leftMouseDown,
            .rightMouseDown,
            .otherMouseDown,
            .leftMouseUp,
            .rightMouseUp,
            .otherMouseUp
        ]
        notificationShadeClickGlobalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mouseEvents) { [weak self] event in
            DispatchQueue.main.async {
                self?.hideNotificationShadeIfClickIsOutside(event, isLocalEvent: false)
            }
        }
        notificationShadeClickLocalMonitor = NSEvent.addLocalMonitorForEvents(matching: mouseEvents) { [weak self] event in
            self?.hideNotificationShadeIfClickIsOutside(event, isLocalEvent: true)
            return event
        }
    }

    private func hideNotificationShadeIfClickIsOutside(_ event: NSEvent, isLocalEvent: Bool) {
        guard let notificationShadeStack else { return }
        guard !notificationShadeStack.contains(point: screenPoint(for: event, isLocalEvent: isLocalEvent)) else { return }

        notificationShadeStack.close()
        self.notificationShadeStack = nil
    }

    private func screenPoint(for event: NSEvent, isLocalEvent: Bool) -> NSPoint {
        if isLocalEvent, let window = event.window {
            return window.convertPoint(toScreen: event.locationInWindow)
        }

        let eventLocation = event.locationInWindow
        if eventLocation != .zero {
            return eventLocation
        }

        return NSEvent.mouseLocation
    }

    private func handleNotificationShadeGesture(_ event: NSEvent) {
        if notificationShadeStack?.handleScroll(event) == true {
            resetGlobalHideGestureIfNeeded(event)
            return
        }

        if handleGlobalHideGesture(event) {
            return
        }

        let phase = event.phase
        if phase.contains(.began) || phase.contains(.mayBegin) {
            notificationShadeSwipeAmount = 0
            didTriggerNotificationShade = false
            notificationShadeSwipeStartedInTrackpadCorner = shouldBeginNotificationShadeSwipe(event)
        } else if notificationShadeSwipeAmount == 0 && !notificationShadeSwipeStartedInTrackpadCorner {
            notificationShadeSwipeStartedInTrackpadCorner = shouldBeginNotificationShadeSwipe(event)
        }

        guard notificationShadeSwipeStartedInTrackpadCorner else {
            notificationShadeSwipeAmount = 0
            return
        }

        let vertical = normalizedFingerVerticalDelta(from: event)
        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let isDownwardSwipe = vertical < 0 && abs(vertical) > abs(horizontal) * 1.05

        if isDownwardSwipe {
            notificationShadeSwipeAmount += abs(vertical)
            if notificationShadeSwipeAmount > Self.notificationShadeSwipeThreshold,
               !didTriggerNotificationShade {
                didTriggerNotificationShade = true
                showNotificationShade()
            }
        }

        if phase.contains(.ended)
            || phase.contains(.cancelled)
            || event.momentumPhase.contains(.ended)
            || event.momentumPhase.contains(.cancelled) {
            notificationShadeSwipeAmount = 0
            didTriggerNotificationShade = false
            notificationShadeSwipeStartedInTrackpadCorner = false
        }
    }

    private func shouldBeginNotificationShadeSwipe(_ event: NSEvent) -> Bool {
        guard event.hasPreciseScrollingDeltas else { return false }
        guard event.momentumPhase == [] else { return false }

        let trackpadMonitor = TrackpadCornerGestureMonitor.shared
        if trackpadMonitor.isAvailable {
            return trackpadMonitor.hasRecentTopRightTouch
        }

        return isPointerInTopRightGestureRegion()
    }

    private func handleGlobalHideGesture(_ event: NSEvent) -> Bool {
        guard notificationShadeStack != nil || !activeNotificationPanels.isEmpty else {
            resetGlobalHideGestureIfNeeded(event)
            return false
        }

        let phase = event.phase
        if phase.contains(.began) || phase.contains(.mayBegin) {
            globalHideSwipeAmount = 0
            didTriggerGlobalHide = false
        }

        let vertical = normalizedFingerVerticalDelta(from: event)
        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let isUpwardSwipe = vertical > 0 && abs(vertical) > abs(horizontal) * 1.25

        if isUpwardSwipe {
            globalHideSwipeAmount += abs(vertical)
            if globalHideSwipeAmount > Self.globalHideSwipeThreshold,
               !didTriggerGlobalHide {
                didTriggerGlobalHide = true
                hideAllMacNotifications()
                return true
            }
        }

        resetGlobalHideGestureIfNeeded(event)
        return false
    }

    private func resetGlobalHideGestureIfNeeded(_ event: NSEvent) {
        if event.phase.contains(.ended)
            || event.phase.contains(.cancelled)
            || event.momentumPhase.contains(.ended)
            || event.momentumPhase.contains(.cancelled) {
            globalHideSwipeAmount = 0
            didTriggerGlobalHide = false
        }
    }

    private func isPointerInTopRightGestureRegion() -> Bool {
        let mouseLocation = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(mouseLocation, $0.frame, false) } ?? NSScreen.main
        guard let screen else { return false }

        let frame = screen.frame
        return mouseLocation.x >= frame.maxX - Self.notificationShadeGestureWidth
            && mouseLocation.y >= frame.maxY - Self.notificationShadeGestureHeight
    }

    private func normalizedFingerHorizontalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaX : event.scrollingDeltaX
    }

    private func normalizedFingerVerticalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaY : event.scrollingDeltaY
    }

    private func existingNotificationPanelId(for notification: AndroidMirroredNotification) -> String? {
        if let exactMatch = notificationPanelOrder.first(where: { panelId in
            activeNotificationPanels[panelId]?.matches(notification) == true
        }) {
            return exactMatch
        }

        return notificationPanelOrder.first { panelId in
            activeNotificationPanels[panelId]?.shouldCoalesce(with: notification) == true
        }
    }

    private func nextNotificationPanelId(for notification: AndroidMirroredNotification) -> String {
        notificationPanelSerial += 1
        let postedAt = notification.postedAtMillis.map(String.init) ?? "unknown"
        return "\(notification.macNotificationIdentifier).\(postedAt).\(notificationPanelSerial)"
    }

    private func repositionNotificationPanels() {
        var nextTopY = notificationPanelInitialTopY()
        for panelId in notificationPanelOrder {
            guard let panel = activeNotificationPanels[panelId] else { continue }
            panel.move(toTopY: nextTopY)
            nextTopY -= panel.currentHeight + 10
        }
    }

    private func notificationPanelTopY(for panelId: String) -> CGFloat {
        var nextTopY = notificationPanelInitialTopY()
        for orderedPanelId in notificationPanelOrder {
            guard let panel = activeNotificationPanels[orderedPanelId] else { continue }
            if orderedPanelId == panelId {
                return nextTopY
            }
            nextTopY -= panel.currentHeight + 10
        }
        return nextTopY
    }

    private func notificationPanelInitialTopY() -> CGFloat {
        guard let screen = NSScreen.main else {
            return 0
        }

        return screen.visibleFrame.maxY - 18
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let userInfo = response.notification.request.content.userInfo
        if response.notification.request.content.categoryIdentifier == Self.androidCallCategory {
            guard
                let callId = userInfo["callId"] as? String,
                !callId.isEmpty
            else {
                return
            }

            switch response.actionIdentifier {
            case Self.callActionAccept:
                onCallAction?("accept", callId, { _, _ in })
            case Self.callActionReject:
                onCallAction?("reject", callId, { _, _ in })
            case Self.callActionSilence:
                onCallAction?("silence", callId, { _, _ in })
            default:
                break
            }
            return
        }

        guard response.actionIdentifier == UNNotificationDismissActionIdentifier else {
            return
        }

        guard
            let notificationKey = userInfo["notificationKey"] as? String,
            !notificationKey.isEmpty
        else {
            return
        }

        await MainActor.run {
            dismissAndroidNotificationFromMac(notificationKey)
        }
    }

    private static let androidNotificationCategory = "ANDROID_MIRRORED_NOTIFICATION"
    private static let androidCallCategory = "ANDROID_MIRRORED_CALL"
    private static let callActionAccept = "ANDROID_CALL_ACCEPT"
    private static let callActionReject = "ANDROID_CALL_REJECT"
    private static let callActionSilence = "ANDROID_CALL_SILENCE"
    private static let maximumImageBytes = 512 * 1024
    private static let notificationShadeSwipeThreshold: CGFloat = 58
    private static let globalHideSwipeThreshold: CGFloat = 70
    private static let notificationShadeGestureWidth: CGFloat = 280
    private static let notificationShadeGestureHeight: CGFloat = 170

    fileprivate static func appIconImage(
        for notification: AndroidMirroredNotification
    ) -> NSImage? {
        if let notificationImage = image(fromBase64Png: notification.notificationImagePngBase64) {
            return notificationImage
        }

        return image(fromBase64Png: notification.appIconPngBase64)
    }

    private static func copyFileTransferURLs(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.writeObjects(urls as [NSURL])
    }

    private static func showFileTransferURLsInFinder(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting(urls)
    }

    private static func image(fromBase64Png encodedImage: String?) -> NSImage? {
        guard
            let encodedImage,
            let imageData = Data(base64Encoded: encodedImage, options: [.ignoreUnknownCharacters]),
            !imageData.isEmpty,
            imageData.count <= maximumImageBytes,
            imageData.starts(with: [0x89, 0x50, 0x4e, 0x47])
        else { return nil }

        return NSImage(data: imageData)
    }
}

private struct RetainedAndroidNotification {
    let id: String
    let notification: AndroidMirroredNotification
    let icon: NSImage?
    let receivedAt: Date
}

private final class AndroidNotificationShadeStackController {
    private var panels: [String: AndroidNotificationPanelController] = [:]
    private var order: [String] = []
    private let onDismiss: (String) -> Void
    private let onAction: (String, String, String?, @escaping (Bool, String) -> Void) -> Void
    private let onHideAll: () -> Void
    private let onClose: () -> Void
    private var scrollOffset: CGFloat = 0
    private var isClosing = false

    private static let panelSpacing: CGFloat = 10
    private static let topPadding: CGFloat = 18

    init(
        onDismiss: @escaping (String) -> Void,
        onAction: @escaping (String, String, String?, @escaping (Bool, String) -> Void) -> Void,
        onHideAll: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) {
        self.onDismiss = onDismiss
        self.onAction = onAction
        self.onHideAll = onHideAll
        self.onClose = onClose
    }

    func show(items: [RetainedAndroidNotification]) {
        update(items: items)
    }

    func update(items: [RetainedAndroidNotification]) {
        guard !items.isEmpty else {
            close()
            return
        }

        let incomingIds = Set(items.map(\.id))
        let staleIds = order.filter { !incomingIds.contains($0) }
        staleIds.forEach { id in
            panels[id]?.close()
            panels[id] = nil
        }

        order = items.map(\.id)
        for item in items {
            if let existingPanel = panels[item.id] {
                existingPanel.update(item.notification, mediaSnapshotReceivedAt: item.receivedAt)
            } else {
                let panel = AndroidNotificationPanelController(
                    notification: item.notification,
                    icon: item.icon,
                    onDismiss: { [weak self] notificationKey in
                        self?.onDismiss(notificationKey)
                    },
                    onAction: { [weak self] notificationKey, actionId, replyText, completion in
                        self?.onAction(notificationKey, actionId, replyText, completion)
                    },
                    onHideAll: { [weak self] in
                        self?.onHideAll()
                    },
                    onClose: { [weak self] in
                        self?.panels[item.id] = nil
                        self?.order.removeAll { $0 == item.id }
                    },
                    onLayoutChange: { [weak self] in
                        self?.reposition(orderFront: false)
                    },
                    shouldAutoClose: false,
                    mediaSnapshotReceivedAt: item.receivedAt
                )
                panels[item.id] = panel
            }
        }

        clampScrollOffset()
        reposition(orderFront: true)
    }

    func handleScroll(_ event: NSEvent) -> Bool {
        guard !panels.isEmpty, pointerIsInsideStack() else {
            return false
        }

        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let vertical = normalizedFingerVerticalDelta(from: event)
        guard abs(vertical) > abs(horizontal), abs(vertical) > 0.2 else {
            return false
        }

        scrollOffset -= vertical
        clampScrollOffset()
        reposition(orderFront: false)
        return true
    }

    func close() {
        guard !isClosing else { return }
        isClosing = true
        Array(panels.values).forEach { $0.close() }
        panels.removeAll()
        order.removeAll()
        onClose()
    }

    private func reposition(orderFront: Bool) {
        var nextTopY = initialTopY() - scrollOffset
        for id in order {
            guard let panel = panels[id] else { continue }
            if orderFront {
                panel.show(atTopY: nextTopY)
            } else {
                panel.move(toTopY: nextTopY)
            }
            nextTopY -= panel.currentHeight + Self.panelSpacing
        }
    }

    private func pointerIsInsideStack() -> Bool {
        contains(point: NSEvent.mouseLocation)
    }

    func contains(point: NSPoint) -> Bool {
        return panels.values.contains { panel in
            NSMouseInRect(point, panel.screenFrame.insetBy(dx: -12, dy: -12), false)
        }
    }

    private func clampScrollOffset() {
        scrollOffset = min(max(scrollOffset, 0), maximumScrollOffset())
    }

    private func maximumScrollOffset() -> CGFloat {
        max(0, contentHeight() - availableHeight())
    }

    private func contentHeight() -> CGFloat {
        let heights = order.compactMap { panels[$0]?.currentHeight }
        guard !heights.isEmpty else { return 0 }
        return heights.reduce(0, +) + (CGFloat(heights.count - 1) * Self.panelSpacing)
    }

    private func availableHeight() -> CGFloat {
        guard let screen = NSScreen.main else { return 0 }
        return max(0, screen.visibleFrame.height - (Self.topPadding * 2))
    }

    private func initialTopY() -> CGFloat {
        guard let screen = NSScreen.main else { return 0 }
        return screen.visibleFrame.maxY - Self.topPadding
    }

    private func normalizedFingerHorizontalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaX : event.scrollingDeltaX
    }

    private func normalizedFingerVerticalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaY : event.scrollingDeltaY
    }
}

private final class AndroidNotificationPanelController: NSObject {
    private let panel: NotificationInteractionPanel
    private let iconView = NSImageView()
    private let titleLabel = NSTextField(labelWithString: "")
    private let bodyLabel = NSTextField(labelWithString: "")
    private let mediaTitleLabel = MarqueeTextLabel()
    private let mediaBodyLabel = MarqueeTextLabel()
    private let expandButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let mediaPrimaryButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let actionContainerView = NSView()
    private let actionContentStack = NSStackView()
    private let mediaControlRowStack = NSStackView()
    private let mediaTimelineStack = NSStackView()
    private let actionStack = NSStackView()
    private let mediaTimelineSlider = MediaTimelineControl()
    private let replyComposerView = NSStackView()
    private let replyBackButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let replyFieldContainer = NSView()
    private let replyTextField = InlineReplyTextField(string: "")
    private let replyCursorView = NSView()
    private let replySendButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let onDismiss: (String) -> Void
    private let onAction: (String, String, String?, @escaping (Bool, String) -> Void) -> Void
    private let onHideAll: () -> Void
    private let onClose: () -> Void
    private let onLayoutChange: () -> Void
    private let shouldAutoClose: Bool
    private var notificationKey: String?
    private var notificationKeys = Set<String>()
    private var packageName = ""
    private var coalescingTitle = ""
    private var notificationActions: [AndroidMirroredNotificationAction] = []
    private var isMediaNotification = false
    private var mediaDurationMillis: Int64?
    private var mediaPositionMillis: Int64?
    private var mediaPositionUpdatedAt: Date?
    private var mediaIsPlaying = false
    private var mediaCanSeek = false
    private var isUpdatingMediaTimelineSlider = false
    private var detectedOTPCode: String?
    private var activeReplyAction: AndroidMirroredNotificationAction?
    private var replyKeyMonitor: Any?
    private var autoCloseTimer: Timer?
    private var mediaTimelineTimer: Timer?
    private var isClosing = false
    private var isExpanded = false
    private var isHovering = false
    private var hasLongContent = false
    private var targetTopY: CGFloat?
    private var fullTitle = ""
    private var fullBody = ""
    private var currentMediaPrimarySymbolName: String?
    private var iconCenterYConstraint: NSLayoutConstraint?
    private var iconTopConstraint: NSLayoutConstraint?
    private var iconWidthConstraint: NSLayoutConstraint?
    private var iconHeightConstraint: NSLayoutConstraint?
    private var textCenterYConstraint: NSLayoutConstraint?
    private var textTopConstraint: NSLayoutConstraint?
    private var textStackWidthConstraint: NSLayoutConstraint?
    private var textAboveActionsConstraint: NSLayoutConstraint?
    private var actionBelowTextConstraint: NSLayoutConstraint?
    private var actionContainerLeadingToTextConstraint: NSLayoutConstraint?
    private var actionContainerLeadingToContentConstraint: NSLayoutConstraint?
    private var actionContainerHeightConstraint: NSLayoutConstraint?
    private var replyCursorLeadingConstraint: NSLayoutConstraint?
    private var mediaTimelineWidthConstraint: NSLayoutConstraint?
    private var mediaTimelineHeightConstraint: NSLayoutConstraint?
    private weak var trackingContentView: HoverTrackingView?
    private var replyCursorBlinkTimer: Timer?
    private static let panelWidth: CGFloat = 352
    private static let textColumnWidth: CGFloat = 240
    private static let mediaTextColumnWidth: CGFloat = 190
    private static let regularIconSize: CGFloat = 40
    private static let mediaIconSize: CGFloat = 58
    private static let collapsedHeight: CGFloat = 72
    private static let mediaPanelHeight: CGFloat = 128
    private static let hoverActionsHeight: CGFloat = 106
    private static let actionRowHeight: CGFloat = 32
    private static let mediaControlRowHeight: CGFloat = 48
    private static let mediaTimelineWidth: CGFloat = 226
    private static let mediaTimelineRowHeight: CGFloat = 48
    private static let mediaTimelineSpacing: CGFloat = 0
    private static let expandedBodyLineLimit = 4
    private static let expandedMaxHeight: CGFloat = 210
    private static let expandedMinHeight: CGFloat = 80
    private static let defaultVisibleDuration: TimeInterval = 7
    private static let unhoverVisibleDuration: TimeInterval = 3
    private static let hoverAnimationDuration: TimeInterval = 0.32
    private static let activeMediaDisplayOffsetMillis: Int64 = 1000
    private static let copyOTPActionId = "zevlink.copy-otp-code"
    private static let mediaSeekActionId = "zevlink.media-seek-to"

    init(
        notification: AndroidMirroredNotification,
        icon: NSImage?,
        onDismiss: @escaping (String) -> Void,
        onAction: @escaping (String, String, String?, @escaping (Bool, String) -> Void) -> Void,
        onHideAll: @escaping () -> Void,
        onClose: @escaping () -> Void,
        onLayoutChange: @escaping () -> Void,
        shouldAutoClose: Bool = true,
        mediaSnapshotReceivedAt: Date = Date()
    ) {
        self.onDismiss = onDismiss
        self.onAction = onAction
        self.onHideAll = onHideAll
        self.onClose = onClose
        self.onLayoutChange = onLayoutChange
        self.shouldAutoClose = shouldAutoClose
        self.isMediaNotification = notification.isMediaPinnedNotification

        panel = NotificationInteractionPanel(
            contentRect: NSRect(
                x: 0,
                y: 0,
                width: Self.panelWidth,
                height: notification.isMediaPinnedNotification ? Self.mediaPanelHeight : Self.collapsedHeight
            ),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.ignoresMouseEvents = false
        panel.isMovableByWindowBackground = false
        panel.appearance = NSApp.effectiveAppearance

        super.init()
        configureContent()
        update(notification, icon: icon, mediaSnapshotReceivedAt: mediaSnapshotReceivedAt)
    }

    var currentHeight: CGFloat {
        panel.frame.height
    }

    var screenFrame: NSRect {
        panel.frame
    }

    func show(atTopY topY: CGFloat) {
        move(toTopY: topY)
        updateMediaTimelineSlider()
        updateMediaTimelineTimer()
        panel.orderFrontRegardless()
        if shouldAutoClose {
            restartAutoCloseTimer(after: Self.defaultVisibleDuration)
        }
    }

    func move(toTopY topY: CGFloat) {
        positionNearTopRight(topY: topY)
    }

    func update(_ notification: AndroidMirroredNotification) {
        update(notification, icon: MacNotificationPresenter.appIconImage(for: notification), mediaSnapshotReceivedAt: Date())
    }

    func update(_ notification: AndroidMirroredNotification, mediaSnapshotReceivedAt: Date) {
        update(
            notification,
            icon: MacNotificationPresenter.appIconImage(for: notification),
            mediaSnapshotReceivedAt: mediaSnapshotReceivedAt
        )
    }

    func refreshVisibility() {
        updateMediaTimelineSlider()
        updateMediaTimelineTimer()
        panel.orderFrontRegardless()
        if shouldAutoClose && !isHovering && activeReplyAction == nil {
            restartAutoCloseTimer(after: Self.defaultVisibleDuration)
        }
    }

    func matches(_ notification: AndroidMirroredNotification) -> Bool {
        guard let removedKey = notification.notificationKey, !removedKey.isEmpty else {
            return false
        }
        return notificationKeys.contains(removedKey) || notificationKey == removedKey
    }

    func hasNotificationKey(_ notificationKey: String) -> Bool {
        notificationKeys.contains(notificationKey) || self.notificationKey == notificationKey
    }

    func shouldCoalesce(with notification: AndroidMirroredNotification) -> Bool {
        guard !notification.isRemoval else { return false }
        let incomingTitle = Self.singleLine(notification.displayTitle)
        return !packageName.isEmpty
            && packageName == notification.packageName
            && !coalescingTitle.isEmpty
            && coalescingTitle == incomingTitle
    }

    func close() {
        guard !isClosing else { return }
        isClosing = true
        autoCloseTimer?.invalidate()
        stopMediaTimelineTimer()
        stopInlineReplyKeyCapture()
        stopReplyCursorBlink()
        panel.close()
        onClose()
    }

    private func update(
        _ notification: AndroidMirroredNotification,
        icon: NSImage?,
        mediaSnapshotReceivedAt: Date
    ) {
        if let incomingKey = notification.notificationKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !incomingKey.isEmpty {
            notificationKey = incomingKey
            notificationKeys.insert(incomingKey)
        }
        packageName = notification.packageName
        isMediaNotification = notification.isMediaPinnedNotification
        if
            let duration = notification.mediaDurationMillis,
            duration > 0,
            let position = notification.mediaPositionMillis,
            position >= 0
        {
            mediaDurationMillis = duration
            mediaPositionMillis = min(position, duration)
            mediaPositionUpdatedAt = mediaSnapshotReceivedAt
            mediaIsPlaying = notification.mediaIsPlaying == true
            mediaCanSeek = notification.mediaCanSeek == true
        } else {
            mediaDurationMillis = nil
            mediaPositionMillis = nil
            mediaPositionUpdatedAt = nil
            mediaIsPlaying = false
            mediaCanSeek = false
        }
        fullTitle = Self.singleLine(notification.displayTitle)
        coalescingTitle = fullTitle
        fullBody = Self.singleLine(notification.displayBody)
        detectedOTPCode = Self.detectOTPCode(in: [
            notification.title,
            notification.body,
            notification.subtext
        ])
        let wasReplying = activeReplyAction != nil
        let maximumAndroidActions = detectedOTPCode == nil ? 4 : 3
        notificationActions = notification.actions?
            .filter { !$0.id.isEmpty && !$0.cleanTitle.isEmpty }
            .prefix(maximumAndroidActions)
            .map { $0 } ?? []
        if let activeReplyAction,
           !notificationActions.contains(where: { $0.id == activeReplyAction.id }) {
            self.activeReplyAction = nil
            stopInlineReplyKeyCapture()
            stopReplyCursorBlink()
        }
        if !(isMediaNotification && mediaTimelineSlider.isInteracting) {
            rebuildActionButtons()
        }
        updateMediaTimelineSlider()
        updateMediaTimelineTimer()
        updateMediaPrimaryButton()
        hasLongContent = Self.requiresExpansion(
            title: fullTitle,
            body: fullBody,
            titleFont: titleLabel.font ?? .systemFont(ofSize: 14, weight: .semibold),
            bodyFont: bodyLabel.font ?? .systemFont(ofSize: 13)
        )
        if isMediaNotification || !hasLongContent {
            isExpanded = false
        }
        applyDisplayState(animated: false)
        iconView.image = icon ?? NSApp.applicationIconImage
        if wasReplying && activeReplyAction == nil {
            restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
        }
    }

    private func configureContent() {
        let contentView = HoverTrackingView(
            frame: panel.contentView?.bounds ?? NSRect(
                x: 0,
                y: 0,
                width: Self.panelWidth,
                height: isMediaNotification ? Self.mediaPanelHeight : Self.collapsedHeight
            )
        )
        trackingContentView = contentView
        contentView.onHoverChanged = { [weak self] isHovering in
            self?.isHovering = isHovering
            self?.applyDisplayState(animated: true)
            if isHovering {
                self?.autoCloseTimer?.invalidate()
                self?.autoCloseTimer = nil
            } else {
                self?.restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
            }
        }
        contentView.preferredHitTest = { [weak self, weak contentView] point in
            guard
                let self,
                let contentView,
                self.isMediaNotification
            else {
                return nil
            }

            if let mediaButton = self.mediaControlButtonHitTarget(at: point, in: contentView) {
                return mediaButton
            }

            guard self.shouldShowMediaTimeline else {
                return nil
            }

            let sliderPoint = self.mediaTimelineSlider.convert(point, from: contentView)
            return self.mediaTimelineSlider.acceptsAncestorHit(at: sliderPoint)
                ? self.mediaTimelineSlider
                : nil
        }
        contentView.onSwipeRight = { [weak self] in
            self?.dismissFromAndroid()
        }
        contentView.onSwipeUp = { [weak self] in
            self?.onHideAll()
        }
        contentView.allowsScrollWheelSwipeUp = shouldAutoClose
        configurePanelContentView(contentView)
        panel.acceptsMouseMovedEvents = true

        iconView.imageScaling = .scaleProportionallyUpOrDown
        iconView.wantsLayer = true
        iconView.layer?.cornerRadius = 9
        iconView.layer?.masksToBounds = true
        iconView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        titleLabel.textColor = .labelColor
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.maximumNumberOfLines = 1
        titleLabel.cell?.usesSingleLineMode = true
        titleLabel.cell?.truncatesLastVisibleLine = true
        titleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        titleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        bodyLabel.font = .systemFont(ofSize: 13)
        bodyLabel.textColor = .secondaryLabelColor
        bodyLabel.lineBreakMode = .byTruncatingTail
        bodyLabel.maximumNumberOfLines = 1
        bodyLabel.cell?.usesSingleLineMode = true
        bodyLabel.cell?.truncatesLastVisibleLine = true
        bodyLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        bodyLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        mediaTitleLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        mediaTitleLabel.textColor = .labelColor
        mediaTitleLabel.isHidden = true
        mediaTitleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        mediaTitleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        mediaBodyLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        mediaBodyLabel.textColor = .secondaryLabelColor
        mediaBodyLabel.isHidden = true
        mediaBodyLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        mediaBodyLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        expandButton.target = self
        expandButton.action = #selector(toggleExpanded)
        expandButton.isHidden = true
        if let chevronImage = Self.chevronImage(named: "chevron.right", accessibilityDescription: "Expand") {
            expandButton.image = chevronImage
        } else {
            expandButton.title = ">"
            expandButton.font = .systemFont(ofSize: 11, weight: .semibold)
        }
        expandButton.contentTintColor = .labelColor
        expandButton.isEnabled = true
        expandButton.isBordered = false
        expandButton.wantsLayer = false
        expandButton.translatesAutoresizingMaskIntoConstraints = false

        mediaPrimaryButton.target = self
        mediaPrimaryButton.action = #selector(performNotificationAction(_:))
        mediaPrimaryButton.isHidden = true
        mediaPrimaryButton.isBordered = false
        mediaPrimaryButton.wantsLayer = true
        mediaPrimaryButton.layer?.backgroundColor = NSColor.clear.cgColor
        mediaPrimaryButton.layer?.masksToBounds = false
        mediaPrimaryButton.pressAnimationScale = 0.9
        mediaPrimaryButton.contentTintColor = .white
        mediaPrimaryButton.focusRingType = .none
        mediaPrimaryButton.imagePosition = .imageOnly
        mediaPrimaryButton.translatesAutoresizingMaskIntoConstraints = false

        actionStack.orientation = .horizontal
        actionStack.alignment = .centerY
        actionStack.spacing = 10
        actionStack.isHidden = true
        actionStack.alphaValue = 0
        actionStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        actionStack.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        actionStack.translatesAutoresizingMaskIntoConstraints = false

        actionContentStack.orientation = .vertical
        actionContentStack.alignment = .leading
        actionContentStack.spacing = Self.mediaTimelineSpacing
        actionContentStack.isHidden = true
        actionContentStack.alphaValue = 0
        actionContentStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        actionContentStack.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        actionContentStack.translatesAutoresizingMaskIntoConstraints = false

        mediaControlRowStack.orientation = .horizontal
        mediaControlRowStack.alignment = .centerY
        mediaControlRowStack.distribution = .fill
        mediaControlRowStack.spacing = 10
        mediaControlRowStack.translatesAutoresizingMaskIntoConstraints = false

        mediaTimelineStack.orientation = .vertical
        mediaTimelineStack.alignment = .leading
        mediaTimelineStack.spacing = 0
        mediaTimelineStack.translatesAutoresizingMaskIntoConstraints = false

        mediaTimelineSlider.target = self
        mediaTimelineSlider.action = #selector(seekMediaTimeline(_:))
        mediaTimelineSlider.isEnabled = false
        mediaTimelineSlider.onPreviewValueChanged = { [weak self] value in
            self?.previewMediaTimelineValue(value)
        }
        mediaTimelineSlider.onInteractionChanged = { [weak self] isInteracting in
            guard let self else { return }
            if isInteracting {
                self.autoCloseTimer?.invalidate()
                self.autoCloseTimer = nil
            } else if !self.isHovering {
                self.restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
            }
        }
        mediaTimelineSlider.translatesAutoresizingMaskIntoConstraints = false
        mediaTimelineSlider.setContentHuggingPriority(.defaultLow, for: .horizontal)
        mediaTimelineSlider.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        mediaTimelineStack.addArrangedSubview(mediaTimelineSlider)
        mediaTimelineSlider.widthAnchor.constraint(equalTo: mediaTimelineStack.widthAnchor).isActive = true
        mediaTimelineSlider.heightAnchor.constraint(equalToConstant: Self.mediaTimelineRowHeight).isActive = true
        mediaTimelineWidthConstraint = mediaTimelineStack.widthAnchor.constraint(equalToConstant: Self.mediaTimelineWidth)
        mediaTimelineHeightConstraint = mediaTimelineStack.heightAnchor.constraint(equalToConstant: Self.mediaTimelineRowHeight)
        mediaTimelineWidthConstraint?.isActive = true
        mediaTimelineHeightConstraint?.isActive = true

        replyFieldContainer.wantsLayer = true
        replyFieldContainer.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.12).cgColor
        replyFieldContainer.layer?.cornerRadius = 14
        replyFieldContainer.layer?.masksToBounds = true
        replyFieldContainer.translatesAutoresizingMaskIntoConstraints = false

        replyTextField.placeholderString = "Reply"
        replyTextField.font = .systemFont(ofSize: 13, weight: .medium)
        replyTextField.textColor = .labelColor
        replyTextField.placeholderAttributedString = Self.placeholderString("Reply")
        replyTextField.backgroundColor = .clear
        replyTextField.drawsBackground = false
        replyTextField.isBordered = false
        replyTextField.focusRingType = .none
        replyTextField.lineBreakMode = .byTruncatingTail
        replyTextField.cell?.usesSingleLineMode = true
        replyTextField.cell?.wraps = false
        replyTextField.cell?.isScrollable = true
        replyTextField.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        replyTextField.translatesAutoresizingMaskIntoConstraints = false
        replyFieldContainer.addSubview(replyTextField)

        replyCursorView.wantsLayer = true
        replyCursorView.layer?.backgroundColor = NSColor.controlAccentColor.cgColor
        replyCursorView.layer?.cornerRadius = 1
        replyCursorView.translatesAutoresizingMaskIntoConstraints = false
        replyCursorView.isHidden = true
        replyFieldContainer.addSubview(replyCursorView)

        replyBackButton.target = self
        replyBackButton.action = #selector(cancelInlineReply)
        replyBackButton.isBordered = false
        replyBackButton.wantsLayer = true
        replyBackButton.layer?.cornerRadius = 14
        replyBackButton.layer?.masksToBounds = true
        replyBackButton.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.10).cgColor
        replyBackButton.contentTintColor = .white
        replyBackButton.focusRingType = .none
        replyBackButton.imagePosition = .imageOnly
        if let backImage = NSImage(systemSymbolName: "chevron.left", accessibilityDescription: "Back to notification actions") {
            replyBackButton.image = backImage.withSymbolConfiguration(.init(pointSize: 11, weight: .semibold))
        } else {
            replyBackButton.title = "<"
        }
        replyBackButton.translatesAutoresizingMaskIntoConstraints = false

        replySendButton.target = self
        replySendButton.action = #selector(sendInlineReply)
        replySendButton.isBordered = false
        replySendButton.wantsLayer = true
        replySendButton.layer?.cornerRadius = 14
        replySendButton.layer?.masksToBounds = false
        replySendButton.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.12).cgColor
        replySendButton.contentTintColor = .white
        replySendButton.focusRingType = .none
        replySendButton.imagePosition = .imageOnly
        if let sendImage = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: "Send reply") {
            replySendButton.image = sendImage.withSymbolConfiguration(.init(pointSize: 11, weight: .semibold))
        } else {
            replySendButton.title = ">"
        }
        replySendButton.translatesAutoresizingMaskIntoConstraints = false

        replyComposerView.orientation = .horizontal
        replyComposerView.alignment = .centerY
        replyComposerView.spacing = 8
        replyComposerView.isHidden = true
        replyComposerView.alphaValue = 0
        replyComposerView.translatesAutoresizingMaskIntoConstraints = false
        replyComposerView.addArrangedSubview(replyBackButton)
        replyComposerView.addArrangedSubview(replyFieldContainer)
        replyComposerView.addArrangedSubview(replySendButton)
        replyBackButton.widthAnchor.constraint(equalToConstant: 28).isActive = true
        replyBackButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
        replyFieldContainer.widthAnchor.constraint(equalToConstant: 160).isActive = true
        replyFieldContainer.heightAnchor.constraint(equalToConstant: 28).isActive = true
        replySendButton.widthAnchor.constraint(equalToConstant: 34).isActive = true
        replySendButton.heightAnchor.constraint(equalToConstant: 28).isActive = true

        actionContainerView.wantsLayer = true
        actionContainerView.layer?.masksToBounds = false
        actionContainerView.isHidden = true
        actionContainerView.alphaValue = 0
        actionContainerView.translatesAutoresizingMaskIntoConstraints = false
        actionContainerView.addSubview(actionContentStack)
        actionContainerView.addSubview(replyComposerView)

        let textStack = NSStackView(views: [titleLabel, mediaTitleLabel, bodyLabel, mediaBodyLabel])
        textStack.orientation = .vertical
        textStack.alignment = .leading
        textStack.spacing = 1
        textStack.translatesAutoresizingMaskIntoConstraints = false

        [iconView, textStack, expandButton, mediaPrimaryButton, actionContainerView].forEach {
            contentView.addSubview($0)
        }

        let iconCenterY = iconView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor)
        let iconTop = iconView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 18)
        let textCenterY = textStack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor)
        let textTop = textStack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 18)
        let iconWidth = iconView.widthAnchor.constraint(equalToConstant: isMediaNotification ? Self.mediaIconSize : Self.regularIconSize)
        let iconHeight = iconView.heightAnchor.constraint(equalToConstant: isMediaNotification ? Self.mediaIconSize : Self.regularIconSize)
        let textStackWidth = textStack.widthAnchor.constraint(equalToConstant: isMediaNotification ? Self.mediaTextColumnWidth : Self.textColumnWidth)
        let textAboveActions = textStack.bottomAnchor.constraint(lessThanOrEqualTo: actionContainerView.topAnchor, constant: -6)
        let actionBelowText = actionContainerView.topAnchor.constraint(equalTo: textStack.bottomAnchor, constant: 8)
        let actionLeadingToText = actionContainerView.leadingAnchor.constraint(equalTo: textStack.leadingAnchor)
        let actionLeadingToContent = actionContainerView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 24)
        let actionHeight = actionContainerView.heightAnchor.constraint(equalToConstant: 0)
        let replyCursorLeading = replyCursorView.leadingAnchor.constraint(equalTo: replyFieldContainer.leadingAnchor, constant: 12)
        iconCenterYConstraint = iconCenterY
        iconTopConstraint = iconTop
        iconWidthConstraint = iconWidth
        iconHeightConstraint = iconHeight
        textCenterYConstraint = textCenterY
        textTopConstraint = textTop
        textStackWidthConstraint = textStackWidth
        textAboveActionsConstraint = textAboveActions
        actionBelowTextConstraint = actionBelowText
        actionContainerLeadingToTextConstraint = actionLeadingToText
        actionContainerLeadingToContentConstraint = actionLeadingToContent
        actionContainerHeightConstraint = actionHeight
        replyCursorLeadingConstraint = replyCursorLeading

        NSLayoutConstraint.activate([
            iconView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
            iconCenterY,
            iconWidth,
            iconHeight,

            textStack.leadingAnchor.constraint(equalTo: iconView.trailingAnchor, constant: 12),
            textStack.trailingAnchor.constraint(lessThanOrEqualTo: expandButton.leadingAnchor, constant: -10),
            textCenterY,
            textStack.topAnchor.constraint(greaterThanOrEqualTo: contentView.topAnchor, constant: 14),
            textStack.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -4),
            textStackWidth,
            titleLabel.widthAnchor.constraint(equalTo: textStack.widthAnchor),
            mediaTitleLabel.widthAnchor.constraint(equalTo: textStack.widthAnchor),
            mediaTitleLabel.heightAnchor.constraint(equalToConstant: 19),
            bodyLabel.widthAnchor.constraint(equalTo: textStack.widthAnchor),
            mediaBodyLabel.widthAnchor.constraint(equalTo: textStack.widthAnchor),
            mediaBodyLabel.heightAnchor.constraint(equalToConstant: 17),

            expandButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
            expandButton.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            expandButton.widthAnchor.constraint(equalToConstant: 18),
            expandButton.heightAnchor.constraint(equalToConstant: 18),

            mediaPrimaryButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -28),
            mediaPrimaryButton.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 24),
            mediaPrimaryButton.widthAnchor.constraint(equalToConstant: 30),
            mediaPrimaryButton.heightAnchor.constraint(equalToConstant: 32),

            actionLeadingToText,
            actionContainerView.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -16),
            actionContainerView.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -6),
            actionHeight,

            actionContentStack.leadingAnchor.constraint(equalTo: actionContainerView.leadingAnchor),
            actionContentStack.trailingAnchor.constraint(lessThanOrEqualTo: actionContainerView.trailingAnchor),
            actionContentStack.centerYAnchor.constraint(equalTo: actionContainerView.centerYAnchor),

            replyTextField.leadingAnchor.constraint(equalTo: replyFieldContainer.leadingAnchor, constant: 12),
            replyTextField.trailingAnchor.constraint(equalTo: replyFieldContainer.trailingAnchor, constant: -10),
            replyTextField.centerYAnchor.constraint(equalTo: replyFieldContainer.centerYAnchor),
            replyTextField.heightAnchor.constraint(equalToConstant: 20),
            replyCursorLeading,
            replyCursorView.centerYAnchor.constraint(equalTo: replyFieldContainer.centerYAnchor),
            replyCursorView.widthAnchor.constraint(equalToConstant: 2),
            replyCursorView.heightAnchor.constraint(equalToConstant: 16),

            replyComposerView.leadingAnchor.constraint(equalTo: actionContainerView.leadingAnchor),
            replyComposerView.trailingAnchor.constraint(lessThanOrEqualTo: actionContainerView.trailingAnchor),
            replyComposerView.centerYAnchor.constraint(equalTo: actionContainerView.centerYAnchor)
        ])
        actionLeadingToContent.isActive = isMediaNotification
        actionLeadingToText.isActive = !isMediaNotification
    }

    private func configurePanelContentView(_ contentView: HoverTrackingView) {
        if #available(macOS 26.0, *) {
            let glassView = NSGlassEffectView(frame: contentView.frame)
            glassView.cornerRadius = 18
            glassView.autoresizingMask = [.width, .height]
            contentView.frame = glassView.bounds
            contentView.autoresizingMask = [.width, .height]
            glassView.contentView = contentView
            panel.contentView = glassView

            contentView.wantsLayer = true
            contentView.layer?.backgroundColor = NSColor.clear.cgColor
            contentView.layer?.masksToBounds = true
            contentView.focusRingType = .none
        } else {
            panel.contentView = contentView
            contentView.wantsLayer = true
            contentView.layer?.backgroundColor = NSColor(calibratedWhite: 0.07, alpha: 0.96).cgColor
            contentView.layer?.cornerRadius = 18
            contentView.layer?.masksToBounds = true
            contentView.focusRingType = .none
        }
    }

    private func restartAutoCloseTimer(after interval: TimeInterval) {
        autoCloseTimer?.invalidate()
        guard shouldAutoClose, !isHovering, activeReplyAction == nil else {
            autoCloseTimer = nil
            return
        }
        autoCloseTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            self?.close()
        }
    }

    private func positionNearTopRight(topY: CGFloat) {
        guard let screen = NSScreen.main else {
            panel.center()
            return
        }

        let visibleFrame = screen.visibleFrame
        let frame = panel.frame
        targetTopY = topY
        panel.setFrameOrigin(
            NSPoint(
                x: visibleFrame.maxX - frame.width - 18,
                y: topY - frame.height
            )
        )
    }

    @objc private func toggleExpanded() {
        guard hasLongContent else { return }
        isExpanded.toggle()
        applyDisplayState(animated: true)
        restartAutoCloseTimer(after: Self.defaultVisibleDuration)
    }

    private func applyDisplayState(animated: Bool) {
        let shouldShowActionRow = isMediaNotification
            ? currentActionContentHeight() > 0
            : (shouldShowMediaTimeline || detectedOTPCode != nil || !notificationActions.isEmpty || activeReplyAction != nil)
        let shouldFadeActionsOut = animated && !shouldShowActionRow && !actionContainerView.isHidden
        let keepsActionRowVisible = shouldShowActionRow || shouldFadeActionsOut
        let actionContentHeight = currentActionContentHeight()
        let usesTopLayout = isExpanded || isMediaNotification
        let actionOffset: CGFloat = keepsActionRowVisible && !usesTopLayout ? -max(0, (actionContentHeight / 2) - 2) : 0

        iconWidthConstraint?.constant = isMediaNotification ? Self.mediaIconSize : Self.regularIconSize
        iconHeightConstraint?.constant = isMediaNotification ? Self.mediaIconSize : Self.regularIconSize
        iconTopConstraint?.constant = isMediaNotification ? 18 : 18
        iconView.layer?.cornerRadius = isMediaNotification ? 12 : 9
        textTopConstraint?.constant = isMediaNotification ? 21 : 18
        textStackWidthConstraint?.constant = isMediaNotification ? Self.mediaTextColumnWidth : Self.textColumnWidth
        actionBelowTextConstraint?.constant = isMediaNotification ? 18 : 8
        actionContainerLeadingToTextConstraint?.isActive = !isMediaNotification
        actionContainerLeadingToContentConstraint?.isActive = isMediaNotification
        mediaTimelineWidthConstraint?.constant = Self.mediaTimelineWidth
        mediaTimelineHeightConstraint?.constant = Self.mediaTimelineRowHeight
        mediaPrimaryButton.isHidden = !isMediaNotification || mediaPrimaryButton.identifier == nil
        titleLabel.font = .systemFont(ofSize: isMediaNotification ? 15 : 14, weight: .semibold)
        bodyLabel.font = .systemFont(ofSize: isMediaNotification ? 13 : 13, weight: isMediaNotification ? .semibold : .regular)
        trackingContentView?.layoutSubtreeIfNeeded()

        if isMediaNotification {
            titleLabel.isHidden = true
            bodyLabel.isHidden = true
            mediaTitleLabel.isHidden = false
            mediaBodyLabel.isHidden = false
            mediaTitleLabel.stringValue = fullTitle
            mediaBodyLabel.stringValue = fullBody
        } else if isExpanded {
            titleLabel.isHidden = false
            bodyLabel.isHidden = false
            mediaTitleLabel.isHidden = true
            mediaBodyLabel.isHidden = true
            titleLabel.stringValue = fullTitle
            titleLabel.lineBreakMode = .byWordWrapping
            titleLabel.maximumNumberOfLines = 1
            titleLabel.cell?.usesSingleLineMode = false
            titleLabel.cell?.wraps = true
            bodyLabel.stringValue = fullBody
            bodyLabel.lineBreakMode = .byWordWrapping
            bodyLabel.maximumNumberOfLines = Self.expandedBodyLineLimit
            bodyLabel.cell?.usesSingleLineMode = false
            bodyLabel.cell?.wraps = true
            bodyLabel.cell?.truncatesLastVisibleLine = true
        } else {
            titleLabel.isHidden = false
            bodyLabel.isHidden = false
            mediaTitleLabel.isHidden = true
            mediaBodyLabel.isHidden = true
            titleLabel.stringValue = Self.fittedSingleLine(
                fullTitle,
                font: titleLabel.font ?? .systemFont(ofSize: 14, weight: .semibold)
            )
            titleLabel.lineBreakMode = .byTruncatingTail
            titleLabel.maximumNumberOfLines = 1
            titleLabel.cell?.usesSingleLineMode = true
            titleLabel.cell?.wraps = false
            bodyLabel.stringValue = Self.fittedSingleLine(
                fullBody,
                font: bodyLabel.font ?? .systemFont(ofSize: 13)
            )
            bodyLabel.lineBreakMode = .byTruncatingTail
            bodyLabel.maximumNumberOfLines = 1
            bodyLabel.cell?.usesSingleLineMode = true
            bodyLabel.cell?.wraps = false
        }

        iconCenterYConstraint?.constant = !isExpanded ? actionOffset : 0
        textCenterYConstraint?.constant = !isExpanded ? actionOffset : 0
        iconCenterYConstraint?.isActive = !usesTopLayout
        textCenterYConstraint?.isActive = !usesTopLayout
        iconTopConstraint?.isActive = usesTopLayout
        textTopConstraint?.isActive = usesTopLayout
        textAboveActionsConstraint?.isActive = keepsActionRowVisible
        actionBelowTextConstraint?.isActive = keepsActionRowVisible
        actionContainerHeightConstraint?.constant = keepsActionRowVisible ? actionContentHeight : 0
        updateExpandButton()
        animateDisplayChanges(
            height:
            isExpanded
                ? Self.expandedHeight(
                    forTitle: fullTitle,
                    body: fullBody,
                    includesActions: shouldShowActionRow,
                    actionContentHeight: actionContentHeight,
                    titleFont: titleLabel.font ?? .systemFont(ofSize: 14, weight: .semibold),
                    bodyFont: bodyLabel.font ?? .systemFont(ofSize: 13)
                )
                : (isMediaNotification
                   ? Self.mediaPanelHeight
                   : (shouldShowActionRow ? Self.collapsedHeight + actionContentHeight + 2 : Self.collapsedHeight)),
            actionsVisible: shouldShowActionRow,
            animated: animated
        )
    }

    private func rebuildActionButtons() {
        actionContentStack.arrangedSubviews.forEach { view in
            actionContentStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        mediaControlRowStack.arrangedSubviews.forEach { view in
            mediaControlRowStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        actionStack.arrangedSubviews.forEach { view in
            actionStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        actionStack.spacing = 10
        mediaPrimaryButton.identifier = nil
        mediaPrimaryButton.image = nil
        mediaPrimaryButton.isHidden = true

        if isMediaNotification {
            updateMediaPrimaryButton()
            let mediaActions = normalizedMediaActions()
            if let previous = mediaActions.previous {
                mediaControlRowStack.addArrangedSubview(mediaActionButton(for: previous, pointSize: 22, width: 28))
            } else {
                mediaControlRowStack.addArrangedSubview(mediaControlSpacer(width: 28))
            }
            if shouldShowMediaTimeline {
                mediaTimelineSlider.isEnabled = true
                mediaTimelineSlider.toolTip = "Seek Android media"
                mediaControlRowStack.addArrangedSubview(mediaTimelineStack)
            }
            if let next = mediaActions.next {
                mediaControlRowStack.addArrangedSubview(mediaActionButton(for: next, pointSize: 22, width: 28))
            } else {
                mediaControlRowStack.addArrangedSubview(mediaControlSpacer(width: 28))
            }
            if shouldShowMediaTimeline || mediaActions.previous != nil || mediaActions.next != nil {
                actionContentStack.addArrangedSubview(mediaControlRowStack)
            }
            actionStack.isHidden = true
            actionStack.alphaValue = 0
            actionContentStack.isHidden = true
            actionContentStack.alphaValue = 0
            replyComposerView.isHidden = true
            replyComposerView.alphaValue = 0
            actionContainerView.isHidden = true
            actionContainerView.alphaValue = 0
            return
        }

        if shouldShowMediaTimeline {
            mediaTimelineSlider.isEnabled = true
            mediaTimelineSlider.toolTip = "Seek Android media"
            mediaTimelineSlider.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            actionContentStack.addArrangedSubview(mediaTimelineStack)
        }

        if detectedOTPCode != nil {
            let button = NotificationActionButton(title: "Copy code", target: self, action: #selector(performNotificationAction(_:)))
            button.identifier = NSUserInterfaceItemIdentifier(Self.copyOTPActionId)
            button.controlSize = .small
            button.font = .systemFont(ofSize: 11, weight: .semibold)
            button.isEnabled = true
            configureActionButton(button)
            button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            button.widthAnchor.constraint(equalToConstant: Self.actionButtonWidth(for: button.title, font: button.font)).isActive = true
            button.heightAnchor.constraint(equalToConstant: 24).isActive = true
            actionStack.addArrangedSubview(button)
        }

        notificationActions.forEach { action in
            let button = NotificationActionButton(title: action.cleanTitle, target: self, action: #selector(performNotificationAction(_:)))
            button.identifier = NSUserInterfaceItemIdentifier(action.id)
            button.controlSize = .small
            button.font = .systemFont(ofSize: 11, weight: .semibold)
            button.isEnabled = true
            configureActionButton(button)
            button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            button.widthAnchor.constraint(equalToConstant: Self.actionButtonWidth(for: action.cleanTitle, font: button.font)).isActive = true
            button.heightAnchor.constraint(equalToConstant: 24).isActive = true
            actionStack.addArrangedSubview(button)
        }
        if detectedOTPCode != nil || !notificationActions.isEmpty {
            actionContentStack.addArrangedSubview(actionStack)
        }
        actionStack.isHidden = true
        actionStack.alphaValue = 0
        actionContentStack.isHidden = true
        actionContentStack.alphaValue = 0
        replyComposerView.isHidden = true
        replyComposerView.alphaValue = 0
        actionContainerView.isHidden = true
        actionContainerView.alphaValue = 0
    }

    private func currentActionContentHeight() -> CGFloat {
        if activeReplyAction != nil {
            return Self.actionRowHeight
        }

        if isMediaNotification {
            let mediaActions = normalizedMediaActions()
            return shouldShowMediaTimeline || mediaActions.previous != nil || mediaActions.next != nil
                ? Self.mediaControlRowHeight
                : 0
        }

        let showsButtons = detectedOTPCode != nil || !notificationActions.isEmpty
        switch (shouldShowMediaTimeline, showsButtons) {
        case (true, true):
            return Self.mediaTimelineRowHeight + Self.mediaTimelineSpacing + Self.actionRowHeight
        case (true, false):
            return Self.mediaTimelineRowHeight
        case (false, true):
            return Self.actionRowHeight
        case (false, false):
            return 0
        }
    }

    private var shouldShowMediaTimeline: Bool {
        guard isMediaNotification else { return false }
        return (mediaDurationMillis ?? 0) > 0 && mediaPositionMillis != nil
    }

    private func updateMediaTimelineSlider() {
        guard shouldShowMediaTimeline, let duration = mediaDurationMillis else { return }
        guard !mediaTimelineSlider.isInteracting else { return }
        let position = currentMediaPositionMillis()
        isUpdatingMediaTimelineSlider = true
        mediaTimelineSlider.minimumValue = 0
        mediaTimelineSlider.maximumValue = Double(duration)
        mediaTimelineSlider.timelineValue = Double(position)
        mediaTimelineSlider.isEnabled = true
        mediaTimelineSlider.elapsedText = Self.formattedMediaTime(position)
        mediaTimelineSlider.remainingText = Self.formattedMediaTime(max(duration - position, 0))
        isUpdatingMediaTimelineSlider = false
    }

    private func previewMediaTimelineValue(_ value: Double) {
        guard shouldShowMediaTimeline, let duration = mediaDurationMillis else { return }
        let targetMillis = Int64(value.rounded()).clamped(to: 0...duration)
        mediaTimelineSlider.elapsedText = Self.formattedMediaTime(targetMillis)
        mediaTimelineSlider.remainingText = Self.formattedMediaTime(max(duration - targetMillis, 0))
    }

    private func updateMediaTimelineTimer() {
        guard shouldShowMediaTimeline, mediaIsPlaying else {
            stopMediaTimelineTimer()
            return
        }

        guard mediaTimelineTimer == nil else { return }

        let timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            self.updateMediaTimelineSlider()
            if let duration = self.mediaDurationMillis,
               self.currentMediaPositionMillis() >= duration {
                self.stopMediaTimelineTimer()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        mediaTimelineTimer = timer
    }

    private func stopMediaTimelineTimer() {
        mediaTimelineTimer?.invalidate()
        mediaTimelineTimer = nil
    }

    private func currentMediaPositionMillis() -> Int64 {
        guard let basePosition = mediaPositionMillis else { return 0 }
        let duration = mediaDurationMillis ?? basePosition
        guard mediaIsPlaying, let updatedAt = mediaPositionUpdatedAt else {
            return min(max(basePosition, 0), duration)
        }

        let elapsedMillis = Int64(Date().timeIntervalSince(updatedAt) * 1000)
        let compensatedPosition = basePosition + elapsedMillis + Self.activeMediaDisplayOffsetMillis
        return min(max(compensatedPosition, 0), duration)
    }

    @objc private func seekMediaTimeline(_ sender: MediaTimelineControl) {
        guard !isUpdatingMediaTimelineSlider else { return }
        guard let notificationKey, !notificationKey.isEmpty else { return }

        let targetMillis = Int64(sender.timelineValue.rounded())
        mediaPositionMillis = targetMillis
        mediaPositionUpdatedAt = Date()
        onAction(notificationKey, Self.mediaSeekActionId, String(targetMillis)) { [weak self] success, _ in
            DispatchQueue.main.async {
                if !success {
                    self?.updateMediaTimelineSlider()
                }
            }
        }
    }

    @objc private func performNotificationAction(_ sender: NSButton) {
        guard let actionId = sender.identifier?.rawValue else {
            return
        }

        if actionId == Self.copyOTPActionId {
            copyDetectedOTPCode(from: sender)
            return
        }

        guard
            let notificationKey,
            !notificationKey.isEmpty,
            let action = notificationActions.first(where: { $0.id == actionId })
        else {
            return
        }

        if action.requiresTextInput == true {
            showInlineReplyComposer(for: action)
            return
        }

        let previousMediaIsPlaying = mediaIsPlaying
        let updatedPlaybackOptimistically = applyOptimisticMediaPlaybackState(for: action.cleanTitle)
        sender.isEnabled = false
        onAction(notificationKey, action.id, nil) { [weak self, weak sender] success, _ in
            DispatchQueue.main.async {
                sender?.isEnabled = true
                if !success, updatedPlaybackOptimistically {
                    self?.mediaIsPlaying = previousMediaIsPlaying
                    self?.updateMediaPrimaryButton()
                    self?.updateMediaTimelineTimer()
                }
                if success && self?.isMediaNotification != true {
                    self?.close()
                }
            }
        }
    }

    private func applyOptimisticMediaPlaybackState(for title: String) -> Bool {
        guard isMediaNotification, Self.isPrimaryMediaAction(title) else { return false }

        let normalizedTitle = Self.normalizedMediaActionTitle(title)
        if normalizedTitle.contains("pause") || normalizedTitle.contains("stop") {
            mediaIsPlaying = false
        } else if normalizedTitle.contains("play") || normalizedTitle.contains("resume") {
            mediaIsPlaying = true
            mediaPositionUpdatedAt = Date()
        } else {
            return false
        }

        updateMediaPrimaryButton()
        updateMediaTimelineTimer()
        return true
    }

    private func copyDetectedOTPCode(from sender: NSButton) {
        guard let detectedOTPCode, !detectedOTPCode.isEmpty else { return }

        sender.isEnabled = false
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(detectedOTPCode, forType: .string)
        dismissFromAndroid()
    }

    private func showInlineReplyComposer(for action: AndroidMirroredNotificationAction) {
        activeReplyAction = action
        autoCloseTimer?.invalidate()
        autoCloseTimer = nil
        replyTextField.stringValue = ""
        updateReplyCursorPosition()
        let placeholder = action.inputLabel?.isEmpty == false ? action.inputLabel! : "Reply"
        replyTextField.placeholderString = placeholder
        replyTextField.placeholderAttributedString = Self.placeholderString(placeholder)
        replySendButton.isEnabled = true
        replyBackButton.isEnabled = true
        replyTextField.isEnabled = true
        applyDisplayState(animated: true)
        panel.orderFrontRegardless()
        startInlineReplyKeyCapture()
        startReplyCursorBlink()
    }

    @objc private func cancelInlineReply() {
        activeReplyAction = nil
        stopInlineReplyKeyCapture()
        stopReplyCursorBlink()
        replyTextField.stringValue = ""
        replyBackButton.isEnabled = true
        replySendButton.isEnabled = true
        replyTextField.isEnabled = true
        applyDisplayState(animated: true)
        restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
    }

    @objc private func sendInlineReply() {
        guard
            let notificationKey,
            !notificationKey.isEmpty,
            let action = activeReplyAction
        else {
            return
        }

        let replyText = replyTextField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !replyText.isEmpty else { return }

        activeReplyAction = nil
        stopInlineReplyKeyCapture()
        stopReplyCursorBlink()
        replyTextField.stringValue = ""
        replyBackButton.isEnabled = true
        replySendButton.isEnabled = true
        replyTextField.isEnabled = true
        applyDisplayState(animated: true)
        restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
        onAction(notificationKey, action.id, replyText) { [weak self] success, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if !success {
                    self.applyDisplayState(animated: true)
                }
            }
        }
    }

    private func startInlineReplyKeyCapture() {
        stopInlineReplyKeyCapture()
        NSApp.activate(ignoringOtherApps: true)
        replyKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, self.activeReplyAction != nil else {
                return event
            }
            return self.handleInlineReplyKey(event)
        }
    }

    private func stopInlineReplyKeyCapture() {
        if let replyKeyMonitor {
            NSEvent.removeMonitor(replyKeyMonitor)
            self.replyKeyMonitor = nil
        }
    }

    private func startReplyCursorBlink() {
        stopReplyCursorBlink()
        replyCursorView.isHidden = false
        replyCursorView.alphaValue = 1
        updateReplyCursorPosition()
        replyCursorBlinkTimer = Timer.scheduledTimer(withTimeInterval: 0.55, repeats: true) { [weak self] _ in
            guard let self, self.activeReplyAction != nil else { return }
            self.replyCursorView.alphaValue = self.replyCursorView.alphaValue == 0 ? 1 : 0
        }
    }

    private func stopReplyCursorBlink() {
        replyCursorBlinkTimer?.invalidate()
        replyCursorBlinkTimer = nil
        replyCursorView.isHidden = true
        replyCursorView.alphaValue = 0
    }

    private func updateReplyCursorPosition() {
        let font = replyTextField.font ?? .systemFont(ofSize: 13, weight: .medium)
        let typedWidth = Self.textWidth(replyTextField.stringValue, font: font)
        let maxCursorX = max(12, replyFieldContainer.bounds.width - 14)
        replyCursorLeadingConstraint?.constant = min(12 + ceil(typedWidth), maxCursorX)
        replyFieldContainer.layoutSubtreeIfNeeded()
    }

    private func handleInlineReplyKey(_ event: NSEvent) -> NSEvent? {
        if event.modifierFlags.intersection([.command, .control, .option]).isEmpty == false {
            return event
        }

        switch event.keyCode {
        case 36, 76:
            sendInlineReply()
            return nil
        case 51:
            let currentText = replyTextField.stringValue
            if !currentText.isEmpty {
                replyTextField.stringValue = String(currentText.dropLast())
                updateReplyCursorPosition()
                replyCursorView.alphaValue = 1
            }
            return nil
        case 53:
            cancelInlineReply()
            return nil
        default:
            guard let characters = event.characters, !characters.isEmpty else {
                return nil
            }
            replyTextField.stringValue += characters.filter { !$0.isNewline }
            updateReplyCursorPosition()
            replyCursorView.alphaValue = 1
            return nil
        }
    }

    private func updateExpandButton() {
        expandButton.isHidden = isMediaNotification || !(hasLongContent && (isHovering || isExpanded))
        if let symbolName = isExpanded ? "chevron.down" : "chevron.right",
           let image = Self.chevronImage(named: symbolName, accessibilityDescription: isExpanded ? "Collapse" : "Expand") {
            expandButton.image = image
            expandButton.title = ""
        } else {
            expandButton.title = isExpanded ? "v" : ">"
        }
    }

    private func animateDisplayChanges(height: CGFloat, actionsVisible: Bool, animated: Bool) {
        let frame = panel.frame
        let topY = targetTopY ?? frame.maxY
        targetTopY = topY
        let shouldDeferResizeUntilFadeOut = animated && !actionsVisible && !actionContainerView.isHidden
        let showsReplyComposer = activeReplyAction != nil
        let newFrame = NSRect(
            x: frame.minX,
            y: topY - (shouldDeferResizeUntilFadeOut ? frame.height : height),
            width: frame.width,
            height: shouldDeferResizeUntilFadeOut ? frame.height : height
        )
        let finalFrame = NSRect(
            x: frame.minX,
            y: topY - height,
            width: frame.width,
            height: height
        )

        if actionsVisible {
            actionContainerView.isHidden = false
            actionContentStack.isHidden = showsReplyComposer
            actionStack.isHidden = showsReplyComposer
            replyComposerView.isHidden = false
            if !animated && actionContainerView.alphaValue == 0 {
                actionContainerView.alphaValue = 1
            }
            if animated && actionContainerView.alphaValue == 0 {
                actionContainerView.alphaValue = 0
                actionContentStack.alphaValue = showsReplyComposer ? 0 : 1
                actionStack.alphaValue = showsReplyComposer ? 0 : 1
                replyComposerView.alphaValue = showsReplyComposer ? 1 : 0
            }
        }

        let changes = {
            self.actionContainerView.alphaValue = actionsVisible ? 1 : 0
            self.actionContentStack.alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.actionStack.alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.replyComposerView.alphaValue = actionsVisible && showsReplyComposer ? 1 : 0
            self.panel.setFrame(newFrame, display: true, animate: false)
            self.panel.contentView?.layoutSubtreeIfNeeded()
            if self.panel.isVisible {
                self.onLayoutChange()
            }
        }

        guard animated else {
            changes()
            actionContentStack.isHidden = !actionsVisible || showsReplyComposer
            actionStack.isHidden = !actionsVisible || showsReplyComposer
            replyComposerView.isHidden = !actionsVisible || !showsReplyComposer
            actionContainerView.isHidden = !actionsVisible
            return
        }

        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0
            context.allowsImplicitAnimation = false
            self.panel.setFrame(newFrame, display: true, animate: false)
            self.trackingContentView?.layoutSubtreeIfNeeded()
            self.panel.contentView?.layoutSubtreeIfNeeded()
            if self.panel.isVisible {
                self.onLayoutChange()
            }
        }

        NSAnimationContext.runAnimationGroup { context in
            context.duration = Self.hoverAnimationDuration
            context.allowsImplicitAnimation = true
            context.timingFunction = CAMediaTimingFunction(name: actionsVisible ? .easeOut : .easeIn)
            self.actionContainerView.animator().alphaValue = actionsVisible ? 1 : 0
            self.actionContentStack.animator().alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.actionStack.animator().alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.replyComposerView.animator().alphaValue = actionsVisible && showsReplyComposer ? 1 : 0
        } completionHandler: { [weak self] in
            guard let self else { return }
            if !actionsVisible {
                self.actionContentStack.isHidden = true
                self.actionStack.isHidden = true
                self.replyComposerView.isHidden = true
                self.actionContainerView.isHidden = true
                self.textAboveActionsConstraint?.isActive = false
                self.actionBelowTextConstraint?.isActive = false
                self.actionContainerHeightConstraint?.constant = 0
                self.panel.setFrame(finalFrame, display: true, animate: false)
                self.trackingContentView?.layoutSubtreeIfNeeded()
                self.panel.contentView?.layoutSubtreeIfNeeded()
                if self.panel.isVisible {
                    self.onLayoutChange()
                }
            } else {
                self.actionContentStack.isHidden = showsReplyComposer
                self.actionStack.isHidden = showsReplyComposer
                self.replyComposerView.isHidden = !showsReplyComposer
            }
        }
    }

    private func configureGlassButton(_ button: NSButton, fallbackCornerRadius: CGFloat) {
        button.appearance = NSApp.effectiveAppearance
        button.contentTintColor = .labelColor
        button.focusRingType = .none
        if #available(macOS 26.0, *) {
            button.isBordered = true
            button.bezelStyle = .glass
        } else {
            button.isBordered = false
            button.wantsLayer = true
            button.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.10).cgColor
            button.layer?.cornerRadius = fallbackCornerRadius
        }
    }

    private func configureActionButton(_ button: NSButton) {
        button.appearance = NSAppearance(named: .darkAqua)
        button.isBordered = false
        button.wantsLayer = true
        button.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.08).cgColor
        button.layer?.cornerRadius = 12
        button.layer?.masksToBounds = true
        button.focusRingType = .none
        button.contentTintColor = .white
        button.alignment = .center
        button.cell?.alignment = .center
        applyReadableButtonTitle(button.title, to: button)
    }

    private func updateMediaPrimaryButton() {
        guard isMediaNotification, let primary = normalizedMediaActions().primary else {
            mediaPrimaryButton.identifier = nil
            mediaPrimaryButton.image = nil
            mediaPrimaryButton.isHidden = true
            return
        }

        mediaPrimaryButton.identifier = NSUserInterfaceItemIdentifier(primary.id)
        mediaPrimaryButton.toolTip = primary.cleanTitle
        mediaPrimaryButton.isEnabled = true
        mediaPrimaryButton.isHidden = false
        mediaPrimaryButton.title = ""
        let visualSymbolName = mediaIsPlaying ? "pause.fill" : "play.fill"
        if let image = NSImage(systemSymbolName: visualSymbolName, accessibilityDescription: primary.cleanTitle) {
            setMediaPrimarySymbol(visualSymbolName, image: image.withSymbolConfiguration(.init(pointSize: 24, weight: .bold)))
        } else if let symbolName = Self.mediaActionSymbolName(for: primary.cleanTitle),
           let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: primary.cleanTitle) {
            setMediaPrimarySymbol(symbolName, image: image.withSymbolConfiguration(.init(pointSize: 24, weight: .bold)))
        } else {
            currentMediaPrimarySymbolName = nil
            mediaPrimaryButton.title = Self.isPrimaryMediaAction(primary.cleanTitle) ? "II" : ""
        }
    }

    private func setMediaPrimarySymbol(_ symbolName: String, image: NSImage?) {
        guard currentMediaPrimarySymbolName != symbolName else {
            mediaPrimaryButton.image = image
            mediaPrimaryButton.alphaValue = 1
            return
        }

        currentMediaPrimarySymbolName = symbolName
        guard mediaPrimaryButton.image != nil else {
            mediaPrimaryButton.image = image
            mediaPrimaryButton.alphaValue = 1
            return
        }

        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.10
            context.timingFunction = CAMediaTimingFunction(name: .easeIn)
            mediaPrimaryButton.animator().alphaValue = 0.25
        } completionHandler: { [weak self] in
            guard let self else { return }
            self.mediaPrimaryButton.image = image
            NSAnimationContext.runAnimationGroup { context in
                context.duration = 0.14
                context.timingFunction = CAMediaTimingFunction(name: .easeOut)
                self.mediaPrimaryButton.animator().alphaValue = 1
            }
        }
    }

    private func mediaActionButton(
        for action: AndroidMirroredNotificationAction,
        pointSize: CGFloat,
        width: CGFloat
    ) -> NSView {
        let button = NotificationActionButton(title: "", target: self, action: #selector(performNotificationAction(_:)))
        button.identifier = NSUserInterfaceItemIdentifier(action.id)
        button.toolTip = action.cleanTitle
        button.isEnabled = true
        button.isBordered = false
        button.wantsLayer = true
        button.layer?.backgroundColor = NSColor.clear.cgColor
        button.layer?.masksToBounds = false
        button.pressAnimationScale = 0.88
        button.focusRingType = .none
        button.contentTintColor = .white
        button.imagePosition = .imageOnly
        button.translatesAutoresizingMaskIntoConstraints = false
        if let symbolName = Self.mediaActionSymbolName(for: action.cleanTitle),
           let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: action.cleanTitle) {
            button.image = image.withSymbolConfiguration(.init(pointSize: pointSize, weight: .bold))
        }
        button.widthAnchor.constraint(equalToConstant: width).isActive = true
        button.heightAnchor.constraint(equalToConstant: Self.mediaControlRowHeight).isActive = true
        return button
    }

    private func mediaControlSpacer(width: CGFloat) -> NSView {
        let spacer = NSView()
        spacer.translatesAutoresizingMaskIntoConstraints = false
        spacer.widthAnchor.constraint(equalToConstant: width).isActive = true
        spacer.heightAnchor.constraint(equalToConstant: Self.mediaControlRowHeight).isActive = true
        return spacer
    }

    private func mediaControlButtonHitTarget(at point: NSPoint, in contentView: NSView) -> NSButton? {
        guard isMediaNotification, !mediaControlRowStack.isHidden else { return nil }

        let buttonHitOutset = NSSize(width: 8, height: 6)
        for case let button as NSButton in mediaControlRowStack.arrangedSubviews {
            guard
                !button.isHidden,
                button.alphaValue > 0.01,
                button.isEnabled
            else {
                continue
            }

            let buttonPoint = button.convert(point, from: contentView)
            if button.bounds.insetBy(dx: -buttonHitOutset.width, dy: -buttonHitOutset.height).contains(buttonPoint) {
                return button
            }
        }

        return nil
    }

    private func normalizedMediaActions() -> (
        previous: AndroidMirroredNotificationAction?,
        primary: AndroidMirroredNotificationAction?,
        next: AndroidMirroredNotificationAction?
    ) {
        let previous = notificationActions.first { Self.isPreviousMediaAction($0.cleanTitle) }
            ?? notificationActions.first { Self.isRewindMediaAction($0.cleanTitle) }
        let primary = notificationActions.first { Self.isPrimaryMediaAction($0.cleanTitle) }
        let next = notificationActions.first {
            let normalized = Self.normalizedMediaActionTitle($0.cleanTitle)
            return normalized.contains("next") || normalized.contains("forward")
        }
        return (previous, primary, next)
    }

    private func applyReadableButtonTitle(_ title: String, to button: NSButton) {
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.alignment = .center
        paragraphStyle.lineBreakMode = .byTruncatingTail
        button.cell?.lineBreakMode = .byTruncatingTail
        button.attributedTitle = NSAttributedString(
            string: title,
            attributes: [
                .font: button.font ?? .systemFont(ofSize: 12, weight: .medium),
                .foregroundColor: NSColor.white,
                .paragraphStyle: paragraphStyle
            ]
        )
        button.attributedAlternateTitle = button.attributedTitle
    }

    private static func actionButtonWidth(for title: String, font: NSFont?) -> CGFloat {
        let font = font ?? .systemFont(ofSize: 11, weight: .semibold)
        let titleWidth = textWidth(title, font: font)
        return min(max(ceil(titleWidth) + 26, 54), 118)
    }

    private static func mediaActionSymbolName(for title: String) -> String? {
        let normalizedTitle = normalizedMediaActionTitle(title)
        if isPreviousMediaAction(title) || isRewindMediaAction(title) {
            return "backward.fill"
        }
        if normalizedTitle.contains("next") || normalizedTitle.contains("forward") {
            return "forward.fill"
        }
        if normalizedTitle.contains("pause") {
            return "pause.fill"
        }
        if normalizedTitle.contains("play") || normalizedTitle.contains("resume") {
            return "play.fill"
        }
        if normalizedTitle.contains("stop") {
            return "stop.fill"
        }
        return nil
    }

    private static func isPrimaryMediaAction(_ title: String) -> Bool {
        let normalizedTitle = normalizedMediaActionTitle(title)
        return normalizedTitle.contains("play")
            || normalizedTitle.contains("pause")
            || normalizedTitle.contains("resume")
    }

    private static func isPreviousMediaAction(_ title: String) -> Bool {
        let normalizedTitle = normalizedMediaActionTitle(title)
        let tokens = Set(normalizedTitle.components(separatedBy: " "))
        return tokens.contains("previous")
            || tokens.contains("prev")
            || normalizedTitle.contains("skip previous")
            || normalizedTitle.contains("skip to previous")
    }

    private static func isRewindMediaAction(_ title: String) -> Bool {
        let normalizedTitle = normalizedMediaActionTitle(title)
        return normalizedTitle.contains("rewind")
    }

    private static func normalizedMediaActionTitle(_ title: String) -> String {
        title
            .lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func chevronImage(named symbolName: String, accessibilityDescription: String) -> NSImage? {
        guard let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: accessibilityDescription) else {
            return nil
        }
        if #available(macOS 12.0, *) {
            return image.withSymbolConfiguration(.init(pointSize: 10, weight: .semibold))
        }
        return image
    }

    private static func fittedSingleLine(_ text: String, font: NSFont) -> String {
        let singleLine = singleLine(text)
        guard textWidth(singleLine, font: font) > textColumnWidth else {
            return singleLine
        }

        let ellipsis = "..."
        var lowerBound = 0
        var upperBound = singleLine.count
        let characters = Array(singleLine)

        while lowerBound < upperBound {
            let midpoint = (lowerBound + upperBound + 1) / 2
            let candidate = String(characters.prefix(midpoint)) + ellipsis
            if textWidth(candidate, font: font) <= textColumnWidth {
                lowerBound = midpoint
            } else {
                upperBound = midpoint - 1
            }
        }

        return String(characters.prefix(lowerBound)).trimmingCharacters(in: .whitespaces) + ellipsis
    }

    private static func textWidth(_ text: String, font: NSFont) -> CGFloat {
        (text as NSString).size(withAttributes: [.font: font]).width
    }

    private static func formattedMediaTime(_ milliseconds: Int64) -> String {
        let totalSeconds = max(0, milliseconds / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
        }

        return String(format: "%lld:%02lld", minutes, seconds)
    }

    private static func placeholderString(_ text: String) -> NSAttributedString {
        NSAttributedString(
            string: text,
            attributes: [
                .font: NSFont.systemFont(ofSize: 13, weight: .medium),
                .foregroundColor: NSColor.secondaryLabelColor
            ]
        )
    }

    private static func requiresExpansion(
        title: String,
        body: String,
        titleFont: NSFont,
        bodyFont: NSFont
    ) -> Bool {
        textWidth(title, font: titleFont) > textColumnWidth || textWidth(body, font: bodyFont) > textColumnWidth
    }

    private static func expandedHeight(
        forTitle title: String,
        body: String,
        includesActions: Bool,
        actionContentHeight: CGFloat,
        titleFont: NSFont,
        bodyFont: NSFont
    ) -> CGFloat {
        let titleRect = (singleLine(title) as NSString).boundingRect(
            with: NSSize(width: textColumnWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: titleFont]
        )
        let bodyRect = (singleLine(body) as NSString).boundingRect(
            with: NSSize(width: textColumnWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: bodyFont]
        )
        let bodyLineHeight = ceil(bodyFont.pointSize + 3)
        let titleLineHeight = ceil(titleFont.pointSize + 4)
        let estimatedBodyLines = max(1, ceil(ceil(bodyRect.height) / max(bodyLineHeight, 1)))
        let visibleBodyLines = min(estimatedBodyLines, CGFloat(expandedBodyLineLimit))
        let cappedBodyHeight = visibleBodyLines * bodyLineHeight
        let topPadding: CGFloat = 18
        let bottomPadding: CGFloat = includesActions ? 6 : 0
        let titleBodySpacing: CGFloat = 1
        let actionHeight: CGFloat = includesActions ? 8 + actionContentHeight : 0
        let contentHeight = topPadding
            + max(ceil(titleRect.height), titleLineHeight)
            + titleBodySpacing
            + cappedBodyHeight
            + actionHeight
            + bottomPadding
        return min(expandedMaxHeight, max(expandedMinHeight, ceil(contentHeight)))
    }

    private static func detectOTPCode(in textParts: [String?]) -> String? {
        let message = textParts
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard !message.isEmpty else { return nil }

        let lowercasedMessage = message.lowercased()
        let otpKeywords = [
            "otp",
            "one time",
            "one-time",
            "verification",
            "verify",
            "code",
            "passcode",
            "security code",
            "login code",
            "authentication"
        ]
        guard otpKeywords.contains(where: { lowercasedMessage.contains($0) }) else {
            return nil
        }

        let range = NSRange(message.startIndex..<message.endIndex, in: message)
        guard
            let regex = try? NSRegularExpression(pattern: #"(?<!\d)(\d(?:[ -]?\d){3,7})(?!\d)"#)
        else {
            return nil
        }

        for match in regex.matches(in: message, range: range) {
            guard let matchRange = Range(match.range(at: 1), in: message) else { continue }
            let candidate = message[matchRange].filter(\.isNumber)
            if candidate.count >= 4 && candidate.count <= 8 {
                return String(candidate)
            }
        }

        return nil
    }

    private static func singleLine(_ text: String) -> String {
        text
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    fileprivate static func singleLineForDisplay(_ text: String) -> String {
        singleLine(text)
    }

    @objc private func dismissFromAndroid() {
        if let notificationKey, !notificationKey.isEmpty {
            onDismiss(notificationKey)
        }
        close()
    }
}

private class HoverTrackingView: NSView {
    var onHoverChanged: ((Bool) -> Void)?
    var onSwipeRight: (() -> Void)?
    var onSwipeUp: (() -> Void)?
    var preferredHitTest: ((NSPoint) -> NSView?)?
    var allowsScrollWheelSwipeUp = true
    private var trackingAreaReference: NSTrackingArea?
    private var horizontalSwipeAmount: CGFloat = 0
    private var verticalSwipeAmount: CGFloat = 0
    private var didTriggerSwipeDismiss = false
    private var didTriggerSwipeUp = false
    private static let swipeDismissThreshold: CGFloat = 80
    private static let swipeUpThreshold: CGFloat = 70

    override func hitTest(_ point: NSPoint) -> NSView? {
        preferredHitTest?(point) ?? super.hitTest(point)
    }

    override func updateTrackingAreas() {
        if let trackingAreaReference {
            removeTrackingArea(trackingAreaReference)
        }

        let trackingArea = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
        trackingAreaReference = trackingArea
        super.updateTrackingAreas()
    }

    override func mouseEntered(with event: NSEvent) {
        onHoverChanged?(true)
    }

    override func mouseExited(with event: NSEvent) {
        onHoverChanged?(false)
    }

    override func swipe(with event: NSEvent) {
        if event.deltaX < 0 {
            triggerSwipeDismiss()
            return
        }
        if event.deltaY > 0 {
            triggerSwipeUp()
            return
        }
        super.swipe(with: event)
    }

    override func scrollWheel(with event: NSEvent) {
        let phase = event.phase
        if phase.contains(.began) || phase.contains(.mayBegin) {
            resetSwipeTracking()
        }

        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let vertical = normalizedFingerVerticalDelta(from: event)
        let isHorizontalSwipe = abs(horizontal) > abs(vertical) * 1.4 && abs(horizontal) > 0.4
        let isVerticalSwipe = abs(vertical) > abs(horizontal) * 1.4 && abs(vertical) > 0.4

        if isHorizontalSwipe {
            horizontalSwipeAmount += horizontal
            if horizontalSwipeAmount < -Self.swipeDismissThreshold {
                triggerSwipeDismiss()
                return
            }
        } else if allowsScrollWheelSwipeUp && isVerticalSwipe && onSwipeUp != nil {
            verticalSwipeAmount += vertical
            if verticalSwipeAmount > Self.swipeUpThreshold {
                triggerSwipeUp()
                return
            }
        } else if abs(vertical) > abs(horizontal) {
            resetSwipeTracking()
        }

        if phase.contains(.ended)
            || phase.contains(.cancelled)
            || event.momentumPhase.contains(.ended)
            || event.momentumPhase.contains(.cancelled) {
            resetSwipeTracking()
        }

        super.scrollWheel(with: event)
    }

    private func normalizedFingerHorizontalDelta(from event: NSEvent) -> CGFloat {
        if event.isDirectionInvertedFromDevice {
            return -event.scrollingDeltaX
        }
        return event.scrollingDeltaX
    }

    private func normalizedFingerVerticalDelta(from event: NSEvent) -> CGFloat {
        if event.isDirectionInvertedFromDevice {
            return -event.scrollingDeltaY
        }
        return event.scrollingDeltaY
    }

    private func triggerSwipeDismiss() {
        guard !didTriggerSwipeDismiss else { return }
        didTriggerSwipeDismiss = true
        onSwipeRight?()
    }

    private func triggerSwipeUp() {
        guard !didTriggerSwipeUp else { return }
        didTriggerSwipeUp = true
        onSwipeUp?()
    }

    private func resetSwipeTracking() {
        horizontalSwipeAmount = 0
        verticalSwipeAmount = 0
        didTriggerSwipeDismiss = false
        didTriggerSwipeUp = false
    }
}

private final class MarqueeTextLabel: NSView {
    var stringValue: String {
        get { label.stringValue }
        set {
            guard label.stringValue != newValue else { return }
            label.stringValue = newValue
            duplicateLabel.stringValue = newValue
            resetMarquee()
        }
    }

    var font: NSFont? {
        get { label.font }
        set {
            label.font = newValue
            duplicateLabel.font = newValue
            resetMarquee()
        }
    }

    var textColor: NSColor? {
        get { label.textColor }
        set {
            label.textColor = newValue
            duplicateLabel.textColor = newValue
        }
    }

    private let label = NSTextField(labelWithString: "")
    private let duplicateLabel = NSTextField(labelWithString: "")
    private var marqueeTimer: Timer?
    private var phaseStart = Date()
    private var scrollOffset: CGFloat = 0
    private let initialPauseDuration: TimeInterval = 1
    private let scrollSpeed: CGFloat = 28
    private let trailingGap: CGFloat = 28

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    override var isHidden: Bool {
        didSet {
            guard oldValue != isHidden else { return }
            isHidden ? stopMarquee() : resetMarquee()
        }
    }

    override var isFlipped: Bool { true }

    override func layout() {
        super.layout()
        updateMarqueeAvailability()
        layoutLabel()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        updateMarqueeAvailability()
    }

    private func configure() {
        wantsLayer = true
        layer?.masksToBounds = true
        label.lineBreakMode = .byClipping
        label.maximumNumberOfLines = 1
        label.cell?.usesSingleLineMode = true
        label.cell?.wraps = false
        label.cell?.truncatesLastVisibleLine = false
        label.translatesAutoresizingMaskIntoConstraints = true
        addSubview(label)

        duplicateLabel.lineBreakMode = .byClipping
        duplicateLabel.maximumNumberOfLines = 1
        duplicateLabel.cell?.usesSingleLineMode = true
        duplicateLabel.cell?.wraps = false
        duplicateLabel.cell?.truncatesLastVisibleLine = false
        duplicateLabel.translatesAutoresizingMaskIntoConstraints = true
        duplicateLabel.isHidden = true
        addSubview(duplicateLabel)
    }

    private func resetMarquee() {
        scrollOffset = 0
        phaseStart = Date()
        updateMarqueeAvailability()
        needsLayout = true
    }

    private func updateMarqueeAvailability() {
        guard !isHidden, window != nil, bounds.width > 0, contentWidth > bounds.width else {
            stopMarquee()
            scrollOffset = 0
            return
        }
        startMarqueeIfNeeded()
    }

    private func startMarqueeIfNeeded() {
        guard marqueeTimer == nil else { return }
        phaseStart = Date()
        let timer = Timer.scheduledTimer(withTimeInterval: 1 / 60, repeats: true) { [weak self] _ in
            self?.tickMarquee()
        }
        RunLoop.main.add(timer, forMode: .common)
        marqueeTimer = timer
    }

    private func stopMarquee() {
        marqueeTimer?.invalidate()
        marqueeTimer = nil
    }

    private func tickMarquee() {
        guard contentWidth > bounds.width else {
            stopMarquee()
            scrollOffset = 0
            needsLayout = true
            return
        }

        let cycleWidth = contentWidth + trailingGap
        let scrollDuration = TimeInterval(cycleWidth / scrollSpeed)
        let elapsed = Date().timeIntervalSince(phaseStart)
        if elapsed <= initialPauseDuration {
            scrollOffset = 0
        } else if elapsed <= initialPauseDuration + scrollDuration {
            let progress = CGFloat((elapsed - initialPauseDuration) / scrollDuration)
            scrollOffset = -cycleWidth * progress
        } else {
            phaseStart = Date()
            scrollOffset = 0
        }
        needsLayout = true
    }

    private func layoutLabel() {
        let width = max(bounds.width, contentWidth)
        label.frame = NSRect(
            x: scrollOffset,
            y: 0,
            width: width,
            height: bounds.height
        )
        duplicateLabel.isHidden = marqueeTimer == nil
        duplicateLabel.frame = NSRect(
            x: scrollOffset + contentWidth + trailingGap,
            y: 0,
            width: width,
            height: bounds.height
        )
    }

    private var contentWidth: CGFloat {
        let measured = (label.stringValue as NSString).size(withAttributes: [
            .font: label.font ?? NSFont.systemFont(ofSize: 13)
        ]).width
        return ceil(measured) + 2
    }
}

private final class MediaTimelineControl: NSControl {
    var minimumValue: Double = 0 { didSet { needsDisplay = true } }
    var maximumValue: Double = 1 { didSet { needsDisplay = true } }
    var timelineValue: Double = 0 { didSet { needsDisplay = true } }
    var elapsedText: String = "0:00" { didSet { needsDisplay = true } }
    var remainingText: String = "0:00" { didSet { needsDisplay = true } }
    var onPreviewValueChanged: ((Double) -> Void)?
    var onInteractionChanged: ((Bool) -> Void)?
    private(set) var isInteracting = false
    private var isDragging = false
    private var isHovering = false
    private var trackingAreaReference: NSTrackingArea?
    private let preferredHeight: CGFloat = 48
    private let horizontalInset: CGFloat = 8
    private let barHeight: CGFloat = 6
    private let trackHitOutset: CGFloat = 16

    override var isFlipped: Bool { true }
    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: preferredHeight)
    }
    override var isEnabled: Bool {
        didSet {
            if !isEnabled {
                resetPointerState()
            }
        }
    }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func hitTest(_ point: NSPoint) -> NSView? {
        guard !isHidden, alphaValue > 0.01, isEnabled else { return nil }
        return acceptsAncestorHit(at: point) ? self : nil
    }

    func acceptsAncestorHit(at point: NSPoint) -> Bool {
        guard !isHidden, alphaValue > 0.01, isEnabled else { return false }
        return trackHitRect.contains(point)
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window == nil {
            resetPointerState()
        }
    }

    override func updateTrackingAreas() {
        if let trackingAreaReference {
            removeTrackingArea(trackingAreaReference)
        }
        let trackingArea = NSTrackingArea(
            rect: trackHitRect,
            options: [.mouseEnteredAndExited, .mouseMoved, .activeAlways, .enabledDuringMouseDrag, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
        trackingAreaReference = trackingArea
        super.updateTrackingAreas()
    }

    override func mouseEntered(with event: NSEvent) {
        setHovering(true)
    }

    override func mouseExited(with event: NSEvent) {
        if !isDragging {
            setHovering(false)
        }
    }

    override func mouseMoved(with event: NSEvent) {
        updateHoverState(from: event)
    }

    override func mouseDown(with event: NSEvent) {
        guard isEnabled else { return }
        window?.makeFirstResponder(self)
        setInteracting(true)
        isDragging = true
        setHovering(true)
        updateValue(from: event)
        trackMouseUntilMouseUp()
    }

    override func mouseDragged(with event: NSEvent) {
        guard isEnabled else { return }
        updateValue(from: event)
    }

    override func mouseUp(with event: NSEvent) {
        guard isEnabled, isDragging else { return }
        finishTracking(with: event)
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)

        let barRect = trackRect
        let barWidth = barRect.width
        let progress = CGFloat((timelineValue - minimumValue) / max(maximumValue - minimumValue, 1))
            .clamped(to: 0...1)

        NSColor.white.withAlphaComponent(0.42).setFill()
        NSBezierPath(roundedRect: barRect, xRadius: barHeight / 2, yRadius: barHeight / 2).fill()

        let progressRect = NSRect(x: barRect.minX, y: barRect.minY, width: barWidth * progress, height: barRect.height)
        NSColor.white.withAlphaComponent(0.84).setFill()
        NSBezierPath(roundedRect: progressRect, xRadius: barHeight / 2, yRadius: barHeight / 2).fill()

        if isHovering || isDragging {
            let knobRadius: CGFloat = isDragging ? 5 : 4
            let knobCenter = NSPoint(x: barRect.minX + (barWidth * progress), y: barRect.midY)
            NSColor.white.withAlphaComponent(0.96).setFill()
            NSBezierPath(ovalIn: NSRect(
                x: knobCenter.x - knobRadius,
                y: knobCenter.y - knobRadius,
                width: knobRadius * 2,
                height: knobRadius * 2
            )).fill()
        }

        let labelFont = NSFont.monospacedDigitSystemFont(ofSize: 10, weight: .semibold)
        let labelColor = NSColor.white.withAlphaComponent(0.58)
        let attributes: [NSAttributedString.Key: Any] = [
            .font: labelFont,
            .foregroundColor: labelColor
        ]
        let labelY = min(bounds.height - 13, barRect.maxY + 8)
        (elapsedText as NSString).draw(at: NSPoint(x: horizontalInset, y: labelY), withAttributes: attributes)
        let remainingSize = (remainingText as NSString).size(withAttributes: attributes)
        (remainingText as NSString).draw(
            at: NSPoint(x: max(horizontalInset, bounds.width - horizontalInset - remainingSize.width), y: labelY),
            withAttributes: attributes
        )
    }

    private func updateValue(from event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        let rect = trackRect
        let usableWidth = max(rect.width, 1)
        let progress = Double(((location.x - rect.minX) / usableWidth).clamped(to: 0...1))
        timelineValue = minimumValue + ((maximumValue - minimumValue) * progress)
        onPreviewValueChanged?(timelineValue)
    }

    private func trackMouseUntilMouseUp() {
        while isDragging {
            guard let event = window?.nextEvent(
                matching: [.leftMouseDragged, .leftMouseUp],
                until: .distantFuture,
                inMode: .eventTracking,
                dequeue: true
            ) else {
                break
            }

            switch event.type {
            case .leftMouseDragged:
                updateValue(from: event)
            case .leftMouseUp:
                finishTracking(with: event)
            default:
                break
            }
        }
        if isDragging {
            finishTracking(with: nil)
        }
    }

    private func finishTracking(with event: NSEvent?) {
        if let event {
            updateValue(from: event)
        }
        isDragging = false
        sendAction(action, to: target)
        setInteracting(false)
        updateHoverStateFromCurrentMouseLocation()
        needsDisplay = true
    }

    private func updateHoverState(from event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        setHovering(trackHitRect.contains(location))
    }

    private func updateHoverStateFromCurrentMouseLocation() {
        guard let window else {
            setHovering(false)
            return
        }
        let location = convert(window.mouseLocationOutsideOfEventStream, from: nil)
        setHovering(trackHitRect.contains(location))
    }

    private func setHovering(_ hovering: Bool) {
        guard isHovering != hovering else { return }
        isHovering = hovering
        needsDisplay = true
    }

    private func setInteracting(_ interacting: Bool) {
        guard isInteracting != interacting else { return }
        isInteracting = interacting
        onInteractionChanged?(interacting)
    }

    private func resetPointerState() {
        setInteracting(false)
        isDragging = false
        isHovering = false
        needsDisplay = true
    }

    private var trackRect: NSRect {
        NSRect(
            x: horizontalInset,
            y: floor((bounds.height - barHeight) / 2),
            width: max(0, bounds.width - (horizontalInset * 2)),
            height: barHeight
        )
    }

    private var trackHitRect: NSRect {
        trackRect.insetBy(dx: -horizontalInset, dy: -trackHitOutset)
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}

private final class NotificationActionButton: NSButton {
    var pressAnimationScale: CGFloat = 1

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override var canBecomeKeyView: Bool { false }

    override func mouseDown(with event: NSEvent) {
        animatePress(isPressed: true)
        super.mouseDown(with: event)
        animatePress(isPressed: false)
    }

    private func animatePress(isPressed: Bool) {
        guard pressAnimationScale < 1 else { return }
        wantsLayer = true
        NSAnimationContext.runAnimationGroup { context in
            context.duration = isPressed ? 0.08 : 0.18
            context.timingFunction = CAMediaTimingFunction(name: isPressed ? .easeIn : .easeOut)
            animator().alphaValue = isPressed ? 0.78 : 1
            layer?.transform = isPressed
                ? CATransform3DMakeScale(pressAnimationScale, pressAnimationScale, 1)
                : CATransform3DIdentity
        }
    }
}

private final class InlineReplyTextField: NSTextField {
    override var acceptsFirstResponder: Bool { false }
}

private final class NotificationInteractionPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

private struct MTTrackpadPoint {
    var x: Float
    var y: Float
}

private struct MTTrackpadVector {
    var position: MTTrackpadPoint
    var velocity: MTTrackpadPoint
}

private struct MTTrackpadTouch {
    var frame: Int32
    var timestamp: Double
    var identifier: Int32
    var state: Int32
    var unknown1: Int32
    var unknown2: Int32
    var normalized: MTTrackpadVector
    var size: Float
    var zero1: Int32
    var angle: Float
    var majorAxis: Float
    var minorAxis: Float
    var mm: MTTrackpadVector
    var zero2: (Int32, Int32)
    var unknown3: Float
}

private final class TrackpadCornerGestureMonitor {
    static let shared = TrackpadCornerGestureMonitor()

    private typealias MTDeviceCreateListFunction = @convention(c) () -> Unmanaged<CFArray>?
    private typealias MTRegisterContactFrameCallbackFunction = @convention(c) (
        UnsafeMutableRawPointer,
        MTContactFrameCallback?
    ) -> Void
    private typealias MTDeviceStartFunction = @convention(c) (UnsafeMutableRawPointer, Int32) -> Void
    private typealias MTContactFrameCallback = @convention(c) (
        Int32,
        UnsafeMutableRawPointer?,
        Int32,
        Double,
        Int32
    ) -> Int32

    private let lock = NSLock()
    private var frameworkHandle: UnsafeMutableRawPointer?
    private var deviceList: CFArray?
    private var didStart = false
    private var activeTopRightTouch = false
    private var lastTopRightTouchAt = Date.distantPast
    private var available = false

    private static let topRightCornerMinimumX: Float = 0.82
    private static let topRightCornerMinimumY: Float = 0.72
    private static let rightEdgeMinimumX: Float = 0.90
    private static let topEdgeMinimumY: Float = 0.88
    private static let recentTouchWindow: TimeInterval = 0.85
    private static let contactCallback: MTContactFrameCallback = { _, touches, touchCount, _, _ in
        TrackpadCornerGestureMonitor.shared.handleContactFrame(rawTouches: touches, touchCount: touchCount)
        return 0
    }

    var isAvailable: Bool {
        lock.withLock { available }
    }

    var hasRecentTopRightTouch: Bool {
        lock.withLock {
            activeTopRightTouch || Date().timeIntervalSince(lastTopRightTouchAt) <= Self.recentTouchWindow
        }
    }

    func start() {
        lock.lock()
        guard !didStart else {
            lock.unlock()
            return
        }
        didStart = true
        lock.unlock()

        guard
            let handle = dlopen(
                "/System/Library/PrivateFrameworks/MultitouchSupport.framework/MultitouchSupport",
                RTLD_LAZY
            ),
            let createListSymbol = dlsym(handle, "MTDeviceCreateList"),
            let registerCallbackSymbol = dlsym(handle, "MTRegisterContactFrameCallback"),
            let startSymbol = dlsym(handle, "MTDeviceStart")
        else {
            return
        }

        let createList = unsafeBitCast(createListSymbol, to: MTDeviceCreateListFunction.self)
        let registerCallback = unsafeBitCast(registerCallbackSymbol, to: MTRegisterContactFrameCallbackFunction.self)
        let startDevice = unsafeBitCast(startSymbol, to: MTDeviceStartFunction.self)

        guard let unmanagedDeviceList = createList() else {
            return
        }

        let devices = unmanagedDeviceList.takeRetainedValue()
        let count = CFArrayGetCount(devices)
        guard count > 0 else {
            return
        }

        for index in 0..<count {
            guard let rawDevice = CFArrayGetValueAtIndex(devices, index) else { continue }
            let device = UnsafeMutableRawPointer(mutating: rawDevice)
            registerCallback(device, Self.contactCallback)
            startDevice(device, 0)
        }

        lock.withLock {
            frameworkHandle = handle
            deviceList = devices
            available = true
        }
    }

    private func handleContactFrame(rawTouches: UnsafeMutableRawPointer?, touchCount: Int32) {
        guard let rawTouches, touchCount > 0 else {
            lock.withLock {
                activeTopRightTouch = false
            }
            return
        }

        let touches = rawTouches.bindMemory(to: MTTrackpadTouch.self, capacity: Int(touchCount))
        let hasTopRightTouch = (0..<Int(touchCount)).contains { index in
            let position = touches[index].normalized.position
            let isInTopRightCorner = position.x >= Self.topRightCornerMinimumX
                && position.y >= Self.topRightCornerMinimumY
            let isOnTopRightEdge = position.x >= Self.rightEdgeMinimumX
                || position.y >= Self.topEdgeMinimumY
            return isInTopRightCorner && isOnTopRightEdge
        }

        lock.withLock {
            activeTopRightTouch = hasTopRightTouch
            if hasTopRightTouch {
                lastTopRightTouchAt = Date()
            }
        }
    }
}

private final class FileTransferPanelController: NSObject {
    private enum State {
        case active
        case sent
        case received
    }

    private let transferId: String
    private let panel: NotificationInteractionPanel
    private let iconView = NSImageView()
    private let titleLabel = NSTextField(labelWithString: "")
    private let statusLabel = NSTextField(labelWithString: "")
    private let detailLabel = NSTextField(labelWithString: "")
    private let progressIndicator = NSProgressIndicator()
    private let cancelButton = NotificationActionButton(title: "Cancel", target: nil, action: nil)
    private let copyButton = NotificationActionButton(title: "Copy", target: nil, action: nil)
    private let showInFinderButton = NotificationActionButton(title: "Show in Finder", target: nil, action: nil)
    private let closeButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let onCancel: (String) -> Void
    private let onCopy: ([URL]) -> Void
    private let onShowInFinder: ([URL]) -> Void
    private let onClose: (String) -> Void
    private var fileURLs: [URL] = []
    private var sentAutoCloseTimer: Timer?
    private var isClosing = false
    private var state: State = .active

    private static let panelWidth: CGFloat = 352
    private static let panelHeight: CGFloat = 128

    init(
        transferId: String,
        onCancel: @escaping (String) -> Void,
        onCopy: @escaping ([URL]) -> Void,
        onShowInFinder: @escaping ([URL]) -> Void,
        onClose: @escaping (String) -> Void
    ) {
        self.transferId = transferId
        self.onCancel = onCancel
        self.onCopy = onCopy
        self.onShowInFinder = onShowInFinder
        self.onClose = onClose

        panel = NotificationInteractionPanel(
            contentRect: NSRect(x: 0, y: 0, width: Self.panelWidth, height: Self.panelHeight),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.ignoresMouseEvents = false
        panel.isMovableByWindowBackground = false
        panel.appearance = NSApp.effectiveAppearance

        super.init()
        configureContent()
    }

    var currentHeight: CGFloat {
        panel.frame.height
    }

    func show() {
        panel.orderFrontRegardless()
    }

    func move(toTopY topY: CGFloat) {
        positionNearTopRight(topY: topY)
    }

    func updateActive(
        fileName: String,
        status: String,
        transferredBytes: Int64,
        totalBytes: Int64
    ) {
        state = .active
        sentAutoCloseTimer?.invalidate()
        sentAutoCloseTimer = nil
        titleLabel.stringValue = Self.singleLine(fileName)
        statusLabel.stringValue = status
        detailLabel.stringValue = Self.progressText(transferredBytes: transferredBytes, totalBytes: totalBytes)
        progressIndicator.isHidden = false
        progressIndicator.doubleValue = Self.progressValue(transferredBytes: transferredBytes, totalBytes: totalBytes)
        cancelButton.isHidden = false
        copyButton.isHidden = true
        showInFinderButton.isHidden = true
        closeButton.isHidden = true
    }

    func updateSent(fileName: String) {
        state = .sent
        titleLabel.stringValue = Self.singleLine(fileName)
        statusLabel.stringValue = "Sent successfully"
        detailLabel.stringValue = "File transfer completed."
        progressIndicator.isHidden = false
        progressIndicator.doubleValue = 100
        cancelButton.isHidden = true
        copyButton.isHidden = true
        showInFinderButton.isHidden = true
        closeButton.isHidden = false
        scheduleSentAutoClose()
    }

    func updateReceived(fileName: String, fileURLs: [URL]) {
        state = .received
        sentAutoCloseTimer?.invalidate()
        sentAutoCloseTimer = nil
        self.fileURLs = fileURLs
        titleLabel.stringValue = Self.singleLine(fileName)
        statusLabel.stringValue = "Received successfully"
        detailLabel.stringValue = "Saved to Downloads."
        progressIndicator.isHidden = false
        progressIndicator.doubleValue = 100
        cancelButton.isHidden = true
        copyButton.isHidden = false
        showInFinderButton.isHidden = false
        closeButton.isHidden = false
    }

    func close(notify: Bool = true) {
        guard !isClosing else { return }
        isClosing = true
        sentAutoCloseTimer?.invalidate()
        sentAutoCloseTimer = nil
        panel.close()
        if notify {
            onClose(transferId)
        }
    }

    private func configureContent() {
        guard let contentView = panel.contentView else { return }

        contentView.wantsLayer = true
        contentView.layer?.backgroundColor = NSColor.windowBackgroundColor.withAlphaComponent(0.94).cgColor
        contentView.layer?.cornerRadius = 20
        contentView.layer?.masksToBounds = true

        iconView.image = NSApp.applicationIconImage
        iconView.imageScaling = .scaleProportionallyUpOrDown
        iconView.wantsLayer = true
        iconView.layer?.cornerRadius = 9
        iconView.layer?.masksToBounds = true
        iconView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        titleLabel.textColor = .labelColor
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.maximumNumberOfLines = 1
        titleLabel.cell?.usesSingleLineMode = true

        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.lineBreakMode = .byTruncatingTail
        statusLabel.maximumNumberOfLines = 1
        statusLabel.cell?.usesSingleLineMode = true

        detailLabel.font = .monospacedDigitSystemFont(ofSize: 11, weight: .medium)
        detailLabel.textColor = .tertiaryLabelColor
        detailLabel.lineBreakMode = .byTruncatingTail
        detailLabel.maximumNumberOfLines = 1
        detailLabel.cell?.usesSingleLineMode = true

        progressIndicator.isIndeterminate = false
        progressIndicator.minValue = 0
        progressIndicator.maxValue = 100
        progressIndicator.controlSize = .small
        progressIndicator.style = .bar
        progressIndicator.translatesAutoresizingMaskIntoConstraints = false

        let textStack = NSStackView(views: [titleLabel, statusLabel, detailLabel, progressIndicator])
        textStack.orientation = .vertical
        textStack.alignment = .leading
        textStack.spacing = 3
        textStack.translatesAutoresizingMaskIntoConstraints = false

        let headerStack = NSStackView(views: [iconView, textStack])
        headerStack.orientation = .horizontal
        headerStack.alignment = .top
        headerStack.spacing = 12
        headerStack.translatesAutoresizingMaskIntoConstraints = false

        let buttonStack = NSStackView()
        buttonStack.orientation = .horizontal
        buttonStack.alignment = .centerY
        buttonStack.spacing = 8
        buttonStack.translatesAutoresizingMaskIntoConstraints = false

        configureButton(cancelButton, action: #selector(cancel))
        configureButton(copyButton, action: #selector(copyFiles))
        configureButton(showInFinderButton, action: #selector(showInFinder))
        cancelButton.hasDestructiveAction = true
        copyButton.isHidden = true
        showInFinderButton.isHidden = true
        configureCloseButton()
        closeButton.isHidden = true

        buttonStack.addArrangedSubview(cancelButton)
        buttonStack.addArrangedSubview(copyButton)
        buttonStack.addArrangedSubview(showInFinderButton)

        let rootStack = NSStackView(views: [headerStack, buttonStack])
        rootStack.orientation = .vertical
        rootStack.alignment = .leading
        rootStack.spacing = 10
        rootStack.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(rootStack)
        contentView.addSubview(closeButton)
        NSLayoutConstraint.activate([
            rootStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            rootStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            rootStack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            rootStack.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -14),
            iconView.widthAnchor.constraint(equalToConstant: 38),
            iconView.heightAnchor.constraint(equalToConstant: 38),
            textStack.widthAnchor.constraint(equalToConstant: 238),
            progressIndicator.widthAnchor.constraint(equalTo: textStack.widthAnchor),
            closeButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -10),
            closeButton.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            closeButton.widthAnchor.constraint(equalToConstant: 20),
            closeButton.heightAnchor.constraint(equalToConstant: 20)
        ])
    }

    private func configureButton(_ button: NSButton, action: Selector) {
        button.target = self
        button.action = action
        button.bezelStyle = .rounded
        button.controlSize = .small
        button.font = .systemFont(ofSize: 12, weight: .medium)
    }

    private func configureCloseButton() {
        closeButton.target = self
        closeButton.action = #selector(closeCompletedTransfer)
        closeButton.image = NSImage(
            systemSymbolName: "xmark",
            accessibilityDescription: "Close"
        )?.withSymbolConfiguration(
            NSImage.SymbolConfiguration(pointSize: 9, weight: .bold)
        )
        closeButton.imagePosition = .imageOnly
        closeButton.contentTintColor = .white
        closeButton.isBordered = false
        closeButton.focusRingType = .none
        closeButton.toolTip = "Close"
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.wantsLayer = true
        closeButton.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.34).cgColor
        closeButton.layer?.cornerRadius = 10
    }

    private func scheduleSentAutoClose() {
        sentAutoCloseTimer?.invalidate()
        sentAutoCloseTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: false) { [weak self] _ in
            self?.close()
        }
    }

    private func positionNearTopRight(topY: CGFloat) {
        guard let screen = NSScreen.main else {
            panel.center()
            return
        }

        let visibleFrame = screen.visibleFrame
        let frame = panel.frame
        panel.setFrameOrigin(
            NSPoint(
                x: visibleFrame.maxX - frame.width - 18,
                y: min(visibleFrame.maxY - 18, topY) - frame.height
            )
        )
    }

    @objc private func cancel() {
        guard state == .active else { return }
        statusLabel.stringValue = "Cancelling..."
        onCancel(transferId)
        close()
    }

    @objc private func copyFiles() {
        onCopy(fileURLs)
        close()
    }

    @objc private func showInFinder() {
        onShowInFinder(fileURLs)
        close()
    }

    @objc private func closeCompletedTransfer() {
        guard state != .active else { return }
        close()
    }

    private static func progressValue(transferredBytes: Int64, totalBytes: Int64) -> Double {
        let safeTotal = max(0, totalBytes)
        guard safeTotal > 0 else { return 100 }
        let clampedTransferred = min(max(0, transferredBytes), safeTotal)
        return (Double(clampedTransferred) / Double(safeTotal)) * 100
    }

    private static func progressText(transferredBytes: Int64, totalBytes: Int64) -> String {
        let safeTotal = max(0, totalBytes)
        let clampedTransferred = min(max(0, transferredBytes), safeTotal)
        let percent = Int(progressValue(transferredBytes: clampedTransferred, totalBytes: safeTotal).rounded())
        return "\(percent)%  \(ByteCountFormatter.string(fromByteCount: clampedTransferred, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: safeTotal, countStyle: .file))"
    }

    private static func singleLine(_ text: String) -> String {
        text
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}

private final class CallControlPanelController: NSObject {
    let callId: String

    private let panel: NSPanel
    private let titleLabel = NSTextField(labelWithString: "")
    private let bodyLabel = NSTextField(labelWithString: "")
    private let statusLabel = NSTextField(labelWithString: "")
    private let acceptButton = NSButton(title: "Accept", target: nil, action: nil)
    private let rejectButton = NSButton(title: "Reject", target: nil, action: nil)
    private let silenceButton = NSButton(title: "Silence", target: nil, action: nil)
    private let buttonSpacer = NSView()
    private let onAction: (String, String, @escaping (Bool, String) -> Void) -> Void
    private var activeSince: Date?
    private var timer: Timer?

    init(
        call: AndroidMirroredCall,
        onAction: @escaping (String, String, @escaping (Bool, String) -> Void) -> Void
    ) {
        callId = call.callId
        self.onAction = onAction

        panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 336, height: 138),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.isMovableByWindowBackground = true
        panel.appearance = NSAppearance(named: .darkAqua)

        super.init()
        configureContent()
        update(call)
    }

    func show() {
        positionNearTopRight()
        panel.orderFrontRegardless()
    }

    func close() {
        stopTimer()
        panel.close()
    }

    func update(_ call: AndroidMirroredCall) {
        titleLabel.stringValue = call.displayTitle
        bodyLabel.stringValue = call.displayBody

        let isAnswered = call.event == "answered"
        acceptButton.isHidden = isAnswered
        silenceButton.isHidden = isAnswered
        rejectButton.title = isAnswered ? "End Call" : "Reject"

        if isAnswered {
            if activeSince == nil {
                activeSince = call.timestampMillis.map {
                    Date(timeIntervalSince1970: TimeInterval($0) / 1_000)
                } ?? Date()
            }
            startTimer()
            updateTimerText()
        } else {
            activeSince = nil
            stopTimer()
            statusLabel.stringValue = "Incoming Android call"
        }
    }

    private func configureContent() {
        guard let contentView = panel.contentView else { return }

        contentView.wantsLayer = true
        contentView.layer?.backgroundColor = NSColor(calibratedWhite: 0.07, alpha: 0.96).cgColor
        contentView.layer?.cornerRadius = 22
        contentView.layer?.masksToBounds = true

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        titleLabel.textColor = .white
        titleLabel.lineBreakMode = .byTruncatingTail
        bodyLabel.font = .systemFont(ofSize: 13)
        bodyLabel.textColor = NSColor.white.withAlphaComponent(0.74)
        bodyLabel.lineBreakMode = .byTruncatingTail
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = NSColor.white.withAlphaComponent(0.64)
        buttonSpacer.translatesAutoresizingMaskIntoConstraints = false

        let buttonStack = NSStackView()
        buttonStack.orientation = .horizontal
        buttonStack.spacing = 8
        buttonStack.alignment = .centerY

        configureButton(acceptButton, action: #selector(accept))
        configureButton(rejectButton, action: #selector(reject))
        configureButton(silenceButton, action: #selector(silence))
        rejectButton.hasDestructiveAction = true

        buttonStack.addArrangedSubview(acceptButton)
        buttonStack.addArrangedSubview(rejectButton)
        buttonStack.addArrangedSubview(silenceButton)

        [titleLabel, bodyLabel, statusLabel, buttonSpacer, buttonStack].forEach {
            stack.addArrangedSubview($0)
        }

        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -18),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
            titleLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            bodyLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            buttonSpacer.heightAnchor.constraint(equalToConstant: 6)
        ])
    }

    private func configureButton(_ button: NSButton, action: Selector) {
        button.target = self
        button.action = action
        button.bezelStyle = .rounded
        button.controlSize = .small
        button.font = .systemFont(ofSize: 13, weight: .medium)
    }

    private func startTimer() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.updateTimerText()
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func updateTimerText() {
        guard let activeSince else {
            statusLabel.stringValue = "Call active on Android"
            return
        }

        let elapsed = max(0, Int(Date().timeIntervalSince(activeSince)))
        let minutes = elapsed / 60
        let seconds = elapsed % 60
        statusLabel.stringValue = String(format: "Call active %@%02d:%02d", "for ", minutes, seconds)
    }

    private func positionNearTopRight() {
        guard let screen = NSScreen.main else {
            panel.center()
            return
        }

        let visibleFrame = screen.visibleFrame
        let frame = panel.frame
        panel.setFrameOrigin(
            NSPoint(
                x: visibleFrame.maxX - frame.width - 18,
                y: visibleFrame.maxY - frame.height - 18
            )
        )
    }

    @objc private func accept() {
        statusLabel.stringValue = "Accepting on Android..."
        onAction("accept", callId) { [weak self] succeeded, message in
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = succeeded ? "Accepted on Android" : message
            }
        }
    }

    @objc private func reject() {
        statusLabel.stringValue = "Ending on Android..."
        onAction("reject", callId) { [weak self] succeeded, message in
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = succeeded ? "Ended on Android" : message
            }
        }
    }

    @objc private func silence() {
        statusLabel.stringValue = "Silencing Android..."
        onAction("silence", callId) { [weak self] succeeded, message in
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = succeeded ? "Silenced on Android" : message
            }
        }
    }
}
