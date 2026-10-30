import UIKit

/// The canvas's strokes, split by how often each part changes.
struct StrokeScene: Equatable {
    /// Finished strokes, in painting order. These change only when a stroke finishes or goes.
    var settled: [DrawingStroke] = []
    /// Strokes still being drawn, here or by the partner. These change with every point.
    var inProgress: [DrawingStroke] = []
}

/// What draws the editor's strokes.
@MainActor
protocol StrokeSceneDisplay: AnyObject {
    func show(_ scene: StrokeScene)
}

/// Draws strokes in two layers, so a frame costs the strokes being drawn rather than the whole
/// canvas. Settled strokes are rasterized away from the main thread and shown as one image;
/// strokes in progress, and the Pencil's predicted points, are drawn above it each frame.
final class StrokeCanvasView: UIView, StrokeSceneDisplay {
    var dry = true {
        didSet { guard dry != oldValue else { return }; rasterize(); redrawLive() }
    }
    /// Where iOS expects the Pencil to be next, drawn ahead of the stroke and never saved.
    var prediction: DrawingStroke? {
        didSet { if prediction != oldValue { redrawLive() } }
    }
    private var scene = StrokeScene()
    private let settledLayer = CALayer()
    private let live = LiveStrokesView()
    private let rasterizer = StrokeRasterizer()
    /// What the image on screen shows, and the IDs of its strokes.
    private var shown: StrokeRasterizer.Request?
    private var shownIDs: Set<String> = []
    private var rasterizing = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear; isUserInteractionEnabled = false
        layer.addSublayer(settledLayer)
        live.frame = bounds
        live.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(live)
        registerForTraitChanges([UITraitDisplayScale.self]) { (view: StrokeCanvasView, _) in view.rasterize() }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func show(_ scene: StrokeScene) {
        let settledChanged = scene.settled != self.scene.settled
        self.scene = scene
        if settledChanged { rasterize() }
        redrawLive()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Until the image for the new size arrives, the old one stretches to fit.
        CATransaction.begin(); CATransaction.setDisableActions(true)
        settledLayer.frame = bounds
        CATransaction.commit()
        rasterize()
    }

    /// Asks for an image of the settled strokes, unless the one on screen or being made has them.
    /// One image is made at a time; changes meanwhile are caught up with when it is done.
    private func rasterize() {
        guard !rasterizing, bounds.width > 0, bounds.height > 0 else { return }
        let request = StrokeRasterizer.Request(strokes: scene.settled, size: bounds.size, scale: traitCollection.displayScale, dry: dry)
        guard request != shown else { return }
        rasterizing = true
        Task {
            let raster = await rasterizer.render(request)
            rasterizing = false
            guard let raster else { return }
            show(raster)
            rasterize()
        }
    }

    private func show(_ raster: StrokeRasterizer.Raster) {
        shown = raster.request
        shownIDs = Set(raster.request.strokes.map(\.id))
        CATransaction.begin(); CATransaction.setDisableActions(true)
        settledLayer.contents = raster.image
        settledLayer.contentsScale = raster.request.scale
        CATransaction.commit()
        redrawLive()
    }

    /// Settled strokes the image does not have yet, such as one that has just finished, are drawn
    /// with the strokes in progress until it does.
    private func redrawLive() {
        live.strokes = scene.settled.filter { !shownIDs.contains($0.id) } + scene.inProgress + (prediction.map { [$0] } ?? [])
        live.dry = dry
        live.setNeedsDisplay()
    }
}

private final class LiveStrokesView: UIView {
    var strokes: [DrawingStroke] = []
    var dry = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear; isUserInteractionEnabled = false
        contentMode = .redraw
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func draw(_ rect: CGRect) {
        guard !strokes.isEmpty, let context = UIGraphicsGetCurrentContext() else { return }
        DoodleRenderer.draw(strokes, dry: dry, in: context, size: bounds.size)
    }
}
