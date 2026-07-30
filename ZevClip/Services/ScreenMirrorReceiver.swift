import AVFoundation
import AppKit
import CoreMedia
import Network
import QuartzCore

@MainActor
final class ScreenMirrorWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var videoView: ScreenMirrorVideoView?
    private var controlPanel: ScreenMirrorControlPanel?
    private var isClosingProgrammatically = false
    private var userClosedWindow = false

    var onUserClose: (() -> Void)?
    var onControlCommand: ((Data) -> Void)? {
        didSet {
            videoView?.onControlCommand = onControlCommand
            controlPanel?.onControlCommand = onControlCommand
        }
    }

    func connectionDidStart() {
        userClosedWindow = false
    }

    func showIfNeeded(width: Int, height: Int) {
        guard !userClosedWindow else { return }
        let isNewWindow = window == nil
        let mirrorWindow = window ?? makeWindow(width: width, height: height)
        window = mirrorWindow
        videoView = mirrorWindow.contentView as? ScreenMirrorVideoView
        videoView?.onControlCommand = onControlCommand
        videoView?.onPointerActivity = { [weak self] in
            self?.handlePointerActivity()
        }
        videoView?.updateVideoSize(width: width, height: height)
        resizeWindowIfNeeded(width: width, height: height)
        if isNewWindow {
            setDockIconVisible(true)
            mirrorWindow.makeKeyAndOrderFront(nil)
            mirrorWindow.makeFirstResponder(videoView)
            NSApp.activate(ignoringOtherApps: true)
        } else if !mirrorWindow.isVisible {
            mirrorWindow.orderFrontRegardless()
        }
        showControls(attachedTo: mirrorWindow)
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
        controlPanel?.close()
        controlPanel = nil
        videoView?.flushAndRemoveImage()
        videoView?.onPointerActivity = nil
        window = nil
        videoView = nil
        onControlCommand = nil
        setDockIconVisible(false)
        guard !isClosingProgrammatically else { return }
        userClosedWindow = true
        onUserClose?()
    }

    func windowDidMove(_ notification: Notification) {
        repositionControls()
    }

    func windowDidResize(_ notification: Notification) {
        videoView?.resizeVideoLayerToCurrentBounds()
        repositionControls()
    }

    func windowDidEnterFullScreen(_ notification: Notification) {
        repositionControls()
        controlPanel?.scheduleAutoHideIfNeeded()
    }

    func windowDidExitFullScreen(_ notification: Notification) {
        controlPanel?.showPinned()
        repositionControls()
    }

    func windowDidMiniaturize(_ notification: Notification) {
        controlPanel?.orderOut()
    }

    func windowDidDeminiaturize(_ notification: Notification) {
        guard let window else { return }
        showControls(attachedTo: window)
    }

    private func setDockIconVisible(_ isVisible: Bool) {
        NSApp.setActivationPolicy(isVisible ? .regular : .accessory)
    }

    private func makeWindow(width: Int, height: Int) -> NSWindow {
        let contentSize = Self.windowSize(width: width, height: height)
        let view = ScreenMirrorVideoView(frame: NSRect(origin: .zero, size: contentSize))
        view.onControlCommand = onControlCommand
        view.updateVideoSize(width: width, height: height)
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
        mirrorWindow.acceptsMouseMovedEvents = true
        mirrorWindow.center()
        return mirrorWindow
    }

    private func showControls(attachedTo mirrorWindow: NSWindow) {
        let panel = controlPanel ?? ScreenMirrorControlPanel()
        controlPanel = panel
        panel.onControlCommand = onControlCommand
        panel.show(attachedTo: mirrorWindow)
    }

    private func handlePointerActivity() {
        guard let window else { return }
        controlPanel?.reveal(attachedTo: window)
    }

    private func repositionControls() {
        guard let window else { return }
        controlPanel?.position(attachedTo: window)
    }

    private func resizeWindowIfNeeded(width: Int, height: Int) {
        guard let window else { return }
        guard !window.styleMask.contains(.fullScreen) else {
            videoView?.resizeVideoLayerToCurrentBounds()
            repositionControls()
            return
        }
        let targetSize = Self.windowSize(width: width, height: height)
        let currentSize = window.contentLayoutRect.size
        guard abs(currentSize.width - targetSize.width) > 2 || abs(currentSize.height - targetSize.height) > 2 else {
            return
        }
        window.setContentSize(targetSize)
        window.center()
        repositionControls()
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

private final class ScreenMirrorControlPanel: NSObject {
    private static let panelSize = NSSize(width: 208, height: 48)
    private static let windowGap: CGFloat = 10
    private static let screenEdgeInset: CGFloat = 16
    private static let fullScreenBottomInset: CGFloat = 28
    private static let autoHideDelay: TimeInterval = 5
    private static let fadeDuration: TimeInterval = 0.18
    private static let savedFullScreenPanelXKey = "screenMirror.fullScreenControlPanel.originX"
    private static let savedFullScreenPanelYKey = "screenMirror.fullScreenControlPanel.originY"
    private let backButton = ScreenMirrorControlButton(symbolName: "chevron.backward", tooltip: "Back")
    private let homeButton = ScreenMirrorControlButton(symbolName: "circle", tooltip: "Home")
    private let recentsButton = ScreenMirrorControlButton(symbolName: "rectangle.on.rectangle", tooltip: "Recents")
    private let panel: NSPanel
    private weak var parentWindow: NSWindow?
    private var dragOriginInScreen: NSPoint?
    private var dragPanelOrigin: NSPoint?
    private var userPlacedFullScreenOriginFraction: CGPoint?
    private var autoHideWorkItem: DispatchWorkItem?

    var onControlCommand: ((Data) -> Void)?

    override init() {
        panel = NSPanel(
            contentRect: NSRect(origin: .zero, size: Self.panelSize),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        super.init()
        userPlacedFullScreenOriginFraction = Self.loadSavedFullScreenOriginFraction()
        configurePanel()
    }

    func show(attachedTo mirrorWindow: NSWindow) {
        if parentWindow !== mirrorWindow {
            parentWindow?.removeChildWindow(panel)
            mirrorWindow.addChildWindow(panel, ordered: .above)
            parentWindow = mirrorWindow
        }
        position(attachedTo: mirrorWindow)
        revealPanel(animated: false)
        scheduleAutoHideIfNeeded()
    }

    func position(attachedTo mirrorWindow: NSWindow) {
        if mirrorWindow.styleMask.contains(.fullScreen) {
            let targetOrigin = userPlacedFullScreenOriginFraction.map {
                fullScreenOrigin(for: $0, attachedTo: mirrorWindow)
            } ?? defaultFullScreenOrigin(attachedTo: mirrorWindow)
            setPanelOrigin(targetOrigin, attachedTo: mirrorWindow, remembersUserPlacement: false)
            return
        }

        let parentFrame = mirrorWindow.frame
        let visibleFrame = mirrorWindow.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? parentFrame
        let preferredX = parentFrame.midX - Self.panelSize.width / 2
        let minX = visibleFrame.minX + 8
        let maxX = visibleFrame.maxX - Self.panelSize.width - 8
        let x = min(max(preferredX, minX), maxX)
        var y = parentFrame.minY - Self.panelSize.height - Self.windowGap
        if y < visibleFrame.minY + 8 {
            y = parentFrame.maxY + Self.windowGap
        }
        if y + Self.panelSize.height > visibleFrame.maxY - 8 {
            y = max(visibleFrame.minY + 8, visibleFrame.maxY - Self.panelSize.height - 8)
        }
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }

    func reveal(attachedTo mirrorWindow: NSWindow) {
        position(attachedTo: mirrorWindow)
        revealPanel(animated: true)
        scheduleAutoHideIfNeeded()
    }

    func scheduleAutoHideIfNeeded() {
        autoHideWorkItem?.cancel()
        guard parentWindow?.styleMask.contains(.fullScreen) == true else { return }
        let workItem = DispatchWorkItem { [weak self] in
            self?.hidePanel(animated: true)
        }
        autoHideWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.autoHideDelay, execute: workItem)
    }

    func showPinned() {
        autoHideWorkItem?.cancel()
        autoHideWorkItem = nil
        revealPanel(animated: false)
    }

    func orderOut() {
        hidePanel(animated: false)
    }

    func close() {
        autoHideWorkItem?.cancel()
        autoHideWorkItem = nil
        parentWindow?.removeChildWindow(panel)
        parentWindow = nil
        hidePanel(animated: false)
    }

    private func configurePanel() {
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.transient, .ignoresCycle]
        panel.level = .floating
        panel.alphaValue = 0

        let root = DraggableControlPanelView(frame: NSRect(origin: .zero, size: Self.panelSize))
        root.onDragBegan = { [weak self] screenPoint in
            self?.beginDrag(at: screenPoint)
        }
        root.onDragged = { [weak self] screenPoint in
            self?.drag(to: screenPoint)
        }
        root.onDragEnded = { [weak self] in
            self?.finishDrag()
        }
        root.onPointerActivity = { [weak self] in
            self?.scheduleAutoHideIfNeeded()
        }
        root.material = .hudWindow
        root.blendingMode = .behindWindow
        root.state = .active
        root.wantsLayer = true
        root.layer?.cornerRadius = 22
        root.layer?.masksToBounds = true
        root.layer?.borderColor = NSColor.white.withAlphaComponent(0.16).cgColor
        root.layer?.borderWidth = 0.5
        panel.contentView = root

        [
            (backButton, "back"),
            (homeButton, "home"),
            (recentsButton, "recents")
        ].forEach { button, action in
            button.target = self
            button.action = #selector(navigationButtonPressed(_:))
            button.identifier = NSUserInterfaceItemIdentifier(action)
            root.addSubview(button)
        }
        layoutButtons()
    }

    private func layoutButtons() {
        let buttonSize = NSSize(width: 46, height: 34)
        let spacing: CGFloat = 18
        let totalWidth = buttonSize.width * 3 + spacing * 2
        let startX = (Self.panelSize.width - totalWidth) / 2
        let y = (Self.panelSize.height - buttonSize.height) / 2
        [backButton, homeButton, recentsButton].enumerated().forEach { index, button in
            button.frame = NSRect(
                x: startX + CGFloat(index) * (buttonSize.width + spacing),
                y: y,
                width: buttonSize.width,
                height: buttonSize.height
            )
        }
    }

    @objc private func navigationButtonPressed(_ sender: NSButton) {
        guard let action = sender.identifier?.rawValue else { return }
        scheduleAutoHideIfNeeded()
        sendControl(["type": "nav", "action": action])
        parentWindow?.makeFirstResponder(parentWindow?.contentView as? ScreenMirrorVideoView)
    }

    private func beginDrag(at screenPoint: NSPoint) {
        guard parentWindow?.styleMask.contains(.fullScreen) == true else { return }
        autoHideWorkItem?.cancel()
        dragOriginInScreen = screenPoint
        dragPanelOrigin = panel.frame.origin
    }

    private func drag(to screenPoint: NSPoint) {
        guard
            let parentWindow,
            parentWindow.styleMask.contains(.fullScreen),
            let dragOriginInScreen,
            let dragPanelOrigin
        else {
            return
        }
        let origin = NSPoint(
            x: dragPanelOrigin.x + screenPoint.x - dragOriginInScreen.x,
            y: dragPanelOrigin.y + screenPoint.y - dragOriginInScreen.y
        )
        setPanelOrigin(origin, attachedTo: parentWindow, remembersUserPlacement: true)
    }

    private func finishDrag() {
        dragOriginInScreen = nil
        dragPanelOrigin = nil
        scheduleAutoHideIfNeeded()
        parentWindow?.makeFirstResponder(parentWindow?.contentView as? ScreenMirrorVideoView)
    }

    private func defaultFullScreenOrigin(attachedTo mirrorWindow: NSWindow) -> NSPoint {
        let frame = mirrorWindow.frame
        return NSPoint(
            x: frame.midX - Self.panelSize.width / 2,
            y: frame.minY + Self.fullScreenBottomInset
        )
    }

    private func setPanelOrigin(
        _ origin: NSPoint,
        attachedTo mirrorWindow: NSWindow,
        remembersUserPlacement: Bool
    ) {
        let frame = mirrorWindow.frame
        let x = min(
            max(origin.x, frame.minX + Self.screenEdgeInset),
            frame.maxX - Self.panelSize.width - Self.screenEdgeInset
        )
        let y = min(
            max(origin.y, frame.minY + Self.screenEdgeInset),
            frame.maxY - Self.panelSize.height - Self.screenEdgeInset
        )
        let clampedOrigin = NSPoint(x: x, y: y)
        panel.setFrameOrigin(clampedOrigin)
        if remembersUserPlacement {
            userPlacedFullScreenOriginFraction = fullScreenFraction(
                for: clampedOrigin,
                attachedTo: mirrorWindow
            )
            saveFullScreenOriginFraction(userPlacedFullScreenOriginFraction)
        }
    }

    private func revealPanel(animated: Bool) {
        guard panel.alphaValue < 1 || !panel.isVisible else { return }
        panel.orderFront(nil)
        if animated {
            NSAnimationContext.runAnimationGroup { context in
                context.duration = Self.fadeDuration
                context.timingFunction = CAMediaTimingFunction(name: .easeOut)
                panel.animator().alphaValue = 1
            }
        } else {
            panel.alphaValue = 1
        }
    }

    private func hidePanel(animated: Bool) {
        autoHideWorkItem?.cancel()
        autoHideWorkItem = nil
        guard panel.isVisible else { return }
        guard animated else {
            panel.alphaValue = 0
            panel.orderOut(nil)
            return
        }
        NSAnimationContext.runAnimationGroup { context in
            context.duration = Self.fadeDuration
            context.timingFunction = CAMediaTimingFunction(name: .easeIn)
            panel.animator().alphaValue = 0
        } completionHandler: { [weak panel] in
            guard let panel, panel.alphaValue <= 0.01 else { return }
            panel.orderOut(nil)
        }
    }

    private func fullScreenOrigin(for fraction: CGPoint, attachedTo mirrorWindow: NSWindow) -> NSPoint {
        let frame = mirrorWindow.frame
        let availableWidth = max(1, frame.width - Self.panelSize.width - Self.screenEdgeInset * 2)
        let availableHeight = max(1, frame.height - Self.panelSize.height - Self.screenEdgeInset * 2)
        return NSPoint(
            x: frame.minX + Self.screenEdgeInset + min(max(fraction.x, 0), 1) * availableWidth,
            y: frame.minY + Self.screenEdgeInset + min(max(fraction.y, 0), 1) * availableHeight
        )
    }

    private func fullScreenFraction(for origin: NSPoint, attachedTo mirrorWindow: NSWindow) -> CGPoint {
        let frame = mirrorWindow.frame
        let availableWidth = max(1, frame.width - Self.panelSize.width - Self.screenEdgeInset * 2)
        let availableHeight = max(1, frame.height - Self.panelSize.height - Self.screenEdgeInset * 2)
        return CGPoint(
            x: min(max((origin.x - frame.minX - Self.screenEdgeInset) / availableWidth, 0), 1),
            y: min(max((origin.y - frame.minY - Self.screenEdgeInset) / availableHeight, 0), 1)
        )
    }

    private static func loadSavedFullScreenOriginFraction() -> CGPoint? {
        let defaults = UserDefaults.standard
        guard
            defaults.object(forKey: savedFullScreenPanelXKey) != nil,
            defaults.object(forKey: savedFullScreenPanelYKey) != nil
        else {
            return nil
        }
        let x = defaults.double(forKey: savedFullScreenPanelXKey)
        let y = defaults.double(forKey: savedFullScreenPanelYKey)
        guard x.isFinite, y.isFinite else { return nil }
        return CGPoint(
            x: min(max(CGFloat(x), 0), 1),
            y: min(max(CGFloat(y), 0), 1)
        )
    }

    private func saveFullScreenOriginFraction(_ fraction: CGPoint?) {
        guard let fraction else { return }
        let defaults = UserDefaults.standard
        defaults.set(Double(fraction.x), forKey: Self.savedFullScreenPanelXKey)
        defaults.set(Double(fraction.y), forKey: Self.savedFullScreenPanelYKey)
    }

    private func sendControl(_ payload: [String: Any]) {
        guard
            JSONSerialization.isValidJSONObject(payload),
            let data = try? JSONSerialization.data(withJSONObject: payload)
        else {
            return
        }
        onControlCommand?(data)
    }
}

