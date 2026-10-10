import JugglingCore
import SwiftUI

/// The home screen: the watch and phone cards, ball-count tabs, the trend
/// graph and the session history.
struct TrackerScreen: View {
    @Environment(TrackerModel.self) private var model
    @Binding var path: [Screen]
    @State private var selectedBalls: Int?
    @State private var sessionToDelete: SessionSummary?
    @State private var sessionForDetails: SessionSummary?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                WatchHeader { path.append(.phoneSession) }

                if model.sessions.isEmpty {
                    emptyState
                } else {
                    history
                }
            }
            .padding(16)
        }
        .sheet(item: $sessionForDetails) { session in
            SessionDetailsView(session: session)
        }
        .alert(
            "Delete Session",
            isPresented: Binding(get: { sessionToDelete != nil }, set: { if !$0 { sessionToDelete = nil } }),
            presenting: sessionToDelete
        ) { session in
            Button("Delete", role: .destructive) { model.delete(session) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Are you sure you want to delete this session from your history?")
        }
    }

    private var ballCounts: [Int] {
        Array(Set(model.sessions.map(\.ballCount))).sorted()
    }

    @ViewBuilder
    private var history: some View {
        let counts = ballCounts
        let balls = selectedBalls.flatMap { counts.contains($0) ? $0 : nil } ?? counts[0]
        let filtered = model.sessions.filter { $0.ballCount == balls }

        Text("Session History")
            .font(.headline)
            .frame(maxWidth: .infinity, alignment: .leading)

        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(counts, id: \.self) { count in
                    Button("\(count) Balls") { selectedBalls = count }
                        .buttonStyle(BallChipStyle(isSelected: count == balls))
                        .accessibilityAddTraits(count == balls ? .isSelected : [])
                }
            }
        }

        SessionHistoryGraph(sessions: filtered)
            .frame(height: 180)

        LazyVStack(spacing: 8) {
            ForEach(filtered, id: \.timestamp) { session in
                SessionHistoryRow(session: session)
                    .onTapGesture { sessionForDetails = session }
                    .contextMenu {
                        Button("Delete", systemImage: "trash", role: .destructive) { sessionToDelete = session }
                    }
                    .swipeToDelete { sessionToDelete = session }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text("No sessions yet")
                .font(.title2)
            Text("Start juggling on your watch or phone and\nresults will appear here automatically.")
                .font(.body)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.secondary)
        .padding(.vertical, 64)
    }
}

extension SessionSummary: Identifiable {
    public var id: Int64 { timestamp }
}

/// Swipe a row left to ask about deleting it; the row springs back either way.
private struct SwipeToDelete: ViewModifier {
    let onDelete: () -> Void
    @State private var offset: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .offset(x: offset)
            .background(alignment: .trailing) {
                if offset < 0 {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(.red)
                        .overlay(alignment: .trailing) {
                            Image(systemName: "trash").foregroundStyle(.white).padding(.trailing, 16)
                        }
                }
            }
            // Alongside the scroll view's own drag; only a mostly sideways
            // drag moves the row.
            .simultaneousGesture(
                DragGesture(minimumDistance: 20)
                    .onChanged { value in
                        guard abs(value.translation.width) > abs(value.translation.height) else { return }
                        offset = min(0, value.translation.width)
                    }
                    .onEnded { value in
                        if offset < -100 { onDelete() }
                        withAnimation { offset = 0 }
                    }
            )
    }
}

private extension View {
    func swipeToDelete(_ onDelete: @escaping () -> Void) -> some View {
        modifier(SwipeToDelete(onDelete: onDelete))
    }
}
