import unittest
import importlib.util

from diagnostic_engine import DiagnosticAnalyzer, IsoTpAssembler, classify_payload, decode_yamaha_cp3_frames


class DiagnosticEngineTests(unittest.TestCase):
    def test_security_access_positive_response_is_detected(self):
        result = classify_payload(bytes.fromhex("67 01 12 34 56 78"))
        self.assertEqual(result["service"], "SecurityAccess")
        self.assertEqual(result["security_exchange"], "seed_response")
        self.assertEqual(result["seed_hex"], "12 34 56 78")

    def test_negative_response_code_is_decoded(self):
        result = classify_payload(bytes.fromhex("7F 27 35"))
        self.assertIn("InvalidKey", result["meaning"])

    def test_isotp_single_frame(self):
        assembler = IsoTpAssembler()
        self.assertEqual(assembler.feed("7E8", bytes.fromhex("03 62 F1 90")),
                         bytes.fromhex("62 F1 90"))

    def test_standard_request_response_pair(self):
        frames = [
            {"id": "7E0", "data": "02 10 03"},
            {"id": "7E8", "data": "02 50 03"},
            {"id": "7E8", "data": "03 67 01 AA"},
        ]
        result = DiagnosticAnalyzer().analyze(frames, "engine")
        self.assertEqual(result["request_response_pairs"][0]["request_id"], "0x7E0")
        self.assertEqual(len(result["security_access_observations"]), 1)

    def test_invalid_module_is_rejected(self):
        with self.assertRaises(ValueError):
            DiagnosticAnalyzer().analyze([], "gearbox")

    @unittest.skipUnless(importlib.util.find_spec("cantools"), "cantools dependency is installed in installer build")
    def test_yamaha_cp3_dbc_decodes_documented_wheel_speed(self):
        result = decode_yamaha_cp3_frames([
            {"id": "205", "data": "2B 21 2B 78 00 04 00 00"}
        ])
        self.assertEqual(result["profile_id"], "YAMAHA-CP3-TRACER9-RN70-2022-EU")
        self.assertEqual(result["frames_decoded"], 1)
        signals = result["decoded"][0]["signals"]
        self.assertAlmostEqual(signals["WHEEL_SPEED_FRONT"], 109.3, places=1)


if __name__ == "__main__":
    unittest.main()