private final class DraggableControlPanelView: NSVisualEffectView {
    var onDragBegan: ((NSPoint) -> Void)?
    var onDragged: ((NSPoint) -> Void)?
    var onDragEnded: (() -> Void)?
    var onPointerActivity: (() -> Void)?

    override func mouseDown(with event: NSEvent) {
        onPointerActivity?()
        onDragBegan?(NSEvent.mouseLocation)
    }

    override func mouseDragged(with event: NSEvent) {
        onPointerActivity?()
        onDragged?(NSEvent.mouseLocation)
    }

    override func mouseUp(with event: NSEvent) {
        onPointerActivity?()
        onDragEnded?()
    }

    override func mouseMoved(with event: NSEvent) {
        onPointerActivity?()
        super.mouseMoved(with: event)
    }
}

final class ScreenMirrorVideoView: NSView {
    private let displayLayer = AVSampleBufferDisplayLayer()
    private var videoSize = NSSize(width: 1, height: 1)
    private var pointerTrackingArea: NSTrackingArea?
    private var touchIsActive = false
    private var lastTouchPoint: CGPoint?
    private var lastTouchSentAt: TimeInterval = 0
    private var scrollTouchIsActive = false
    private var scrollTouchPoint: CGPoint?
    private var pendingScrollTouchDelta = CGSize.zero
    private var scrollTouchEndWorkItem: DispatchWorkItem?
    private var zoomIsActive = false
    private var pendingZoomMagnification: CGFloat = 0
    private var pendingZoomCenter: CGPoint?
    private var lastZoomMoveSentAt: TimeInterval = 0

