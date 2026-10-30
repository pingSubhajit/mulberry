import SwiftUI

/// Nothing while something loads quickly; a spinner fades in only once it has taken longer than
/// `delay`. Apple's guidance for launch: show the app's own background, not a loading screen, so
/// a quick restore goes straight to the content without a flash.
public struct DelayedProgressView: View {
    let delay: Duration
    @State private var slow = false

    public init(after delay: Duration = .seconds(1)) { self.delay = delay }

    public var body: some View {
        ZStack {
            if slow { ProgressView().transition(.opacity) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.2)) { slow = true }
        }
    }
}
