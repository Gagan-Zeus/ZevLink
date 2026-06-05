import AppKit
import Foundation

struct MacClipboardTextChange: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let changeCount: Int
    let observedAt: Date
}

@MainActor
final class MacClipboardWatcher: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var status = "Clipboard watcher is stopped."
    @Published private(set) var lastObservedText: String?
    @Published private(set) var lastObservedAt: Date?
    @Published private(set) var lastObservedChange: MacClipboardTextChange?

    var onTextChanged: ((MacClipboardTextChange) -> Void)?

    private let pasteboard: NSPasteboard
    private var timer: Timer?
    private var lastKnownChangeCount: Int
    private var ignoredProgrammaticWrite: IgnoredPasteboardWrite?

    init(pasteboard: NSPasteboard = .general) {
        self.pasteboard = pasteboard
        lastKnownChangeCount = pasteboard.changeCount
    }

    func start() {
        guard !isRunning else { return }

        lastKnownChangeCount = pasteboard.changeCount
        isRunning = true
        status = "Watching Mac clipboard changes."

        let newTimer = Timer(timeInterval: Self.pollInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.pollPasteboard()
            }
        }
        newTimer.tolerance = Self.pollTolerance
        RunLoop.main.add(newTimer, forMode: .common)
        timer = newTimer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        isRunning = false
        status = "Clipboard watcher is stopped."
    }

    func markProgrammaticPasteboardWrite(text: String, changeCount: Int) {
        ignoredProgrammaticWrite = IgnoredPasteboardWrite(
            text: text,
            changeCount: changeCount
        )
        lastKnownChangeCount = changeCount
    }

    private func pollPasteboard() {
        let changeCount = pasteboard.changeCount
        guard changeCount != lastKnownChangeCount else { return }

        lastKnownChangeCount = changeCount

        guard let text = pasteboard.string(forType: .string) else {
            ignoredProgrammaticWrite = nil
            status = "Clipboard changed; no text content found."
            return
        }

        if shouldIgnoreProgrammaticWrite(text: text, changeCount: changeCount) {
            status = "Ignored clipboard text received from Android."
            return
        }

        let change = MacClipboardTextChange(
            text: text,
            changeCount: changeCount,
            observedAt: Date()
        )
        lastObservedText = text
        lastObservedAt = change.observedAt
        lastObservedChange = change
        status = "Observed \(text.utf8.count) UTF-8 bytes from the Mac clipboard."
        onTextChanged?(change)
    }

    private func shouldIgnoreProgrammaticWrite(text: String, changeCount: Int) -> Bool {
        guard let ignoredProgrammaticWrite else { return false }
        self.ignoredProgrammaticWrite = nil

        return ignoredProgrammaticWrite.changeCount == changeCount
            && ignoredProgrammaticWrite.text == text
    }

    private struct IgnoredPasteboardWrite {
        let text: String
        let changeCount: Int
    }

    private static let pollInterval: TimeInterval = 0.75
    private static let pollTolerance: TimeInterval = 0.35
}
