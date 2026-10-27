#!/usr/bin/env python3
"""Experimental, local-only bridge for Zalo Desktop sticker media on macOS.

The bridge copies PNG bytes and records a hash manifest. It deliberately does not
touch Zalo's databases, does not delete files, and never writes the live Zalo cache
unless the caller explicitly opts in with ``--zalo-cache --apply --yes``.

The observed Zalo Desktop media layout is::

    ~/Library/Application Support/ZaloData/media/<account-id>/sticker/<pack>/<id>/<hash>.png

That layout is a cache format, not a documented sticker-installation contract. A
successful file copy therefore must not be interpreted as a native-sticker result.
"""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import zlib
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_PNG_BYTES = 32 * 1024 * 1024
# Zalo Desktop keeps some animated/sprite sticker strips wider than 4096px
# (for example 11960x130). Preserve those bytes instead of silently dropping them.
MAX_PNG_DIMENSION = 16384
MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024
MAX_FILES = 20_000
MANIFEST_SCHEMA = 1


class BridgeError(RuntimeError):
    """A recoverable validation or command-line error."""


@dataclass(frozen=True)
class PngInfo:
    width: int
    height: int
    has_alpha: bool


PNG_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
ADAM7_PASSES = (
    (0, 0, 8, 8),
    (4, 0, 8, 8),
    (0, 4, 4, 8),
    (2, 0, 4, 4),
    (0, 2, 2, 4),
    (1, 0, 2, 2),
    (0, 1, 1, 2),
)


class ScanlineValidator:
    """Bound and validate PNG's decompressed filter/scanline payload."""

    def __init__(self, row_lengths: list[int]) -> None:
        self._row_lengths = row_lengths
        self._row_index = 0
        self._row_offset = 0
        self.total = 0

    def feed(self, payload: bytes) -> None:
        offset = 0
        while offset < len(payload):
            if self._row_index >= len(self._row_lengths):
                raise BridgeError("IDAT PNG có quá nhiều scanline")
            row_length = self._row_lengths[self._row_index]
            if self._row_offset == 0 and payload[offset] > 4:
                raise BridgeError("PNG có filter byte không hợp lệ")
            take = min(row_length - self._row_offset, len(payload) - offset)
            self._row_offset += take
            self.total += take
            offset += take
            if self.total > MAX_DECOMPRESSED_BYTES:
                raise BridgeError("Payload PNG giải nén vượt giới hạn")
            if self._row_offset == row_length:
                self._row_index += 1
                self._row_offset = 0

    def finish(self) -> None:
        if self._row_index != len(self._row_lengths) or self._row_offset != 0:
            raise BridgeError("IDAT PNG thiếu scanline theo IHDR")


def png_row_lengths(
    width: int,
    height: int,
    bit_depth: int,
    color_type: int,
    interlace: int,
) -> list[int]:
    # Validate dimensions before constructing a row list. IHDR is untrusted;
    # rejecting a huge height here prevents an attacker-controlled allocation.
    if not 0 < width <= MAX_PNG_DIMENSION or not 0 < height <= MAX_PNG_DIMENSION:
        raise BridgeError("Kích thước PNG không hợp lệ")
    channels = PNG_CHANNELS[color_type]

    def pass_size(start: int, extent: int, step: int) -> int:
        if extent <= start:
            return 0
        return (extent - start + step - 1) // step

    passes = ((0, 0, 1, 1),) if interlace == 0 else ADAM7_PASSES
    row_specs: list[tuple[int, int]] = []
    total = 0
    for x_start, y_start, x_step, y_step in passes:
        pass_width = pass_size(x_start, width, x_step)
        pass_height = pass_size(y_start, height, y_step)
        if pass_width == 0 or pass_height == 0:
            continue
        row_bytes = (pass_width * channels * bit_depth + 7) // 8
        row_length = 1 + row_bytes
        pass_total = row_length * pass_height
        if pass_total > MAX_DECOMPRESSED_BYTES - total:
            raise BridgeError("Payload PNG theo IHDR vượt giới hạn")
        total += pass_total
        row_specs.append((row_length, pass_height))
    if total <= 0 or total > MAX_DECOMPRESSED_BYTES:
        raise BridgeError("Payload PNG theo IHDR vượt giới hạn")
    rows: list[int] = []
    for row_length, pass_height in row_specs:
        rows.extend([row_length] * pass_height)
    return rows


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def default_zalo_root() -> Path:
    return Path.home() / "Library" / "Application Support" / "ZaloData"


