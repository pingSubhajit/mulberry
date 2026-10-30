import SwiftUI

/// The shared drawing as this device last saved or received it. It changes as fast as strokes'
/// points arrive, here or from the partner, so it lives apart from the app model: only what draws
/// the canvas follows it, rather than every view that watches the app.
@MainActor
final class CanvasModel: ObservableObject {
    @Published private(set) var state = DrawingState()
    /// Edits saved here and not yet confirmed by the server.
    @Published private(set) var pendingEdits = 0
    private var version: UInt64?

    /// Shows a saved document unless one at least as new is already shown, which is the case for
    /// the same document arriving both from a save and from the sync that published it.
    @discardableResult
    func accept(_ document: DrawingDocument) -> Bool {
        if let version, document.version <= version { return false }
        state = document.visible
        pendingEdits = document.pending.count
        version = document.version
        return true
    }

    /// For a sign-out, or another account or pair.
    func reset() {
        state = DrawingState(); pendingEdits = 0; version = nil
    }
}
