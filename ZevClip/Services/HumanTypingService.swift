import Foundation
import AppKit

@MainActor
final class HumanTypingService {
    static let shared = HumanTypingService()

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var lastCopiedText = ""
    private(set) var isTyping = false
    private var isSynthesizingKeystroke = false
    private var typingTask: Task<Void, Never>?
    private var remainingText = ""
    private var remainingTextStartsAtLineStart = true
    private var hasEmittedCharacterInCurrentTask = false
    private var permissionCheckTimer: Timer?

    private init() {}

    func start() {
        refresh(promptIfNeeded: false)
    }

    func refresh(promptIfNeeded: Bool = false) {
        guard ZevClipRuntime.shared.appSettings.humanTypingStartShortcut != nil else {
            stop()
            remainingText = ""
            remainingTextStartsAtLineStart = true
            lastCopiedText = ""
            return
        }

        guard eventTap == nil else { return }
        let isTrusted: Bool
        if promptIfNeeded {
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true]
            isTrusted = AXIsProcessTrustedWithOptions(options as CFDictionary)
        } else {
            isTrusted = AXIsProcessTrusted()
        }

        if isTrusted {
            setupEventTap()
        }

        if eventTap == nil && promptIfNeeded {
            permissionCheckTimer?.invalidate()
            permissionCheckTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self = self else { return }
                    if AXIsProcessTrusted() {
                        self.setupEventTap()
                        if self.eventTap != nil {
                            self.permissionCheckTimer?.invalidate()
                            self.permissionCheckTimer = nil
                            NSSound.beep() // Notify user it's ready
                        }
                    }
                }
            }
        }
    }

    func stop() {
        typingTask?.cancel()
        typingTask = nil
        permissionCheckTimer?.invalidate()
        permissionCheckTimer = nil
        if !hasEmittedCharacterInCurrentTask {
            remainingText = ""
            remainingTextStartsAtLineStart = true
        }
        hasEmittedCharacterInCurrentTask = false
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
            if let runLoopSource = runLoopSource {
                CFRunLoopRemoveSource(CFRunLoopGetCurrent(), runLoopSource, .commonModes)
            }
            self.eventTap = nil
            self.runLoopSource = nil
        }
        isTyping = false
    }

    private func setupEventTap() {
        if eventTap != nil { return }

        let eventMask = (1 << CGEventType.keyDown.rawValue)

        let callback: CGEventTapCallBack = { (proxy, type, event, refcon) -> Unmanaged<CGEvent>? in
            let service = Unmanaged<HumanTypingService>.fromOpaque(refcon!).takeUnretainedValue()

            if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
                if let tap = service.eventTap {
                    CGEvent.tapEnable(tap: tap, enable: true)
                }
                return Unmanaged.passRetained(event)
            }

            guard type == .keyDown else { return Unmanaged.passRetained(event) }

            let settings = ZevClipRuntime.shared.appSettings
            let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))
            let nsEvent = NSEvent(cgEvent: event)
            let currentModifiers = nsEvent?.modifierFlags.intersection(.deviceIndependentFlagsMask).rawValue ?? 0

            if let startShortcut = settings.humanTypingStartShortcut,
               startShortcut.keyCode == keyCode,
               startShortcut.modifiers == currentModifiers {

                // Fire and forget
                Task { @MainActor in
                    service.startTypingFromClipboard()
                }
                return nil // Consume shortcut
            }

            // Check if typing and the event is NOT synthesized by us
            if MainActor.assumeIsolated({ service.isTyping && !service.isSynthesizingKeystroke }) {
                Task { @MainActor in
                    service.stopTyping()
                }
            }

            return Unmanaged.passRetained(event)
        }

        let refcon = Unmanaged.passUnretained(self).toOpaque()
        eventTap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: CGEventMask(eventMask),
            callback: callback,
            userInfo: refcon
        )

        if let tap = eventTap {
            runLoopSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
            CFRunLoopAddSource(RunLoop.main.getCFRunLoop(), runLoopSource, .commonModes)
            CGEvent.tapEnable(tap: tap, enable: true)
        }
    }

    func startTypingFromClipboard() {
        guard ZevClipRuntime.shared.appSettings.humanTypingStartShortcut != nil else { return }
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            NSSound.beep()
            return
        }

        // Treat all common line-ending formats as one logical Enter keypress.
        // In particular, CRLF must not become two separate lines when it is
        // resumed after a cancellation.
        let normalizedText = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        typingTask?.cancel()
        isTyping = true
        hasEmittedCharacterInCurrentTask = false

        let textToType: String
        let startsAtLineStart: Bool
        if normalizedText == lastCopiedText && !remainingText.isEmpty {
            textToType = remainingText
            startsAtLineStart = remainingTextStartsAtLineStart
        } else {
            lastCopiedText = normalizedText
            remainingText = normalizedText
            textToType = normalizedText
            remainingTextStartsAtLineStart = true
            startsAtLineStart = true
        }

        typingTask = Task {
            do {
                try await Task.sleep(nanoseconds: 1_000_000_000) // 1 second delay
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            await typeTextHumanLike(textToType, startsAtLineStart: startsAtLineStart)
        }
    }

    func stopTyping() {
        typingTask?.cancel()
        isTyping = false
        if !hasEmittedCharacterInCurrentTask {
            remainingText = ""
            remainingTextStartsAtLineStart = true
        }
    }

    private func typeTextHumanLike(_ text: String, startsAtLineStart: Bool) async {
        let speed = UserDefaults.standard.double(forKey: "humanTypingSpeed")
        let cps = speed > 0 ? speed : 15.0
        let baseDelay = 1.0 / cps

        var currentIndex = text.startIndex
        var isAtLineStart = startsAtLineStart
        while currentIndex < text.endIndex {
            if Task.isCancelled {
                self.remainingText = String(text[currentIndex...])
                self.remainingTextStartsAtLineStart = isAtLineStart
                break
            }

            let char = text[currentIndex]

            // Let the focused editor create indentation after Return. Source
            // indentation is deliberately skipped so it is not duplicated.
            if isAtLineStart && (char == " " || char == "\t") {
                currentIndex = text.index(after: currentIndex)
                continue
            }

            typeCharacter(char)

            var delay = baseDelay
            if char.isWhitespace {
                delay *= 1.5
            } else if char.isPunctuation {
                delay *= 2.0
            }
            let jitter = Double.random(in: 0.8...1.2)
            delay *= jitter

            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))

            let nextIndex = text.index(after: currentIndex)
            if Task.isCancelled {
                self.remainingText = String(text[nextIndex...])
                self.remainingTextStartsAtLineStart = char == "\n"
                break
            }

            isAtLineStart = char == "\n"
            currentIndex = nextIndex
        }

        if currentIndex == text.endIndex {
            isTyping = false
            remainingText = ""
            remainingTextStartsAtLineStart = false
            hasEmittedCharacterInCurrentTask = false
        }
    }

    private func typeCharacter(_ char: Character) {
        // Set flag so event tap ignores this
        isSynthesizingKeystroke = true
        defer {
            // Need to reset it AFTER a small delay because events propagate asynchronously
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 10_000_000) // 10ms
                self.isSynthesizingKeystroke = false
            }
        }

        // Browser-based editors handle Enter in their keyboard event path
        // (keydown, beforeinput, keyup) instead of accepting a literal LF
        // inserted through keyboardSetUnicodeString. Use the macOS Return
        // virtual key so editors such as Monaco and CodeMirror can process
        // the newline and update their cursor/indentation state.
        if char == "\n" {
            typeReturnKey()
            return
        }

        var unichar = Array(String(char).utf16)

        guard let eventDown = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true) else { return }
        eventDown.keyboardSetUnicodeString(stringLength: unichar.count, unicodeString: &unichar)
        eventDown.post(tap: .cghidEventTap)

        guard let eventUp = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false) else { return }
        eventUp.keyboardSetUnicodeString(stringLength: unichar.count, unicodeString: &unichar)
        eventUp.post(tap: .cghidEventTap)
        hasEmittedCharacterInCurrentTask = true
    }

    private func typeReturnKey() {
        // kVK_Return from Carbon/HIToolbox. Keeping this service AppKit-only
        // avoids importing Carbon just for this well-known virtual key code.
        let returnKeyCode: CGKeyCode = 36
        let source = CGEventSource(stateID: .hidSystemState)

        guard
            let eventDown = CGEvent(keyboardEventSource: source, virtualKey: returnKeyCode, keyDown: true),
            let eventUp = CGEvent(keyboardEventSource: source, virtualKey: returnKeyCode, keyDown: false)
        else {
            return
        }

        eventDown.post(tap: .cghidEventTap)
        eventUp.post(tap: .cghidEventTap)
        hasEmittedCharacterInCurrentTask = true
    }
}
