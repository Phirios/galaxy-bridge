import XCTest
@testable import WearLink

final class GBLinkFramingTests: XCTestCase {
    func testChunkRoundTrip() throws {
        let message = Data((0..<1000).map { UInt8($0 & 0xFF) })
        var assembler = GBLinkAssembler()
        var result: Data?
        for chunk in GBLink.chunks(for: message, maxChunk: 20) {
            XCTAssertLessThanOrEqual(chunk.count, 20)
            if let m = try assembler.append(chunk) { result = m }
        }
        XCTAssertEqual(result, message)
    }

    func testSingleChunkHasBothFlags() {
        let chunks = GBLink.chunks(for: Data("{}".utf8), maxChunk: 180)
        XCTAssertEqual(chunks.count, 1)
        XCTAssertEqual(chunks[0].first, 0x03)
    }

    func testRejectsContinuationWithoutStart() {
        var assembler = GBLinkAssembler()
        XCTAssertThrowsError(try assembler.append(Data([0x02, 1, 2]))) {
            XCTAssertEqual($0 as? GBLinkError, .chunkOutOfOrder)
        }
    }

    func testRejectsOversizedDeclaredLength() {
        var assembler = GBLinkAssembler()
        let huge = UInt32(GBLink.maxMessageBytes + 1).bigEndian
        var chunk = Data([0x01])
        withUnsafeBytes(of: huge) { chunk.append(contentsOf: $0) }
        XCTAssertThrowsError(try assembler.append(chunk)) {
            XCTAssertEqual($0 as? GBLinkError, .tooLarge)
        }
    }

    func testRejectsMoreBytesThanDeclared() {
        var assembler = GBLinkAssembler()
        XCTAssertThrowsError(try assembler.append(Data([0x03, 0, 0, 0, 1, 0xAA, 0xBB]))) {
            XCTAssertEqual($0 as? GBLinkError, .lengthMismatch)
        }
    }

    func testRecoversAfterError() throws {
        var assembler = GBLinkAssembler()
        _ = try? assembler.append(Data([0x02, 9]))
        let ok = try assembler.append(GBLink.chunks(for: Data("x".utf8), maxChunk: 50)[0])
        XCTAssertEqual(ok, Data("x".utf8))
    }
}

