import XCTest
import CryptoKit
#if canImport(CommonCrypto)
import CommonCrypto
#endif
@testable import WearLink

/// An independent UKEY2 *initiator* (written separately from the responder) so the round-trip
/// is a real cross-check: both sides must agree on the verification code and exchange messages.
/// The official google/ukey2 library is the external oracle (added in a later step).
private final class TestInitiator {
    let priv = P256.KeyAgreement.PrivateKey()
    var clientKey = Data(); var serverKey = Data()
    var encodeSeq: Int32 = 0; var decodeSeq: Int32 = 0
    var verification = ""

    let salt = Data(SHA256.hash(data: Data("SecureMessage".utf8)))
    let d2dSalt = Data(SHA256.hash(data: Data("D2D".utf8)))
    func hkdf(_ ikm: Data, _ s: Data, _ info: Data) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: s, info: info, outputByteCount: 32)
            .withUnsafeBytes { Data($0) }
    }
    func bigInt(_ c: Data) -> Data { var b = c; while b.first == 0 { b.removeFirst() }; if let f = b.first, f & 0x80 != 0 { b.insert(0, at: 0) }; return b }
    func coord(_ r: Data) -> Data { var b = r; if b.count == 33, b.first == 0 { b.removeFirst() }; if b.count < 32 { b = Data(repeating: 0, count: 32 - b.count) + b }; return b }
    func generic(_ x963: Data) -> Data {
        var ec = ProtoWriter(); ec.bytes(1, bigInt(x963.subdata(in: 1..<33))); ec.bytes(2, bigInt(x963.subdata(in: 33..<65)))
        var g = ProtoWriter(); g.int32(1, 1); g.bytes(2, ec.data); return g.data
    }
    func msg(_ t: Int32, _ d: Data) -> Data { var w = ProtoWriter(); w.int32(1, t); w.bytes(2, d); return w.data }

    // Build M1 (ClientInit) with a commitment to the ClientFinished we'll send.
    private(set) var clientFinishMsg = Data()
    func clientInit() -> Data {
        var cfInner = ProtoWriter(); cfInner.bytes(1, generic(priv.publicKey.x963Representation))
        clientFinishMsg = msg(4, cfInner.data)
        let commitment = Data(SHA512.hash(data: clientFinishMsg))
        var cc = ProtoWriter(); cc.int32(1, 100); cc.bytes(2, commitment)
        var ci = ProtoWriter(); ci.int32(1, 1); ci.bytes(2, Data(repeating: 7, count: 32)); ci.bytes(3, cc.data); ci.string(4, "AES_256_CBC-HMAC_SHA256")
        return msg(2, ci.data)
    }

    func finish(serverInitMsg: Data, m1: Data) throws {
        let outer = try ProtoMessage(serverInitMsg)
        let si = try ProtoMessage(outer.first(2)!.asData!)
        let gpk = try ProtoMessage(si.first(4)!.asData!)
        let ec = try ProtoMessage(gpk.first(2)!.asData!)
        let x963 = Data([0x04]) + coord(ec.first(1)!.asData!) + coord(ec.first(2)!.asData!)
        let serverPub = try P256.KeyAgreement.PublicKey(x963Representation: x963)
        let dhs = try priv.sharedSecretFromKeyAgreement(with: serverPub).withUnsafeBytes { Data($0) }
        let derived = Data(SHA256.hash(data: dhs))
        let info = m1 + serverInitMsg
        let auth = hkdf(derived, Data("UKEY2 v1 auth".utf8), info)
        verification = auth.prefix(16).base64EncodedString().replacingOccurrences(of: "=", with: "")
        let next = hkdf(derived, Data("UKEY2 v1 next".utf8), info)
        clientKey = hkdf(next, d2dSalt, Data("client".utf8))
        serverKey = hkdf(next, d2dSalt, Data("server".utf8))
    }

    #if canImport(CommonCrypto)
    private func aes(_ op: Int, _ k: Data, _ iv: Data, _ input: Data) -> Data {
        let cap = input.count + kCCBlockSizeAES128
        var out = Data(count: cap); var moved = 0
        _ = out.withUnsafeMutableBytes { o in input.withUnsafeBytes { i in k.withUnsafeBytes { kk in iv.withUnsafeBytes { vv in
            CCCrypt(CCOperation(op), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                    kk.baseAddress, k.count, vv.baseAddress, i.baseAddress, input.count, o.baseAddress, cap, &moved) } } } }
        out.removeSubrange(moved..<out.count); return out
    }
    #else
    private func aes(_ op: Int, _ k: Data, _ iv: Data, _ input: Data) -> Data { Data() }
    #endif
    func purpose(_ k: Data, _ p: String) -> Data { hkdf(k, salt, Data(p.utf8)) }
    func hmac(_ k: Data, _ d: Data) -> Data { Data(HMAC<SHA256>.authenticationCode(for: d, using: SymmetricKey(data: k))) }

    // Initiator encodes with clientKey, decodes with serverKey (mirror of the responder).
    func seal(_ pt: Data) -> Data {
        encodeSeq += 1
        var dm = ProtoWriter(); dm.bytes(1, pt); dm.int32(2, encodeSeq)
        let enc = purpose(clientKey, "ENC:2"), mac = purpose(clientKey, "SIG:1")
        var iv = Data(count: 16); iv.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        var md = ProtoWriter(); md.int32(1, 13); md.int32(2, 1)
        var hdr = ProtoWriter(); hdr.int32(1, 1); hdr.int32(2, 2); hdr.bytes(5, iv); hdr.bytes(6, md.data)
        let body = aes(kCCEncrypt, enc, iv, dm.data)
        var hab = ProtoWriter(); hab.bytes(1, hdr.data); hab.bytes(2, body)
        var sm = ProtoWriter(); sm.bytes(1, hab.data); sm.bytes(2, hmac(mac, hab.data)); return sm.data
    }
    func open(_ ct: Data) throws -> Data {
        let enc = purpose(serverKey, "ENC:2"), mac = purpose(serverKey, "SIG:1")
        let sm = try ProtoMessage(ct)
        let hab = sm.first(1)!.asData!
        XCTAssertEqual(hmac(mac, hab), sm.first(2)!.asData!)
        let h = try ProtoMessage(hab)
        let hdr = try ProtoMessage(h.first(1)!.asData!)
        let pt = aes(kCCDecrypt, enc, hdr.first(5)!.asData!, h.first(2)!.asData!)
        decodeSeq += 1
        let dm = try ProtoMessage(pt)
        XCTAssertEqual(Int32(dm.first(2)!.asInt!), decodeSeq)
        return dm.first(1)!.asData!
    }
}

