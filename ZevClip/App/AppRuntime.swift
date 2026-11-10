import AppKit
import Combine
import Network
import QuartzCore
import SwiftUI

@MainActor
final class ZevClipRuntime {
    static let shared = ZevClipRuntime()

    let receiver = ClipboardReceiver()
    private let screenMirrorWindowController = ScreenMirrorWindowController()
    private lazy var screenMirrorReceiver: ScreenMirrorReceiver = {
        let receiver = ScreenMirrorReceiver(windowController: screenMirrorWindowController)
        screenMirrorWindowController.onUserClose = { [weak receiver] in
            receiver?.stopActiveStreamFromWindowClose()
        }
        return receiver
    }()
    let macClipboardWatcher = MacClipboardWatcher()
    let fileTransferService = FileTransferService()
    let intentPresetStore = IntentPresetStore()
    lazy var androidClipboardSender = AndroidClipboardSender(
        tokenProvider: { [weak self] in
            self?.receiver.pairingToken ?? ""
        }
    )
    let appSettings = AppSettings()

    private lazy var settingsWindowController = SettingsWindowController(
        receiver: receiver,
        macClipboardWatcher: macClipboardWatcher,
        androidClipboardSender: androidClipboardSender,
        appSettings: appSettings
    )
    private lazy var intentCreatorWindowController = IntentCreatorWindowController(
        sender: androidClipboardSender,
        presetStore: intentPresetStore
    )
    private lazy var statusItemController = StatusItemController(
        receiver: receiver,
        macClipboardWatcher: macClipboardWatcher,
        androidClipboardSender: androidClipboardSender,
        appSettings: appSettings,
        presetStore: intentPresetStore,
        openSettings: { [weak self] in
            self?.showSettingsWindow()
        },
        openIntentCreator: { [weak self] in
            self?.showIntentCreatorWindow()
        }
    )

