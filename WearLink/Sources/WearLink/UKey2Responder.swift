import Foundation
import CryptoKit
#if canImport(CommonCrypto)
import CommonCrypto
#endif

// UKEY2 responder for the watch's encrypted channel (the iPhone is the responder).
//
// Implements the handshake and D2DConnectionContextV1 transport from google/ukey2
// (Apache-2.0), pinned commit 10fc737aa901e873a3367e7e26b88eb01cd55d69. Every constant is
// cited in docs/UKEY2_IMPL.md. The official library is used, unmodified, only as the test
// oracle — no upstream code is copied here.
//
// Security: never logs key material, the verification code, or decrypted payloads; any
// authentication or sequence failure throws so the session drops; there is no plaintext path.

public enum UKey2Error: Error, Equatable {
    case badState
    case badMessage
    case commitmentMismatch
    case badPublicKey
    case notEstablished
    case authFailed
    case badSequence
    case crypto
}

// MARK: - Primitives

private enum UK {
    static let secureMessageSalt = Data(SHA256.hash(data: Data("SecureMessage".utf8))) // SecureMessage sub-keys (ENC:2/SIG:1)
    static let d2dSalt = Data(SHA256.hash(data: Data("D2D".utf8)))                      // D2DCryptoOps.SALT: client/server keys, sessionUnique

    static func sha256(_ d: Data) -> Data { Data(SHA256.hash(data: d)) }
    static func sha512(_ d: Data) -> Data { Data(SHA512.hash(data: d)) }

