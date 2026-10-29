import SwiftUI

/// A text being typed. A new one has not been added to the canvas yet.
struct CanvasElementSession<Element: Equatable>: Equatable {
    var element: Element
    let isNew: Bool
}

let mulberryRed = Color(argb: 0xFFB31329)

extension Color {
    init(argb: UInt32) {
        let alpha = Double((argb >> 24) & 0xFF) / 255
        let red = Double((argb >> 16) & 0xFF) / 255
        let green = Double((argb >> 8) & 0xFF) / 255
        let blue = Double(argb & 0xFF) / 255
        self.init(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
    }
}
