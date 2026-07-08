import AVFoundation
import AppKit
import CoreMedia
import Network
import QuartzCore

@MainActor
final class ScreenMirrorWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var videoView: ScreenMirrorVideoView?
    private var isClosingProgrammatically = false
    private var userClosedWindow = false

    var onUserClose: (() -> Void)?

    func connectionDidStart() {
        userClosedWindow = false
    }

    func showIfNeeded(width: Int, height: Int) {
        guard !userClosedWindow else { return }
        let isNewWindow = window == nil
        let mirrorWindow = window ?? makeWindow(width: width, height: height)
        window = mirrorWindow
        videoView = mirrorWindow.contentView as? ScreenMirrorVideoView
        resizeWindowIfNeeded(width: width, height: height)
        if isNewWindow {
            setDockIconVisible(true)
            mirrorWindow.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        } else if !mirrorWindow.isVisible {
            mirrorWindow.orderFrontRegardless()
        }
    }

    func updateFormat(_ format: CMVideoFormatDescription, width: Int, height: Int) {
        showIfNeeded(width: width, height: height)
        videoView?.updateFormat(format)
        resizeWindowIfNeeded(width: width, height: height)
    }

    func enqueue(_ sampleBuffer: CMSampleBuffer) {
        videoView?.enqueue(sampleBuffer)
    }

    func connectionDidClose() {
        videoView?.flushAndRemoveImage()
        guard let mirrorWindow = window else { return }
        isClosingProgrammatically = true
        mirrorWindow.close()
        isClosingProgrammatically = false
    }

    func windowWillClose(_ notification: Notification) {
        videoView?.flushAndRemoveImage()
        window = nil
        videoView = nil
        setDockIconVisible(false)
        guard !isClosingProgrammatically else { return }
        userClosedWindow = true
        onUserClose?()
    }

    private func setDockIconVisible(_ isVisible: Bool) {
        NSApp.setActivationPolicy(isVisible ? .regular : .accessory)
    }

    private func makeWindow(width: Int, height: Int) -> NSWindow {
        let contentSize = Self.windowSize(width: width, height: height)
        let view = ScreenMirrorVideoView(frame: NSRect(origin: .zero, size: contentSize))
        let mirrorWindow = NSWindow(
            contentRect: NSRect(origin: .zero, size: contentSize),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        mirrorWindow.title = "ZevLink Screen"
        mirrorWindow.contentView = view
        mirrorWindow.isReleasedWhenClosed = false
        mirrorWindow.delegate = self
        mirrorWindow.center()
        return mirrorWindow
    }

    private func resizeWindowIfNeeded(width: Int, height: Int) {
        guard let window else { return }
        let targetSize = Self.windowSize(width: width, height: height)
        let currentSize = window.contentLayoutRect.size
        guard abs(currentSize.width - targetSize.width) > 2 || abs(currentSize.height - targetSize.height) > 2 else {
            return
        }
        window.setContentSize(targetSize)
        window.center()
    }

    private static func windowSize(width: Int, height: Int) -> NSSize {
        guard width > 0, height > 0 else {
            return NSSize(width: 420, height: 760)
        }
        let visibleFrame = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let maxWidth = visibleFrame.width * 0.82
        let maxHeight = visibleFrame.height * 0.86
        let scale = min(maxWidth / CGFloat(width), maxHeight / CGFloat(height), 1)
        return NSSize(
            width: max(320, CGFloat(width) * scale),
            height: max(240, CGFloat(height) * scale)
        )
    }
}

final class ScreenMirrorVideoView: NSView {
    private let displayLayer = AVSampleBufferDisplayLayer()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        let rootLayer = CALayer()
        rootLayer.backgroundColor = NSColor.black.cgColor
        layer = rootLayer
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = NSColor.black.cgColor
        rootLayer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layout() {
        super.layout()
        displayLayer.frame = bounds
    }

    func updateFormat(_ format: CMVideoFormatDescription) {
        if displayLayer.status == .failed {
            displayLayer.flushAndRemoveImage()
        } else {
            displayLayer.flush()
        }
    }

    func enqueue(_ sampleBuffer: CMSampleBuffer) {
        if displayLayer.status == .failed {
            displayLayer.flushAndRemoveImage()
        }
        displayLayer.enqueue(sampleBuffer)
    }

