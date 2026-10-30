import CoreGraphics
import Foundation

/// The Apple Pencil's touches, turned into what it really drew. UIKit reports the Pencil as the
/// glass senses it, which is not always what it drew:
///
/// - A sample's force comes first as an estimate, and as measured some 30 ms later. With a palm on
///   the glass and the tip hovering close, iPadOS opens touches whose measured force is zero: the
///   Pencil never touched. So a sample counts once its force is measured, and only if it pressed.
/// - While the Pencil presses, a resting palm can pull one coordinate of a sample across to the
///   palm for a few samples, and iPadOS's smoothing echoes the pull in the samples after. So a sample
///   counts only if the Pencil could have reached it, and is not such an echo.
///
/// What passes is handed on as commands, stroke by stroke in the order they were drawn, even while
/// one stroke is still being measured and the next has begun. Times are on the touch clock,
/// `ProcessInfo.systemUptime`, which `UITouch.timestamp` uses.
struct PencilInput {
    /// One sample of the Pencil, as UIKit reported it.
    struct Sample: Equatable {
        /// UIKit's `estimationUpdateIndex`, which a later measurement of the sample quotes.
        var index: Int?
        var time: TimeInterval
        var point: CGPoint
        var force: CGFloat
        /// The force is measured; otherwise it is an estimate awaiting measurement.
        var forceMeasured: Bool
    }

    /// What the Pencil drew, for the editor.
    enum Command: Equatable {
        case begin(CGPoint)
        case move([CGPoint])
        case end
    }

    /// The least measured force that is the Pencil pressing. A hovering Pencil measures 0.00 to
    /// 0.01; the lightest real stroke recorded reached 0.06.
    static let contactForce: CGFloat = 0.03
    /// How long to wait for a measurement before judging a sample on its estimate.
    static let patience: TimeInterval = 0.5

    private struct Stroke {
        /// The touch drawing it, until the Pencil lifts.
        var touch: ObjectIdentifier?
        /// Samples awaiting their measured force, oldest first.
        var waiting: [Sample] = []
        var gate = MotionGate()
        /// Points judged drawn, not yet handed on.
        var passed: [CGPoint] = []
        var begun = false
        var lifted: Bool { touch == nil }

        /// Judges the samples whose force is measured, in order, and any that have waited past
        /// `cutoff` on their estimate.
        mutating func judge(expiringBefore cutoff: TimeInterval = -.infinity) {
            while let sample = waiting.first, sample.forceMeasured || sample.time < cutoff {
                waiting.removeFirst()
                guard sample.force >= PencilInput.contactForce else { continue }
                passed += gate.add(sample.time, sample.point)
            }
        }
    }

    /// Strokes not yet fully handed on, in the order they were drawn.
    private var strokes: [Stroke] = []

    /// The Pencil touched down.
    mutating func begin(_ touch: ObjectIdentifier, with samples: [Sample]) {
        // UIKit may reuse a touch object while its last stroke is still being measured.
        lift(touch)
        strokes.append(Stroke(touch: touch))
        add(samples, to: touch)
    }

    mutating func add(_ samples: [Sample], to touch: ObjectIdentifier) {
        guard let index = strokes.firstIndex(where: { $0.touch == touch }) else { return }
        strokes[index].waiting += samples
        strokes[index].judge()
    }

    /// The Pencil lifted, or its touch was cancelled. Its stroke ends once its samples are measured.
    mutating func lift(_ touch: ObjectIdentifier) {
        guard let index = strokes.firstIndex(where: { $0.touch == touch }) else { return }
        strokes[index].touch = nil
    }

    /// A sample's force, measured.
    mutating func measure(_ index: Int, force: CGFloat, final: Bool) {
        for stroke in strokes.indices {
            guard let sample = strokes[stroke].waiting.firstIndex(where: { $0.index == index }) else { continue }
            strokes[stroke].waiting[sample].force = force
            strokes[stroke].waiting[sample].forceMeasured = final
            strokes[stroke].judge()
            return
        }
    }

    /// Judges samples whose measurement has not come within `patience`.
    mutating func expire(at time: TimeInterval) {
        for stroke in strokes.indices { strokes[stroke].judge(expiringBefore: time - Self.patience) }
    }

    /// Whether every stroke has been handed on.
    var isIdle: Bool { strokes.isEmpty }

    /// What the Pencil has been judged to draw since last asked, in drawing order.
    mutating func commands() -> [Command] {
        var commands: [Command] = []
        while !strokes.isEmpty {
            hand(strokes[0].passed, into: &commands)
            strokes[0].passed = []
            guard strokes[0].lifted, strokes[0].waiting.isEmpty else { break }
            hand(strokes[0].gate.finish(), into: &commands)
            // A touch that never pressed draws nothing at all.
            if strokes[0].begun { commands.append(.end) }
            strokes.removeFirst()
        }
        return commands
    }

    /// The samples of the stroke being drawn that are still being measured, as they would be drawn
    /// if the Pencil pressed: to show ahead of the stroke, never saved. Nil while no stroke is being
    /// drawn, including before it has begun, so a Pencil that never touches never shows.
    var preview: [CGPoint]? {
        guard let stroke = strokes.first, stroke.begun, !stroke.lifted else { return nil }
        var gate = stroke.gate
        return stroke.waiting.filter { $0.force >= Self.contactForce }.flatMap { gate.add($0.time, $0.point) }
    }