final class UKey2ResponderTests: XCTestCase {
    func testHandshakeAndTransport() throws {
        let responder = UKey2Responder()
        let initiator = TestInitiator()

        let m1 = initiator.clientInit()
        let serverInit = try responder.handleHandshakeMessage(m1)
        XCTAssertNotNil(serverInit)
        XCTAssertNil(try responder.handleHandshakeMessage(initiator.clientFinishMsg))
        try initiator.finish(serverInitMsg: serverInit!, m1: m1)

        XCTAssertTrue(responder.isEstablished)
        XCTAssertEqual(responder.verificationCode, initiator.verification)
        XCTAssertFalse(responder.verificationCode?.isEmpty ?? true)

        // Responder → initiator.
        let a = Data("watch, this is the phone".utf8)
        XCTAssertEqual(try initiator.open(responder.seal(a)), a)
        // Initiator → responder.
        let b = Data("phone, this is the watch".utf8)
        XCTAssertEqual(try responder.open(initiator.seal(b)), b)
        // A second message each way (sequence numbers advance).
        let c = Data("second frame".utf8)
        XCTAssertEqual(try initiator.open(responder.seal(c)), c)
        XCTAssertEqual(try responder.open(initiator.seal(c)), c)
    }

    /// Official cross-check against google/ukey2's own vector
    /// (D2DConnectionContextTest.testSessionUniqueValues_V1_Initiator, pinned commit 10fc737).
    /// Fixed ASCII keys must yield this exact 32-byte session-unique value. Proves the D2D SALT
    /// (SHA256("D2D")), the SHA-256, and the Java-hashCode key ordering all match upstream.
    func testSessionUniqueMatchesGoogleVector() {
        let enc = Data("initiator_encode_key_for_aes_256".utf8)
        let dec = Data("initiator_decode_key_for_aes_256".utf8)
        let expected = Data([
            0x91, 0xc7, 0xc9, 0x26, 0x2c, 0x17, 0x8a, 0xa0, 0x36, 0x9f, 0xf2, 0x05, 0x20, 0x98,
            0x38, 0x53, 0xa5, 0x46, 0xab, 0x3a, 0x21, 0x3b, 0x76, 0x58, 0x59, 0x4e, 0xe7, 0xe3,
            0xc1, 0x69, 0x87, 0xfa])
        // Initiator orientation and responder orientation must both match.
        XCTAssertEqual(D2DContext(encodeKey: enc, decodeKey: dec).sessionUnique(), expected)
        XCTAssertEqual(D2DContext(encodeKey: dec, decodeKey: enc).sessionUnique(), expected)
    }

    func testTamperedFrameThrows() throws {
        let responder = UKey2Responder()
        let initiator = TestInitiator()
        let m1 = initiator.clientInit()
        let si = try responder.handleHandshakeMessage(m1)!
        _ = try responder.handleHandshakeMessage(initiator.clientFinishMsg)
        try initiator.finish(serverInitMsg: si, m1: m1)

        var frame = initiator.seal(Data("hello".utf8))
        frame[frame.count - 1] ^= 0x01            // flip a signature bit
        XCTAssertThrowsError(try responder.open(frame))
    }
}
