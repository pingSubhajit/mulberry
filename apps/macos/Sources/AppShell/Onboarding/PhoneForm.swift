import SwiftUI

/// iOS's own form controls, drawn at the onboarding phone's size in dark mode: an inset grouped
/// list of rows, and the wheel date picker and number pad, which macOS does not have.
enum PhoneForm {
    static let cell = Color(white: 0.13)
    static let separator = Color(white: 0.25)
    static let rowHeight: CGFloat = 30
    static let inset: CGFloat = 12
    static let radius: CGFloat = 12
    static let font = Font.system(size: 11.5)
    static let secondary = Color.white.opacity(0.5)
    /// Mulberry red lifted for dark mode, as iOS lifts its tints, so a value being edited reads.
    static let activeTint = Color(red: 0.97, green: 0.38, blue: 0.45)

}

/// One card of rows.
struct PhoneFormSection<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .background(PhoneForm.cell, in: .rect(cornerRadius: PhoneForm.radius, style: .continuous))
    }
}

/// The hairline between rows, starting where their text does.
struct PhoneFormSeparator: View {
    var body: some View {
        Rectangle().fill(PhoneForm.separator).frame(height: 0.5).padding(.leading, PhoneForm.inset)
    }
}

/// A label at the row's leading edge, and what it holds after it.
struct PhoneFormRow<Content: View>: View {
    let label: String
    var labelWidth: CGFloat?
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 10) {
            Text(label)
                .foregroundStyle(.white)
                .frame(width: labelWidth, alignment: .leading)
            content
        }
        .font(PhoneForm.font)
        .padding(.horizontal, PhoneForm.inset)
        .frame(height: PhoneForm.rowHeight)
    }
}

// MARK: Wheel date picker

/// iOS's wheel date picker: month, day and year on turning drums, in the order the locale writes a
/// date, with the chosen row on a band across the middle. Dates after today spring back to it.
struct WheelDatePicker: View {
    @Binding var date: Date
    /// The drum the keyboard turns, by its place from the leading edge; nil when the keyboard is
    /// elsewhere.
    var keyboardColumn: Int?

    private let calendar = Calendar.current
    static let height: CGFloat = 118

    private enum Column: Hashable { case month, day, year }

    /// The locale's order: "MMMM d, y" in the US, "d MMMM y" in India and Britain.
    private static let order: [Column] = {
        let pattern = DateFormatter.dateFormat(fromTemplate: "yMMMMd", options: 0, locale: .current) ?? "MMMM d y"
        let found: [(Column, String.Index)] = [(.month, "M"), (.day, "d"), (.year, "y")].compactMap { column, letter in
            pattern.firstIndex(of: Character(letter)).map { (column, $0) }
        }
        return found.count == 3 ? found.sorted { $0.1 < $1.1 }.map(\.0) : [.month, .day, .year]
    }()

    private static var dayFirst: Bool {
        (order.firstIndex(of: .day) ?? 1) < (order.firstIndex(of: .month) ?? 0)
    }

    /// A date a step further along on one drum, as the arrow keys turn it.
    static func step(_ date: Date, column index: Int, by amount: Int) -> Date {
        let calendar = Calendar.current
        let component: Calendar.Component = switch order[max(0, min(order.count - 1, index))] {
        case .month: .month
        case .day: .day
        case .year: .year
        }
        var parts = calendar.dateComponents([.year, .month, .day], from: date)
        switch component {
        case .month: parts.month = ((parts.month ?? 1) - 1 + amount + 12) % 12 + 1
        case .year: parts.year = (parts.year ?? 2000) + amount
        default:
            let days = calendar.range(of: .day, in: .month, for: date)?.count ?? 31
            parts.day = ((parts.day ?? 1) - 1 + amount + days) % days + 1
        }
        let first = calendar.date(from: DateComponents(year: parts.year, month: parts.month, day: 1)) ?? date
        let days = calendar.range(of: .day, in: .month, for: first)?.count ?? 28
        parts.day = min(parts.day ?? 1, days)
        let chosen = calendar.date(from: parts) ?? date
        return min(chosen, calendar.startOfDay(for: .now))
    }

