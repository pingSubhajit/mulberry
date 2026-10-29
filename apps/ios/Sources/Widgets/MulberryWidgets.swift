import ActivityKit
import SwiftUI
import WidgetKit

@main
struct MulberryWidgets: WidgetBundle {
    var body: some Widget {
        ReactionLiveActivity()
    }
}

/// The partner's reactions, while they are fresh: the latest one and a running count in the
/// Dynamic Island, and who sent what on the Lock Screen.
struct ReactionLiveActivity: Widget {
    private static let accent = Color(red: 179 / 255, green: 19 / 255, blue: 41 / 255)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ReactionActivityAttributes.self) { context in
            ReactionBanner(name: context.attributes.partnerName, photo: context.attributes.partnerPhoto, state: context.state)
                .padding(16)
                // A clear tint lets the system draw the Lock Screen's own glass behind it,
                // instead of the default opaque black.
                .activityBackgroundTint(.clear)
                .activitySystemActionForegroundColor(Self.accent)
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                // Top row, either side of the camera: who sent them, and a one-tap reply.
                DynamicIslandExpandedRegion(.leading) {
                    PartnerAvatar(photo: context.attributes.partnerPhoto, name: context.attributes.partnerName, tint: state.latestType.tint)
                        .accessibilityLabel(context.attributes.partnerName)
                        .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ReplyButton(state: state)
                        .padding(.trailing, 6)
                }
                // The reactions themselves are the hero: one sticker for each, risen into the band
                // under the camera between the photo and the reply, with who sent them beneath.
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 2) {
                        StickerPile(state: state)
                        Text("\(context.attributes.partnerName) sent love")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, -18)
                    .padding(.bottom, 4)
                }
            } compactLeading: {
                Image("BrandMark").resizable().scaledToFit()
                    .frame(height: 22)
                    .accessibilityLabel("Mulberry")
            } compactTrailing: {
                // The latest reaction, and how many have arrived in all, in the reaction's colour.
                // Live Activities animate only as their content changes, so each new reaction pops
                // in and the count rolls; nothing can loop on its own.
                HStack(spacing: 4) {
                    Sticker(type: state.latestType, size: 22)
                        .id(state.total)
                        .transition(.scale(scale: 0.3).combined(with: .opacity))
                    Text("\(state.total)")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(state.latestType.tint)
                        .contentTransition(.numericText(value: Double(state.total)))
                }
                .animation(.spring(duration: 0.5, bounce: 0.5), value: state.total)
                .accessibilityElement(children: .combine)
            } minimal: {
                Sticker(type: state.latestType, size: 22)
            }
        }
    }
}

/// The Lock Screen's line: who sent love, and a small pile of what they sent.
private struct ReactionBanner: View {
    let name: String
    let photo: Data?
    let state: ReactionActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 12) {
            PartnerAvatar(photo: photo, name: name, tint: state.latestType.tint, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline).lineLimit(1)
                Text("Sent you love").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            .privacySensitive()
            Spacer(minLength: 8)
            StickerPile(state: state, style: .row)
        }
    }
}

/// Every reaction as its own sticker, piled with a tilt like ones pressed onto a page: two hearts
/// are two hearts. The latest kind lands on top. Past six, every kind keeps a place and the
/// most frequent ones keep more.
private struct StickerPile: View {
    /// The island's hero pile, or the small row on the Lock Screen.
    enum Style { case hero, row }

    let state: ReactionActivityAttributes.ContentState
    var style = Style.hero
    /// As many as fit between the photo and the reply button, or beside the Lock Screen's line.
    private var capacity: Int { style == .hero ? 6 : 5 }

