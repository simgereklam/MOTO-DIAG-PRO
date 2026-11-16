"""Embedded local backend for the unified MOTO DIAG PRO / SavvyCAN desktop app."""
import os

# The diagnostic web interface is hosted locally and shown inside SavvyCAN's Qt window.
os.environ.setdefault("MOTO_DIAG_EMBEDDED", "1")

from SEYFI_USTA_MASAUSTE import run_flask


if __name__ == "__main__":
    run_flask(host="127.0.0.1", port=5000)
