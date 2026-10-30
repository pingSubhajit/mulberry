import AppKit
import ProductCore
import SwiftUI

/// Behind the onboarding phone's lock screen: a home screen with Mulberry alone in its dock, which
/// opens itself, growing from its icon into the red launch screen, then the person's details, a
/// partner's code or an invite for them, as the iPhone app asks for them.
struct OnboardingPhoneApp: View {
    @ObservedObject var flow: OnboardingFlow

    private var home: Bool { flow.appScreen == .home }

    var body: some View {
        ZStack {
            // The home screen is the wallpaper itself; the app has its own dark background.
            Color(white: 0.06).opacity(home ? 0 : 1)
            Group {
                switch flow.appScreen {
                case .home, .splash: EmptyView()
                case .profile: ProfileScreen(flow: flow)
                case .code: CodeScreen(flow: flow)
                case .acceptance: AcceptanceScreen(flow: flow)
                case .invite: InviteScreen(flow: flow)
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 50)
            .padding(.bottom, 24)
            .transition(.opacity.combined(with: .offset(y: 10)))
            if home { dock }
            // Mulberry's launch screen, growing out of its icon as the app opens.
            AppLaunch(progress: flow.launch, iconPressed: flow.iconPressed)
                .opacity(home || flow.appScreen == .splash ? 1 : 0)
                .animation(.easeInOut(duration: 0.45), value: flow.appScreen == .splash)
                .allowsHitTesting(false)
            // The app's own home indicator.
            VStack {
                Spacer()
                Capsule().fill(.white.opacity(0.85)).frame(width: 106, height: 4.5).padding(.bottom, 8)
            }
            .allowsHitTesting(false)
        }
        .frame(width: LockScreenView.screen.width, height: LockScreenView.screen.height)
        .tint(mulberryRed)
        .environment(\.colorScheme, .dark)
    }

    /// The iPhone's dock, in glass, with Mulberry alone in it. The icon itself is drawn by the
    /// launch, which grows from it.
    private var dock: some View {
        VStack {
            Spacer()
            RoundedRectangle(cornerRadius: AppLaunch.dockRadius, style: .continuous)
                .fill(.clear)
                .frame(width: LockScreenView.screen.width - 20, height: AppLaunch.dockHeight)
                .glassEffect(.regular, in: .rect(cornerRadius: AppLaunch.dockRadius, style: .continuous))
                .padding(.bottom, AppLaunch.dockBottom)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Mulberry opening as iOS opens an app: its icon in the dock, drawn as the iPhone app's, grows to
/// fill the screen, its colour turning to the launch screen's red as the mark grows with it.
private struct AppLaunch: View, Animatable {
    var progress: CGFloat
    let iconPressed: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    nonisolated var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    static let icon: CGFloat = 46
    /// The build's own icon colours: the development build's purple, or Mulberry red.
    #if DEBUG
    private static let iconColors = [Color(red: 0.58, green: 0.28, blue: 0.94), Color(red: 0.49, green: 0.17, blue: 0.87)]
    #else
    private static let iconColors = [Color(red: 0.80, green: 0.16, blue: 0.24), mulberryRed]
    #endif
    /// The mark's size in the icon, against its size on the launch screen.
    private static let iconMark: CGFloat = 0.4
    static let iconRadius: CGFloat = 11
    static let dockHeight: CGFloat = 68
    static let dockRadius: CGFloat = 26
    static let dockBottom: CGFloat = 10
    private static let screenRadius: CGFloat = 45

    var body: some View {
        let screen = LockScreenView.screen
        // With Reduce Motion the launch screen fades in over the icon instead of growing from it.
        let grow = reduceMotion ? (progress > 0 ? 1 : 0) : progress
        let iconCenter = CGPoint(x: screen.width / 2, y: screen.height - Self.dockBottom - Self.dockHeight / 2)
        let width = Self.icon + (screen.width - Self.icon) * grow
        let height = Self.icon + (screen.height - Self.icon) * grow
        let center = CGPoint(x: iconCenter.x + (screen.width / 2 - iconCenter.x) * grow,
                             y: iconCenter.y + (screen.height / 2 - iconCenter.y) * grow)
        let radius = Self.iconRadius + (Self.screenRadius - Self.iconRadius) * grow
        ZStack {
            // The icon's gentle gradient, settling to the flat launch red.
            LinearGradient(colors: Self.iconColors, startPoint: .top, endPoint: .bottom)
                .opacity(1 - grow)
            mulberryRed.opacity(grow)
            if let mark = SettingsArtwork.image("BrandMark.svg") {
                Image(nsImage: mark).renderingMode(.template).resizable()
                    .foregroundStyle(LinearGradient(colors: [Color(red: 0.95, green: 1, blue: 0.97), Color(red: 1, green: 0.92, blue: 0.95)],
                                                    startPoint: .top, endPoint: .bottom))
                    .frame(width: 44, height: 72)
                    // As large in the icon as the iPhone app's mark, as large as the launch
                    // screen's once open.
                    .scaleEffect(Self.iconMark + (1 - Self.iconMark) * grow)
                    .opacity(0.9 + 0.1 * grow)
            }
        }
        .frame(width: width, height: height)
        .clipShape(.rect(cornerRadius: radius, style: .continuous))
        .scaleEffect(iconPressed ? 0.88 : 1)
        .animation(.snappy(duration: 0.12), value: iconPressed)
        .position(center)
        .frame(width: screen.width, height: screen.height)
    }
}

// MARK: Screens

private struct ProfileScreen: View {
    @ObservedObject var flow: OnboardingFlow
    @FocusState private var focus: Field?
    private enum Field { case name, partner }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PhoneHeading(kicker: "Welcome to", title: "Mulberry")
            VStack(alignment: .leading, spacing: 13) {
                PhoneField(label: "What is your name?") {
                    TextField("The name your partner calls you", text: $flow.name)
                        .focused($focus, equals: .name)
                        .onSubmit { focus = .partner }
                }
                PhoneField(label: "What is your partner’s name?") {
                    TextField("The name you call them", text: $flow.partnerName)
                        .focused($focus, equals: .partner)
                }
                PhoneField(label: "When is your anniversary?") {
                    AnniversaryButton(date: $flow.anniversary)
                }
            }
            .padding(.top, 22)
            Spacer(minLength: 12)
            OpenAtLoginToggle(flow: flow)
            PhonePrimaryButton(title: "Let’s get going", busy: flow.busy, enabled: flow.profileComplete) {
                Task { await flow.saveProfile() }
            }
            .padding(.top, 12)
            PhoneError(message: flow.error)
            CodeLink(flow: flow)
        }
        .onAppear { if flow.name.isEmpty { focus = .name } }
    }
}

private struct CodeScreen: View {
    @ObservedObject var flow: OnboardingFlow
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: flow.back) {
                Label("Back", systemImage: "chevron.left").font(.system(size: 13, weight: .medium))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white.opacity(0.75))
            .disabled(flow.busy)
            PhoneHeading(kicker: "Have a code?", title: "Join your partner")
                .padding(.top, 18)
            Text("Enter the six-digit code your partner shared with you.")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.6))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
            TextField("000000", text: $flow.code)
                .textFieldStyle(.plain)
                .font(.system(size: 30, weight: .semibold, design: .monospaced))
                .tracking(6)
                .multilineTextAlignment(.center)
                .focused($focused)
                .padding(.vertical, 16)
                .background(.white.opacity(0.1), in: .rect(cornerRadius: 14, style: .continuous))
                .padding(.top, 22)
                .accessibilityLabel("Six-digit invite code")
                .onChange(of: flow.code) { _, value in
                    let digits = String(value.filter { $0.isASCII && $0.isNumber }.prefix(6))
                    if digits != value { flow.code = digits }
                }
                .onSubmit { Task { await flow.redeem() } }
            Spacer(minLength: 12)
            PhonePrimaryButton(title: "Join", busy: flow.busy, enabled: flow.code.count == 6) {
                Task { await flow.redeem() }
            }
            PhoneError(message: flow.error)
        }
        .onAppear {
            // A code just copied from a message is waiting on the clipboard.
            if flow.code.isEmpty, let copied = NSPasteboard.general.string(forType: .string)?.trimmingCharacters(in: .whitespacesAndNewlines),
               copied.count == 6, copied.allSatisfy({ $0.isASCII && $0.isNumber }) {
                flow.code = copied
            }
            focused = true
        }
    }
}

