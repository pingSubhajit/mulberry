import Foundation
import CryptoKit

/// Tracks what is rendered, not just server acknowledgements. Offline strokes,
/// clears, photo choices and visibility changes all invalidate an old receipt.
public enum WallpaperContent {
    public static func signature(canvas: DrawingState, backgroundID: String, showDrawings: Bool, renderMode: String, canvasViewport: [Double] = []) throws -> String {
        struct Content: Encodable {
            let strokes: [DrawingStroke]
            let backgroundID: String
            let showDrawings: Bool
            let renderMode: String
            let canvasViewport: [Double]
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(Content(strokes: showDrawings ? canvas.strokes : [], backgroundID: backgroundID,
                                              showDrawings: showDrawings, renderMode: renderMode,
                                              canvasViewport: showDrawings ? canvasViewport : []))
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    public static func canUseOfflineCopy(after error: Error) -> Bool {
        if let url = error as? URLError {
            return [.notConnectedToInternet, .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed,
                    .networkConnectionLost, .timedOut, .dataNotAllowed, .internationalRoamingOff].contains(url.code)
        }
        if case BackendError.rejected(let status, _) = error { return (500...599).contains(status) }
        return false
    }
}