    var onControlCommand: ((Data) -> Void)?
    var onPointerActivity: (() -> Void)?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        let rootLayer = CALayer()
        rootLayer.backgroundColor = NSColor.black.cgColor
        rootLayer.contentsScale = NSScreen.main?.backingScaleFactor ?? 2
        layer = rootLayer
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = NSColor.black.cgColor
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        displayLayer.contentsScale = rootLayer.contentsScale
        displayLayer.frame = bounds
        rootLayer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var acceptsFirstResponder: Bool { true }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let pointerTrackingArea {
            removeTrackingArea(pointerTrackingArea)
        }
        let trackingArea = NSTrackingArea(
            rect: bounds,
            options: [.activeInKeyWindow, .mouseMoved, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
        pointerTrackingArea = trackingArea
    }

    override func layout() {
        super.layout()
        resizeVideoLayerToCurrentBounds()
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        resizeVideoLayerToCurrentBounds()
    }

    override func setBoundsSize(_ newSize: NSSize) {
        super.setBoundsSize(newSize)
        resizeVideoLayerToCurrentBounds()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        resizeVideoLayerToCurrentBounds()
    }

    func resizeVideoLayerToCurrentBounds() {
        guard bounds.width > 0, bounds.height > 0 else { return }
        let backingScale = window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer?.contentsScale = backingScale
        displayLayer.contentsScale = backingScale
        displayLayer.frame = bounds
        CATransaction.commit()
    }

    func updateVideoSize(width: Int, height: Int) {
        guard width > 0, height > 0 else { return }
        videoSize = NSSize(width: width, height: height)
        resizeVideoLayerToCurrentBounds()
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

    override func mouseMoved(with event: NSEvent) {
        onPointerActivity?()
        super.mouseMoved(with: event)
    }

    override func mouseDown(with event: NSEvent) {
        onPointerActivity?()
        finishScrollTouch()
        window?.makeFirstResponder(self)
        guard let point = normalizedPoint(for: event) else {
            touchIsActive = false
            lastTouchPoint = nil
            return
        }
        touchIsActive = true
        lastTouchPoint = point
        lastTouchSentAt = event.timestamp
        sendTouch(action: "down", point: point)
    }

    override func mouseDragged(with event: NSEvent) {
        guard touchIsActive, let point = normalizedPoint(for: event, clamped: true) else { return }
        let elapsed = event.timestamp - lastTouchSentAt
        let movedEnough = lastTouchPoint.map { hypot(point.x - $0.x, point.y - $0.y) >= 0.0015 } ?? true
        guard movedEnough && elapsed >= Self.touchMoveInterval else { return }
        lastTouchPoint = point
        lastTouchSentAt = event.timestamp
        sendTouch(action: "move", point: point)
    }

    override func mouseUp(with event: NSEvent) {
        guard touchIsActive else { return }
        guard let point = normalizedPoint(for: event, clamped: true) ?? lastTouchPoint else {
            cancelTouch()
            return
        }
        sendTouch(action: "up", point: point)
        touchIsActive = false
        lastTouchPoint = nil
    }

    override func rightMouseDown(with event: NSEvent) {
        sendControl(["type": "nav", "action": "back"])
    }

    override func scrollWheel(with event: NSEvent) {
        guard !touchIsActive else { return }
        guard event.momentumPhase == [] else {
            finishScrollTouch()
            return
        }
        guard let cursorPoint = normalizedPoint(for: event) else {
            finishScrollTouch()
            return
        }
        let rawX = event.hasPreciseScrollingDeltas ? event.scrollingDeltaX : event.deltaX * 12
        let rawY = event.hasPreciseScrollingDeltas ? event.scrollingDeltaY : event.deltaY * 12
        if event.phase.intersection([.ended, .cancelled]).isEmpty == false {
            finishScrollTouch()
            pendingScrollTouchDelta = .zero
            return
        }

        let delta: CGVector
        if scrollTouchIsActive {
            delta = scrollTouchDelta(rawX: rawX, rawY: rawY)
        } else {
            if event.phase.contains(.began) {
                pendingScrollTouchDelta = .zero
            }
            pendingScrollTouchDelta.width += rawX
            pendingScrollTouchDelta.height += rawY
            delta = scrollTouchDelta(
                rawX: pendingScrollTouchDelta.width,
                rawY: pendingScrollTouchDelta.height
            )
            guard hypot(delta.dx, delta.dy) >= Self.scrollTouchStartThreshold else {
                return
            }
            beginScrollTouch(near: cursorPoint)
            pendingScrollTouchDelta = .zero
        }
        guard abs(delta.dx) >= Self.minimumScrollTouchDelta || abs(delta.dy) >= Self.minimumScrollTouchDelta else {
            scheduleScrollTouchEnd()
            return
        }

        moveScrollTouch(by: delta, near: cursorPoint)
        scheduleScrollTouchEnd()
    }

    override func magnify(with event: NSEvent) {
        guard !touchIsActive else { return }
        finishScrollTouch()
        guard let center = normalizedPoint(for: event, clamped: true) else {
            finishZoom()
            return
        }

        if event.phase.contains(.began) {
            beginZoom(at: center, timestamp: event.timestamp)
        }

        if !zoomIsActive {
            beginZoom(at: center, timestamp: event.timestamp)
        }

        pendingZoomCenter = center
        pendingZoomMagnification += event.magnification
        if event.phase.intersection([.ended, .cancelled]).isEmpty == false {
            sendPendingZoomMove(force: true, timestamp: event.timestamp)
            finishZoom(at: center)
            return
        }

        sendPendingZoomMove(force: false, timestamp: event.timestamp)
    }

    private func beginZoom(at center: CGPoint, timestamp: TimeInterval) {
        zoomIsActive = true
        pendingZoomMagnification = 0
        pendingZoomCenter = center
        lastZoomMoveSentAt = timestamp
        sendControl([
            "type": "zoom",
            "action": "begin",
            "x": center.x,
            "y": center.y
        ])
    }

    private func sendPendingZoomMove(force: Bool, timestamp: TimeInterval) {
        guard zoomIsActive else { return }
        guard let center = pendingZoomCenter else { return }
        let elapsed = timestamp - lastZoomMoveSentAt
        let readyByDistance = abs(pendingZoomMagnification) >= Self.zoomMagnificationThreshold
        let readyByTime = elapsed >= Self.zoomMoveInterval
        guard force || (readyByDistance && readyByTime) else { return }
        guard abs(pendingZoomMagnification) >= Self.minimumZoomMagnification else { return }

        let magnification = max(-Self.maxZoomMagnificationStep, min(Self.maxZoomMagnificationStep, pendingZoomMagnification))
        pendingZoomMagnification -= magnification
        lastZoomMoveSentAt = timestamp
        sendControl([
            "type": "zoom",
            "action": "move",
            "x": center.x,
            "y": center.y,
            "magnification": magnification
        ])
    }

    private func finishZoom(at center: CGPoint? = nil) {
        guard zoomIsActive else { return }
        let endCenter = center ?? pendingZoomCenter ?? CGPoint(x: 0.5, y: 0.5)
        sendControl([
            "type": "zoom",
            "action": "end",
            "x": endCenter.x,
            "y": endCenter.y
        ])
        zoomIsActive = false
        pendingZoomMagnification = 0
        pendingZoomCenter = nil
        lastZoomMoveSentAt = 0
    }

    private func beginScrollTouch(near cursorPoint: CGPoint) {
        scrollTouchEndWorkItem?.cancel()
        let point = scrollTouchAnchor(near: cursorPoint)
        scrollTouchIsActive = true
        scrollTouchPoint = point
        sendTouch(action: "down", point: point)
    }

    private func moveScrollTouch(by delta: CGVector, near cursorPoint: CGPoint) {
        scrollTouchEndWorkItem?.cancel()
        if !scrollTouchIsActive {
            beginScrollTouch(near: cursorPoint)
        }
        guard let currentPoint = scrollTouchPoint else { return }
        let nextPoint = CGPoint(
            x: max(Self.scrollTouchInset, min(1 - Self.scrollTouchInset, currentPoint.x + delta.dx)),
            y: max(Self.scrollTouchInset, min(1 - Self.scrollTouchInset, currentPoint.y + delta.dy))
        )
        guard hypot(nextPoint.x - currentPoint.x, nextPoint.y - currentPoint.y) >= Self.minimumScrollTouchDelta else {
            return
        }
        scrollTouchPoint = nextPoint
        sendTouch(action: "move", point: nextPoint)
    }

    private func finishScrollTouch() {
        scrollTouchEndWorkItem?.cancel()
        scrollTouchEndWorkItem = nil
        guard scrollTouchIsActive else { return }
        sendTouch(action: "up", point: scrollTouchPoint ?? CGPoint(x: 0.5, y: 0.5))
        scrollTouchIsActive = false
        scrollTouchPoint = nil
        pendingScrollTouchDelta = .zero
    }

    private func scheduleScrollTouchEnd() {
        scrollTouchEndWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            self?.finishScrollTouch()
        }
        scrollTouchEndWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.scrollTouchEndDelay, execute: workItem)
    }

