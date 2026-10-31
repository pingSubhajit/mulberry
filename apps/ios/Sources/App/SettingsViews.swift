import SwiftUI
import PhotosUI
import WebKit

struct SettingsView: View {
    @ObservedObject var model: MulberryModel
    @ObservedObject var library: WallpaperLibrary
    let onWallpaperSetup: () -> Void
    @Environment(\.dismiss) private var dismiss
    #if DEBUG
    @AppStorage("developerOptions") private var developerOptions = false
    #endif
    @State private var logout = false
    @State private var deleteAccount = false
    @State private var deletingAccount = false
    @State private var streakOpen = false
    @State private var inviteOpen = false
    @State private var visibilityOpen = false

    private var profile: BootstrapState? { model.bootstrap }
    private var paired: Bool { profile?.isPaired == true }
    private var partnerName: String { profile?.partnerDisplayName?.nonEmpty ?? "Your partner" }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    SettingsHero(model: model, streaks: model.streaks, onStreak: { streakOpen = true }, onInvite: { inviteOpen = true })
                }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)

                if let visibility = PartnerVisibility(paired: paired, status: profile?.partnerWallpaperStatus), visibility.needsAttention {
                    Section {
                        PartnerVisibilityBanner(label: visibility.title(partnerName)) { visibilityOpen = true }
                    }
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                }
                if let error = model.canvasSaveError {
                    Section {
                        Label(error, systemImage: "exclamationmark.icloud").foregroundStyle(MulberryDesign.accent)
                        Button("Retry saving drawing") { model.retryCanvasSave?() }
                    }
                }

                Section {
                    NavigationLink { ProfileSettingsView(model: model) } label: {
                        SettingsRow("Profile", symbol: "person.fill", tint: .blue)
                    }
                    NavigationLink { PartnerSettingsView(model: model) } label: {
                        SettingsRow("Partner", symbol: "heart.fill", tint: .pink)
                    }
                    LabeledContent {
                        SyncStatus(connection: paired ? model.drawingConnection : nil)
                    } label: {
                        SettingsRow("Sync", symbol: "arrow.triangle.2.circlepath", tint: .green)
                    }
                }

                Section {
                    Toggle(isOn: Binding(get: { library.showDrawings }, set: { library.setShowDrawings($0) })) {
                        SettingsRow("Show doodles on wallpaper", symbol: "scribble.variable", tint: .indigo)
                    }
                    Button(action: onWallpaperSetup) {
                        SettingsRow("Wallpaper setup", symbol: "wand.and.sparkles", tint: .teal)
                    }
                    .foregroundStyle(MulberryDesign.ink)
                } header: { Text("Wallpaper") }

                Section("Canvas") {
                    Toggle(isOn: Binding(get: { profile?.canvasStrokeRenderMode == "dry" },
                                         set: { enabled in Task { await model.setDryBrush(enabled) } })) {
                        SettingsRow("Dry brush strokes", symbol: "paintbrush.pointed.fill", tint: .orange)
                    }
                    .disabled(!paired || model.busy)
                }

                Section {
                    NavigationLink { AboutView() } label: { SettingsRow("About", symbol: "info", tint: .gray) }
                    NavigationLink { FeedbackView() } label: { SettingsRow("Send feedback", symbol: "bubble.left.fill", tint: .purple) }
                    #if DEBUG
                    if developerOptions {
                        NavigationLink { DeveloperSettingsView(model: model) } label: { SettingsRow("Developer options", symbol: "hammer.fill", tint: .gray) }
                    }
                    #endif
                }

                if let error = model.errorMessage { Section { InlineError(message: error) }.listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }

                Section {
                    Button("Log Out", role: .destructive) { logout = true }
                        .frame(maxWidth: .infinity)
                        .disabled(model.busy)
                } footer: {
                    VStack(spacing: 14) {
                        if deletingAccount { ProgressView().controlSize(.small) }
                        else {
                            Button("Delete your account") { deleteAccount = true }
                                .font(.footnote).foregroundStyle(.red)
                                .disabled(model.busy)
                        }
                        Text("Mulberry \(AppVersion.display)")
                    }
                    .frame(maxWidth: .infinity).padding(.top, 8)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(role: .close) { dismiss() }.tint(.primary) } }
            .confirmationDialog("Log out of Mulberry?", isPresented: $logout, titleVisibility: .visible) {
                Button("Log Out", role: .destructive) { Task { await model.signOut() } }
            } message: { Text("Your account and pairing stay saved.") }
            .alert("Delete your Mulberry account?", isPresented: $deleteAccount) {
                Button("Delete Account", role: .destructive) {
                    deletingAccount = true
                    Task { await model.deleteAccount(); deletingAccount = false }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(paired ? "This also unpairs you from \(profile?.partnerDisplayName?.nonEmpty ?? "your partner") and erases your shared canvas and streak. This can’t be undone." : "This can’t be undone.")
            }
        }
        .sheet(isPresented: $streakOpen) {
            StreakSheet(streaks: model.streaks) {
                if let api = try? model.api { await model.streaks.refresh(api: api) }
            }
        }
        .sheet(isPresented: $inviteOpen) { InviteShareView(model: model) }
        .sheet(isPresented: $visibilityOpen) {
            if let visibility = PartnerVisibility(paired: paired, status: profile?.partnerWallpaperStatus) {
                PartnerVisibilitySheet(visibility: visibility, partnerName: partnerName) { visibilityOpen = false }
            }
        }
    }
}

