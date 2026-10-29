import UIKit

/// Spoken names for colours, so VoiceOver says "Mulberry red" rather than a hex code.
enum ColourNames {
    private static let palette: [UInt32: String] = [
        0xFFB31329: "Mulberry red", 0xFFFF6A2A: "Orange", 0xFFFFE66D: "Lemon yellow", 0xFF2457D6: "Blue",
        0xFF5BB7E8: "Sky blue", 0xFF006B4F: "Forest green", 0xFF567A3A: "Olive green", 0xFF80D8B0: "Mint",
        0xFFE85072: "Pink", 0xFFA78BFA: "Lavender", 0xFF3B2F8F: "Indigo", 0xFFF5B83D: "Amber",
        0xFF141414: "Black", 0xFFF7F4EF: "Off-white", 0xFF3A3A3A: "Charcoal", 0xFF7A4A32: "Brown",
        0xFFB56A43: "Terracotta", 0xFFD8A0A8: "Dusty rose", 0xFF00A53C: "Green",
    ]

    /// A palette colour's name, or a description of any other colour by its hue and lightness.
    static func name(_ argb: UInt32) -> String {
        if let name = palette[argb | 0xFF000000] { return name }
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 0
        UIColor(argb: argb).getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha)
        if brightness < 0.15 { return "Black" }
        if saturation < 0.12 { return brightness > 0.9 ? "White" : brightness > 0.6 ? "Light grey" : brightness > 0.3 ? "Grey" : "Dark grey" }
        let names = ["Red", "Orange", "Yellow", "Lime", "Green", "Teal", "Cyan", "Blue", "Indigo", "Purple", "Magenta", "Pink", "Red"]
        let base = names[Int((hue * 12).rounded())]
        return brightness < 0.45 ? "Dark \(base.lowercased())" : saturation < 0.45 && brightness > 0.8 ? "Light \(base.lowercased())" : base
    }
}