def default_bridge_root() -> Path:
    return Path.home() / "Library" / "Application Support" / "MemeVaultZaloBridge"


def resolved(path: Path) -> Path:
    return Path(os.path.abspath(os.path.expanduser(os.fspath(path)))).resolve(strict=False)


def lexical(path: Path) -> Path:
    """Expand a path without resolving symlinks."""

    return Path(os.path.abspath(os.path.expanduser(os.fspath(path))))


def relative_to(path: Path, root: Path) -> str:
    try:
        return path.resolve(strict=False).relative_to(root.resolve(strict=False)).as_posix()
    except ValueError as error:
        raise BridgeError(f"Đường dẫn nằm ngoài thư mục gốc: {path}") from error


def require_directory(path: Path, label: str) -> Path:
    raw = lexical(path)
    try:
        if raw.is_symlink() or not raw.is_dir():
            raise BridgeError(f"{label} không phải thư mục hợp lệ: {raw}")
        return raw.resolve(strict=True)
    except OSError as error:
        raise BridgeError(f"Không truy cập được {label}: {raw}") from error


def ensure_directory_below(root: Path, path: Path, *, create: bool) -> Path:
    """Ensure a directory path has no symlink component below a trusted root."""

    root = require_directory(root, "Thư mục gốc an toàn")
    raw = lexical(path)
    try:
        parts = raw.relative_to(root).parts
    except ValueError as error:
        raise BridgeError(f"Đường dẫn nằm ngoài thư mục gốc an toàn: {raw}") from error
    current = root
    for part in parts:
        current = current / part
        if os.path.lexists(current):
            if current.is_symlink():
                raise BridgeError(f"Từ chối symlink trong đường dẫn: {current}")
            if not current.is_dir():
                raise BridgeError(f"Đường dẫn không phải thư mục: {current}")
        elif create:
            try:
                current.mkdir(mode=0o700)
                os.chmod(current, 0o700)
            except FileExistsError:
                if current.is_symlink() or not current.is_dir():
                    raise BridgeError(f"Đường dẫn tạo đồng thời không an toàn: {current}")
            except OSError as error:
                raise BridgeError(f"Không tạo được thư mục bridge: {current}") from error
    return current


def ensure_file_below(root: Path, path: Path, *, create_parent: bool) -> Path:
    """Validate a file path and all existing parent components below root."""

    root = require_directory(root, "Thư mục gốc an toàn")
    raw = lexical(path)
    parent = ensure_directory_below(root, raw.parent, create=create_parent)
    if os.path.lexists(raw):
        if raw.is_symlink():
            raise BridgeError(f"Từ chối symlink file: {raw}")
        if not raw.is_file():
            raise BridgeError(f"Đích không phải file thường: {raw}")
    if parent != raw.parent:
        raise BridgeError(f"Không xác thực được parent của file: {raw}")
    return raw


