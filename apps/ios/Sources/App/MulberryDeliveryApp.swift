import SwiftUI

@main
struct MulberryDeliveryApp: App {
    @UIApplicationDelegateAdaptor(NotificationBridge.self) var notifications
    @StateObject private var model = MulberryModel()
    @StateObject private var wallpapers = WallpaperLibrary()
    var body: some Scene {
        WindowGroup {
            MulberryRootView(model: model, wallpapers: wallpapers)
                .tint(MulberryDesign.accent)
                .foregroundStyle(MulberryDesign.ink)
        }
    }
}
