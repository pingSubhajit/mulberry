import CoreGraphics

public enum CanvasGeometry {
    /// Matches Android's calculateSnapshotPlacement: fit the authored canvas
    /// inside the screen, keeping one scale for both axes, then center it.
    public static func placement(canvas: CGSize?, inside target: CGSize) -> CGRect {
        guard let canvas, valid(canvas) else { return CGRect(origin: .zero, size: target) }
        let scale = min(target.width / canvas.width, target.height / canvas.height)
        let size = CGSize(width: canvas.width * scale, height: canvas.height * scale)
        return CGRect(x: (target.width - size.width) / 2, y: (target.height - size.height) / 2,
                      width: size.width, height: size.height)
    }

    public static func valid(_ size: CGSize) -> Bool {
        size.width.isFinite && size.height.isFinite && size.width > 0 && size.height > 0
    }
}