def png_info(path: Path) -> PngInfo:
    """Validate PNG chunks, CRCs and IHDR without decoding image pixels."""

    try:
        if path.is_symlink() or not path.is_file():
            raise BridgeError(f"Bỏ qua file không thường: {path}")
        size = path.stat().st_size
    except OSError as error:
        raise BridgeError(f"Không truy cập được PNG: {path}") from error
    if size <= 0 or size > MAX_PNG_BYTES:
        raise BridgeError(f"PNG vượt giới hạn dung lượng: {path}")
    try:
        with path.open("rb") as stream:
            if stream.read(8) != PNG_SIGNATURE:
                raise BridgeError(f"Không phải PNG hợp lệ: {path}")
            ihdr = None
            width = height = bit_depth = color_type = None
            seen_idat = False
            seen_iend = False
            palette_seen = False
            transparency_seen = False
            idat_decoder = zlib.decompressobj()
            idat_stream_done = False
            scanlines: ScanlineValidator | None = None
            while True:
                length_bytes = stream.read(4)
                if len(length_bytes) != 4:
                    raise BridgeError(f"PNG thiếu IEND: {path}")
                length = struct.unpack(">I", length_bytes)[0]
                chunk_type = stream.read(4)
                if len(chunk_type) != 4:
                    raise BridgeError(f"PNG bị cắt ở tên chunk: {path}")
                if length > MAX_PNG_BYTES or stream.tell() + length + 4 > size:
                    raise BridgeError(f"Chunk PNG vượt giới hạn hoặc bị cắt: {path}")
                data = stream.read(length)
                crc_bytes = stream.read(4)
                if len(data) != length or len(crc_bytes) != 4:
                    raise BridgeError(f"PNG bị cắt trong chunk: {path}")
                expected_crc = struct.unpack(">I", crc_bytes)[0]
                actual_crc = zlib.crc32(chunk_type)
                actual_crc = zlib.crc32(data, actual_crc) & 0xFFFFFFFF
                if actual_crc != expected_crc:
                    raise BridgeError(f"CRC PNG không hợp lệ: {path}")
                if ihdr is None and chunk_type != b"IHDR":
                    raise BridgeError(f"PNG không bắt đầu bằng IHDR: {path}")
                if chunk_type == b"IHDR":
                    if ihdr is not None or length != 13:
                        raise BridgeError(f"PNG có IHDR không hợp lệ: {path}")
                    ihdr = data
                    width, height, bit_depth, color_type, compression, filt, interlace = struct.unpack(
                        ">IIBBBBB", data
                    )
                    if compression != 0 or filt != 0 or interlace not in (0, 1):
                        raise BridgeError(f"PNG dùng thông số nén/interlace không hỗ trợ: {path}")
                    if width <= 0 or height <= 0:
                        raise BridgeError(f"PNG có kích thước rỗng: {path}")
                    legal_bit_depths = {
                        0: (1, 2, 4, 8, 16),
                        2: (8, 16),
                        3: (1, 2, 4, 8),
                        4: (8, 16),
                        6: (8, 16),
                    }
                    if color_type not in legal_bit_depths or bit_depth not in legal_bit_depths[color_type]:
                        raise BridgeError(f"PNG dùng kiểu màu không hỗ trợ: {path}")
                    scanlines = ScanlineValidator(
                        png_row_lengths(width, height, bit_depth, color_type, interlace)
                    )
                elif chunk_type == b"PLTE":
                    palette_seen = True
                elif chunk_type == b"tRNS":
                    transparency_seen = True
                elif chunk_type == b"IDAT":
                    if idat_stream_done:
                        raise BridgeError(f"PNG có IDAT sau khi stream kết thúc: {path}")
                    pending = data
                    while pending:
                        try:
                            decoded = idat_decoder.decompress(pending, 1024 * 1024)
                        except zlib.error as error:
                            raise BridgeError(f"IDAT PNG không giải nén được: {path}") from error
                        if scanlines is None:
                            raise BridgeError(f"IDAT PNG xuất hiện trước IHDR: {path}")
                        scanlines.feed(decoded)
                        if idat_decoder.unused_data:
                            raise BridgeError(f"IDAT PNG có dữ liệu dư: {path}")
                        pending = idat_decoder.unconsumed_tail
                        if idat_decoder.eof:
                            idat_stream_done = True
                            break
                    seen_idat = True
                elif chunk_type == b"IEND":
                    if length != 0:
                        raise BridgeError(f"PNG có IEND không rỗng: {path}")
                    seen_iend = True
                    if stream.read(1) != b"":
                        raise BridgeError(f"PNG có dữ liệu sau IEND: {path}")
                    break
                elif chunk_type and chunk_type[0] & 0x20 == 0:
                    raise BridgeError(f"PNG có critical chunk không hỗ trợ: {path}")
            if ihdr is None or not seen_idat or not seen_iend:
                raise BridgeError(f"PNG thiếu IHDR/IDAT/IEND: {path}")
            if not idat_decoder.eof:
                raise BridgeError(f"IDAT PNG chưa kết thúc: {path}")
            if scanlines is None:
                raise BridgeError(f"PNG thiếu cấu trúc scanline: {path}")
            try:
                scanlines.feed(idat_decoder.flush())
            except zlib.error as error:
                raise BridgeError(f"IDAT PNG flush thất bại: {path}") from error
            scanlines.finish()
    except OSError as error:
        raise BridgeError(f"Không đọc được PNG: {path}") from error
    assert width is not None and height is not None
    assert bit_depth is not None and color_type is not None
    if not 0 < width <= MAX_PNG_DIMENSION or not 0 < height <= MAX_PNG_DIMENSION:
        raise BridgeError(f"Kích thước PNG không hợp lệ: {path}")
    if color_type == 3 and not palette_seen:
        raise BridgeError(f"PNG palette thiếu PLTE: {path}")
    return PngInfo(
        width=width,
        height=height,
        has_alpha=color_type in (4, 6) or transparency_seen,
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        raise BridgeError(f"Không đọc được file để hash: {path}") from error
    return digest.hexdigest()


def iter_pngs(root: Path) -> Iterable[Path]:
    root = require_directory(root, "Thư mục nguồn")
    count = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink() or path.suffix.lower() != ".png":
            continue
        count += 1
        if count > MAX_FILES:
            raise BridgeError(f"Nguồn vượt giới hạn {MAX_FILES} file PNG")
        yield path


def detect_accounts(zalo_root: Path) -> list[Path]:
    media_root = ensure_directory_below(
        require_directory(zalo_root, "ZaloData"),
        resolved(zalo_root) / "media",
        create=False,
    )
    if not media_root.is_dir():
        raise BridgeError(f"Không tìm thấy media của Zalo Desktop: {media_root}")
    accounts = []
    for child in sorted(media_root.iterdir()):
        sticker = child / "sticker"
        if (
            child.is_dir()
            and not child.is_symlink()
            and sticker.is_dir()
            and not sticker.is_symlink()
        ):
            accounts.append(child)
    return accounts


def select_account(zalo_root: Path, account_id: str | None) -> Path:
    accounts = detect_accounts(zalo_root)
    if account_id:
        selected = resolved(zalo_root) / "media" / account_id
        if selected not in accounts:
            available = ", ".join(path.name for path in accounts) or "(không có)"
            raise BridgeError(f"Không tìm thấy account {account_id}; hiện có: {available}")
        return selected
    if len(accounts) == 1:
        return accounts[0]
    if not accounts:
        raise BridgeError("Zalo Desktop chưa có thư mục account chứa sticker")
    raise BridgeError(
        "Có nhiều account Zalo; truyền --account-id với một trong: "
        + ", ".join(path.name for path in accounts)
    )


def load_manifest(path: Path) -> dict[str, Any]:
    if os.path.lexists(path) and path.is_symlink():
        raise BridgeError(f"Từ chối symlink manifest: {path}")
    if not path.exists():
        return {"schema": MANIFEST_SCHEMA, "updated_at": now_iso(), "entries": {}}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise BridgeError(f"Không đọc được manifest: {path}") from error
    if not isinstance(raw, dict) or raw.get("schema") != MANIFEST_SCHEMA:
        raise BridgeError(f"Manifest không đúng schema {MANIFEST_SCHEMA}: {path}")
    entries = raw.get("entries")
    if not isinstance(entries, dict):
        raise BridgeError(f"Manifest thiếu entries: {path}")
    return raw


def save_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path = lexical(path)
    if os.path.lexists(path) and path.is_symlink():
        raise BridgeError(f"Từ chối symlink manifest: {path}")
    manifest["schema"] = MANIFEST_SCHEMA
    manifest["updated_at"] = now_iso()
    temporary_name = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".part", dir=str(path.parent)
        )
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(manifest, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
    except OSError as error:
        raise BridgeError(f"Không ghi được manifest: {path}") from error
    finally:
        if temporary_name and os.path.exists(temporary_name):
            os.unlink(temporary_name)


def copy_atomically(
    source: Path,
    target: Path,
    digest: str,
    dry_run: bool,
    *,
    safe_root: Path | None = None,
) -> str:
    target = lexical(target)
    if safe_root is not None:
        target = ensure_file_below(
            require_directory(safe_root, "Thư mục đích an toàn"),
            target,
            create_parent=not dry_run,
        )
    if os.path.lexists(target):
        if target.is_symlink() or not target.is_file():
            raise BridgeError(f"Đích đã tồn tại nhưng không phải file thường: {target}")
        if sha256_file(target) == digest:
            return "existing"
        raise BridgeError(f"Đích cùng tên nhưng khác hash, không ghi đè: {target}")
    if dry_run:
        return "would_copy"
    if safe_root is None and not dry_run:
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise BridgeError(f"Không tạo được thư mục đích: {target.parent}") from error
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{target.name}.",
            suffix=".part",
            dir=str(target.parent),
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            try:
                with source.open("rb") as input_stream:
                    shutil.copyfileobj(input_stream, temporary, length=1024 * 1024)
            except OSError as error:
                raise BridgeError(f"Không đọc được file nguồn: {source}") from error
            temporary.flush()
            os.fsync(temporary.fileno())
        os.chmod(temporary_name, 0o600)
        temporary_path = Path(temporary_name)
        if sha256_file(temporary_path) != digest:
            raise BridgeError(f"Hash file tạm không khớp trước khi publish: {source}")
        try:
            # Hard-link publication is create-once on the same filesystem; unlike
            # os.replace it cannot overwrite a target that appeared concurrently.
            os.link(temporary_name, target)
        except FileExistsError:
            if target.is_symlink() or not target.is_file():
                raise BridgeError(f"Đích xuất hiện nhưng không phải file thường: {target}")
            if sha256_file(target) == digest:
                return "existing"
            raise BridgeError(f"Đích cùng tên nhưng khác hash, không ghi đè: {target}")
        if sha256_file(target) != digest:
            try:
                target.unlink(missing_ok=True)
            except OSError:
                pass
            raise BridgeError(f"Hash đích không khớp sau khi copy: {target}")
        return "copied"
    except OSError as error:
        raise BridgeError(f"Không copy được file tới: {target}") from error
    finally:
        if temporary_name and os.path.exists(temporary_name):
            os.unlink(temporary_name)


def is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve(strict=False).relative_to(root.resolve(strict=False))
        return True
    except ValueError:
        return False


def validate_bridge_root(bridge_root: Path, zalo_root: Path, *, create: bool) -> Path:
    raw = lexical(bridge_root)
    if raw.is_symlink():
        raise BridgeError(f"bridge-dir không được là symlink: {raw}")
    bridge_root = raw.resolve(strict=False)
    if is_within(bridge_root, zalo_root):
        raise BridgeError("bridge-dir không được nằm trong ZaloData")
    created = False
    if bridge_root.exists():
        if not bridge_root.is_dir():
            raise BridgeError(f"bridge-dir không phải thư mục: {bridge_root}")
    elif create:
        try:
            bridge_root.mkdir(parents=True, exist_ok=True)
            created = True
        except OSError as error:
            raise BridgeError(f"Không tạo được bridge-dir: {bridge_root}") from error
    if created:
        try:
            os.chmod(bridge_root, 0o700)
        except OSError as error:
            raise BridgeError(f"Không đặt quyền bridge-dir: {bridge_root}") from error
    if raw.is_symlink():
        raise BridgeError(f"bridge-dir trở thành symlink trong lúc tạo: {raw}")
    return bridge_root


@contextlib.contextmanager
def manifest_lock(bridge_root: Path) -> Iterator[None]:
    """Serialize manifest read/update/write across bridge processes."""

    lock_path = ensure_file_below(
        bridge_root,
        bridge_root / "sync-manifest.lock",
        create_parent=True,
    )
    flags = os.O_RDWR | os.O_CREAT
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(lock_path, flags, 0o600)
    except OSError as error:
        raise BridgeError(f"Không mở được manifest lock: {lock_path}") from error
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "r+", encoding="utf-8") as stream:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
    except Exception:
        # fd ownership transfers to fdopen only after it succeeds.
        try:
            os.close(fd)
        except OSError:
            pass
        raise


