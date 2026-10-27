import os
import tempfile
import unittest
from pathlib import Path


import zalo_android_bridge as bridge


class ZaloAndroidBridgeTest(unittest.TestCase):
    def test_parse_devices_keeps_only_serial_and_state(self) -> None:
        devices = bridge.parse_devices(
            "List of devices attached\n"
            "ABC123\tdevice product:rmx3800 model:RMX3800\n"
            "OFFLINE\toffline\n"
            "NOPE\tno permissions usb:1-1\n"
        )
        self.assertEqual(["ABC123", "OFFLINE", "NOPE"], [device.serial for device in devices])
        self.assertEqual(["device", "offline", "no permissions"], [device.state for device in devices])

    def test_remote_path_is_absolute_and_cannot_escape(self) -> None:
        self.assertEqual(
            "/sdcard/photo_sticker/recent_sticker",
            bridge.validate_remote_path("/sdcard/photo_sticker/recent_sticker/"),
        )
        for path in (
            "relative/path",
            "//sdcard/photo_sticker",
            "//storage/emulated/0/photo_sticker",
            "//data/photo_sticker",
            "/sdcard/../data",
            "/sdcard/./photo_sticker",
            "/sdcard",
            "/sdcard/DCIM",
            "/sdcard/photo_sticker;rm",
        ):
            with self.subTest(path=path), self.assertRaises(bridge.BridgeError):
                bridge.validate_remote_path(path)
        self.assertEqual(
            "/sdcard/Android/data/com.zing.zalo/files/photo_sticker/recent_sticker",
            bridge.validate_remote_path(
                "/sdcard/Android/data/com.zing.zalo/files/photo_sticker/recent_sticker"
            ),
        )

    def test_remote_child_rejects_path_injection(self) -> None:
        with self.assertRaises(bridge.BridgeError):
            bridge.remote_child("/sdcard/photo_sticker", "../escape.png")
        with self.assertRaises(bridge.BridgeError):
            bridge.remote_child("/sdcard/photo_sticker", "name\nrm")

    def test_local_dry_run_does_not_create_or_change_bridge_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "missing"
            self.assertFalse(root.exists())
            bridge.validate_local_root(root, create=False)
            self.assertFalse(root.exists())

            root.mkdir()
            os.chmod(root, 0o755)
            bridge.validate_local_root(root, create=False)
            self.assertEqual(0o755, root.stat().st_mode & 0o777)

    def test_choose_remote_dir_prefers_first_existing_candidate(self) -> None:
        class FakeClient:
            def remote_type(self, path: str) -> str:
                return "dir" if path == bridge.REMOTE_CANDIDATES[2] else "missing"

        remote, exists = bridge.choose_remote_dir(FakeClient(), None)
        self.assertEqual(bridge.REMOTE_CANDIDATES[2], remote)
        self.assertTrue(exists)

    def test_missing_explicit_remote_is_returned_for_push_creation(self) -> None:
        class FakeClient:
            def remote_type(self, path: str) -> str:
                return "missing"

        remote, exists = bridge.choose_remote_dir(FakeClient(), "/sdcard/photo_sticker")
        self.assertEqual("/sdcard/photo_sticker", remote)
        self.assertFalse(exists)


if __name__ == "__main__":
    unittest.main()