// MARK: Root pieces

/// Apple's Settings row: a white glyph on a small coloured tile, aligned with the row text.
struct SettingsRow: View {
    let title: String
    let symbol: String
    let tint: Color
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 30

    init(_ title: String, symbol: String, tint: Color) {
        self.title = title; self.symbol = symbol; self.tint = tint
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: symbol)
                .font(.system(size: tile * 0.5, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: tile, height: tile)
                .background(tint.gradient, in: .rect(cornerRadius: tile * 0.27, style: .continuous))
                .accessibilityHidden(true)
        }
    }
}

/// The couple at the top of Settings: both faces, who you are together with and since when.
private struct SettingsHero: View {
    @ObservedObject var model: MulberryModel
    @ObservedObject var streaks: StreakModel
    let onStreak: () -> Void
    let onInvite: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let profile = model.bootstrap
        let paired = profile?.isPaired == true
        VStack(spacing: 14) {
            avatars(profile, paired: paired)
            VStack(spacing: 4) {
                Text(profile?.userDisplayName?.nonEmpty ?? "Mulberry")
                    .font(MulberryDesign.font(26, weight: "Bold", relativeTo: .title))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                subtitle(profile, paired: paired)
                    .font(.subheadline).foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            if paired {
                // Side by side until accessibility sizes, where the units would truncate.
                AnyLayout(typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))) {
                    StatTile(value: daysTogether(profile), unit: "days together") {
                        Image(systemName: "heart.fill").foregroundStyle(MulberryDesign.accent)
                    }
                    Button(action: onStreak) {
                        StatTile(value: streaks.streak?.currentStreakDays ?? profile?.currentStreakDays ?? 0, unit: "day streak") {
                            Image("HomeStreakBolt").resizable().scaledToFit().frame(width: 12, height: 17)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Opens your streak for this week")
                }
            } else {
                Button(action: onInvite) {
                    Label("Invite your partner", systemImage: "person.badge.plus").foregroundStyle(.white)
                }
                .buttonStyle(.glassProminent).tint(MulberryDesign.accent).controlSize(.large)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8).padding(.bottom, 12)
    }

    /// Android's hero: a ringed you, your partner beside you, and doodled hearts and an arrow between.
    private func avatars(_ profile: BootstrapState?, paired: Bool) -> some View {
        ZStack {
            Group {
                if paired {
                    HeroDecorations().offset(x: 6, y: 2)
                } else {
                    HeroArrow().offset(x: 26, y: -54)
                }
            }
            .accessibilityHidden(true)
            HeroAvatar(url: profile?.userPhotoUrl, name: profile?.userDisplayName, size: 100, border: 5)
                .offset(x: -60)
            Group {
                if paired {
                    HeroAvatar(url: profile?.partnerPhotoUrl, name: profile?.partnerDisplayName, size: 82, border: 0)
                } else {
                    Button(action: onInvite) {
                        Text("?").font(MulberryDesign.font(58, weight: "SemiBold"))
                            .foregroundStyle(MulberryDesign.accent)
                            .frame(width: 82, height: 82)
                            .background(HeroAvatar.light.opacity(0.55), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Invite your partner")
                }
            }
            .offset(x: 58, y: -1)
        }
        .frame(maxWidth: .infinity).frame(height: 138)
    }

    @ViewBuilder private func subtitle(_ profile: BootstrapState?, paired: Bool) -> some View {
        if paired, let partner = profile?.partnerDisplayName?.nonEmpty {
            if let since = profile?.anniversaryDate.flatMap(AnniversaryDay.date) {
                Text("with **\(partner)** · since \(since.formatted(date: .abbreviated, time: .omitted))")
            } else {
                Text("with **\(partner)**")
            }
        } else {
            Text("Waiting for your partner to join")
        }
    }

    private func daysTogether(_ profile: BootstrapState?) -> Int {
        guard let paired = profile?.pairedAt.flatMap(ServerDate.parse) else { return 0 }
        return max(0, Calendar.current.dateComponents([.day], from: paired, to: .now).day ?? 0)
    }
}

private struct HeroAvatar: View {
    static let light = Color(red: 247 / 255, green: 221 / 255, blue: 226 / 255)
    let url: String?
    let name: String?
    let size: CGFloat
    let border: CGFloat

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Text(String(name?.trimmingCharacters(in: .whitespaces).prefix(1) ?? "?").uppercased())
                .font(MulberryDesign.font(size * 0.4, weight: "SemiBold"))
                .foregroundStyle(.white)
        }
        .frame(width: size - border * 2, height: size - border * 2)
        .background(MulberryDesign.accent)
        .clipShape(Circle())
        .frame(width: size, height: size)
        .background(Self.light, in: Circle())
        .overlay { if border > 0 { Circle().strokeBorder(MulberryDesign.accent, lineWidth: border) } }
        .accessibilityLabel(name ?? "Profile photo")
    }
}

/// Five tilted hearts and the arrow, positioned as in Android's Figma export.
private struct HeroDecorations: View {
    private let hearts: [(size: CGFloat, angle: Double, x: CGFloat, y: CGFloat)] = [
        (23, 12.8531, 5.11639, 0), (23, -13.7174, 31.601, 25.0552), (21, 26.2702, 41.2947, 80),
        (23, -19.2486, 4.85172, 101.434), (38, 9.03514, 27.2195, 94.252),
    ]