@contextlib.contextmanager
def manifest_transaction(
    bridge_root: Path,
    manifest_path: Path,
    *,
    dry_run: bool,
) -> Iterator[dict[str, Any]]:
    """Load/update/save the manifest under one process lock; dry-run stays read-only."""

    if dry_run:
        yield {"schema": MANIFEST_SCHEMA, "updated_at": now_iso(), "entries": {}}
        return
    with manifest_lock(bridge_root):
        manifest = load_manifest(manifest_path)
        yield manifest
        save_manifest(manifest_path, manifest)


def update_entry(
    manifest: dict[str, Any],
    digest: str,
    *,
    direction: str,
    source_relative: str,
    target_relative: str,
    info: PngInfo,
    status: str,
) -> None:
    entries = manifest.setdefault("entries", {})
    entry = entries.setdefault(digest, {})
    entry.update(
        {
            "last_seen_at": now_iso(),
            "direction": direction,
            "source_relative": source_relative,
            "target_relative": target_relative,
            "width": info.width,
            "height": info.height,
            "has_alpha": info.has_alpha,
            "status": status,
        }
    )


def scan_command(args: argparse.Namespace) -> int:
    zalo_root = require_directory(args.zalo_root, "ZaloData")
    account = select_account(zalo_root, args.account_id)
    sticker_root = account / "sticker"
    files = []
    invalid = []
    total_bytes = 0
    for path in iter_pngs(sticker_root):
        try:
            info = png_info(path)
            digest = sha256_file(path)
        except BridgeError as error:
            invalid.append(str(error))
            continue
        total_bytes += path.stat().st_size
        files.append(
            {
                "relative_path": relative_to(path, sticker_root),
                "sha256": digest,
                "width": info.width,
                "height": info.height,
                "has_alpha": info.has_alpha,
                "bytes": path.stat().st_size,
            }
        )
    result = {
        "zalo_root": str(zalo_root),
        "account_id": account.name,
        "sticker_root": str(sticker_root),
        "files": len(files),
        "bytes": total_bytes,
        "invalid_files": len(invalid),
    }
    if args.json:
        result["items"] = files
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"ZaloData: {zalo_root}")
        print(f"Account: {account.name}")
        print(f"Sticker PNG: {len(files)} file, {total_bytes} bytes")
        print(f"PNG lỗi/bị bỏ qua: {len(invalid)}")
        print(f"Sticker root: {sticker_root}")
    return 0


