import SwiftUI

struct WallpaperSetupView: View {
    @ObservedObject var setup: WallpaperSetupModel
    let onDone: () -> Void
    @Environment(\.scenePhase) private var phase
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ScreenHeading(title: "Keep your canvas close", subtitle: "Connect Shortcuts once to update your wallpaper.")
                        .listRowInsets(EdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0)).listRowBackground(Color.clear)
                }
                Section {
                    Label(setup.health.title, systemImage: statusIcon).font(.headline)
                    Text(setup.health.detail).font(.callout).foregroundStyle(MulberryDesign.muted)
                    if let date = setup.health.checkedAt {
                        Text("Checked \(date.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(MulberryDesign.muted)
                    }
                }
                Section("1. Allow updates") {
                    LabeledContent("Notifications", value: setup.permission)
                    if !setup.allowed {
                        Button(setup.permission == "Off" ? "Open notification settings" : "Allow notifications") { Task { await setup.requestPermission() } }
                    }
                }
                if let message = setup.remoteDeliveryMessage {
                    Section { Label(message, systemImage: "wifi.exclamationmark").font(.callout) }
                }
                Section {
                    if let template = setup.template { ShareLink("Add Shortcut", item: template) }
                    else { Text("The Shortcut template is missing from this build.").foregroundStyle(MulberryDesign.accent) }
                } header: { Text("2. Add the Shortcut") } footer: {
                    Text("Choose Shortcuts in the share sheet, then Add Shortcut. If you already have a copy, turn its Automation off before replacing it.")
                }
                Section {
                    Button("Open Shortcut") { setup.editShortcut() }
                } header: { Text("3. Choose your wallpaper") } footer: {
                    Text("In Set Wallpaper, tap the wallpaper name and choose the one to update. Expand the notification trigger and turn Automation on. Tap Back to save.")
                }
                Section {
                    Button("Run once and allow access") { Task { await setup.runShortcut() } }
                } footer: { Text("Allow Mulberry and Shortcuts to update your wallpaper when asked. You will return here when the run ends.") }
                if let message = setup.message { Section { Text(message).font(.callout) } }
                Section {
                    DisclosureGroup("If updates stop") {
                        Text("iOS cannot tell Mulberry if a Shortcut is missing, changed, or turned off. Run this test after changing it. A completed test shows what worked at that time.")
                        Button("Open Shortcut") { setup.editShortcut() }
                        Button("Open notification settings") { setup.openSettings() }
                    }
                    DisclosureGroup("If drawings look blurry") {
                        Text("In iPhone Settings → Wallpaper, tap Customise under your selected Lock Screen. Turn Spatial Scene off using the hexagon photo button, then tap Done. This keeps your drawings sharp. Also leave Blur off in the Home Screen settings.")
                    }
                }
            }.scrollContentBackground(.hidden).background(MulberryDesign.background)
                .navigationTitle("Wallpaper setup").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: onDone) } }
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 8) {
                        PrimaryAction(title: "Test wallpaper update", loading: setup.busy,
                                      enabled: setup.allowed && setup.health.state != .waiting) { Task { await setup.test() } }
                        Text("Go Home or lock your phone for the test.").font(.caption).foregroundStyle(MulberryDesign.muted)
                    }.padding(.horizontal, 24).padding(.vertical, 12).background(MulberryDesign.background)
                }
                .task(id: phase) {
                    guard phase == .active else { return }
                    while !Task.isCancelled { await setup.refresh(); try? await Task.sleep(for: .seconds(2)) }
                }
        }
    }
    private var statusIcon: String {
        switch setup.health.state {
        case .responded: "checkmark.circle"
        case .waiting: "clock"
        case .blocked, .incomplete, .settingsChanged: "exclamationmark.circle"
        default: "circle.dashed"
        }
    }
}
