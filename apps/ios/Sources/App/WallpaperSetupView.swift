import SwiftUI

struct WallpaperSetupView: View {
    @ObservedObject var setup: WallpaperSetupModel
    let onDone: () -> Void
    @Environment(\.scenePhase) private var phase
    @State private var automationGuide = false
    @State private var celebration = 0

    private typealias Step = SetupStep

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    hero
                    if setup.health.state == .incomplete && currentStep == nil { troubleshooting } else { steps }
                    notices
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 24)
            }
            .background(MulberryDesign.background)
            .overlay { if celebration > 0 { CelebrationConfetti().id(celebration) } }
            .navigationTitle("Wallpaper").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { helpMenu }
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onDone) }
            }
            .safeAreaInset(edge: .bottom) { callToAction }
            .sheet(isPresented: $automationGuide) {
                AutomationGuideSheet {
                    automationGuide = false
                    setup.editShortcut(for: setup.automationOn ? nil : .automate)
                }
            }
            .animation(.snappy, value: setup.health.state)
            .animation(.snappy, value: currentStep)
            .sensoryFeedback(.success, trigger: celebration)
            .onChange(of: setup.health.state) { old, new in
                if new == .responded && old == .waiting { celebration += 1 }
            }
            .onChange(of: phase) { if phase == .active { setup.returnedToApp() } }
            .task(id: phase) {
                guard phase == .active else { return }
                while !Task.isCancelled { await setup.refresh(); try? await Task.sleep(for: .seconds(2)) }
            }
        }
    }

    // MARK: Hero

    private var hero: some View {
        VStack(spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(LinearGradient(colors: [tint.opacity(0.9), tint.opacity(0.35)], startPoint: .top, endPoint: .bottom))
                    .frame(width: 78, height: 132)
                    .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(.white.opacity(0.25), lineWidth: 1))
                    .shadow(color: tint.opacity(0.45), radius: 24, y: 8)
                Capsule().fill(.black.opacity(0.35)).frame(width: 22, height: 6).offset(y: -56)
                Image(systemName: heroSymbol)
                    .font(.system(size: 30, weight: .semibold)).foregroundStyle(.white)
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.pulse, isActive: setup.health.state == .waiting)
                    .symbolEffect(.bounce, value: celebration)
            }
            .accessibilityHidden(true)
            VStack(spacing: 6) {
                Text(heroTitle).font(MulberryDesign.font(24, weight: "SemiBold", relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                Text(heroSubtitle).font(.subheadline).foregroundStyle(MulberryDesign.muted)
            }
            .multilineTextAlignment(.center)
            .contentTransition(.opacity)
        }
        .frame(maxWidth: .infinity).padding(.top, 12)
        .accessibilityElement(children: .combine)
    }

    // MARK: Steps

    private var steps: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Setup").font(.footnote.weight(.semibold)).foregroundStyle(MulberryDesign.muted)
                Spacer()
                progressDots
            }
            .padding(.horizontal, 18).padding(.top, 16).padding(.bottom, 6)
            ForEach(Step.allCases, id: \.self) { step in
                row(step)
                if step != .run { Divider() }
            }
        }
        .padding(.bottom, 6)
        .background(MulberryDesign.surface.opacity(0.7), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }

    private var progressDots: some View {
        HStack(spacing: 4) {
            ForEach(Step.allCases, id: \.self) { step in
                Capsule().fill(isDone(step) ? MulberryDesign.accent : MulberryDesign.muted.opacity(0.3))
                    .frame(width: step == currentStep ? 18 : 6, height: 6)
            }
        }
        .accessibilityElement().accessibilityLabel("\(Step.allCases.filter(isDone).count) of \(Step.allCases.count) steps done")
    }

    private func row(_ step: Step) -> some View {
        let done = isDone(step), current = step == currentStep
        return HStack(spacing: 14) {
            ZStack {
                Circle().fill(done ? MulberryDesign.accent : .clear)
                Circle().strokeBorder(done ? .clear : (current ? MulberryDesign.accent : MulberryDesign.muted.opacity(0.4)), lineWidth: 1.5)
                if done {
                    Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                        .transition(.scale.combined(with: .opacity))
                } else {
                    Text("\(step.rawValue)").font(MulberryDesign.font(14, weight: "SemiBold"))
                        .foregroundStyle(current ? MulberryDesign.accent : MulberryDesign.muted)
                }
            }
            .frame(width: 30, height: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(title(step)).font(MulberryDesign.font(16, weight: current ? "SemiBold" : "Medium", relativeTo: .body))
                    .foregroundStyle(done || current ? MulberryDesign.ink : MulberryDesign.muted)
                if current {
                    Text(hint(step)).font(.footnote).foregroundStyle(MulberryDesign.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            Spacer(minLength: 8)
            HStack(spacing: 6) {
                if step == .automate && !done {
                    Button("Show where Automation is", systemImage: "questionmark") { automationGuide = true }
                        .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.small)
                }
                rowAction(step, done: done, current: current)
            }
        }
        .padding(.horizontal, 18).padding(.vertical, current ? 14 : 11)
        .background(current ? MulberryDesign.accent.opacity(0.08) : .clear)
        // Unfinished rows act like their chevron. Finished rows stay inert so a stray tap can't undo progress;
        // the ↺ and ? buttons keep their own actions because buttons win over the row gesture.
        .contentShape(Rectangle())
        .onTapGesture { if !done { perform(step) } }
    }

    @ViewBuilder private func rowAction(_ step: Step, done: Bool, current: Bool) -> some View {
        if done && (step == .allow || step == .run) {
            // Confirmed by iOS or by the Shortcut itself, so there is nothing to redo here.
            EmptyView()
        } else if step == .add && setup.template == nil {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(MulberryDesign.accent)
                .accessibilityLabel("Shortcut template missing from this build")
        } else if done {
            Button("Redo \(title(step).lowercased())", systemImage: "arrow.counterclockwise") {
                Task { await setup.undoSteps(from: step); perform(step) }
            }
                .labelStyle(.iconOnly).buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.small)
                .disabled(testRunning)
        } else {
            Button("\(verb(step)): \(title(step))", systemImage: "chevron.right") { perform(step) }
                .labelStyle(.iconOnly).buttonBorderShape(.circle).modifier(RowButtonStyle(prominent: current))
        }
    }

    // MARK: Failed test

    private struct Check: Identifiable {
        enum Action { case shortcut, settings, guide, library }
        let symbol: String, title: String, hint: String
        var action: Action?
        var id: String { title }
    }

    /// Likely causes, most common first. iOS does not say which one failed, so none is presented as the cause.
    private let checks = [
        Check(symbol: "bolt.badge.clock", title: "Is Automation on?", hint: "It’s tucked inside the Shortcut’s trigger", action: .guide),
        Check(symbol: "photo.on.rectangle", title: "Wallpaper picked?", hint: "Set Wallpaper needs one to update", action: .shortcut),
        Check(symbol: "bell.badge", title: "Getting notifications?", hint: "Focus or Scheduled Summary may hold them back", action: .settings),
        Check(symbol: "square.on.square", title: "Just one copy?", hint: "Older Mulberry Sync copies should have Automation off", action: .library),
    ]

    private func run(_ action: Check.Action) {
        switch action {
        case .shortcut: setup.editShortcut()
        case .guide: automationGuide = true
        case .settings: setup.openSettings()
        case .library: setup.openShortcutsLibrary()
        }
    }

    private func actionLabel(_ action: Check.Action) -> String {
        switch action {
        case .shortcut: "Open Shortcut"
        case .guide: "Show where Automation is"
        case .settings: "Open notification settings"
        case .library: "Open Shortcuts"
        }
    }

    private var troubleshooting: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(spacing: 0) {
                Text("Things to try").font(.footnote.weight(.semibold)).foregroundStyle(MulberryDesign.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18).padding(.top, 16).padding(.bottom, 6)
                ForEach(checks) { check in
                    // The whole row is the target; the chevron only signals that it leads somewhere.
                    Button { if let action = check.action { run(action) } } label: {
                        HStack(spacing: 14) {
                            Image(systemName: check.symbol).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(MulberryDesign.ink).frame(width: 30, height: 30)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(check.title).font(MulberryDesign.font(16, weight: "Medium", relativeTo: .body))
                                    .foregroundStyle(MulberryDesign.ink)
                                Text(check.hint).font(.footnote).foregroundStyle(MulberryDesign.muted)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 8)
                            if check.action != nil {
                                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(MulberryDesign.ink)
                                    .frame(width: 30, height: 30).glassEffect(.regular, in: .circle)
                            }
                        }
                        .padding(.horizontal, 18).padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(check.action == nil)
                    .accessibilityHint(check.action.map(actionLabel) ?? "")
                    if check.id != checks.last?.id { Divider() }
                }
            }
            .padding(.bottom, 6)
            .background(MulberryDesign.surface.opacity(0.7), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        }
        .transition(.opacity)
    }

    // MARK: Notices and help

    @ViewBuilder private var notices: some View {
        // The hero already narrates waiting and success, so the test's instructions would only repeat it.
        if let info = setup.message, setup.health.state != .waiting, !isAllSet {
            Label(info, systemImage: "info.circle").font(.footnote).foregroundStyle(MulberryDesign.ink)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 6)
        }
    }

    private var helpMenu: some View {
        SetupHelpMenu(testRunning: testRunning, editShortcut: { [setup] in setup.editShortcut() },
                      openSettings: { [setup] in setup.openSettings() },
                      startOver: { [setup] in Task { await setup.startOver() } })
            .equatable()
    }

    // MARK: Call to action

    private var callToAction: some View {
        VStack(spacing: 8) {
            if retryHint {
                Text("Sometimes iOS is just slow, so another try often works")
                    .font(.footnote).foregroundStyle(MulberryDesign.muted).multilineTextAlignment(.center)
            }
            // Testing before every step is done can only fail, so the test unlocks at the end.
            PrimaryAction(title: ctaTitle, loading: setup.busy || setup.health.state == .waiting,
                          enabled: isAllSet || currentStep == nil, action: primary)
            if let ctaFootnote {
                Text(ctaFootnote).font(.caption).foregroundStyle(MulberryDesign.muted)
            }
        }
        .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 6)
        .background(MulberryDesign.background)
    }

    private func primary() {
        if isAllSet { onDone() } else { Task { await setup.test() } }
    }

    private func perform(_ step: Step) {
        switch step {
        case .allow: Task { await setup.requestPermission() }
        case .add: shareShortcut()
        case .choose, .automate: setup.editShortcut(for: step)
        case .run: Task { await setup.runShortcut() }
        }
    }

    /// Uses UIKit's share sheet because ShareLink cannot tell a completed share from a dismissed one.
    private func shareShortcut() {
        guard let template = setup.template,
              let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController
        else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        let sheet = UIActivityViewController(activityItems: [template], applicationActivities: nil)
        sheet.completionWithItemsHandler = { [setup] activity, completed, _, _ in
            guard completed || activity != nil else { return }
            Task { @MainActor in setup.markShortcutAdded() }
        }
        top.present(sheet, animated: true)
    }

    // MARK: State

    private var isAllSet: Bool { setup.health.state == .responded }
    /// Resetting mid-test would discard the probe the Shortcut is about to report against.
    private var testRunning: Bool { setup.busy || setup.health.state == .waiting }

    private func isDone(_ step: Step) -> Bool {
        switch step {
        case .allow: setup.allowed
        case .add: setup.shortcutAdded
        case .choose: setup.shortcutConfigured
        case .automate: setup.automationOn
        case .run: setup.shortcutRan
        }
    }

    private var stepsLeft: Int { currentStep.map { Step.allCases.count + 1 - $0.rawValue } ?? 0 }

    /// The first unfinished step; nil once every step is done and only the test remains.
    private var currentStep: Step? { Step.allCases.first { !isDone($0) } }

    private func title(_ step: Step) -> String {
        switch step {
        case .allow: "Allow notifications"
        case .add: "Add the Shortcut"
        case .choose: "Pick your wallpaper"
        case .automate: "Turn Automation on"
        case .run: "Run it once"
        }
    }

    private func hint(_ step: Step) -> String {
        switch step {
        case .allow: setup.permission == "Off" ? "Turned off in Settings" : "They quietly trigger each update"
        case .add: "Share sheet → Shortcuts → Add"
        case .choose: "In Set Wallpaper, choose a photo pair with no linked Focus. Mulberry switches to it only when Focus is off."
        case .automate: "It’s in the Shortcut’s trigger. Tap ? to see where"
        case .run: "Allow access when asked"
        }
    }

    private func verb(_ step: Step) -> String {
        switch step {
        case .allow: setup.permission == "Off" ? "Settings" : "Allow"
        case .add: "Add"
        case .choose, .automate: "Open"
        case .run: "Run"
        }
    }

    private var tint: Color {
        switch setup.health.state {
        case .responded: .green
        case .blocked, .incomplete: .orange
        default: MulberryDesign.accent
        }
    }

    private var heroSymbol: String {
        switch setup.health.state {
        case .responded: "checkmark"
        case .waiting: "lock.fill"
        case .blocked: "bell.slash.fill"
        case .incomplete: "arrow.clockwise"
        default: currentStep == nil ? "wand.and.sparkles" : "scribble.variable"
        }
    }

    private var heroTitle: String {
        switch setup.health.state {
        case .responded: "You’re all set"
        case .waiting: "Lock your phone now"
        case .blocked: "Notifications are off"
        case .incomplete: "That didn’t go through"
        case .settingsChanged: "Settings changed"
        default: currentStep == nil ? "Ready to test" : "Drawings on your wallpaper"
        }
    }

    private var heroSubtitle: String {
        switch setup.health.state {
        case .responded: "New drawings will appear on their own"
        case .waiting: "Come back once your wallpaper changes"
        case .blocked: "Turn them on to keep syncing"
        case .incomplete: "It’s usually a quick fix"
        case .settingsChanged: "Run a quick test to confirm"
        default:
            stepsLeft > 0 ? "\(stepsLeft) quick step\(stepsLeft == 1 ? "" : "s") · about a minute" : "One test and you’re done"
        }
    }

    private var ctaTitle: String {
        if isAllSet { return "Done" }
        if setup.health.state == .waiting { return "Waiting for update…" }
        return setup.health.state == .incomplete ? "Test again" : "Test wallpaper update"
    }

    /// Shown above the button with the troubleshooting list, since retrying is itself a fix.
    private var retryHint: Bool { setup.health.state == .incomplete && currentStep == nil }

    private var ctaFootnote: String? {
        if isAllSet || retryHint || setup.health.state == .waiting { return nil }
        return currentStep == nil ? "Then lock your phone" : nil
    }
}