    var body: some View {
        ZStack(alignment: .topLeading) {
            HeroArrow().offset(x: 18, y: 15)
            ForEach(hearts.indices, id: \.self) { index in
                let heart = hearts[index]
                Image("SettingsHeroHeart").resizable().scaledToFit()
                    .frame(width: heart.size, height: heart.size)
                    .rotationEffect(.degrees(heart.angle))
                    .offset(x: heart.x, y: heart.y)
            }
        }
        .frame(width: 66, height: 138, alignment: .topLeading)
    }
}

private struct HeroArrow: View {
    var body: some View {
        Image("SettingsHeroArrow").resizable().frame(width: 47, height: 13).foregroundStyle(.primary)
    }
}

private struct StatTile<Icon: View>: View {
    let value: Int
    let unit: String
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                icon()
                Text(value, format: .number)
                    .font(MulberryDesign.font(22, weight: "SemiBold", relativeTo: .title2))
                    .contentTransition(.numericText(value: Double(value)))
            }
            Text(unit).font(.footnote).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 22, style: .continuous))
        .contentShape(.rect(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(value) \(unit)")
    }
}

private struct SyncStatus: View {
    /// Nil while unpaired, when there is nothing to sync with.
    let connection: DrawingConnection?

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8).accessibilityHidden(true)
            Text(label)
        }
        .foregroundStyle(.secondary)
    }

    private var label: String {
        switch connection {
        case .connected: "Connected"
        case .connecting: "Connecting"
        case .offline: "Offline"
        case .pairingChanged: "Updating"
        case nil: "Not paired"
        }
    }

    private var color: Color {
        switch connection {
        case .connected: .green
        case .connecting, .pairingChanged: .yellow
        case .offline, nil: .gray
        }
    }
}

