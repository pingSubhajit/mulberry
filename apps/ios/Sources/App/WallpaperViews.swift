import SwiftUI
import PhotosUI

struct WallpaperPickerView: View {
    @ObservedObject var library: WallpaperLibrary
    var onboarding = false
    let onSetup: () -> Void
    var onSkip: () -> Void = {}
    @Environment(\.scenePhase) private var phase
    @State private var photo: PhotosPickerItem?
    @State private var gallery = false
    @State private var loadingPhoto = false
    @State private var hasChanges = true
    private let radius: CGFloat = 15.38
    private var busy: Bool { library.working || loadingPhoto }

    var body: some View {
        ScrollView {
            VStack(spacing: 34) {
                if onboarding {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Almost there").foregroundStyle(MulberryDesign.muted)
                        ScreenHeading(title: "Let’s set up your\nlock screen", subtitle: "Let’s set up a device background for you that only you will see. The canvas will appear on top of it.")
                    }
                }
                preview
                VStack(spacing: 28) {
                    // iOS only: Shortcuts applies the wallpaper, so offer a manual run when the result is out of date.
                    if !onboarding {
                        PrimaryAction(title: "Update wallpaper", loading: library.working, enabled: hasChanges && !busy, action: onSetup)
                    }
                    InlineError(message: library.errorMessage)
                    uploadTile
                    VStack(spacing: 20) {
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 20), GridItem(.flexible(), spacing: 20)], spacing: 20) {
                            ForEach(WallpaperChoice.builtIns) { choice in presetCard(choice) }
                        }
                        Button { gallery = true } label: {
                            Text("View more wallpapers").font(MulberryDesign.font(14, weight: "SemiBold"))
                                .foregroundStyle(MulberryDesign.accent)
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .background(MulberryDesign.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: radius))
                        }.buttonStyle(.plain).disabled(busy)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.top, 34).padding(.bottom, 18)
        }.background(MulberryDesign.background)
            .safeAreaInset(edge: .bottom) {
                if onboarding {
                    VStack(spacing: 10) {
                        PrimaryAction(title: "Set up lock screen", enabled: !busy, action: onSetup)
                        Button("Skip without setup", action: onSkip).frame(minHeight: 44)
                    }.padding(.horizontal, 24).padding(.vertical, 14).background(MulberryDesign.background)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $gallery) { WallpaperGalleryView(library: library) }
            // Recheck whenever the background changes or the user returns from the Shortcut.
            .task(id: "\(library.choice.id)-\(library.showDrawings)-\(phase == .active)") {
                guard !onboarding, phase == .active else { return }
                hasChanges = await ProductWallpaperService.shared.hasUnappliedChanges()
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                loadingPhoto = true
                let user = library.userId
                Task {
                    defer { loadingPhoto = false; photo = nil }
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self) else { throw WallpaperImageError.invalidImage }
                        guard user == library.userId else { return }
                        _ = await library.importPhoto(data)
                    } catch { if user == library.userId { library.errorMessage = error.localizedDescription } }
                }
            }
    }

    /// Lock Screen preview matching Android: a framed background with a placeholder clock.
    private var preview: some View {
        Image(uiImage: library.image).resizable().scaledToFill()
            .frame(width: 221, height: 416).clipShape(RoundedRectangle(cornerRadius: radius))
            .overlay(alignment: .top) {
                VStack(spacing: 0) {
                    Text("9:41").font(MulberryDesign.font(52, weight: "SemiBold")).foregroundStyle(.white.opacity(0.62))
                    Text(Date.now, format: .dateTime.weekday(.wide).month(.wide).day())
                        .font(MulberryDesign.font(12, weight: "Medium")).foregroundStyle(.white.opacity(0.7))
                }.padding(.top, 48).accessibilityHidden(true)
            }
            .overlay { if busy { ProgressView().padding(16).background(.regularMaterial, in: Circle()) } }
            .padding(6)
            .background(MulberryDesign.previewFrame, in: RoundedRectangle(cornerRadius: radius))
            .accessibilityElement().accessibilityLabel("Selected wallpaper: \(library.choice.title)")
    }

    private var uploadTile: some View {
        PhotosPicker(selection: $photo, matching: .images) {
            Label("Upload from gallery", systemImage: "photo")
                .font(MulberryDesign.font(14, weight: "SemiBold")).foregroundStyle(MulberryDesign.accent)
                .frame(maxWidth: .infinity, minHeight: 115)
                .background(MulberryDesign.softAlt, in: RoundedRectangle(cornerRadius: radius))
                .overlay(RoundedRectangle(cornerRadius: radius).strokeBorder(MulberryDesign.accent, style: StrokeStyle(lineWidth: 1.5, dash: [6, 5])))
        }.buttonStyle(.plain).disabled(busy)
    }

    private func presetCard(_ choice: WallpaperChoice) -> some View {
        let selected = library.choice.id == choice.id
        return Button { library.select(choice) } label: {
            Color.clear.aspectRatio(171 / 133, contentMode: .fit)
                .overlay { Image(choice.asset!).resizable().scaledToFill() }
                .overlay {
                    if selected {
                        ZStack {
                            Color.black.opacity(0.46)
                            Image(systemName: "checkmark").font(.system(size: 26, weight: .bold)).foregroundStyle(.white)
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: radius))
        }
        .buttonStyle(.plain).disabled(busy)
        .accessibilityLabel(choice.title).accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct WallpaperGalleryView: View {
    @ObservedObject var library: WallpaperLibrary
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var items: [CatalogWallpaper] = []
    @State private var cursor: String?
    @State private var loaded = false
    @State private var loading = false
    @State private var error: String?
    @State private var preview: CatalogWallpaper?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: textSize.isAccessibilitySize ? 1 : 2), spacing: 20) {
                    ForEach(items) { item in
                        Button { preview = item } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                GeometryReader { proxy in
                                    AsyncImage(url: item.thumbnailUrl) { phase in
                                        if let image = phase.image { image.resizable().scaledToFill() }
                                        else if phase.error != nil { Image(systemName: "photo").foregroundStyle(MulberryDesign.muted).frame(maxWidth: .infinity, maxHeight: .infinity) }
                                        else { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
                                    }.frame(width: proxy.size.width, height: proxy.size.height).clipped()
                                }.aspectRatio(0.65, contentMode: .fit).background(MulberryDesign.surface).clipShape(RoundedRectangle(cornerRadius: 16))
                                Text(item.title).font(MulberryDesign.font(14, weight: "Medium")).foregroundStyle(MulberryDesign.ink)
                            }
                        }.buttonStyle(.plain).accessibilityLabel(item.title)
                    }
                }.padding(20)
                if loading { ProgressView("Loading wallpapers…").padding() }
                if let error {
                    InlineError(message: error).padding(.horizontal, 20)
                    Button("Try again") { Task { await loadMore() } }.padding()
                } else if !loaded || cursor != nil {
                    Color.clear.frame(height: 1).id(cursor ?? "first-page").onAppear { Task { await loadMore() } }
                } else if items.isEmpty { ContentUnavailableView("No wallpapers yet", systemImage: "photo.on.rectangle") }
            }.background(MulberryDesign.background)
                .navigationTitle("Wallpapers").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
                .sheet(item: $preview) { item in
                    WallpaperGalleryPreview(item: item, library: library) { preview = nil; dismiss() }
                }
        }
    }

    private func loadMore() async {
        guard !loading, !loaded || cursor != nil else { return }
        loading = true; error = nil
        defer { loading = false }
        do {
            let page = try await AppRuntime.api().wallpapers(cursor: cursor)
            let existing = Set(items.map(\.id))
            items.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            cursor = page.nextCursor; loaded = true
        } catch { self.error = error.localizedDescription }
    }
}

private struct WallpaperGalleryPreview: View {
    let item: CatalogWallpaper
    @ObservedObject var library: WallpaperLibrary
    let onSelected: () -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    AsyncImage(url: item.previewUrl) { phase in
                        if let image = phase.image { image.resizable().scaledToFit() }
                        else if phase.error != nil { ContentUnavailableView("Preview unavailable", systemImage: "photo") }
                        else { ProgressView().frame(maxWidth: .infinity, minHeight: 300) }
                    }.clipShape(RoundedRectangle(cornerRadius: 24))
                    if !item.description.isEmpty { Text(item.description).foregroundStyle(MulberryDesign.muted) }
                    InlineError(message: library.errorMessage)
                }.padding(24)
            }.background(MulberryDesign.background)
                .navigationTitle(item.title).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(library.working) } }
                .safeAreaInset(edge: .bottom) {
                    PrimaryAction(title: "Use wallpaper", loading: library.working) {
                        Task { if await library.download(item) { onSelected() } }
                    }.padding(24).background(MulberryDesign.background)
                }
        }.interactiveDismissDisabled(library.working)
    }
}
