import CanvasDrawing
import ProductCore

/// The drawing the renderer's tests draw: a stroke, a text label and a sticker, as a partner's
/// device would send them. Test data only; the app never shows it.
enum CanvasRenderFixture {
    static func previewOperations() -> [DrawingOperation] {
        let stroke = DrawingStroke(
            id: "stroke-1",
            colorArgb: 0xFFFF4F8B,
            width: 0.028,
            createdAt: 1,
            points: [
                DrawingPoint(x: 0.14, y: 0.18),
                DrawingPoint(x: 0.24, y: 0.3),
                DrawingPoint(x: 0.37, y: 0.22),
                DrawingPoint(x: 0.5, y: 0.42),
                DrawingPoint(x: 0.68, y: 0.29),
                DrawingPoint(x: 0.82, y: 0.46)
            ]
        )
        let text = CanvasTextElement(
            id: "text-1",
            text: "Miss you",
            createdAt: 4,
            center: DrawingPoint(x: 0.5, y: 0.62),
            rotationRad: -0.12,
            scale: 1.08,
            boxWidth: 0.58,
            colorArgb: 0xFFFFD166,
            backgroundPillEnabled: true,
            font: .virgil,
            alignment: .center
        )
        let sticker = CanvasStickerElement(
            id: "sticker-1",
            createdAt: 5,
            center: DrawingPoint(x: 0.48, y: 0.78),
            rotationRad: 0.16,
            scale: 0.22,
            sticker: StickerReference(packKey: "fixture-pack", packVersion: 1, stickerId: "heart")
        )
        // Built from fixed values, so restoring them cannot fail.
        let strokeOperations = (try? DrawingOperation.restore(stroke)) ?? []
        return strokeOperations + [.add(.text(text)), .add(.sticker(sticker))]
    }

    static func previewState() -> DrawingState {
        var state = DrawingState()
        for operation in previewOperations() {
            try? state.apply(operation)
        }
        return state
    }
}
