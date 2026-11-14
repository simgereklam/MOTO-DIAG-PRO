import unittest

from abs_diagnostics import (
    ABS_DTC_DATABASE,
    abs_profile_key,
    decode_abs_dtc,
    evaluate_abs_profile,
    normalize_abs_text,
)


class AbsDiagnosticsTests(unittest.TestCase):
    def test_xmax_2019_known_code_is_decoded(self):
        result = decode_abs_dtc("Yamaha", "XMAX 300", 2019, "11")
        self.assertIn("Ön tekerlek", result)

    def test_xmax_codes_are_scoped_to_exact_year_and_model(self):
        self.assertIn(("yamaha", "xmax 300", 2019, "11"), ABS_DTC_DATABASE)
        self.assertNotIn(("yamaha", "xmax 300", 2020, "11"), ABS_DTC_DATABASE)
        self.assertNotIn(("yamaha", "xmax 250", 2019, "11"), ABS_DTC_DATABASE)

    def test_unknown_model_does_not_inherit_code(self):
        result = decode_abs_dtc("Honda", "NC750X", 2021, "11")
        self.assertIn("bulunamadı", result)

    def test_profile_check_never_claims_unregistered_abs_support(self):
        result = evaluate_abs_profile("Yamaha", "XMAX 300", 2019)
        self.assertFalse(result["verified"])
        self.assertEqual(result["capabilities"], [])
        self.assertIn("hiçbir komut gönderilmedi", result["message"])

    def test_model_key_normalization(self):
        self.assertEqual(
            abs_profile_key("YAMAHA", "XMAX-300", "2019"),
            ("yamaha", "xmax 300", 2019),
        )
        self.assertEqual(normalize_abs_text("XMAX-300"), "xmax 300")

    def test_invalid_year_is_rejected(self):
        with self.assertRaises(ValueError):
            abs_profile_key("Yamaha", "XMAX 300", "yirmi")


if __name__ == "__main__":
    unittest.main()
