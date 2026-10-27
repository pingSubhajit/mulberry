import UIKit

enum CanvasGeometry {
    /// Matches Android's calculateSnapshotPlacement: fit the authored canvas
    /// inside the screen, keeping one scale for both axes, then center it.
    static func placement(canvas: CGSize?, inside target: CGSize) -> CGRect {
        guard let canvas, valid(canvas) else { return CGRect(origin: .zero, size: target) }
        let scale = min(target.width / canvas.width, target.height / canvas.height)
        let size = CGSize(width: canvas.width * scale, height: canvas.height * scale)
        return CGRect(x: (target.width - size.width) / 2, y: (target.height - size.height) / 2,
                      width: size.width, height: size.height)
    }

    static func valid(_ size: CGSize) -> Bool {
        size.width.isFinite && size.height.isFinite && size.width > 0 && size.height > 0
    }
}

/// Shortcuts can run without the canvas view or even a foreground app scene.
/// Save the measured drawing area, just as Android saves its canvas viewport.
@MainActor enum CanvasViewportStore {
    static func load(user: String, pair: String?) -> CGSize? {
        guard let values = UserDefaults.standard.array(forKey: key(user, pair)) as? [Double], values.count == 2 else { return nil }
        let size = CGSize(width: values[0], height: values[1])
        return CanvasGeometry.valid(size) ? size : nil
    }

    static func save(_ size: CGSize, user: String, pair: String?) {
        guard CanvasGeometry.valid(size), load(user: user, pair: pair) != size else { return }
        UserDefaults.standard.set([Double(size.width), Double(size.height)], forKey: key(user, pair))
    }

    private static func key(_ user: String, _ pair: String?) -> String { "canvasViewport.\(user).\(pair ?? "unpaired")" }
}