    func flush() {
        displayLayer.flush()
    }

    func flushAndRemoveImage() {
        displayLayer.flushAndRemoveImage()
    }
}

final class ScreenMirrorAudioPlayer {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let lock = NSLock()
    private var format: AVAudioFormat?
    private var queuedFrames: AVAudioFramePosition = 0
    private var isPrepared = false
    private var isPlayerAttached = false

    func configure(sampleRate: Int, channels: Int) {
        guard sampleRate > 0, channels > 0 else { return }
        let nextFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(sampleRate),
            channels: AVAudioChannelCount(channels),
            interleaved: true
        )
        guard let nextFormat else { return }

        lock.lock()
        if let format, format.sampleRate == nextFormat.sampleRate, format.channelCount == nextFormat.channelCount {
            lock.unlock()
            return
        }
        let shouldDetachPlayer = isPlayerAttached
        format = nextFormat
        queuedFrames = 0
        isPrepared = false
        lock.unlock()

        player.stop()
        if shouldDetachPlayer {
            engine.detach(player)
        }
        engine.stop()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: nextFormat)

        lock.lock()
        isPlayerAttached = true
        queuedFrames = 0
        isPrepared = true
        lock.unlock()
    }

    func enqueuePcm(_ data: Data, sampleRate: Int, channels: Int) {
        configure(sampleRate: sampleRate, channels: channels)

        lock.lock()
        guard let format, isPrepared else {
            lock.unlock()
            return
        }
        let shouldResetQueue = queuedFrames > AVAudioFramePosition(sampleRate / 2)
        if shouldResetQueue {
            queuedFrames = 0
        }
        lock.unlock()

        if shouldResetQueue {
            player.stop()
        }
        if !engine.isRunning {
            do {
                try engine.start()
            } catch {
                NSLog("ZevLink screen audio engine failed: \(error.localizedDescription)")
                return
            }
        }
        if !player.isPlaying {
            player.play()
        }
        let bytesPerFrame = max(1, channels * MemoryLayout<Int16>.size)
        let frameCount = data.count / bytesPerFrame
        guard frameCount > 0, let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(frameCount)
        ) else {
            return
        }
        buffer.frameLength = AVAudioFrameCount(frameCount)
        let destination = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList)
        guard let baseAddress = destination.first?.mData else {
            return
        }
        data.withUnsafeBytes { source in
            if let sourceAddress = source.baseAddress {
                memcpy(baseAddress, sourceAddress, min(data.count, Int(destination.first?.mDataByteSize ?? 0)))
            }
        }
        lock.lock()
        queuedFrames += AVAudioFramePosition(frameCount)
        lock.unlock()
        player.scheduleBuffer(buffer) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.queuedFrames = max(0, self.queuedFrames - AVAudioFramePosition(frameCount))
            self.lock.unlock()
        }
    }

    func stop() {
        lock.lock()
        queuedFrames = 0
        let shouldStop = isPrepared
        lock.unlock()
        guard shouldStop else { return }
        player.stop()
        engine.stop()
    }
}

final class ScreenMirrorReceiver {
    static let port: UInt16 = 9877