/// Whether the partner's wallpaper can show new drawings, as their device last reported.
private enum PartnerVisibility {
    case syncOff, wallpaperNotSet, fine

    init?(paired: Bool, status: PartnerWallpaperStatus?) {
        guard paired, let status else { return nil }
        if !status.wallpaperSyncEnabled { self = .syncOff }
        else if !status.wallpaperSelectedOnHome && !status.wallpaperSelectedOnLock { self = .wallpaperNotSet }
        else { self = .fine }
    }

    var needsAttention: Bool { self != .fine }

    func title(_ partner: String) -> String {
        switch self {
        case .syncOff: "\(partner) can’t see your latest drawings"
        case .wallpaperNotSet: "\(partner) can’t see your drawings"
        case .fine: "\(partner) can see your latest drawings"
        }
    }

    func detail(_ partner: String) -> String {
        switch self {
        case .syncOff: "“Show doodles on wallpaper” is off on their phone. Ask \(partner) to turn it on in Settings."
        case .wallpaperNotSet: "Mulberry isn’t their wallpaper yet. Ask \(partner) to finish wallpaper setup."
        case .fine: "New drawings reach their wallpaper."
        }
    }
}

/// Android's solid mulberry banner, so the warning reads the same on both platforms.
private struct PartnerVisibilityBanner: View {
    let label: String
    let action: () -> Void
    @ScaledMetric(relativeTo: .body) private var icon: CGFloat = 21
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image("PartnerVisibilitySync").resizable().scaledToFit().frame(width: icon, height: icon)
                Text("Can’t see your latest drawings")
                    .font(MulberryDesign.font(16, weight: "SemiBold", relativeTo: .body))
                    .lineLimit(typeSize.isAccessibilitySize ? nil : 1).minimumScaleFactor(0.8)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).opacity(0.6)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 24).padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(MulberryDesign.accent, in: .rect(cornerRadius: 15.38, style: .continuous))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isButton)
    }
}

private struct PartnerVisibilitySheet: View {
    let visibility: PartnerVisibility
    let partnerName: String
    let onClose: () -> Void
    /// The sheet is as tall as its text, which grows with Dynamic Type.
    @State private var height: CGFloat = 280

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(visibility.title(partnerName))
                .font(MulberryDesign.font(22, weight: "SemiBold", relativeTo: .title2))
                .accessibilityAddTraits(.isHeader)
            Text(visibility.detail(partnerName)).foregroundStyle(.secondary)
            PrimaryAction(title: "Got it", action: onClose).padding(.top, 12)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(24).padding(.top, 8)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        .presentationDetents([.height(height)])
        // On iPad, a form sheet just tall enough for the text.
        .presentationSizing(.form.fitted(horizontal: false, vertical: true))
        .presentationBackground(MulberryDesign.background)
    }
}

private enum AppVersion {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1" }
    static var display: String { "\(version) (\(build))" }
}

/// Anniversaries are calendar days, so they are read without a time zone shift.
private enum AnniversaryDay {
    private static let formatter: DateFormatter = {
        let result = DateFormatter()
        result.locale = Locale(identifier: "en_US_POSIX")
        result.calendar = Calendar(identifier: .gregorian)
        result.dateFormat = "yyyy-MM-dd"
        return result
    }()
    static func date(_ value: String) -> Date? { formatter.date(from: value) }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

// MARK: Editable avatar

/// A Contacts-style photo header: the picture with a camera badge that opens the photo picker.
private struct EditableAvatar<Avatar: View>: View {
    @Binding var selection: PhotosPickerItem?
    let processing: Bool
    let enabled: Bool
    @ViewBuilder let avatar: () -> Avatar

