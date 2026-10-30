#if DEBUG
import UIKit

/// Everything the canvas is told about touches, written down as it arrives, before anything acts
/// on it: each contact on the glass, the Pencil's every sample, and the hand's. A debug build keeps
/// the last session in Library/Caches/InputTrace.jsonl, one event per line, to be read off the
/// device when input misbehaves.
@MainActor
final class InputTrace {
    static let shared = InputTrace()

    /// One contact, as UIKit reported it at one moment.
    struct Sample: Encodable {
        /// The contact, the same through its touch sequence.
        let touch: String
        let type: Int
        let phase: Int
        let time: TimeInterval
        let x: CGFloat, y: CGFloat
        let preciseX: CGFloat, preciseY: CGFloat
        let radius: CGFloat, radiusTolerance: CGFloat
        let force: CGFloat
        let altitude: CGFloat, azimuth: CGFloat
        let estimated: Int, expectingUpdates: Int
        let updateIndex: Int?
        /// Over the canvas, rather than elsewhere in the window.
        let onCanvas: Bool
    }

    /// One call from UIKit.
    struct Event: Encodable {
        let callback: String
        let eventTime: TimeInterval?
        /// The touches the call is about, each with the samples coalesced into it, oldest first.
        let touches: [[Sample]]
        /// Every contact on the glass at the time.
        let all: [Sample]
        let canvas: CGSize
    }

    private let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("InputTrace.jsonl")
    private let queue = DispatchQueue(label: "InputTrace", qos: .utility)
    private let handle: FileHandle?
    private let encoder = JSONEncoder()

    private init() {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try? FileHandle(forWritingTo: url)
    }

    func record(_ callback: String, _ touches: Set<UITouch>, _ event: UIEvent?, in view: UIView) {
        let traced = Event(
            callback: callback,
            eventTime: event?.timestamp,
            touches: touches.map { touch in (event?.coalescedTouches(for: touch) ?? [touch]).map { sample($0, in: view) } },
            all: (event?.allTouches ?? []).map { sample($0, in: view) },
            canvas: view.bounds.size
        )
        guard let line = try? encoder.encode(traced) else { return }
        queue.async { [handle] in
            handle?.write(line)
            handle?.write(Data("\n".utf8))
        }
    }

    private func sample(_ touch: UITouch, in view: UIView) -> Sample {
        let location = touch.location(in: view), precise = touch.preciseLocation(in: view)
        let pencil = touch.type == .pencil
        return Sample(
            touch: String(UInt(bitPattern: ObjectIdentifier(touch).hashValue), radix: 16),
            type: touch.type.rawValue, phase: touch.phase.rawValue, time: touch.timestamp,
            x: location.x, y: location.y, preciseX: precise.x, preciseY: precise.y,
            radius: touch.majorRadius, radiusTolerance: touch.majorRadiusTolerance, force: touch.force,
            altitude: pencil ? touch.altitudeAngle : 0, azimuth: pencil ? touch.azimuthAngle(in: view) : 0,
            estimated: Int(touch.estimatedProperties.rawValue),
            expectingUpdates: Int(touch.estimatedPropertiesExpectingUpdates.rawValue),
            updateIndex: touch.estimationUpdateIndex?.intValue,
            onCanvas: touch.view === view
        )
    }
}
#endif
