import AppIntents

extension ReactionType: AppEnum {
    public static var typeDisplayRepresentation: TypeDisplayRepresentation = "Reaction"
    public static var caseDisplayRepresentations: [ReactionType: DisplayRepresentation] = [
        .heart: "❤️ Love", .hug: "🤗 Hug", .kiss: "💋 Kiss", .smile: "😊 Smile", .laugh: "😂 Laugh", .sparkle: "✨ Sparkles"
    ]
}

/// Sends a reaction without opening Mulberry, from Shortcuts, Siri, Spotlight or the Action button.
struct SendReactionIntent: AppIntent {
    static var title: LocalizedStringResource = "Send a reaction"
    static var description = IntentDescription("Send your partner a reaction in Mulberry.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Reaction", default: .heart) var reaction: ReactionType
    static var parameterSummary: some ParameterSummary { Summary("Send \(\.$reaction)") }

    init() {}
    init(reaction: ReactionType) { self.reaction = reaction }

    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        let api = try AppRuntime.api()
        guard let user = await api.signedInUserId else { throw ReactionIntentError.signedOut }
        do { try await api.sendReaction(reaction) }
        catch BackendError.rejected(400, _) { throw ReactionIntentError.unpaired }
        UserDefaults.standard.set(reaction.rawValue, forKey: "reactionLastUsed.\(user)")
        return .result(dialog: "Sent \(reaction.emoji)")
    }
}

enum ReactionIntentError: Error, CustomLocalizedStringResourceConvertible {
    case signedOut, unpaired
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .signedOut: "Open Mulberry and sign in first."
        case .unpaired: "Pair with your partner in Mulberry first."
        }
    }
}
