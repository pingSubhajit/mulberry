import AppKit
import Combine
import ProductCore
import SwiftUI

/// Where the partner's reactions show while no canvas is open, as the iPhone shows them in the
/// Dynamic Island: grown out of the notch on a Mac that has one, else as a glass card under the
/// menu bar icon. With neither, nothing shows; the reactions play when a canvas next opens.
///
/// The notch opens with the reactions, then settles into a small island around the notch until
/// they are seen; the card shows for a few seconds and leaves a dot on the menu bar icon.
@MainActor
final class ReactionIslandController {
    private let reactions: ReactionsModel
    private let partner: () -> (name: String, photoURL: String?)
    private let statusButton: () -> NSStatusBarButton?
    private let openCanvas: () -> Void
    private let state = IslandState()
    private var panel: NSPanel?
    private var presentation: Presentation?
    private var collapseTask: Task<Void, Never>?
    private var cancellables: Set<AnyCancellable> = []
    private var badge: NSView?

    private enum Presentation: Equatable {
        case notch(NSRect)
        case menuBar
    }

    /// How long the reactions stay open before settling.
    private static let openFor: Duration = .seconds(6)

    init(
        reactions: ReactionsModel,
        partner: @escaping () -> (name: String, photoURL: String?),
        statusButton: @escaping () -> NSStatusBarButton?,
        openCanvas: @escaping () -> Void
    ) {
        self.reactions = reactions
        self.partner = partner
        self.statusButton = statusButton
        self.openCanvas = openCanvas
        state.reply = { [weak reactions] in Task { await reactions?.reply() } }
        state.open = { [weak self] in self?.openCanvas() }
        state.hover = { [weak self] hovering in self?.hovered(hovering) }
        reactions.$announcement.dropFirst().sink { [weak self] _ in
            DispatchQueue.main.async { self?.announce() }
        }.store(in: &cancellables)
        reactions.$waiting.sink { [weak self] waiting in
            DispatchQueue.main.async { self?.waitingChanged(waiting) }
        }.store(in: &cancellables)
        reactions.$surface.combineLatest(reactions.$arrival).sink { [weak self] surface, arrival in
            // Looking at a canvas, the reactions play there instead.
            if surface != nil || arrival != nil { DispatchQueue.main.async { self?.dismiss() } }
        }.store(in: &cancellables)
    }

    // MARK: Announcing

    private func announce() {
        guard let waiting = reactions.waiting, reactions.surface == nil, reactions.arrival == nil else { return }
        let person = partner()
        state.waiting = waiting
        state.partnerName = person.name
        state.partnerPhotoURL = person.photoURL
        if let notch = Self.notchRect() {
            present(.notch(notch))
        } else if statusButtonIsVisible {
            present(.menuBar)
        }
    }

    private func waitingChanged(_ waiting: ReactionsModel.Waiting?) {
        updateBadge()
        if let waiting {
            // A reply or a new count updates what is showing in place.
            state.waiting = waiting
        } else {
            dismiss()
        }
    }

