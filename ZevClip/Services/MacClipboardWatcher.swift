import AppKit
import Foundation
import UniformTypeIdentifiers

enum MacClipboardContent: Equatable {
    case text(String)
    case pngImage(Data)
    case file(data: Data, fileName: String, mimeType: String)

    var byteCount: Int {
        switch self {
        case .text(let text):
            return text.utf8.count
        case .pngImage(let data):
            return data.count
        case .file(let data, _, _):
            return data.count
        }
    }
}

struct MacClipboardChange: Identifiable, Equatable {
    let id = UUID()
    let content: MacClipboardContent
    let changeCount: Int
    let observedAt: Date
}

@MainActor
final class MacClipboardWatcher: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var status = "Clipboard watcher is stopped."
    @Published private(set) var lastObservedText: String?
    @Published private(set) var lastObservedAt: Date?
    @Published private(set) var lastObservedChange: MacClipboardChange?

    var onContentChanged: ((MacClipboardChange) -> Void)?

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

        let fileURLs = copiedFileURLs()
        if !fileURLs.isEmpty {
            guard
                fileURLs.count == 1,
                let fileContent = approvedFileContent(at: fileURLs[0])
            else {
                ignoredProgrammaticWrite = nil
                status = "Ignored copied folders, multiple files, or unsupported file types."
                return
            }

            let change = MacClipboardChange(
                content: fileContent,
                changeCount: changeCount,
                observedAt: Date()
            )
            lastObservedText = nil
            lastObservedAt = change.observedAt
            lastObservedChange = change
            status = "Observed approved clipboard file \(fileURLs[0].lastPathComponent)."
            onContentChanged?(change)
            return
        }

        if let pngData = pngImageData() {
            let change = MacClipboardChange(
                content: .pngImage(pngData),
                changeCount: changeCount,
                observedAt: Date()
            )
            lastObservedText = nil
            lastObservedAt = change.observedAt
            lastObservedChange = change
            status = "Observed \(pngData.count) bytes of image data from the Mac clipboard."
            onContentChanged?(change)
            return
        }

        guard let text = pasteboard.string(forType: .string) else {
            ignoredProgrammaticWrite = nil
            status = "Clipboard changed; no supported text or image content found."
            return
        }

        if shouldIgnoreProgrammaticWrite(text: text, changeCount: changeCount) {
            status = "Ignored clipboard text received from Android."
            return
        }

        let change = MacClipboardChange(
            content: .text(text),
            changeCount: changeCount,
            observedAt: Date()
        )
        lastObservedText = text
        lastObservedAt = change.observedAt
        lastObservedChange = change
        status = "Observed \(text.utf8.count) UTF-8 bytes from the Mac clipboard."
        onContentChanged?(change)
    }

    private func copiedFileURLs() -> [URL] {
        let objects = pasteboard.readObjects(
            forClasses: [NSURL.self],
            options: [.urlReadingFileURLsOnly: true]
        ) ?? []
        return objects.compactMap { ($0 as? NSURL)?.filePathURL }
    }

    private func approvedFileContent(at url: URL) -> MacClipboardContent? {
        guard
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
            values.isRegularFile == true,
            let fileSize = values.fileSize,
            fileSize > 0,
            fileSize <= Self.maximumFileSize
        else {
            return nil
        }

        let fileExtension = url.pathExtension.lowercased()
        guard Self.approvedFileExtensions.contains(fileExtension) else {
            return nil
        }

        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            return nil
        }
        if fileExtension == "xml" && !Self.isAndroidVectorDrawable(data) {
            return nil
        }

        let mimeType = Self.mimeType(for: fileExtension)
        return .file(data: data, fileName: url.lastPathComponent, mimeType: mimeType)
    }

    private func pngImageData() -> Data? {
        if let pngData = pasteboard.data(forType: .png), !pngData.isEmpty {
            return pngData
        }

        let image: NSImage?
        if let tiffData = pasteboard.data(forType: .tiff) {
            image = NSImage(data: tiffData)
        } else {
            image = NSImage(pasteboard: pasteboard)
        }

        guard
            let tiffData = image?.tiffRepresentation,
            let bitmap = NSBitmapImageRep(data: tiffData)
        else {
            return nil
        }

        return bitmap.representation(using: .png, properties: [:])
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
    private static let maximumFileSize = 20 * 1_024 * 1_024
    private static let approvedFileExtensions: Set<String> = [
        "jpeg", "jpg", "png", "gif", "webp", "avif", "heif", "heic",
        "tif", "tiff", "bmp", "svg", "xml", "icns", "raw", "dng",
        "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "ico", "psd"
    ]

    private static func mimeType(for fileExtension: String) -> String {
        if fileExtension == "xml" {
            return "application/vnd.android.vector-drawable"
        }
        return UTType(filenameExtension: fileExtension)?.preferredMIMEType
            ?? fallbackMIMETypes[fileExtension]
            ?? "application/octet-stream"
    }

    private static func isAndroidVectorDrawable(_ data: Data) -> Bool {
        guard let prefix = String(data: data.prefix(16_384), encoding: .utf8) else {
            return false
        }
        return prefix.range(of: #"<vector(?:\s|>)"#, options: .regularExpression) != nil &&
            prefix.contains("http://schemas.android.com/apk/res/android")
    }

    private static let fallbackMIMETypes: [String: String] = [
        "avif": "image/avif",
        "heif": "image/heif",
        "heic": "image/heic",
        "raw": "image/x-raw",
        "cr2": "image/x-canon-cr2",
        "cr3": "image/x-canon-cr3",
        "nef": "image/x-nikon-nef",
        "arw": "image/x-sony-arw",
        "orf": "image/x-olympus-orf",
        "rw2": "image/x-panasonic-rw2",
        "raf": "image/x-fuji-raf",
        "psd": "image/vnd.adobe.photoshop"
    ]
}
