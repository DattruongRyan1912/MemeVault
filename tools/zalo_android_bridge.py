#!/usr/bin/env python3
"""Experimental ADB bridge for inspecting/copying PNGs to Zalo Android.

This is a developer/lab tool, not an Android product capability.  It probes the
``photo_sticker`` locations that have been observed on Zalo Android and only
writes a user-selected remote directory after ``--apply --yes``.  It never
automates Zalo UI, edits its database, or claims that a copied PNG is a native
sticker.  Android scoped storage and Zalo's private app-data permissions may
make the private candidates inaccessible.
"""

from __future__ import annotations

import argparse
import json
import os
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from zalo_desktop_bridge import (
    BridgeError,
    copy_atomically,
    ensure_directory_below,
    iter_pngs,
    lexical,
    manifest_transaction,
    png_info,
    relative_to,
    require_directory,
    sha256_file,
    update_entry,
)


ZALO_PACKAGE = "com.zing.zalo"
DEFAULT_BRIDGE_ROOT = Path.home() / "Library" / "Application Support" / "MemeVaultZaloBridge" / "android"
MAX_REMOTE_PATH = 512

# Keep the private app-data candidates last: shell/ADB normally cannot read
# them on a non-rooted production phone.  The external paths are also only
# candidates; existence does not prove that Zalo consumes the files.
REMOTE_CANDIDATES = (
    f"/sdcard/Android/data/{ZALO_PACKAGE}/files/photo_sticker/recent_sticker",
    f"/storage/emulated/0/Android/data/{ZALO_PACKAGE}/files/photo_sticker/recent_sticker",
    f"/sdcard/Android/data/{ZALO_PACKAGE}/files/photo_sticker",
    f"/storage/emulated/0/Android/data/{ZALO_PACKAGE}/files/photo_sticker",
    "/sdcard/photo_sticker/recent_sticker",
    "/storage/emulated/0/photo_sticker/recent_sticker",
    f"/data/user/0/{ZALO_PACKAGE}/files/photo_sticker/recent_sticker",
    f"/data/user/0/{ZALO_PACKAGE}/files/photo_sticker",
    f"/data/data/{ZALO_PACKAGE}/files/photo_sticker/recent_sticker",
    f"/data/data/{ZALO_PACKAGE}/files/photo_sticker",
)
SHA256_RE = re.compile(r"^([0-9a-fA-F]{64})(?:\s|$)")
REMOTE_PATH_RE = re.compile(r"^/[A-Za-z0-9._/-]+$")


@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    detail: str


@dataclass(frozen=True)
class RemoteProbe:
    path: str
    exists: bool
    error: str | None = None


def parse_devices(output: str) -> list[Device]:
    devices: list[Device] = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 2 or fields[0] == "List":
            continue
        state = " ".join(fields[1:3]) if fields[1] == "no" and len(fields) > 2 else fields[1]
        if state not in {"device", "offline", "unauthorized", "no permissions"}:
            continue
        devices.append(Device(fields[0], state, " ".join(fields[2:])))
    return devices


def resolve_adb(binary: Path | None) -> str:
    candidate = os.fspath(binary) if binary else shutil.which("adb")
    if not candidate:
        raise BridgeError("Không tìm thấy adb trong PATH")
    path = Path(candidate).expanduser()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise BridgeError(f"adb không chạy được: {path}")
    return str(path)


