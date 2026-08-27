// RideLink Phase 1b security spike — Apple side. Not production code; see ../README.md.
//
// Answers, empirically:
//  1. Can a minimal hand-written DER X.509 encoder plus SecKey signing produce a certificate that
//     Apple's own parser (SecCertificateCreateWithData) accepts? (ARCHITECTURE §12 "self-signed
//     X.509 identity generation on iOS", severity High.)
//  2. Can SecIdentityCreate(cert, key) build a sec_identity_t usable by Network.framework TLS,
//     with no PKCS#12 round trip and no private-key export?
//  3. Does a mutually authenticated TLS 1.3 handshake complete with that identity?
//  4. Is a keying-material exporter reachable from public API, and does the context-less variant
//     equal the zero-length-context variant under TLS 1.3? (PROTOCOL §4.5.1 specifies a
//     "zero-length but present" context.)
//  5. Does the SPKI reconstructed from the peer's public key equal the DER SPKI that OpenSSL and
//     Conscrypt compute for the same key? (ADR-012 — the pinned identity.)
//
// Modes:
//   applespike self        both endpoints in this process
//   applespike client PORT connect to an external TLS 1.3 server (the cross-stack experiment)

import CryptoKit
import Foundation
import Network
import Security

let sasLabel = "EXPORTER-RideLink-SAS-v1" // PROTOCOL §4.5.1, 24 ASCII bytes
let sasExporterLength = 32                // PROTOCOL §4.5.1

// MARK: - Minimal DER encoder

enum DER {
    static func length(_ n: Int) -> [UInt8] {
        if n < 0x80 { return [UInt8(n)] }
        var bytes: [UInt8] = []
        var value = n
        while value > 0 {
            bytes.insert(UInt8(value & 0xFF), at: 0)
            value >>= 8
        }
        return [UInt8(0x80 | bytes.count)] + bytes
    }

    static func tlv(_ tag: UInt8, _ body: [UInt8]) -> [UInt8] { [tag] + length(body.count) + body }
    static func sequence(_ items: [[UInt8]]) -> [UInt8] { tlv(0x30, items.flatMap { $0 }) }
    static func set(_ items: [[UInt8]]) -> [UInt8] { tlv(0x31, items.flatMap { $0 }) }
    static func objectIdentifier(_ raw: [UInt8]) -> [UInt8] { tlv(0x06, raw) }
    static func bitString(_ body: [UInt8]) -> [UInt8] { tlv(0x03, [0x00] + body) }
    static func octetString(_ body: [UInt8]) -> [UInt8] { tlv(0x04, body) }
    static func utf8String(_ s: String) -> [UInt8] { tlv(0x0C, Array(s.utf8)) }
    static func utcTime(_ s: String) -> [UInt8] { tlv(0x17, Array(s.utf8)) }
    static func boolean(_ v: Bool) -> [UInt8] { tlv(0x01, [v ? 0xFF : 0x00]) }
    static func explicit(_ tagNumber: UInt8, _ body: [UInt8]) -> [UInt8] { tlv(0xA0 | tagNumber, body) }

    /// Minimal two's-complement positive INTEGER.
    static func integer(_ magnitude: [UInt8]) -> [UInt8] {
        var b = magnitude
        while b.count > 1, b[0] == 0x00, (b[1] & 0x80) == 0 { b.removeFirst() }
        if b.isEmpty { b = [0x00] }
        if (b[0] & 0x80) != 0 { b.insert(0x00, at: 0) }
        return tlv(0x02, b)
    }

    static func integer(_ value: Int) -> [UInt8] {
        var bytes: [UInt8] = []
        var v = value
        if v == 0 { bytes = [0x00] }
        while v > 0 {
            bytes.insert(UInt8(v & 0xFF), at: 0)
            v >>= 8
        }
        return integer(bytes)
    }
}

