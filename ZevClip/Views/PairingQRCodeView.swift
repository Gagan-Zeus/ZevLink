import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

struct PairingQRCodeView: View {
    let token: String
    let deviceId: String

    @State private var host = LocalNetworkHost.currentPairingHost()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Pairing QR")
                .font(.headline)

            Text("Scan this from Android to save the Mac identity, host, port, and pairing token.")
                .foregroundStyle(.secondary)

            HStack(alignment: .top, spacing: 16) {
                if let image = pairingQRCodeImage {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 180, height: 180)
                        .padding(12)
                        .background(.white, in: RoundedRectangle(cornerRadius: 10))
                } else {
                    ContentUnavailableView("QR unavailable", systemImage: "qrcode")
                        .frame(width: 204, height: 204)
                }

                VStack(alignment: .leading, spacing: 8) {
                    LabeledContent("Name") {
                        Text(ClipboardReceiver.serviceName)
                    }

                    LabeledContent("Host") {
                        Text(host)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                    }

                    LabeledContent("Port") {
                        Text("\(ClipboardReceiver.port)")
                            .font(.system(.body, design: .monospaced))
                    }

                    LabeledContent("Device ID") {
                        Text(deviceId)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                            .lineLimit(2)
                    }

                    Button("Refresh Host") {
                        host = LocalNetworkHost.currentPairingHost()
                    }
                }
            }
        }
    }

    private var pairingQRCodeImage: NSImage? {
        guard
            !token.isEmpty,
            let payloadData = pairingPayloadData,
            let outputImage = makeQRCodeImage(from: payloadData)
        else {
            return nil
        }

        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let representation = NSCIImageRep(ciImage: scaledImage)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }

    private var pairingPayloadData: Data? {
        let payload: [String: Any] = [
            "name": ClipboardReceiver.serviceName,
            "deviceId": deviceId,
            "host": host,
            "port": Int(ClipboardReceiver.port),
            "token": token
        ]

        return try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
    }

    private func makeQRCodeImage(from data: Data) -> CIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        return filter.outputImage
    }
}
