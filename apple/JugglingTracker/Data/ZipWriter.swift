import Foundation

/// A minimal zip writer: stored (uncompressed) entries, no folders. Enough for
/// the recordings export, which Android writes with `ZipOutputStream`; iOS has
/// no public zip API.
struct ZipWriter {
    private var body = Data()
    private var directory = Data()
    private var count: UInt16 = 0

    mutating func add(name: String, data: Data) {
        let nameBytes = Data(name.utf8)
        let crc = Self.crc32(data)
        let offset = UInt32(body.count)

        var local = Data()
        local.append(le32: 0x0403_4b50)
        local.append(le16: 20) // version needed
        local.append(le16: 0x0800) // flags: UTF-8 names
        local.append(le16: 0) // stored
        local.append(le16: 0) // time
        local.append(le16: 0x21) // date: 1980-01-01
        local.append(le32: crc)
        local.append(le32: UInt32(data.count))
        local.append(le32: UInt32(data.count))
        local.append(le16: UInt16(nameBytes.count))
        local.append(le16: 0) // extra
        local.append(nameBytes)
        body.append(local)
        body.append(data)

        directory.append(le32: 0x0201_4b50)
        directory.append(le16: 20) // version made by
        directory.append(le16: 20) // version needed
        directory.append(le16: 0x0800)
        directory.append(le16: 0)
        directory.append(le16: 0)
        directory.append(le16: 0x21)
        directory.append(le32: crc)
        directory.append(le32: UInt32(data.count))
        directory.append(le32: UInt32(data.count))
        directory.append(le16: UInt16(nameBytes.count))
        directory.append(le16: 0) // extra
        directory.append(le16: 0) // comment
        directory.append(le16: 0) // disk
        directory.append(le16: 0) // internal attributes
        directory.append(le32: 0) // external attributes
        directory.append(le32: offset)
        directory.append(nameBytes)
        count += 1
    }

    /// The finished archive.
    func finish() -> Data {
        var out = body
        out.append(directory)
        out.append(le32: 0x0605_4b50)
        out.append(le16: 0)
        out.append(le16: 0)
        out.append(le16: count)
        out.append(le16: count)
        out.append(le32: UInt32(directory.count))
        out.append(le32: UInt32(body.count))
        out.append(le16: 0)
        return out
    }

    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 {
            c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func append(le16 value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8(value >> 8))
    }

    mutating func append(le32 value: UInt32) {
        append(le16: UInt16(value & 0xFFFF))
        append(le16: UInt16(value >> 16))
    }
}