    static let columnCount = 3

    var body: some View {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        let year = parts.year ?? 2000, month = parts.month ?? 1, day = parts.day ?? 1
        let thisYear = calendar.component(.year, from: .now)
        let days = calendar.range(of: .day, in: .month, for: date)?.count ?? 31
        ZStack {
            // The band the chosen date sits on.
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(.white.opacity(0.09))
                .frame(height: WheelColumn.row)
                .padding(.horizontal, 8)
            HStack(spacing: 0) {
                ForEach(Array(Self.order.enumerated()), id: \.element) { index, column in
                    let keyed = keyboardColumn == index
                    switch column {
                    case .month:
                        // Leaning toward the day, wherever the locale puts it.
                        WheelColumn(name: "Month", items: Array(1...12), selection: binding(year, month, day, .month),
                                    alignment: Self.dayFirst ? .leading : .trailing, keyed: keyed) { calendar.monthSymbols[$0 - 1] }
                            .frame(width: 76)
                    case .day:
                        WheelColumn(name: "Day", items: Array(1...days), selection: binding(year, month, day, .day),
                                    alignment: .center, keyed: keyed) { "\($0)" }
                            .frame(width: 30)
                    case .year:
                        WheelColumn(name: "Year", items: Array((thisYear - 80)...thisYear), selection: binding(year, month, day, .year),
                                    alignment: .leading, keyed: keyed) { String($0) }
                            .frame(width: 46)
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
        .frame(height: Self.height)
        // The drums turn away out of sight at the top and the foot.
        .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.28),
                                     .init(color: .black, location: 0.72), .init(color: .clear, location: 1)],
                             startPoint: .top, endPoint: .bottom))
    }

    private func binding(_ year: Int, _ month: Int, _ day: Int, _ column: Column) -> Binding<Int> {
        Binding {
            switch column {
            case .month: month
            case .day: day
            case .year: year
            }
        } set: { value in
            var next = (year: year, month: month, day: day)
            switch column {
            case .month: next.month = value
            case .day: next.day = value
            case .year: next.year = value
            }
            set(next.year, next.month, next.day)
        }
    }

    /// The day is kept within its month, and the date within today.
    private func set(_ year: Int, _ month: Int, _ day: Int) {
        let first = calendar.date(from: DateComponents(year: year, month: month, day: 1)) ?? date
        let days = calendar.range(of: .day, in: .month, for: first)?.count ?? 28
        let chosen = calendar.date(from: DateComponents(year: year, month: month, day: min(day, days))) ?? date
        let today = calendar.startOfDay(for: .now)
        withAnimation(.snappy(duration: 0.3)) { date = min(chosen, today) }
    }
}

/// One drum of the wheel: rows that snap to the band, turning away and fading toward its edges.
private struct WheelColumn: View {
    let name: String
    let items: [Int]
    @Binding var selection: Int
    let alignment: Alignment
    /// The drum the arrow keys turn: its chosen value is tinted.
    var keyed = false
    let text: (Int) -> String
    @State private var position: Int?

    static let row: CGFloat = 22

