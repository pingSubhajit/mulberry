import AppKit
import CanvasDrawing
import CoreText
import IOKit.ps
import ProductCore
import ProductUI
import SwiftUI

// The menu bar icon opens a small iPhone lock screen: the drawing over a wallpaper, exactly where
// the partner sees it, under the time. Its foot holds three glass buttons, as Portal's player
// does: the reactions, the drawings on the desktop in the middle, and Quick Draw; Mulberry and
// Settings sit small in the corners. Reactions arriving while it is open play on it, and the
// Dynamic Island names who sent them. Swiping up from the home bar, or two fingers up on the
// trackpad, slides the lock screen away to a home screen of the two of you: your partner and
// the streak as widgets, and Mulberry, Quick Draw, Reactions and Settings as apps.

/// What the lock screen shows, kept current by the app delegate.
@MainActor
final class LockScreenModel: ObservableObject {
    enum Account: Equatable { case signedOut, unpaired, paired }

    struct Actions {
        let quickDraw: () -> Void
        let openCanvas: () -> Void
        let openStreak: () -> Void
        let toggleOverlay: () -> Void
        let openSettings: () -> Void
        let send: (ReactionType) -> Void
    }

    @Published var account = Account.signedOut
    @Published var partnerName = ""
    @Published var partnerPhotoURL: String?
    /// When the canvas last gained a stroke, text or sticker, from either partner.
    @Published var updatedAt: Date?
    @Published var streak: StreakResponse?
    @Published var overlayShown = false
    /// The reactions' rail, opened from the heart, or from Reactions on the home screen.
    @Published var railOpen = false
    /// The drawing, drawn as a picture at the screen's width so it clips and settles with the rest.
    @Published var drawing: NSImage?
    /// This Mac's battery, for the status bar; nil on a Mac without one.
    @Published var battery: Int?
    /// Past the lock screen, on the home screen.
    @Published var unlocked = false
    /// How far a swipe in progress has carried the lock screen, in points; up is negative.
    @Published var swipe: CGFloat = 0
    var actions: Actions?

    /// A swipe let go: past the screen's middle, counting its speed, it carries on; short of it,
    /// the lock screen settles back.
    func swipeEnded(predicted: CGFloat) {
        let height = LockScreenView.screen.height
        setUnlocked((unlocked ? height : 0) - predicted > height * 0.45)
    }

    /// The lock screen hops up a little and springs back, as iOS hints that it can be swiped away
    /// when its empty glass is tapped. Nothing moves with Reduce Motion.
    func hintSwipe() {
        guard !unlocked, swipe == 0, !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion else { return }
        withAnimation(.timingCurve(0.2, 0.8, 0.3, 1, duration: 0.16)) { swipe = -14 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { [weak self] in
            guard let self, self.swipe == -14 else { return }
            withAnimation(.spring(duration: 0.5, bounce: 0.45)) { self.swipe = 0 }
        }
    }

    func setUnlocked(_ value: Bool) {
        let motion: Animation = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            ? .easeInOut(duration: 0.2) : .spring(duration: 0.38, bounce: 0.1)
        withAnimation(motion) {
            if unlocked != value { railOpen = false }
            unlocked = value
            swipe = 0
        }
    }
}

/// Opens the lock screen under the menu bar icon and puts it away on Esc, a click elsewhere or a
/// second click on the icon.
@MainActor
final class LockScreenPanelController {
    private let model: LockScreenModel
    private let reactions: ReactionsModel
    private let statusButton: () -> NSStatusBarButton?
    private let presence = LockScreenPresence()
    private var panel: LockScreenPanel?
    private var monitors: [Any] = []
    /// Tells the app when the lock screen opens or closes, so reactions can play on it.
    var visibilityChanged: () -> Void = {}

    private(set) var isOpen = false
    private static let hintShownKey = "lockScreenSwipeHintShown"

    init(model: LockScreenModel, reactions: ReactionsModel, statusButton: @escaping () -> NSStatusBarButton?) {
        self.model = model
        self.reactions = reactions
        self.statusButton = statusButton
    }

    /// When pressing the icon took the keyboard from the lock screen and closed it, before the
    /// icon's own click arrived: that click must not open it again. Forgotten once used.
    private var closedFromIcon: Date?
    /// The app the person was working in, given the keyboard back when the lock screen closes.
    private var previousApp: NSRunningApplication?

    func toggle() {
        if isOpen {
            return close(fromToggle: true)
        }
        if let closed = closedFromIcon {
            closedFromIcon = nil
            if Date().timeIntervalSince(closed) < 0.6 {
                return
            }
        }
        open()
    }

    func open() {
        guard !isOpen, let button = statusButton(), let buttonWindow = button.window else { return }
        let panel = panel ?? makePanel()
        self.panel = panel
        let anchor = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
        let visible = buttonWindow.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
        let size = LockScreenView.panelSize
        // The phone's top sits just under the menu bar, centred on the icon, kept on screen.
        let x = min(max(anchor.midX - size.width / 2, visible.minX), visible.maxX - size.width)
        panel.setFrame(NSRect(x: x, y: anchor.minY - size.height, width: size.width, height: size.height), display: false)
        isOpen = true
        closedFromIcon = nil
        let front = NSWorkspace.shared.frontmostApplication
        previousApp = front?.processIdentifier == ProcessInfo.processInfo.processIdentifier ? nil : front
        presence.shown = false
        // It always opens on the lock screen.
        model.battery = MacBattery.level()
        model.unlocked = false
        model.swipe = 0
        model.railOpen = false
        panel.orderFrontRegardless()
        panel.makeKey()
        withAnimation(.timingCurve(0.16, 1, 0.3, 1, duration: 0.42)) { presence.shown = true }
        // The very first time, once it has settled, it shows that it can be swiped away.
        if model.account == .paired, !UserDefaults.standard.bool(forKey: Self.hintShownKey) {
            UserDefaults.standard.set(true, forKey: Self.hintShownKey)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { [weak self] in
                guard let self, self.isOpen else { return }
                self.model.hintSwipe()
            }
        }
        button.highlight(true)
        installMonitors()
        visibilityChanged()
    }