    private func scrollTouchAnchor(near point: CGPoint) -> CGPoint {
        CGPoint(
            x: max(Self.scrollTouchInset, min(1 - Self.scrollTouchInset, point.x)),
            y: max(Self.scrollTouchInset, min(1 - Self.scrollTouchInset, point.y))
        )
    }

    private func scrollTouchDelta(rawX: CGFloat, rawY: CGFloat) -> CGVector {
        CGVector(
            dx: max(-Self.maxHorizontalScrollTouchDelta, min(Self.maxHorizontalScrollTouchDelta, rawX * Self.horizontalScrollTouchScale)),
            dy: max(-Self.maxVerticalScrollTouchDelta, min(Self.maxVerticalScrollTouchDelta, rawY * Self.verticalScrollTouchScale))
        )
    }

    override func keyDown(with event: NSEvent) {
        if event.modifierFlags.contains(.command), sendCommandShortcut(event) {
            return
        }

        switch event.keyCode {
        case 51, 117:
            sendControl(["type": "key", "key": "delete"])
        case 36, 76:
            sendControl(["type": "key", "key": "enter"])
        case 48:
            sendControl(["type": "key", "key": "tab"])
        case 53:
            sendControl(["type": "nav", "action": "back"])
        case 123:
            sendControl(["type": "key", "key": "arrow_left"])
        case 124:
            sendControl(["type": "key", "key": "arrow_right"])
        case 125:
            sendControl(["type": "key", "key": "arrow_down"])
        case 126:
            sendControl(["type": "key", "key": "arrow_up"])
        default:
            guard event.modifierFlags.intersection([.control, .option]).isEmpty else {
                super.keyDown(with: event)
                return
            }
            if let characters = event.characters, !characters.isEmpty {
                sendControl(["type": "text", "text": characters])
            } else {
                super.keyDown(with: event)
            }
        }
    }