    private func present(_ presentation: Presentation) {
        if self.presentation != presentation {
            panel?.orderOut(nil)
            state.phase = .hidden
            panel = makePanel(for: presentation)
            self.presentation = presentation
            panel?.orderFrontRegardless()
            // One frame as the bare notch first, so the island grows out of it.
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(30))
                guard let self, self.presentation == presentation else { return }
                withAnimation(IslandMotion.open) { self.state.phase = .expanded }
            }
        } else {
            panel?.orderFrontRegardless()
            withAnimation(IslandMotion.open) { state.phase = .expanded }
        }
        scheduleSettle()
    }

    private func scheduleSettle() {
        collapseTask?.cancel()
        collapseTask = Task { [weak self] in
            try? await Task.sleep(for: Self.openFor)
            guard !Task.isCancelled else { return }
            self?.settle()
        }
    }

    /// After the reactions have had their moment: the notch keeps a small island, the card goes.
    private func settle() {
        guard !state.hovering else { return }
        switch presentation {
        case .notch:
            withAnimation(IslandMotion.settle) { state.phase = .compact }
        case .menuBar:
            hidePanel()
        case nil:
            break
        }
    }

    private func hovered(_ hovering: Bool) {
        state.hovering = hovering
        if hovering {
            collapseTask?.cancel()
            if presentation != nil, state.phase == .compact {
                withAnimation(IslandMotion.open) { state.phase = .expanded }
            }
        } else {
            collapseTask?.cancel()
            collapseTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(1.2))
                guard !Task.isCancelled else { return }
                self?.settle()
            }
        }
    }

    /// Seen on a canvas, or another way: the island closes back into the notch.
    private func dismiss() {
        collapseTask?.cancel()
        guard presentation != nil else { return }
        hidePanel()
    }

    private func hidePanel() {
        let closing = panel
        withAnimation(IslandMotion.close) { state.phase = .hidden }
        presentation = nil
        panel = nil
        Task {
            try? await Task.sleep(for: .seconds(0.55))
            closing?.orderOut(nil)
        }
    }

    // MARK: The menu bar icon

    private var statusButtonIsVisible: Bool {
        guard let button = statusButton(), let window = button.window, window.screen != nil else { return false }
        return window.isVisible && window.occlusionState.contains(.visible)
    }

    /// A Mulberry red dot on the icon while reactions wait to be seen.
    private func updateBadge() {
        guard let button = statusButton() else { return }
        if reactions.waiting == nil {
            badge?.removeFromSuperview()
            badge = nil
            return
        }
        guard badge == nil else { return }
        let dot = NSView(frame: NSRect(x: button.bounds.maxX - 9, y: button.bounds.maxY - 9, width: 7, height: 7))
        dot.wantsLayer = true
        dot.layer?.backgroundColor = NSColor(srgbRed: 179 / 255, green: 19 / 255, blue: 41 / 255, alpha: 1).cgColor
        dot.layer?.cornerRadius = 3.5
        dot.autoresizingMask = [.minXMargin, .minYMargin]
        button.addSubview(dot)
        badge = dot
    }

    // MARK: Panels

    /// The notch on the display in use, in screen coordinates, if it has one.
    private static func notchRect() -> NSRect? {
        guard let screen = NSScreen.main, screen.safeAreaInsets.top > 0,
              let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea else { return nil }
        let height = screen.safeAreaInsets.top
        return NSRect(x: left.maxX, y: screen.frame.maxY - height, width: right.minX - left.maxX, height: height)
    }

    private func makePanel(for presentation: Presentation) -> NSPanel {
        let frame: NSRect
        let content: AnyView
        switch presentation {
        case let .notch(notch):
            let size = NotchIsland.panelSize(notch: notch.size)
            frame = NSRect(x: notch.midX - size.width / 2, y: notch.maxY - size.height, width: size.width, height: size.height)
            content = AnyView(NotchIsland(state: state, notch: notch.size))
        case .menuBar:
            let size = MenuBarReactionCard.panelSize
            let anchor = statusButton().flatMap { button in button.window.map { $0.convertToScreen(button.convert(button.bounds, to: nil)) } } ?? .zero
            let visible = statusButton()?.window?.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
            let x = min(max(anchor.midX - size.width / 2, visible.minX + 8), visible.maxX - size.width - 8)
            frame = NSRect(x: x, y: anchor.minY - 6 - size.height, width: size.width, height: size.height)
            content = AnyView(MenuBarReactionCard(state: state))
        }
        let panel = NSPanel(contentRect: frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        // Above the menu bar, on every Space, and over full-screen apps.
        panel.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 8)
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        panel.hidesOnDeactivate = false
        panel.isMovable = false
        let host = NSHostingView(rootView: content)
        host.sizingOptions = []
        panel.contentView = host
        return panel
    }
}