enum OID {
    static let ecPublicKey: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01]        // 1.2.840.10045.2.1
    static let prime256v1: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07]   // 1.2.840.10045.3.1.7
    static let ecdsaWithSHA256: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02] // 1.2.840.10045.4.3.2
    static let commonName: [UInt8] = [0x55, 0x04, 0x03]                                  // 2.5.4.3
    static let basicConstraints: [UInt8] = [0x55, 0x1D, 0x13]                            // 2.5.29.19
    static let keyUsage: [UInt8] = [0x55, 0x1D, 0x0F]                                    // 2.5.29.15
}

/// SubjectPublicKeyInfo for a P-256 key, from the raw X9.63 uncompressed point. 91 bytes, and
/// byte-identical to what `PublicKey.getEncoded()` yields on the JVM for the same key.
func spkiDER(p256Point: [UInt8]) -> [UInt8] {
    DER.sequence([
        DER.sequence([DER.objectIdentifier(OID.ecPublicKey), DER.objectIdentifier(OID.prime256v1)]),
        DER.bitString(p256Point),
    ])
}

func utcTimeString(_ date: Date) -> String {
    let f = DateFormatter()
    f.dateFormat = "yyMMddHHmmss'Z'"
    f.timeZone = TimeZone(identifier: "UTC")
    f.locale = Locale(identifier: "en_US_POSIX")
    return f.string(from: date)
}

func buildSelfSignedCertificate(privateKey: SecKey, point: [UInt8], serial: [UInt8],
                                notBefore: Date, notAfter: Date) throws -> Data {
    // Generic subject only — no device name, no user name, no serial, no identifier (CLAUDE.md
    // privacy rules). Identity is the SPKI hash, never the subject text (ADR-012).
    let name = DER.sequence([DER.set([DER.sequence([
        DER.objectIdentifier(OID.commonName), DER.utf8String("RideLink Device"),
    ])])])
    let signatureAlgorithm = DER.sequence([DER.objectIdentifier(OID.ecdsaWithSHA256)])
    let extensions = DER.explicit(3, DER.sequence([
        DER.sequence([DER.objectIdentifier(OID.basicConstraints), DER.boolean(true),
                      DER.octetString(DER.sequence([]))]),                        // CA:FALSE
        DER.sequence([DER.objectIdentifier(OID.keyUsage), DER.boolean(true),
                      DER.octetString([0x03, 0x02, 0x07, 0x80])]),                // digitalSignature
    ]))
    let tbs = DER.sequence([
        DER.explicit(0, DER.integer(2)), // v3
        DER.integer(serial),
        signatureAlgorithm,
        name, // issuer == subject: self-signed
        DER.sequence([DER.utcTime(utcTimeString(notBefore)), DER.utcTime(utcTimeString(notAfter))]),
        name,
        spkiDER(p256Point: point),
        extensions,
    ])
    var error: Unmanaged<CFError>?
    guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256,
                                                Data(tbs) as CFData, &error) as Data? else {
        throw NSError(domain: "spike", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "SecKeyCreateSignature failed: \(error!.takeRetainedValue())"])
    }
    return Data(DER.sequence([tbs, signatureAlgorithm, DER.bitString([UInt8](signature))]))
}

func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
func hex(_ bytes: [UInt8]) -> String { hex(Data(bytes)) }
func sha256Hex(_ bytes: [UInt8]) -> String { hex(Data(SHA256.hash(data: Data(bytes)))) }

// MARK: - Identity

struct SpikeIdentity {
    let privateKey: SecKey
    let point: [UInt8]
    let certificateDER: Data
    let secIdentity: SecIdentity
    var spkiHash: String { sha256Hex(spkiDER(p256Point: point)) }
}

