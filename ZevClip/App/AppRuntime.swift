import AppKit
import Combine
import SwiftUI

@MainActor
final class ZevClipRuntime {
    static let shared = ZevClipRuntime()

    let receiver = ClipboardReceiver()
    let appSettings = AppSettings()

    private lazy var settingsWindowController = SettingsWindowController(
        receiver: receiver,
        appSettings: appSettings
    )
    private lazy var statusItemController = StatusItemController(
        receiver: receiver,
        appSettings: appSettings,
        openSettings: { [weak self] in
            self?.showSettingsWindow()
        }
    )

    private init() {}

    func start() {
        statusItemController.start()

        if !appSettings.showMenuBarIcon {
            showSettingsWindow()
        }
    }

    func showSettingsWindow() {
        settingsWindowController.show()
    }
}

@MainActor
private final class SettingsWindowController {
    private let receiver: ClipboardReceiver
    private let appSettings: AppSettings
    private var window: NSWindow?

    init(receiver: ClipboardReceiver, appSettings: AppSettings) {
        self.receiver = receiver
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
            rootView: ContentView(receiver: receiver, appSettings: appSettings)
        )
        let window = NSWindow(contentViewController: hostingController)
        window.title = "ZevClip Settings"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 560, height: 720))
        window.setFrameAutosaveName("settings")
        window.isReleasedWhenClosed = false
        window.center()
        return window
    }
}

@MainActor
private final class StatusItemController: NSObject {
    private let receiver: ClipboardReceiver
    private let appSettings: AppSettings
    private let openSettings: () -> Void

    private var statusItem: NSStatusItem?
    private var cancellables: Set<AnyCancellable> = []

    init(
        receiver: ClipboardReceiver,
        appSettings: AppSettings,
        openSettings: @escaping () -> Void
    ) {
        self.receiver = receiver
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
        item.button?.toolTip = "ZevClip"
        item.button?.image = menuBarImage()
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

        statusItem.button?.image = menuBarImage()
        statusItem.menu = makeMenu()
    }

    private func makeMenu() -> NSMenu {
        let menu = NSMenu()

        addDisabledItem("Receiver: \(receiver.status.title)", to: menu)
        addDisabledItem(receiver.detailMessage.truncatedForMenu(), to: menu)
        addDisabledItem(
            receiver.isAdvertising ? "Bonjour: Advertising" : "Bonjour: Not advertising",
            to: menu
        )
        addDisabledItem("Port: \(ClipboardReceiver.port)", to: menu)

        if let lastReceivedAt = receiver.lastReceivedAt {
            addDisabledItem(
                "Last sync: \(lastReceivedAt.formatted(date: .omitted, time: .shortened))",
                to: menu
            )
        } else {
            addDisabledItem("Last sync: None", to: menu)
        }

        if let lastReceivedText = receiver.lastReceivedText, !lastReceivedText.isEmpty {
            menu.addItem(.separator())
            addDisabledItem("Last Received", to: menu)
            addDisabledItem(lastReceivedText.truncatedForMenu(), to: menu)
        }

        menu.addItem(.separator())
        menu.addItem(actionItem(
            title: "Start Receiver",
            action: #selector(startReceiver),
            isEnabled: receiver.canStart
        ))
        menu.addItem(actionItem(
            title: "Stop Receiver",
            action: #selector(stopReceiver),
            isEnabled: receiver.canStop
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
        menu.addItem(actionItem(title: "Quit ZevClip", action: #selector(quit)))

        return menu
    }

    private func menuBarImage() -> NSImage? {
        if let image = NSImage(named: "MenuBarIcon") {
            image.isTemplate = false
            return image
        }

        let fallback = NSImage(systemSymbolName: "link.circle", accessibilityDescription: "ZevClip")
        fallback?.isTemplate = true
        return fallback
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

    @objc private func startReceiver() {
        receiver.startServer()
    }

    @objc private func stopReceiver() {
        receiver.stopServer()
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