    var body: some View {
        PhotosPicker(selection: $selection, matching: .images) {
            avatar()
                .overlay { if processing { Circle().fill(.black.opacity(0.4)); ProgressView().tint(.white) } }
                .overlay(alignment: .bottomTrailing) {
                    if enabled {
                        Image(systemName: "camera.fill")
                            .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(MulberryDesign.accent, in: Circle())
                            .padding(3).background(Color(.systemGroupedBackground), in: Circle())
                    }
                }
        }
        .buttonStyle(.plain)
        .disabled(!enabled || processing)
        .accessibilityLabel("Change photo")
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

// MARK: Profile

struct ProfileSettingsView: View {
    @ObservedObject var model: MulberryModel
    @State private var name = ""
    @State private var photo: PhotosPickerItem?
    @State private var processingPhoto = false
    @State private var photoError: String?
    @Environment(\.dismiss) private var dismiss

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        let profile = model.bootstrap
        Form {
            Section {
                EditableAvatar(selection: $photo, processing: processingPhoto, enabled: !model.busy) {
                    ProfileAvatar(url: profile?.userPhotoUrl, name: profile?.userDisplayName, size: 110, ringWidth: 0)
                }
            }
            .listRowBackground(Color.clear)
            Section {
                LabeledContent("Name") {
                    TextField("Your name", text: $name)
                        .multilineTextAlignment(.trailing)
                        .textContentType(.nickname).textInputAutocapitalization(.words)
                        .submitLabel(.done)
                }
                LabeledContent("Google account") {
                    Text(profile?.userEmail ?? "").textSelection(.enabled)
                }
            }
            if let error = photoError ?? model.errorMessage { Section { InlineError(message: error) }.listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
        }
        .navigationTitle("Profile").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(role: .confirm) {
                    Task { await model.updateName(trimmed); if model.errorMessage == nil { dismiss() } }
                }
                .disabled(model.busy || processingPhoto || trimmed.isEmpty || trimmed == model.bootstrap?.userDisplayName)
            }
        }
        .onAppear { name = model.bootstrap?.userDisplayName ?? "" }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            processingPhoto = true; photoError = nil
            let user = model.userId
            Task {
                defer { processingPhoto = false; photo = nil }
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else { throw WallpaperImageError.invalidImage }
                    let jpeg = try await ImagePreparation.profileJPEG(data)
                    guard user == model.userId else { return }
                    await model.updatePhoto(jpeg)
                } catch { photoError = error.localizedDescription }
            }
        }
    }
}

// MARK: Partner

struct PartnerSettingsView: View {
    @ObservedObject var model: MulberryModel
    @State private var name = ""
    @State private var anniversary = ""
    @State private var photo: PhotosPickerItem?
    @State private var jpeg: Data?
    @State private var processingPhoto = false
    @State private var photoError: String?
    @State private var disconnect = false
    @State private var invite = false
    @Environment(\.dismiss) private var dismiss

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        let profile = model.bootstrap
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let editable = profile?.canEditPartner(at: context.date) == true
            Form {
                Section {
                    EditableAvatar(selection: $photo, processing: processingPhoto, enabled: editable && !model.busy) {
                        if let jpeg, let image = UIImage(data: jpeg) {
                            Image(uiImage: image).resizable().scaledToFill().frame(width: 110, height: 110).clipShape(Circle())
                        } else {
                            ProfileAvatar(url: profile?.partnerPhotoUrl, name: profile?.partnerDisplayName, size: 110, ringWidth: 0)
                        }
                    }
                }
                .listRowBackground(Color.clear)

                Section {
                    LabeledContent("Name") {
                        TextField("Partner’s name", text: $name)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.words).submitLabel(.done)
                    }
                    if let date = AnniversaryDay.date(anniversary) {
                        DatePicker("Anniversary", selection: Binding(get: { date }, set: { anniversary = ServerDate.localDay($0) }),
                                   in: ...Date(), displayedComponents: .date)
                    } else {
                        Button { anniversary = ServerDate.localDay() } label: {
                            LabeledContent("Anniversary") { Text("Add").foregroundStyle(MulberryDesign.accent) }
                        }
                        .foregroundStyle(MulberryDesign.ink)
                    }
                } footer: {
                    if let unlock = profile?.partnerProfileUnlockDate, !editable {
                        Label("You can edit these again \(unlock.formatted(.relative(presentation: .named))).", systemImage: "lock.fill")
                    } else {
                        Text("Name, photo and anniversary can change once every 72 hours.")
                    }
                }
                .disabled(!editable || model.busy)