    func close() {
        close(fromToggle: false)
    }

    private func close(fromToggle: Bool) {
        guard isOpen, let panel else { return }
        isOpen = false
        // Only a close the icon's click did not make itself can swallow that click.
        closedFromIcon = !fromToggle && pointerIsOnIcon ? Date() : nil
        model.railOpen = false
        removeMonitors()
        statusButton()?.highlight(false)
        withAnimation(.easeIn(duration: 0.14)) {
            presence.shown = false
        } completion: { [weak self] in
            guard let self, !self.isOpen else { return }
            panel.orderOut(nil)
            self.returnFocus()
        }
        visibilityChanged()
    }

    private func makePanel() -> LockScreenPanel {
        let panel = LockScreenPanel(contentRect: NSRect(origin: .zero, size: LockScreenView.panelSize),
                                    styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .popUpMenu
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.hidesOnDeactivate = false
        panel.isMovable = false
        // Esc folds the reactions' rail first, then locks the home screen, then closes.
        panel.onEscape = { [weak self] in
            guard let self else { return }
            if self.model.railOpen {
                withAnimation(.snappy(duration: 0.2)) { self.model.railOpen = false }
            } else if self.model.unlocked {
                self.model.setUnlocked(false)
            } else {
                self.close()
            }
        }
        // Like a menu bar extra, it goes when Mulberry loses the keyboard: Command-Tab, another
        // Space, Spotlight or Mission Control.
        NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification, object: panel, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }
        let host = NSHostingView(rootView: LockScreenView(model: model, reactions: reactions, presence: presence) { [weak self] in self?.close() })
        host.sizingOptions = []
        panel.contentView = host
        return panel
    }

    /// The lock screen takes the keyboard without making Mulberry the active app. Closing it, the
    /// keyboard goes back to the app the person was in, unless they have since moved on, or one
    /// of Mulberry's own windows has come forward.
    private func returnFocus() {
        guard !NSApp.isActive, let app = previousApp, NSWorkspace.shared.frontmostApplication == app else { return }
        app.activate(options: [])
    }

    private var pointerIsOnIcon: Bool {
        guard let button = statusButton(), let window = button.window else { return false }
        return window.convertToScreen(button.convert(button.bounds, to: nil)).contains(NSEvent.mouseLocation)
    }

    /// A click anywhere but the lock screen puts it away; a click on the icon toggles it there.
    private func installMonitors() {
        if let global = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }) { monitors.append(global) }
        if let local = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] event in
            MainActor.assumeIsolated {
                guard let self else { return }
                if event.window !== self.panel, event.window !== self.statusButton()?.window { self.close() }
            }
            return event
        }) { monitors.append(local) }
        if let scroll = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] event in
            MainActor.assumeIsolated { self?.scrolled(event) ?? false } ? nil : event
        }) { monitors.append(scroll) }
    }

    /// Whether a two-finger swipe that began over the lock screen is carrying it.
    private var swiping = false
    private var swipeVelocity: CGFloat = 0

    /// Two fingers up the trackpad swipe to the home screen, and down swipe back, following the
    /// fingers as a drag of the home bar does. Only a swipe that begins here counts, never a
    /// scroll's momentum or one carried in from elsewhere; a mouse wheel's notch swipes outright.
    private func scrolled(_ event: NSEvent) -> Bool {
        guard event.window === panel, model.account == .paired || model.unlocked else { return false }
        // Up on the fingers, whichever way the system scrolls.
        let delta = event.scrollingDeltaY * (event.isDirectionInvertedFromDevice ? 1 : -1)
        guard event.hasPreciseScrollingDeltas else {
            if abs(delta) > 0.5 { model.setUnlocked(delta < 0) }
            return true
        }
        guard event.momentumPhase.isEmpty else { return swiping }
        if event.phase.contains(.began) {
            swiping = true
            swipeVelocity = 0
        }
        guard swiping else { return false }
        if event.phase.contains(.changed) {
            model.swipe += delta
            swipeVelocity = delta
        }
        if event.phase.contains(.ended) || event.phase.contains(.cancelled) {
            swiping = false
            model.swipeEnded(predicted: model.swipe + swipeVelocity * 12)
        }
        return true
    }

    private func removeMonitors() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors = []
    }
}

/// Whether the lock screen is showing, for its arrival and departure.
@MainActor
final class LockScreenPresence: ObservableObject {
    @Published var shown = false
}

/// A borderless panel that takes the keyboard without bringing Mulberry forward, so Esc closes it
/// and its keys work: 1 to 6 send the reactions, Space shows or hides the drawings on the
/// desktop, D starts Quick Draw, O opens Mulberry, Command-Comma opens Settings, and the Up and
/// Down arrows move between the lock screen and the home screen.
final class LockScreenPanel: NSPanel {
    var onEscape: () -> Void = {}

    override var canBecomeKey: Bool { true }

