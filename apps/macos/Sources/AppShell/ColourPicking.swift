import AppKit
import SwiftUI

private let hues = stride(from: 0.0, through: 1.0, by: 1.0 / 12).map { Color(hue: $0, saturation: 0.8, brightness: 1) }

/// The colour button's face, and the custom colour well, as on iPhone: the colour inside a
/// ring blending every hue.
struct ColourPaletteIcon: View {
    let color: UInt32
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().strokeBorder(AngularGradient(colors: hues, center: .center), lineWidth: size * 0.15)
            Circle().fill(Color(argb: color))
                .overlay(Circle().strokeBorder(.primary.opacity(0.2), lineWidth: 0.5))
                .padding(size * 0.23)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A colour of your own, chosen in place: saturation and brightness in the square, hue in the
/// bar beneath. Back returns to the palette; the checkmark uses the colour.
struct CustomColourPicker: View {
    let width: CGFloat
    let onBack: () -> Void
    let onConfirm: (UInt32) -> Void

    @State private var hue: Double
    @State private var saturation: Double
    @State private var brightness: Double

    init(color: UInt32, width: CGFloat = 220, onBack: @escaping () -> Void, onConfirm: @escaping (UInt32) -> Void) {
        self.width = width
        self.onBack = onBack
        self.onConfirm = onConfirm
        let start = NSColor(argb: color)
        _hue = State(initialValue: Double(start.hueComponent))
        _saturation = State(initialValue: Double(start.saturationComponent))
        _brightness = State(initialValue: Double(start.brightnessComponent))
    }

    private var chosen: UInt32 {
        let color = NSColor(colorSpace: .sRGB, hue: hue, saturation: saturation, brightness: brightness, alpha: 1)
        let channel = { (value: CGFloat) in UInt32((min(1, max(0, value)) * 255).rounded()) }
        return 0xFF000000 | channel(color.redComponent) << 16 | channel(color.greenComponent) << 8 | channel(color.blueComponent)
    }

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 13, weight: .semibold))
                        .frame(width: 28, height: 28)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .help("Colours")
                .accessibilityLabel("Back to colours")
                Spacer()
                Circle().fill(Color(argb: chosen))
                    .frame(width: 28, height: 28)
                    .overlay(Circle().strokeBorder(.primary.opacity(0.2), lineWidth: 0.5))
                    .accessibilityHidden(true)
                Spacer()
                Button { onConfirm(chosen) } label: {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 20, height: 20)
                }
                .buttonStyle(.glassProminent)
                .buttonBorderShape(.circle)
                .tint(mulberryRed)
                .keyboardShortcut(.defaultAction)
                .accessibilityLabel("Use colour")
            }
            saturationBrightness
            hueBar
        }
        .frame(width: width)
    }

    private var saturationBrightness: some View {
        let height = width * 0.7
        return ZStack {
            Color(hue: hue, saturation: 1, brightness: 1)
            LinearGradient(colors: [.white, .white.opacity(0)], startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.black.opacity(0), .black], startPoint: .top, endPoint: .bottom)
        }
        .frame(width: width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(.primary.opacity(0.12), lineWidth: 0.5))
        .overlay(alignment: .topLeading) {
            knob.offset(x: saturation * width - 9, y: (1 - brightness) * height - 9)
        }
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
            saturation = min(1, max(0, drag.location.x / width))
            brightness = 1 - min(1, max(0, drag.location.y / height))
        })
        .accessibilityElement()
        .accessibilityLabel("Saturation and brightness")
        .accessibilityValue("\(Int(saturation * 100)) percent saturation, \(Int(brightness * 100)) percent brightness")
        .accessibilityAdjustableAction { direction in
            brightness = min(1, max(0, brightness + (direction == .increment ? 0.05 : -0.05)))
        }
    }

    private var hueBar: some View {
        Capsule()
            .fill(LinearGradient(colors: hues.map { $0 }, startPoint: .leading, endPoint: .trailing))
            .frame(width: width, height: 14)
            .overlay(Capsule().strokeBorder(.primary.opacity(0.12), lineWidth: 0.5))
            .overlay(alignment: .leading) {
                knob.offset(x: hue * width - 9)
            }
            .frame(height: 18)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                hue = min(1, max(0, drag.location.x / width))
            })
            .accessibilityElement()
            .accessibilityLabel("Hue")
            .accessibilityValue("\(Int(hue * 360)) degrees")
            .accessibilityAdjustableAction { direction in
                hue = min(1, max(0, hue + (direction == .increment ? 1.0 / 36 : -1.0 / 36)))
            }
    }

    private var knob: some View {
        Circle()
            .fill(Color(argb: chosen))
            .frame(width: 18, height: 18)
            .overlay(Circle().strokeBorder(.white, lineWidth: 2.5))
            .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
            .allowsHitTesting(false)
    }
}

