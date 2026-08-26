import Foundation

/// PROTOCOL §1. This is a defensive limit and does not move (CLAUDE.md rule 11).
public enum FrameLimits {
    public static let maxControlFrameBytes = 262_144
}

public enum ProtocolVersion {
    public static let current = 1
}

public enum DecodeResult: Equatable {
    case success(envelope: Envelope, versionOk: Bool)
    case failure(errorCode: String)
}

/// Envelope encode/decode + the two structural checks that must happen before a single JSON
/// field is inspected: frame-size rejection (PROTOCOL §1) and version compatibility (PROTOCOL §2).
public enum EnvelopeCodec {
    public static let jsonDecoder: JSONDecoder = JSONDecoder()

    public static let jsonEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    public static func decode(_ bytes: [UInt8]) -> DecodeResult {
        if bytes.count > FrameLimits.maxControlFrameBytes {
            return .failure(errorCode: "frame_too_large")
        }
        return decode(Data(bytes))
    }

    public static func decode(_ data: Data) -> DecodeResult {
        do {
            let envelope = try jsonDecoder.decode(Envelope.self, from: data)
            return .success(envelope: envelope, versionOk: envelope.v == ProtocolVersion.current)
        } catch {
            return .failure(errorCode: "malformed_frame")
        }
    }

    public static func decode(_ text: String) -> DecodeResult {
        guard let data = text.data(using: .utf8) else {
            return .failure(errorCode: "malformed_frame")
        }
        return decode(data)
    }

    public static func encode(_ envelope: Envelope) throws -> Data {
        try jsonEncoder.encode(envelope)
    }

    public static func encodeToString(_ envelope: Envelope) throws -> String {
        String(data: try encode(envelope), encoding: .utf8) ?? ""
    }
}
