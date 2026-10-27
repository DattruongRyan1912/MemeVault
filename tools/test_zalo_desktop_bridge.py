import json
import os
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))
import zalo_desktop_bridge as bridge  # noqa: E402


def write_test_png(path: Path, width: int = 16, height: int = 12) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    raw = b"".join(b"\x00" + (b"\x00" * width * 4) for _ in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(kind)
        crc = zlib.crc32(data, crc) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", crc)

    path.write_bytes(
        bridge.PNG_SIGNATURE
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


class ZaloDesktopBridgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.zalo = self.root / "ZaloData"
        self.account = self.zalo / "media" / "123" / "sticker"
        self.source = self.root / "source"
        self.bridge = self.root / "bridge"
        self.account.mkdir(parents=True)
        self.source.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_png_info_and_hash(self) -> None:
        path = self.source / "cat.png"
        write_test_png(path)
        info = bridge.png_info(path)
        self.assertEqual((16, 12), (info.width, info.height))
        self.assertTrue(info.has_alpha)
        self.assertEqual(64, len(bridge.sha256_file(path)))

    def test_malformed_png_is_rejected(self) -> None:
        path = self.source / "truncated.png"
        path.write_bytes(bridge.PNG_SIGNATURE + struct.pack(">I", 13) + b"IHDR" + b"\x00" * 13)
        with self.assertRaises(bridge.BridgeError):
            bridge.png_info(path)

    def test_png_payload_must_match_ihdr_scanlines(self) -> None:
        path = self.source / "empty-idat.png"
        ihdr = struct.pack(">IIBBBBB", 16, 12, 8, 6, 0, 0, 0)

        def chunk(kind: bytes, data: bytes) -> bytes:
            crc = zlib.crc32(kind)
            crc = zlib.crc32(data, crc) & 0xFFFFFFFF
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", crc)

        path.write_bytes(
            bridge.PNG_SIGNATURE
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(b""))
            + chunk(b"IEND", b"")
        )
        with self.assertRaises(bridge.BridgeError):
            bridge.png_info(path)

    def test_png_row_lengths_rejects_huge_ihdr_before_allocation(self) -> None:
        with self.assertRaises(bridge.BridgeError):
            bridge.png_row_lengths(1, 2**32 - 1, 8, 6, 0)

    def test_copy_rejects_source_bytes_that_do_not_match_digest(self) -> None:
        source = self.source / "sticker.png"
        target = self.root / "target" / "sticker.png"
        write_test_png(source)
        digest_before_mutation = bridge.sha256_file(source)
        source.write_bytes(source.read_bytes() + b"changed")
        with self.assertRaises(bridge.BridgeError):
            bridge.copy_atomically(
                source,
                target,
                digest_before_mutation,
                dry_run=False,
            )
        self.assertFalse(target.exists())

    def test_pull_is_hash_deduplicated_and_writes_manifest(self) -> None:
        write_test_png(self.account / "50" / "11" / "server-name.png")
        # A second path with identical bytes represents a duplicate cache entry.
        duplicate = self.account / "50" / "12" / "another-name.png"
        write_test_png(duplicate)
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "pull",
            ]
        )
        self.assertEqual(0, result)
        copied = list((self.bridge / "from_zalo").glob("*.png"))
        self.assertEqual(1, len(copied))
        manifest = json.loads((self.bridge / "sync-manifest.json").read_text())
        self.assertEqual(1, len(manifest["entries"]))
        self.assertEqual("zalo_to_bridge", next(iter(manifest["entries"].values()))["direction"])

    def test_live_zalo_target_requires_explicit_confirmation(self) -> None:
        write_test_png(self.source / "sticker.png")
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "push",
                str(self.source),
                "--zalo-cache",
            ]
        )
        self.assertEqual(2, result)
        self.assertFalse((self.zalo / "media" / "123" / "sticker" / "memevault-bridge").exists())

    def test_push_dry_run_does_not_write(self) -> None:
        write_test_png(self.source / "sticker.png")
        target = self.root / "outbox"
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "push",
                str(self.source),
                "--target",
                str(target),
                "--dry-run",
            ]
        )
        self.assertEqual(0, result)
        self.assertFalse(target.exists())

    def test_pull_dry_run_does_not_create_bridge_root(self) -> None:
        write_test_png(self.account / "50" / "11" / "sticker.png")
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "pull",
                "--dry-run",
            ]
        )
        self.assertEqual(0, result)
        self.assertFalse(self.bridge.exists())

    def test_dry_run_does_not_change_existing_bridge_mode(self) -> None:
        write_test_png(self.source / "sticker.png")
        self.bridge.mkdir()
        os.chmod(self.bridge, 0o755)
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "push",
                str(self.source),
                "--dry-run",
            ]
        )
        self.assertEqual(0, result)
        self.assertEqual(0o755, self.bridge.stat().st_mode & 0o777)

    def test_explicit_cache_apply_uses_isolated_bridge_folder(self) -> None:
        write_test_png(self.source / "sticker.png")
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "push",
                str(self.source),
                "--zalo-cache",
                "--apply",
                "--yes",
            ]
        )
        self.assertEqual(0, result)
        target = self.zalo / "media" / "123" / "sticker" / "memevault-bridge"
        copied = list(target.glob("*.png"))
        self.assertEqual(1, len(copied))
        self.assertTrue((self.bridge / "sync-manifest.json").is_file())

    def test_symlink_destination_is_rejected_before_copy(self) -> None:
        write_test_png(self.account / "50" / "11" / "sticker.png")
        outside = self.root / "outside"
        outside.mkdir()
        self.bridge.mkdir()
        (self.bridge / "from_zalo").symlink_to(outside, target_is_directory=True)
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "pull",
            ]
        )
        self.assertEqual(2, result)
        self.assertEqual([], list(outside.iterdir()))

    def test_symlink_sticker_root_is_not_selected(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        account = self.zalo / "media" / "456"
        account.mkdir(parents=True)
        (account / "sticker").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(bridge.BridgeError):
            bridge.select_account(self.zalo, "456")

    def test_target_inside_zalo_cannot_bypass_zalo_cache_flag(self) -> None:
        write_test_png(self.source / "sticker.png")
        target = self.zalo / "media" / "123" / "sticker" / "other"
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "push",
                str(self.source),
                "--target",
                str(target),
                "--apply",
                "--yes",
            ]
        )
        self.assertEqual(2, result)
        self.assertFalse(target.exists())

    def test_manifest_symlink_is_rejected(self) -> None:
        write_test_png(self.source / "sticker.png")
        self.bridge.mkdir()
        outside = self.root / "manifest-outside.json"
        outside.write_text('{"schema": 1, "entries": {}}')
        (self.bridge / "sync-manifest.json").symlink_to(outside)
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "push",
                str(self.source),
            ]
        )
        self.assertEqual(2, result)
        self.assertEqual('{"schema": 1, "entries": {}}', outside.read_text())

    def test_sync_runs_pull_then_push(self) -> None:
        write_test_png(self.source / "sticker.png")
        result = bridge.main(
            [
                "--zalo-root",
                str(self.zalo),
                "--bridge-dir",
                str(self.bridge),
                "sync",
                str(self.source),
            ]
        )
        self.assertEqual(0, result)
        self.assertTrue((self.bridge / "sync-manifest.json").is_file())
        self.assertEqual(1, len(list((self.bridge / "outbox").glob("*.png"))))


if __name__ == "__main__":
    unittest.main()