    override func cancelOperation(_ sender: Any?) {
        onEscape()
    }
}

// MARK: Lock screen

struct LockScreenView: View {
    @ObservedObject var model: LockScreenModel
    @ObservedObject var reactions: ReactionsModel
    @ObservedObject var presence: LockScreenPresence
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// An iPhone 18 Pro's screen, 9 by 19.5, at the size of a menu bar panel.
    static let screen = CGSize(width: 290, height: 628)
    static let bezel: CGFloat = 5
    /// Room around the phone for its shadow: a little above, under the menu bar, more below.
    static let topInset: CGFloat = 4
    static let margin: CGFloat = 34
    static var panelSize: CGSize {
        CGSize(width: screen.width + (bezel + margin) * 2, height: screen.height + bezel * 2 + topInset + margin)
    }
    private static let screenRadius: CGFloat = 45

    /// The clock's tint: a deep mulberry that reads on the wallpaper's pale sky, as iOS lets a
    /// lock screen's clock take a colour from its wallpaper.
    private static let ink = Color(red: 0.43, green: 0.04, blue: 0.10)

    /// How far the lock screen has slid up, in points: rubber-banded past either end.
    private var lift: CGFloat {
        let height = Self.screen.height
        let raw = (model.unlocked ? height : 0) - model.swipe
        if raw < 0 { return raw / 4 }
        if raw > height { return height + (raw - height) / 4 }
        return raw
    }

    private var progress: CGFloat { min(1, max(0, lift / Self.screen.height)) }

    var body: some View {
        ZStack {
            // One wallpaper and drawing for both screens, held still, as iOS holds the wallpaper
            // while the lock screen lifts off it like a pane of glass.
            wallpaper
            drawing
            // The home screen settles in as the wallpaper dims a touch behind its icons.
            Color.black.opacity(0.1 * progress).allowsHitTesting(false)
            HomeScreenView(model: model, ink: Self.ink, swipe: swipeGesture, close: close)
                .scaleEffect(reduceMotion ? 1 : 0.92 + 0.08 * progress)
                .blur(radius: reduceMotion ? 0 : (1 - progress) * 10)
                .opacity(reduceMotion ? progress : min(1, progress * 1.6))
                .allowsHitTesting(model.unlocked)
            // The lock screen's own layer, sliding up and thinning as it goes, with Reduce
            // Motion only fading.
            ZStack {
                // Clicking the lock screen's empty glass hints that it lifts away.
                Color.clear
                    .contentShape(.rect)
                    .onTapGesture { if model.account == .paired { model.hintSwipe() } }
                // The dark foot and its controls let go first, so what lifts is clear glass
                // carrying the clock.
                foot.opacity(reduceMotion ? 1 : max(0, 1 - progress * 2.4))
                VStack(spacing: 0) {
                    clock.padding(.top, 52)
                    Spacer(minLength: 0)
                }
                .opacity(reduceMotion ? 1 : max(0, 1 - progress * 1.2))
            }
            .offset(y: reduceMotion ? 0 : -lift)
            .opacity(reduceMotion ? 1 - progress : 1)
            .allowsHitTesting(!model.unlocked)
            VStack(spacing: 0) {
                // The island and the status bar stay put; on the home screen the island locks again.
                ZStack(alignment: .top) {
                    StatusBar(unlocked: model.unlocked, battery: model.battery)
                    DynamicIsland(reactions: reactions, partnerName: model.partnerName)
                        .contentShape(.capsule)
                        .onTapGesture { if model.unlocked { model.setUnlocked(false) } }
                }
                .padding(.top, 10)
                Spacer(minLength: 0)
            }
            ReactionStage(reactions: reactions, surface: .menuBar)
        }
        .frame(width: Self.screen.width, height: Self.screen.height)
        .clipShape(.rect(cornerRadius: Self.screenRadius, style: .continuous))
        .padding(Self.bezel)
        .background(.black, in: .rect(cornerRadius: Self.screenRadius + Self.bezel, style: .continuous))
        .overlay {
            // The frame's edge catching the light.
            RoundedRectangle(cornerRadius: Self.screenRadius + Self.bezel, style: .continuous)
                .strokeBorder(.white.opacity(0.16), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.35), radius: 18, y: 10)
        .scaleEffect(presence.shown || reduceMotion ? 1 : 0.94, anchor: .top)
        .blur(radius: presence.shown || reduceMotion ? 0 : 6)
        .opacity(presence.shown ? 1 : 0)
        .padding(.top, Self.topInset)
        .frame(width: Self.panelSize.width, height: Self.panelSize.height, alignment: .top)
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Mulberry")
        .background { shortcuts }
    }

    // MARK: Screen

    private var wallpaper: some View {
        Group {
            if let image = LockScreenArtwork.wallpaper {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                mulberryRed
            }
        }
        .frame(width: Self.screen.width, height: Self.screen.height)
        .clipped()
        .accessibilityHidden(true)
    }

    /// The shared canvas at the wallpaper's width, as the partner's phone lays it over theirs.
    /// The drawing as the partner's iPhone lays it over its wallpaper: in the proportions of an
    /// iPhone's drawing area, about 402 by 752 points between its navigation bar and its tools,
    /// fitted whole inside the screen and centred.
    static let canvasSize = CanvasGeometry.placement(canvas: CGSize(width: 402, height: 752), inside: screen).size

