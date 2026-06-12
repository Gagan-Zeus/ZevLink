import AppKit
import Combine
import SwiftUI

@MainActor
final class ZevClipRuntime {
    static let shared = ZevClipRuntime()

    let receiver = ClipboardReceiver()
    let macClipboardWatcher = MacClipboardWatcher()
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
    private lazy var statusItemController = StatusItemController(
        receiver: receiver,
        macClipboardWatcher: macClipboardWatcher,
        androidClipboardSender: androidClipboardSender,
        appSettings: appSettings,
        openSettings: { [weak self] in
            self?.showSettingsWindow()
        }
    )

    private init() {
        MacNotificationPresenter.shared.requestAuthorizationIfNeeded()

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
        receiver.onAndroidNowPlaying = { payload in
            MacNowPlayingController.shared.update(from: payload)
        }
        MacNowPlayingController.shared.start { [weak self] action in
            Task { @MainActor in
                self?.androidClipboardSender.sendAndroidMediaCommand(action: action)
            }
        }
        MacNotificationPresenter.shared.onDismiss = { [weak self] notificationKey in
            Task { @MainActor in
                self?.androidClipboardSender.dismissAndroidNotification(notificationKey: notificationKey)
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
        macClipboardWatcher.onTextChanged = { [weak self] change in
            self?.androidClipboardSender.send(change)
        }
    }

    func start() {
        startClipboardSync()
        statusItemController.start()

        if !appSettings.showMenuBarIcon {
            showSettingsWindow()
        }
    }

    private func startClipboardSync() {
        receiver.startServer()
        macClipboardWatcher.start()
        androidClipboardSender.startStatusMonitoring()
        androidClipboardSender.rediscoverAndroidReceiver()
    }

    func showSettingsWindow() {
        settingsWindowController.show()
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
private final class StatusItemController: NSObject {
    private let receiver: ClipboardReceiver
    private let macClipboardWatcher: MacClipboardWatcher
    private let androidClipboardSender: AndroidClipboardSender
    private let appSettings: AppSettings
    private let openSettings: () -> Void

    private var statusItem: NSStatusItem?
    private var cancellables: Set<AnyCancellable> = []

    private var isClipboardSyncRunning: Bool {
        receiver.status == .running && macClipboardWatcher.isRunning
    }

    init(
        receiver: ClipboardReceiver,
        macClipboardWatcher: MacClipboardWatcher,
        androidClipboardSender: AndroidClipboardSender,
        appSettings: AppSettings,
        openSettings: @escaping () -> Void
    ) {
        self.receiver = receiver
        self.macClipboardWatcher = macClipboardWatcher
        self.androidClipboardSender = androidClipboardSender
        self.appSettings = appSettings
        self.openSettings = openSettings
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
        statusItem.menu = makeMenu()
    }

    private func makeMenu() -> NSMenu {
        let menu = NSMenu()

        addDisabledItem(
            isClipboardSyncRunning ? "Clipboard Sync: On" : "Clipboard Sync: Off",
            to: menu
        )
        addDisabledItem("Mac: \(macMenuStatus)", to: menu)
        if let endpoint = androidClipboardSender.resolvedEndpoint {
            addDisabledItem("Android: \(batteryMenuTitle(for: endpoint))", to: menu)
        } else {
            addDisabledItem("Android: \(androidClipboardSender.isDiscovering ? "Searching" : "Not connected")", to: menu)
        }

        if let lastActivity = latestActivityDate {
            addDisabledItem(
                "Last activity: \(lastActivity.formatted(date: .omitted, time: .shortened))",
                to: menu
            )
        }

        menu.addItem(.separator())
        menu.addItem(actionItem(
            title: "Start Clipboard Sync",
            action: #selector(startClipboardSync),
            isEnabled: !isClipboardSyncRunning
        ))
        menu.addItem(actionItem(
            title: "Stop Clipboard Sync",
            action: #selector(stopClipboardSync),
            isEnabled: isClipboardSyncRunning
        ))
        menu.addItem(actionItem(
            title: "Reconnect Android",
            action: #selector(reconnectAndroid),
            isEnabled: !androidClipboardSender.isDiscovering
        ))

        menu.addItem(.separator())
        menu.addItem(toggleItem(
            title: "Show in Menu Bar",
            isOn: appSettings.showMenuBarIcon,
            action: #selector(toggleMenuBarIcon)
        ))
        menu.addItem(toggleItem(
            title: "Launch at Login",
            isOn: appSettings.launchAtLoginEnabled,
            action: #selector(toggleLaunchAtLogin)
        ))
        menu.addItem(actionItem(title: "Open Settings...", action: #selector(openSettingsWindow)))

        menu.addItem(.separator())
        menu.addItem(actionItem(title: "Quit ZevLink", action: #selector(quit)))

        return menu
    }

    private func configureStatusButton(_ button: NSStatusBarButton?) {
        guard let button else { return }

        if let endpoint = androidClipboardSender.resolvedEndpoint {
            button.image = nil
            button.attributedTitle = batteryMenuAttributedTitle(for: endpoint)
            button.toolTip = "ZevLink Android battery: \(batteryMenuTitle(for: endpoint))"
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

    @objc private func toggleMenuBarIcon() {
        appSettings.setShowMenuBarIcon(!appSettings.showMenuBarIcon)
    }

    @objc private func toggleLaunchAtLogin() {
        appSettings.setLaunchAtLoginEnabled(!appSettings.launchAtLoginEnabled)
    }

    @objc private func openSettingsWindow() {
        openSettings()
    }

    @objc private func quit() {
        NSApplication.shared.terminate(nil)
    }
}

private extension String {
    func truncatedForMenu(limit: Int = 30) -> String {
        guard count > limit else { return self }

        let endIndex = index(startIndex, offsetBy: max(0, limit - 1))
        return String(self[..<endIndex]) + "..."
    }
}