extension NSColor {
    convenience init(argb: UInt32) {
        self.init(
            srgbRed: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}

/// Moving between the palette and the custom picker, as a native popover navigates: the picker
/// slides in from the trailing edge and back out, and the popover resizes with it.
enum ColourPickerMotion {
    static func animation(reduceMotion: Bool) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .smooth(duration: 0.32)
    }

    static func picker(reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: .trailing).combined(with: .opacity)
    }

    static func palette(reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: .leading).combined(with: .opacity)
    }
}

/// The brush width, upright as on iPhone: AppKit's own vertical slider, which SwiftUI's slider
/// cannot be (rotating it leaves the mouse tracking sideways). Wider toward the top.
struct VerticalWidthSlider: NSViewRepresentable {
    @Binding var value: Float
    let range: ClosedRange<Float>
    let colour: NSColor

    func makeNSView(context: Context) -> NSSlider {
        let slider = NSSlider(
            value: Double(value),
            minValue: Double(range.lowerBound),
            maxValue: Double(range.upperBound),
            target: context.coordinator,
            action: #selector(Coordinator.changed(_:))
        )
        slider.isVertical = true
        slider.isContinuous = true
        slider.setAccessibilityLabel("Brush width")
        return slider
    }

    func updateNSView(_ slider: NSSlider, context: Context) {
        context.coordinator.value = $value
        if Float(slider.doubleValue) != value { slider.doubleValue = Double(value) }
        slider.trackFillColor = colour
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(value: $value)
    }

    final class Coordinator: NSObject {
        var value: Binding<Float>

        init(value: Binding<Float>) {
            self.value = value
        }

        /// Whole points, as the brush width is set everywhere else.
        @MainActor @objc func changed(_ slider: NSSlider) {
            value.wrappedValue = Float(slider.doubleValue.rounded())
        }
    }
}

/// The palette with the custom colour well as its last cell, as on iPhone; the well pushes the
/// custom picker in place. Used by the brush colour button and the text format bar.
struct PaletteColourPicker: View {
    let palette: [UInt32]
    let selected: UInt32
    let onPick: (UInt32) -> Void
    @State private var customOpen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if customOpen {
                CustomColourPicker(color: selected) {
                    withAnimation(ColourPickerMotion.animation(reduceMotion: reduceMotion)) { customOpen = false }
                } onConfirm: { color in
                    onPick(color)
                }
                .transition(ColourPickerMotion.picker(reduceMotion: reduceMotion))
            } else {
                grid
                    .transition(ColourPickerMotion.palette(reduceMotion: reduceMotion))
            }
        }
        .padding(16)
        .clipped()
    }

    private var grid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(30), spacing: 10), count: 5), spacing: 10) {
            ForEach(palette, id: \.self) { color in
                let isSelected = selected == color
                Button { onPick(color) } label: {
                    Circle().fill(Color(argb: color))
                        .frame(width: 26, height: 26)
                        .overlay(Circle().stroke(.primary.opacity(0.15)))
                        .padding(2)
                        .overlay { if isSelected { Circle().strokeBorder(.primary, lineWidth: 2) } }
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .help(ColourNames.name(color))
                .accessibilityLabel(ColourNames.name(color))
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
            let custom = !palette.contains(selected)
            Button {
                withAnimation(ColourPickerMotion.animation(reduceMotion: reduceMotion)) { customOpen = true }
            } label: {
                ColourPaletteIcon(color: custom ? selected : 0xFFFFFFFF, size: 26)
                    .padding(2)
                    .overlay { if custom { Circle().strokeBorder(.primary, lineWidth: 2) } }
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .help("Custom colour")
            .accessibilityLabel("Custom colour")
            .accessibilityAddTraits(custom ? .isSelected : [])
        }
    }
}
