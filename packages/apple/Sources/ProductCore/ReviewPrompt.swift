import Foundation

/// Asks for an App Store review once the pair keeps a 3-day streak, as Android asks for a Play
/// review, then waits 14, 60, 180 and 365 days between asks. The system decides whether it
/// actually shows. Shared by the iPhone, iPad and Mac.
public enum ReviewPrompt {
    private static let attemptsKey = "reviewPromptAttempts"
    private static let nextKey = "reviewPromptNextEligibleAt"
    private static let backoffDays = [0, 14, 60, 180, 365]

    /// True when this streak may ask now; asking also books the next wait.
    public static func reserve(streakDays: Int, now: Date = .now, defaults: UserDefaults = .standard) -> Bool {
        guard streakDays >= 3 else { return false }
        if let next = defaults.object(forKey: nextKey) as? Date, now < next { return false }
        let attempts = defaults.integer(forKey: attemptsKey) + 1
        defaults.set(attempts, forKey: attemptsKey)
        let days = backoffDays[min(attempts, backoffDays.count - 1)]
        defaults.set(now.addingTimeInterval(Double(days) * 86_400), forKey: nextKey)
        return true
    }
}
