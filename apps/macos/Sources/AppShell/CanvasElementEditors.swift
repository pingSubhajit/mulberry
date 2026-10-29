import AppKit
import CanvasDrawing
import ProductCore
import StickerAssets
import Stickers
import SwiftUI

/// A text or sticker open in its editor. A new one has not been added to the canvas yet.
struct CanvasElementSession<Element: Equatable>: Equatable {
    var element: Element
    let isNew: Bool
}

private let mulberryRed = Color(argb: 0xFFB31329)

// MARK: Text

/// Android's text editor, as on iPhone: the text is edited in place over a dimmed canvas,
/// with its style controls along the bottom.
struct CanvasTextEditorOverlay: View {
    let palette: [UInt32]
    let onDone: (CanvasTextElement) -> Void
    let onCancel: () -> Void
    let onDelete: () -> Void

    @State private var draft: CanvasTextElement
    @State private var rotationDegrees: Double
    @State private var panel: Panel?
    @FocusState private var focused: Bool
    private let initialDegrees: Double

    enum Panel { case font, size, rotation, colour }

    init(
        session: CanvasElementSession<CanvasTextElement>,
        palette: [UInt32],
        onDone: @escaping (CanvasTextElement) -> Void,
        onCancel: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.palette = palette
        self.onDone = onDone
        self.onCancel = onCancel
        self.onDelete = onDelete
        var element = session.element
        element.scale = min(CanvasTextElement.scaleRange.upperBound, max(CanvasTextElement.scaleRange.lowerBound, element.scale))
        _draft = State(initialValue: element)
        initialDegrees = degrees(fromRadians: element.rotationRad)
        _rotationDegrees = State(initialValue: initialDegrees)
    }

    var body: some View {
        GeometryReader { proxy in
            CanvasEditorFrame(onBackdrop: finish, onCancel: onCancel, onDone: finish) {
                textField(width: proxy.size.width)
            } panel: {
                panelContent
            } controls: {
                panelButton(.font, title: "Font", symbol: "textformat")
                panelButton(.size, title: "Size", symbol: "textformat.size")
                panelButton(.rotation, title: "Rotation", symbol: "rotate.right")
                colourButton
                CanvasEditorIconButton(title: "Alignment: \(alignmentName)", symbol: alignmentSymbol) { cycleAlignment() }
                CanvasEditorIconButton(
                    title: "Background",
                    symbol: draft.backgroundPillEnabled ? "a.square.fill" : "a.square",
                    selected: draft.backgroundPillEnabled
                ) { draft.backgroundPillEnabled.toggle() }
                Spacer(minLength: 12)
                CanvasEditorIconButton(title: "Delete", symbol: "trash", action: onDelete)
            }
        }
        .onAppear { focused = true }
    }

    private var fontSize: CGFloat { min(160, max(12, CanvasTextStyle.baseSize * draft.scale)) }

    private var textAlignment: TextAlignment {
        switch draft.alignment {
        case .left: .leading
        case .center: .center
        case .right: .trailing
        }
    }

