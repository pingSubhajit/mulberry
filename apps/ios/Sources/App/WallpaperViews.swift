import SwiftUI
import PhotosUI

struct WallpaperPickerView: View {
    @ObservedObject var library: WallpaperLibrary
    var onboarding = false
    let onSetup: () -> Void
    var onSkip: () -> Void = {}
    @State private var photo: PhotosPickerItem?
    @State private var gallery = false
    @State private var loadingPhoto = false

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                if onboarding {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Almost there").foregroundStyle(MulberryDesign.muted)
                        ScreenHeading(title: "Let’s set up your\nlock screen", subtitle: "Let’s set up a device background for you that only you will see. The canvas will appear on top of it.")
                    }
                }
                Image(uiImage: library.image).resizable().scaledToFill()
                    .frame(width: 214, height: 340).clipShape(RoundedRectangle(cornerRadius: 28))
                    .accessibilityLabel("Selected wallpaper: \(library.choice.title)")
                    .overlay(alignment: .center) {
                        if library.working || loadingPhoto { ProgressView().padding(16).background(.regularMaterial, in: Circle()) }
                    }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(WallpaperChoice.builtIns) { choice in
                            Button { library.select(choice) } label: {
                                Image(choice.asset!).resizable().scaledToFill().frame(width: 62, height: 85)
                                    .clipShape(RoundedRectangle(cornerRadius: 12)).padding(3)
                                    .overlay(RoundedRectangle(cornerRadius: 15).stroke(library.choice.id == choice.id ? MulberryDesign.accent : .clear, lineWidth: 2))
                            }.buttonStyle(.plain).disabled(library.working || loadingPhoto)
                                .accessibilityLabel(choice.title).accessibilityAddTraits(library.choice.id == choice.id ? .isSelected : [])
                        }
                    }.padding(3)
                }
                ViewThatFits(in: .horizontal) {
                    HStack { photoButton; Spacer(minLength: 12); galleryButton }
                    VStack(spacing: 4) { photoButton; galleryButton }
                }.font(MulberryDesign.font(14))
                InlineError(message: library.errorMessage)
                if !onboarding {
                    Text("Your background stays on this phone. Only your drawings are shared with your partner.")
                        .font(.callout).foregroundStyle(MulberryDesign.muted).frame(maxWidth: .infinity, alignment: .leading)
                }
            }.padding(24)
        }.background(MulberryDesign.background)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 10) {
                    PrimaryAction(title: onboarding ? "Set up lock screen" : "Update wallpaper", enabled: !library.working && !loadingPhoto, action: onSetup)
                    if onboarding { Button("Skip without setup", action: onSkip).frame(minHeight: 44) }
                }.padding(.horizontal, 24).padding(.vertical, 14).background(MulberryDesign.background)
            }
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $gallery) { WallpaperGalleryView(library: library) }
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

    private var photoButton: some View {
        PhotosPicker(selection: $photo, matching: .images) { Text("Upload from gallery").frame(minHeight: 44) }
            .disabled(library.working || loadingPhoto)
    }
    private var galleryButton: some View {
        Button("View more wallpapers") { gallery = true }.frame(minHeight: 44).disabled(library.working || loadingPhoto)
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
