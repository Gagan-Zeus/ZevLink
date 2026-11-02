import AppKit
import Combine
import Network
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
                    self?.updateMenu()
                }
            }
            .store(in: &cancellables)

        macClipboardWatcher.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.updateMenu()
                }
            }
            .store(in: &cancellables)

        androidClipboardSender.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.updateMenu()
                }
            }
            .store(in: &cancellables)

        appSettings.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.updateMenu()
                }
            }
            .store(in: &cancellables)

        presetStore.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.updateMenu()
                }
            }
            .store(in: &cancellables)

        MacBluetoothService.shared.objectWillChange
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    self?.updateMenu()
                }
            }
            .store(in: &cancellables)

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] _ in
            DispatchQueue.main.async {
                self?.updateMenu()
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
        configureStatusButton(item.button)
        item.menu = makeMenu()
        statusItem = item
    }

    private func removeStatusItem() {
        guard let statusItem else { return }

        NSStatusBar.system.removeStatusItem(statusItem)
        self.statusItem = nil
    }

    private func updateMenu() {
        guard let statusItem else { return }

        configureStatusButton(statusItem.button)
        let menu = makeMenu()
        menu.delegate = self
        statusItem.menu = menu
    }

    func menuWillOpen(_ menu: NSMenu) {
        updateMenu()
    }

    private func makeMenu() -> NSMenu {
        let menu = NSMenu()

        let hasLocalNetwork = !LocalNetworkHost.currentPairingHosts().isEmpty
        let currentMode: String
        if hasLocalNetwork && androidClipboardSender.activeTransportMode == .wifi && androidClipboardSender.resolvedEndpoint != nil {
            currentMode = "Mode: Wi-Fi"
        } else if MacBluetoothService.shared.isConnected {
            currentMode = "Mode: Bluetooth"
        } else {
            currentMode = "Mode: Disconnected"
        }
        addDisabledItem(currentMode, to: menu)
        menu.addItem(.separator())

        menu.addItem(actionItem(
            title: androidClipboardSender.isFindingPhone ? "Stop Ringing" : "Find My Phone",
            action: #selector(findMyPhone),
            isEnabled: androidClipboardSender.activeTransportMode == .wifi || MacBluetoothService.shared.isConnected
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

        return menu
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

        let hasLocalNetwork = !LocalNetworkHost.currentPairingHosts().isEmpty

        // Priority 1: Verified Wi-Fi
        if hasLocalNetwork && androidClipboardSender.activeTransportMode == .wifi, let endpoint = androidClipboardSender.resolvedEndpoint {
            button.image = nil
            button.attributedTitle = batteryMenuAttributedTitle(for: endpoint)
            button.toolTip = "ZevLink (Mode: Wi-Fi) • Phone Battery: \(batteryMenuTitle(for: endpoint))"
            return
        }

        // Priority 2: Bluetooth connected
        if MacBluetoothService.shared.isConnected {
            button.image = nil
            let battery = MacBluetoothService.shared.phoneBatteryPercentage ?? (androidClipboardSender.activeTransportMode == .wifi ? androidClipboardSender.resolvedEndpoint?.batteryPercentage : nil)
            if let battery {
                button.attributedTitle = NSAttributedString(
                    string: "\(battery)%",
                    attributes: [
                        .font: NSFont.systemFont(ofSize: 11, weight: .semibold),
                        .foregroundColor: NSColor.labelColor
                    ]
                )
                button.toolTip = "ZevLink (Mode: Bluetooth) • Phone Battery: \(battery)%"
            } else {
                button.attributedTitle = NSAttributedString(
                    string: "BT",
                    attributes: [
                        .font: NSFont.systemFont(ofSize: 11, weight: .semibold),
                        .foregroundColor: NSColor.labelColor
                    ]
                )
                button.toolTip = "ZevLink (Mode: Bluetooth)"
            }
            return
        }

        button.attributedTitle = NSAttributedString()
        button.title = ""
        button.image = phoneStatusImage()
        button.toolTip = "ZevLink: Android not connected"
    }

    private func batteryMenuTitle(for endpoint: AndroidReceiverEndpoint) -> String {
        if let batteryPercentage = endpoint.batteryPercentage {
            return "\(batteryPercentage)%"
        }

        return "--%"
    }

    private func batteryMenuAttributedTitle(for endpoint: AndroidReceiverEndpoint) -> NSAttributedString {
        NSAttributedString(
            string: batteryMenuTitle(for: endpoint),
            attributes: [
                .font: NSFont.systemFont(ofSize: 11, weight: .semibold),
                .foregroundColor: NSColor.labelColor
            ]
        )
    }

    private func phoneStatusImage() -> NSImage? {
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
