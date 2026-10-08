import Foundation

/// Display strings, identical to what `MainView.mc` and `RecordingView.mc` draw.
public enum Format {
    /// "m:ss", or "h:mm:ss" from one hour on.
    public static func elapsed(_ totalSeconds: Int64) -> String {
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds / 60) % 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return "\(hours):\(twoDigits(minutes)):\(twoDigits(seconds))"
        }
        return "\(minutes):\(twoDigits(seconds))"
    }

    /// Stats show "-" until there is a value.
    public static func countOrDash(_ value: Int) -> String { value == 0 ? "-" : String(value) }

    public static func averageOrDash(_ value: Double) -> String {
        value == 0.0 ? "-" : String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    /// Shape consistency: "-" until a run has been scored (-1), else "83%".
    public static func percentOrDash(_ value: Int) -> String { value < 0 ? "-" : "\(value)%" }

    public static func syncing(_ dots: Int) -> String {
        "Sync to phone" + String(repeating: ".", count: max(dots, 0))
    }

    /// While chunks are going over, the record screen shows progress instead.
    public static func recordingSyncText(_ state: RecordingUiState) -> String {
        if state.headerSent && !state.transferDone && state.totalChunks > 0 {
            return "\(state.chunkIndex)/\(state.totalChunks)"
        }
        return syncing(state.syncDots)
    }

    private static func twoDigits(_ value: Int64) -> String { value < 10 ? "0\(value)" : String(value) }
}
