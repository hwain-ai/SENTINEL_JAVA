#!/usr/bin/python3
"""Read and validate the repository-owned Java toolchain lock."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform as platform_module
import re
import stat
import sys
from pathlib import Path
from typing import Any


EXPECTED_REPOSITORY = "SENTINEL_JAVA"
LOCKED_STATUS = "locked"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
SAFE_DIRECTORY_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+_-]*")
SHA512_PATTERN = re.compile(r"[0-9a-f]{128}")
ROOT_FIELDS = {"repository", "status", "toolchains"}
COMMON_TOOL_FIELDS = {
    "version",
    "platform",
    "archiveUrl",
    "archiveSize",
    "archiveSha256",
    "archiveRoot",
    "installDirectory",
    "binarySha256",
    "installedTreeSha256",
    "versionOutput",
    "status",
}


PLATFORM_KEYS = ("linux-x86_64", "linux-aarch64", "darwin-x86_64", "darwin-aarch64")
# Per-platform JDK fields; the remaining java fields are common to every platform.
JAVA_PLATFORM_FIELDS = {
    "archiveUrl",
    "archiveSize",
    "archiveSha256",
    "archiveRoot",
    "installDirectory",
    "javaHomeRelativePath",
    "binarySha256",
    "installedTreeSha256",
}
JAVA_COMMON_FIELDS = {"vendor", "version", "versionOutput", "status", "platforms"}


class LockError(ValueError):
    """Raised when the lock cannot safely select a toolchain."""


def platform_key(system: str | None = None, machine: str | None = None) -> str:
    """linux-x86_64 | linux-aarch64 | darwin-x86_64 | darwin-aarch64 for this host."""

    system = (system or platform_module.system()).lower()
    machine = (machine or platform_module.machine()).lower()
    if system not in ("linux", "darwin"):
        raise LockError(f"unsupported operating system: {system}")
    if machine in ("x86_64", "amd64"):
        architecture = "x86_64"
    elif machine in ("aarch64", "arm64"):
        architecture = "aarch64"
    else:
        raise LockError(f"unsupported architecture: {machine}")
    return f"{system}-{architecture}"


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise LockError(f"toolchain lock contains duplicate key {key!r}")
        result[key] = value
    return result


def _invalid_constant(value: str) -> None:
    raise LockError(f"toolchain lock contains invalid constant {value}")


def _text(mapping: dict[str, Any], key: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value:
        raise LockError(f"toolchain lock field {key!r} is missing")
    return value


def _optional_text(mapping: dict[str, Any], key: str) -> str | None:
    value = mapping.get(key)
    if value is not None and (not isinstance(value, str) or not value):
        raise LockError(f"toolchain lock field {key!r} is invalid")
    return value


def _load(path: Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise LockError("toolchain lock must be a regular, non-symlink file")
    try:
        document = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_invalid_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise LockError(f"cannot read toolchain lock: {error}") from error
    if not isinstance(document, dict) or document.get("repository") != EXPECTED_REPOSITORY:
        raise LockError("toolchain lock repository identity is invalid")
    if not isinstance(document.get("toolchains"), dict):
        raise LockError("toolchain lock has no toolchains object")
    if set(document) != ROOT_FIELDS:
        raise LockError("toolchain lock root fields are invalid")
    if set(document["toolchains"]) != {"java", "maven"}:
        raise LockError("toolchain lock tool set is invalid")
    _validate_fields(document["toolchains"])
    return document


def _validate_fields(toolchains: dict[str, Any]) -> None:
    java = toolchains["java"]
    maven = toolchains["maven"]
    if not isinstance(java, dict):
        raise LockError("java toolchain fields are invalid")
    if "platforms" in java:
        if set(java) != JAVA_COMMON_FIELDS or not isinstance(java["platforms"], dict):
            raise LockError("java toolchain fields are invalid")
        for key, entry in java["platforms"].items():
            if key not in PLATFORM_KEYS or not isinstance(entry, dict) or set(entry) != JAVA_PLATFORM_FIELDS:
                raise LockError(f"java platform entry is invalid: {key}")
    elif set(java) != COMMON_TOOL_FIELDS | {"vendor"}:
        raise LockError("java toolchain fields are invalid")
    if not isinstance(maven, dict) or set(maven) != COMMON_TOOL_FIELDS | {"archiveSha512"}:
        raise LockError("maven toolchain fields are invalid")


def _for_platform(tool: str, toolchain: dict[str, Any], platform: str | None) -> dict[str, Any]:
    """Merge the JDK's platforms[...] entry into its common fields; Maven is the same tarball everywhere."""

    platforms = toolchain.get("platforms")
    if platforms is None:
        return toolchain
    key = platform or platform_key()
    entry = platforms.get(key)
    if not isinstance(entry, dict):
        raise LockError(f"{tool} toolchain has no entry for platform {key}")
    merged = {name: value for name, value in toolchain.items() if name != "platforms"}
    merged.update(entry)
    merged["platform"] = key
    return merged


