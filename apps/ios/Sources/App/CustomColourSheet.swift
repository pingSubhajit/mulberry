import SwiftUI

struct CustomColourSheet: View {
    @Binding var color: UInt32
    let onConfirm: () -> Void

    var body: some View {
        EmbeddedColourPicker(color: $color, onConfirm: onConfirm)
            .presentationDetents([.height(560), .large])
    }
}

/// The colour palette button's face: the chosen colour inside a ring blending every hue.
struct ColourPaletteIcon: View {
    let color: UInt32
    let size: CGFloat

    private static let hues = stride(from: 0.0, through: 1.0, by: 1.0 / 12).map { Color(hue: $0, saturation: 0.8, brightness: 1) }

    var body: some View {
        ZStack {
            Circle().strokeBorder(AngularGradient(colors: Self.hues, center: .center), lineWidth: size * 0.15)
            Circle().fill(Color(uiColor: UIColor(argb: color)))
                .overlay(Circle().strokeBorder(.primary.opacity(0.2), lineWidth: 0.5))
                .padding(size * 0.23)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct CustomColourWell: UIViewRepresentable {
    let color: UInt32
    func makeUIView(context: Context) -> UIColorWell {
        let well = UIColorWell()
        well.supportsAlpha = false
        well.isUserInteractionEnabled = false
        return well
    }
    func updateUIView(_ well: UIColorWell, context: Context) {
        well.selectedColor = UIColor(argb: color)
    }
}

private struct EmbeddedColourPicker: UIViewControllerRepresentable {
    @Binding var color: UInt32
    let onConfirm: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(color: $color, onConfirm: onConfirm) }

    func makeUIViewController(context: Context) -> ColourPickerNavigation {
        let picker = UIColorPickerViewController()
        picker.title = "Custom colour"
        picker.supportsAlpha = false
        picker.selectedColor = UIColor(argb: color)
        picker.delegate = context.coordinator
        context.coordinator.picker = picker

        // Inside a navigation controller the picker leaves out its close button, so the
        // system's Done checkmark takes that place in the bar instead.
        let confirm = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [coordinator = context.coordinator] _ in
            coordinator.onConfirm()
        })
        confirm.tintColor = UIColor(MulberryDesign.accent)
        confirm.accessibilityLabel = "Use colour and return to canvas"
        picker.navigationItem.rightBarButtonItem = confirm
        // The picker draws the title itself, beside its eyedropper.
        picker.navigationItem.titleView = UIView()
        let navigation = ColourPickerNavigation(navigationBarClass: PassThroughNavigationBar.self, toolbarClass: nil)
        navigation.viewControllers = [picker]
        return navigation
    }

    func updateUIViewController(_ controller: ColourPickerNavigation, context: Context) {
        context.coordinator.color = $color
        context.coordinator.onConfirm = onConfirm
        if context.coordinator.picker?.selectedColor.argb != color {
            context.coordinator.picker?.selectedColor = UIColor(argb: color)
        }
    }

    final class Coordinator: NSObject, UIColorPickerViewControllerDelegate {
        var color: Binding<UInt32>
        var onConfirm: () -> Void
        weak var picker: UIColorPickerViewController?
        init(color: Binding<UInt32>, onConfirm: @escaping () -> Void) {
            self.color = color
            self.onConfirm = onConfirm
        }

        func colorPickerViewController(_ viewController: UIColorPickerViewController, didSelect color: UIColor, continuously: Bool) {
            self.color.wrappedValue = color.argb
        }
    }
}

/// Lifts the picker under its navigation bar, so the picker's own header (eyedropper and
/// title) shares one row with the bar's Done checkmark rather than sitting beneath it.
private final class ColourPickerNavigation: UINavigationController {
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let picker = viewControllers.first else { return }
        let inset = -navigationBar.bounds.height
        if picker.additionalSafeAreaInsets.top != inset {
            picker.additionalSafeAreaInsets.top = inset
        }
    }
}

/// The bar shares its row with the picker's eyedropper, so it keeps only touches on its buttons.
private final class PassThroughNavigationBar: UINavigationBar {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        var view = hit
        while let current = view, current !== self {
            if current is UIControl { return hit }
            view = current.superview
        }
        return nil
    }
}
