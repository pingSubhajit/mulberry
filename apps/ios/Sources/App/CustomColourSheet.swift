import SwiftUI

struct CustomColourSheet: View {
    @Binding var color: UInt32
    let onConfirm: () -> Void

    var body: some View {
        EmbeddedColourPicker(color: $color, onConfirm: onConfirm)
            .presentationDetents([.height(560), .large])
    }
}

private struct ConfirmColourButton: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "checkmark")
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
        }
        .buttonStyle(.glassProminent)
        .buttonBorderShape(.circle)
        .tint(MulberryDesign.accent)
        .accessibilityLabel("Use colour and return to canvas")
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

    func makeCoordinator() -> Coordinator { Coordinator(color: $color) }

    func makeUIViewController(context: Context) -> UIViewController {
        let container = UIViewController()
        let picker = UIColorPickerViewController()
        picker.title = "Custom colour"
        picker.supportsAlpha = false
        picker.selectedColor = UIColor(argb: color)
        picker.delegate = context.coordinator
        context.coordinator.picker = picker
        container.addChild(picker)
        container.view.addSubview(picker.view)
        picker.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            picker.view.leadingAnchor.constraint(equalTo: container.view.leadingAnchor),
            picker.view.trailingAnchor.constraint(equalTo: container.view.trailingAnchor),
            picker.view.topAnchor.constraint(equalTo: container.view.topAnchor),
            picker.view.bottomAnchor.constraint(equalTo: container.view.bottomAnchor)
        ])
        picker.didMove(toParent: container)

        // Anchor the SwiftUI action inside the native picker's own header.
        // A SwiftUI overlay on its representable can be laid out in the presenting window.
        let confirmation = UIHostingController(rootView: ConfirmColourButton(action: onConfirm))
        confirmation.view.backgroundColor = .clear
        picker.addChild(confirmation)
        picker.view.addSubview(confirmation.view)
        confirmation.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            confirmation.view.topAnchor.constraint(equalTo: picker.view.safeAreaLayoutGuide.topAnchor, constant: 16),
            confirmation.view.trailingAnchor.constraint(equalTo: picker.view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            confirmation.view.widthAnchor.constraint(equalToConstant: 44),
            confirmation.view.heightAnchor.constraint(equalToConstant: 44)
        ])
        confirmation.didMove(toParent: picker)
        context.coordinator.confirmation = confirmation
        return container
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        context.coordinator.color = $color
        context.coordinator.confirmation?.rootView = ConfirmColourButton(action: onConfirm)
        if context.coordinator.picker?.selectedColor.argb != color {
            context.coordinator.picker?.selectedColor = UIColor(argb: color)
        }
    }

    final class Coordinator: NSObject, UIColorPickerViewControllerDelegate {
        var color: Binding<UInt32>
        weak var picker: UIColorPickerViewController?
        weak var confirmation: UIHostingController<ConfirmColourButton>?
        init(color: Binding<UInt32>) { self.color = color }

        func colorPickerViewController(_ viewController: UIColorPickerViewController, didSelect color: UIColor, continuously: Bool) {
            self.color.wrappedValue = color.argb
        }
    }
}
