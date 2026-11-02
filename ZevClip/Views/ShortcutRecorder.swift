import SwiftUI

struct KeyShortcut: Codable, Equatable {
    var keyCode: Int
    var modifiers: UInt
    var displayString: String
}

struct ShortcutRecorder: View {
    let title: String
    @Binding var shortcut: KeyShortcut?
    @State private var isRecording = false

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Button(action: {
                isRecording = true
            }) {
                Text(isRecording ? "Recording..." : (shortcut?.displayString ?? "Click to record"))
                    .frame(minWidth: 100)
            }
            .background(ShortcutRecorderOverlay(isRecording: $isRecording, shortcut: $shortcut))

            if shortcut != nil {
                Button(action: {
                    shortcut = nil
                }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

struct ShortcutRecorderOverlay: NSViewRepresentable {
    @Binding var isRecording: Bool
    @Binding var shortcut: KeyShortcut?

    func makeNSView(context: Context) -> KeyCatcherView {
        let view = KeyCatcherView()
        view.onKeyDown = { event in
            guard isRecording else { return false }

            if event.keyCode == 53 { // Escape cancels recording.
                isRecording = false
                return true
            }

            let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)

            // Ignore if only modifiers are pressed
            let keyCode = event.keyCode
            if keyCode == 54 || keyCode == 55 || keyCode == 56 || keyCode == 58 || keyCode == 59 || keyCode == 60 || keyCode == 61 || keyCode == 62 {
                return true // Still recording, don't stop
            }

            let shortcutModifiers: NSEvent.ModifierFlags = [.command, .option, .control, .shift]
            guard !modifiers.intersection(shortcutModifiers).isEmpty else {
                NSSound.beep()
                return true
            }

            var display = ""
            if modifiers.contains(.control) { display += "⌃" }
            if modifiers.contains(.option) { display += "⌥" }
            if modifiers.contains(.shift) { display += "⇧" }
            if modifiers.contains(.command) { display += "⌘" }
            if let chars = event.charactersIgnoringModifiers?.uppercased(), !chars.isEmpty {
                display += chars
            }

            guard !display.isEmpty else {
                NSSound.beep()
                return true
            }

            shortcut = KeyShortcut(keyCode: Int(keyCode), modifiers: modifiers.rawValue, displayString: display)
            isRecording = false
            return true
        }
        return view
    }

    func updateNSView(_ nsView: KeyCatcherView, context: Context) {
        if isRecording {
            DispatchQueue.main.async {
                nsView.window?.makeFirstResponder(nsView)
            }
        }
    }
}

class KeyCatcherView: NSView {
    var onKeyDown: ((NSEvent) -> Bool)?

    override var acceptsFirstResponder: Bool { true }

    override func keyDown(with event: NSEvent) {
        if let onKeyDown = onKeyDown, onKeyDown(event) {
            return
        }
        super.keyDown(with: event)
    }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if let onKeyDown = onKeyDown, onKeyDown(event) {
            return true
        }
        return super.performKeyEquivalent(with: event)
    }
}
