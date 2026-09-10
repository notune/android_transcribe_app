import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify_model_apk.py")
ASSET = "assets/builtin-model/parakeet-tdt-0.6b-v3-Q4_K_M.gguf"


class VerifyModelApkTest(unittest.TestCase):
    def run_verify(self, entries, expected_bytes=b"model", manifest_package="dev.ipf.offlinespeechtotext"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "candidate.apk"
            metadata = root / "model.json"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(apk, "w") as archive:
                    for name, content in entries:
                        archive.writestr(name, content)
            metadata.write_text(json.dumps({
                "asset_path": ASSET,
                "byte_length": len(expected_bytes),
                "sha256": hashlib.sha256(expected_bytes).hexdigest(),
                "application_id": "dev.ipf.offlinespeechtotext",
            }))
            return subprocess.run(
                [sys.executable, str(SCRIPT), "--apk", str(apk), "--metadata", str(metadata),
                 "--manifest-package", manifest_package],
                text=True, capture_output=True,
            )

    def test_accepts_exact_asset_and_package(self):
        result = self.run_verify([(ASSET, b"model")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verified", result.stdout)

    def test_rejects_missing_empty_wrong_length_bad_hash_and_duplicate_asset(self):
        cases = [
            [],
            [(ASSET, b"")],
            [(ASSET, b"mode")],
            [(ASSET, b"modem")],
            [(ASSET, b"model"), (ASSET, b"model")],
        ]
        for entries in cases:
            with self.subTest(entries=len(entries), sizes=[len(x[1]) for x in entries]):
                self.assertNotEqual(self.run_verify(entries).returncode, 0)

    def test_rejects_wrong_manifest_package(self):
        result = self.run_verify([(ASSET, b"model")], manifest_package="dev.notune.transcribe")
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
