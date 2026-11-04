import Overlay
import ProductCore

extension OverlayController {
    /// Shows or hides the drawings on the desktop because the person asked to. Turning them on is
    /// the overlay's one setup step done; turning them off is reported by presence, as with any
    /// surface.
    func setShownByPerson(_ shown: Bool) {
        if shown {
            show()
            AppAnalytics.track(.wallpaperSetupStep(surface: .macOverlay, step: .enableOverlay, outcome: .completed))
        } else {
            hide()
        }
    }
}
