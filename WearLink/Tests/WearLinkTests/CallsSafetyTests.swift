import XCTest
@testable import WearLink

final class CallsSafetyTests: XCTestCase {
    private func body(_ m: GBMessage?) throws -> [String: Any] {
        guard case let .raw(_, json)? = m else { XCTFail("not raw"); return [:] }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
    }

    func testPublicEmergencyNumbersAreNeverAutoCalled() {
        for n in ["111", "911", "112", "000", "999", "1-1-1", "+112", " 911 ", "08", "1234"] {
            XCTAssertFalse(EmergencyNumbers.isAllowedForAutoCall(n), n)
        }
        XCTAssertTrue(EmergencyNumbers.isAllowedForAutoCall("+64 21 555 0100"))
        XCTAssertTrue(EmergencyNumbers.isAllowedForAutoCall("(021) 555-0100"))
        XCTAssertFalse(EmergencyNumbers.isAllowedForAutoCall("call me"))
    }

    func testSafetyConfigDefaultsOffAndDisablesAutoCallWithoutContact() {
        let d = SafetyConfig()
        XCTAssertFalse(d.fallDetection || d.sos || d.hrAlerts || d.checkIn)
        var c = SafetyConfig()
        c.contactNumber = "111"
        c.fallDetection = true
        c.sos = true
        c.checkIn = true
        c.hrAlerts = true
        let s = c.sanitized()
        XCTAssertEqual(s.contactNumber, "")
        XCTAssertFalse(s.fallDetection || s.sos || s.checkIn)
        XCTAssertTrue(s.hrAlerts, "HR alerts don't call anyone, so they stay on")
    }

    func testSafetyConfigClampsRanges() throws {
        var c = SafetyConfig()
        c.contactNumber = "+64215550100"
        c.fallDetection = true
        c.countdown = 3
        c.hrHigh = 400
        c.hrLow = 5
        c.checkInHours = 0
        let b = try body(GBFeature.message("safetyConfig", c.payload))
        XCTAssertEqual(b["t"] as? String, "safetyConfig")
        XCTAssertEqual(b["countdown"] as? Int, 15, "countdown is never shorter than 15 s")
        XCTAssertEqual(b["hrHigh"] as? Int, 220)
        XCTAssertEqual(b["hrLow"] as? Int, 30)
        XCTAssertEqual(b["checkInHours"] as? Int, 1)
        XCTAssertEqual(b["fall"] as? Bool, true)
        XCTAssertEqual(b["number"] as? String, "+64215550100")
    }

    func testFavouritesPayloadIsCapped() throws {
        let many = (0..<20).map { CallFavourite(name: "P\($0)", number: "+6421555\(1000 + $0)") }
        let b = try body(GBFeature.message("callFavourites", CallFavourite.payload(many)))
        XCTAssertEqual((b["items"] as? [[String: Any]])?.count, CallsSafetyLimits.maxFavourites)
    }

    func testWatchMessagesDecodeAsRaw() throws {
        for t in ["callLog", "safetyState", "sosAlert", "hrAlert", "findLoud", "quickReply"] {
            let data = try JSONSerialization.data(withJSONObject: ["t": t])
            guard case let .raw(type, _) = try GBMessage.decode(data) else { return XCTFail(t) }
            XCTAssertEqual(type, t)
        }
        for t in ["callFavourites", "callLogGet", "safetyConfig", "safetyGet", "findLoud", "quickReplies"] {
            XCTAssertNotNil(GBFeature.message(t), t)
        }
    }

    func testParsesCallLog() {
        let log = CallLogEntry.parse(["items": [
            ["n": "Sam", "p": "+64215550100", "dir": "out", "at": 1_700_000_000_000],
            ["n": "", "p": "", "dir": "in", "at": 1_700_000_000_000], // nothing to show
            ["n": "X", "dir": "sideways", "at": 1], // bad direction
        ]])
        XCTAssertEqual(log.count, 1)
        XCTAssertEqual(log.first?.direction, .outgoing)
        XCTAssertEqual(log.first?.at, Date(timeIntervalSince1970: 1_700_000_000))
    }

    func testParsesSosAlertAndRejectsBadLocation() {
        let a = SosAlert.parse(["kind": "fall", "stage": "location", "lat": -36.85, "lon": 174.76, "acc": 12.0])
        XCTAssertEqual(a?.kind, .fall)
        XCTAssertTrue(a?.hasLocation == true)
        XCTAssertNotNil(a?.mapsURL)
        let bad = SosAlert.parse(["kind": "sos", "stage": "calling", "lat": 200.0, "lon": 0.0])
        XCTAssertEqual(bad?.hasLocation, false)
        XCTAssertNil(SosAlert.parse(["kind": "nope", "stage": "calling"]))
    }

    func testParsesHrAlert() {
        XCTAssertEqual(HrAlert.parse(["kind": "high", "bpm": 172, "limit": 150])?.bpm, 172)
        XCTAssertNil(HrAlert.parse(["kind": "high", "bpm": 900]))
    }

    func testPrefilledMessageURL() throws {
        let url = try XCTUnwrap(prefilledMessageURL(to: "+64 21 555 0100", body: "I need help & I'm here: https://maps.apple.com/?ll=1,2"))
        XCTAssertTrue(url.absoluteString.hasPrefix("sms:+64215550100&body="))
        XCTAssertFalse(url.absoluteString.contains(" "))
        XCTAssertTrue(url.absoluteString.contains("%26"), "a literal & would end the body early")
    }
}