    private func textField(width: CGFloat) -> some View {
        // Android previews size by scaling the font, keeping the wrap width.
        TextField("", text: $draft.text, prompt: Text("Type something").foregroundStyle(.white.opacity(0.45)), axis: .vertical)
            .textFieldStyle(.plain)
            .font(.custom(CanvasTextStyle.postScriptName(draft.font), fixedSize: fontSize))
            .foregroundStyle(Color(argb: CanvasTextStyle.textColor(draft)))
            .multilineTextAlignment(textAlignment)
            .lineLimit(1...10)
            .focused($focused)
            .frame(width: max(120, (width - 96) * min(0.95, max(0.35, draft.boxWidth))))
            .padding(.horizontal, draft.backgroundPillEnabled ? 18 : 0)
            .padding(.vertical, draft.backgroundPillEnabled ? 14 : 0)
            .background {
                if draft.backgroundPillEnabled {
                    RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color(argb: draft.colorArgb))
                }
            }
            .rotationEffect(.degrees(rotationDegrees))
            .accessibilityLabel("Text")
    }

    @ViewBuilder private var panelContent: some View {
        switch panel {
        case .font:
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(CanvasTextFont.allCases, id: \.self) { font in
                        let selected = draft.font == font
                        Button { draft.font = font } label: {
                            Text(CanvasTextStyle.displayName(font))
                                .font(.custom(CanvasTextStyle.postScriptName(font), fixedSize: 15))
                                .padding(.horizontal, 14)
                                .frame(height: 32)
                                .background(.white.opacity(selected ? 0.16 : 0.06), in: Capsule())
                                .overlay { if selected { Capsule().strokeBorder(mulberryRed, lineWidth: 2) } }
                                .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
        case .size:
            CanvasEditorSlider(value: $draft.scale, range: CanvasTextElement.scaleRange, label: "Size") {
                Text("Aa").font(.system(size: 11, weight: .semibold))
            } maximum: {
                Text("Aa").font(.system(size: 17, weight: .semibold))
            }
        case .rotation:
            CanvasEditorRotation(degrees: $rotationDegrees)
        case .colour:
            CanvasEditorPalette(palette: palette, colour: $draft.colorArgb)
        case nil:
            EmptyView()
        }
    }

    private func panelButton(_ target: Panel, title: String, symbol: String) -> some View {
        CanvasEditorIconButton(title: title, symbol: symbol, selected: panel == target) {
            panel = panel == target ? nil : target
        }
    }

    private var colourButton: some View {
        Button { panel = panel == .colour ? nil : .colour } label: {
            Circle().fill(Color(argb: draft.colorArgb))
                .padding(3)
                .background(Circle().fill(AngularGradient(colors: [.red, .yellow, .green, .cyan, .blue, .purple, .red], center: .center)))
                .frame(width: 20, height: 20)
                .frame(width: 34, height: 34)
                .background(.white.opacity(panel == .colour ? 0.16 : 0), in: RoundedRectangle(cornerRadius: 8))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help("Colour")
        .accessibilityLabel("Colour")
    }

    private var alignmentSymbol: String {
        switch draft.alignment {
        case .left: "text.alignleft"
        case .center: "text.aligncenter"
        case .right: "text.alignright"
        }
    }

    private var alignmentName: String {
        switch draft.alignment {
        case .left: "Left"
        case .center: "Centre"
        case .right: "Right"
        }
    }

    private func cycleAlignment() {
        switch draft.alignment {
        case .left: draft.alignment = .center
        case .center: draft.alignment = .right
        case .right: draft.alignment = .left
        }
    }

    private func finish() {
        var element = draft
        // An untouched slider keeps the exact angle, so an unchanged edit saves nothing.
        if rotationDegrees != initialDegrees { element.rotationRad = rotationDegrees * .pi / 180 }
        onDone(element)
    }
}

// MARK: Stickers

/// Android's sticker editor, as on iPhone: a large preview over a dimmed canvas, with the
/// pack picker open by default and size and rotation one click away.
struct CanvasStickerEditorOverlay: View {
    @ObservedObject var catalog: StickerCatalog
    let user: String
    let store: StickerAssetStore
    let onDone: (CanvasStickerElement) -> Void
    let onCancel: () -> Void
    let onDelete: () -> Void

    @State private var draft: CanvasStickerElement
    @State private var rotationDegrees: Double
    @State private var panel: Panel = .picker
    private let initialDegrees: Double

    enum Panel { case picker, size, rotation }

    init(
        session: CanvasElementSession<CanvasStickerElement>,
        catalog: StickerCatalog,
        user: String,
        store: StickerAssetStore,
        onDone: @escaping (CanvasStickerElement) -> Void,
        onCancel: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.catalog = catalog
        self.user = user
        self.store = store
        self.onDone = onDone
        self.onCancel = onCancel
        self.onDelete = onDelete
        var element = session.element
        element.scale = element.renderScale
        _draft = State(initialValue: element)
        initialDegrees = degrees(fromRadians: element.rotationRad)
        _rotationDegrees = State(initialValue: initialDegrees)
    }

    private var hasSticker: Bool { !draft.sticker.stickerId.isEmpty }

    var body: some View {
        CanvasEditorFrame(onBackdrop: finish, onCancel: onCancel, onDone: finish) {
            preview
        } panel: {
            switch panel {
            case .picker: picker
            case .size:
                CanvasEditorSlider(value: $draft.scale, range: CanvasStickerElement.scaleRange, label: "Size") {
                    Image(systemName: "photo").font(.system(size: 11))
                } maximum: {
                    Image(systemName: "photo").font(.system(size: 17))
                }
            case .rotation: CanvasEditorRotation(degrees: $rotationDegrees)
            }
        } controls: {
            panelButton(.picker, title: "Stickers", symbol: "square.grid.2x2")
            panelButton(.size, title: "Size", symbol: "arrow.up.left.and.arrow.down.right")
            panelButton(.rotation, title: "Rotation", symbol: "rotate.right")
            Spacer(minLength: 12)
            CanvasEditorIconButton(title: "Delete", symbol: "trash", action: onDelete)
        }
        .onAppear { catalog.load() }
        // A new sticker opened before the catalog loaded takes its default once it arrives.
        .onChange(of: catalog.defaultSticker) { _, sticker in
            if !hasSticker, let sticker { draft.sticker = sticker }
        }
    }

    /// Android grows the 240-point preview from 0.7x to 1.35x across the size range.
    private var previewScale: CGFloat {
        let range = CanvasStickerElement.scaleRange
        let progress = min(1, max(0, (draft.scale - range.lowerBound) / (range.upperBound - range.lowerBound)))
        return 0.7 + (1.35 - 0.7) * progress
    }

    @ViewBuilder private var preview: some View {
        if hasSticker {
            StickerThumbnail(sticker: draft.sticker, user: user, store: store, variant: .full, maxPixelSize: 1024)
                .id(draft.sticker)
                .frame(width: 200, height: 200)
                .scaleEffect(previewScale)
                .rotationEffect(.degrees(rotationDegrees))
                .accessibilityLabel("Sticker preview")
        } else if catalog.loading {
            ProgressView().controlSize(.large)
        }
    }

    private func panelButton(_ target: Panel, title: String, symbol: String) -> some View {
        CanvasEditorIconButton(title: title, symbol: symbol, selected: panel == target) {
            panel = panel == target ? .picker : target
        }
    }

    private var picker: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(catalog.selected?.title ?? "Stickers").font(.headline).lineLimit(1)
                Spacer(minLength: 0)
                if catalog.loading && !catalog.packs.isEmpty { ProgressView().controlSize(.small) }
            }
            if let error = catalog.errorMessage {
                HStack(spacing: 10) {
                    Text(error).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                    Spacer(minLength: 0)
                    Button("Retry") { catalog.load(force: true) }.controlSize(.small)
                }
            }
            if catalog.packs.count > 1 { packRow }
            stickerGrid
        }
    }

    private var packRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(catalog.packs) { pack in
                    let selected = catalog.selected?.id == pack.id
                    Button { catalog.select(pack) } label: {
                        StickerThumbnail(
                            sticker: StickerReference(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: StickerAssetStore.coverID),
                            user: user, store: store, hint: pack.coverThumbnailUrl, maxPixelSize: 168
                        )
                        .padding(5)
                        .frame(width: 48, height: 48)
                        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(selected ? mulberryRed : .white.opacity(0.08), lineWidth: selected ? 2 : 1))
                        .contentShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .help(pack.title)
                    .accessibilityLabel(pack.title)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }

    @ViewBuilder private var stickerGrid: some View {
        if let pack = catalog.selected {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 8)], spacing: 8) {
                    let stickers = pack.stickers.sorted { $0.sortOrder < $1.sortOrder }
                    ForEach(Array(stickers.enumerated()), id: \.element.id) { index, sticker in
                        let reference = StickerReference(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: sticker.stickerId)
                        let selected = draft.sticker == reference
                        Button { draft.sticker = reference } label: {
                            StickerThumbnail(sticker: reference, user: user, store: store, hint: sticker.thumbnailUrl)
                                .padding(5)
                                .aspectRatio(1, contentMode: .fit)
                                .background(.white.opacity(selected ? 0.14 : 0.04), in: RoundedRectangle(cornerRadius: 12))
                                .overlay { if selected { RoundedRectangle(cornerRadius: 12).strokeBorder(mulberryRed, lineWidth: 2) } }
                                .contentShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                        // Stickers have no names, so VoiceOver reads the pack and the place in it.
                        .accessibilityLabel("\(pack.title), sticker \(index + 1) of \(stickers.count)")
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
            .frame(height: 200)
        } else {
            Color.clear.frame(height: 200)
                .overlay { if catalog.loading { ProgressView() } }
        }
    }

    private func finish() {
        var element = draft
        if rotationDegrees != initialDegrees { element.rotationRad = rotationDegrees * .pi / 180 }
        onDone(element)
    }
}

