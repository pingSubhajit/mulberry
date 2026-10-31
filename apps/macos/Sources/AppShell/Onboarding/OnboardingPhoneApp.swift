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
            .padding(.top, 74)
            .padding(.bottom, 24)
            .transition(.opacity.combined(with: .offset(y: 10)))
            if home { dock }
            // The code screen's number pad, rising from the foot as iOS's keyboard does.
            if flow.appScreen == .code {
                VStack {
                    Spacer()
                    PhoneNumberPad { digit in
                        if flow.code.count < 6 { flow.code.append(String(digit)) }
                    } delete: {
                        if !flow.code.isEmpty { flow.code.removeLast() }
                    }
                }
                .transition(.move(edge: .bottom))
                .disabled(flow.busy)
            }
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
    @State private var choosingDate = false
    /// The wheel's drum the arrow keys turn.
    @State private var dateColumn = 0
    private enum Field { case name, partner, date }

    /// Room for "Partner", so both fields start at one edge.
    private let labelWidth: CGFloat = 46

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PhoneHeading(kicker: "Welcome to", title: "Mulberry")
            VStack(spacing: 12) {
                PhoneFormSection {
                    PhoneFormRow(label: "You", labelWidth: labelWidth) {
                        TextField("You", text: $flow.name, prompt: Text("What they call you"))
                            .focused($focus, equals: .name)
                            .onSubmit { focus = .partner }
                    }
                    PhoneFormSeparator()
                    PhoneFormRow(label: "Partner", labelWidth: labelWidth) {
                        TextField("Partner", text: $flow.partnerName, prompt: Text("What you call them"))
                            .focused($focus, equals: .partner)
                            .onSubmit {
                                focus = .date
                                setDateOpen(true)
                            }
                    }
                }
                .textFieldStyle(.plain)
                anniversary
            }
            .padding(.top, 30)
            Spacer(minLength: 12)
            // The Mac's own switch, in Mulberry red.
            Toggle(isOn: $flow.openAtLogin) {
                Text("Open at Login").font(PhoneForm.font).foregroundStyle(.white.opacity(0.85))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .toggleStyle(.switch)
            .controlSize(.small)
            .tint(mulberryRed)
            .padding(.horizontal, 2)
            .padding(.bottom, 12)
            PhonePrimaryButton(title: "Let’s get going", busy: flow.busy, enabled: flow.profileComplete) {
                Task { await flow.saveProfile() }
            }
            PhoneError(message: flow.error)
            CodeLink(flow: flow)
        }
        .onAppear {
            #if DEBUG
            // `-onboardingDateOpen YES` opens with the wheel unfolded, for looking at it.
            if UserDefaults.standard.bool(forKey: "onboardingDateOpen") {
                setDateOpen(true)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { focus = .date }
                return
            }
            #endif
            if flow.name.isEmpty { focus = .name }
        }
        // Typing again folds the wheel away.
        .onChange(of: focus) { _, field in if field == .name || field == .partner { setDateOpen(false) } }
    }

    /// The anniversary's row, and the wheel that unfolds under it. From the keyboard: Return or
    /// Space opens it, the left and right arrows pick a drum, the up and down arrows turn it, and
    /// Return closes it again.
    private var anniversary: some View {
        PhoneFormSection {
            Button { setDateOpen(!choosingDate) } label: {
                PhoneFormRow(label: "Anniversary") {
                    Spacer(minLength: 0)
                    Text(flow.anniversary?.formatted(date: .abbreviated, time: .omitted) ?? "Not set")
                        .foregroundStyle(choosingDate || focus == .date ? PhoneForm.activeTint : PhoneForm.secondary)
                        .contentTransition(.numericText())
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Anniversary")
            .accessibilityValue(flow.anniversary?.formatted(date: .long, time: .omitted) ?? "Not set")
            .accessibilityHint(choosingDate ? "Hides the date picker" : "Shows the date picker")
            if choosingDate {
                PhoneFormSeparator()
                WheelDatePicker(date: Binding { flow.anniversary ?? .now } set: { flow.anniversary = $0 },
                                keyboardColumn: focus == .date ? dateColumn : nil)
                    .transition(.opacity)
            }
        }
        .clipShape(.rect(cornerRadius: PhoneForm.radius, style: .continuous))
        // With the keyboard here, the date and the drum it turns are tinted instead of ringed.
        // Reached by Tab like a text field, whatever the Mac's keyboard navigation setting.
        .focusable(interactions: .edit)
        .focused($focus, equals: .date)
        .focusEffectDisabled()
        .onKeyPress(.space) {
            setDateOpen(!choosingDate)
            return .handled
        }
        .onKeyPress(.return) {
            if choosingDate {
                setDateOpen(false)
                return .handled
            }
            return submit()
        }
        .onKeyPress(keys: [.leftArrow, .rightArrow, .upArrow, .downArrow]) { press in
            guard choosingDate, let date = flow.anniversary else { return .ignored }
            switch press.key {
            case .leftArrow: dateColumn = max(0, dateColumn - 1)
            case .rightArrow: dateColumn = min(WheelDatePicker.columnCount - 1, dateColumn + 1)
            case .upArrow: flow.anniversary = WheelDatePicker.step(date, column: dateColumn, by: -1)
            default: flow.anniversary = WheelDatePicker.step(date, column: dateColumn, by: 1)
            }
            return .handled
        }
    }

    /// Return sends the form once it is complete.
    private func submit() -> KeyPress.Result {
        guard flow.profileComplete, !flow.busy else { return .ignored }
        Task { await flow.saveProfile() }
        return .handled
    }

    /// The wheel unfolds under the anniversary, as iOS's inline date pickers do; opening it
    /// chooses the date it shows.
    private func setDateOpen(_ open: Bool) {
        guard open != choosingDate else { return }
        if open, flow.anniversary == nil { flow.anniversary = Calendar.current.startOfDay(for: .now) }
        withAnimation(.snappy(duration: 0.32)) { choosingDate = open }
    }
}

private struct CodeScreen: View {
    @ObservedObject var flow: OnboardingFlow
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PhoneHeading(kicker: nil, title: "Join your partner")
            PhoneNote("Enter the six-digit code your partner shared with you.")
            // Six boxes, one digit each. The field behind them takes the Mac's keyboard and pasting.
            CodeSegments(code: flow.code, editing: true)
                .background {
                    TextField("", text: $flow.code)
                        .textFieldStyle(.plain)
                        .focused($focused)
                        .opacity(0.02)
                        .accessibilityLabel("Six-digit invite code")
                }
                .contentShape(.rect)
                .onTapGesture { focused = true }
                .padding(.top, 30)
                .onChange(of: flow.code) { _, value in
                    let digits = String(value.filter { $0.isASCII && $0.isNumber }.prefix(6))
                    if digits != value { flow.code = digits }
                }
                .onSubmit { Task { await flow.redeem() } }
            Spacer(minLength: 12)
            PhoneError(message: flow.error)
            PhonePrimaryButton(title: "Join", busy: flow.busy, enabled: flow.code.count == 6) {
                Task { await flow.redeem() }
            }
            // Clear of the number pad below.
            .padding(.bottom, PhoneNumberPad.height - 24 + 14)
        }
        // Back sits where a navigation bar's would, above the heading, without moving it.
        .overlay(alignment: .topLeading) {
            Button(action: flow.back) {
                Label("Back", systemImage: "chevron.left").font(PhoneForm.font)
            }
            .buttonStyle(.plain)
            .foregroundStyle(PhoneForm.activeTint)
            .disabled(flow.busy)
            .offset(x: -4, y: -30)
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
            HStack(spacing: -10) {
                PersonAvatar(url: flow.userPhotoURL, name: flow.userDisplayName, size: 60)
                PersonAvatar(url: flow.partnerPhotoURL, name: flow.inviterName, size: 60)
            }
            .padding(.top, 24)
            Text("\(flow.inviterName) invited you to Mulberry")
                .font(.custom("Poppins-SemiBold", size: 17))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 12)
            PhonePrimaryButton(title: "Awesome, let’s go", busy: flow.busy, enabled: true) {
                Task { await flow.acceptInvite() }
            }
            Button("Wrong partner? Disconnect") { Task { await flow.declineInvite() } }
                .buttonStyle(.plain)
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.55))
                .frame(minHeight: 26)
                .padding(.top, 8)
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
            PhoneNote("Share this code. \(flow.partnerDisplayName) enters it after signing up for Mulberry on their phone.")
            Group {
                if let invite = flow.invite {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let expiry = ServerDate.parse(invite.expiresAt) ?? .distantPast
                        VStack(spacing: 8) {
                            CodeSegments(code: invite.code, editing: false)
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel("Invite code \(invite.code.map(String.init).joined(separator: " "))")
                            if expiry > context.date {
                                Text("Expires in \(Text(expiry, style: .relative))")
                                    .font(.system(size: 10.5))
                                    .foregroundStyle(PhoneForm.secondary)
                            } else {
                                Button("This code has expired. Get a new one") { Task { await flow.createInvite() } }
                                    .buttonStyle(.plain)
                                    .font(.system(size: 10.5))
                                    .foregroundStyle(PhoneForm.activeTint)
                            }
                        }
                    }
                } else if flow.busy {
                    ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 50)
                } else {
                    Button("Try again") { Task { await flow.createInvite() } }
                        .buttonStyle(.plain)
                        .font(PhoneForm.font)
                        .foregroundStyle(PhoneForm.activeTint)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
            }
            .padding(.top, 30)
            Spacer(minLength: 12)
            // Share leads until the code has gone out; then Continue does.
            if let invite = flow.invite {
                InviteShareButton(text: PartnerInvite.message(code: invite.code),
                                  primary: !flow.inviteShared) {
                    withAnimation(.easeOut(duration: 0.25)) { flow.inviteShared = true }
                }
            }
            Button {
                Task { await flow.continueWithoutPartner() }
            } label: {
                Text("Continue")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 30)
                    .background(flow.inviteShared ? mulberryRed : .white.opacity(0.1), in: .rect(cornerRadius: 9, style: .continuous))
                    .contentShape(.rect(cornerRadius: 9, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            PhoneError(message: flow.error)
            CodeLink(flow: flow)
        }
    }
}