    private init() {
        MacNotificationPresenter.shared.requestAuthorizationIfNeeded()
        MacBluetoothService.shared.tokenProvider = { [weak self] in
            self?.receiver.pairingToken ?? ""
        }
        MacBluetoothService.shared.onClipboardReceived = { [weak self] text in
            self?.receiver.receive(text)
        }
        MacBluetoothService.shared.onNotificationReceived = { [weak self] data in
            self?.receiver.receiveAndroidNotification(data)
        }
        MacBluetoothService.shared.onCallReceived = { [weak self] data in
            self?.receiver.receiveAndroidCall(data)
        }
        MacBluetoothService.shared.onRemoteReceived = { data in
            guard AppSettings.savedRemoteControlEnabled() else { return }
            _ = MacRemoteController.handle(data)
        }
        MacBluetoothService.shared.onIntentBroadcastAcknowledgement = { [weak self] data in
            Task { @MainActor in
                self?.androidClipboardSender.handleIntentBroadcastAcknowledgement(data)
            }
        }
        MacBluetoothService.shared.onPresenceReceived = { [weak self] json in
            Task { @MainActor in
                if let ringing = json["isRinging"] as? Bool {
                    self?.androidClipboardSender.setFindingPhone(ringing)
                }
            }
        }
        MacBluetoothService.shared.onPhoneIpsDiscovered = { [weak self] phoneIps, phonePort in
            self?.androidClipboardSender.tryConnectToPhoneIps(phoneIps, port: phonePort)
        }
        MacBluetoothService.shared.onTransportModeReceived = { [weak self] mode in
            self?.androidClipboardSender.handlePhoneTransportMode(mode)
        }
        MacBluetoothService.shared.onDisconnectReceived = { [weak self] in
            Task { @MainActor in
                self?.androidClipboardSender.clearResolvedEndpoint()
            }
        }

        MacBluetoothService.shared.startAdvertisingIfNeeded()

        receiver.onPasteboardWrite = { [weak macClipboardWatcher] text, changeCount in
            macClipboardWatcher?.markProgrammaticPasteboardWrite(
                text: text,
                changeCount: changeCount
            )
        }
        receiver.onAndroidEndpointSeen = { [weak self] endpoint in
            self?.androidClipboardSender.updateEndpointSeenFromAndroid(endpoint)
        }
        receiver.onAndroidNotification = { notification in
            MacNotificationPresenter.shared.show(notification)
        }
        receiver.onAndroidCall = { call in
            MacNotificationPresenter.shared.show(call)
        }
        receiver.onFileTransferRequest = { [weak self] path, headers, body in
            self?.fileTransferService.handleIncomingRequest(path: path, headers: headers, body: body) ??
                FileTransferHTTPResponse(status: "503 Service Unavailable", text: "File transfer service is unavailable.")
        }
        receiver.isRemoteControlEnabled = {
            AppSettings.savedRemoteControlEnabled()
        }
        fileTransferService.updateSettings(
            autoAcceptIncoming: appSettings.fileTransferAutoAccept
        )
        fileTransferService.onCancelPeerTransfer = { [weak self] transferId in
            guard
                let self,
                let endpoint = self.androidClipboardSender.resolvedEndpoint
            else {
                return
            }
            let token = self.receiver.pairingToken
            guard !token.isEmpty else { return }
            Task {
                try? await AndroidFileTransferHTTPClient.cancel(
                    transferId: transferId,
                    endpoint: endpoint,
                    token: token
                )
            }
        }
        MacNotificationPresenter.shared.onDismiss = { [weak self] notificationKey in
            Task { @MainActor in
                self?.androidClipboardSender.dismissAndroidNotification(notificationKey: notificationKey)
            }
        }
        MacNotificationPresenter.shared.onNotificationAction = { [weak self] notificationKey, actionId, replyText, completion in
            Task { @MainActor in
                self?.androidClipboardSender.performAndroidNotificationAction(
                    notificationKey: notificationKey,
                    actionId: actionId,
                    replyText: replyText,
                    completion: completion
                )
            }
        }
        MacNotificationPresenter.shared.onCallAction = { [weak self] action, callId, completion in
            Task { @MainActor in
                self?.androidClipboardSender.sendAndroidCallAction(
                    action: action,
                    callId: callId,
                    completion: completion
                )
            }
        }
        MacNotificationPresenter.shared.onFileTransferCancel = { [weak self] transferId in
            Task { @MainActor in
                self?.fileTransferService.cancelTransfer(transferId: transferId)
            }
        }
        MacNotificationPresenter.shared.onFileTransferAccept = { [weak self] transferId in
            Task { @MainActor in
                self?.fileTransferService.acceptIncomingTransfer(transferId: transferId)
            }
        }
        MacNotificationPresenter.shared.onFileTransferDecline = { [weak self] transferId in
            Task { @MainActor in
                self?.fileTransferService.declineIncomingTransfer(transferId: transferId)
            }
        }
        macClipboardWatcher.onContentChanged = { [weak self] change in
            self?.androidClipboardSender.send(change)
        }
    }

    func start() {
        startClipboardSync()
        statusItemController.start()

        HumanTypingService.shared.start()

        if !appSettings.showMenuBarIcon {
            showSettingsWindow()
        }
    }

    private func startClipboardSync() {
        receiver.startServer()
        screenMirrorReceiver.start()
        macClipboardWatcher.start()
        androidClipboardSender.startStatusMonitoring()
        androidClipboardSender.rediscoverAndroidReceiver()
    }

    func showSettingsWindow() {
        settingsWindowController.show()
    }

    func showIntentCreatorWindow() {
        intentCreatorWindowController.show()
    }
}

@MainActor
private final class SettingsWindowController {
    private let receiver: ClipboardReceiver
    private let macClipboardWatcher: MacClipboardWatcher
    private let androidClipboardSender: AndroidClipboardSender
    private let appSettings: AppSettings
    private var window: NSWindow?

