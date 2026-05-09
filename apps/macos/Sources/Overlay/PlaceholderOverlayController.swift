import AppKit
import SwiftUI

@MainActor
public final class PlaceholderOverlayController: ObservableObject {
    public static let defaultAspectRatio = CGSize(width: 9, height: 20)
    public static let defaultSize = CGSize(width: 450, height: 1_000)

    @Published public private(set) var isVisible = false

    private var panel: NSPanel?

    public init() {}

    public func show() {
        let panel = panel ?? makePanel()
        self.panel = panel
        panel.setFrame(defaultFrame(), display: true)
        panel.orderFrontRegardless()
        isVisible = true
    }

    public func hide() {
        panel?.orderOut(nil)
        isVisible = false
    }

    public func toggle() {
        if isVisible {
            hide()
        } else {
            show()
        }
    }

    public func resetPosition() {
        let panel = panel ?? makePanel()
        self.panel = panel
        panel.setFrame(defaultFrame(), display: true)
        if isVisible {
            panel.orderFrontRegardless()
        }
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: defaultFrame(),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = false
        panel.ignoresMouseEvents = true
        panel.level = NSWindow.Level(rawValue: NSWindow.Level.normal.rawValue - 1)
        panel.collectionBehavior = [.ignoresCycle]
        panel.contentView = NSHostingView(rootView: PlaceholderOverlayView())
        return panel
    }

    private func defaultFrame() -> CGRect {
        let visibleFrame = NSScreen.main?.visibleFrame ?? CGRect(x: 0, y: 0, width: 1_440, height: 900)
        let size = clampedDefaultSize(for: visibleFrame)
        return CGRect(
            x: visibleFrame.midX - size.width / 2,
            y: visibleFrame.midY - size.height / 2,
            width: size.width,
            height: size.height
        )
    }

    private func clampedDefaultSize(for visibleFrame: CGRect) -> CGSize {
        let requested = Self.defaultSize
        let maxWidth = visibleFrame.width * 0.82
        let maxHeight = visibleFrame.height * 0.82
        let scale = min(1, maxWidth / requested.width, maxHeight / requested.height)
        return CGSize(width: requested.width * scale, height: requested.height * scale)
    }
}

private struct PlaceholderOverlayView: View {
    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(.pink.opacity(0.42), style: StrokeStyle(lineWidth: 2, dash: [8, 8]))

            Text("Overlay placeholder")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.thinMaterial, in: Capsule())
                .padding(12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.clear)
    }
}