def pull_command(args: argparse.Namespace) -> int:
    zalo_root = require_directory(args.zalo_root, "ZaloData")
    account = select_account(zalo_root, args.account_id)
    sticker_root = ensure_directory_below(account, account / "sticker", create=False)
    bridge_root = validate_bridge_root(args.bridge_dir, zalo_root, create=not args.dry_run)
    if args.dry_run and not bridge_root.is_dir():
        # A dry-run may inspect a future destination path but must not create or
        # chmod the bridge root (or any child below it).
        destination_root = lexical(bridge_root / "from_zalo")
        bridge_safe_root = None
    else:
        destination_root = ensure_directory_below(
            bridge_root,
            bridge_root / "from_zalo",
            create=not args.dry_run,
        )
        bridge_safe_root = bridge_root
    manifest_path = bridge_root / "sync-manifest.json"
    copied = existing = invalid = 0
    with manifest_transaction(bridge_root, manifest_path, dry_run=args.dry_run) as manifest:
        for path in iter_pngs(sticker_root):
            try:
                info = png_info(path)
                digest = sha256_file(path)
                target = destination_root / f"{digest}.png"
                status = copy_atomically(
                    path,
                    target,
                    digest,
                    args.dry_run,
                    safe_root=bridge_safe_root,
                )
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
                direction="zalo_to_bridge",
                source_relative=relative_to(path, zalo_root),
                target_relative=relative_to(target, bridge_root),
                info=info,
                status=status,
            )
    print(
        f"pull: copied={copied} existing={existing} skipped={invalid} "
        f"destination={destination_root} dry_run={args.dry_run}"
    )
    return 0 if invalid == 0 else 2