    @ViewBuilder private var drawing: some View {
        if let image = model.drawing {
            Image(nsImage: image)
                .resizable()
                .frame(width: Self.canvasSize.width, height: Self.canvasSize.height)
                .frame(width: Self.screen.width, height: Self.screen.height)
                .allowsHitTesting(false)
                .accessibilityLabel("Your shared drawing")
        }
    }

    /// The panel's keys, as on a keyboard-driven menu bar extra.
    private var shortcuts: some View {
        ZStack {
            if model.account == .paired {
                ForEach(Array(ReactionType.allCases.enumerated()), id: \.element) { index, type in
                    Button(type.sendLabel) { model.actions?.send(type) }
                        .keyboardShortcut(KeyEquivalent(Character("\(index + 1)")), modifiers: [])
                }
                Button("Quick Draw") { close(); model.actions?.quickDraw() }
                    .keyboardShortcut("d", modifiers: [])
                Button("Drawings on the desktop") { model.actions?.toggleOverlay() }
                    .keyboardShortcut(.space, modifiers: [])
            }
            Button("Open Mulberry") { close(); model.actions?.openCanvas() }
                .keyboardShortcut("o", modifiers: [])
            if model.account == .paired || model.unlocked {
                Button("Home Screen") { model.setUnlocked(true) }
                    .keyboardShortcut(.upArrow, modifiers: [])
                Button("Lock Screen") { model.setUnlocked(false) }
                    .keyboardShortcut(.downArrow, modifiers: [])
            }
            Button("Settings") { close(); model.actions?.openSettings() }
                .keyboardShortcut(",", modifiers: .command)
        }
        .opacity(0)
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
    }

    /// The date in the clock's ink, over the time in Liquid Glass, as iOS 26 draws its lock screen.
    private var clock: some View {
        TimelineView(.everyMinute) { context in
            VStack(spacing: 6) {
                // Short, as the iPhone writes it: "Fri 30 Oct".
                Text("\(context.date.formatted(.dateTime.weekday(.abbreviated))) \(context.date.formatted(.dateTime.day())) \(context.date.formatted(.dateTime.month(.abbreviated)))")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.22), radius: 3, y: 1)
                GlassClock(date: context.date)
            }
            .accessibilityElement(children: .combine)
        }
    }

    /// Dragging the home bar carries the lock screen with the pointer, as a finger does.
    private var swipeGesture: AnyGesture<DragGesture.Value> {
        AnyGesture(DragGesture(minimumDistance: 2, coordinateSpace: .global)
            .onChanged { value in model.swipe = value.translation.height }
            .onEnded { value in model.swipeEnded(predicted: value.predictedEndTranslation.height) })
    }

    // MARK: Foot

    /// A dark blur rising from the bottom edge, holding the three glass buttons, and Mulberry and
    /// Settings small in the corners either side of the home indicator.
    private var foot: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                switch model.account {
                case .paired: controls
                case .unpaired: invitation("Invite Your Partner")
                case .signedOut: invitation("Sign In")
                }
                HStack(alignment: .center) {
                    FootIcon(symbol: "macwindow", help: "Open Mulberry") {
                        close()
                        model.actions?.openCanvas()
                    }
                    Spacer()
                    HomeBar(swipe: swipeGesture, enabled: model.account == .paired)
                    Spacer()
                    FootIcon(symbol: "gearshape.fill", help: "Settings") {
                        close()
                        model.actions?.openSettings()
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 22)
                .padding(.bottom, 8)
            }
            .frame(maxWidth: .infinity)
            .background(alignment: .bottom) { footScrim }
        }
    }

    private var footScrim: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
                .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.55)],
                                     startPoint: .top, endPoint: .bottom))
            LinearGradient(stops: [.init(color: .black.opacity(0), location: 0), .init(color: .black.opacity(0.62), location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .frame(height: 260)
        .allowsHitTesting(false)
    }

    /// Portal's row of glass: the reactions, the drawings on the desktop larger in the middle,
    /// and Quick Draw. The heart unfolds the reactions' rail above the row.
    private var controls: some View {
        VStack(spacing: 14) {
            if model.railOpen {
                ReactionRail { type in
                    model.actions?.send(type)
                    withAnimation(.snappy(duration: 0.2)) { model.railOpen = false }
                }
                .transition(.asymmetric(
                    insertion: .scale(scale: 0.6, anchor: UnitPoint(x: 0.22, y: 1)).combined(with: .opacity),
                    removal: .scale(scale: 0.8, anchor: UnitPoint(x: 0.22, y: 1)).combined(with: .opacity)))
            }
            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 10) {
                    GlassRowButton(size: 54, help: "Reactions") {
                        withAnimation(reduceMotion ? .easeOut(duration: 0.15) : .spring(duration: 0.35, bounce: 0.3)) {
                            model.railOpen.toggle()
                        }
                    } label: {
                        Image(systemName: model.railOpen ? "heart.fill" : "heart")
                            .font(.system(size: 19, weight: .semibold))
                            .contentTransition(.symbolEffect(.replace))
                    }
                    GlassRowButton(size: 76, help: model.overlayShown ? "Hide drawings on the desktop" : "Show drawings on the desktop") {
                        model.actions?.toggleOverlay()
                    } label: {
                        DesktopDrawingsIcon(shown: model.overlayShown)
                    }
                    .accessibilityLabel("Drawings on the desktop")
                    .accessibilityValue(model.overlayShown ? "On" : "Off")
                    .accessibilityAddTraits(.isToggle)
                    GlassRowButton(size: 54, help: "Quick Draw") {
                        close()
                        model.actions?.quickDraw()
                    } label: {
                        Image(systemName: "scribble.variable").font(.system(size: 19, weight: .semibold))
                    }
                }
            }
        }
        .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(duration: 0.35, bounce: 0.3), value: model.railOpen)
    }

    /// Signed out or unpaired: one way forward, in the canvas window.
    private func invitation(_ title: String) -> some View {
        Button {
            close()
            model.actions?.openCanvas()
        } label: {
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 22)
                .frame(height: 40)
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(mulberryRed.opacity(0.55)).interactive(), in: .capsule)
    }
}

