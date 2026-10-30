import ProductUI
import SwiftUI

/// The partner's reactions arriving, and the one just sent leaving, over a canvas.
struct ReactionStage: View {
    @ObservedObject var reactions: ReactionsModel
    let surface: ReactionsModel.Surface

    var body: some View {
        ZStack {
            if reactions.surface == surface, let arrival = reactions.arrival {
                ReactionArrivalView(arrival: arrival) { Task { await reactions.played(arrival) } }
                    .id(arrival.id)
            }
            if reactions.surface == surface, let sent = reactions.sent {
                ReactionSentBurst(sent: sent) { reactions.finishedSending(sent) }
                    .id(sent.id)
            }
        }
        .allowsHitTesting(false)
    }
}