def java_home_relative(toolchain: dict[str, Any]) -> str:
    """Where JAVA_HOME sits inside the installed tree ('' on Linux, Contents/Home in macOS bundles)."""

    value = toolchain.get("javaHomeRelativePath", "")
    if value and (not isinstance(value, str) or any(part in ("", ".", "..") for part in value.split("/"))):
        raise LockError("java javaHomeRelativePath is unsafe")
    return value


def _select(
    document: dict[str, Any], tool: str, require_locked: bool, platform: str | None = None
) -> dict[str, Any]:
    toolchain = document["toolchains"].get(tool)
    if not isinstance(toolchain, dict):
        raise LockError(f"{tool} toolchain is pending")
    toolchain = _for_platform(tool, toolchain, platform)
    java_home_relative(toolchain)
    repository_status = _text(document, "status")
    tool_status = _text(toolchain, "status")
    if require_locked and (repository_status != LOCKED_STATUS or tool_status != LOCKED_STATUS):
        raise LockError(
            f"{tool} toolchain is pending: repository={repository_status}, tool={tool_status}"
        )
    directory = _text(toolchain, "installDirectory")
    if not SAFE_DIRECTORY_PATTERN.fullmatch(directory):
        raise LockError(f"{tool} installDirectory is unsafe")
    archive_root = _text(toolchain, "archiveRoot")
    if not SAFE_DIRECTORY_PATTERN.fullmatch(archive_root):
        raise LockError(f"{tool} archiveRoot is unsafe")
    archive_sha256 = _optional_text(toolchain, "archiveSha256")
    binary_sha256 = _optional_text(toolchain, "binarySha256")
    tree_sha256 = _optional_text(toolchain, "installedTreeSha256")
    if require_locked:
        _validate_locked_metadata(toolchain, tool)
    if require_locked and (
        archive_sha256 is None
        or binary_sha256 is None
        or tree_sha256 is None
        or SHA256_PATTERN.fullmatch(archive_sha256) is None
        or SHA256_PATTERN.fullmatch(binary_sha256) is None
        or SHA256_PATTERN.fullmatch(tree_sha256) is None
    ):
        raise LockError(f"{tool} checksums are pending")
    return toolchain


def _validate_locked_metadata(toolchain: dict[str, Any], tool: str) -> None:
    _text(toolchain, "version")
    _text(toolchain, "platform")
    if tool == "java":
        _text(toolchain, "vendor")
    url = _text(toolchain, "archiveUrl")
    size = toolchain.get("archiveSize")
    _text(toolchain, "versionOutput")
    if not url.startswith("https://") or not isinstance(size, int) or isinstance(size, bool) or size < 1:
        raise LockError(f"{tool} archive metadata is pending or invalid")
    for field in ("archiveSha256", "binarySha256", "installedTreeSha256"):
        value = _text(toolchain, field)
        if SHA256_PATTERN.fullmatch(value) is None:
            raise LockError(f"{tool} {field} is invalid")
        if len(set(value)) == 1:
            raise LockError(f"{tool} {field} is a placeholder")
    if tool == "maven":
        sha512 = _text(toolchain, "archiveSha512")
        if SHA512_PATTERN.fullmatch(sha512) is None or len(set(sha512)) == 1:
            raise LockError("maven archiveSha512 is invalid or a placeholder")
    if _text(toolchain, "archiveRoot") != _text(toolchain, "installDirectory"):
        raise LockError(f"{tool} archive and install directories differ")


def _fields(toolchain: dict[str, Any]) -> list[str]:
    names = (
        "version",
        "archiveUrl",
        "archiveSize",
        "archiveSha256",
        "archiveRoot",
        "installDirectory",
        "binarySha256",
        "versionOutput",
        "installedTreeSha256",
    )
    values: list[str] = []
    for name in names:
        value = toolchain.get(name)
        values.append("" if value is None else str(value))
    return values


def _record(digest: Any, kind: bytes, path: bytes, mode: int, payload: bytes) -> None:
    for value in (kind, path, f"{mode:03o}".encode("ascii"), payload):
        digest.update(len(value).to_bytes(8, "big"))
        digest.update(value)


def _file_digest(path: Path) -> bytes:
    digest = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        try:
            with os.fdopen(descriptor, "rb", closefd=False) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
        finally:
            os.close(descriptor)
    except OSError as error:
        raise LockError("cannot read installed tree file") from error
    return digest.digest()


def _tree_entries(root: Path) -> list[Path]:
    entries: list[Path] = []
    for current, directories, files in os.walk(
        root, topdown=True, onerror=_walk_error, followlinks=False
    ):
        current_path = Path(current)
        entries.extend(current_path / name for name in directories)
        entries.extend(current_path / name for name in files)
    return sorted(entries, key=lambda item: item.relative_to(root).as_posix().encode("utf-8"))


def _walk_error(error: OSError) -> None:
    raise LockError(f"cannot enumerate installed tree: {error}")