/// The lock screen's time in real Liquid Glass, as iOS 26 draws its clock: clear, neutral glass
/// in the shape of the numerals themselves, moulded at its edges, lit along the top and shaded
/// along the bottom, and casting a soft shadow onto the wallpaper.
private struct GlassClock: View {
    let date: Date

    var body: some View {
        let time = date.formatted(.dateTime.hour(.defaultDigits(amPM: .omitted)).minute())
        let numerals = GlyphOutline(time)
        ZStack {
            // The shadow the glass casts.
            numerals.fill(.white.opacity(0.04))
                .shadow(color: .black.opacity(0.28), radius: 9, y: 6)
            Color.clear.glassEffect(.regular, in: numerals)
            // The moulded rim: light along the upper edges, shade along the lower, inside the glass.
            numerals
                .stroke(LinearGradient(colors: [.white.opacity(0.95), .white.opacity(0.15), .clear],
                                       startPoint: .top, endPoint: .bottom), lineWidth: 3.2)
                .clipShape(numerals)
            numerals
                .stroke(LinearGradient(colors: [.clear, .black.opacity(0.22)], startPoint: .center, endPoint: .bottom), lineWidth: 4)
                .clipShape(numerals)
        }
        .frame(width: numerals.size.width, height: numerals.size.height)
        .accessibilityElement()
        .accessibilityLabel(time)
    }
}

/// A line of text as a shape: the lock screen clock's face, rounded and bold, set tight, with
/// figures of one width so the time holds still as it changes.
private struct GlyphOutline: Shape {
    let outline: Path
    let size: CGSize

    init(_ string: String) {
        let base = NSFont.systemFont(ofSize: 92, weight: .bold)
        let descriptor = (base.fontDescriptor.withDesign(.rounded) ?? base.fontDescriptor).addingAttributes([.featureSettings: [[
            NSFontDescriptor.FeatureKey.typeIdentifier: kNumberSpacingType,
            NSFontDescriptor.FeatureKey.selectorIdentifier: kMonospacedNumbersSelector
        ]]])
        let font = NSFont(descriptor: descriptor, size: 92) ?? base
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: [.font: font, .kern: -2.5]))
        var ascent: CGFloat = 0
        _ = CTLineGetTypographicBounds(line, &ascent, nil, nil)
        let path = CGMutablePath()
        for run in CTLineGetGlyphRuns(line) as? [CTRun] ?? [] {
            let attributes = CTRunGetAttributes(run) as NSDictionary
            guard let runFont = attributes[kCTFontAttributeName] else { continue }
            let count = CTRunGetGlyphCount(run)
            var glyphs = [CGGlyph](repeating: 0, count: count)
            var positions = [CGPoint](repeating: .zero, count: count)
            CTRunGetGlyphs(run, CFRange(location: 0, length: count), &glyphs)
            CTRunGetPositions(run, CFRange(location: 0, length: count), &positions)
            for (glyph, position) in zip(glyphs, positions) {
                // Core Text draws up from the baseline; the shape runs down from its top.
                var transform = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: position.x, ty: ascent - position.y)
                if let shape = CTFontCreatePathForGlyph(runFont as! CTFont, glyph, &transform) { path.addPath(shape) }
            }
        }
        // Framed tight to the numerals, so the clock sits by the date as the text one did.
        let bounds = path.boundingBoxOfPath
        outline = Path(path).offsetBy(dx: -bounds.minX, dy: -bounds.minY)
        size = CGSize(width: ceil(bounds.width), height: ceil(bounds.height))
    }

    func path(in rect: CGRect) -> Path {
        outline.offsetBy(dx: rect.minX + (rect.width - size.width) / 2, dy: rect.minY + (rect.height - size.height) / 2)
    }
}

/// The iPhone's status bar: on the lock screen a carrier's name at the left, on the home screen
/// the time; at the right the signal, Wi-Fi and this Mac's battery.
private struct StatusBar: View {
    let unlocked: Bool
    let battery: Int?

    var body: some View {
        HStack(alignment: .top) {
            Group {
                if unlocked {
                    TimelineView(.everyMinute) { context in
                        Text(context.date, format: .dateTime.hour(.defaultDigits(amPM: .omitted)).minute())
                    }
                } else {
                    Text("Mulberry")
                }
            }
            .font(.system(size: 13, weight: .semibold))
            .frame(height: 26)
            .padding(.leading, 26)
            Spacer()
            HStack(spacing: 4) {
                Image(systemName: "cellularbars").opacity(0.4)
                Image(systemName: "wifi")
                BatteryGlyph(level: battery ?? 100)
            }
            .font(.system(size: 11, weight: .semibold))
            .frame(height: 26)
            .padding(.trailing, 20)
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.22), radius: 3, y: 1)
        .accessibilityHidden(true)
    }
}

/// The iPhone's battery: a white pill with the level written inside it.
private struct BatteryGlyph: View {
    let level: Int

