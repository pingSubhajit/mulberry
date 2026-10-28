import SwiftUI

/// Shared pieces of the full-screen text and sticker editors, which Android
/// draws over a dimmed canvas with a row of round tools at the bottom.
struct CanvasEditorBackdrop: View {
    let shown: Bool
    let onTap: () -> Void

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
            Color.black.opacity(0.72)
        }
        .ignoresSafeArea()
        .opacity(shown ? 1 : 0)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .accessibilityHidden(true)
    }
}

struct CanvasEditorDoneButton: View {
    let identifier: String
    let action: () -> Void

    var body: some View {
        HStack {
            Spacer()
            Button(action: action) {
                Image(systemName: "checkmark")
                    .font(.system(size: 19, weight: .semibold))
                    .frame(width: 24, height: 24)
            }
            .buttonStyle(.glassProminent)
            .buttonBorderShape(.circle)
            .controlSize(.large)
            .tint(MulberryDesign.accent)
            .accessibilityLabel("Done")
            .accessibilityIdentifier(identifier)
        }
        .padding(.horizontal, 16).padding(.top, 8)
    }
}

struct CanvasEditorButton<Label: View>: View {
    let title: String
    var selected = false
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(selected ? MulberryDesign.accent.opacity(0.35) : .clear).interactive(), in: .circle)
        .overlay {
            if selected { Circle().strokeBorder(MulberryDesign.accent, lineWidth: 2).allowsHitTesting(false) }
        }
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension CanvasEditorButton where Label == CanvasEditorSymbol {
    init(_ title: String, symbol: String, selected: Bool = false, tint: Color = .white, action: @escaping () -> Void) {
        self.init(title: title, selected: selected, action: action) { CanvasEditorSymbol(name: symbol, tint: tint) }
    }

    static func delete(action: @escaping () -> Void) -> Self {
        .init("Delete", symbol: "trash", tint: Color(red: 1, green: 0.42, blue: 0.42), action: action)
    }
}

struct CanvasEditorSymbol: View {
    let name: String
    let tint: Color
    var body: some View {
        Image(systemName: name)
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(tint)
            .contentTransition(.symbolEffect(.replace))
    }
}

/// A capsule of glass holding a slider and its end labels.
struct CanvasEditorSliderPanel<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        HStack(spacing: 12) { content() }
            .foregroundStyle(.white)
            .tint(MulberryDesign.accent)
            .padding(.horizontal, 18)
            .frame(height: 52)
            .glassEffect(.regular, in: .capsule)
    }
}

/// Android's -180...180 rotation slider, settling on level with a tick near zero.
struct CanvasEditorRotationPanel: View {
    @Binding var degrees: Double

    var body: some View {
        CanvasEditorSliderPanel {
            Image(systemName: "rotate.left").accessibilityHidden(true)
            Slider(value: Binding(get: { degrees }, set: { degrees = abs($0) < 3 ? 0 : $0 }), in: -180...180)
                .accessibilityLabel("Rotation")
                .accessibilityValue("\(Int(degrees.rounded())) degrees")
            Image(systemName: "rotate.right").accessibilityHidden(true)
        }
        .sensoryFeedback(.selection, trigger: degrees == 0)
    }

    /// The same angle within -180...180, for any angle a gesture left behind.
    static func degrees(fromRadians radians: Double) -> Double {
        let degrees = radians * 180 / .pi
        return degrees - 360 * (degrees / 360).rounded()
    }
}