    static func hkdf(ikm: Data, salt: Data, info: Data, len: Int = 32) -> Data {
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm),
                                         salt: salt, info: info, outputByteCount: len)
        return key.withUnsafeBytes { Data($0) }
    }

    static func hmac(key: Data, _ data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    /// deriveAes256KeyFor: HKDF(key, salt=SHA256("SecureMessage"), info=purpose).
    static func purposeKey(_ key: Data, _ purpose: String) -> Data {
        hkdf(ikm: key, salt: secureMessageSalt, info: Data(purpose.utf8))
    }

    #if canImport(CommonCrypto)
    static func aesCbc(_ op: Int, key: Data, iv: Data, _ input: Data) throws -> Data {
        let outCapacity = input.count + kCCBlockSizeAES128
        var out = Data(count: outCapacity)
        var moved = 0
        let status: CCCryptorStatus = out.withUnsafeMutableBytes { o in
            input.withUnsafeBytes { i in
                key.withUnsafeBytes { k in
                    iv.withUnsafeBytes { v in
                        CCCrypt(CCOperation(op), CCAlgorithm(kCCAlgorithmAES),
                                CCOptions(kCCOptionPKCS7Padding),
                                k.baseAddress, key.count, v.baseAddress,
                                i.baseAddress, input.count,
                                o.baseAddress, outCapacity, &moved)
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw UKey2Error.crypto }
        out.removeSubrange(moved..<out.count)
        return out
    }
    static func aesCbcEncrypt(key: Data, iv: Data, _ pt: Data) throws -> Data {
        try aesCbc(kCCEncrypt, key: key, iv: iv, pt)
    }
    static func aesCbcDecrypt(key: Data, iv: Data, _ ct: Data) throws -> Data {
        try aesCbc(kCCDecrypt, key: key, iv: iv, ct)
    }
    #else
    static func aesCbcEncrypt(key: Data, iv: Data, _ pt: Data) throws -> Data { throw UKey2Error.crypto }
    static func aesCbcDecrypt(key: Data, iv: Data, _ ct: Data) throws -> Data { throw UKey2Error.crypto }
    #endif

    static func random(_ n: Int) -> Data {
        var d = Data(count: n)
        d.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return d
    }

    // Java BigInteger(1, …).toByteArray() style: minimal big-endian, leading 0x00 if MSB set.
    static func bigIntBytes(_ coord32: Data) -> Data {
        var b = coord32
        while b.first == 0x00 { b.removeFirst() }          // minimal
        if let f = b.first, f & 0x80 != 0 { b.insert(0x00, at: 0) } // keep positive
        return b
    }
    // Reverse: strip a sign byte, left-pad to 32.
    static func coord32(_ raw: Data) -> Data? {
        var b = raw
        if b.count == 33, b.first == 0x00 { b.removeFirst() }
        if b.count > 32 { return nil }
        if b.count < 32 { b = Data(repeating: 0, count: 32 - b.count) + b }
        return b
    }
}

// MARK: - Proto bodies (field numbers from docs/UKEY2_IMPL.md)

private enum Ukey2Type { static let clientInit: Int32 = 2, serverInit: Int32 = 3, clientFinish: Int32 = 4 }
private let kP256SHA512: Int32 = 100
private let kNextProtocol = "AES_256_CBC-HMAC_SHA256"
private let kPayloadD2DMessage: Int32 = 13
private let kSecureGcmVersion: Int32 = 1

private func ukey2Message(type: Int32, data: Data) -> Data {
    var w = ProtoWriter(); w.int32(1, type); w.bytes(2, data); return w.data
}

private func genericEcPublicKey(x963: Data) -> Data {
    // x963 = 0x04 || X(32) || Y(32)
    let x = UK.bigIntBytes(x963.subdata(in: 1..<33))
    let y = UK.bigIntBytes(x963.subdata(in: 33..<65))
    var ec = ProtoWriter(); ec.bytes(1, x); ec.bytes(2, y)
    var gpk = ProtoWriter(); gpk.int32(1, 1 /* EC_P256 */); gpk.bytes(2, ec.data)
    return gpk.data
}

private func parseEcPublicKey(_ generic: Data) throws -> P256.KeyAgreement.PublicKey {
    let gpk = try ProtoMessage(generic)
    guard let ecBytes = gpk.first(2)?.asData else { throw UKey2Error.badPublicKey }
    let ec = try ProtoMessage(ecBytes)
    guard let xr = ec.first(1)?.asData, let yr = ec.first(2)?.asData,
          let x = UK.coord32(xr), let y = UK.coord32(yr) else { throw UKey2Error.badPublicKey }
    let x963 = Data([0x04]) + x + y
    do { return try P256.KeyAgreement.PublicKey(x963Representation: x963) }
    catch { throw UKey2Error.badPublicKey }
}

// MARK: - D2DConnectionContextV1

final class D2DContext {
    private let encodeKey: Data   // responder: serverKey
    private let decodeKey: Data   // responder: clientKey
    private var encodeSeq: Int32 = 0
    private var decodeSeq: Int32 = 0

    init(encodeKey: Data, decodeKey: Data) { self.encodeKey = encodeKey; self.decodeKey = decodeKey }

    /// Shared fingerprint both peers compute identically: SHA256(SALT || firstKey || secondKey),
    /// keys ordered by Java Arrays.hashCode. Used to cross-check against google/ukey2's vector.
    func sessionUnique() -> Data {
        let first: Data, second: Data
        if javaByteArrayHashCode(encodeKey) < javaByteArrayHashCode(decodeKey) {
            first = encodeKey; second = decodeKey
        } else {
            first = decodeKey; second = encodeKey
        }
        return UK.sha256(UK.d2dSalt + first + second)
    }

    func seal(_ plaintext: Data) throws -> Data {
        encodeSeq &+= 1
        // body = DeviceToDeviceMessage{ message=1, sequence_number=2 }
        var dm = ProtoWriter(); dm.bytes(1, plaintext); dm.int32(2, encodeSeq)
        return try signcrypt(body: dm.data, dirKey: encodeKey)
    }

    func open(_ ciphertext: Data) throws -> Data {
        let body = try verifyDecrypt(ciphertext, dirKey: decodeKey)
        let dm = try ProtoMessage(body)
        guard let msg = dm.first(1)?.asData, let seq = dm.first(2)?.asInt else { throw UKey2Error.badMessage }
        decodeSeq &+= 1
        guard Int32(seq) == decodeSeq else { throw UKey2Error.badSequence }
        return msg
    }

    // SecureMessage signcrypt; sign key == enc key ⇒ no plaintext tag.
    private func signcrypt(body: Data, dirKey: Data) throws -> Data {
        let encSub = UK.purposeKey(dirKey, "ENC:2")
        let macSub = UK.purposeKey(dirKey, "SIG:1")
        let iv = UK.random(16)

        var md = ProtoWriter(); md.int32(1, kPayloadD2DMessage); md.int32(2, kSecureGcmVersion)
        var hdr = ProtoWriter()
        hdr.int32(1, 1)          // signature_scheme = HMAC_SHA256
        hdr.int32(2, 2)          // encryption_scheme = AES_256_CBC
        hdr.bytes(5, iv)         // iv
        hdr.bytes(6, md.data)    // public_metadata
        let header = hdr.data

        let encBody = try UK.aesCbcEncrypt(key: encSub, iv: iv, body)
        var hab = ProtoWriter(); hab.bytes(1, header); hab.bytes(2, encBody)
        let headerAndBody = hab.data
        let sig = UK.hmac(key: macSub, headerAndBody)

        var sm = ProtoWriter(); sm.bytes(1, headerAndBody); sm.bytes(2, sig)
        return sm.data
    }

    private func verifyDecrypt(_ secureMessage: Data, dirKey: Data) throws -> Data {
        let encSub = UK.purposeKey(dirKey, "ENC:2")
        let macSub = UK.purposeKey(dirKey, "SIG:1")
        let sm = try ProtoMessage(secureMessage)
        guard let headerAndBody = sm.first(1)?.asData, let sig = sm.first(2)?.asData else { throw UKey2Error.badMessage }
        let expected = UK.hmac(key: macSub, headerAndBody)
        guard constantTimeEqual(expected, sig) else { throw UKey2Error.authFailed }
        let hab = try ProtoMessage(headerAndBody)
        guard let header = hab.first(1)?.asData, let encBody = hab.first(2)?.asData else { throw UKey2Error.badMessage }
        let hdr = try ProtoMessage(header)
        guard let iv = hdr.first(5)?.asData, let md = hdr.first(6)?.asData else { throw UKey2Error.badMessage }
        let meta = try ProtoMessage(md)
        guard meta.first(1)?.asInt == Int64(kPayloadD2DMessage) else { throw UKey2Error.badMessage }
        return try UK.aesCbcDecrypt(key: encSub, iv: iv, encBody)
    }
}

/// Replicates java.util.Arrays.hashCode(byte[]) with 32-bit wraparound and signed bytes.
private func javaByteArrayHashCode(_ d: Data) -> Int32 {
    var r: Int32 = 1
    for byte in d { r = 31 &* r &+ Int32(Int8(bitPattern: byte)) }
    return r
}

private func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
    guard a.count == b.count else { return false }
    var diff: UInt8 = 0
    for i in 0..<a.count { diff |= a[a.startIndex + i] ^ b[b.startIndex + i] }
    return diff == 0
}

// MARK: - Responder

public final class UKey2Responder: LinkEncryption {
    private enum Phase { case awaitingClientInit, awaitingClientFinish, established, failed }
    private var phase: Phase = .awaitingClientInit

    private let privateKey = P256.KeyAgreement.PrivateKey()
    private var m1 = Data()               // raw ClientInit Ukey2Message bytes
    private var m2 = Data()               // raw ServerInit Ukey2Message bytes
    private var commitment = Data()       // SHA512(ClientFinished) expected
    private var context: D2DContext?
    private var verification: String?

    private let verificationByteLength: Int

    public init(verificationByteLength: Int = 16) {
        self.verificationByteLength = max(1, min(32, verificationByteLength))
    }

    public var isEstablished: Bool { phase == .established }
    public var verificationCode: String? { verification }

    public func handleHandshakeMessage(_ message: Data) throws -> Data? {
        switch phase {
        case .awaitingClientInit: return try onClientInit(message)
        case .awaitingClientFinish: try onClientFinish(message); return nil
        default: phase = .failed; throw UKey2Error.badState
        }
    }

    private func onClientInit(_ raw: Data) throws -> Data {
        let msg = try ProtoMessage(raw)
        guard msg.first(1)?.asInt == Int64(Ukey2Type.clientInit), let inner = msg.first(2)?.asData else {
            phase = .failed; throw UKey2Error.badMessage
        }
        let ci = try ProtoMessage(inner)
        // Record this cipher's commitment (P256_SHA512).
        var found = Data()
        for v in (ci.fields[3] ?? []) {
            guard let cc = v.asData else { continue }
            let c = try ProtoMessage(cc)
            if c.first(1)?.asInt == Int64(kP256SHA512), let cm = c.first(2)?.asData { found = cm }
        }
        guard !found.isEmpty else { phase = .failed; throw UKey2Error.badMessage }
        commitment = found
        m1 = raw

        // Build ServerInit with our public key.
        var si = ProtoWriter()
        si.int32(1, 1)                                   // version
        si.bytes(2, UK.random(32))                       // random
        si.int32(3, kP256SHA512)                         // handshake_cipher
        si.bytes(4, genericEcPublicKey(x963: privateKey.publicKey.x963Representation)) // public_key
        m2 = ukey2Message(type: Ukey2Type.serverInit, data: si.data)
        phase = .awaitingClientFinish
        return m2
    }

    private func onClientFinish(_ raw: Data) throws {
        guard constantTimeEqual(UK.sha512(raw), commitment) else {
            phase = .failed; throw UKey2Error.commitmentMismatch
        }
        let msg = try ProtoMessage(raw)
        guard msg.first(1)?.asInt == Int64(Ukey2Type.clientFinish), let inner = msg.first(2)?.asData else {
            phase = .failed; throw UKey2Error.badMessage
        }
        let cf = try ProtoMessage(inner)
        guard let pkBytes = cf.first(1)?.asData else { phase = .failed; throw UKey2Error.badMessage }
        let theirPublic = try parseEcPublicKey(pkBytes)

        // derivedSecretKey = SHA256(ECDH(ourPriv, theirPub))
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: theirPublic)
        let dhs = shared.withUnsafeBytes { Data($0) }
        let derived = UK.sha256(dhs)
        let info = m1 + m2

        let authString = UK.hkdf(ikm: derived, salt: Data("UKEY2 v1 auth".utf8), info: info)
        verification = authString.prefix(verificationByteLength)
            .base64EncodedString().replacingOccurrences(of: "=", with: "")

        let nextSecret = UK.hkdf(ikm: derived, salt: Data("UKEY2 v1 next".utf8), info: info)
        // deriveNewKeyForPurpose uses D2DCryptoOps.SALT = SHA256("D2D"), not the SecureMessage salt.
        let clientKey = UK.hkdf(ikm: nextSecret, salt: UK.d2dSalt, info: Data("client".utf8))
        let serverKey = UK.hkdf(ikm: nextSecret, salt: UK.d2dSalt, info: Data("server".utf8))
        // Responder encodes with serverKey, decodes with clientKey.
        context = D2DContext(encodeKey: serverKey, decodeKey: clientKey)
        phase = .established
    }

    public func seal(_ plaintext: Data) throws -> Data {
        guard phase == .established, let context else { throw UKey2Error.notEstablished }
        return try context.seal(plaintext)
    }

    public func open(_ ciphertext: Data) throws -> Data {
        guard phase == .established, let context else { throw UKey2Error.notEstablished }
        return try context.open(ciphertext)
    }
}
