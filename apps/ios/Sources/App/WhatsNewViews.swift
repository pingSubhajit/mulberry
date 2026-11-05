import SwiftUI

extension WhatsNewModel {
    /// The app's notes, from `whats-new/ios` on Mulberry's server, named after `CFBundleShortVersionString`.
    static let app = WhatsNewModel(
        platform: .ios,
        version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
        source: { try AppRuntime.api() }
    )
}

/// The note for the version just installed, shown once over the canvas, as on Android.
struct WhatsNewSheet: View {
    let entry: WhatsNewEntry
    let onDone: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                WhatsNewHeader(title: "What’s new", subtitle: entry.subtitle)
                WhatsNewNote(entry: entry)
            }
            .padding(.horizontal, 20).padding(.top, 28).padding(.bottom, 16)
            .readableColumn(560)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) {
            Button(action: onDone) {
                Text(entry.callToAction).font(MulberryDesign.font(17, weight: "SemiBold"))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .prominentButtonStyle()
            .readableColumn(560)
            .padding(.horizontal, 20).padding(.bottom, 12)
        }
        .presentationDetents([.large]).presentationDragIndicator(.visible)
    }
}

/// Every note, newest first, from Settings. More load as the list reaches its end.
struct WhatsNewHistorySheet: View {
    @StateObject private var history: WhatsNewHistory
    private let model: WhatsNewModel
    @Environment(\.dismiss) private var dismiss

    init(model: WhatsNewModel) {
        self.model = model
        _history = StateObject(wrappedValue: model.history())
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    WhatsNewHeader(title: "What’s new", subtitle: "All recent updates")
                    if history.loading {
                        ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                    } else if history.loaded && history.entries.isEmpty {
                        Text("No what’s new entries are configured yet.")
                            .font(.subheadline).foregroundStyle(MulberryDesign.muted)
                    }
                    ForEach(history.entries) { entry in
                        VStack(alignment: .leading, spacing: 14) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.title ?? "What’s new")
                                    .font(MulberryDesign.font(19, weight: "SemiBold", relativeTo: .title3))
                                    .accessibilityAddTraits(.isHeader)
                                if let subtitle = entry.subtitle {
                                    Text(subtitle).font(.footnote).foregroundStyle(MulberryDesign.muted)
                                }
                            }
                            WhatsNewNote(entry: entry)
                            if entry.id != history.entries.last?.id { Divider().padding(.top, 8) }
                        }
                        .onAppear { if entry.id == history.entries.last?.id { Task { await history.load() } } }
                    }
                    if history.loadingMore { ProgressView().frame(maxWidth: .infinity) }
                    if let error = history.errorMessage, !history.loadingMore {
                        VStack(spacing: 8) {
                            Text(error).font(.subheadline).foregroundStyle(MulberryDesign.muted).multilineTextAlignment(.center)
                            Button("Try again") { Task { await history.load() } }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 24)
                .readableColumn(560)
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(role: .close) { dismiss() }.tint(.primary) } }
        }
        .task { await history.load() }
        .presentationDetents([.large]).presentationDragIndicator(.visible)
        .onDisappear {
            // Reading this version's note here counts as seeing it, as on Android.
            if history.entries.contains(where: { $0.version == model.version }) { model.markSeen(model.version) }
        }
    }
}

private struct WhatsNewHeader: View {
    let title: String
    let subtitle: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(MulberryDesign.font(26, weight: "SemiBold", relativeTo: .title))
                .accessibilityAddTraits(.isHeader)
            if let subtitle { Text(subtitle).font(.footnote).foregroundStyle(MulberryDesign.muted) }
        }
    }
}

/// A note's hero image and body.
private struct WhatsNewNote: View {
    let entry: WhatsNewEntry
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let url = (try? AppRuntime.api()).flatMap({ entry.heroImageURL(relativeTo: $0.baseURL) }) {
                WhatsNewHero(url: url)
            }
            WhatsNewMarkdownView(blocks: entry.blocks,
                                 headingFont: MulberryDesign.font(16, weight: "SemiBold", relativeTo: .headline),
                                 bodyFont: .subheadline,
                                 headingColor: MulberryDesign.ink, bodyColor: MulberryDesign.muted)
        }
    }
}

/// The note's picture, at its own shape once it loads. Nothing shows if it can't load.
private struct WhatsNewHero: View {
    let url: URL
    private let corner = RoundedRectangle(cornerRadius: 16, style: .continuous)
    var body: some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFit().clipShape(corner).accessibilityHidden(true)
            case .empty:
                corner.fill(MulberryDesign.surface).aspectRatio(365 / 243, contentMode: .fit)
            default:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity)
    }
}
