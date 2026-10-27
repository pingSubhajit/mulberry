import SwiftUI

enum MulberryDesign {
    static let accent = Color(red: 179 / 255, green: 19 / 255, blue: 41 / 255)
    static let background = adaptive(light: 0xFFFAFA, dark: 0x101010)
    static let surface = adaptive(light: 0xF5EAED, dark: 0x242424)
    static let soft = adaptive(light: 0xFFF0F3, dark: 0x24181B)
    static let ink = adaptive(light: 0x171016, dark: 0xFFFFFF)
    static let muted = adaptive(light: 0x6F5961, dark: 0xBDBDBD)

    static func font(_ size: CGFloat = 16, weight: String = "Regular", relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Poppins-\(weight)", size: size, relativeTo: style)
    }

    private static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { trait in UIColor(argb: 0xFF000000 | (trait.userInterfaceStyle == .dark ? dark : light)) })
    }
}

extension UIColor {
    convenience init(argb: UInt32) {
        self.init(red: CGFloat((argb >> 16) & 255) / 255, green: CGFloat((argb >> 8) & 255) / 255, blue: CGFloat(argb & 255) / 255, alpha: CGFloat((argb >> 24) & 255) / 255)
    }
    var argb: UInt32 {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 1
        getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return UInt32((alpha * 255).rounded()) << 24 | UInt32((red * 255).rounded()) << 16 | UInt32((green * 255).rounded()) << 8 | UInt32((blue * 255).rounded())
    }
}

struct PrimaryAction: View {
    let title: String
    var loading = false
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if loading { ProgressView().tint(.white) }
                Text(title).font(MulberryDesign.font(17, weight: "SemiBold"))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 54)
            .padding(.vertical, 3)
            .background(MulberryDesign.accent.opacity(enabled ? 1 : 0.5), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .disabled(!enabled || loading)
    }
}

struct InlineError: View {
    let message: String?
    var body: some View {
        if let message {
            Label(message, systemImage: "exclamationmark.circle")
                .font(.callout).foregroundStyle(MulberryDesign.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14).background(MulberryDesign.soft, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityIdentifier("inlineError")
        }
    }
}

struct ProfileAvatar: View {
    let url: String?
    let name: String?
    var size: CGFloat = 88
    var unknown = false
    var body: some View {
        ZStack {
            Circle().fill(MulberryDesign.soft)
            AsyncImage(url: url.flatMap(URL.init(string:))) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Text(unknown ? "?" : String(name?.prefix(1) ?? "M").uppercased())
                    .font(MulberryDesign.font(size * 0.4, weight: "SemiBold"))
                    .foregroundStyle(MulberryDesign.accent)
            }
        }
        .frame(width: size, height: size).clipShape(Circle())
        .overlay(Circle().stroke(MulberryDesign.soft, lineWidth: 5))
        .accessibilityLabel(name ?? "Profile photo")
    }
}

struct OnboardingField: View {
    let label: String
    let placeholder: String
    @Binding var value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(label).font(MulberryDesign.font(16, weight: "SemiBold"))
            TextField(placeholder, text: $value)
                .font(MulberryDesign.font(15)).textContentType(.nickname)
                .textInputAutocapitalization(.words)
                .padding(16)
                .background(MulberryDesign.surface.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(MulberryDesign.muted.opacity(0.25)))
                .accessibilityLabel(label)
        }
    }
}

struct ScreenHeading: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(MulberryDesign.font(29, weight: "Bold", relativeTo: .largeTitle))
                .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
            if let subtitle { Text(subtitle).font(.body).foregroundStyle(MulberryDesign.muted).fixedSize(horizontal: false, vertical: true) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