                if profile?.isPaired == true {
                    Section {
                        if let paired = profile?.pairedAt.flatMap(ServerDate.parse) {
                            LabeledContent("Paired") { Text(paired, format: .dateTime.day().month(.abbreviated).year()) }
                        }
                        LabeledContent("Daily streak") { Text("\(model.streaks.streak?.currentStreakDays ?? profile?.currentStreakDays ?? 0) days") }
                    }
                    Section {
                        Button("Unpair", role: .destructive) { disconnect = true }
                            .frame(maxWidth: .infinity).disabled(model.busy)
                    }
                } else {
                    Section {
                        Button("Invite your partner", systemImage: "person.badge.plus") { invite = true }
                    }
                }
                if let error = photoError ?? model.errorMessage { Section { InlineError(message: error) }.listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .confirm) {
                        Task { await model.updatePartner(name: trimmed, anniversary: anniversary, jpeg: jpeg); if model.errorMessage == nil { dismiss() } }
                    }
                    .disabled(!editable || model.busy || processingPhoto || !changed || trimmed.isEmpty || anniversary.isEmpty)
                }
            }
        }
        .navigationTitle("Partner").navigationBarTitleDisplayMode(.inline)
        .onAppear { name = model.bootstrap?.partnerDisplayName ?? ""; anniversary = model.bootstrap?.anniversaryDate ?? "" }
        .confirmationDialog("Unpair from \(model.bootstrap?.partnerDisplayName?.nonEmpty ?? "your partner")?", isPresented: $disconnect, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { Task { await model.disconnect(); if model.errorMessage == nil { dismiss() } } }
        } message: { Text("Your profile stays, but shared drawing stops until you pair again.") }
        .sheet(isPresented: $invite) { InviteShareView(model: model) }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            processingPhoto = true; photoError = nil
            Task {
                defer { processingPhoto = false; photo = nil }
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else { throw WallpaperImageError.invalidImage }
                    jpeg = try await ImagePreparation.profileJPEG(data)
                } catch { photoError = error.localizedDescription }
            }
        }
    }

    private var changed: Bool { name != model.bootstrap?.partnerDisplayName || anniversary != model.bootstrap?.anniversaryDate || jpeg != nil }
}

// MARK: About

struct AboutView: View {
    #if DEBUG
    @AppStorage("developerOptions") private var developerOptions = false
    @State private var taps = 0
    #endif
    @State private var legal: LegalDocument?

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Image("BrandMark").resizable().scaledToFit().frame(width: 64, height: 64)
                        .padding(18)
                        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 26, style: .continuous))
                        .shadow(color: MulberryDesign.accent.opacity(0.25), radius: 20, y: 8)
                        .accessibilityHidden(true)
                    Text("Mulberry").font(MulberryDesign.font(28, weight: "Bold", relativeTo: .title))
                    Text("A shared canvas that lives on your wallpaper.")
                        .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 8)
            }
            .listRowBackground(Color.clear)

            #if DEBUG
            Section {
                // Seven taps on the version unlock developer options, as on Android. Debug builds
                // only: App Review rejects hidden features in the App Store build.
                Button { taps += 1; if taps >= 7 { developerOptions = true } } label: {
                    LabeledContent("Version", value: AppVersion.version)
                }
                .foregroundStyle(MulberryDesign.ink)
                LabeledContent("Build", value: AppVersion.build)
            } footer: {
                if developerOptions { Text("Developer options are on.") }
            }
            #else
            Section {
                LabeledContent("Version", value: AppVersion.version)
                LabeledContent("Build", value: AppVersion.build)
            }
            #endif

            Section {
                Button { legal = .privacy } label: { SettingsRow("Privacy Policy", symbol: "hand.raised.fill", tint: .blue) }
                Button { legal = .terms } label: { SettingsRow("Terms of Use", symbol: "doc.text.fill", tint: .gray) }
            }
            .foregroundStyle(MulberryDesign.ink)

            Section {
                Link(destination: URL(string: "https://mulberry.my")!) { ExternalRow(title: "Website", symbol: "globe", tint: .teal) }
                Link(destination: URL(string: "https://github.com/pingSubhajit/mulberry")!) {
                    ExternalRow(title: "Source code", symbol: "chevron.left.forwardslash.chevron.right", tint: .indigo)
                }
            }
        }
        .navigationTitle("About").navigationBarTitleDisplayMode(.inline)
        .sheet(item: $legal) { LegalDocumentView(document: $0) }
    }
}