/// What the island shows, shared by the notch and the card.
@MainActor
final class IslandState: ObservableObject {
    enum Phase { case hidden, compact, expanded }

    @Published var phase = Phase.hidden
    @Published var waiting: ReactionsModel.Waiting?
    @Published var partnerName = ""
    @Published var partnerPhotoURL: String?
    var hovering = false
    var reply: () -> Void = {}
    var open: () -> Void = {}
    var hover: (Bool) -> Void = { _ in }
}

// MARK: Notch

/// The Dynamic Island's motion: a springy grow, a firmer settle, and a quick tuck back in.
private enum IslandMotion {
    static let open = Animation.spring(response: 0.5, dampingFraction: 0.74)
    static let settle = Animation.spring(response: 0.46, dampingFraction: 0.86)
    static let close = Animation.spring(response: 0.4, dampingFraction: 0.95)
    /// Contents arrive once the shape has mostly grown, and leave before it shrinks.
    static let contentIn = Animation.spring(response: 0.38, dampingFraction: 0.9).delay(0.12)
    static let contentOut = Animation.easeOut(duration: 0.12)
}

/// Contents appear out of a soft blur, as the island's do.
private struct Materialise: ViewModifier {
    let hidden: Bool
    var scale: CGFloat = 0.82
    var anchor: UnitPoint = .top
    var slide: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .blur(radius: hidden ? 10 : 0)
            .opacity(hidden ? 0 : 1)
            .scaleEffect(hidden ? scale : 1, anchor: anchor)
            .offset(x: hidden ? slide : 0)
    }
}

private extension AnyTransition {
    /// Open contents: they bloom from the top as the island grows, and go before it shrinks.
    static var islandContent: AnyTransition {
        .asymmetric(
            insertion: .modifier(active: Materialise(hidden: true), identity: Materialise(hidden: false)).animation(IslandMotion.contentIn),
            removal: .modifier(active: Materialise(hidden: true, scale: 0.9), identity: Materialise(hidden: false)).animation(IslandMotion.contentOut)
        )
    }

    /// A flank's contents slide out from behind the notch, and back behind it.
    static func islandFlank(towardNotch slide: CGFloat) -> AnyTransition {
        .asymmetric(
            insertion: .modifier(
                active: Materialise(hidden: true, scale: 0.6, anchor: .center, slide: slide),
                identity: Materialise(hidden: false, anchor: .center)
            ).animation(IslandMotion.contentIn),
            removal: .modifier(
                active: Materialise(hidden: true, scale: 0.6, anchor: .center, slide: slide),
                identity: Materialise(hidden: false, anchor: .center)
            ).animation(IslandMotion.contentOut)
        )
    }
}

/// The Dynamic Island, grown from the MacBook's notch: black, so it reads as the notch itself.
/// Open, the partner's photo and a reply sit either side of the camera, and the reactions with
/// who sent them below it. Settled, the Mulberry mark and the latest reaction with the count
/// flank the notch, as the island's compact view does.
private struct NotchIsland: View {
    @ObservedObject var state: IslandState
    let notch: CGSize

    /// Each side of the notch when settled: room for the mark, or a reaction and its count.
    private static let flank: CGFloat = 62
    private static let openWidth: CGFloat = 440
    private static let openBelow: CGFloat = 122
    /// The open island's inner margin, generous as the island's is.
    private static let inset: CGFloat = 22

    static func panelSize(notch: CGSize) -> CGSize {
        CGSize(width: max(openWidth, notch.width + 2 * flank) + 80, height: notch.height + openBelow + 50)
    }

    private var size: CGSize {
        switch state.phase {
        case .hidden: notch
        case .compact: CGSize(width: notch.width + 2 * Self.flank, height: notch.height)
        case .expanded: CGSize(width: max(Self.openWidth, notch.width + 2 * Self.flank), height: notch.height + Self.openBelow)
        }
    }

