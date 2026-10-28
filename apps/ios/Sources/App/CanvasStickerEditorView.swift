import SwiftUI

/// Android's StickerEditorOverlay: a large preview over a dimmed canvas, with
/// the pack picker open by default and size and rotation one tap away.
struct CanvasStickerEditorView: View {
    @ObservedObject var catalog: StickerCatalog
    let onDone: (CanvasStickerElement) -> Void
    let onDelete: () -> Void

    @State private var draft: CanvasStickerElement
    @State private var rotationDegrees: Double
    private let initialDegrees: Double
    @State private var panel: Panel
    @State private var shown = false
    @State private var closing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    enum Panel { case picker, size, rotation }

    init(session: CanvasStickerSession, catalog: StickerCatalog, panel: Panel = .picker,
         onDone: @escaping (CanvasStickerElement) -> Void, onDelete: @escaping () -> Void) {
        self.catalog = catalog; self.onDone = onDone; self.onDelete = onDelete
        _panel = State(initialValue: panel)
        var element = session.element
        element.scale = element.renderScale
        _draft = State(initialValue: element)
        initialDegrees = CanvasEditorRotationPanel.degrees(fromRadians: element.rotationRad)
        _rotationDegrees = State(initialValue: initialDegrees)
    }

    private var hasSticker: Bool { !draft.sticker.stickerId.isEmpty }

