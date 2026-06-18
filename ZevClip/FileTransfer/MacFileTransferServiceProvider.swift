import AppKit
import Foundation

final class MacFileTransferServiceProvider: NSObject {
    @objc func sendFilesToPhone(
        _ pasteboard: NSPasteboard,
        userData: String?,
        error: AutoreleasingUnsafeMutablePointer<NSString?>
    ) {
        let urls = pasteboard.readObjects(forClasses: [NSURL.self]) as? [URL] ?? []
        guard !urls.isEmpty else {
            error.pointee = "No files were selected." as NSString
            return
        }

        Task { @MainActor in
            let runtime = ZevClipRuntime.shared
            runtime.fileTransferService.sendFilesToPhone(
                urls: urls,
                endpoint: runtime.androidClipboardSender.resolvedEndpoint,
                token: runtime.receiver.pairingToken
            )
        }
    }
}