// MARK: Chrome

/// The editors' shared layout: a dimmed canvas behind, the element in the middle, an optional
/// panel and the control bar along the bottom, and Done at the top. Esc cancels; ⌘↩ is Done.
private struct CanvasEditorFrame<Content: View, Panel: View, Controls: View>: View {
    let onBackdrop: () -> Void
    let onCancel: () -> Void
    let onDone: () -> Void
    @ViewBuilder let content: Content
    @ViewBuilder let panel: Panel
    @ViewBuilder let controls: Controls

    var body: some View {
        ZStack {
            Rectangle()
                .fill(.black.opacity(0.62))
                .background(.ultraThinMaterial)
                .contentShape(Rectangle())
                .onTapGesture(perform: onBackdrop)
            VStack(spacing: 14) {
                HStack {
                    Spacer()
                    Button("Done", action: onDone)
                        .keyboardShortcut(.return, modifiers: .command)
                        .buttonStyle(.borderedProminent)
                        .tint(mulberryRed)
                }
                Spacer(minLength: 0)
                content
                Spacer(minLength: 0)
                VStack(spacing: 10) {
                    panel
                    HStack(spacing: 4) { controls }
                }
                .padding(12)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(18)
        }
        .environment(\.colorScheme, .dark)
        .onExitCommand(perform: onCancel)
    }
}

private struct CanvasEditorIconButton: View {
    let title: String
    let symbol: String
    var selected = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .frame(width: 34, height: 34)
                .foregroundStyle(selected ? mulberryRed : .primary)
                .background(.white.opacity(selected ? 0.16 : 0), in: RoundedRectangle(cornerRadius: 8))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(title)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct CanvasEditorSlider<Minimum: View, Maximum: View>: View {
    @Binding var value: Double
    let range: ClosedRange<Double>
    let label: String
    @ViewBuilder let minimum: Minimum
    @ViewBuilder let maximum: Maximum

    var body: some View {
        HStack(spacing: 12) {
            minimum.accessibilityHidden(true)
            Slider(value: $value, in: range).accessibilityLabel(label)
            maximum.accessibilityHidden(true)
        }
    }
}

private struct CanvasEditorRotation: View {
    @Binding var degrees: Double

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "rotate.left").accessibilityHidden(true)
            Slider(value: $degrees, in: -180...180).accessibilityLabel("Rotation")
            Text("\(Int(degrees.rounded()))°")
                .monospacedDigit()
                .frame(width: 44, alignment: .trailing)
            Button("Reset") { degrees = 0 }.controlSize(.small).disabled(degrees == 0)
        }
    }
}

