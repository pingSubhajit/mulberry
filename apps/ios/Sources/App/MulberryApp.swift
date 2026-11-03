import SwiftUI

@main
struct MulberryApp: App {
    @UIApplicationDelegateAdaptor(NotificationBridge.self) var notifications
    @StateObject private var model = MulberryModel()
    @StateObject private var wallpapers = WallpaperLibrary()
    @StateObject private var updates = AppUpdateGate.shared
    var body: some Scene {
        WindowGroup {
            Group {
                // Below the minimum build, nothing else opens, sheets included.
                if updates.status == .required {
                    AppUpdateRequiredView(update: updates.openStore)
                } else {
                    MulberryRootView(model: model, wallpapers: wallpapers)
                        .alert("Update Mulberry?", isPresented: Binding(
                            get: { updates.asksToUpdate && model.bootstrap?.onboardingCompleted == true },
                            set: { if !$0 { updates.asksToUpdate = false } }
                        )) {
                            Button("Update") { updates.answerPrompt(update: true) }
                            Button("Not now", role: .cancel) { updates.answerPrompt(update: false) }
                        } message: { Text("A newer version is ready in the App Store.") }
                }
            }
            .tint(MulberryDesign.accent)
            .foregroundStyle(MulberryDesign.ink)
        }
    }
}
