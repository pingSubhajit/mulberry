import SwiftUI
import PhotosUI
import WebKit

struct SettingsView: View {
    @ObservedObject var model: MulberryModel
    @ObservedObject var library: WallpaperLibrary
    var isTab = false
    let onWallpaperSetup: () -> Void
    @Environment(\.dismiss) private var dismiss
    @AppStorage("developerOptions") private var developerOptions = false
    @State private var logout = false
    @State private var streakOpen = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink { ProfileSettingsView(model: model) } label: {
                        HStack(spacing: 16) {
                            ProfileAvatar(url: model.bootstrap?.userPhotoUrl, name: model.bootstrap?.userDisplayName, size: 48)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Profile info")
                                Text(model.bootstrap?.userDisplayName ?? "").font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 4)
                    }
                    if model.bootstrap?.isPaired == true {
                        Button { streakOpen = true } label: { Label("Daily streak", systemImage: "bolt.fill") }
                    }
                    NavigationLink { PartnerSettingsView(model: model) } label: { Label("Partner settings", systemImage: "person.2") }
                    LabeledContent { Text(model.bootstrap?.isPaired == true ? connectionLabel : "Unpaired").foregroundStyle(.secondary) } label: { Label("Sync status", systemImage: "arrow.triangle.2.circlepath") }
                }
                if let error = model.canvasSaveError {
                    Section("Drawing sync") {
                        Text(error).foregroundStyle(MulberryDesign.accent)
                        Button("Retry saving drawing") { model.retryCanvasSave?() }
                    }
                }
                Section {
                    Toggle("Show doodles on wallpaper", isOn: Binding(get: { library.showDrawings }, set: { library.setShowDrawings($0) }))
                    Toggle("Dry brush strokes", isOn: Binding(get: { model.bootstrap?.canvasStrokeRenderMode == "dry" }, set: { enabled in Task { await model.setDryBrush(enabled) } }))
                        .disabled(model.bootstrap?.isPaired != true || model.busy)
                    Button("Wallpaper setup", action: onWallpaperSetup)
                } footer: { Text("Wallpaper changes take effect when your Shortcut next runs.") }
                Section {
                    NavigationLink { AboutView() } label: { Label("About", systemImage: "info.circle") }
                    Button {} label: { Label("What’s new", systemImage: "sparkles").foregroundStyle(MulberryDesign.ink) }
                        .accessibilityHint("Not available in this version")
                    NavigationLink { FeedbackView() } label: { Label("Send feedback", systemImage: "bubble.left") }
                }
                if developerOptions {
                    Section { NavigationLink("Developer Options") { DeveloperSettingsView(model: model) } }
                }
                if let error = model.errorMessage { Section { InlineError(message: error) } }
                Section { Button("Log out", role: .destructive) { logout = true }.disabled(model.busy) }
            }.scrollContentBackground(.hidden).background(MulberryDesign.background)
                .navigationTitle(isTab ? "Profile" : "Settings")
                .toolbar { if !isTab { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } } }
                .confirmationDialog("Log out of Mulberry?", isPresented: $logout, titleVisibility: .visible) {
                    Button("Log out", role: .destructive) { Task { await model.signOut() } }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("Your account and pairing will stay saved. Sign in again to keep drawing together.") }
        }
        .sheet(isPresented: $streakOpen) {
            StreakSheet(streaks: model.streaks) {
                if let api = try? model.api { await model.streaks.refresh(api: api) }
            }
        }
    }
    private var connectionLabel: String {
        switch model.drawingConnection { case .connected: "Connected"; case .connecting: "Connecting"; case .offline: "Offline"; case .pairingChanged: "Updating" }
    }
}

private struct ProfileSettingsView: View {
    @ObservedObject var model: MulberryModel
    @State private var name = ""
    @State private var photo: PhotosPickerItem?
    @State private var processingPhoto = false
    @State private var photoError: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let profile = model.bootstrap
        Form {
            Section {
                HStack { Spacer(); PhotosPicker(selection: $photo, matching: .images) {
                    VStack(spacing: 12) {
                        ProfileAvatar(url: profile?.userPhotoUrl, name: profile?.userDisplayName, size: 100)
                        if processingPhoto { ProgressView() } else { Text("Change profile photo") }
                    }.padding(.vertical, 16)
                }.disabled(processingPhoto || model.busy); Spacer() }
            }.listRowBackground(Color.clear)
            Section("Your name") { TextField("Your name", text: $name).textContentType(.nickname).textInputAutocapitalization(.words) }
            Section("Google account") { Text(model.bootstrap?.userEmail ?? "").foregroundStyle(.secondary).textSelection(.enabled) }
            if let error = photoError ?? model.errorMessage { Section { InlineError(message: error) } }
        }.scrollContentBackground(.hidden).background(MulberryDesign.background)
            .navigationTitle("Profile info").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    Task { await model.updateName(name.trimmingCharacters(in: .whitespacesAndNewlines)); if model.errorMessage == nil { dismiss() } }
                }.disabled(model.busy || processingPhoto || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name == model.bootstrap?.userDisplayName)
            } }
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

