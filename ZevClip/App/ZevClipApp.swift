import AppKit

@main
final class ZevClipApp: NSObject, NSApplicationDelegate {
    static let settingsWindowID = "settings"

    static func main() {
        let app = NSApplication.shared
        let delegate = ZevClipApp()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.finishLaunching()

        DispatchQueue.main.async {
            ZevClipRuntime.shared.start()
        }

        withExtendedLifetime(delegate) {
            app.run()
        }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
    }

    func applicationShouldHandleReopen(
        _ sender: NSApplication,
        hasVisibleWindows flag: Bool
    ) -> Bool {
        ZevClipRuntime.shared.showSettingsWindow()
        return false
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }
}
