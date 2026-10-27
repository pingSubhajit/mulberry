import Foundation

// Motion adapted from Konfetti 2.0.5, used by Mulberry Android.
// Copyright (c) 2017 Dion Segijn. ISC license; see Resources/Legal/konfetti_license.txt.
// Run the same damping, accumulated gravity and rotation at a fixed 60 Hz so
// ProMotion and standard displays produce the same burst in logical points.
public struct ConfettiBurst: Sendable {
    public struct Particle: Sendable {
        public let rightSide: Bool
        public let delay: Double
        public let size: Double
        public let shape: Int
        public let color: Int
        let vx: Double
        let vy: Double
        let mass: Double
        let spin: Double
        let flip: Double
    }
    public struct Frame: Sendable {
        public let x: Double
        public let y: Double
        public let rotation: Double
        public let scaleX: Double
        public let opacity: Double
    }
    public let particles: [Particle]
    public static let duration = 4.0
    public static let colors: [UInt32] = [0xFFB31329, 0xFFFF4D6D, 0xFFFFB000, 0xFFFFE066, 0xFFFF7A3D, 0xFF00A878, 0xFF6C5CE7, 0xFFFFF4F5]

    public init(seed: UInt64 = UInt64.random(in: 1...UInt64.max)) {
        var random = BurstRandom(state: seed)
        particles = (0..<140).map { index in
            let angle = ((index < 70 ? -12.0 : 192.0) + random.unit() * 100 - 50) * .pi / 180
            let speed = 22 + random.unit() * 22
            return Particle(rightSide: index >= 70, delay: Double(index % 70) / 70 * 0.36,
                            size: [6, 9, 12][Int(random.unit() * 3)], shape: Int(random.unit() * 3),
                            color: Int(random.unit() * 8), vx: cos(angle) * speed, vy: sin(angle) * speed,
                            mass: 5 + random.unit(), spin: (0.5 + random.unit()) * 8,
                            flip: (0.5 + random.unit()) * 1.5)
        }
    }

    public func frame(_ particle: Particle, elapsed: Double, width: Double, height: Double, displayScale: Double) -> Frame? {
        let age = elapsed - particle.delay
        guard age >= 0, elapsed < Self.duration else { return nil }
        let frames = age * 60
        let n = floor(frames)
        let fraction = frames - n
        func position(_ count: Double) -> (Double, Double) {
            let d = 0.9
            let travel = d * (1 - pow(d, count)) / (1 - d)
            let gravity = 0.02 / particle.mass
            let fall = gravity * d * (count * (count + 1) / (2 * (1 - d))
                - d / pow(1 - d, 2) * (count - travel))
            return (particle.vx * travel, particle.vy * travel + fall)
        }
        let first = position(n), second = position(n + 1)
        let x = (particle.rightSide ? width : 0) + first.0 + (second.0 - first.0) * fraction
        let y = height * 0.42 + first.1 + (second.1 - first.1) * fraction
        guard y <= height else { return nil }
        let turn = (frames * particle.flip / (particle.size * max(1, displayScale))).truncatingRemainder(dividingBy: 1)
        return Frame(x: x, y: y, rotation: particle.spin * frames,
                     scaleX: abs((1 - turn) - 0.5) * 2,
                     opacity: min(1, max(0, 1 - (age - 3.2) * 60 * 5 / 255)))
    }
}

private struct BurstRandom {
    var state: UInt64
    mutating func unit() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(state >> 11) / 9_007_199_254_740_992.0
    }
}