/// A line under a heading, saying what the screen asks for.
private struct PhoneNote: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(PhoneForm.font)
            .foregroundStyle(PhoneForm.secondary)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 8)
    }
}

// MARK: Pieces

private struct PhoneHeading: View {
    let kicker: String?
    let title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            if let kicker {
                Text(kicker).font(.system(size: 12)).foregroundStyle(.white.opacity(0.65))
            }
            Text(title)
                .font(.custom("Poppins-SemiBold", size: 20))
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .accessibilityAddTraits(.isHeader)
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
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.white.opacity(enabled ? 1 : 0.55))
            .frame(maxWidth: .infinity, minHeight: 30)
            .background(mulberryRed.opacity(enabled ? 1 : 0.4), in: .rect(cornerRadius: 9, style: .continuous))
            .contentShape(.rect(cornerRadius: 9, style: .continuous))
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
                .font(.system(size: 11))
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
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.55))
                .frame(maxWidth: .infinity, minHeight: 26)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(flow.busy)
        .padding(.top, 8)
    }
}

/// Share, through the Mac's share menu: it tells when a way to share was chosen, so the screen
/// knows the code has gone out. Closing the menu without choosing changes nothing.
private struct InviteShareButton: View {
    let text: String
    let primary: Bool
    let shared: () -> Void
    @State private var anchor = ShareAnchor()