    private func sendCommandShortcut(_ event: NSEvent) -> Bool {
        guard let characters = event.charactersIgnoringModifiers?.lowercased(), characters.count == 1 else {
            return false
        }
        let action: String
        switch characters {
        case "a":
            action = "select_all"
        case "c":
            action = "copy"
        case "v":
            action = "paste"
        case "x":
            action = "cut"
        default:
            return false
        }
        sendControl(["type": "shortcut", "action": action])
        return true
    }

    override func mouseExited(with event: NSEvent) {
        guard touchIsActive, let point = normalizedPoint(for: event, clamped: true) else { return }
        lastTouchPoint = point
        sendTouch(action: "move", point: point)
    }

    private func normalizedPoint(for event: NSEvent, clamped: Bool = false) -> CGPoint? {
        let location = convert(event.locationInWindow, from: nil)
        let visibleRect = displayedVideoRect()
        guard visibleRect.contains(location), visibleRect.width > 0, visibleRect.height > 0 else {
            guard clamped, visibleRect.width > 0, visibleRect.height > 0 else {
                return nil
            }
            let clampedLocation = CGPoint(
                x: max(visibleRect.minX, min(visibleRect.maxX, location.x)),
                y: max(visibleRect.minY, min(visibleRect.maxY, location.y))
            )
            return normalizedPoint(for: clampedLocation, in: visibleRect)
        }
        return normalizedPoint(for: location, in: visibleRect)
    }