    private var bottomRadius: CGFloat {
        switch state.phase {
        case .hidden, .compact: min(12, notch.height * 0.34)
        case .expanded: 34
        }
    }

    var body: some View {
        let expanded = state.phase == .expanded
        let ear: CGFloat = expanded ? 12 : 7
        ZStack(alignment: .top) {
            // The curves where it meets the menu bar sit outside the island's own width.
            IslandShape(bottomRadius: bottomRadius, earRadius: ear)
                .fill(.black)
                .frame(width: size.width + 2 * ear, height: size.height)
                .shadow(color: .black.opacity(expanded ? 0.4 : 0), radius: 22, y: 10)
            if let waiting = state.waiting {
                if expanded {
                    open(waiting)
                        .frame(width: size.width, alignment: .top)
                        .transition(.islandContent)
                }
                if state.phase == .compact {
                    markFlank
                        .frame(width: size.width, alignment: .leading)
                        .transition(.islandFlank(towardNotch: 26))
                }
                if state.phase == .compact {
                    countFlank(waiting)
                        .frame(width: size.width, alignment: .trailing)
                        .transition(.islandFlank(towardNotch: -26))
                }
            }
        }
        .frame(width: size.width, height: size.height, alignment: .top)
        .contentShape(IslandShape(bottomRadius: bottomRadius, earRadius: 0))
        .onHover { state.hover($0) }
        .onTapGesture { state.open() }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(state.waiting.map { "\(state.partnerName) sent \(ReactionArrivalView.announcement($0.batch).replacingOccurrences(of: "Your partner sent ", with: ""))" } ?? "")
    }

    private func open(_ waiting: ReactionsModel.Waiting) -> some View {
        VStack(spacing: 0) {
            // Either side of the camera: who sent them, and a one-click reply.
            HStack(alignment: .center) {
                IslandAvatar(url: state.partnerPhotoURL, name: state.partnerName, tint: waiting.latest.tint, size: min(28, notch.height - 8))
                Spacer(minLength: notch.width + 16)
                IslandReplyButton(waiting: waiting, size: min(30, notch.height - 6), action: state.reply)
            }
            .padding(.horizontal, Self.inset)
            .frame(height: notch.height)
            Spacer(minLength: 0)
            VStack(spacing: 6) {
                StickerPile(waiting: waiting)
                Text("\(state.partnerName) sent love")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
            }
            .padding(.horizontal, Self.inset)
            .padding(.bottom, 18)
        }
        .frame(height: notch.height + Self.openBelow)
    }

    private var glyph: CGFloat { min(20, notch.height - 14) }

    /// Left of the notch when settled: the Mulberry mark, centred in its side.
    private var markFlank: some View {
        Group {
            if let mark = SettingsArtwork.image("BrandMark.svg") {
                Image(nsImage: mark).resizable().scaledToFit()
                    .frame(width: glyph, height: glyph)
            }
        }
        .frame(width: Self.flank, height: notch.height)
        .padding(.leading, 4)
    }

    /// Right of the notch: the latest reaction and how many have arrived, centred in its side.
    private func countFlank(_ waiting: ReactionsModel.Waiting) -> some View {
        HStack(spacing: 4) {
            ReactionArtwork(type: waiting.latest, size: glyph)
                .id(waiting.latest)
                .transition(.scale(scale: 0.3).combined(with: .opacity))
            Text("\(waiting.total)")
                .font(.system(size: 14, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(waiting.latest.tint)
                .contentTransition(.numericText(value: Double(waiting.total)))
        }
        .frame(width: Self.flank, height: notch.height)
        .padding(.trailing, 4)
        .animation(.spring(duration: 0.5, bounce: 0.5), value: waiting.total)
    }
}

/// The notch's outline grown downward: square to the top of the screen, with little outward
/// curves where it meets the menu bar, and rounded at the bottom.
private struct IslandShape: Shape {
    var bottomRadius: CGFloat
    var earRadius: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(bottomRadius, earRadius) }
        set { bottomRadius = newValue.first; earRadius = newValue.second }
    }

