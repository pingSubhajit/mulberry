import Foundation

/// What the canvas window shows over the canvas, and which shape it is in.
@MainActor
final class MainWindowState: ObservableObject {
    @Published var showsStreak = false
    /// Whether the window is the wide sign-in window, until it has turned back into the canvas's.
    @Published var showsSignIn = false
    /// The sign-in window's welcome stepping aside as the window turns into the canvas's shape.
    @Published var signInLeaving = false
    /// A pretend sign-in for looking at the window in a debug build, without signing out.
    var previewSignIn: (() -> Void)?
    @Published var previewBusy = false
}