    private func normalizedPoint(for location: CGPoint, in visibleRect: NSRect) -> CGPoint {
        CGPoint(
            x: max(0, min(1, (location.x - visibleRect.minX) / visibleRect.width)),
            y: max(0, min(1, (visibleRect.maxY - location.y) / visibleRect.height))
        )
    }

    private func cancelTouch() {
        if let point = lastTouchPoint {
            sendTouch(action: "cancel", point: point)
        }
        touchIsActive = false
        lastTouchPoint = nil
    }

    private func sendTouch(action: String, point: CGPoint) {
        sendControl([
            "type": "touch",
            "action": action,
            "x": point.x,
            "y": point.y
        ])
    }

    private static let touchMoveInterval: TimeInterval = 1.0 / 60.0
    private static let scrollTouchEndDelay: TimeInterval = 2.0
    private static let scrollTouchInset: CGFloat = 0.025
    private static let horizontalScrollTouchScale: CGFloat = 0.0032
    private static let verticalScrollTouchScale: CGFloat = 0.0014
    private static let maxHorizontalScrollTouchDelta: CGFloat = 0.075
    private static let maxVerticalScrollTouchDelta: CGFloat = 0.035
    private static let minimumScrollTouchDelta: CGFloat = 0.001
    private static let scrollTouchStartThreshold: CGFloat = 0.004
    private static let zoomMoveInterval: TimeInterval = 1.0 / 60.0
    private static let minimumZoomMagnification: CGFloat = 0.0015
    private static let zoomMagnificationThreshold: CGFloat = 0.012
    private static let maxZoomMagnificationStep: CGFloat = 0.035

