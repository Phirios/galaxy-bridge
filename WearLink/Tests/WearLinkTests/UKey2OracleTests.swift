import XCTest
@testable import WearLink

/// Cross-implementation interop against the real google/ukey2 library acting as the UKEY2
/// initiator (see research/ukey2-src/src/main/java/wearlink/Ukey2InitiatorCli.java). This proves
/// the handshake transcript and the D2D signcrypt framing are byte-exact with upstream, which the
/// in-process round-trip and the session-unique vector cannot do alone.
///
/// Skipped unless the oracle is available. Run it with scripts/ukey2_oracle_check.sh, which sets:
///   JAVA_HOME         — a JDK
///   UKEY2_ORACLE_CP   — runtime classpath of the compiled ukey2 library + the CLI
final class UKey2OracleTests: XCTestCase {
    func testGoogleInitiatorInterop() throws {
        let env = ProcessInfo.processInfo.environment
        guard let cp = env["UKEY2_ORACLE_CP"], let javaHome = env["JAVA_HOME"] else {
            throw XCTSkip("Set JAVA_HOME and UKEY2_ORACLE_CP (see scripts/ukey2_oracle_check.sh)")
        }
        let java = "\(javaHome)/bin/java"
        guard FileManager.default.isExecutableFile(atPath: java) else {
            throw XCTSkip("No java at \(java)")
        }

        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: java)
        proc.arguments = ["-cp", cp, "wearlink.Ukey2InitiatorCli"]
        let stdin = Pipe(), stdout = Pipe()
        proc.standardInput = stdin; proc.standardOutput = stdout
        proc.standardError = FileHandle.standardError
        try proc.run()
        defer { stdin.fileHandleForWriting.closeFile(); proc.waitUntilExit() }

        var buffer = Data()
        func readLine() throws -> String {
            while !buffer.contains(0x0a) {
                let chunk = stdout.fileHandleForReading.availableData
                if chunk.isEmpty { throw UKey2TestError.oracleClosed }
                buffer.append(chunk)
            }
            let nl = buffer.firstIndex(of: 0x0a)!
            let line = buffer[buffer.startIndex..<nl]
            buffer.removeSubrange(buffer.startIndex...nl)
            return String(data: line, encoding: .utf8)!.trimmingCharacters(in: .whitespaces)
        }
        func send(_ s: String) { stdin.fileHandleForWriting.write(Data("\(s)\n".utf8)) }

        let responder = UKey2Responder()

        // Handshake: Google ClientInit -> our ServerInit -> Google ClientFinished.
        send("CI")
        let clientInit = try hexToData(readLine())
        let serverInit = try XCTUnwrap(try responder.handleHandshakeMessage(clientInit))
        send("SI \(dataToHex(serverInit))")
        let clientFinished = try hexToData(readLine())
        XCTAssertNil(try responder.handleHandshakeMessage(clientFinished))
        XCTAssertTrue(responder.isEstablished)

        // Both sides must derive the same verification code.
        send("V")
        let googleAuth = try hexToData(readLine())        // 16 raw bytes
        let googleCode = googleAuth.base64EncodedString().replacingOccurrences(of: "=", with: "")
        XCTAssertEqual(responder.verificationCode, googleCode)

        // Google initiator -> our responder.
        let g2s = Data("hello from google".utf8)
        send("E \(dataToHex(g2s))")
        let encFromGoogle = try hexToData(readLine())
        XCTAssertEqual(try responder.open(encFromGoogle), g2s)

        // Our responder -> Google initiator.
        let s2g = Data("hi from swift".utf8)
        let sealed = try responder.seal(s2g)
        send("D \(dataToHex(sealed))")
        XCTAssertEqual(try hexToData(readLine()), s2g)

        // A second frame each way (sequence numbers advance in lockstep).
        send("E \(dataToHex(Data("two".utf8)))")
        XCTAssertEqual(try responder.open(try hexToData(readLine())), Data("two".utf8))
        send("D \(dataToHex(try responder.seal(Data("three".utf8))))")
        XCTAssertEqual(try hexToData(readLine()), Data("three".utf8))
    }
}

private enum UKey2TestError: Error { case oracleClosed }

private func dataToHex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
private func hexToData(_ s: String) throws -> Data {
    var d = Data(); var i = s.startIndex
    while i < s.endIndex, let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex) {
        guard let b = UInt8(s[i..<j], radix: 16) else { throw UKey2TestError.oracleClosed }
        d.append(b); i = j
    }
    return d
}