    var body: some View {
        HStack(spacing: 1) {
            Text("\(level)")
                .font(.system(size: 9.5, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(.black)
                .frame(width: 23, height: 12)
                .background(.white, in: .rect(cornerRadius: 3.5, style: .continuous))
            Capsule().fill(.white.opacity(0.5)).frame(width: 1.5, height: 4)
        }
    }
}

/// This Mac's battery level, as a percentage, from its power sources.
enum MacBattery {
    static func level() -> Int? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let sources = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for source in sources {
            guard let description = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                  let current = description[kIOPSCurrentCapacityKey] as? Int,
                  let maximum = description[kIOPSMaxCapacityKey] as? Int, maximum > 0 else { continue }
            return min(100, current * 100 / maximum)
        }
        return nil
    }
}

// MARK: Home screen

/// Past the lock screen, the two of you: your partner and the streak as widgets, and Mulberry,
/// Quick Draw, Reactions and Settings as apps, over the wallpaper and drawing the two screens share.
private struct HomeScreenView: View {
    @ObservedObject var model: LockScreenModel
    let ink: Color
    let swipe: AnyGesture<DragGesture.Value>
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                HStack(spacing: 14) {
                    partnerWidget
                    streakWidget
                }
                .padding(.top, 54)
                HStack(alignment: .top, spacing: 0) {
                    AppIcon(title: "Mulberry", ink: ink) {
                        close()
                        model.actions?.openCanvas()
                    } icon: {
                        Image(nsImage: NSApp.applicationIconImage).resizable().scaledToFit()
                    }
                    Spacer(minLength: 0)
                    AppIcon(title: "Quick Draw", ink: ink) {
                        close()
                        model.actions?.quickDraw()
                    } icon: {
                        GlyphIcon(symbol: "scribble.variable", ink: ink)
                    }
                    Spacer(minLength: 0)
                    AppIcon(title: "Reactions", ink: ink) {
                        withAnimation(reduceMotion ? .easeOut(duration: 0.15) : .spring(duration: 0.35, bounce: 0.3)) {
                            model.railOpen.toggle()
                        }
                    } icon: {
                        GlyphIcon(symbol: model.railOpen ? "heart.fill" : "heart", ink: ink)
                    }
                    Spacer(minLength: 0)
                    AppIcon(title: "Settings", ink: ink) {
                        close()
                        model.actions?.openSettings()
                    } icon: {
                        GlyphIcon(symbol: "gearshape.fill", ink: ink)
                    }
                }
                .padding(.top, 22)
                Spacer(minLength: 0)
                if model.railOpen {
                    ReactionRail { type in
                        model.actions?.send(type)
                        withAnimation(.snappy(duration: 0.2)) { model.railOpen = false }
                    }
                    .transition(.scale(scale: 0.7, anchor: .bottom).combined(with: .opacity))
                    .padding(.bottom, 12)
                }
                HomeBar(swipe: swipe, enabled: true)
                    .padding(.bottom, 8)
            }
            .padding(.horizontal, 20)
            .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(duration: 0.35, bounce: 0.3), value: model.railOpen)
        }
        .frame(width: LockScreenView.screen.width, height: LockScreenView.screen.height)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Home screen")
    }

    /// Your partner's photo, filling the widget inside a thin edge of its glass, with their name
    /// and when the canvas last changed over its foot. Opens the canvas.
    private var partnerWidget: some View {
        WidgetTile(help: partnerSummary, inset: 6) {
            close()
            model.actions?.openCanvas()
        } content: {
            PartnerPhoto(url: model.partnerPhotoURL, name: model.partnerName)
                // A dark gradient up from the bottom, so the name reads over any photo.
                .overlay(alignment: .bottom) {
                    LinearGradient(colors: [.black.opacity(0), .black.opacity(0.68)], startPoint: .top, endPoint: .bottom)
                        .frame(height: 64)
                }
                .overlay(alignment: .bottomLeading) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(model.partnerName)
                            .font(.custom("Poppins-SemiBold", size: 14, relativeTo: .headline))
                            .lineLimit(1)
                        if let updated = model.updatedAt {
                            TimelineView(.everyMinute) { _ in
                                Text(updated, format: .relative(presentation: .named, unitsStyle: .abbreviated))
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.8))
                                    .lineLimit(1)
                            }
                        }
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 9)
                    .padding(.bottom, 8)
                }
                .clipShape(.rect(cornerRadius: 18, style: .continuous))
        }
        .accessibilityLabel(partnerSummary)
    }

    private var partnerSummary: String {
        guard let updated = model.updatedAt else { return model.partnerName }
        return "\(model.partnerName), updated \(updated.formatted(.relative(presentation: .named, unitsStyle: .wide)))"
    }

    /// The streak and this week, with the bolt standing the widget's full height at its right
    /// edge, running out past it. Opens the streak.
    private var streakWidget: some View {
        WidgetTile(help: "Streak", inset: 0) {
            close()
            model.actions?.openStreak()
        } content: {
            ZStack(alignment: .leading) {
                if let bolt = StreakWidgetArtwork.bolt {
                    Image(nsImage: bolt)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 150, height: 150)
                        .position(x: 106, y: 59)
                        .accessibilityHidden(true)
                }
                let days = model.streak?.currentStreakDays ?? 0
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)
                    Text(days, format: .number)
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .contentTransition(.numericText(value: Double(days)))
                    Text(days == 1 ? "day" : "days")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ink.opacity(0.72))
                        .padding(.top, -4)
                    if let streak = model.streak {
                        HStack(spacing: 3) {
                            ForEach(streak.week.prefix(7), id: \.day) { day in
                                let past = day.day < streak.today
                                Circle()
                                    .fill(day.hasActivity ? AnyShapeStyle(mulberryRed) : AnyShapeStyle(ink.opacity(past ? 0.14 : 0)))
                                    .overlay { if !day.hasActivity && !past { Circle().strokeBorder(mulberryRed, lineWidth: day.day == streak.today ? 1.4 : 0.9) } }
                                    .frame(width: 7, height: 7)
                            }
                        }
                        .padding(.top, 7)
                        .accessibilityHidden(true)
                    }
                }
                .padding(13)
            }
            .frame(width: 118, height: 118)
            .clipShape(.rect(cornerRadius: 24, style: .continuous))
        }
        .accessibilityLabel("Streak: \(model.streak?.currentStreakDays ?? 0) days")
    }
}