    private func displayedVideoRect() -> NSRect {
        guard videoSize.width > 0, videoSize.height > 0, bounds.width > 0, bounds.height > 0 else {
            return bounds
        }
        let scale = min(bounds.width / videoSize.width, bounds.height / videoSize.height)
        let size = NSSize(width: videoSize.width * scale, height: videoSize.height * scale)
        return NSRect(
            x: bounds.midX - size.width / 2,
            y: bounds.midY - size.height / 2,
            width: size.width,
            height: size.height
        )
    }

    private func sendControl(_ payload: [String: Any]) {
        guard
            JSONSerialization.isValidJSONObject(payload),
            let data = try? JSONSerialization.data(withJSONObject: payload)
        else {
            return
        }
        onControlCommand?(data)
    }
}

private final class ScreenMirrorControlButton: NSButton {
    init(symbolName: String, tooltip: String) {
        super.init(frame: .zero)
        image = NSImage(systemSymbolName: symbolName, accessibilityDescription: tooltip)
        title = ""
        toolTip = tooltip
        bezelStyle = .rounded
        isBordered = true
        imagePosition = .imageOnly
    }

    required init?(coder: NSCoder) {
        nil
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
        Task { @MainActor in
            self.windowController.onControlCommand = { [weak mirrorConnection] payload in
                mirrorConnection?.sendControl(payload)
            }
        }
        oldConnections.forEach { $0.cancel() }
        audioPlayer.stop()
    }