def push_command(args: argparse.Namespace) -> int:
    source_root = require_directory(args.source, "Thư mục sticker nguồn")
    zalo_root = resolved(args.zalo_root)
    bridge_root = validate_bridge_root(args.bridge_dir, zalo_root, create=not args.dry_run)
    if args.zalo_cache and args.target:
        raise BridgeError("Dùng --zalo-cache hoặc --target, không dùng cả hai")
    target: Path
    if args.zalo_cache:
        zalo_root = require_directory(zalo_root, "ZaloData")
        account = select_account(zalo_root, args.account_id)
        target = account / "sticker" / "memevault-bridge"
    elif args.target:
        target = lexical(args.target)
        if target.is_symlink():
            raise BridgeError(f"Đích không được là symlink: {target}")
        target = target.resolve(strict=False)
    else:
        target = resolved(args.bridge_dir) / "outbox"

    writes_live_zalo = is_within(target, zalo_root)
    if is_within(target, source_root):
        raise BridgeError("Đích không được nằm bên trong thư mục nguồn")
    if writes_live_zalo and not args.zalo_cache:
        raise BridgeError("Ghi trong ZaloData chỉ được phép qua --zalo-cache")
    if writes_live_zalo and not args.dry_run and not (args.apply and args.yes):
        raise BridgeError(
            "Đích nằm trong ZaloData; cần truyền đồng thời --apply --yes "
            "(không tự động bật ghi cache)"
        )
    target_anchor: Path | None
    if writes_live_zalo:
        target_anchor = zalo_root
    elif args.target:
        target_anchor = None
    else:
        target_anchor = bridge_root
    if target_anchor is not None:
        if args.dry_run and not target_anchor.is_dir():
            # Keep the inspection path lexical in dry-run mode. In particular,
            # do not create bridge/outbox when the bridge root is absent.
            target = lexical(target)
            target_anchor = None
        else:
            target = ensure_directory_below(target_anchor, target, create=not args.dry_run)
    else:
        target = lexical(target)
        if target.is_symlink():
            raise BridgeError(f"Đích không được là symlink: {target}")
        if not args.dry_run:
            target.mkdir(parents=True, exist_ok=True)
        if target.exists() and not target.is_dir():
            raise BridgeError(f"Đích không phải thư mục: {target}")
    manifest_path = bridge_root / "sync-manifest.json"
    copied = existing = invalid = 0
    with manifest_transaction(bridge_root, manifest_path, dry_run=args.dry_run) as manifest:
        for path in iter_pngs(source_root):
            try:
                info = png_info(path)
                digest = sha256_file(path)
                destination = target / f"{digest}.png"
                status = copy_atomically(
                    path,
                    destination,
                    digest,
                    args.dry_run,
                    safe_root=target_anchor,
                )
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
                direction="bridge_to_zalo_cache" if writes_live_zalo else "bridge_to_outbox",
                source_relative=relative_to(path, source_root),
                target_relative=relative_to(destination, target),
                info=info,
                status=status,
            )
    print(
        f"push: copied={copied} existing={existing} skipped={invalid} "
        f"destination={target} live_zalo={writes_live_zalo} dry_run={args.dry_run}"
    )
    return 0 if invalid == 0 else 2