/// The partner's photo filling its frame, or their initial on Mulberry red until it loads.
private struct PartnerPhoto: View {
    let url: String?
    let name: String

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            ZStack {
                mulberryRed
                Text(name.prefix(1).uppercased())
                    .font(.custom("Poppins-SemiBold", size: 40, relativeTo: .largeTitle))
                    .foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)
    }
}

/// The streak popover's large bolt, shipped in Resources/Streak.
@MainActor
private enum StreakWidgetArtwork {
    static let bolt: NSImage? = AppResources.url("Streak/streak_bolt_large.png").flatMap(NSImage.init(contentsOf:))
}

/// A small home screen widget: a square of light glass, its words in the clock's ink, its
/// content inset from the glass's edge.
private struct WidgetTile<Content: View>: View {
    let help: String
    var inset: CGFloat = 13
    let action: () -> Void
    @ViewBuilder let content: Content
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            content
                .foregroundStyle(Color(red: 0.43, green: 0.04, blue: 0.10))
                .padding(inset)
                .frame(width: 118, height: 118)
                .contentShape(.rect(cornerRadius: 24, style: .continuous))
                .contentShape(.focusEffect, .rect(cornerRadius: 24, style: .continuous))
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(.white.opacity(hovered ? 0.4 : 0.3)).interactive(), in: .rect(cornerRadius: 24, style: .continuous))
        .onHover { hovered = $0 }
        .animation(.easeOut(duration: 0.15), value: hovered)
        .help(help)
    }
}

/// An app on the home screen: its icon, and its name under it.
private struct AppIcon<Icon: View>: View {
    let title: String
    let ink: Color
    let action: () -> Void
    @ViewBuilder let icon: Icon

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                icon.frame(width: 52, height: 52)
                Text(title)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(ink)
                    .lineLimit(1)
                    .fixedSize()
            }
            .frame(width: 58)
            .contentShape(.rect)
            .contentShape(.focusEffect, .rect(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(AppIconStyle())
        .help(title)
        .accessibilityLabel(title)
    }
}

/// An app icon pressed in, as the home screen's do.
private struct AppIconStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.snappy(duration: 0.12), value: configuration.isPressed)
    }
}

/// A clear glass app icon with one glyph, as iOS draws icons in its clear style.
private struct GlyphIcon: View {
    let symbol: String
    let ink: Color

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 21, weight: .semibold))
            .foregroundStyle(ink)
            .contentTransition(.symbolEffect(.replace))
            .frame(width: 52, height: 52)
            .glassEffect(.regular.tint(.white.opacity(0.3)), in: .rect(cornerRadius: 13, style: .continuous))
    }
}

/// The home bar: drag it up to leave the lock screen, or down to come back.
/// Under the pointer it widens and brightens, and the pointer becomes an open hand, the Mac's
/// sign that something can be dragged.
private struct HomeBar: View {
    let swipe: AnyGesture<DragGesture.Value>
    let enabled: Bool
    @State private var hovered = false

    var body: some View {
        let lit = enabled && hovered
        Capsule().fill(.white.opacity(lit ? 1 : 0.9))
            .frame(width: lit ? 124 : 106, height: lit ? 5.5 : 4.5)
            .shadow(color: .white.opacity(lit ? 0.35 : 0), radius: 6)
            .frame(width: 150, height: 26)
            .contentShape(.rect)
            .onHover { hovered = $0 }
            .pointerStyle(enabled ? .grabIdle : nil)
            .animation(.spring(duration: 0.3, bounce: 0.3), value: lit)
            .gesture(swipe, isEnabled: enabled)
            .accessibilityHidden(true)
    }
}

// MARK: Pieces

/// The black pill at the top of the screen. Reactions arriving while the lock screen is open widen
/// it to name who sent them, as the iPhone's island does.
private struct DynamicIsland: View {
    @ObservedObject var reactions: ReactionsModel
    let partnerName: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var arriving: ReactionType? {
        guard reactions.surface == .menuBar, let batch = reactions.arrival?.batch else { return nil }
        return ReactionType.allCases.max { batch.count($0) < batch.count($1) }
    }

    var body: some View {
        HStack(spacing: 8) {
            if let arriving {
                Text(partnerName)
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .transition(.opacity)
                Spacer(minLength: 0)
                ReactionArtwork(type: arriving, size: 20)
                    .transition(.scale(scale: 0.4).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 12)
        .frame(width: arriving == nil ? 90 : 168, height: 26)
        .background(.black, in: .capsule)
        .animation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(duration: 0.45, bounce: 0.3), value: arriving)
        .accessibilityHidden(arriving == nil)
        .onChange(of: arriving) { _, type in
            guard let type else { return }
            AccessibilityNotification.Announcement("\(partnerName) sent \(type.spamNoun)").post()
        }
    }
}

/// One of the foot's glass buttons, as Portal's player draws them: the same clear glass over the
/// blur whatever its state, with a white glyph that shows the state itself.
private struct GlassRowButton<Label: View>: View {
    let size: CGFloat
    let help: String
    let action: () -> Void
    @ViewBuilder let label: Label