    init(
        receiver: ClipboardReceiver,
        macClipboardWatcher: MacClipboardWatcher,
        androidClipboardSender: AndroidClipboardSender,
        appSettings: AppSettings
    ) {
        self.receiver = receiver
        self.macClipboardWatcher = macClipboardWatcher
        self.androidClipboardSender = androidClipboardSender
        self.appSettings = appSettings
    }

    func show() {
        let settingsWindow = window ?? makeWindow()
        window = settingsWindow
        settingsWindow.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private func makeWindow() -> NSWindow {
        let hostingController = NSHostingController(
            rootView: ContentView(
                receiver: receiver,
                macClipboardWatcher: macClipboardWatcher,
                androidClipboardSender: androidClipboardSender,
                appSettings: appSettings
            )
        )
        let window = NSWindow(contentViewController: hostingController)
        window.title = "ZevLink Settings"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 500, height: 560))
        window.setFrameAutosaveName("settings")
        window.isReleasedWhenClosed = false
        window.center()
        return window
    }
}

@MainActor
private final class StatusItemController: NSObject, NSMenuDelegate {
    private enum ConnectionMode: String {
        case wifi = "Wi-Fi"
        case bluetooth = "Bluetooth"
        case hotspot = "Hotspot"
        case disconnected = "Disconnected"
    }

    private let receiver: ClipboardReceiver
    private let macClipboardWatcher: MacClipboardWatcher
    private let androidClipboardSender: AndroidClipboardSender
    private let appSettings: AppSettings
    private let presetStore: IntentPresetStore
    private let openSettings: () -> Void
    private let openIntentCreator: () -> Void

    private var statusItem: NSStatusItem?
    private var cancellables: Set<AnyCancellable> = []
    private var pathMonitor: NWPathMonitor?
    private var isMenuOpen = false
    private var isStatusRefreshScheduled = false
    private var lastRenderedStatus: RenderedStatus?
    private lazy var phoneStatusImage: NSImage? = Self.makePhoneStatusImage()

    private enum RenderedStatus: Equatable {
        case battery(mode: ConnectionMode, percentage: Int)
        case phone(mode: ConnectionMode)
    }

    private var isClipboardSyncRunning: Bool {
        receiver.status == .running && macClipboardWatcher.isRunning
    }

    init(
        receiver: ClipboardReceiver,
        macClipboardWatcher: MacClipboardWatcher,
        androidClipboardSender: AndroidClipboardSender,
        appSettings: AppSettings,
        presetStore: IntentPresetStore,
        openSettings: @escaping () -> Void,
        openIntentCreator: @escaping () -> Void
    ) {
        self.receiver = receiver
        self.macClipboardWatcher = macClipboardWatcher
        self.androidClipboardSender = androidClipboardSender
        self.appSettings = appSettings
        self.presetStore = presetStore
        self.openSettings = openSettings
        self.openIntentCreator = openIntentCreator
        super.init()
    }