    private let queue = DispatchQueue(label: "com.zevlink.screen-mirror.receiver", qos: .userInteractive)
    private let windowController: ScreenMirrorWindowController
    private let audioPlayer = ScreenMirrorAudioPlayer()
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: ScreenMirrorConnection] = [:]

    init(windowController: ScreenMirrorWindowController) {
        self.windowController = windowController
    }

    func start() {
        queue.async { [weak self] in
            self?.startOnQueue()
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.listener?.cancel()
            self.listener = nil
            let activeConnections = Array(self.connections.values)
            self.connections.removeAll()
            activeConnections.forEach { $0.cancel() }
            self.audioPlayer.stop()
            Task { @MainActor in
                self.windowController.connectionDidClose()
            }
        }
    }

    func stopActiveStreamFromWindowClose() {
        queue.async { [weak self] in
            guard let self else { return }
            let activeConnections = Array(self.connections.values)
            self.connections.removeAll()
            activeConnections.forEach { $0.cancel() }
            self.audioPlayer.stop()
        }
    }

    private func startOnQueue() {
        guard listener == nil else { return }
        guard let port = NWEndpoint.Port(rawValue: Self.port) else { return }
        do {
            let newListener = try NWListener(using: .tcp, on: port)
            listener = newListener
            newListener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            newListener.stateUpdateHandler = { state in
                if case .failed(let error) = state {
                    NSLog("ZevLink screen mirror receiver failed: \(error.localizedDescription)")
                }
            }
            newListener.start(queue: queue)
        } catch {
            NSLog("Could not start ZevLink screen mirror receiver: \(error.localizedDescription)")
        }
    }

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        let oldConnections = Array(connections.values)
        connections.removeAll()
        Task { @MainActor in
            self.windowController.connectionDidStart()
        }
        let mirrorConnection = ScreenMirrorConnection(
            connection: connection,
            queue: queue,
            windowController: windowController,
            audioPlayer: audioPlayer,
            onClose: { [weak self] in
                self?.connectionDidClose(id)
            }
        )
        connections[id] = mirrorConnection
        mirrorConnection.start()
        oldConnections.forEach { $0.cancel() }
        audioPlayer.stop()
    }

    private func connectionDidClose(_ id: ObjectIdentifier) {
        guard connections[id] != nil else { return }
        connections[id] = nil
        audioPlayer.stop()
        Task { @MainActor in
            self.windowController.connectionDidClose()
        }
    }
}

private final class ScreenMirrorConnection {
    private static let headerLength = 28
    private static let maximumPayloadLength = 64 * 1024 * 1024
    private static let magic: UInt32 = 0x5A56534D
    private static let typeCodecConfig: UInt8 = 1
    private static let typeFrame: UInt8 = 2
    private static let typeVideoSize: UInt8 = 3
    private static let typeAudioConfig: UInt8 = 4
    private static let typeAudioPcm: UInt8 = 5

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let windowController: ScreenMirrorWindowController
    private let audioPlayer: ScreenMirrorAudioPlayer
    private let onClose: () -> Void
    private var buffer = Data()
    private var formatDescription: CMVideoFormatDescription?
    private var didClose = false