private struct AcceptanceScreen: View {
    @ObservedObject var flow: OnboardingFlow

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: -12) {
                PersonAvatar(url: flow.userPhotoURL, name: flow.userDisplayName, size: 78)
                PersonAvatar(url: flow.partnerPhotoURL, name: flow.inviterName, size: 78)
            }
            .padding(.top, 40)
            Text("\(flow.inviterName) invited you to Mulberry")
                .font(.custom("Poppins-Bold", size: 22))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 26)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 12)
            PhonePrimaryButton(title: "Awesome, let’s go", busy: flow.busy, enabled: true) {
                Task { await flow.acceptInvite() }
            }
            .padding(.top, 12)
            Button("Wrong partner? Disconnect") { Task { await flow.declineInvite() } }
                .buttonStyle(.plain)
                .font(.system(size: 12.5))
                .foregroundStyle(.white.opacity(0.6))
                .frame(minHeight: 32)
                .disabled(flow.busy)
            PhoneError(message: flow.error)
        }
    }
}

private struct InviteScreen: View {
    @ObservedObject var flow: OnboardingFlow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PhoneHeading(kicker: nil, title: "Invite \(flow.partnerDisplayName)")
            Text("Share this code. \(flow.partnerDisplayName) enters it after signing up for Mulberry on their phone.")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.6))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
            Group {
                if let invite = flow.invite {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let expiry = ServerDate.parse(invite.expiresAt) ?? .distantPast
                        VStack(spacing: 8) {
                            Text(invite.code)
                                .font(.system(size: 34, weight: .semibold, design: .monospaced))
                                .tracking(6)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 18)
                                .background(.white.opacity(0.1), in: .rect(cornerRadius: 14, style: .continuous))
                                .accessibilityLabel("Invite code \(invite.code.map(String.init).joined(separator: " "))")
                            if expiry > context.date {
                                Text("Expires in \(Text(expiry, style: .relative))")
                                    .font(.system(size: 12))
                                    .foregroundStyle(.white.opacity(0.5))
                            } else {
                                Button("This code has expired. Get a new one") { Task { await flow.createInvite() } }
                                    .buttonStyle(.plain)
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.75))
                            }
                        }
                    }
                } else if flow.busy {
                    ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 80)
                } else {
                    Button("Try again") { Task { await flow.createInvite() } }
                        .frame(maxWidth: .infinity, minHeight: 80)
                }
            }
            .padding(.top, 22)
            Spacer(minLength: 12)
            if let invite = flow.invite {
                ShareLink(item: "Join me on Mulberry: https://mulberry.my/invite?code=\(invite.code)\n\nMy invite code is \(invite.code).") {
                    Label("Share invite code", systemImage: "square.and.arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(mulberryRed, in: .rect(cornerRadius: 12, style: .continuous))
                        .contentShape(.rect(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            Button {
                Task { await flow.continueWithoutPartner() }
            } label: {
                Text("Continue")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .background(.white.opacity(0.1), in: .rect(cornerRadius: 12, style: .continuous))
                    .contentShape(.rect(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            PhoneError(message: flow.error)
            CodeLink(flow: flow)
        }
    }
}

// MARK: Pieces

private struct PhoneHeading: View {
    let kicker: String?
    let title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let mark = SettingsArtwork.image("BrandMark.svg") {
                Image(nsImage: mark).resizable().frame(width: 22, height: 36).padding(.bottom, 14)
            }
            if let kicker {
                Text(kicker).font(.system(size: 15)).foregroundStyle(.white.opacity(0.75))
            }
            Text(title)
                .font(.custom("Poppins-Bold", size: 28))
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .accessibilityAddTraits(.isHeader)
        }
    }
}

private struct PhoneField<Content: View>: View {
    let label: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(.white.opacity(0.9))
            content
                .textFieldStyle(.plain)
                .font(.system(size: 13.5))
                .padding(.horizontal, 12)
                .frame(height: 34)
                .background(.white.opacity(0.1), in: .rect(cornerRadius: 10, style: .continuous))
        }
    }
}

/// The anniversary as a button that opens the calendar.
private struct AnniversaryButton: View {
    @Binding var date: Date?
    @State private var choosing = false

    var body: some View {
        Button {
            choosing = true
        } label: {
            HStack {
                Text(date?.formatted(date: .long, time: .omitted) ?? "Select date")
                    .foregroundStyle(date == nil ? .white.opacity(0.35) : .white)
                Spacer()
                Image(systemName: "calendar").foregroundStyle(.white.opacity(0.5))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Anniversary")
        .accessibilityValue(date?.formatted(date: .long, time: .omitted) ?? "Not selected")
        .popover(isPresented: $choosing, arrowEdge: .trailing) {
            DatePicker("Anniversary", selection: Binding { date ?? Date() } set: { date = $0 },
                       in: ...Date(), displayedComponents: .date)
                .datePickerStyle(.graphical)
                .labelsHidden()
                .padding(12)
        }
    }
}

private struct PhonePrimaryButton: View {
    let title: String
    let busy: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Text(title).opacity(busy ? 0 : 1)
                if busy { ProgressView().controlSize(.small).tint(.white) }
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(mulberryRed.opacity(enabled ? 1 : 0.45), in: .rect(cornerRadius: 12, style: .continuous))
            .contentShape(.rect(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!enabled || busy)
        .keyboardShortcut(.defaultAction)
    }
}

private struct PhoneError: View {
    let message: String?

    var body: some View {
        if let message {
            Text(message)
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 1, green: 0.55, blue: 0.55))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
        }
    }
}

private struct CodeLink: View {
    @ObservedObject var flow: OnboardingFlow

    var body: some View {
        Button(action: flow.enterCode) {
            Text("Have a code? \(Text("Enter code").foregroundStyle(.white))")
                .font(.system(size: 12.5))
                .foregroundStyle(.white.opacity(0.55))
                .frame(maxWidth: .infinity, minHeight: 30)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(flow.busy)
    }
}
