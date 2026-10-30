import ProductCore
import SwiftUI

/// The code that lets a partner join, for the Mac's unpaired places: the window, Settings and the
/// menu bar's lock screen. The server hands back the account's active code, or a new one.
@MainActor
final class PartnerInvite: ObservableObject {
    @Published private(set) var invite: CreatedInvite?
    @Published private(set) var loading = false
    @Published private(set) var errorMessage: String?
    private let api: () -> BackendClient
    private var user: String?

    init(api: @escaping () -> BackendClient) {
        self.api = api
    }

    /// The account changed: an unpaired one gets its code, anyone else none.
    func bind(user: String?, unpaired: Bool) {
        let next = unpaired ? user : nil
        guard next != self.user else { return }
        self.user = next
        invite = nil
        errorMessage = nil
        if next != nil { Task { await load() } }
    }

    /// Fetches the code, or a fresh one once it has expired.
    func load() async {
        guard user != nil, !loading else { return }
        if let invite, let expiry = ServerDate.parse(invite.expiresAt), expiry > .now { return }
        loading = true
        defer { loading = false }
        do {
            invite = try await api().createInvite()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// The code, how long it lasts, and a way to share it.
struct InviteCodeCard: View {
    @ObservedObject var invite: PartnerInvite

    var body: some View {
        VStack(spacing: 12) {
            if let current = invite.invite {
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    let expiry = ServerDate.parse(current.expiresAt) ?? .distantPast
                    VStack(spacing: 10) {
                        Text(current.code)
                            .font(.system(size: 32, weight: .semibold, design: .monospaced))
                            .tracking(6)
                            .textSelection(.enabled)
                            .accessibilityLabel("Invite code \(current.code.map(String.init).joined(separator: " "))")
                        if expiry > context.date {
                            Text("Expires \(expiry, style: .relative)")
                                .font(.callout)
                                .foregroundStyle(.secondary)
                            ShareLink(item: "Join me on Mulberry: https://mulberry.my/invite?code=\(current.code)\n\nMy invite code is \(current.code).") {
                                Label("Share Invite Code", systemImage: "square.and.arrow.up")
                            }
                            .buttonStyle(.glassProminent)
                            .tint(mulberryRed)
                        } else {
                            Button("Get a New Code") { Task { await invite.load() } }
                        }
                    }
                }
            } else if invite.loading {
                ProgressView().controlSize(.small)
            } else if let message = invite.errorMessage {
                Text(message).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button("Try Again") { Task { await invite.load() } }
            }
        }
        .task { await invite.load() }
    }
}