    var body: some View {
        Button(action: action) {
            label
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .contentShape(.circle)
                .contentShape(.focusEffect, .circle)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .help(help)
        .accessibilityLabel(help)
    }
}

/// The drawings on the desktop: a display with a hand-drawn squiggle on its screen. Turning them on
/// draws the squiggle in; turning them off un-draws it, dims the display and cuts a slash across it.
private struct DesktopDrawingsIcon: View {
    let shown: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let line = StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round)

    var body: some View {
        ZStack {
            ZStack {
                RoundedRectangle(cornerRadius: 4.5, style: .continuous)
                    .stroke(style: line)
                    .frame(width: 32, height: 22)
                    .offset(y: -3)
                // The stand, below the screen's edge.
                Path { path in
                    path.move(to: CGPoint(x: 18, y: 27))
                    path.addLine(to: CGPoint(x: 18, y: 30.5))
                    path.move(to: CGPoint(x: 12.5, y: 31))
                    path.addLine(to: CGPoint(x: 23.5, y: 31))
                }
                .stroke(style: line)
                Squiggle()
                    .trim(from: 0, to: shown ? 1 : 0)
                    .stroke(style: StrokeStyle(lineWidth: 2.1, lineCap: .round, lineJoin: .round))
                    .frame(width: 21, height: 11)
                    .offset(y: -3)
            }
            .frame(width: 36, height: 36)
            .opacity(shown ? 1 : 0.6)
            // The slash, cut clear of the display beneath it.
            Slash().trim(from: 0, to: shown ? 0 : 1)
                .stroke(style: StrokeStyle(lineWidth: 6.5, lineCap: .round))
                .blendMode(.destinationOut)
            Slash().trim(from: 0, to: shown ? 0 : 1)
                .stroke(style: line)
        }
        .frame(width: 36, height: 36)
        .compositingGroup()
        .animation(reduceMotion ? .easeOut(duration: 0.15) : .timingCurve(0.22, 1, 0.36, 1, duration: shown ? 0.6 : 0.32), value: shown)
    }

    /// A hand-drawn stroke: a rise, a cursive loop and a trailing wave, as a pen writes.
    private struct Squiggle: Shape {
        func path(in rect: CGRect) -> Path {
            func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * rect.width, y: rect.minY + y * rect.height) }
            var path = Path()
            path.move(to: point(0, 0.85))
            path.addCurve(to: point(0.36, 0.08), control1: point(0.14, 0.6), control2: point(0.22, 0.04))
            // A cursive loop back on itself, as a pen turns.
            path.addCurve(to: point(0.3, 0.8), control1: point(0.52, 0.14), control2: point(0.44, 0.92))
            path.addCurve(to: point(0.66, 0.42), control1: point(0.2, 0.68), control2: point(0.48, 0.3))
            path.addCurve(to: point(1, 0.46), control1: point(0.8, 0.52), control2: point(0.9, 0.3))
            return path
        }
    }

    private struct Slash: Shape {
        func path(in rect: CGRect) -> Path {
            var path = Path()
            path.move(to: CGPoint(x: rect.minX + 4, y: rect.minY + 3))
            path.addLine(to: CGPoint(x: rect.maxX - 4, y: rect.maxY - 3))
            return path
        }
    }
}

/// The six reactions on a glass rail above the row: each lifts under the pointer and sends on a
/// click.
private struct ReactionRail: View {
    let send: (ReactionType) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(ReactionType.allCases) { type in
                LockScreenReaction(type: type) { send(type) }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .glassEffect(.regular, in: .capsule)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Reactions")
    }
}

/// A reaction on the rail.
private struct LockScreenReaction: View {
    let type: ReactionType
    let send: () -> Void
    @State private var hovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: send) {
            ReactionArtwork(type: type, size: 30)
                .frame(width: 38, height: 38)
                .contentShape(.circle)
                .contentShape(.focusEffect, .circle)
        }
        .buttonStyle(ReactionButtonStyle())
        .scaleEffect(hovered && !reduceMotion ? 1.16 : 1)
        .offset(y: hovered && !reduceMotion ? -3 : 0)
        .onHover { hovered = $0 }
        .animation(.spring(duration: 0.25, bounce: 0.4), value: hovered)
        .help(type.sendLabel)
        .accessibilityLabel(type.sendLabel)
    }
}

/// Mulberry and Settings in the foot's corners, as small as the status bar's icons: quiet until
/// the pointer is over them.
private struct FootIcon: View {
    let symbol: String
    let help: String
    let action: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(hovered ? 1 : 0.85))
                .frame(width: 30, height: 30)
                .background(Circle().fill(.white.opacity(hovered ? 0.16 : 0)))
                .contentShape(.circle)
                .contentShape(.focusEffect, .circle)
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
        .animation(.easeOut(duration: 0.12), value: hovered)
        .help(help)
        .accessibilityLabel(help)
    }
}

/// The iPhone's sign-in wallpaper, shipped in Resources/MenuBar.
@MainActor
enum LockScreenArtwork {
    static let wallpaper: NSImage? = AppResources.url("MenuBar/lock_screen_wallpaper.jpg").flatMap(NSImage.init(contentsOf:))
}
