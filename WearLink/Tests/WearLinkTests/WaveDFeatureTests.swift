import XCTest
@testable import WearLink

/// Wave D feature messages: the names must be registered in the right direction, and the two
/// new names must not overlap (each side decodes only the other side's set).
final class WaveDFeatureTests: XCTestCase {
    func testPhoneToWatchNamesRegistered() throws {
        for t in ["musicPush", "musicList", "musicDelete", "faceSchedule", "getFaceSchedule", "qrPass",
                  "qrPassDelete", "qrPassList", "homeScenes", "homeSceneResult", "toolsConfig",
                  "translateResult", "checklist", "getTileState"] {
            XCTAssertTrue(GBFeature.toWatch.contains(t), t)
            let m = try XCTUnwrap(GBFeature.message(t, ["id": "abc"]))
            guard case let .raw(type, json) = m else { return XCTFail("not raw") }
            XCTAssertEqual(type, t)
            let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
            XCTAssertEqual(obj["t"] as? String, t)
        }
    }

    func testWatchToPhoneNamesDecodeAsRaw() throws {
        for t in ["musicAck", "musicLib", "faceScheduleState", "qrPasses", "homeScene", "translate",
                  "checklistTick", "reminderAdd", "tileState", "mediaSeek"] {
            let data = try JSONSerialization.data(withJSONObject: ["t": t, "id": "x"])
            guard case let .raw(type, _) = try GBMessage.decode(data) else { return XCTFail(t) }
            XCTAssertEqual(type, t)
        }
    }

    func testWaveDDirectionsDoNotOverlap() {
        let mine: Set<String> = ["musicAck", "musicLib", "faceScheduleState", "qrPasses", "homeScene", "translate",
                                 "checklistTick", "reminderAdd", "tileState", "mediaSeek"]
        XCTAssertTrue(GBFeature.toWatch.isDisjoint(with: mine))
    }
}
