import tempfile
import unittest
from pathlib import Path

from adaptive_ecu_learning import ECULearningEngine, IsoTpDecoder


class IsoTpTests(unittest.TestCase):
    def test_single_frame(self):
        decoder = IsoTpDecoder()
        self.assertEqual(decoder.feed(0x7E8, "RX", bytes.fromhex("03 67 01 AA")), bytes.fromhex("67 01 AA"))

    def test_multiframe(self):
        decoder = IsoTpDecoder()
        self.assertIsNone(decoder.feed(0x7E8, "RX", bytes.fromhex("10 08 67 01 01 02 03 04")))
        self.assertEqual(decoder.feed(0x7E8, "RX", bytes.fromhex("21 05 06 07 08")), bytes.fromhex("67 01 01 02 03 04 05 06"))


class LearningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.engine = ECULearningEngine(Path(self.tmp.name) / "learn.sqlite3")

    def tearDown(self):
        self.tmp.cleanup()

    def test_seed_key_exchange_is_correlated_and_persisted(self):
        self.engine.ingest_uds_message(0x7E0, "TX", bytes.fromhex("27 01"))
        seed_event = self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 01 12 34"))
        self.assertEqual(seed_event["event"], "seed_captured")
        key_event = self.engine.ingest_uds_message(0x7E0, "TX", bytes.fromhex("27 02 AB CD"))
        self.assertEqual(key_event["event"], "key_submission_observed")
        accepted = self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 02"))
        self.assertEqual(accepted["event"], "key_accepted")
        state = self.engine.snapshot()
        exchange = state["seed_key_exchanges"][0]
        self.assertEqual(exchange["seed_hex"], "12 34")
        self.assertEqual(exchange["key_hex"], "AB CD")
        self.assertEqual(exchange["status"], "accepted")

    def test_security_positive_response_without_submitted_key_is_not_accepted(self):
        self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 01 11 22"))
        event = self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 02"))
        self.assertEqual(event["event"], "security_positive_response_unpaired")
        self.assertEqual(self.engine.snapshot()["seed_key_exchanges"][0]["status"], "seed_captured")

    def test_seed_must_be_fresh_and_unconsumed(self):
        self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 01 11 22"))
        pending = self.engine.pending_seed("ISO15765:0x7E8", 0x02)
        self.assertTrue(pending["ok"])
        self.assertEqual(pending["seed_hex"], "11 22")
        self.engine.ingest_uds_message(0x7E0, "TX", bytes.fromhex("27 02 AA BB"))
        self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 02"))
        consumed = self.engine.pending_seed("ISO15765:0x7E8", 0x02)
        self.assertFalse(consumed["ok"])

    def test_invalid_key_is_recorded_without_retry(self):
        self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 01 01 02"))
        self.engine.ingest_uds_message(0x7E0, "TX", bytes.fromhex("27 02 99 99"))
        event = self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("7F 27 35"))
        self.assertEqual(event["security_status"], "key_rejected")
        exchange = self.engine.snapshot()["seed_key_exchanges"][0]
        self.assertEqual(exchange["status"], "rejected")
        self.assertEqual(exchange["nrc"], 0x35)

    def test_attempt_limit_and_delay_are_marked_lockout(self):
        self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 01 01"))
        self.engine.ingest_uds_message(0x7E0, "TX", bytes.fromhex("27 02 99"))
        event = self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("7F 27 36"))
        self.assertEqual(event["security_status"], "lockout_or_delay")
        self.assertEqual(self.engine.snapshot()["seed_key_exchanges"][0]["status"], "lockout_or_delay")

    def test_candidate_key_requires_three_accepted_pairs(self):
        pairs = [(0x10, 0xA5), (0x22, 0x97), (0x35, 0x80)]
        for seed, key in pairs:
            self.engine.ingest_uds_message(0x7E8, "RX", bytes([0x67, 0x01, seed]))
            self.engine.ingest_uds_message(0x7E0, "TX", bytes([0x27, 0x02, key]))
            self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("67 02"))
        unconfirmed = self.engine.analyze_seed_key_candidates("ISO15765:0x7E8", 0x02)
        self.assertEqual(unconfirmed["status"], "ecu_identity_unconfirmed")
        self.engine.set_ecu_identity("ISO15765:0x7E8", {
            "ecu_part_number": "YAMAHA-TEST-ECU-001",
            "software_number": "SW-TEST-001"
        })
        analysis = self.engine.analyze_seed_key_candidates("ISO15765:0x7E8", 0x02)
        self.assertEqual(analysis["status"], "candidate_found_unverified")
        result = self.engine.calculate_candidate_key("ISO15765:0x7E8", 0x02, "44")
        self.assertTrue(result["ok"])
        self.assertEqual(result["key_hex"], "F1")
        self.assertTrue(result["requires_user_approval"])
        self.assertFalse(result["ecu_acceptance_verified"])

    def test_kwp_seed_response_is_learned_from_valid_frame(self):
        payload = bytes.fromhex("67 01 12 34")
        # Format, length, target, source, payload, checksum.
        frame = bytearray([0x80, len(payload) + 3, 0xF1, 0x10]) + bytearray(payload) + bytearray([0])
        frame[-1] = sum(frame[:-1]) & 0xFF
        event = self.engine.ingest_kwp_frame(frame, "RX")
        self.assertEqual(event["event"], "seed_captured")
        self.assertEqual(event["ecu_key"], "KWP2000:0x010")

    def test_can_tx_rx_frames_share_same_ecu_profile(self):
        self.engine.ingest_can_frame(0x7E0, bytes.fromhex("02 27 01"), "TX")
        self.engine.ingest_can_frame(0x7E8, bytes.fromhex("04 67 01 12 34"), "RX")
        profiles = self.engine.snapshot()["ecu_profiles"]
        keys = {row["ecu_key"] for row in profiles}
        self.assertEqual(keys, {"ISO15765:0x7E8"})

    def test_observed_ecu_profile_survives_restart(self):
        event = self.engine.ingest_uds_message(0x7E8, "RX", bytes.fromhex("62 F1 90 01 02"))
        self.assertEqual(event["event"], "positive_response")
        reopened = ECULearningEngine(Path(self.tmp.name) / "learn.sqlite3")
        state = reopened.snapshot()
        self.assertEqual(state["frame_count"], 0)
        self.assertEqual(state["ecu_profiles"][0]["ecu_key"], "ISO15765:0x7E8")
        self.assertEqual(state["recent_observations"][0]["did"], 0xF190)

    def test_observed_abs_routine_is_candidate_not_executable(self):
        self.engine.ingest_uds_message(0x7E0, "TX", bytes.fromhex("31 01 12 34"))
        state = self.engine.snapshot()
        self.assertEqual(len(state["active_test_candidates"]), 1)
        candidate = state["active_test_candidates"][0]
        self.assertEqual(candidate["did"], 0x1234)
        self.assertFalse(candidate["verified"])
        self.assertFalse(candidate["execution_allowed"])

    def test_no_fake_algorithm_claim(self):
        state = self.engine.snapshot()
        self.assertEqual(state["key_algorithm_status"], "provider_or_verified_samples_required")
        self.assertFalse(state["automatic_actions"]["guess_or_bruteforce_key"])


if __name__ == "__main__":
    unittest.main()
