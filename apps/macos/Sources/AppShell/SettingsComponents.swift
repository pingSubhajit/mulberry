import SwiftUI

// The building blocks of every Settings pane: a scrolling page of cards, each card a stack of
// rows with hairlines between them, and the section's title above the card.

/// A pane: its cards, scrolling under the title bar.
struct SettingsPage<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                content()
            }
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 28)
            .padding(.top, 52)
            .padding(.bottom, 32)
        }
        .scrollIndicators(.automatic)
    }
}

/// A card of rows, with an optional title above it and a note below it.
struct SettingsSection<Content: View>: View {
    var title: String?
    var footer: String?
    @ViewBuilder let content: () -> Content

    init(_ title: String? = nil, footer: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.footer = footer
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
                    .padding(.bottom, 10)
            }
            VStack(spacing: 0) {
                Group(subviews: content()) { rows in
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                        if index > 0 {
                            Rectangle()
                                .fill(.primary.opacity(0.07))
                                .frame(height: 1)
                                .padding(.horizontal, 16)
                        }
                        row
                    }
                }
            }
            .background(.primary.opacity(0.055), in: .rect(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(.primary.opacity(0.06), lineWidth: 1)
            }
            if let footer {
                Text(footer)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
        }
        .padding(.bottom, 22)
    }
}

/// A row: its name and an optional line of explanation, and a control at the trailing edge.
struct SettingsRow<Accessory: View>: View {
    let title: String
    var detail: String?
    @ViewBuilder let accessory: () -> Accessory

    init(_ title: String, detail: String? = nil, @ViewBuilder accessory: @escaping () -> Accessory) {
        self.title = title
        self.detail = detail
        self.accessory = accessory
    }

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14, weight: .medium))
                if let detail {
                    Text(detail)
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 12)
            accessory()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, detail == nil ? 13 : 12)
        .frame(minHeight: 50)
    }
}

extension SettingsRow where Accessory == EmptyView {
    init(_ title: String, detail: String? = nil) {
        self.init(title, detail: detail) { EmptyView() }
    }
}

/// A switch row, in Mulberry red.
struct SettingsToggleRow: View {
    let title: String
    var detail: String?
    @Binding var isOn: Bool

    init(_ title: String, detail: String? = nil, isOn: Binding<Bool>) {
        self.title = title
        self.detail = detail
        self._isOn = isOn
    }

    var body: some View {
        SettingsRow(title, detail: detail) {
            Toggle(title, isOn: $isOn)
                .toggleStyle(.switch)
                .labelsHidden()
                .tint(mulberryRed)
        }
    }
}

/// A read-only value at the trailing edge.
struct SettingsValue: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 14))
            .foregroundStyle(.secondary)
            .textSelection(.enabled)
            .multilineTextAlignment(.trailing)
    }
}

/// Raycast's neutral filled button: a soft pill that brightens under the pointer.
struct SettingsButtonStyle: ButtonStyle {
    var prominent = false
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        Label(configuration: configuration, prominent: prominent, enabled: enabled)
    }

    private struct Label: View {
        let configuration: ButtonStyleConfiguration
        let prominent: Bool
        let enabled: Bool
        @State private var hovered = false

        var body: some View {
            configuration.label
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(prominent ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                .padding(.horizontal, 14)
                .frame(height: 30)
                .background(fill, in: .rect(cornerRadius: 8, style: .continuous))
                .opacity(enabled ? 1 : 0.4)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
                .onHover { hovered = $0 }
                .animation(.easeOut(duration: 0.12), value: hovered)
                .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
        }

        private var fill: AnyShapeStyle {
            if prominent {
                return AnyShapeStyle(mulberryRed.opacity(configuration.isPressed ? 0.8 : hovered ? 0.92 : 1))
            }
            return AnyShapeStyle(.primary.opacity(configuration.isPressed ? 0.16 : hovered && enabled ? 0.13 : 0.09))
        }
    }
}

extension ButtonStyle where Self == SettingsButtonStyle {
    static var settings: SettingsButtonStyle { SettingsButtonStyle() }
    static var settingsProminent: SettingsButtonStyle { SettingsButtonStyle(prominent: true) }
}

/// A choice written as its current value and a chevron, opening a menu of the others.
struct SettingsMenu<Value: Hashable>: View {
    let options: [(value: Value, title: String)]
    @Binding var selection: Value

    var body: some View {
        Menu {
            ForEach(options, id: \.value) { option in
                Button {
                    selection = option.value
                } label: {
                    if option.value == selection {
                        Label(option.title, systemImage: "checkmark")
                    } else {
                        Text(option.title)
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(options.first { $0.value == selection }?.title ?? "")
                    .font(.system(size: 14, weight: .medium))
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .fixedSize()
    }
}

/// A problem with the setting above it, and what to do about it, as Raycast's amber callout.
struct SettingsNotice: View {
    let message: String
    var action: (title: String, run: () -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.yellow)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(message)
                    .font(.system(size: 12.5))
                    .foregroundStyle(.primary.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)
                if let action {
                    Button(action.title, action: action.run)
                        .buttonStyle(.plain)
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(.yellow)
                        .underline()
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(.yellow.opacity(0.08), in: .rect(cornerRadius: 10, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(.yellow.opacity(0.35), lineWidth: 1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
    }
}