    var body: some View {
        ScrollView(.vertical) {
            LazyVStack(spacing: 0) {
                ForEach(items, id: \.self) { item in
                    Text(text(item))
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(keyed && item == selection ? PhoneForm.activeTint : .white)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: alignment)
                        .padding(.horizontal, 4)
                        .frame(height: Self.row)
                        .contentShape(.rect)
                        .onTapGesture { withAnimation(.snappy(duration: 0.3)) { position = item } }
                        // On a drum: rows away from the middle tilt back, shrink and dim.
                        .visualEffect { content, proxy in
                            let height = proxy.bounds(of: .scrollView(axis: .vertical))?.height ?? WheelDatePicker.height
                            let middle = proxy.frame(in: .scrollView(axis: .vertical)).midY
                            let offset = max(-1, min(1, (middle - height / 2) / (height / 2)))
                            return content
                                .rotation3DEffect(.degrees(Double(offset) * -55), axis: (x: 1, y: 0, z: 0), perspective: 0.15)
                                .scaleEffect(1 - abs(offset) * 0.1)
                                .opacity(1 - abs(offset) * 0.62)
                        }
                }
            }
            .scrollTargetLayout()
        }
        .scrollIndicators(.never)
        .scrollTargetBehavior(.viewAligned)
        .scrollPosition(id: $position, anchor: .center)
        .contentMargins(.vertical, (WheelDatePicker.height - Self.row) / 2, for: .scrollContent)
        .onAppear { position = selection }
        .onChange(of: position) { _, value in
            if let value, value != selection { selection = value }
        }
        // A date clamped elsewhere, such as one after today, turns the drum back to it.
        .onChange(of: selection) { _, value in
            if position != value { withAnimation(.snappy(duration: 0.3)) { position = value } }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
        .accessibilityValue(text(selection))
        .accessibilityAdjustableAction { direction in
            guard let index = items.firstIndex(of: selection) else { return }
            switch direction {
            case .increment: if index + 1 < items.count { selection = items[index + 1] }
            case .decrement: if index > 0 { selection = items[index - 1] }
            @unknown default: break
            }
        }
    }
}

// MARK: Number pad

/// iOS's number pad in dark mode, measured from the real one and drawn at the phone's size: a
/// tray rising from the foot of the screen, digits with their letters, and delete.
struct PhoneNumberPad: View {
    let type: (Int) -> Void
    let delete: () -> Void

    /// The tray's height, for content to keep clear of it.
    static let height: CGFloat = 218
    private static let key = CGSize(width: 91, height: 34.5)
    private static let gap: CGFloat = 4.5
    private static let letters = ["", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"]

    var body: some View {
        VStack(spacing: Self.gap) {
            ForEach(0..<3) { row in
                HStack(spacing: Self.gap) {
                    ForEach(1...3, id: \.self) { column in digit(row * 3 + column) }
                }
            }
            HStack(spacing: Self.gap) {
                Color.clear.frame(width: Self.key.width, height: Self.key.height)
                digit(0)
                Button(action: delete) {
                    Image(systemName: "delete.left")
                        .font(.system(size: 16))
                        .foregroundStyle(.white)
                        .frame(width: Self.key.width, height: Self.key.height)
                        .contentShape(.rect)
                }
                .buttonStyle(PadKeyStyle(filled: false))
                .accessibilityLabel("Delete")
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 17)
        .frame(width: LockScreenView.screen.width, height: Self.height)
        .background(Color(white: 0.125), in: UnevenRoundedRectangle(topLeadingRadius: 26, topTrailingRadius: 26, style: .continuous))
    }

    private func digit(_ value: Int) -> some View {
        Button { type(value) } label: {
            VStack(spacing: -1) {
                Text("\(value)").font(.system(size: 18))
                if !Self.letters[value].isEmpty {
                    Text(Self.letters[value]).font(.system(size: 6.5, weight: .semibold)).tracking(1.4)
                }
            }
            .foregroundStyle(.white)
            .frame(width: Self.key.width, height: Self.key.height)
            .contentShape(.rect)
        }
        .buttonStyle(PadKeyStyle(filled: true))
        .accessibilityLabel("\(value)")
    }
}

/// A key: grey, lighter while pressed, as iOS's keys flash.
private struct PadKeyStyle: ButtonStyle {
    let filled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if filled || configuration.isPressed {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color(white: configuration.isPressed ? 0.42 : 0.267))
                }
            }
    }
}