def run_process(command: Sequence[str], *, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            list(command),
            text=True,
            capture_output=True,
            stdin=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as error:
        raise BridgeError(f"Không chạy được lệnh: {command[0]}") from error
    except subprocess.TimeoutExpired as error:
        raise BridgeError(f"adb timeout sau {timeout:.0f}s: {' '.join(command[:3])}") from error
    except OSError as error:
        raise BridgeError(f"Không chạy được adb: {error}") from error


def select_serial(adb: str, requested: str | None) -> str:
    result = run_process([adb, "devices", "-l"])
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise BridgeError(f"adb devices thất bại: {detail}")
    devices = parse_devices(result.stdout)
    if requested:
        selected = next((device for device in devices if device.serial == requested), None)
        if selected is None:
            raise BridgeError(f"Không tìm thấy thiết bị adb serial={requested}")
        if selected.state != "device":
            raise BridgeError(f"Thiết bị {requested} ở trạng thái {selected.state}; cần bật USB debugging")
        return selected.serial
    usable = [device for device in devices if device.state == "device"]
    if len(usable) == 1:
        return usable[0].serial
    if not usable:
        if devices:
            states = ", ".join(f"{device.serial}:{device.state}" for device in devices)
            raise BridgeError(f"Không có thiết bị adb sẵn sàng ({states})")
        raise BridgeError("Không có thiết bị adb; hãy mở USB debugging và xác nhận RSA trên điện thoại")
    raise BridgeError(
        "Có nhiều thiết bị adb; truyền --serial với một trong: "
        + ", ".join(device.serial for device in usable)
    )


@dataclass
class AdbClient:
    binary: str
    serial: str

    def run(self, args: Sequence[str], *, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
        return run_process([self.binary, "-s", self.serial, *args], timeout=timeout)

    def shell(self, args: Sequence[str], *, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
        return self.run(["shell", *args], timeout=timeout)

    def remote_type(self, path: str) -> str:
        if self.shell(["test", "-L", path]).returncode == 0:
            return "symlink"
        if self.shell(["test", "-d", path]).returncode == 0:
            return "dir"
        if self.shell(["test", "-f", path]).returncode == 0:
            return "file"
        if self.shell(["test", "-e", path]).returncode == 0:
            return "other"
        return "missing"

    def remote_sha256(self, path: str) -> str:
        result = self.shell(["sha256sum", path])
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise BridgeError(f"Không hash được file remote {path}: {detail}")
        match = SHA256_RE.match(result.stdout.strip())
        if not match:
            raise BridgeError(f"sha256sum remote trả kết quả không hợp lệ: {path}")
        return match.group(1).lower()


def validate_remote_path(value: str) -> str:
    path = value.strip()
    if (
        not path.startswith("/")
        or path.startswith("//")
        or len(path) > MAX_REMOTE_PATH
        or any(ord(char) < 0x20 for char in path)
        or not REMOTE_PATH_RE.fullmatch(path)
    ):
        raise BridgeError(f"remote-dir không hợp lệ: {value}")
    normalized = posixpath.normpath(path)
    if normalized != path.rstrip("/") and path != "/":
        raise BridgeError("remote-dir không được chứa . hoặc ..")
    if normalized in {"/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0", "/data"}:
        raise BridgeError(f"remote-dir quá rộng, từ chối: {normalized}")
    if ".." in normalized.split("/"):
        raise BridgeError("remote-dir không được chứa ..")
    components = set(normalized.strip("/").split("/"))
    if not components.intersection({"photo_sticker", "recent_sticker"}):
        raise BridgeError(
            "remote-dir phải thuộc nhánh photo_sticker/recent_sticker của Zalo"
        )
    return normalized


def remote_child(directory: str, name: str) -> str:
    if not name or "/" in name or name in {".", ".."} or any(ord(char) < 0x20 for char in name):
        raise BridgeError(f"Tên file remote không hợp lệ: {name}")
    return f"{directory.rstrip('/')}/{name}"


def probe_directory(client: AdbClient, path: str) -> RemoteProbe:
    path = validate_remote_path(path)
    remote_type = client.remote_type(path)
    if remote_type == "dir":
        return RemoteProbe(path, True)
    if remote_type == "symlink":
        detail = "symlink bị từ chối"
    else:
        detail = "không tồn tại hoặc không có quyền đọc"
    return RemoteProbe(path, False, detail)


def discover_remote_dirs(client: AdbClient, requested: str | None) -> list[RemoteProbe]:
    candidates = [validate_remote_path(requested)] if requested else list(REMOTE_CANDIDATES)
    return [probe_directory(client, path) for path in candidates]


def choose_remote_dir(client: AdbClient, requested: str | None) -> tuple[str, bool]:
    probes = discover_remote_dirs(client, requested)
    found = next((probe for probe in probes if probe.exists), None)
    if found:
        return found.path, True
    if requested:
        return probes[0].path, False
    raise BridgeError(
        "Không tìm thấy photo_sticker/recent_sticker hoặc adb không có quyền đọc. "
        "Dùng scan để xem candidate; nếu đã xác định path, truyền --remote-dir và --create-remote."
    )


def validate_local_root(path: Path, *, create: bool) -> Path:
    raw = lexical(path)
    if raw.is_symlink():
        raise BridgeError(f"bridge-dir không được là symlink: {raw}")
    root = raw.resolve(strict=False)
    created = False
    if root.exists():
        if not root.is_dir():
            raise BridgeError(f"bridge-dir không phải thư mục: {root}")
    elif create:
        try:
            root.mkdir(parents=True, exist_ok=True)
            created = True
        except OSError as error:
            raise BridgeError(f"Không tạo được bridge-dir: {root}") from error
    if created:
        try:
            os.chmod(root, 0o700)
        except OSError as error:
            raise BridgeError(f"Không đặt quyền bridge-dir: {root}") from error
    if raw.is_symlink():
        raise BridgeError(f"bridge-dir trở thành symlink trong lúc tạo: {raw}")
    return root


def package_is_installed(client: AdbClient) -> bool:
    result = client.shell(["pm", "path", ZALO_PACKAGE])
    return result.returncode == 0 and ZALO_PACKAGE in result.stdout


def scan_command(args: argparse.Namespace, client: AdbClient) -> int:
    if not package_is_installed(client):
        raise BridgeError(f"Không thấy package {ZALO_PACKAGE} trên thiết bị")
    probes = discover_remote_dirs(client, args.remote_dir)
    if args.json:
        print(
            json.dumps(
                {
                    "serial": client.serial,
                    "package": ZALO_PACKAGE,
                    "candidates": [
                        {"path": probe.path, "exists": probe.exists, "error": probe.error}
                        for probe in probes
                    ],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        print(f"Thiết bị: {client.serial}")
        print(f"Package: {ZALO_PACKAGE} (đã cài)")
        for probe in probes:
            print(f"{'FOUND' if probe.exists else 'MISS'}  {probe.path}")
    return 0 if any(probe.exists for probe in probes) else 2


def ensure_remote_dir(client: AdbClient, remote_dir: str, exists: bool, *, create: bool, dry_run: bool) -> bool:
    if exists:
        return True
    if dry_run:
        return False
    if not create:
        raise BridgeError(
            f"Remote directory chưa tồn tại/không truy cập được: {remote_dir}; "
            "chỉ tạo khi truyền --create-remote --apply --yes"
        )
    result = client.shell(["mkdir", "-p", remote_dir])
    if result.returncode != 0 or client.remote_type(remote_dir) != "dir":
        detail = (result.stderr or result.stdout).strip()
        raise BridgeError(f"Không tạo/truy cập được remote-dir {remote_dir}: {detail}")
    return True


def pull_command(args: argparse.Namespace, client: AdbClient) -> int:
    if not package_is_installed(client):
        raise BridgeError(f"Không thấy package {ZALO_PACKAGE} trên thiết bị")
    remote_dir, remote_exists = choose_remote_dir(client, args.remote_dir)
    if not remote_exists:
        raise BridgeError(f"Không thể pull từ remote-dir chưa tồn tại/quyền bị từ chối: {remote_dir}")
    bridge_root = validate_local_root(args.bridge_dir, create=not args.dry_run)
    if args.dry_run:
        print(f"pull: remote={remote_dir} exists={remote_exists} dry_run=True")
        return 0 if remote_exists else 2
    destination = ensure_directory_below(
        bridge_root,
        bridge_root / "from_zalo_android",
        create=True,
    )
    manifest_path = bridge_root / "android-sync-manifest.json"
    copied = existing = invalid = 0
    with tempfile.TemporaryDirectory(prefix="memevault-zalo-pull-") as temporary:
        staging = Path(temporary) / "remote"
        staging.mkdir(mode=0o700)
        result = client.run(["pull", remote_dir, str(staging)], timeout=180)
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise BridgeError(f"adb pull thất bại từ {remote_dir}: {detail}")
        with manifest_transaction(bridge_root, manifest_path, dry_run=False) as manifest:
            for path in iter_pngs(staging):
                try:
                    info = png_info(path)
                    digest = sha256_file(path)
                    target = destination / f"{digest}.png"
                    status = copy_atomically(path, target, digest, False, safe_root=bridge_root)
                except BridgeError as error:
                    invalid += 1
                    print(f"SKIP: {error}", file=sys.stderr)
                    continue
                if status == "copied":
                    copied += 1
                elif status == "existing":
                    existing += 1
                update_entry(
                    manifest,
                    digest,
                    direction="android_zalo_to_bridge",
                    source_relative=f"{remote_dir}/{relative_to(path, staging)}",
                    target_relative=relative_to(target, bridge_root),
                    info=info,
                    status=status,
                )
    print(
        f"pull: copied={copied} existing={existing} skipped={invalid} "
        f"remote={remote_dir} destination={destination} dry_run=False"
    )
    return 0 if invalid == 0 else 2


def cleanup_remote_temp(client: AdbClient, path: str) -> None:
    if client.remote_type(path) == "file":
        client.shell(["rm", "-f", path])


def push_one(client: AdbClient, source: Path, remote_target: str, digest: str) -> str:
    target_type = client.remote_type(remote_target)
    if target_type == "symlink":
        raise BridgeError(f"Đích remote là symlink: {remote_target}")
    if target_type == "file":
        if client.remote_sha256(remote_target) == digest:
            return "existing"
        raise BridgeError(f"Đích remote cùng tên nhưng khác hash, không ghi đè: {remote_target}")
    if target_type != "missing":
        raise BridgeError(f"Đích remote không phải file thường: {remote_target}")

    parent = remote_target.rsplit("/", 1)[0]
    temporary = remote_child(parent, f".memevault-{digest}-{uuid.uuid4().hex}.part")
    try:
        result = client.run(["push", str(source), temporary], timeout=180)
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            raise BridgeError(f"adb push thất bại tới {temporary}: {detail}")
        if client.remote_sha256(temporary) != digest:
            raise BridgeError(f"Hash remote file tạm không khớp: {temporary}")

        target_type = client.remote_type(remote_target)
        if target_type == "symlink":
            raise BridgeError(f"Đích remote xuất hiện dưới dạng symlink: {remote_target}")
        if target_type == "file":
            if client.remote_sha256(remote_target) == digest:
                return "existing"
            raise BridgeError(f"Đích remote xuất hiện nhưng khác hash: {remote_target}")
        if target_type != "missing":
            raise BridgeError(f"Đích remote xuất hiện nhưng không phải file thường: {remote_target}")

        # -i refuses an overwrite if another process creates the hash-named
        # target between the check above and this publish step.
        publish = client.shell(["mv", "-i", temporary, remote_target])
        if publish.returncode != 0 and client.remote_type(remote_target) == "missing":
            detail = (publish.stderr or publish.stdout).strip()
            raise BridgeError(f"Không publish được remote file: {detail}")
        final_type = client.remote_type(remote_target)
        if final_type != "file":
            raise BridgeError(f"Remote file không tồn tại sau publish: {remote_target}")
        if client.remote_sha256(remote_target) != digest:
            raise BridgeError(f"Hash remote file sau publish không khớp: {remote_target}")
        return "copied"
    finally:
        cleanup_remote_temp(client, temporary)


def push_command(args: argparse.Namespace, client: AdbClient) -> int:
    if not package_is_installed(client):
        raise BridgeError(f"Không thấy package {ZALO_PACKAGE} trên thiết bị")
    source_root = require_directory(args.source, "Thư mục sticker nguồn")
    bridge_root = validate_local_root(args.bridge_dir, create=not args.dry_run)
    remote_dir, remote_exists = choose_remote_dir(client, args.remote_dir)
    if not args.dry_run and not (args.apply and args.yes):
        raise BridgeError("Ghi vào điện thoại cần truyền đồng thời --apply --yes")
    ensure_remote_dir(
        client,
        remote_dir,
        remote_exists,
        create=args.create_remote,
        dry_run=args.dry_run,
    )
    manifest_path = bridge_root / "android-sync-manifest.json"
    copied = existing = invalid = 0
    with manifest_transaction(bridge_root, manifest_path, dry_run=args.dry_run) as manifest:
        for path in iter_pngs(source_root):
            try:
                info = png_info(path)
                digest = sha256_file(path)
                remote_target = remote_child(remote_dir, f"{digest}.png")
                if args.dry_run:
                    status = "would_copy"
                else:
                    status = push_one(client, path, remote_target, digest)
            except BridgeError as error:
                invalid += 1
                print(f"SKIP: {error}", file=sys.stderr)
                continue
            if status == "copied":
                copied += 1
            elif status == "existing":
                existing += 1
            update_entry(
                manifest,
                digest,
                direction="bridge_to_android_zalo",
                source_relative=relative_to(path, source_root),
                target_relative=remote_target,
                info=info,
                status=status,
            )
    print(
        f"push: copied={copied} existing={existing} skipped={invalid} "
        f"remote={remote_dir} dry_run={args.dry_run}"
    )
    return 0 if invalid == 0 else 2


def sync_command(args: argparse.Namespace, client: AdbClient) -> int:
    pull_result = pull_command(
        argparse.Namespace(
            bridge_dir=args.bridge_dir,
            dry_run=args.dry_run,
            remote_dir=args.remote_dir,
        ),
        client,
    )
    push_result = push_command(args, client)
    return max(pull_result, push_result)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, help="Đường dẫn adb nếu không nằm trong PATH")
    parser.add_argument("--serial", help="ADB serial khi có nhiều thiết bị")
    parser.add_argument("--bridge-dir", type=Path, default=DEFAULT_BRIDGE_ROOT)
    subparsers = parser.add_subparsers(dest="command", required=True)

    scan = subparsers.add_parser("scan", help="Quét candidate photo_sticker, chỉ đọc thiết bị")
    scan.add_argument("--remote-dir", help="Chỉ probe một thư mục remote cụ thể")
    scan.add_argument("--json", action="store_true")
    scan.set_defaults(handler=scan_command)

    pull = subparsers.add_parser("pull", help="Kéo PNG từ Zalo Android về bridge")
    pull.add_argument("--remote-dir")
    pull.add_argument("--dry-run", action="store_true")
    pull.set_defaults(handler=pull_command)

    push = subparsers.add_parser("push", help="Đẩy PNG vào photo_sticker trên Zalo Android")
    push.add_argument("source", type=Path)
    push.add_argument("--remote-dir")
    push.add_argument("--create-remote", action="store_true")
    push.add_argument("--apply", action="store_true")
    push.add_argument("--yes", action="store_true")
    push.add_argument("--dry-run", action="store_true")
    push.set_defaults(handler=push_command)

    sync = subparsers.add_parser("sync", help="Pull từ Zalo rồi push thư mục nguồn")
    sync.add_argument("source", type=Path)
    sync.add_argument("--remote-dir")
    sync.add_argument("--create-remote", action="store_true")
    sync.add_argument("--apply", action="store_true")
    sync.add_argument("--yes", action="store_true")
    sync.add_argument("--dry-run", action="store_true")
    sync.set_defaults(handler=sync_command)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        adb = resolve_adb(args.adb)
        serial = select_serial(adb, args.serial)
        client = AdbClient(adb, serial)
        return args.handler(args, client)
    except BridgeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Đã hủy", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