func makeIdentity(label: String) throws -> SpikeIdentity {
    var error: Unmanaged<CFError>?
    // kSecAttrIsPermanent: false — a transient key needs no keychain entitlement, so this harness
    // runs as an unsigned command-line tool. The real implementation stores a PERMANENT key in the
    // app's Keychain; that path is exercised by RideLinkPlatform's simulator tests, not here.
    let attributes: [String: Any] = [
        kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
        kSecAttrKeySizeInBits as String: 256,
        kSecAttrIsPermanent as String: false,
    ]
    guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
        throw NSError(domain: "spike", code: 2,
                      userInfo: [NSLocalizedDescriptionKey: "key generation failed: \(error!.takeRetainedValue())"])
    }
    guard let publicKey = SecKeyCopyPublicKey(privateKey),
          let raw = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
        throw NSError(domain: "spike", code: 3, userInfo: [NSLocalizedDescriptionKey: "public-key export failed"])
    }
    var serial = [UInt8](repeating: 0, count: 16)
    _ = SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial)
    serial[0] &= 0x7F // keep it a positive INTEGER

    let now = Date()
    let der = try buildSelfSignedCertificate(
        privateKey: privateKey, point: [UInt8](raw), serial: serial,
        notBefore: now.addingTimeInterval(-86400),
        notAfter: now.addingTimeInterval(86400 * 3650)) // 10 years, per ADR-012
    guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else {
        throw NSError(domain: "spike", code: 4,
                      userInfo: [NSLocalizedDescriptionKey: "SecCertificateCreateWithData REJECTED our DER"])
    }
    guard let identity = SecIdentityCreate(nil, certificate, privateKey) else {
        throw NSError(domain: "spike", code: 5,
                      userInfo: [NSLocalizedDescriptionKey: "SecIdentityCreate returned nil"])
    }
    print("APPLE_\(label)_CERT_DER_BYTES=\(der.count)")
    print("APPLE_\(label)_CERT_PARSED_BY_APPLE=true")
    print("APPLE_\(label)_CERT_SUBJECT_SUMMARY=\(SecCertificateCopySubjectSummary(certificate) as String? ?? "nil")")
    print("APPLE_\(label)_OWN_SPKI_SHA256=\(sha256Hex(spkiDER(p256Point: [UInt8](raw))))")
    return SpikeIdentity(privateKey: privateKey, point: [UInt8](raw),
                         certificateDER: der, secIdentity: identity)
}

// MARK: - TLS

final class Endpoint: @unchecked Sendable {
    var exporterNoContext: Data?
    var exporterEmptyContext: Data?
    var exporterWithContext: Data?
    var negotiatedVersion = "?"
    var peerSPKIHash = "?"
    let ready = DispatchSemaphore(value: 0)
}

func exportSecret(_ metadata: sec_protocol_metadata_t, context: [UInt8]?, length: Int) -> Data? {
    let labelBytes = Array(sasLabel.utf8)
    let exported: dispatch_data_t? = labelBytes.withUnsafeBufferPointer { labelBuffer in
        let labelPointer = UnsafeRawPointer(labelBuffer.baseAddress!).assumingMemoryBound(to: CChar.self)
        guard let context else {
            return sec_protocol_metadata_create_secret(metadata, labelBytes.count, labelPointer, length)
        }
        // A zero-length Swift array has a nil baseAddress, so back an empty context with a real
        // one-byte allocation and pass count 0. Without this a nil return would be ambiguous
        // between "Apple rejects a zero-length context" and "we passed a bad pointer".
        var storage = context.isEmpty ? [UInt8(0)] : context
        return storage.withUnsafeMutableBufferPointer { contextBuffer in
            sec_protocol_metadata_create_secret_with_context(
                metadata, labelBytes.count, labelPointer,
                context.count, contextBuffer.baseAddress!, length)
        }
    }
    guard let exported else { return nil }
    var out = Data()
    (exported as DispatchData).enumerateBytes { buffer, _, _ in out.append(contentsOf: buffer) }
    return out
}

