import SwiftUI

enum HomeQuickAction: String {
    case clear = "clear_doodles", wallpaper = "change_wallpaper", toggle = "toggle_doodles", react = "send_reaction"
}

/// Keeps a cold-launch action until sign-in and onboarding have finished.
@MainActor
final class HomeQuickActions: ObservableObject {
    static let shared = HomeQuickActions()
    @Published var pending: HomeQuickAction?

    @discardableResult
    func receive(_ item: UIApplicationShortcutItem) -> Bool {
        guard let action = HomeQuickAction(rawValue: item.type) else { return false }
        pending = action
        return true
    }

    /// Paired, the last reaction sent comes first, like Android's launcher shortcut.
    func publish(signedIn: Bool, paired: Bool, showDrawings: Bool, reaction: ReactionType) {
        guard signedIn else { UIApplication.shared.shortcutItems = []; return }
        var items = [UIApplicationShortcutItem(type: HomeQuickAction.wallpaper.rawValue, localizedTitle: "Change Wallpaper", localizedSubtitle: nil, icon: .init(systemImageName: "photo"))]
        if paired {
            items.insert(.init(type: HomeQuickAction.clear.rawValue, localizedTitle: "Clear doodles", localizedSubtitle: nil, icon: .init(systemImageName: "trash")), at: 0)
            items.insert(.init(type: HomeQuickAction.react.rawValue, localizedTitle: reaction.sendLabel, localizedSubtitle: reaction.emoji, icon: .init(systemImageName: "heart")), at: 0)
        }
        items.append(.init(type: HomeQuickAction.toggle.rawValue, localizedTitle: showDrawings ? "Hide doodles" : "Show doodles", localizedSubtitle: "On your wallpaper", icon: .init(systemImageName: showDrawings ? "eye.slash" : "eye")))
        UIApplication.shared.shortcutItems = items
    }
}

final class QuickActionSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem, completionHandler: @escaping (Bool) -> Void) {
        completionHandler(HomeQuickActions.shared.receive(shortcutItem))
    }
}