def sync_command(args: argparse.Namespace) -> int:
    """Pull Zalo cache then push a local source directory in one explicit command."""

    pull_result = pull_command(
        argparse.Namespace(
            zalo_root=args.zalo_root,
            bridge_dir=args.bridge_dir,
            account_id=args.account_id,
            dry_run=args.dry_run,
        )
    )
    push_result = push_command(
        argparse.Namespace(
            source=args.source,
            target=args.target,
            zalo_cache=args.zalo_cache,
            apply=args.apply,
            yes=args.yes,
            dry_run=args.dry_run,
            zalo_root=args.zalo_root,
            bridge_dir=args.bridge_dir,
            account_id=args.account_id,
        )
    )
    return max(pull_result, push_result)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zalo-root", type=Path, default=default_zalo_root())
    parser.add_argument("--bridge-dir", type=Path, default=default_bridge_root())
    parser.add_argument("--account-id")
    subparsers = parser.add_subparsers(dest="command", required=True)

    scan = subparsers.add_parser("scan", help="Quét cache sticker, chỉ đọc")
    scan.add_argument("--json", action="store_true", help="In manifest tạm dạng JSON")
    scan.set_defaults(handler=scan_command)

    pull = subparsers.add_parser("pull", help="Copy sticker từ Zalo vào bridge")
    pull.add_argument("--dry-run", action="store_true")
    pull.set_defaults(handler=pull_command)

    push = subparsers.add_parser("push", help="Copy PNG từ bridge/thư mục nguồn ra đích")
    push.add_argument("source", type=Path)
    push.add_argument("--target", type=Path, help="Đích an toàn; mặc định là bridge/outbox")
    push.add_argument(
        "--zalo-cache",
        action="store_true",
        help="Chọn ZaloData/media/<account>/sticker/memevault-bridge",
    )
    push.add_argument("--apply", action="store_true", help="Cho phép ghi đích Zalo khi dùng --zalo-cache")
    push.add_argument("--yes", action="store_true", help="Xác nhận ghi cache Zalo thử nghiệm")
    push.add_argument("--dry-run", action="store_true")
    push.set_defaults(handler=push_command)

    sync = subparsers.add_parser("sync", help="Pull từ Zalo rồi push thư mục nguồn")
    sync.add_argument("source", type=Path)
    sync.add_argument("--target", type=Path, help="Đích an toàn; mặc định là bridge/outbox")
    sync.add_argument(
        "--zalo-cache",
        action="store_true",
        help="Chọn ZaloData/media/<account>/sticker/memevault-bridge cho push",
    )
    sync.add_argument("--apply", action="store_true", help="Cho phép ghi đích Zalo khi dùng --zalo-cache")
    sync.add_argument("--yes", action="store_true", help="Xác nhận ghi cache Zalo thử nghiệm")
    sync.add_argument("--dry-run", action="store_true")
    sync.set_defaults(handler=sync_command)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except BridgeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Đã hủy", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