    init(
        connection: NWConnection,
        queue: DispatchQueue,
        windowController: ScreenMirrorWindowController,
        audioPlayer: ScreenMirrorAudioPlayer,
        onClose: @escaping () -> Void
    ) {
        self.connection = connection
        self.queue = queue
        self.windowController = windowController
        self.audioPlayer = audioPlayer
        self.onClose = onClose
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.receiveMore()
            case .failed, .cancelled:
                self?.finish()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func cancel() {
        connection.cancel()
        finish()
    }

    private func receiveMore() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.parseAvailablePackets()
            }
            if isComplete || error != nil {
                self.finish()
                return
            }
            self.receiveMore()
        }
    }

    private func parseAvailablePackets() {
        while buffer.count >= Self.headerLength {
            guard buffer.uint32BE(at: 0) == Self.magic else {
                finish()
                return
            }
            let type = buffer[4]
            let flags = buffer[5]
            let width = Int(buffer.uint32BE(at: 8))
            let height = Int(buffer.uint32BE(at: 12))
            let ptsUs = Int64(bitPattern: UInt64(buffer.uint64BE(at: 16)))
            let payloadLength = Int(buffer.uint32BE(at: 24))
            guard payloadLength >= 0 && payloadLength <= Self.maximumPayloadLength else {
                finish()
                return
            }
            let packetLength = Self.headerLength + payloadLength
            guard buffer.count >= packetLength else { return }

            let payload = buffer.subdata(in: Self.headerLength..<packetLength)
            buffer.removeSubrange(0..<packetLength)
            handlePacket(type: type, flags: flags, width: width, height: height, ptsUs: ptsUs, payload: payload)
        }
    }

    private func handlePacket(type: UInt8, flags: UInt8, width: Int, height: Int, ptsUs: Int64, payload: Data) {
        switch type {
        case Self.typeCodecConfig:
            handleCodecConfig(payload, width: width, height: height)
        case Self.typeFrame:
            handleFrame(payload, flags: flags, ptsUs: ptsUs)
        case Self.typeVideoSize:
            Task { @MainActor in
                windowController.showIfNeeded(width: width, height: height)
            }
        case Self.typeAudioConfig:
            audioPlayer.configure(sampleRate: width, channels: height)
        case Self.typeAudioPcm:
            audioPlayer.enqueuePcm(payload, sampleRate: width, channels: height)
        default:
            break
        }
    }

    private func handleCodecConfig(_ avcc: Data, width: Int, height: Int) {
        guard let parameterSets = H264AvcCParameterSets(avcc: avcc) else { return }
        let sps = parameterSets.parameterSets[0]
        let pps = parameterSets.parameterSets[1]
        sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                guard
                    let spsBase = spsBytes.bindMemory(to: UInt8.self).baseAddress,
                    let ppsBase = ppsBytes.bindMemory(to: UInt8.self).baseAddress
                else {
                    return
                }
                let pointers: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                let sizes = [sps.count, pps.count]
                var nextDescription: CMVideoFormatDescription?
                let status = pointers.withUnsafeBufferPointer { pointerBuffer in
                    sizes.withUnsafeBufferPointer { sizeBuffer in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: pointerBuffer.count,
                            parameterSetPointers: pointerBuffer.baseAddress!,
                            parameterSetSizes: sizeBuffer.baseAddress!,
                            nalUnitHeaderLength: Int32(parameterSets.nalLengthSize),
                            formatDescriptionOut: &nextDescription
                        )
                    }
                }
                guard status == noErr, let nextDescription else { return }
                formatDescription = nextDescription
                Task { @MainActor in
                    windowController.updateFormat(nextDescription, width: width, height: height)
                }
            }
        }
    }

    private func handleFrame(_ sampleData: Data, flags: UInt8, ptsUs: Int64) {
        guard let formatDescription else { return }
        var blockBuffer: CMBlockBuffer?
        let blockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: sampleData.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: sampleData.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard blockStatus == noErr, let blockBuffer else { return }
        let replaceStatus = sampleData.withUnsafeBytes { bytes in
            CMBlockBufferReplaceDataBytes(
                with: bytes.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: sampleData.count
            )
        }
        guard replaceStatus == noErr else { return }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 60),
            presentationTimeStamp: CMTime(value: ptsUs, timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = sampleData.count
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else { return }
        markDisplayImmediately(sampleBuffer, isKeyFrame: flags & 0x01 != 0)
        Task { @MainActor in
            windowController.enqueue(sampleBuffer)
        }
    }

    private func markDisplayImmediately(_ sampleBuffer: CMSampleBuffer, isKeyFrame: Bool) {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) else {
            return
        }
        let dictionary = unsafeBitCast(
            CFArrayGetValueAtIndex(attachments, 0),
            to: CFMutableDictionary.self
        )
        CFDictionarySetValue(
            dictionary,
            Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
            Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
        )
        if isKeyFrame {
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanFalse).toOpaque()
            )
        }
    }

    private func finish() {
        guard !didClose else { return }
        didClose = true
        connection.cancel()
        onClose()
    }
}

private struct H264AvcCParameterSets {
    let parameterSets: [Data]
    let nalLengthSize: Int

    init?(avcc: Data) {
        guard avcc.count >= 7, avcc[0] == 1 else { return nil }
        nalLengthSize = Int(avcc[4] & 0x03) + 1
        let spsCount = Int(avcc[5] & 0x1F)
        var offset = 6
        var sets: [Data] = []
        for _ in 0..<spsCount {
            guard let set = avcc.readLengthPrefixedBytes(offset: &offset) else { return nil }
            sets.append(set)
        }
        guard offset < avcc.count else { return nil }
        let ppsCount = Int(avcc[offset])
        offset += 1
        for _ in 0..<ppsCount {
            guard let set = avcc.readLengthPrefixedBytes(offset: &offset) else { return nil }
            sets.append(set)
        }
        guard sets.count >= 2 else { return nil }
        parameterSets = sets
    }
}

private extension Data {
    func uint32BE(at offset: Int) -> UInt32 {
        let range = offset..<(offset + 4)
        return self[range].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    func uint64BE(at offset: Int) -> UInt64 {
        let range = offset..<(offset + 8)
        return self[range].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    func readLengthPrefixedBytes(offset: inout Int) -> Data? {
        guard offset + 2 <= count else { return nil }
        let length = Int(self[offset]) << 8 | Int(self[offset + 1])
        offset += 2
        guard offset + length <= count else { return nil }
        defer { offset += length }
        return subdata(in: offset..<(offset + length))
    }
}