    func path(in rect: CGRect) -> Path {
        let ear = min(earRadius, rect.width / 4)
        let bottom = min(bottomRadius, rect.height / 2, (rect.width - 2 * ear) / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addQuadCurve(to: CGPoint(x: rect.minX + ear, y: rect.minY + ear), control: CGPoint(x: rect.minX + ear, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + ear, y: rect.maxY - bottom))
        path.addQuadCurve(to: CGPoint(x: rect.minX + ear + bottom, y: rect.maxY), control: CGPoint(x: rect.minX + ear, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - ear - bottom, y: rect.maxY))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - ear, y: rect.maxY - bottom), control: CGPoint(x: rect.maxX - ear, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - ear, y: rect.minY + ear))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY), control: CGPoint(x: rect.maxX - ear, y: rect.minY))
        path.closeSubpath()
        return path
    }
}

// MARK: Menu bar card

/// The reactions under the menu bar icon, on a Mac without a notch: the iPhone's Lock Screen
/// line on Liquid Glass, with the reply beside it.
private struct MenuBarReactionCard: View {
    @ObservedObject var state: IslandState

    static let panelSize = CGSize(width: 360, height: 110)

    var body: some View {
        ZStack(alignment: .top) {
            if state.phase != .hidden, let waiting = state.waiting {
                card(waiting)
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.92, anchor: .top).combined(with: .opacity).combined(with: .offset(y: -8)),
                        removal: .opacity.combined(with: .offset(y: -6))
                    ))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func card(_ waiting: ReactionsModel.Waiting) -> some View {
        HStack(spacing: 12) {
            IslandAvatar(url: state.partnerPhotoURL, name: state.partnerName, tint: waiting.latest.tint, size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(state.partnerName)
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
                Text("Sent you love")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            StickerPile(waiting: waiting, style: .row)
            IslandReplyButton(waiting: waiting, size: 34, action: state.reply)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(width: Self.panelSize.width - 20)
        .glassEffect(.regular, in: .rect(cornerRadius: 22, style: .continuous))
        .contentShape(.rect(cornerRadius: 22, style: .continuous))
        .onHover { state.hover($0) }
        .onTapGesture { state.open() }
        .padding(.top, 4)
        .accessibilityElement(children: .contain)
    }
}

// MARK: Pieces

/// Every reaction as its own sticker, piled with a tilt like ones pressed onto a page: two hearts
/// are two hearts. The latest kind lands on top. Past the pile's room, every kind keeps a place
/// and the most frequent keep more. The iPhone's island holds them still; here they play.
private struct StickerPile: View {
    enum Style { case hero, row }

    let waiting: ReactionsModel.Waiting
    var style = Style.hero
    private var capacity: Int { style == .hero ? 6 : 4 }

    private struct Piece: Identifiable {
        let type: ReactionType
        let copy: Int
        let style: Style
        var id: String { "\(type.rawValue)-\(copy)" }
        var tilt: Double { type.tilt * (copy.isMultiple(of: 2) ? 1 : -1) + Double(copy) * 4 }
        var lift: CGFloat { type.lift * (copy.isMultiple(of: 2) ? 1 : -1) * (style == .hero ? 2 : 1) }
        var size: CGFloat {
            switch style {
            case .hero: copy.isMultiple(of: 2) ? 54 : 48
            case .row: copy.isMultiple(of: 2) ? 32 : 28
            }
        }
    }

    private var pieces: [Piece] {
        let kinds = waiting.kinds
        var shown = Dictionary(uniqueKeysWithValues: kinds.map { ($0.type, 0) })
        var room = capacity
        // Deal one sticker to each kind in turn until the pile is full.
        while room > 0, kinds.contains(where: { shown[$0.type, default: 0] < $0.count }) {
            for kind in kinds where room > 0 && shown[kind.type, default: 0] < kind.count {
                shown[kind.type, default: 0] += 1
                room -= 1
            }
        }
        let types = kinds.map(\.type)
        let order = types.filter { $0 != waiting.latest } + types.filter { $0 == waiting.latest }
        return order.flatMap { type in (0..<shown[type, default: 0]).map { Piece(type: type, copy: $0, style: style) } }
    }

    var body: some View {
        HStack(spacing: style == .hero ? -20 : -12) {
            ForEach(pieces) { piece in
                ReactionArtwork(type: piece.type, size: piece.size)
                    .rotationEffect(.degrees(piece.tilt))
                    .offset(y: piece.lift)
                    .shadow(color: .black.opacity(style == .hero ? 0.5 : 0.3), radius: style == .hero ? 4 : 2, y: style == .hero ? 3 : 1.5)
                    .transition(.scale(scale: 0.2).combined(with: .opacity))
            }
        }
        .frame(height: style == .hero ? 64 : 40)
        .animation(.spring(duration: 0.5, bounce: 0.45), value: waiting)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(waiting.kinds.map { "\($0.count) \($0.type.spamNoun)" }.joined(separator: ", "))
    }
}

/// One click returns the latest reaction: the reaction in a tinted circle with a small reply
/// arrow. Once sent, a check holds until more reactions arrive.
private struct IslandReplyButton: View {
    let waiting: ReactionsModel.Waiting
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        let sent = waiting.repliedWith
        let type = sent ?? waiting.latest
        Button(action: action) {
            ZStack {
                Circle().fill(type.tint.opacity(sent == nil ? 0.3 : 0.18))
                if sent == nil {
                    ReactionArtwork(type: type, size: size * 0.56)
                        .overlay(alignment: .bottomTrailing) {
                            Image(systemName: "arrowshape.turn.up.backward.fill")
                                .font(.system(size: size * 0.2, weight: .bold))
                                .foregroundStyle(.black)
                                .padding(2.5)
                                .background(Circle().fill(type.tint))
                                .offset(x: size * 0.16, y: size * 0.12)
                        }
                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                } else {
                    Image(systemName: "checkmark")
                        .font(.system(size: size * 0.4, weight: .bold))
                        .foregroundStyle(type.tint)
                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                }
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(sent != nil)
        .animation(.spring(duration: 0.35, bounce: 0.4), value: sent)
        .help(sent == nil ? "Send \(type.spamNoun) back" : "Sent")
        .accessibilityLabel(sent == nil ? "Send \(type.spamNoun) back" : "Sent")
    }
}

/// The partner's photo, or their initial on the latest reaction's colour.
private struct IslandAvatar: View {
    let url: String?
    let name: String
    let tint: Color
    let size: CGFloat

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Text(name.prefix(1).uppercased())
                .font(.system(size: size * 0.45, weight: .bold, design: .rounded))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(tint.opacity(0.35))
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

extension ReactionType {
    /// The colour of the reaction's artwork, so a count beside it matches.
    var tint: Color {
        switch self {
        case .heart, .kiss: Color(red: 0.91, green: 0.33, blue: 0.27)
        case .hug, .smile, .laugh: Color(red: 0.98, green: 0.79, blue: 0.24)
        case .sparkle: Color(red: 0.98, green: 0.83, blue: 0.33)
        }
    }

    /// Each kind keeps its own tilt and height in the pile, so it never jumps as others arrive.
    var tilt: Double {
        switch self {
        case .heart: -10
        case .hug: 8
        case .kiss: -6
        case .smile: 11
        case .laugh: -8
        case .sparkle: 6
        }
    }

    var lift: CGFloat {
        switch self {
        case .heart, .kiss, .laugh: 3
        case .hug, .smile, .sparkle: -3
        }
    }
}