private struct CanvasEditorPalette: View {
    let palette: [UInt32]
    @Binding var colour: UInt32

    var body: some View {
        HStack(spacing: 8) {
            ForEach(palette, id: \.self) { candidate in
                let selected = colour == candidate
                Button { colour = candidate } label: {
                    Circle().fill(Color(argb: candidate))
                        .frame(width: 22, height: 22)
                        .overlay(Circle().strokeBorder(selected ? mulberryRed : .white.opacity(0.25), lineWidth: selected ? 2.5 : 1))
                        .padding(2)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
            ColorPicker("Custom colour", selection: custom, supportsOpacity: false)
                .labelsHidden()
                .help("Custom colour")
        }
    }

    private var custom: Binding<Color> {
        Binding(
            get: { Color(argb: colour) },
            set: { colour = NSColor($0).usingColorSpace(.sRGB).map(argb(of:)) ?? colour }
        )
    }
}

/// Keeps a slider's angle in -180...180 whatever angle a gesture left behind.
private func degrees(fromRadians radians: Double) -> Double {
    var value = (radians * 180 / .pi).truncatingRemainder(dividingBy: 360)
    if value > 180 { value -= 360 }
    if value < -180 { value += 360 }
    return value
}

private func argb(of color: NSColor) -> UInt32 {
    let channel = { (value: CGFloat) in UInt32((min(1, max(0, value)) * 255).rounded()) }
    return 0xFF000000 | channel(color.redComponent) << 16 | channel(color.greenComponent) << 8 | channel(color.blueComponent)
}

extension Color {
    init(argb: UInt32) {
        let alpha = Double((argb >> 24) & 0xFF) / 255
        let red = Double((argb >> 16) & 0xFF) / 255
        let green = Double((argb >> 8) & 0xFF) / 255
        let blue = Double(argb & 0xFF) / 255
        self.init(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
    }
}