final class GBMessageTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    var nowMs: Int64 { Int64(now.timeIntervalSince1970 * 1000) }
    let batch = "8A0F5C2E-3B1D-4C8E-9F7A-1D2C3B4A5E6F"

    func testSamplesRoundTrip() throws {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .heartRate, start: nowMs - 60_000, end: nowMs - 60_000, value: 71),
            HealthSample(kind: .steps, start: nowMs - 120_000, end: nowMs - 60_000, value: 140),
        ])
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), m)
    }

    func testSensorAndSleepSamplesRoundTrip() throws {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .spo2, start: nowMs, end: nowMs, value: 97),
            HealthSample(kind: .skinTemp, start: nowMs, end: nowMs, value: 33.4),
            HealthSample(kind: .restingHeartRate, start: nowMs, end: nowMs, value: 58),
            HealthSample(kind: .sleep, start: nowMs - 3_600_000, end: nowMs, value: SleepStage.asleep.rawValue),
        ])
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), m)
    }

    func testHealthDepthSamplesRoundTrip() throws {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .hrv, start: nowMs - 60_000, end: nowMs, value: 42.5),
            HealthSample(kind: .water, start: nowMs, end: nowMs, value: 250),
            HealthSample(kind: .caffeine, start: nowMs, end: nowMs, value: 95),
            HealthSample(kind: .menstrualFlow, start: nowMs, end: nowMs, value: MenstrualFlow.medium.rawValue),
            HealthSample(kind: .sleepingHeartRate, start: nowMs, end: nowMs, value: 52),
        ])
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), m)
        // Wire names must match the Kotlin SampleKind enum.
        XCTAssertEqual([SampleKind.hrv, .water, .caffeine, .menstrualFlow, .sleepingHeartRate].map(\.rawValue),
                       ["hrv", "water", "caffeine", "flow", "shr"])
    }

    func testRejectsImplausibleHealthDepthSamples() throws {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .hrv, start: nowMs, end: nowMs, value: 0),         // no such HRV
            HealthSample(kind: .water, start: nowMs, end: nowMs, value: 20_000),  // 20 litres at once
            HealthSample(kind: .menstrualFlow, start: nowMs, end: nowMs, value: 9),
            HealthSample(kind: .caffeine, start: nowMs, end: nowMs, value: 63),
        ])
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), .samples(batchId: batch, items: [
            HealthSample(kind: .caffeine, start: nowMs, end: nowMs, value: 63),
        ]))
    }

    func testHealthDepthFeatureMessages() throws {
        XCTAssertNotNil(GBFeature.message("hdConfig", ["goalSteps": 8000]))
        XCTAssertNotNil(GBFeature.message("hdGetState"))
        XCTAssertNotNil(GBFeature.message("hdMeasureHrv"))
        let result = Data(#"{"t":"hrvResult","ok":true,"sdnn":40,"rmssd":35,"stress":45,"at":1}"#.utf8)
        guard case let .raw(type, _) = try GBMessage.decode(result) else { return XCTFail() }
        XCTAssertEqual(type, "hrvResult")
        guard case .raw("hdState", _) = try GBMessage.decode(Data(#"{"t":"hdState"}"#.utf8)) else { return XCTFail() }
    }

    func testRejectsImplausibleSpo2() {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .spo2, start: nowMs, end: nowMs, value: 20),   // below 50%: impossible
            HealthSample(kind: .spo2, start: nowMs, end: nowMs, value: 95),
        ])
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), .samples(batchId: batch, items: [
            HealthSample(kind: .spo2, start: nowMs, end: nowMs, value: 95),
        ]))
    }

    func testRejectsImplausibleHeartRate() {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .heartRate, start: nowMs, end: nowMs, value: 900),
            HealthSample(kind: .heartRate, start: nowMs, end: nowMs, value: 70),
        ])
        // The bad sample is dropped; the good one survives so the batch can be acked.
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), .samples(batchId: batch, items: [
            HealthSample(kind: .heartRate, start: nowMs, end: nowMs, value: 70),
        ]))
    }

    func testRejectsNonFiniteValue() throws {
        let json = #"{"t":"samples","id":"\#(batch)","items":[{"k":"hr","start":1,"end":1,"v":1e999}]}"#
        XCTAssertThrowsError(try GBMessage.decode(Data(json.utf8), now: now))
    }

    func testRejectsFutureAndAncientSamples() {
        for start in [nowMs + 3_600_000, nowMs - 90 * 24 * 3_600_000] {
            let m = GBMessage.samples(batchId: batch, items: [
                HealthSample(kind: .steps, start: start, end: start, value: 1),
            ])
            XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), .samples(batchId: batch, items: []))
        }
    }

    func testRejectsEndBeforeStart() {
        let m = GBMessage.samples(batchId: batch, items: [
            HealthSample(kind: .distance, start: nowMs, end: nowMs - 1, value: 5),
        ])
        XCTAssertEqual(try GBMessage.decode(m.encoded(), now: now), .samples(batchId: batch, items: []))
    }

    func testRejectsBadBatchIdAndUnknownType() {
        XCTAssertThrowsError(try GBMessage.decode(Data(#"{"t":"ack","id":"../../x"}"#.utf8)))
        XCTAssertThrowsError(try GBMessage.decode(Data(#"{"t":"rm -rf"}"#.utf8)))
    }

    func testRejectsTooManySamples() {
        let items = (0...GBMessage.maxSamplesPerBatch).map { _ in
            HealthSample(kind: .steps, start: nowMs, end: nowMs, value: 1)
        }
        XCTAssertThrowsError(try GBMessage.decode(GBMessage.samples(batchId: batch, items: items).encoded(), now: now))
    }

    func testBatteryRange() throws {
        XCTAssertEqual(try GBMessage.decode(GBMessage.battery(percent: 55).encoded()), .battery(percent: 55))
        XCTAssertThrowsError(try GBMessage.decode(Data(#"{"t":"battery","pct":101}"#.utf8)))
    }
}

final class GBAdvertTests: XCTestCase {
    func testParsesReadyToPairAndName() {
        XCTAssertEqual(GBAdvert(Data([1, 1]) + Data("A1B2".utf8)), GBAdvert(readyToPair: true, name: "A1B2"))
        XCTAssertEqual(GBAdvert(Data([1, 0])), GBAdvert(readyToPair: false, name: ""))
    }

    func testRejectsUnknownVersionAndOversize() {
        XCTAssertNil(GBAdvert(Data([2, 1])))
        XCTAssertNil(GBAdvert(Data([1, 1]) + Data(repeating: 0x41, count: 11)))
        XCTAssertNil(GBAdvert(Data([1])))
    }

    func testStripsControlCharacters() {
        XCTAssertEqual(GBAdvert(Data([1, 1, 0x41, 0x0A, 0x07, 0x42]))?.name, "AB")
    }

    func testStatusKeepsOnlyKnownSettings() throws {
        let json = #"{"t":"status","model":"SM-R890","os":"Wear OS","app":"0.2.0","pct":80,"charging":false,"freeMB":5000,"totalMB":16000,"settings":{"brightness":200,"timeout":9999,"evil":1},"perms":{"system":true,"dnd":false,"ancs":true}}"#
        guard case let .status(s) = try GBMessage.decode(Data(json.utf8)) else { return XCTFail() }
        XCTAssertEqual(s.settings, ["brightness": 200])
        XCTAssertTrue(s.perms.ancs)
    }

    func testFindAndSetEncode() throws {
        let set = try JSONSerialization.jsonObject(with: GBMessage.set(key: .brightness, value: 10).encoded()) as! [String: Any]
        XCTAssertEqual(set["key"] as? String, "brightness")
        XCTAssertEqual(set["value"] as? Int, 10)
        XCTAssertEqual(try GBMessage.decode(GBMessage.find(on: true).encoded()), .find(on: true))
        let ev = try JSONSerialization.jsonObject(with: GBMessage.events([CalendarItem(title: "A", start: 1, end: 2, allDay: false)]).encoded()) as! [String: Any]
        XCTAssertEqual((ev["items"] as? [[String: Any]])?.first?["title"] as? String, "A")
    }

    func testNewFeatureMessagesRoundTrip() throws {
        // Every new phone→watch feature builds a .raw message whose JSON carries its "t" and fields.
        func decodeBody(_ m: GBMessage?) throws -> [String: Any] {
            guard case let .raw(_, data)? = m else { throw Invalid.missingField("raw") }
            return try JSONSerialization.jsonObject(with: data) as! [String: Any]
        }
        enum Invalid: Error { case missingField(String) }

        let face = try decodeBody(GBFeature.message("applyFace", ["pkg": "com.example.face", "cls": ""]))
        XCTAssertEqual(face["t"] as? String, "applyFace")
        XCTAssertEqual(face["pkg"] as? String, "com.example.face")

        let bia = try decodeBody(GBFeature.message("setBiaProfile", ["height": 178.0, "weight": 74.5, "gender": 1, "age": 31]))
        XCTAssertEqual(bia["t"] as? String, "setBiaProfile")
        XCTAssertEqual(bia["height"] as? Double, 178.0)
        XCTAssertEqual(bia["gender"] as? Int, 1)

        let call = try decodeBody(GBFeature.message("call", ["action": "dial", "number": "+6421234567"]))
        XCTAssertEqual(call["t"] as? String, "call")
        XCTAssertEqual(call["action"] as? String, "dial")

        let install = try decodeBody(GBFeature.message("install", ["id": "abc", "part": 0, "parts": 1, "name": "App", "data": "QQ=="]))
        XCTAssertEqual(install["t"] as? String, "install")
        XCTAssertEqual(install["parts"] as? Int, 1)

        // The watch's install-status reply decodes as a .raw feature message.
        let status = Data(#"{"t":"install","pkg":"com.example.app","status":"ok","detail":"done"}"#.utf8)
        guard case let .raw(type, _) = try GBMessage.decode(status) else { return XCTFail() }
        XCTAssertEqual(type, "install")
    }

    func testCorrectsWrongWatchClock() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let batch = "8A0F5C2E-3B1D-4C8E-9F7A-1D2C3B4A5E6F"
        // Watch thinks it's ~19 months ago; its sample was taken one minute before sending.
        let watchNow = nowMs - 50_000_000_000
        let json = #"{"t":"samples","id":"\#(batch)","now":\#(watchNow),"items":[{"k":"hr","start":\#(watchNow - 60_000),"end":\#(watchNow - 60_000),"v":72}]}"#
        guard case let .samples(_, items) = try GBMessage.decode(Data(json.utf8), now: now) else { return XCTFail() }
        XCTAssertEqual(items.first?.start, nowMs - 60_000)
    }
}
