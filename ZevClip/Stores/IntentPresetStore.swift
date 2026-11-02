import Foundation

@MainActor
final class IntentPresetStore: ObservableObject {
    @Published private(set) var presets: [IntentPreset] = []

    private let defaults: UserDefaults
    private let key = "intentPresets.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
    }

    func save(_ preset: IntentPreset) {
        if let index = presets.firstIndex(where: { $0.id == preset.id }) {
            presets[index] = preset
        } else {
            presets.append(preset)
        }
        presets.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        persist()
    }

    func duplicate(_ preset: IntentPreset) -> IntentPreset {
        var copy = preset
        copy.id = UUID()
        copy.name = "\(preset.name) Copy"
        save(copy)
        return copy
    }

    func delete(_ preset: IntentPreset) {
        presets.removeAll { $0.id == preset.id }
        persist()
    }

    func preset(id: UUID?) -> IntentPreset? {
        guard let id else { return nil }
        return presets.first { $0.id == id }
    }

    private func load() {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([IntentPreset].self, from: data)
        else {
            return
        }
        presets = decoded.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(presets) else { return }
        defaults.set(data, forKey: key)
    }
}