    var body: some View {
        Button {
            anchor.share(text, chosen: shared)
        } label: {
            Label("Share invite code", systemImage: "square.and.arrow.up")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 30)
                .background(primary ? mulberryRed : .white.opacity(0.1), in: .rect(cornerRadius: 9, style: .continuous))
                .contentShape(.rect(cornerRadius: 9, style: .continuous))
        }
        .buttonStyle(.plain)
        .background(ShareAnchorView(anchor: anchor))
    }
}

/// A six-digit code in six boxes, one digit to a box. Being typed, the next box to fill is ringed
/// in Mulberry red with a blinking caret, as iOS's one-time code boxes show.
private struct CodeSegments: View {
    let code: String
    let editing: Bool
    private let count = 6

    var body: some View {
        let digits = Array(code)
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { index in
                let active = editing && index == min(digits.count, count - 1) && digits.count < count
                ZStack {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(PhoneForm.cell)
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .strokeBorder(PhoneForm.activeTint, lineWidth: 1.5)
                        .opacity(active ? 1 : 0)
                    if index < digits.count {
                        Text(String(digits[index]))
                            .font(.system(size: 19).monospacedDigit())
                            .foregroundStyle(.white)
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                    } else if active {
                        Capsule()
                            .fill(PhoneForm.activeTint)
                            .frame(width: 1.5, height: 18)
                            .phaseAnimator([1.0, 0.0]) { caret, shown in caret.opacity(shown) } animation: { _ in .easeInOut(duration: 0.5) }
                    }
                }
                .frame(height: 44)
                .frame(maxWidth: .infinity)
            }
        }
        .animation(.snappy(duration: 0.18), value: code)
    }
}
