import AppKit
import CanvasDrawing
import ProductCore
import StickerAssets
import Stickers
import SwiftUI

// Stickers on the Mac canvas work as assets do in Figma: the Sticker tool opens a panel of packs;
// clicking a sticker drops it in the middle of the canvas, selected, and dragging one drops it
// where it lands. A selected sticker has the text's frame to move, resize and rotate it, and a
// bar above it to replace, straighten, duplicate or delete it.

/// The published packs: covers along the top, the chosen pack's stickers in a grid below.
/// Click a sticker to pick it; drag it out to drop it on the canvas.
struct StickerPickerPanel: View {
    @ObservedObject var catalog: StickerCatalog
    let user: String
    let store: StickerAssetStore
    /// The sticker being replaced, ringed in the grid.
    var current: StickerReference?
    let onPick: (StickerReference) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(catalog.selected?.title ?? "Stickers")
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
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
            grid
        }
        .padding(14)
        .frame(width: 320)
        .onAppear { catalog.load() }
    }

    private var packRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(catalog.packs) { pack in
                    let selected = catalog.selected?.id == pack.id
                    Button { catalog.select(pack) } label: {
                        StickerThumbnail(
                            sticker: StickerReference(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: StickerAssetStore.coverID),
                            user: user, store: store, hint: pack.coverThumbnailUrl, maxPixelSize: 168
                        )
                        .padding(5)
                        .frame(width: 40, height: 40)
                        .background(.primary.opacity(selected ? 0.12 : 0.04), in: .rect(cornerRadius: 10, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .strokeBorder(selected ? mulberryRed : .primary.opacity(0.06), lineWidth: selected ? 1.5 : 1)
                        }
                        .contentShape(.rect(cornerRadius: 10, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .help(pack.title)
                    .accessibilityLabel(pack.title)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }

    @ViewBuilder private var grid: some View {
        if let pack = catalog.selected {
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                    let stickers = pack.stickers.sorted { $0.sortOrder < $1.sortOrder }
                    ForEach(Array(stickers.enumerated()), id: \.element.id) { index, sticker in
                        let reference = StickerReference(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: sticker.stickerId)
                        StickerCell(
                            reference: reference, user: user, store: store, hint: sticker.thumbnailUrl,
                            current: current == reference
                        ) { onPick(reference) }
                        // Stickers have no names, so VoiceOver reads the pack and the place in it.
                        .accessibilityLabel("\(pack.title), sticker \(index + 1) of \(stickers.count)")
                    }
                }
            }
            .frame(height: 260)
        } else {
            Color.clear.frame(height: 260)
                .overlay { if catalog.loading { ProgressView() } }
        }
    }
}

/// One sticker in the panel: brightens under the pointer, and drags out onto the canvas.
private struct StickerCell: View {
    let reference: StickerReference
    let user: String
    let store: StickerAssetStore
    let hint: String?
    let current: Bool
    let pick: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: pick) {
            StickerThumbnail(sticker: reference, user: user, store: store, hint: hint)
                .padding(6)
                .aspectRatio(1, contentMode: .fit)
                .scaleEffect(hovered ? 1.06 : 1)
                .background(.primary.opacity(current ? 0.14 : hovered ? 0.08 : 0.03), in: .rect(cornerRadius: 12, style: .continuous))
                .overlay { if current { RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(mulberryRed, lineWidth: 1.5) } }
                .contentShape(.rect(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
        .animation(.easeOut(duration: 0.12), value: hovered)
        .draggable(reference.dragString) {
            StickerThumbnail(sticker: reference, user: user, store: store, hint: hint)
                .frame(width: 72, height: 72)
        }
        .accessibilityAddTraits(current ? .isSelected : [])
    }
}

/// The selected sticker's actions, floating above it in the text bar's glass: replace the artwork,
/// straighten it when tilted, duplicate it, delete it.
struct StickerBar: View {
    let sticker: CanvasStickerElement
    /// The sticker panel, for Replace.
    let panel: (_ current: StickerReference, _ pick: @escaping (StickerReference) -> Void) -> AnyView
    @Binding var replacing: Bool
    let onReplace: (StickerReference) -> Void
    let onStraighten: () -> Void
    let onDuplicate: () -> Void
    let onDelete: () -> Void

    private var tilted: Bool { abs(sticker.rotationRad.remainder(dividingBy: 2 * .pi)) > 0.001 }

    var body: some View {
        HStack(spacing: 2) {
            Button { replacing.toggle() } label: {
                FormatSurface(selected: replacing) {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 13, weight: .medium))
                        Text("Replace")
                            .font(.system(size: 13, weight: .medium))
                    }
                    .padding(.horizontal, 8)
                    .frame(height: 28)
                }
            }
            .buttonStyle(.plain)
            .help("Replace sticker (Return)")
            .accessibilityLabel("Replace sticker")
            .popover(isPresented: $replacing, arrowEdge: .top) {
                panel(sticker.sticker) { reference in
                    replacing = false
                    onReplace(reference)
                }
            }
            if tilted {
                FormatButton(title: "Straighten", symbol: "level", action: onStraighten)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            }
            FormatButton(title: "Duplicate (⌘D)", symbol: "plus.square.on.square", action: onDuplicate)
            Rectangle().fill(.primary.opacity(0.12)).frame(width: 1, height: 18).padding(.horizontal, 3)
            FormatButton(title: "Delete (⌫)", symbol: "trash", action: onDelete)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .glassEffect(.regular, in: Capsule())
        .fixedSize()
        .animation(.smooth(duration: 0.2), value: tilted)
    }
}

/// A drawing tool's button; the Sticker tool's also opens the sticker panel beside it.
struct CanvasToolSlotButton: View {
    let slot: CanvasToolSlot
    @ObservedObject var model: CanvasEditorModel
    let size: CGFloat
    let canvasIsEmpty: Bool
    /// Which side of the button the panel opens on.
    var panelEdge: Edge = .top

    var body: some View {
        if slot == .stickers {
            CanvasToolButton(slot, model: model, size: size, canvasIsEmpty: canvasIsEmpty)
                .popover(isPresented: $model.stickerPanelOpen, arrowEdge: panelEdge) {
                    model.stickerPanel()
                }
        } else {
            CanvasToolButton(slot, model: model, size: size, canvasIsEmpty: canvasIsEmpty)
        }
    }
}