func versionName(_ version: tls_protocol_version_t) -> String {
    switch version {
    case .TLSv13: return "TLSv1.3"
    case .TLSv12: return "TLSv1.2"
    case .TLSv11: return "TLSv1.1"
    case .TLSv10: return "TLSv1.0"
    default: return "other(\(version.rawValue))"
    }
}

func peerSPKIHash(from metadata: sec_protocol_metadata_t) -> String {
    var result = "?"
    sec_protocol_metadata_access_peer_certificate_chain(metadata) { secCertificate in
        guard result == "?" else { return } // leaf only
        let certificate = sec_certificate_copy_ref(secCertificate).takeRetainedValue()
        guard let key = SecCertificateCopyKey(certificate) else { return }
        var error: Unmanaged<CFError>?
        guard let raw = SecKeyCopyExternalRepresentation(key, &error) as Data? else { return }
        result = sha256Hex(spkiDER(p256Point: [UInt8](raw)))
    }
    return result
}

func capture(_ metadata: sec_protocol_metadata_t, into endpoint: Endpoint) {
    endpoint.negotiatedVersion = versionName(sec_protocol_metadata_get_negotiated_tls_protocol_version(metadata))
    endpoint.exporterNoContext = exportSecret(metadata, context: nil, length: sasExporterLength)
    endpoint.exporterEmptyContext = exportSecret(metadata, context: [], length: sasExporterLength)
    endpoint.exporterWithContext = exportSecret(metadata, context: [1, 2, 3], length: sasExporterLength)
}

func tlsOptions(identity: SecIdentity, endpoint: Endpoint, queue: DispatchQueue) -> NWProtocolTLS.Options {
    let options = NWProtocolTLS.Options()
    let sec = options.securityProtocolOptions
    sec_protocol_options_set_local_identity(sec, sec_identity_create(identity)!)
    sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
    sec_protocol_options_set_max_tls_protocol_version(sec, .TLSv13)
    sec_protocol_options_set_peer_authentication_required(sec, true)
    sec_protocol_options_set_challenge_block(sec, { _, complete in
        complete(sec_identity_create(identity)!)
    }, queue)
    sec_protocol_options_set_verify_block(sec, { metadata, _, complete in
        // RideLink deliberately does not use PKI hostname/chain validation (ARCHITECTURE §4.3):
        // accept the transport, then apply the SPKI pin above it.
        endpoint.peerSPKIHash = peerSPKIHash(from: metadata)
        complete(true)
    }, queue)
    return options
}

// MARK: - Experiments

