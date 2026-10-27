import SwiftUI

// Isolated visual harness. It never signs in or touches the real account/pair.
private struct PreviewTokens: SessionTokenStore {
    func load() -> SessionTokens? { .init(accessToken: "preview", refreshToken: "preview", userId: "preview") }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}
private actor PreviewTransport: BackendTransport {
    var scenario = "active"
    func set(_ value: String) { scenario = value }
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        if scenario == "offline" { throw URLError(.notConnectedToInternet) }
        let lost = scenario == "lost"
        let days = scenario == "new" || lost ? 0 : scenario == "level" ? 2 : 1
        let today = ServerDate.localDay()
        let calendar = Calendar.current
        let last = ServerDate.localDay(calendar.date(byAdding: .day, value: lost ? -2 : 0, to: Date())!)
        let start = calendar.date(byAdding: .day, value: 1 - calendar.component(.weekday, from: Date()), to: Date())!
        let week: [[String: Any]] = (0..<7).map {
            let day = ServerDate.localDay(calendar.date(byAdding: .day, value: $0, to: start)!)
            let yesterday = ServerDate.localDay(calendar.date(byAdding: .day, value: -1, to: Date())!)
            return ["day": day, "hasActivity": days > 0 && day == today || lost && day == last || scenario == "level" && day == yesterday]
        }
        let data = try JSONSerialization.data(withJSONObject: [
            "today": today, "currentStreakDays": days, "previousStreakDays": lost ? 8 : days,
            "lastActivityDay": scenario == "new" ? NSNull() : last as Any,
            "hasActivityToday": days > 0, "week": week
        ])
        return (data, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}
@main struct StreakPreview: App {
    var body: some Scene { WindowGroup { StreakPreviewRoot() } }
}
private struct StreakPreviewRoot: View {
    @StateObject private var model = StreakModel(defaults: UserDefaults(suiteName: "streak-visual-fixture")!)
    @State private var streakOpen = false
    @State private var pairingOpen = false
    @State private var largeType = false
    @State private var lastTap = ""
    private let transport = PreviewTransport()
    private var api: BackendClient { try! BackendClient(baseURL: URL(string: "https://preview.invalid")!, tokenStore: PreviewTokens(), transport: transport) }
    var body: some View {
        NavigationStack {
            VStack {
                Text("Isolated streak preview").font(.headline)
                HStack {
                    Button("New") { load("new") }
                    Button("Active") { load("active") }
                    Button("Lost") { load("lost") }
                    Button("Offline") { load("offline") }
                }.buttonStyle(.bordered)
                Toggle("Large text", isOn: $largeType)
                ZStack {
                    RoundedRectangle(cornerRadius: 24).fill(MulberryDesign.canvas)
                    Button("Canvas touch check: \(lastTap)") { lastTap = "passed" }
                    StreakCanvasCelebration(streaks: model) { streakOpen = true }
                }
                Button("Level up") { Task { await transport.set("level"); await model.refresh(api: api) } }
                    .buttonStyle(.borderedProminent)
                Button("Pairing confirmed") { pairingOpen = true }.buttonStyle(.bordered)
            }.padding()
                .toolbar { ToolbarItem(placement: .topBarTrailing) {
                    StreakPill(streaks: model, fallbackDays: 0) { streakOpen = true }
                } }
        }
        .tint(MulberryDesign.accent)
        .dynamicTypeSize(largeType ? .accessibility3 : .large)
        .sheet(isPresented: $streakOpen) {
            StreakSheet(streaks: model) { await model.refresh(api: api) }
                .dynamicTypeSize(largeType ? .accessibility3 : .large)
        }
        .sheet(isPresented: $pairingOpen) {
            PairingConfirmedSheet(userName: "Alex", partnerName: "Sam") { pairingOpen = false }
                }
        .task { model.bind(userId: "visual", pairSessionId: UUID().uuidString, baselineDays: 1); await model.refresh(api: api) }
    }
    private func load(_ scenario: String) {
        Task { await transport.set(scenario); await model.refresh(api: api); streakOpen = true }
    }
}