    var body: some View {
        CanvasEditorBackdrop(shown: shown, onTap: finish)
            .safeAreaInset(edge: .top, spacing: 0) {
                CanvasEditorDoneButton(identifier: "stickerEditorDone", action: finish).opacity(shown ? 1 : 0)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                controls.opacity(shown ? 1 : 0)
            }
            // Fixed screen insets, as on Android, so switching panels never moves the preview.
            .overlay {
                preview
                    .opacity(shown ? 1 : 0)
                    .scaleEffect(shown || reduceMotion ? 1 : 0.98)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, 110).padding(.bottom, 400)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
        .environment(\.colorScheme, .dark)
        .presentationBackground(.clear)
        .onAppear {
            catalog.load()
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.12)) { shown = true }
        }
        // A new sticker opened before the catalog loaded takes its default once it arrives.
        .onChange(of: catalog.defaultSticker) { _, sticker in
            if !hasSticker, let sticker { draft.sticker = sticker }
        }
    }

    // MARK: Preview

    /// Android grows the 240-point preview from 0.7x to 1.35x across the size range.
    private var previewScale: CGFloat {
        let range = CanvasStickerElement.scaleRange
        let t = min(1, max(0, (draft.scale - range.lowerBound) / (range.upperBound - range.lowerBound)))
        return 0.7 + (1.35 - 0.7) * t
    }

    @ViewBuilder private var preview: some View {
        if hasSticker {
            StickerArtwork(sticker: draft.sticker, user: catalog.user, variant: .full, maxPixelSize: 1024)
                .id(draft.sticker)
                .frame(width: 240, height: 240)
                .scaleEffect(previewScale)
                .rotationEffect(.degrees(rotationDegrees))
                .accessibilityLabel("Sticker preview")
                .animation(reduceMotion ? nil : .snappy(duration: 0.2), value: draft.sticker)
        } else if catalog.loading {
            ProgressView().controlSize(.large).tint(.white)
        }
    }

    // MARK: Controls

    private var controls: some View {
        VStack(spacing: 10) {
            Group {
                switch panel {
                case .picker: picker
                case .size: sizePanel
                case .rotation: CanvasEditorRotationPanel(degrees: $rotationDegrees)
                }
            }
            .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            GlassEffectContainer(spacing: 6) {
                HStack(spacing: 6) {
                    panelButton(.picker, title: "Stickers", symbol: "square.grid.2x2")
                    panelButton(.size, title: "Size", symbol: "arrow.up.left.and.arrow.down.right")
                    panelButton(.rotation, title: "Rotation", symbol: "rotate.right")
                    Spacer(minLength: 0)
                    CanvasEditorButton.delete(action: delete)
                }
            }
        }
        .padding(.horizontal, 12).padding(.bottom, 8)
        .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: panel)
        .sensoryFeedback(.selection, trigger: draft.sticker)
    }

    /// Choosing the open panel again returns to the picker, as on Android.
    private func panelButton(_ target: Panel, title: String, symbol: String) -> some View {
        CanvasEditorButton(title, symbol: symbol, selected: panel == target) { panel = panel == target ? .picker : target }
    }

    private var sizePanel: some View {
        CanvasEditorSliderPanel {
            Image(systemName: "photo").font(.system(size: 12)).accessibilityHidden(true)
            Slider(value: $draft.scale, in: CanvasStickerElement.scaleRange)
                .accessibilityLabel("Size")
                .accessibilityValue("\(Int((draft.scale / CanvasStickerElement.defaultScale * 100).rounded())) percent")
            Image(systemName: "photo").font(.system(size: 20)).accessibilityHidden(true)
        }
    }

    private var picker: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(catalog.selected?.title ?? "Stickers")
                    .font(MulberryDesign.font(16, weight: "SemiBold"))
                    .lineLimit(1)
                Spacer(minLength: 0)
                if catalog.loading && !catalog.packs.isEmpty { ProgressView().controlSize(.small).tint(.white) }
            }
            if let error = catalog.errorMessage {
                HStack(spacing: 10) {
                    Text(error).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(2)
                    Spacer(minLength: 0)
                    Button("Retry") { catalog.load(force: true) }
                        .buttonStyle(.glass).controlSize(.small)
                }
            }
            if catalog.packs.count > 1 { packRow }
            stickerGrid
        }
        .foregroundStyle(.white)
        .padding(14)
        .glassEffect(.regular, in: .rect(cornerRadius: 28))
    }

    private var packRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(catalog.packs) { pack in
                    let selected = catalog.selected?.id == pack.id
                    Button { catalog.select(pack) } label: {
                        StickerArtwork(sticker: .init(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: StickerAssetStore.coverID),
                                       user: catalog.user, hint: pack.coverThumbnailUrl, maxPixelSize: 168)
                            .padding(6)
                            .frame(width: 56, height: 56)
                            .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? MulberryDesign.accent : .white.opacity(0.08), lineWidth: selected ? 2 : 1))
                            .contentShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(pack.title)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .scrollClipDisabled()
    }

    @ViewBuilder private var stickerGrid: some View {
        if let pack = catalog.selected {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 8)], spacing: 8) {
                    ForEach(pack.stickers.sorted { $0.sortOrder < $1.sortOrder }) { sticker in
                        let reference = StickerReference(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: sticker.stickerId)
                        let selected = draft.sticker == reference
                        Button { draft.sticker = reference } label: {
                            StickerArtwork(sticker: reference, user: catalog.user, hint: sticker.thumbnailUrl, maxPixelSize: 256)
                                .padding(6)
                                .aspectRatio(1, contentMode: .fit)
                                .background(.white.opacity(selected ? 0.14 : 0.04), in: RoundedRectangle(cornerRadius: 14))
                                .overlay {
                                    if selected { RoundedRectangle(cornerRadius: 14).strokeBorder(MulberryDesign.accent, lineWidth: 2) }
                                }
                                .contentShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Sticker \(sticker.stickerId)")
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
            .frame(height: 240)
            .scrollIndicators(.hidden)
        } else {
            Color.clear.frame(height: 240)
                .overlay { if catalog.loading { ProgressView().tint(.white) } }
        }
    }

    // MARK: Exit

    private func finish() {
        var element = draft
        // An untouched slider keeps the exact angle, so an unchanged edit saves nothing.
        if rotationDegrees != initialDegrees { element.rotationRad = rotationDegrees * .pi / 180 }
        close { onDone(element) }
    }

    private func delete() { close(onDelete) }

    private func close(_ completion: @escaping () -> Void) {
        guard !closing else { return }
        closing = true
        guard !reduceMotion else { return completion() }
        withAnimation(.easeIn(duration: 0.1)) { shown = false } completion: { completion() }
    }
}
