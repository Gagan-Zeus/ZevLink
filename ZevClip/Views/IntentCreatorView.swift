import AppKit
import SwiftUI

struct IntentCreatorView: View {
    @ObservedObject var sender: AndroidClipboardSender
    @ObservedObject var presetStore: IntentPresetStore
    @ObservedObject private var bluetoothService = MacBluetoothService.shared

    @State private var draft = IntentPreset()
    @State private var selectedPresetID: UUID?
    @State private var showingDeleteConfirmation = false
    @State private var editorMessage: String?

    var body: some View {
        HStack(spacing: 0) {
            presetList
                .frame(width: 220)

            Divider()

            editor
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 900, minHeight: 650)
        .onAppear(perform: selectInitialPreset)
        .onChange(of: selectedPresetID) { _, newValue in
            guard let preset = presetStore.preset(id: newValue) else { return }
            draft = preset
            editorMessage = nil
        }
        .alert("Delete Intent preset?", isPresented: $showingDeleteConfirmation) {
            Button("Delete", role: .destructive) {
                if let preset = presetStore.preset(id: selectedPresetID) {
                    presetStore.delete(preset)
                    selectedPresetID = presetStore.presets.first?.id
                    draft = presetStore.presets.first ?? IntentPreset()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the saved configuration from this Mac.")
        }
    }

    private var presetList: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Intent Presets")
                    .font(.headline)
                Spacer()
                Button {
                    let newPreset = IntentPreset()
                    selectedPresetID = nil
                    draft = newPreset
                    editorMessage = nil
                } label: {
                    Image(systemName: "plus")
                }
                .help("Create a new Intent preset")
            }

            if presetStore.presets.isEmpty {
                ContentUnavailableView(
                    "No Presets",
                    systemImage: "bolt.horizontal.circle",
                    description: Text("Create an Intent to reuse it from this menu.")
                )
            } else {
                List(selection: $selectedPresetID) {
                    ForEach(presetStore.presets) { preset in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(preset.name)
                                .lineLimit(1)
                            Text(preset.action.isEmpty ? "Action not configured" : preset.action)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        .tag(Optional(preset.id))
                        .contextMenu {
                            Button("Duplicate") {
                                let copy = presetStore.duplicate(preset)
                                selectedPresetID = copy.id
                                draft = copy
                            }
                            Button("Delete", role: .destructive) {
                                selectedPresetID = preset.id
                                showingDeleteConfirmation = true
                            }
                        }
                    }
                }
                .listStyle(.sidebar)
            }

            Spacer()
        }
        .padding(16)
    }

    private var editor: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Intent Creator")
                            .font(.title2.bold())
                        Text("Build a typed Android broadcast and send it through the existing ZevLink bridge.")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    connectionBadge
                }

                GroupBox("Configuration") {
                    VStack(alignment: .leading, spacing: 10) {
                        labeledField("Intent name", text: $draft.name, placeholder: "Enable Hotspot")
                        labeledField("Action", text: $draft.action, placeholder: "com.zevlink.HOTSPOT_ON")

                        Text("Delivery mode")
                            .foregroundStyle(.secondary)
                        HStack {
                            Image(systemName: "dot.radiowaves.left.and.right")
                            Text("Broadcast Intent")
                            Spacer()
                            Text("Authenticated ZevLink peer")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        labeledField("Target package (optional)", text: $draft.targetPackage, placeholder: "com.example.automation")
                        labeledField("Data URI (optional)", text: $draft.dataUri, placeholder: "scheme://path")
                        labeledField("MIME type (optional)", text: $draft.mimeType, placeholder: "text/plain")

                        Text("Categories (optional, one per line)")
                            .foregroundStyle(.secondary)
                        TextEditor(text: $draft.categories)
                            .font(.system(.body, design: .monospaced))
                            .frame(height: 58)
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))

                        labeledField("Flags (optional decimal or 0x hexadecimal)", text: $draft.flags, placeholder: "0x10000000")
                    }
                    .padding(4)
                }

                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("Extras")
                                .font(.headline)
                            Spacer()
                            Button("Add extra") {
                                draft.extras.append(IntentExtra())
                            }
                        }

                        if draft.extras.isEmpty {
                            Text("No extras. The broadcast will contain only its action and optional metadata.")
                                .foregroundStyle(.secondary)
                                .font(.callout)
                        } else {
                            ForEach($draft.extras) { $extra in
                                HStack(alignment: .top, spacing: 8) {
                                    TextField("Key", text: $extra.key)
                                        .frame(minWidth: 150)
                                    Picker("Type", selection: $extra.type) {
                                        ForEach(IntentExtraType.allCases) { type in
                                            Text(type.displayName).tag(type)
                                        }
                                    }
                                    .frame(width: 130)
                                    TextField(
                                        extra.type == .stringArray ? "One value per line" : "Value",
                                        text: $extra.value,
                                        axis: extra.type == .stringArray ? .vertical : .horizontal
                                    )
                                    .lineLimit(1...3)
                                    Button(role: .destructive) {
                                        draft.extras.removeAll { $0.id == extra.id }
                                    } label: {
                                        Image(systemName: "minus.circle")
                                    }
                                    .buttonStyle(.borderless)
                                }
                            }
                        }
                    }
                    .padding(4)
                }

                if let editorMessage {
                    Label(editorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                }

                HStack {
                    Button("Save preset") {
                        savePreset()
                    }
                    .keyboardShortcut("s", modifiers: [.command])

                    Button("Duplicate") {
                        let copy = presetStore.duplicate(draft)
                        selectedPresetID = copy.id
                        draft = copy
                    }
                    .disabled(presetStore.preset(id: selectedPresetID) == nil)

                    Button("Delete", role: .destructive) {
                        showingDeleteConfirmation = true
                    }
                    .disabled(presetStore.preset(id: selectedPresetID) == nil)

                    Spacer()

                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(draft.action, forType: .string)
                    } label: {
                        Label("Copy action", systemImage: "doc.on.doc")
                    }
                    .disabled(draft.action.isEmpty)

                    Button("Send Intent") {
                        sendPreset()
                    }
                    .buttonStyle(.borderedProminent)
                }

                GroupBox("Delivery status") {
                    HStack(alignment: .top, spacing: 10) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 10, height: 10)
                            .padding(.top, 5)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(sender.intentBroadcastStatus.title)
                                .font(.headline)
                            Text(sender.intentBroadcastMessage)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                            Text("A successful broadcast request does not confirm that MacroDroid, Tasker, or another receiver executed a macro.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .padding(4)
                }
            }
            .padding(22)
        }
    }

    private var connectionBadge: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(sender.resolvedEndpoint != nil || bluetoothService.isConnected ? .green : .secondary)
                .frame(width: 8, height: 8)
            Text(sender.resolvedEndpoint != nil ? "Wi-Fi connected" : (bluetoothService.isConnected ? "Bluetooth connected" : "Not connected"))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var statusColor: Color {
        switch sender.intentBroadcastStatus {
        case .intentBroadcastRequested, .requestDelivered: return .green
        case .sending: return .orange
        case .broadcastOperationFailed, .requestTimedOut, .invalidConfiguration: return .red
        case .notConnected: return .secondary
        case .idle: return .secondary
        }
    }

    private func labeledField(_ label: String, text: Binding<String>, placeholder: String) -> some View {
        HStack {
            Text(label)
                .frame(width: 210, alignment: .leading)
                .foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .textFieldStyle(.roundedBorder)
        }
    }

    private func selectInitialPreset() {
        if let selectedPresetID, let preset = presetStore.preset(id: selectedPresetID) {
            draft = preset
            return
        }
        if let first = presetStore.presets.first {
            selectedPresetID = first.id
            draft = first
        }
    }

    private func savePreset() {
        do {
            _ = try draft.makeRequest()
            presetStore.save(draft)
            selectedPresetID = draft.id
            editorMessage = "Saved \(draft.name)."
        } catch {
            editorMessage = error.localizedDescription
        }
    }

    private func sendPreset() {
        do {
            _ = try draft.makeRequest()
            sender.sendIntentBroadcast(draft)
            editorMessage = nil
        } catch {
            editorMessage = error.localizedDescription
        }
    }
}