    func start() {
        appSettings.$showMenuBarIcon
            .removeDuplicates()
            .sink { [weak self] isVisible in
                self?.setStatusItemVisible(isVisible)
            }
            .store(in: &cancellables)

        receiver.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.scheduleStatusRefresh()
                }
            }
            .store(in: &cancellables)

        macClipboardWatcher.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.scheduleStatusRefresh()
                }
            }
            .store(in: &cancellables)

        androidClipboardSender.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.scheduleStatusRefresh()
                }
            }
            .store(in: &cancellables)

        appSettings.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.scheduleStatusRefresh()
                }
            }
            .store(in: &cancellables)

        presetStore.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.scheduleStatusRefresh()
                }
            }
            .store(in: &cancellables)

        MacBluetoothService.shared.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.scheduleStatusRefresh()
                }
            }
            .store(in: &cancellables)

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] _ in
            DispatchQueue.main.async {
                self?.scheduleStatusRefresh()
            }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        pathMonitor = monitor

        setStatusItemVisible(appSettings.showMenuBarIcon)
    }

    private func setStatusItemVisible(_ isVisible: Bool) {
        if isVisible {
            createStatusItemIfNeeded()
        } else {
            removeStatusItem()
        }
    }

    private func createStatusItemIfNeeded() {
        guard statusItem == nil else {
            updateMenu()
            return
        }

        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        lastRenderedStatus = nil
        configureStatusButton(item.button)
        item.menu = makeMenu()
        statusItem = item
    }

    private func removeStatusItem() {
        guard let statusItem else { return }

        NSStatusBar.system.removeStatusItem(statusItem)
        self.statusItem = nil
        lastRenderedStatus = nil
    }

    private func refreshStatusItem() {
        guard let statusItem else { return }

        configureStatusButton(statusItem.button)
        if isMenuOpen, let menu = statusItem.menu {
            refreshConnectionMenuItems(in: menu)
        }
    }

    private func scheduleStatusRefresh() {
        guard !isStatusRefreshScheduled else { return }

        isStatusRefreshScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.isStatusRefreshScheduled = false
            self.refreshStatusItem()
        }
    }

    private func updateMenu() {
        guard let statusItem else { return }

        configureStatusButton(statusItem.button)
        if let menu = statusItem.menu {
            populateMenu(menu)
        }
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        refreshMenuBeforeDisplay(menu)
    }

    func menuWillOpen(_ menu: NSMenu) {
        isMenuOpen = true
        refreshMenuBeforeDisplay(menu)
    }

    func menuDidClose(_ menu: NSMenu) {
        isMenuOpen = false
    }

    private func refreshMenuBeforeDisplay(_ menu: NSMenu) {
        configureStatusButton(statusItem?.button)
        populateMenu(menu)
    }

    private func makeMenu() -> NSMenu {
        let menu = NSMenu()
        menu.delegate = self
        populateMenu(menu)
        return menu
    }

    private func populateMenu(_ menu: NSMenu) {
        menu.removeAllItems()
        addDisabledItem("Mode: \(currentConnectionMode.rawValue)", to: menu)
        menu.addItem(.separator())

        menu.addItem(actionItem(
            title: androidClipboardSender.isFindingPhone ? "Stop Ringing" : "Find My Phone",
            action: #selector(findMyPhone),
            isEnabled: currentConnectionMode != .disconnected
        ))
        menu.addItem(actionItem(
            title: "Reconnect Android",
            action: #selector(reconnectAndroid),
            isEnabled: !androidClipboardSender.isDiscovering
        ))

        menu.addItem(.separator())
        menu.addItem(makeIntentsMenu())

        menu.addItem(.separator())
        menu.addItem(actionItem(title: "Open Settings...", action: #selector(openSettingsWindow)))

        menu.addItem(.separator())
        menu.addItem(actionItem(title: "Quit ZevLink", action: #selector(quit)))
    }

    private func refreshConnectionMenuItems(in menu: NSMenu) {
        let mode = currentConnectionMode
        menu.items.first?.title = "Mode: \(mode.rawValue)"

        if let findPhoneItem = menu.items.first(where: { $0.action == #selector(findMyPhone) }) {
            findPhoneItem.title = androidClipboardSender.isFindingPhone ? "Stop Ringing" : "Find My Phone"
            findPhoneItem.isEnabled = mode != .disconnected
        }
    }

    private var currentConnectionMode: ConnectionMode {
        if androidClipboardSender.phoneReportedDisconnected {
            return .disconnected
        }

        if androidClipboardSender.phoneReportedBluetooth {
            return .bluetooth
        }

        if androidClipboardSender.activeTransportMode == .wifi,
           let endpoint = androidClipboardSender.resolvedEndpoint {
            return LocalNetworkHost.isHostDefaultRouter(endpoint.host) ? .hotspot : .wifi
        }

        if MacBluetoothService.shared.isConnected {
            return .bluetooth
        }

        return .disconnected
    }

    private func makeIntentsMenu() -> NSMenuItem {
        let intentsItem = NSMenuItem(title: "Intents", action: nil, keyEquivalent: "")
        let intentsMenu = NSMenu(title: "Intents")
        intentsItem.submenu = intentsMenu
        intentsMenu.addItem(actionItem(title: "Open Intent Creator…", action: #selector(openIntentCreatorWindow)))
        intentsMenu.addItem(.separator())

        if presetStore.presets.isEmpty {
            addDisabledItem("No saved presets", to: intentsMenu)
        } else {
            for preset in presetStore.presets {
                let item = actionItem(
                    title: "Send \(preset.name)",
                    action: #selector(sendIntentPreset(_:)),
                    isEnabled: androidClipboardSender.resolvedEndpoint != nil || MacBluetoothService.shared.isConnected
                )
                item.representedObject = preset.id
                intentsMenu.addItem(item)
            }
        }

        return intentsItem
    }

    private func configureStatusButton(_ button: NSStatusBarButton?) {
        guard let button else { return }

        let mode = currentConnectionMode
        let batteryPercentage: Int?
        switch mode {
        case .wifi, .hotspot:
            batteryPercentage = androidClipboardSender.resolvedEndpoint?.batteryPercentage
        case .bluetooth:
            batteryPercentage = MacBluetoothService.shared.phoneBatteryPercentage
        case .disconnected:
            batteryPercentage = nil
        }

        let renderedStatus: RenderedStatus
        if let batteryPercentage {
            renderedStatus = .battery(mode: mode, percentage: batteryPercentage)
        } else {
            renderedStatus = .phone(mode: mode)
        }

        if renderedStatus != lastRenderedStatus {
            if case .battery(_, let oldBattery)? = lastRenderedStatus,
               case .battery(_, let newBattery) = renderedStatus,
               oldBattery != newBattery {
                animateBatteryChange(on: button, rising: newBattery > oldBattery)
            }

            switch renderedStatus {
            case .battery(_, let batteryPercentage):
                setStatusTitle("\(batteryPercentage)%", on: button)
            case .phone:
                setStatusImage(phoneStatusImage, on: button)
            }
            lastRenderedStatus = renderedStatus
        }

        let toolTip: String
        if mode != .disconnected, let batteryPercentage {
            toolTip = "ZevLink (Mode: \(mode.rawValue)) • Phone Battery: \(batteryPercentage)%"
        } else if mode == .disconnected {
            toolTip = "ZevLink (Mode: \(mode.rawValue))"
        } else {
            toolTip = "ZevLink (Mode: \(mode.rawValue)) • Phone battery unavailable"
        }
        setStatusToolTip(toolTip, on: button)
    }

    private func setStatusTitle(_ title: String, on button: NSStatusBarButton) {
        guard button.attributedTitle.string != title || button.image != nil else { return }

        button.attributedTitle = NSAttributedString(
            string: title,
            attributes: [
                .font: NSFont.systemFont(ofSize: 11, weight: .semibold),
                .foregroundColor: NSColor.labelColor
            ]
        )
        button.image = nil
    }

    private func setStatusImage(_ image: NSImage?, on button: NSStatusBarButton) {
        if button.attributedTitle.string.isEmpty && button.image === image { return }

        button.image = image
        if !button.attributedTitle.string.isEmpty {
            button.attributedTitle = NSAttributedString()
            button.title = ""
        }
    }

    private func setStatusToolTip(_ toolTip: String, on button: NSStatusBarButton) {
        guard button.toolTip != toolTip else { return }

        button.toolTip = toolTip
    }

    private func animateBatteryChange(on button: NSStatusBarButton, rising: Bool) {
        button.wantsLayer = true
        guard let layer = button.layer else { return }

        let transition = CATransition()
        transition.type = .push
        // The incoming number travels upward as the charge increases and
        // downward as it decreases.
        transition.subtype = rising ? .fromBottom : .fromTop
        transition.duration = 0.28
        transition.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        layer.add(transition, forKey: "zevlink-battery-flip")
    }

    private static func makePhoneStatusImage() -> NSImage? {
        if let url = Bundle.main.url(forResource: "PhoneStatusIcon", withExtension: "png"),
           let image = NSImage(contentsOf: url) {
            image.size = NSSize(width: 18, height: 18)
            image.isTemplate = false
            return image
        }

        let fallback = NSImage(systemSymbolName: "iphone", accessibilityDescription: "Android not connected")
        fallback?.isTemplate = true
        return fallback
    }

    private var macMenuStatus: String {
        switch receiver.status {
        case .running:
            return "Ready"
        case .starting:
            return "Starting"
        case .stopped:
            return "Stopped"
        case .failed:
            return "Needs attention"
        }
    }

    private var latestActivityDate: Date? {
        [receiver.lastReceivedAt, androidClipboardSender.lastSentAt]
            .compactMap { $0 }
            .max()
    }

    private func addDisabledItem(_ title: String, to menu: NSMenu) {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        menu.addItem(item)
    }

    private func actionItem(
        title: String,
        action: Selector,
        isEnabled: Bool = true
    ) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        item.isEnabled = isEnabled
        return item
    }

    private func toggleItem(
        title: String,
        isOn: Bool,
        action: Selector
    ) -> NSMenuItem {
        let item = actionItem(title: title, action: action)
        item.state = isOn ? .on : .off
        return item
    }

    @objc private func startClipboardSync() {
        receiver.startServer()
        macClipboardWatcher.start()
        androidClipboardSender.rediscoverAndroidReceiver()
    }

    @objc private func stopClipboardSync() {
        receiver.stopServer()
        macClipboardWatcher.stop()
    }

    @objc private func reconnectAndroid() {
        androidClipboardSender.rediscoverAndroidReceiver()
    }

    @objc private func findMyPhone() {
        androidClipboardSender.toggleFindMyPhone { success, message in
            guard !success else { return }

            let alert = NSAlert()
            alert.alertStyle = .warning
            alert.messageText = "Could Not Find Phone"
            alert.informativeText = message
            alert.addButton(withTitle: "OK")
            alert.runModal()
        }
    }

    @objc private func toggleMenuBarIcon() {
        appSettings.setShowMenuBarIcon(!appSettings.showMenuBarIcon)
    }

    @objc private func toggleLaunchAtLogin() {
        appSettings.setLaunchAtLoginEnabled(!appSettings.launchAtLoginEnabled)
    }

    @objc private func openSettingsWindow() {
        openSettings()
    }

    @objc private func openIntentCreatorWindow() {
        openIntentCreator()
    }

    @objc private func sendIntentPreset(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? UUID,
              let preset = presetStore.preset(id: id)
        else {
            return
        }
        androidClipboardSender.sendIntentBroadcast(preset)
    }

    @objc private func quit() {
        NSApplication.shared.terminate(nil)
    }
}

@MainActor
private final class IntentCreatorWindowController {
    private let sender: AndroidClipboardSender
    private let presetStore: IntentPresetStore
    private var window: NSWindow?

    init(sender: AndroidClipboardSender, presetStore: IntentPresetStore) {
        self.sender = sender
        self.presetStore = presetStore
    }

    func show() {
        let intentWindow = window ?? makeWindow()
        window = intentWindow
        intentWindow.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private func makeWindow() -> NSWindow {
        let hostingController = NSHostingController(
            rootView: IntentCreatorView(sender: sender, presetStore: presetStore)
        )
        let window = NSWindow(contentViewController: hostingController)
        window.title = "ZevLink Intent Creator"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 980, height: 720))
        window.setFrameAutosaveName("intent-creator")
        window.isReleasedWhenClosed = false
        window.center()
        return window
    }
}

private extension String {
    func truncatedForMenu(limit: Int = 30) -> String {
        guard count > limit else { return self }

        let endIndex = index(startIndex, offsetBy: max(0, limit - 1))
        return String(self[..<endIndex]) + "..."
    }
}