    private func connectionDidClose(_ id: ObjectIdentifier) {
        guard connections[id] != nil else { return }
        connections[id] = nil
        audioPlayer.stop()
        Task { @MainActor in
            self.windowController.onControlCommand = nil
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
    private static let typeControl: UInt8 = 6

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

    func sendControl(_ payload: Data) {
        guard !payload.isEmpty && payload.count <= 16 * 1024 else { return }
        let packet = Self.packet(type: Self.typeControl, payload: payload)
        queue.async { [weak self] in
            guard let self, !self.didClose else { return }
            self.connection.send(content: packet, completion: .contentProcessed { error in
                if let error {
                    NSLog("ZevLink screen control send failed: \(error.localizedDescription)")
                }
            })
        }
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

    private static func packet(type: UInt8, payload: Data) -> Data {
        var data = Data(capacity: headerLength + payload.count)
        data.appendUInt32BE(magic)
        data.append(type)
        data.append(0)
        data.appendUInt16BE(0)
        data.appendUInt32BE(0)
        data.appendUInt32BE(0)
        data.appendUInt64BE(0)
        data.appendUInt32BE(UInt32(payload.count))
        data.append(payload)
        return data
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

    mutating func appendUInt16BE(_ value: UInt16) {
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }

    mutating func appendUInt32BE(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }

    mutating func appendUInt64BE(_ value: UInt64) {
        append(UInt8((value >> 56) & 0xFF))
        append(UInt8((value >> 48) & 0xFF))
        append(UInt8((value >> 40) & 0xFF))
        append(UInt8((value >> 32) & 0xFF))
        append(UInt8((value >> 24) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8(value & 0xFF))
    }
}
