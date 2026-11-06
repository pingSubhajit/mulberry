import Foundation
#if canImport(ProductCore)
import ProductCore
#endif

/// The caption's font size and lines.
public struct ShareCaptionFit: Equatable, Sendable {
    public let fontSize: Int
    public let lines: [String]

    public init(fontSize: Int, lines: [String]) {
        self.fontSize = fontSize; self.lines = lines
    }
}

/// Fits a caption into its layout, as the reference renderer does (docs/share-frames.md,
/// "Caption"): the largest integer size from `maxFontSize` down to `minFontSize` at which the
/// words, wrapped on spaces, take at most `maxLines` lines no wider than `maxWidth`. At each size
/// the first line takes as many words as fit; the rest go into the fewest lines that fit, split
/// for the smallest sum of squared unused widths. If nothing fits, `minFontSize`, wrapped greedily.
public enum ShareCaptionFitter {
    /// `width(text, size)` is the text's advance width at a font size.
    public static func fit(_ text: String, caption: ShareFrameLayout.Caption, width: (String, Int) -> Double) -> ShareCaptionFit {
        let words = text.split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return ShareCaptionFit(fontSize: caption.maxFontSize, lines: []) }
        for size in stride(from: caption.maxFontSize, through: caption.minFontSize, by: -1) {
            if let lines = balanced(words, maxWidth: caption.maxWidth, maxLines: caption.maxLines, width: { width($0, size) }) {
                return ShareCaptionFit(fontSize: size, lines: lines)
            }
        }
        let size = caption.minFontSize
        return ShareCaptionFit(fontSize: size, lines: greedy(words, maxWidth: caption.maxWidth, width: { width($0, size) }))
    }

    /// The lines at one size, or nil when the words don't fit in `maxLines`.
    static func balanced(_ words: [String], maxWidth: Double, maxLines: Int, width: (String) -> Double) -> [String]? {
        var widths: [Range<Int>: Double] = [:]
        func measure(_ range: Range<Int>) -> Double {
            if let known = widths[range] { return known }
            let value = width(words[range].joined(separator: " "))
            widths[range] = value
            return value
        }
        struct Key: Hashable { let start: Int; let count: Int }
        var memo: [Key: (score: Double, lines: [Range<Int>])?] = [:]

        func wrap(_ start: Int, _ count: Int) -> (score: Double, lines: [Range<Int>])? {
            if start == words.count { return count == 0 ? (0, []) : nil }
            if count == 0 { return nil }
            let key = Key(start: start, count: count)
            if let known = memo[key] { return known }
            var ends = Array((start + 1)...words.count)
            if start == 0 {
                // The first line takes as many words as fit.
                ends = measure(0..<1) <= maxWidth ? [ends.filter { measure(0..<$0) <= maxWidth }.max()!] : []
            }
            var best: (score: Double, lines: [Range<Int>])?
            for end in ends {
                let lineWidth = measure(start..<end)
                if lineWidth > maxWidth { break }
                guard let rest = wrap(end, count - 1) else { continue }
                let score = (maxWidth - lineWidth) * (maxWidth - lineWidth) + rest.score
                if best == nil || score < best!.score { best = (score, [start..<end] + rest.lines) }
            }
            memo[key] = .some(best)
            return best
        }

        for count in 1...max(1, maxLines) {
            if let solution = wrap(0, count) { return solution.lines.map { words[$0].joined(separator: " ") } }
        }
        return nil
    }

    /// Each line takes as many words as fit; a word wider than the line has a line to itself.
    static func greedy(_ words: [String], maxWidth: Double, width: (String) -> Double) -> [String] {
        var lines: [String] = []
        var current: [String] = []
        for word in words {
            let candidate = (current + [word]).joined(separator: " ")
            if current.isEmpty || width(candidate) <= maxWidth {
                current.append(word)
            } else {
                lines.append(current.joined(separator: " "))
                current = [word]
            }
        }
        if !current.isEmpty { lines.append(current.joined(separator: " ")) }
        return lines
    }
}