def _tree_digest(root: Path) -> str:
    if root.is_symlink() or not root.is_dir():
        raise LockError("installed tree is missing or is a symlink")
    _validate_owned_mode(root.lstat(), "installed tree root")
    resolved_root = root.resolve(strict=True)
    digest = hashlib.sha256()
    try:
        entries = _tree_entries(root)
    except (OSError, UnicodeError) as error:
        raise LockError(f"cannot enumerate installed tree: {error}") from error
    for path in entries:
        _add_tree_entry(digest, resolved_root, root, path)
    return digest.hexdigest()


def _add_tree_entry(digest: Any, resolved_root: Path, root: Path, path: Path) -> None:
    relative = path.relative_to(root).as_posix()
    try:
        path_bytes = relative.encode("utf-8")
        metadata = path.lstat()
    except (OSError, UnicodeError) as error:
        raise LockError(f"invalid installed tree entry: {relative!r}") from error
    mode = stat.S_IMODE(metadata.st_mode)
    if stat.S_ISREG(metadata.st_mode):
        _validate_owned_mode(metadata, relative)
        if metadata.st_nlink != 1:
            raise LockError(f"installed tree contains a hard link: {relative}")
        _record(digest, b"file", path_bytes, mode, _file_digest(path))
        return
    if stat.S_ISDIR(metadata.st_mode):
        _validate_owned_mode(metadata, relative)
        _record(digest, b"directory", path_bytes, mode, b"")
        return
    if stat.S_ISLNK(metadata.st_mode):
        # Symlink modes differ between Linux (777) and macOS (umask-dependent); only the target matters.
        mode = 0o777
        if metadata.st_uid != os.geteuid():
            raise LockError(f"installed tree entry has wrong ownership: {relative}")
        _add_symlink(digest, resolved_root, path, path_bytes, mode)
        return
    raise LockError(f"installed tree contains special file: {relative}")


def _validate_owned_mode(metadata: os.stat_result, label: str) -> None:
    if metadata.st_uid != os.geteuid():
        raise LockError(f"installed tree entry has wrong ownership: {label}")
    if stat.S_IMODE(metadata.st_mode) & 0o022:
        raise LockError(f"installed tree entry has unsafe permissions: {label}")


def _add_symlink(
    digest: Any, resolved_root: Path, path: Path, path_bytes: bytes, mode: int
) -> None:
    try:
        target = os.readlink(path)
        target_bytes = target.encode("utf-8")
    except (OSError, UnicodeError) as error:
        raise LockError("installed tree symlink target is invalid") from error
    if os.path.isabs(target):
        raise LockError("installed tree contains an absolute symlink")
    try:
        resolved_target = (path.parent / target).resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise LockError("installed tree symlink target is missing") from error
    try:
        resolved_target.relative_to(resolved_root)
    except ValueError as error:
        raise LockError("installed tree symlink escapes its root") from error
    _record(digest, b"symlink", path_bytes, mode, target_bytes)


def _verify_tree(toolchain: dict[str, Any], root: Path) -> None:
    expected = _text(toolchain, "installedTreeSha256")
    if _tree_digest(root) != expected:
        raise LockError("installed tree manifest mismatch")


def _verify_private_directory(path: Path) -> None:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise LockError("private directory is missing") from error
    if (not stat.S_ISDIR(metadata.st_mode)
            or stat.S_ISLNK(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o700):
        raise LockError("private directory ownership or permissions are invalid")


def _verify_version_output(toolchain: dict[str, Any], actual: str) -> None:
    if actual != _text(toolchain, "versionOutput"):
        raise LockError("toolchain version output mismatch")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("lock", type=Path)
    parser.add_argument("tool", choices=("java", "maven"))
    parser.add_argument("--require-locked", action="store_true")
    parser.add_argument("--platform", choices=PLATFORM_KEYS)
    tree_action = parser.add_mutually_exclusive_group()
    tree_action.add_argument("--verify-tree", type=Path)
    tree_action.add_argument("--print-tree-digest", type=Path)
    tree_action.add_argument("--verify-private-directory", type=Path)
    tree_action.add_argument("--verify-version-output")
    arguments = parser.parse_args()
    try:
        document = _load(arguments.lock)
        toolchain = _select(document, arguments.tool, arguments.require_locked, arguments.platform)
        if arguments.verify_tree is not None:
            _verify_tree(toolchain, arguments.verify_tree)
        if arguments.print_tree_digest is not None:
            print(_tree_digest(arguments.print_tree_digest))
        if arguments.verify_private_directory is not None:
            _verify_private_directory(arguments.verify_private_directory)
        if arguments.verify_version_output is not None:
            _verify_version_output(toolchain, arguments.verify_version_output)
    except LockError as error:
        print(f"toolchain error: {error}", file=sys.stderr)
        return 2
    if (arguments.verify_tree is None
            and arguments.print_tree_digest is None
            and arguments.verify_private_directory is None
            and arguments.verify_version_output is None):
        print("\n".join(_fields(toolchain)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
