import Foundation

/// The messages between a watch and the phone, as dictionaries. Every watch
/// sends the same ones (README, "Communication Payloads"): `session`,
/// `rec_start`, `rec_chunk` and `rec_end` from the watch, `ack` from the phone.
/// The Garmin app transmits them through Connect IQ, the Apple Watch app hands
/// them to WatchConnectivity as they are, and they can be carried as UTF-8 JSON
/// the way the Wear OS app does. Swift counterpart of `shared/.../WatchProtocol.kt`.
public enum WatchProtocol {
    public static let typeSession = "session"
    public static let typeRecStart = "rec_start"
    public static let typeRecChunk = "rec_chunk"
    public static let typeRecEnd = "rec_end"
    public static let typeAck = "ack"

    /// The payload as UTF-8 JSON. Values must be JSON types: strings, numbers,
    /// arrays and dictionaries of them.
    public static func encode(_ payload: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])) ?? Data("{}".utf8)
    }

    /// The payload as a dictionary, or nil for anything that is not a JSON object.
    public static func decode(_ data: Data) -> [String: Any]? {
        guard let object = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return object as? [String: Any]
    }

    /// The phone's `ack`, echoing the payload's timestamp when it has one.
    public static func ack(timestamp: Int64?) -> [String: Any] {
        var ack: [String: Any] = ["type": typeAck]
        if let timestamp { ack["timestamp"] = timestamp }
        return ack
    }

    /// What an ack echoes: a session's timestamp, or a recorded run's id.
    public static func ackTimestamp(of payload: [String: Any]) -> Int64? {
        int64(payload["timestamp"]) ?? int64(payload["id"])
    }

    /// A whole number from a decoded payload, whichever numeric type the
    /// transport produced (JSONSerialization gives NSNumber on Apple platforms
    /// and Int or Double elsewhere; WatchConnectivity keeps what was sent).
    public static func int64(_ value: Any?) -> Int64? {
        switch value {
        case let v as Int: return Int64(v)
        case let v as Int64: return v
        case let v as Int32: return Int64(v)
        case let v as Double where v.isFinite && v == v.rounded(): return Int64(v)
        case let v as NSNumber: return v.int64Value
        default: return nil
        }
    }

    public static func int(_ value: Any?) -> Int? {
        int64(value).flatMap { Int(exactly: $0) }
    }

    /// The whole numbers of a list, skipping anything that is not one.
    public static func int64List(_ value: Any?) -> [Int64]? {
        guard let list = value as? [Any] else { return nil }
        return list.compactMap { int64($0) }
    }

    public static func intList(_ value: Any?) -> [Int]? {
        guard let list = value as? [Any] else { return nil }
        return list.compactMap { int($0) }
    }
}