    private mutating func hand(_ points: [CGPoint], into commands: inout [Command]) {
        var points = points
        guard !points.isEmpty else { return }
        if !strokes[0].begun {
            strokes[0].begun = true
            commands.append(.begin(points.removeFirst()))
        }
        if !points.isEmpty { commands.append(.move(points)) }
    }
}

/// Keeps the samples the Pencil could have reached from the one before. A sample further away than
/// the Pencil moves in the time between is a glitch, unless several samples agree it is really
/// there: then the Pencil moved faster than the limit, or the first sample was the glitch. The
/// samples just after a glitch are also checked for its echo.
struct MotionGate {
    /// Faster than handwriting, recorded at no more than 1,500 points a second, and well below the
    /// glitches, recorded at 2,000 to 100,000.
    static let speedLimit: CGFloat = 2_500
    /// Points a sample may stray at any speed: the Pencil's own jitter.
    static let slack: CGFloat = 6
    /// How fast samples rejected one after another may move between them and still agree.
    static let trackSpeed: CGFloat = 10_000
    /// How many rejected samples must agree to show the Pencil is really there.
    static let trackLength = 3
    /// iPadOS smooths the positions it reports, so a glitch rings on in the samples after it, smaller,
    /// swinging either side of the Pencil's course along the line to the glitch. This many samples
    /// after a glitch are checked for its echo.
    static let echoLength = 2
    /// How far off the Pencil's course, along the line to a glitch just rejected, a sample may lie and
    /// not be its echo. Real samples land within half a point of their course, at the median, so
    /// dropping one now and then costs a few milliseconds of line, where an echo shows as a spike.
    static let echoTolerance: CGFloat = 6

    private struct Fix {
        let time: TimeInterval
        let point: CGPoint
    }

    private var anchor: Fix?
    /// The sample before the anchor, which gives the Pencil's course.
    private var previous: Fix?
    /// The last glitch rejected, while the samples after it may still echo it.
    private var glitch: Fix?
    private var echoesLeft = 0
    /// The first sample is held until another agrees with it, so a stroke never starts at a glitch.
    private var anchorHeld = false
    private var outliers: [Fix] = []

    /// Takes in a sample, and gives back the points now judged real.
    mutating func add(_ time: TimeInterval, _ point: CGPoint) -> [CGPoint] {
        let fix = Fix(time: time, point: point)
        guard let anchor else {
            self.anchor = fix
            anchorHeld = true
            return []
        }
        if Self.reachable(fix, from: anchor, at: Self.speedLimit) {
            if echoesLeft > 0 {
                echoesLeft -= 1
                if let glitch, echoes(fix, glitch, from: anchor) { return [] }
            }
            let points = (anchorHeld ? [anchor.point] : []) + [point]
            previous = anchor
            self.anchor = fix
            anchorHeld = false
            outliers = []
            return points
        }
        glitch = fix
        echoesLeft = Self.echoLength
        outliers = Array((outliers + [fix]).suffix(Self.trackLength))
        guard outliers.count == Self.trackLength, Self.agree(outliers), let last = outliers.last else { return [] }
        // A held first sample the track runs on from was real; one it leaps from was the glitch.
        let points = (anchorHeld && Self.agree([anchor] + outliers) ? [anchor.point] : []) + outliers.map(\.point)
        previous = outliers.dropLast().last
        self.anchor = last
        glitch = nil
        echoesLeft = 0
        anchorHeld = false
        outliers = []
        return points
    }

    /// The stroke has ended. A first sample nothing contradicted is a dot, and samples rejected last
    /// are kept if they run on from the last point, as the end of a flick does.
    mutating func finish() -> [CGPoint] {
        defer { anchorHeld = false; outliers = [] }
        guard let anchor else { return [] }
        let held = anchorHeld ? [anchor.point] : []
        return Self.agree([anchor] + outliers) ? held + outliers.map(\.point) : held
    }

    /// Whether a sample lies off the Pencil's course, along the line to the glitch, further than real
    /// samples do. The course runs on from the last two samples kept, at their velocity.
    private func echoes(_ fix: Fix, _ glitch: Fix, from anchor: Fix) -> Bool {
        var course = anchor.point
        if let previous, anchor.time > previous.time {
            let ahead = (fix.time - anchor.time) / (anchor.time - previous.time)
            course.x += (anchor.point.x - previous.point.x) * ahead
            course.y += (anchor.point.y - previous.point.y) * ahead
        }
        let towards = CGPoint(x: glitch.point.x - anchor.point.x, y: glitch.point.y - anchor.point.y)
        let length = hypot(towards.x, towards.y)
        guard length > 0 else { return false }
        let lead = ((fix.point.x - course.x) * towards.x + (fix.point.y - course.y) * towards.y) / length
        return abs(lead) > Self.echoTolerance
    }

    /// Whether each sample is reachable from the one before at a hand's top speed.
    private static func agree(_ fixes: [Fix]) -> Bool {
        zip(fixes, fixes.dropFirst()).allSatisfy { reachable($1, from: $0, at: trackSpeed) }
    }

    private static func reachable(_ fix: Fix, from start: Fix, at speed: CGFloat) -> Bool {
        hypot(fix.point.x - start.point.x, fix.point.y - start.point.y) <= slack + speed * max(0, fix.time - start.time)
    }
}
