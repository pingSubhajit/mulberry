import SwiftUI

struct InviteEntryView: View {
    @ObservedObject var model: MulberryModel
    @State private var code = ""
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    Image("BrandMark").resizable().scaledToFit().frame(width: 46, height: 46).accessibilityHidden(true)
                    ScreenHeading(title: "Let’s get you set up", subtitle: "Enter the invite code your partner shared with you.")
                    TextField("000000", text: $code)
                        .font(.system(size: 36, weight: .semibold, design: .monospaced))
                        .multilineTextAlignment(.center).tracking(8).keyboardType(.numberPad)
                        .textContentType(.oneTimeCode).focused($focused)
                        .padding(.vertical, 22).frame(maxWidth: .infinity)
                        .background(MulberryDesign.surface, in: RoundedRectangle(cornerRadius: 16))
                        .accessibilityLabel("Six-digit invite code").accessibilityIdentifier("inviteCode")
                        .onChange(of: code) { _, value in code = String(value.filter { $0.isASCII && $0.isNumber }.prefix(6)) }
                    InlineError(message: model.errorMessage)
                }.padding(24).padding(.top, 16)
            }.scrollDismissesKeyboard(.interactively).background(MulberryDesign.background)
                .safeAreaInset(edge: .bottom) {
                    PrimaryAction(title: "Let’s get going", loading: model.busy, enabled: code.count == 6) {
                        focused = false
                        Task { await model.redeem(code) }
                    }.padding(24).background(MulberryDesign.background)
                }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }.interactiveDismissDisabled(model.busy)
    }
}

struct InviteAcceptanceView: View {
    @ObservedObject var model: MulberryModel
    var body: some View {
        ScrollView {
            VStack(spacing: 36) {
                HStack(spacing: -12) {
                    ProfileAvatar(url: model.bootstrap?.userPhotoUrl, name: model.bootstrap?.invite?.recipientDisplayName, size: 104)
                    ProfileAvatar(url: model.bootstrap?.partnerPhotoUrl, name: model.bootstrap?.invite?.inviterDisplayName, size: 104)
                }.padding(.top, 72)
                Text("\(model.bootstrap?.invite?.inviterDisplayName ?? "Your partner") invited you\nto Mulberry")
                    .font(MulberryDesign.font(29, weight: "Bold", relativeTo: .largeTitle)).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
                Text("Welcome \(model.bootstrap?.invite?.recipientDisplayName ?? model.bootstrap?.userDisplayName ?? "")")
                    .foregroundStyle(MulberryDesign.muted)
                InlineError(message: model.errorMessage)
            }.padding(24).readableColumn()
        }.background(MulberryDesign.background)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 12) {
                    PrimaryAction(title: "Awesome, let’s go", loading: model.busy) { Task { await model.acceptInvite() } }
                    Button("Wrong partner? Disconnect") { Task { await model.declineInvite() } }
                        .frame(minHeight: 44).disabled(model.busy)
                }.readableColumn().padding(24).background(MulberryDesign.background)
            }
    }
}

struct InviteShareView: View {
    @ObservedObject var model: MulberryModel
    @Environment(\.dismiss) private var dismiss
    private var needsDetails: Bool {
        (model.bootstrap?.partnerDisplayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        || (model.bootstrap?.anniversaryDate?.isEmpty ?? true)
    }
    var body: some View {
        NavigationStack {
            Group {
                if needsDetails { InvitePartnerDetailsView(model: model) }
                else { inviteContent }
            }
            .navigationTitle("Invite partner").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task {
                model.errorMessage = nil
                if !needsDetails && model.createdInvite == nil { await model.createInvite() }
            }
            .onChange(of: model.bootstrap?.isPaired) { _, paired in if paired == true { dismiss() } }
        }
    }

    private var inviteContent: some View {
            ScrollView {
                VStack(spacing: 24) {
                    Image("BrandMark").resizable().scaledToFit().frame(width: 52, height: 52).accessibilityHidden(true)
                    ScreenHeading(title: "Invite your partner", subtitle: "Share this code with your partner and ask them to enter it after they have signed up for Mulberry.")
                    if let invite = model.createdInvite {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            let expiry = ServerDate.parse(invite.expiresAt) ?? .distantPast
                            let expired = expiry <= context.date
                            VStack(spacing: 20) {
                                Text(invite.code).font(.system(size: 42, weight: .semibold, design: .monospaced))
                                    .tracking(6).textSelection(.enabled).accessibilityLabel("Invite code \(invite.code.map(String.init).joined(separator: " "))")
                                    .frame(maxWidth: .infinity).padding(.vertical, 24)
                                    .background(MulberryDesign.surface, in: RoundedRectangle(cornerRadius: 18))
                                if expired {
                                    Text("This code has expired.").foregroundStyle(MulberryDesign.muted)
                                    PrimaryAction(title: "Generate new code", loading: model.busy) { Task { await model.createInvite() } }
                                } else {
                                    Text("Expires in \(Text(expiry, style: .relative))").font(.callout).foregroundStyle(MulberryDesign.muted)
                                    ShareLink(item: "Join me on Mulberry: https://mulberry.my/invite?code=\(invite.code)\n\nMy invite code is \(invite.code).") {
                                        Label("Share invite code", systemImage: "square.and.arrow.up")
                                            .font(MulberryDesign.font(17, weight: "SemiBold")).foregroundStyle(.white)
                                            .frame(maxWidth: .infinity, minHeight: 54)
                                            .background(MulberryDesign.accent, in: RoundedRectangle(cornerRadius: 16))
                                    }
                                }
                            }
                        }
                    } else if model.busy { ProgressView("Creating your invite…") }
                    InlineError(message: model.errorMessage)
                    if model.createdInvite == nil && !model.busy {
                        Button("Try again") { Task { await model.createInvite() } }
                    }
                }.padding(24)
            }.background(MulberryDesign.background)
    }
}

private struct InvitePartnerDetailsView: View {
    @ObservedObject var model: MulberryModel
    @State private var name = ""
    @State private var anniversary = ""
    @FocusState private var focused: Bool
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                ScreenHeading(title: "Partner details", subtitle: "Add your partner’s details to create an invite code.")
                OnboardingField(label: "What is your partner’s name?", placeholder: "Enter the name you call your partner", value: $name)
                    .focused($focused).submitLabel(.done)
                AnniversaryField(value: $anniversary)
                InlineError(message: model.errorMessage)
            }.padding(24)
        }.scrollDismissesKeyboard(.interactively).background(MulberryDesign.background)
            .safeAreaInset(edge: .bottom) {
                PrimaryAction(title: "Save and create invite", loading: model.busy,
                              enabled: !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !anniversary.isEmpty) {
                    focused = false
                    Task {
                        await model.updatePartner(name: name.trimmingCharacters(in: .whitespacesAndNewlines), anniversary: anniversary, jpeg: nil)
                        if model.errorMessage == nil { await model.createInvite() }
                    }
                }.padding(24).background(MulberryDesign.background)
            }
            .onAppear { name = model.bootstrap?.partnerDisplayName ?? ""; anniversary = model.bootstrap?.anniversaryDate ?? "" }
    }
}