    private struct Piece: Identifiable {
        let type: ReactionType
        let copy: Int
        var id: String { "\(type.rawValue)-\(copy)" }
        /// Copies of one kind alternate their tilt, height and size, so they read as separate stickers.
        var tilt: Double { type.tilt * (copy.isMultiple(of: 2) ? 1 : -1) + Double(copy) * 4 }
        var lift: CGFloat { type.lift * (copy.isMultiple(of: 2) ? 1 : -1) * (style == .hero ? 2 : 1) }
        let style: Style
        var size: CGFloat {
            switch style {
            case .hero: copy.isMultiple(of: 2) ? 62 : 56
            case .row: copy.isMultiple(of: 2) ? 34 : 30
            }
        }
    }

    private var pieces: [Piece] {
        var shown = Dictionary(uniqueKeysWithValues: state.kinds.map { ($0.type, 0) })
        var room = capacity
        // Deal one sticker to each kind in turn until the pile is full.
        while room > 0, state.kinds.contains(where: { shown[$0.type, default: 0] < $0.count }) {
            for kind in state.kinds where room > 0 && shown[kind.type, default: 0] < kind.count {
                shown[kind.type, default: 0] += 1
                room -= 1
            }
        }
        let kinds = state.kinds.map(\.type)
        let order = kinds.filter { $0 != state.latestType } + kinds.filter { $0 == state.latestType }
        return order.flatMap { type in (0..<shown[type, default: 0]).map { Piece(type: type, copy: $0, style: style) } }
    }

    var body: some View {
        HStack(spacing: style == .hero ? -24 : -12) {
            ForEach(pieces) { piece in
                Sticker(type: piece.type, size: piece.size)
                    .rotationEffect(.degrees(piece.tilt))
                    .offset(y: piece.lift)
                    .shadow(color: .black.opacity(style == .hero ? 0.5 : 0.3), radius: style == .hero ? 4 : 2, y: style == .hero ? 3 : 1.5)
                    .transition(.scale(scale: 0.2).combined(with: .opacity))
            }
        }
        .frame(height: style == .hero ? 72 : 44)
        .animation(.spring(duration: 0.5, bounce: 0.45), value: state)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(state.kinds.map { "\($0.count) \($0.type.spamNoun)" }.joined(separator: ", "))
    }
}

/// One tap returns the latest reaction, without opening Mulberry: the reaction in a tinted
/// circle with a small reply arrow. Once sent, a check holds until more reactions arrive.
private struct ReplyButton: View {
    let state: ReactionActivityAttributes.ContentState

    var body: some View {
        let sent = state.repliedWith.flatMap(ReactionType.init(rawValue:))
        let type = sent ?? state.latestType
        Button(intent: SendReactionBackIntent(type)) {
            ZStack {
                Circle().fill(type.tint.opacity(sent == nil ? 0.3 : 0.18))
                if sent == nil {
                    Sticker(type: type, size: 24)
                        .overlay(alignment: .bottomTrailing) {
                            Image(systemName: "arrowshape.turn.up.backward.fill")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(.black)
                                .padding(3)
                                .background(Circle().fill(type.tint))
                                .offset(x: 7, y: 5)
                        }
                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                } else {
                    Image(systemName: "checkmark")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(type.tint)
                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(sent != nil)
        .animation(.spring(duration: 0.35, bounce: 0.4), value: sent)
        .accessibilityLabel(sent == nil ? "Send \(type.spamNoun) back" : "Sent")
    }
}

/// The partner's photo, or their initial on the latest reaction's colour when there is none.
private struct PartnerAvatar: View {
    let photo: Data?
    let name: String
    let tint: Color
    var size: CGFloat = 34

    var body: some View {
        Group {
            if let photo, let image = UIImage(data: photo) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Text(name.prefix(1).uppercased())
                    .font(.system(.headline, design: .rounded, weight: .bold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(tint.opacity(0.35))
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

/// A reaction's artwork, the same as the app's reactions, held still.
private struct Sticker: View {
    let type: ReactionType
    let size: CGFloat

    var body: some View {
        Image(type.stickerName).resizable().scaledToFit()
            .frame(width: size, height: size)
            .accessibilityLabel(type.spamNoun)
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

    var stickerName: String { "Sticker" + rawValue.prefix(1) + rawValue.dropFirst().lowercased() }

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