func runSelfExperiment(queue: DispatchQueue) throws {
    let serverIdentity = try makeIdentity(label: "SERVER")
    let clientIdentity = try makeIdentity(label: "CLIENT")
    let server = Endpoint(), client = Endpoint()

    let listenParameters = NWParameters(tls: tlsOptions(identity: serverIdentity.secIdentity,
                                                        endpoint: server, queue: queue))
    listenParameters.allowLocalEndpointReuse = true
    let listener = try NWListener(using: listenParameters, on: .any)
    var accepted: NWConnection?
    listener.newConnectionHandler = { connection in
        accepted = connection
        connection.stateUpdateHandler = { state in
            if case .ready = state {
                if let md = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata {
                    capture(md.securityProtocolMetadata, into: server)
                }
                server.ready.signal()
            }
            if case .failed(let e) = state {
                print("APPLE_SERVER_FAILED=\(e)")
                server.ready.signal()
            }
        }
        connection.start(queue: queue)
    }
    let listening = DispatchSemaphore(value: 0)
    listener.stateUpdateHandler = { if case .ready = $0 { listening.signal() } }
    listener.start(queue: queue)
    _ = listening.wait(timeout: .now() + 10)

    let connection = NWConnection(host: .ipv4(.loopback), port: listener.port!,
                                  using: NWParameters(tls: tlsOptions(identity: clientIdentity.secIdentity,
                                                                      endpoint: client, queue: queue)))
    connection.stateUpdateHandler = { state in
        if case .ready = state {
            if let md = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata {
                capture(md.securityProtocolMetadata, into: client)
            }
            client.ready.signal()
        }
        if case .failed(let e) = state {
            print("APPLE_CLIENT_FAILED=\(e)")
            client.ready.signal()
        }
    }
    connection.start(queue: queue)
    _ = client.ready.wait(timeout: .now() + 20)
    _ = server.ready.wait(timeout: .now() + 20)

    print("APPLE_SERVER_NEGOTIATED=\(server.negotiatedVersion)")
    print("APPLE_CLIENT_NEGOTIATED=\(client.negotiatedVersion)")
    print("APPLE_SERVER_EXPORTER_NOCTX=\(server.exporterNoContext.map(hex) ?? "nil")")
    print("APPLE_CLIENT_EXPORTER_NOCTX=\(client.exporterNoContext.map(hex) ?? "nil")")
    print("APPLE_SERVER_EXPORTER_EMPTYCTX=\(server.exporterEmptyContext.map(hex) ?? "nil")")
    print("APPLE_SERVER_EXPORTER_CTX010203=\(server.exporterWithContext.map(hex) ?? "nil")")
    print("APPLE_SERVER_SEES_PEER_SPKI=\(server.peerSPKIHash)")
    print("APPLE_CLIENT_SEES_PEER_SPKI=\(client.peerSPKIHash)")

    let tls13 = server.negotiatedVersion == "TLSv1.3" && client.negotiatedVersion == "TLSv1.3"
    let exportersAgree = server.exporterNoContext != nil && server.exporterNoContext == client.exporterNoContext
    let pinsMatch = server.peerSPKIHash == clientIdentity.spkiHash
        && client.peerSPKIHash == serverIdentity.spkiHash
    print("RESULT_APPLE_TLS13=\(tls13)")
    print("RESULT_APPLE_EXPORTERS_AGREE=\(exportersAgree)")
    print("RESULT_APPLE_EMPTYCTX_VARIANT_USABLE=\(server.exporterEmptyContext != nil)")
    print("RESULT_APPLE_SPKI_PIN_OBSERVED=\(pinsMatch)")

    connection.cancel()
    accepted?.cancel()
    listener.cancel()
}

func runClientExperiment(port: UInt16, queue: DispatchQueue) throws {
    let identity = try makeIdentity(label: "CLIENT")
    let client = Endpoint()
    let connection = NWConnection(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: port)!,
                                  using: NWParameters(tls: tlsOptions(identity: identity.secIdentity,
                                                                      endpoint: client, queue: queue)))
    connection.stateUpdateHandler = { state in
        if case .ready = state {
            if let md = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata {
                capture(md.securityProtocolMetadata, into: client)
            }
            client.ready.signal()
        }
        if case .failed(let e) = state {
            print("APPLE_CLIENT_FAILED=\(e)")
            client.ready.signal()
        }
    }
    connection.start(queue: queue)
    _ = client.ready.wait(timeout: .now() + 25)
    print("APPLE_CLIENT_NEGOTIATED=\(client.negotiatedVersion)")
    print("APPLE_CLIENT_EXPORTER_NOCTX=\(client.exporterNoContext.map(hex) ?? "nil")")
    print("APPLE_CLIENT_EXPORTER_EMPTYCTX=\(client.exporterEmptyContext.map(hex) ?? "nil")")
    print("APPLE_CLIENT_PEER_SPKI_SHA256=\(client.peerSPKIHash)")
    Thread.sleep(forTimeInterval: 1.5) // let the peer finish its own export before FIN
    connection.cancel()
}

do {
    let queue = DispatchQueue(label: "ridelink.spike")
    let mode = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "self"
    if mode == "client" {
        try runClientExperiment(port: UInt16(CommandLine.arguments[2])!, queue: queue)
    } else {
        try runSelfExperiment(queue: queue)
    }
} catch {
    print("SPIKE FAILED: \(error)")
    exit(1)
}