private struct PartnerSettingsView: View {
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

    var body: some View {
        let profile = model.bootstrap
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let editable = model.bootstrap?.canEditPartner(at: context.date) == true
            Form {
                Section {
                    HStack { Spacer(); PhotosPicker(selection: $photo, matching: .images) {
                        VStack(spacing: 12) {
                            if let jpeg, let image = UIImage(data: jpeg) { Image(uiImage: image).resizable().scaledToFill().frame(width: 100, height: 100).clipShape(Circle()) }
                            else { ProfileAvatar(url: profile?.partnerPhotoUrl, name: profile?.partnerDisplayName, size: 100) }
                            if processingPhoto { ProgressView() } else { Text("Change profile photo") }
                        }.padding(.vertical, 16)
                    }.disabled(!editable || processingPhoto || model.busy); Spacer() }
                }.listRowBackground(Color.clear)
                Section {
                    TextField("Partner’s name", text: $name).textInputAutocapitalization(.words)
                    AnniversaryField(value: $anniversary).padding(.vertical, 8)
                }.disabled(!editable || model.busy)
                Section {
                    if let unlock = model.bootstrap?.partnerProfileUnlockDate, !editable {
                        Text("You can change your partner’s details again on \(unlock.formatted(date: .abbreviated, time: .shortened)).")
                    } else { Text("You can change your partner’s name, photo, and anniversary once every 72 hours. Save them together.") }
                }.font(.footnote).foregroundStyle(.secondary)
                if model.bootstrap?.isPaired == true {
                    Section {
                        if let paired = model.bootstrap?.pairedAt.flatMap(ServerDate.parse) {
                            LabeledContent("Paired for", value: "\(max(0, Calendar.current.dateComponents([.day], from: paired, to: context.date).day ?? 0)) days")
                        }
                        LabeledContent("Daily streak", value: "\(model.bootstrap?.currentStreakDays ?? 0) days")
                        Button("Unpair partner", role: .destructive) { disconnect = true }.disabled(model.busy)
                    }
                } else { Section { Button("Invite your partner") { invite = true } } }
                if let error = photoError ?? model.errorMessage { Section { InlineError(message: error) } }
            }.scrollContentBackground(.hidden).background(MulberryDesign.background)
                .toolbar { ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task { await model.updatePartner(name: name.trimmingCharacters(in: .whitespacesAndNewlines), anniversary: anniversary, jpeg: jpeg); if model.errorMessage == nil { dismiss() } }
                    }.disabled(!editable || model.busy || processingPhoto || !changed || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || anniversary.isEmpty)
                } }
        }
        .navigationTitle("Partner settings").navigationBarTitleDisplayMode(.inline)
        .onAppear { name = model.bootstrap?.partnerDisplayName ?? ""; anniversary = model.bootstrap?.anniversaryDate ?? "" }
        .confirmationDialog("Disconnect partner?", isPresented: $disconnect, titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) { Task { await model.disconnect(); if model.errorMessage == nil { dismiss() } } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Your profile stays intact, but shared sync stops until you pair again.") }
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

private struct AboutView: View {
    @AppStorage("developerOptions") private var developerOptions = false
    @State private var taps = 0
    @State private var legal: LegalDocument?
    var body: some View {
        Form {
            Section {
                HStack { Spacer(); Image("BrandMark").resizable().scaledToFit().frame(width: 70, height: 70).padding(20); Spacer() }
                Text("Mulberry").font(MulberryDesign.font(28, weight: "Bold")).frame(maxWidth: .infinity)
                Text("Share a canvas with your partner that lives on your wallpaper.").multilineTextAlignment(.center).foregroundStyle(.secondary)
            }.listRowBackground(Color.clear)
            Section {
                Button { taps += 1; if taps >= 7 { developerOptions = true } } label: {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0").foregroundStyle(MulberryDesign.ink)
                }
                LabeledContent("Build", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1")
                if developerOptions { Text("Developer options enabled").font(.caption).foregroundStyle(.secondary) }
            }
            Section {
                Button("Privacy Policy") { legal = .privacy }
                Button("Terms of Use") { legal = .terms }
                Link("Website", destination: URL(string: "https://mulberry.my")!)
                Link("Source code", destination: URL(string: "https://github.com/pingSubhajit/mulberry")!)
            }
        }.scrollContentBackground(.hidden).background(MulberryDesign.background)
            .navigationTitle("About").navigationBarTitleDisplayMode(.inline)
            .sheet(item: $legal) { LegalDocumentView(document: $0) }
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
                LabeledContent("Canvas revision", value: String(model.drawing.revision))
                LabeledContent("Pending edits", value: String(model.pendingEdits))
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
            }
        }.navigationTitle("Developer options").navigationBarTitleDisplayMode(.inline)
    }
}

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
