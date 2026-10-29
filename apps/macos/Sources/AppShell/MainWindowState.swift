import Foundation

/// What the canvas window shows over the canvas: for now, only whether the streak is open.
@MainActor
final class MainWindowState: ObservableObject {
    @Published var showsStreak = false
}
