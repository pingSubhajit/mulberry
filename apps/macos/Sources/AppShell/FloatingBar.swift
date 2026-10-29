import AppKit
import SwiftUI

/// Content drawn in its own small panel attached to the window, centred where this view is
/// placed. The canvas clips what crosses its edge; a child panel does not, so a bar near the
/// canvas's or the window's edge shows whole, moving with the window as a palette would.
struct FloatingBar<Content: View>: NSViewRepresentable {
    @ViewBuilder let content: () -> Content

    func makeNSView(context: Context) -> FloatingBarAnchor {
        FloatingBarAnchor()
    }

    func updateNSView(_ anchor: FloatingBarAnchor, context: Context) {
        anchor.show(AnyView(content()))
    }

    static func dismantleNSView(_ anchor: FloatingBarAnchor, coordinator: ()) {
        anchor.close()
    }
}

/// A point in the canvas that carries the bar's panel: the panel is centred on it, and follows
/// it as the canvas lays out, and as the bar's own size changes.
final class FloatingBarAnchor: NSView {
    private var panel: FloatingBarPanel?
    private var host: FloatingBarHostingView?

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    func show(_ content: AnyView) {
        if let host {
            host.rootView = content
        } else {
            let host = FloatingBarHostingView(rootView: content)
            host.sizingOptions = [.intrinsicContentSize]
            host.onResize = { [weak self] in self?.reposition() }
            let panel = FloatingBarPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            panel.isOpaque = false
            panel.backgroundColor = .clear
            panel.hasShadow = false
            panel.hidesOnDeactivate = false
            panel.contentView = host
            self.host = host
            self.panel = panel
        }
        attach()
        reposition()
    }

    func close() {
        if let panel {
            panel.parent?.removeChildWindow(panel)
            panel.orderOut(nil)
        }
        panel = nil
        host = nil
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window == nil {
            if let panel { panel.parent?.removeChildWindow(panel); panel.orderOut(nil) }
        } else {
            attach()
            reposition()
        }
    }

    override func setFrameOrigin(_ newOrigin: NSPoint) {
        super.setFrameOrigin(newOrigin)
        reposition()
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        reposition()
    }

    private func attach() {
        guard let window, let panel, panel.parent !== window else { return }
        panel.parent?.removeChildWindow(panel)
        // A child stays just above its window, moves with it, and leaves with it.
        window.addChildWindow(panel, ordered: .above)
    }

    private func reposition() {
        guard let window, let panel, let host else { return }
        let size = host.fittingSize
        guard size.width > 0, size.height > 0 else { return }
        let centre = window.convertPoint(toScreen: convert(NSPoint(x: bounds.midX, y: bounds.midY), to: nil))
        let frame = NSRect(x: (centre.x - size.width / 2).rounded(), y: (centre.y - size.height / 2).rounded(), width: size.width, height: size.height)
        if panel.frame != frame { panel.setFrame(frame, display: true) }
    }
}

/// Clicks on the bar leave the window it floats over key, so typing carries on in the text.
final class FloatingBarPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

/// Reports when the bar's size changes, as a new font or a Straighten button does.
final class FloatingBarHostingView: NSHostingView<AnyView> {
    var onResize: () -> Void = {}

    override func invalidateIntrinsicContentSize() {
        super.invalidateIntrinsicContentSize()
        DispatchQueue.main.async { [weak self] in self?.onResize() }
    }
}
