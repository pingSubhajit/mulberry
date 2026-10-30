import CoreGraphics
import Foundation

/// Tells a finger meant to draw from the hand holding the Pencil, as iPadOS does for its own
/// drawing. The Pencil always draws, and takes over from any finger on the canvas. While it is in
/// use, fingers do not draw. A contact the size of a palm never draws, nor does a finger landing
/// beside another contact, as a resting hand does. And a finger's stroke stays on this device until
/// it has proved to be a finger, so a palm that is caught late never reaches the partner.
///
/// Times are on the touch clock, `ProcessInfo.systemUptime`, which `UITouch.timestamp` uses.
struct PalmRejection {
    /// A finger followed on the canvas.
    struct Finger: Equatable {
        /// When it landed.
        var landed: TimeInterval
        /// Its largest contact so far, in points: UIKit's radius less its stated uncertainty, so a
        /// fingertip is never mistaken for a palm.
        var radius: CGFloat
        /// Proved to be a finger: its stroke is saved and sent.
        var confirmed = false

        /// Takes in a newer reading of the contact. A palm's contact grows as it settles.
        mutating func measure(majorRadius: CGFloat, tolerance: CGFloat) {
            radius = max(radius, PalmRejection.radius(majorRadius: majorRadius, tolerance: tolerance))
        }
    }

    enum Verdict: Equatable {
        /// The hand, not a finger: it never draws, and what it drew goes.
        case palm
        /// Not yet known: its stroke shows here but is not saved.
        case pending
        /// A finger: its stroke is saved and sent.
        case finger
    }

    /// The smallest contact taken for a palm. iPad reports radii in steps of about 5.2 points, each
    /// with an uncertainty of about 5.2: a fingertip, even pressed flat, reads up to 26.1, so at
    /// least 20.9; the side of a hand reads 31.3 or more, so at least 26.1.
    static let palmRadius: CGFloat = 25
    /// How long after the Pencil last touched or hovered it still counts as in use, so a palm that
    /// rests between words does not draw.
    static let pencilGrace: TimeInterval = 2
    /// How long a finger must stay finger-sized and alone before its stroke is saved. A palm shows
    /// itself well within this as its contact spreads or the rest of the hand lands.
    static let confirmation: TimeInterval = 0.15

    private var pencilSeen = -TimeInterval.infinity

    static func radius(majorRadius: CGFloat, tolerance: CGFloat) -> CGFloat {
        max(0, majorRadius - tolerance)
    }

    /// The Pencil touched or hovered.
    mutating func notePencil(at time: TimeInterval) {
        pencilSeen = max(pencilSeen, time)
    }

    func pencilInUse(at time: TimeInterval) -> Bool {
        time - pencilSeen < Self.pencilGrace
    }

    /// What a finger is now. `others` counts the other fingers and palms on the canvas;
    /// `pencilOnly` is the iPadOS setting Only Draw with Apple Pencil.
    func classify(_ finger: Finger, others: Int, pencilOnly: Bool, at time: TimeInterval) -> Verdict {
        if finger.radius >= Self.palmRadius { return .palm }
        // Once proved, a finger keeps drawing: only a palm-sized contact or the Pencil stops it.
        if finger.confirmed { return .finger }
        if pencilOnly || pencilInUse(at: time) || others > 0 { return .palm }
        return time - finger.landed >= Self.confirmation ? .finger : .pending
    }
}
