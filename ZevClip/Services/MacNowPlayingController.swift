import Foundation
import AppKit
import MediaPlayer

struct AndroidNowPlayingPayload: Decodable {
    let title: String?
    let artist: String?
    let album: String?
    let isPlaying: Bool?
    let durationMillis: Double?
    let positionMillis: Double?
    let artworkBase64: String?

    var hasMetadata: Bool {
        [title, artist, album].contains { value in
            value?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        }
    }
}

@MainActor
final class MacNowPlayingController {
    static let shared = MacNowPlayingController()

    private var commandHandler: ((String) -> Void)?
    private var commandTargets: [Any] = []

    private init() {}

    func start(commandHandler: @escaping (String) -> Void) {
        self.commandHandler = commandHandler
        configureRemoteCommands()
    }

    func update(from payload: AndroidNowPlayingPayload) {
        guard payload.hasMetadata else { return }

        var nowPlayingInfo: [String: Any] = [:]
        if let title = payload.title?.trimmedNonEmpty {
            nowPlayingInfo[MPMediaItemPropertyTitle] = title
        }
        if let artist = payload.artist?.trimmedNonEmpty {
            nowPlayingInfo[MPMediaItemPropertyArtist] = artist
        }
        if let album = payload.album?.trimmedNonEmpty {
            nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
        }
        if let duration = payload.durationMillis, duration > 0 {
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration / 1000.0
        }
        if let position = payload.positionMillis, position >= 0 {
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position / 1000.0
        }
        if let artwork = artwork(from: payload.artworkBase64) {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
        }

        let playbackRate = payload.isPlaying == false ? 0.0 : 1.0
        nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate
        nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0

        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nowPlayingInfo
        center.playbackState = payload.isPlaying == false ? .paused : .playing
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.removeTarget(nil)
        center.pauseCommand.removeTarget(nil)
        center.togglePlayPauseCommand.removeTarget(nil)
        center.nextTrackCommand.removeTarget(nil)
        center.previousTrackCommand.removeTarget(nil)

        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        center.previousTrackCommand.isEnabled = true

        commandTargets = [
            center.playCommand.addTarget { [weak self] _ in
                self?.send("playpause")
                return .success
            },
            center.pauseCommand.addTarget { [weak self] _ in
                self?.send("pause")
                return .success
            },
            center.togglePlayPauseCommand.addTarget { [weak self] _ in
                self?.send("playpause")
                return .success
            },
            center.nextTrackCommand.addTarget { [weak self] _ in
                self?.send("next")
                return .success
            },
            center.previousTrackCommand.addTarget { [weak self] _ in
                self?.send("previous")
                return .success
            }
        ]
    }

    private func send(_ action: String) {
        commandHandler?(action)
    }

    private func artwork(from base64: String?) -> MPMediaItemArtwork? {
        guard
            let base64 = base64?.trimmedNonEmpty,
            let data = Data(base64Encoded: base64),
            let image = NSImage(data: data)
        else {
            return nil
        }

        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}

private extension String {
    var trimmedNonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
