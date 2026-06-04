import SwiftUI

@main
struct ZevClipApp: App {
    @StateObject private var receiver = ClipboardReceiver()

    var body: some Scene {
        WindowGroup {
            ContentView(receiver: receiver)
        }
        .defaultSize(width: 560, height: 420)
    }
}

