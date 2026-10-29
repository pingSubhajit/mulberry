import AppIntents

/// The island's Send back button. The widget extension only declares it; iOS runs it in the app,
/// which sends the reaction and marks the activity as answered.
struct SendReactionBackIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Send a reaction back"
    static var isDiscoverable = false

    @Parameter(title: "Reaction") var reaction: String

    init() {}
    init(_ type: ReactionType) { reaction = type.rawValue }

    @MainActor func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        try await ReactionActivities.sendBack(ReactionType(rawValue: reaction) ?? .heart)
        #endif
        return .result()
    }
}