private struct RowButtonStyle: ViewModifier {
    let prominent: Bool
    func body(content: Content) -> some View {
        if prominent {
            content.buttonStyle(.glassProminent).tint(MulberryDesign.accent).controlSize(.small)
        } else {
            content.buttonStyle(.glass).controlSize(.small)
        }
    }
}

/// Compared by its one input, so the polling screen behind it never rebuilds the menu while it is open.
private struct SetupHelpMenu: View, Equatable {
    let testRunning: Bool
    let editShortcut: () -> Void
    let openSettings: () -> Void
    let startOver: () -> Void

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.testRunning == rhs.testRunning }

    var body: some View {
        Menu {
            Button("Edit Shortcut", systemImage: "square.and.pencil", action: editShortcut)
            Button("Notification settings", systemImage: "bell", action: openSettings)
            Button("Start setup over", systemImage: "arrow.counterclockwise", action: startOver)
                .disabled(testRunning)
        } label: { Image(systemName: "questionmark") }
            .accessibilityLabel("Help")
    }
}

/// Shows the real Shortcuts trigger, because the expand arrow beside the app name is hard to describe in words.
private struct AutomationGuideSheet: View {
    let onOpen: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("Turn Automation on").font(MulberryDesign.font(22, weight: "SemiBold", relativeTo: .title2))
                .accessibilityAddTraits(.isHeader)
            Image("AutomationGuide").resizable().scaledToFit()
                .overlay {
                    // Positions are fractions of the 1120 × 920 crop.
                    GeometryReader { geo in
                        marker(1, center: CGPoint(x: 0.649, y: 0.355), size: CGSize(width: 0.1, height: 0.12), badgeTrailing: true, in: geo.size)
                        marker(2, center: CGPoint(x: 0.873, y: 0.913), size: CGSize(width: 0.2, height: 0.12), badgeTrailing: false, in: geo.size)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(.white.opacity(0.12)))
                .accessibilityLabel("Shortcut trigger with the arrow beside Mulberry and the Automation switch marked")
            VStack(alignment: .leading, spacing: 14) {
                instruction(1, "Tap the arrow beside Mulberry")
                instruction(2, "Switch Automation on")
            }
            Spacer(minLength: 0)
            PrimaryAction(title: "Open Shortcut", action: onOpen)
        }
        .padding(24).padding(.top, 8)
        .presentationDetents([.height(620), .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(MulberryDesign.background)
        .onAppear { pulse = !reduceMotion }
    }

    private func marker(_ number: Int, center: CGPoint, size: CGSize, badgeTrailing: Bool, in frame: CGSize) -> some View {
        let width = frame.width * size.width, height = frame.height * size.height
        let x = frame.width * center.x, y = frame.height * center.y
        return ZStack {
            Capsule().strokeBorder(MulberryDesign.accent, lineWidth: 3)
                .frame(width: width, height: height)
                .scaleEffect(pulse ? 1.12 : 1)
                .animation(pulse ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true) : nil, value: pulse)
                .position(x: x, y: y)
            badge(number).position(x: x + (badgeTrailing ? 1 : -1) * (width / 2 + 18), y: y)
        }
    }

    private func instruction(_ number: Int, _ text: String) -> some View {
        HStack(spacing: 12) {
            badge(number)
            Text(text).font(MulberryDesign.font(16, weight: "Medium", relativeTo: .body))
        }
    }

    private func badge(_ number: Int) -> some View {
        Text("\(number)").font(MulberryDesign.font(13, weight: "SemiBold")).foregroundStyle(.white)
            .frame(width: 24, height: 24).background(MulberryDesign.accent, in: Circle())
    }
}
