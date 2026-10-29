import UIKit

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
