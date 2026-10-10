import Charts
import JugglingCore
import SwiftUI

private let bestColor = Color(red: 0x4C / 255, green: 0xAF / 255, blue: 0x50 / 255)
private let averageColor = Color(red: 0x21 / 255, green: 0x96 / 255, blue: 0xF3 / 255)

private func shortDate(_ timestamp: Int64) -> String {
    Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000)
        .formatted(.dateTime.month(.abbreviated).day(.twoDigits).hour().minute())
}

/// Best and average run of each session, oldest to newest, with the spread
/// of the runs shaded around the average. Touch to see one session's numbers.
struct SessionHistoryGraph: View {
    let sessions: [SessionSummary]
    @State private var selectedIndex: Int?

    private var ordered: [SessionSummary] { sessions.reversed() }

    var body: some View {
        let points = Array(ordered.enumerated())
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                if let selectedIndex, selectedIndex < points.count {
                    let s = points[selectedIndex].element
                    Text("\(shortDate(s.timestamp)) | Average: \(s.avgThrows, specifier: "%.1f") | Best: \(s.bestRun)")
                        .font(.caption2.bold())
                        .foregroundStyle(Color.accentColor)
                } else {
                    Text("Watch-Hand Trends").font(.caption2)
                }
                Spacer()
                legend(bestColor, "Best")
                legend(averageColor, "Average")
            }
            Chart {
                ForEach(points, id: \.offset) { index, s in
                    AreaMark(
                        x: .value("Session", index),
                        yStart: .value("Low", max(s.avgThrows - s.stdDevThrows, 0)),
                        yEnd: .value("High", s.avgThrows + s.stdDevThrows)
                    )
                    .foregroundStyle(averageColor.opacity(0.2))
                }
                ForEach(points, id: \.offset) { index, s in
                    LineMark(x: .value("Session", index), y: .value("Catches", Double(s.bestRun)), series: .value("Series", "Best"))
                        .foregroundStyle(bestColor.opacity(0.5))
                    PointMark(x: .value("Session", index), y: .value("Catches", Double(s.bestRun)))
                        .foregroundStyle(bestColor)
                        .symbolSize(selectedIndex == index ? 80 : 30)
                    LineMark(x: .value("Session", index), y: .value("Catches", s.avgThrows), series: .value("Series", "Average"))
                        .foregroundStyle(averageColor.opacity(0.5))
                    PointMark(x: .value("Session", index), y: .value("Catches", s.avgThrows))
                        .foregroundStyle(averageColor)
                        .symbolSize(selectedIndex == index ? 80 : 30)
                }
                if let selectedIndex {
                    RuleMark(x: .value("Session", selectedIndex))
                        .foregroundStyle(.gray.opacity(0.5))
                }
            }
            .chartXAxis(.hidden)
            .chartYAxisLabel("# of catches", position: .leading)
            .chartYScale(domain: 0...(Double(max(sessions.map(\.bestRun).max() ?? 10, 10)) * 1.2))
            .chartXSelection(value: $selectedIndex)
        }
        .padding(12)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func legend(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.caption2)
        }
    }
}

/// One session in the history list.
struct SessionHistoryRow: View {
    let session: SessionSummary

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(shortDate(session.timestamp)).bold()
                Text("\(session.ballCount) balls • \(session.runCount) runs • \(session.totalThrows) catches")
                    .font(.caption)
            }
            Spacer()
            HStack(spacing: 16) {
                StatColumn(label: "Average", value: String(format: "%.1f (±%.1f)", session.avgThrows, session.stdDevThrows))
                StatColumn(label: "Best", value: "\(session.bestRun)")
                if let regularity = session.shapeConsistency {
                    StatColumn(label: "Regularity", value: "\(regularity)%")
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
    }
}

private struct StatColumn: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(label).font(.caption2)
            Text(value).font(.subheadline.bold())
        }
    }
}

/// One session's details: its runs as bars, the stats, Regularity, and the
/// juggling frequency.
struct SessionDetailsView: View {
    let session: SessionSummary
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("\(session.ballCount) Balls • \(session.runCount) Runs")
                        .font(.headline)

                    Chart {
                        ForEach(Array(session.runHistory.enumerated()), id: \.offset) { index, run in
                            BarMark(x: .value("Run", index + 1), y: .value("Catches", Double(run)))
                                .foregroundStyle(averageColor)
                        }
                    }
                    .chartXAxis(.hidden)
                    .chartYScale(domain: 0...Double(max(session.runHistory.max() ?? 1, 10)))
                    .frame(height: 140)

                    HStack {
                        StatItem(label: "Avg", value: String(format: "%.1f", session.avgThrows))
                        StatItem(label: "Max", value: "\(session.bestRun)")
                        StatItem(label: "Total", value: "\(session.totalThrows)")
                        // Sessions from before the watches sent Regularity, or
                        // too short to score, show n/a.
                        StatItem(label: "Regularity", value: session.shapeConsistency.map { "\($0)%" } ?? "n/a")
                    }

                    if let rates = Self.rates(session) {
                        HStack {
                            StatItem(label: "Average Frequency", value: String(format: "%.2f/s", rates.frequency))
                            StatItem(label: "Average Gap", value: String(format: "%.2fs", rates.gap))
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Session Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack {
                        Text("Session Details").font(.headline)
                        Text(Date(timeIntervalSince1970: TimeInterval(session.timestamp) / 1000)
                            .formatted(.dateTime.month(.wide).day(.twoDigits).hour().minute()))
                            .font(.caption)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// Catches per second over the timed runs and seconds between catches,
    /// or nil when the session carries no run durations.
    static func rates(_ session: SessionSummary) -> (frequency: Double, gap: Double)? {
        guard !session.runDurationsMillis.isEmpty else { return nil }
        let seconds = Double(session.runDurationsMillis.reduce(0, +)) / 1000
        let frequency = seconds > 0 ? Double(session.totalThrows) / seconds : 0
        let gap = session.totalThrows > 0 ? seconds / Double(session.totalThrows) : 0
        return (frequency, gap)
    }
}