private struct ExternalRow: View {
    let title: String
    let symbol: String
    let tint: Color
    var body: some View {
        HStack {
            SettingsRow(title, symbol: symbol, tint: tint).foregroundStyle(MulberryDesign.ink)
            Spacer()
            Image(systemName: "arrow.up.right").font(.footnote.weight(.semibold)).foregroundStyle(Color(.tertiaryLabel))
                .accessibilityHidden(true)
        }
    }
}

#if DEBUG
/// Where the canvas is in its sync, following the drawing as it changes.
private struct CanvasDiagnostics: View {
    @ObservedObject var canvas: CanvasModel
    var body: some View {
        LabeledContent("Canvas revision", value: String(canvas.state.revision))
        LabeledContent("Pending edits", value: String(canvas.pendingEdits))
    }
}

private struct DeveloperSettingsView: View {
    @ObservedObject var model: MulberryModel
    @AppStorage("developerOptions") private var enabled = false
    @State private var status: String?
    var body: some View {
        Form {
            Section { Toggle("Developer options", isOn: $enabled) }
            Section("App diagnostics") {
                LabeledContent("Environment", value: Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true ? "Development" : "Production")
                LabeledContent("API", value: Bundle.main.object(forInfoDictionaryKey: "MulberryAPIBaseURL") as? String ?? "Unavailable")
                LabeledContent("User ID", value: model.userId ?? "Unavailable")
                LabeledContent("Pair session", value: model.bootstrap?.pairSessionId ?? "Unpaired")
                CanvasDiagnostics(canvas: model.canvas)
            }.font(.caption).textSelection(.enabled)
            if enabled {
                Section("Sync and wallpaper") {
                    Button("Force sync now") { Task { await model.setActive(false); await model.setActive(true) } }
                    Button("Regenerate wallpaper snapshot") {
                        Task {
                            do { _ = try await ProductWallpaperService.shared.prepare(); status = "Wallpaper image prepared. Run the Shortcut to apply it." }
                            catch { status = error.localizedDescription }
                        }
                    }
                    if let status { Text(status).font(.callout) }
                }
                Section {
                    Button("Send test error") { CrashReporting.testError(); status = "Test error sent to Crashlytics." }
                    Button("Crash now", role: .destructive) { CrashReporting.testCrash() }
                } header: { Text("Crash reporting") } footer: { Text("A crash is reported when Mulberry next opens.") }
            }
        }.navigationTitle("Developer options").navigationBarTitleDisplayMode(.inline)
    }
}
#endif

private struct FeedbackView: View {
    @State private var url: URL?
    @State private var error: String?
    var body: some View {
        Group {
            if let url { FeedbackWebView(url: url, error: $error) }
            else if error == nil { ProgressView("Opening feedback…") }
            if let error {
                VStack(spacing: 16) { InlineError(message: error); Button("Try again") { Task { await load() } } }.padding(24)
            }
        }.navigationTitle("Send feedback").navigationBarTitleDisplayMode(.inline).task { await load() }
    }
    private func load() async {
        error = nil; url = nil
        do {
            let token = try await AppRuntime.api().cannyToken()
            var components = URLComponents(string: "https://webview.canny.io")!
            components.queryItems = [.init(name: "boardToken", value: "6a631c96-1dd3-3c43-0653-b53d1edd0828"), .init(name: "ssoToken", value: token), .init(name: "theme", value: "auto")]
            url = components.url
        } catch { self.error = error.localizedDescription }
    }
}

private struct FeedbackWebView: UIViewRepresentable {
    let url: URL
    @Binding var error: String?
    func makeCoordinator() -> Coordinator { Coordinator(error: $error) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration(); configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: url)); return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {}
    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var error: String?
        init(error: Binding<String?>) { _error = error }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { self.error = "Feedback could not load. Please try again." }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { self.error = "Feedback could not load. Please try again." }
    }
}
