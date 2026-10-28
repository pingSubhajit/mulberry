import SwiftUI
import PhotosUI

struct WallpaperPickerView: View {
    @ObservedObject var library: WallpaperLibrary
    var onboarding = false
    let onSetup: () -> Void
    var onSkip: () -> Void = {}
    @Environment(\.scenePhase) private var phase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var photo: PhotosPickerItem?
    @State private var gallery = false
    @State private var loadingPhoto = false
    @State private var hasChanges = true
    /// A wide window puts the preview beside the choices instead of above them.
    @State private var wide = false
    private let radius: CGFloat = 15.38
    private var busy: Bool { library.working || loadingPhoto }

    var body: some View {
        Group {
            if wide {
                // Side by side, only the wallpaper grid scrolls; the preview and the upload tile stay put.
                VStack(alignment: .leading, spacing: 34) {
                    heading
                    HStack(alignment: .top, spacing: 56) {
                        preview(fitted: true)
                        VStack(spacing: 28) {
                            fixedControls
                            ScrollView { presets.padding(.bottom, 18) }
                        }
                        .frame(maxWidth: 440)
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 20).padding(.top, 34)
                // Wide enough that the device fills the height rather than running out of width.
                .readableColumn(1120)
                .frame(maxHeight: .infinity, alignment: .top)
            } else {
                ScrollView {
                    VStack(spacing: 34) {
                        heading
                        preview()
                        VStack(spacing: 28) {
                            fixedControls
                            presets
                        }
                    }
                    .padding(.horizontal, 20).padding(.top, 34).padding(.bottom, 18)
                    .readableColumn(600)
                }
            }
        }.background(MulberryDesign.background)
            .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height && $0.size.width >= 900 } action: { wide = $0 }
            .safeAreaInset(edge: .bottom) {
                if onboarding {
                    VStack(spacing: 10) {
                        PrimaryAction(title: "Set up lock screen", enabled: !busy, action: onSetup)
                        Button("Skip without setup", action: onSkip).frame(minHeight: 44)
                    }.readableColumn().padding(.horizontal, 24).padding(.vertical, 14).background(MulberryDesign.background)
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

    @ViewBuilder private var heading: some View {
        if onboarding {
            VStack(alignment: .leading, spacing: 10) {
                Text("Almost there").foregroundStyle(MulberryDesign.muted)
                // One line on iPad, where there is room; the phone keeps its break.
                ScreenHeading(title: sizeClass == .regular ? "Let’s set up your lock screen" : "Let’s set up your\nlock screen",
                              subtitle: "Let’s set up a device background for you that only you will see. The canvas will appear on top of it.")
            }
        }
    }

    @ViewBuilder private var fixedControls: some View {
        // iOS only: Shortcuts applies the wallpaper, so offer a manual run when the result is out of date.
        if !onboarding {
            PrimaryAction(title: "Update wallpaper", loading: library.working, enabled: hasChanges && !busy, action: onSetup)
        }
        InlineError(message: library.errorMessage)
        uploadTile
    }

    private var presets: some View {
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

    /// Lock Screen preview matching Android: a framed background with a placeholder clock. On iPad
    /// it takes the iPad's own portrait shape, since the wallpaper is for this device.
    /// A drawn device showing the lock screen, in this device's own shape, since the wallpaper is
    /// for this device. Fitted, it fills the height beside the choices.
    private func preview(fitted: Bool = false) -> some View {
        let pad = UIDevice.current.userInterfaceIdiom == .pad
        let screen = (try? WallpaperRenderSize.screen()) ?? (pad ? CGSize(width: 1640, height: 2360) : CGSize(width: 1206, height: 2622))
        return DeviceMockup(kind: pad ? .tablet : .phone,
                            screenAspect: min(screen.width, screen.height) / max(screen.width, screen.height)) { lockScreen(pad: pad) }
            .frame(height: fitted ? nil : (pad ? 440 : 416))
            .frame(maxHeight: fitted ? .infinity : nil)
            .accessibilityElement().accessibilityLabel("Selected wallpaper: \(library.choice.title)")
    }

    /// The system Lock Screen over the background: status icons, the date above a large clock, and
    /// the home indicator, with the flashlight and camera buttons on iPhone. Sizes follow the real
    /// screens' proportions, so it reads right at any preview size.
    private func lockScreen(pad: Bool) -> some View {
        GeometryReader { proxy in
            let width = proxy.size.width, height = proxy.size.height
            Image(uiImage: library.image).resizable().scaledToFill()
                .frame(width: width, height: height).clipped()
                .overlay(alignment: .topTrailing) {
                    HStack(spacing: width * 0.012) {
                        Image(systemName: "wifi")
                        Image(systemName: "battery.100")
                    }
                    .font(.system(size: height * (pad ? 0.012 : 0.017), weight: .semibold))
                    .padding(.top, height * (pad ? 0.012 : 0.024)).padding(.trailing, width * (pad ? 0.035 : 0.075))
                }
                .overlay(alignment: .top) {
                    VStack(spacing: 0) {
                        Text(Date.now, format: .dateTime.weekday(.wide).day().month(.wide))
                            .font(.system(size: height * (pad ? 0.019 : 0.023), weight: .semibold))
                        Text("9:41")
                            .font(.system(size: height * (pad ? 0.1 : 0.125), weight: .bold))
                            .tracking(-height * 0.002)
                    }
                    // Frosted, as close to the system's glass clock as SwiftUI allows: a light
                    // material fill that picks up the wallpaper behind the glyphs.
                    .foregroundStyle(.regularMaterial)
                    .environment(\.colorScheme, .light)
                    .padding(.top, height * (pad ? 0.06 : 0.1))
                }
                .overlay(alignment: .bottom) {
                    VStack(spacing: height * 0.035) {
                        if !pad {
                            HStack {
                                lockScreenButton("flashlight.off.fill", width: width, height: height)
                                Spacer()
                                lockScreenButton("camera.fill", width: width, height: height)
                            }
                            .padding(.horizontal, width * 0.11)
                        }
                        Capsule().frame(width: width * (pad ? 0.24 : 0.35), height: max(2, height * 0.006))
                    }
                    .padding(.bottom, height * 0.01)
                }
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.18), radius: 6)
                .accessibilityHidden(true)
                .overlay { if busy { ProgressView().padding(16).background(.regularMaterial, in: Circle()) } }
        }
    }

    private func lockScreenButton(_ symbol: String, width: CGFloat, height: CGFloat) -> some View {
        Image(systemName: symbol)
            .font(.system(size: height * 0.024, weight: .medium))
            .frame(width: width * 0.13, height: width * 0.13)
            .background(.black.opacity(0.32), in: Circle())
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

/// A drawn device: black glass bezel, a metal edge, display corners that follow the bezel, and
/// the front camera where the real one sits: a Dynamic Island on iPhone, the long side on iPad Air.
private struct DeviceMockup<Screen: View>: View {
    enum Kind { case phone, tablet }
    let kind: Kind
    /// The display's width over its height, in portrait.
    let screenAspect: CGFloat
    @ViewBuilder let screen: () -> Screen

    /// Bezel and corner sizes as shares of the device's width, close to the real hardware.
    private var bezel: CGFloat { kind == .phone ? 0.032 : 0.045 }
    private var corner: CGFloat { kind == .phone ? 0.16 : 0.075 }

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let inset = width * bezel
            let radius = width * corner
            ZStack {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(Color(white: 0.05))
                    .overlay {
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .strokeBorder(LinearGradient(colors: [Color(white: 0.62), Color(white: 0.24), Color(white: 0.5)],
                                                         startPoint: .topLeading, endPoint: .bottomTrailing),
                                          lineWidth: max(1.5, width * 0.007))
                    }
                screen()
                    .overlay(alignment: .top) {
                        if kind == .phone {
                            Capsule().fill(.black)
                                .frame(width: width * 0.31, height: width * 0.09)
                                .padding(.top, width * 0.027)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: radius - inset, style: .continuous))
                    .padding(inset)
                if kind == .tablet {
                    Circle().fill(Color(white: 0.18))
                        .frame(width: inset * 0.34, height: inset * 0.34)
                        .position(x: inset / 2, y: proxy.size.height / 2)
                }
            }
        }
        // The outer shape: the display plus a bezel of the same width all round.
        .aspectRatio(1 / ((1 - 2 * bezel) / screenAspect + 2 * bezel), contentMode: .fit)
        .shadow(color: .black.opacity(0.28), radius: 22, y: 12)
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
