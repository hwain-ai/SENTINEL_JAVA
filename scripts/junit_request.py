#!/usr/bin/python3
"""Create the private request consumed by the opt-in JUnit typed listener."""

from __future__ import annotations

import argparse
import hashlib
import os
import secrets
import shutil
import stat
import sys
from pathlib import Path


class RequestError(ValueError):
    """Raised when a typed-listener request cannot be created safely."""


def _directory(path: Path, field: str, private: bool) -> Path:
    if not path.is_absolute():
        raise RequestError(f"{field} must be absolute")
    try:
        resolved = path.resolve(strict=True)
        details = path.lstat()
    except OSError as error:
        raise RequestError(f"{field} is unavailable") from error
    if resolved != path or not stat.S_ISDIR(details.st_mode):
        raise RequestError(f"{field} is not a canonical directory")
    if details.st_uid != os.getuid():
        raise RequestError(f"{field} has the wrong owner")
    if private and stat.S_IMODE(details.st_mode) & 0o077:
        raise RequestError(f"{field} is not owner-only")
    return resolved


def _source(root: Path, relative_value: str) -> Path:
    relative = Path(relative_value)
    if relative.is_absolute() or relative != Path(os.path.normpath(relative_value)):
        raise RequestError("source path is not canonical relative")
    source = root / relative
    try:
        details = source.lstat()
    except OSError as error:
        raise RequestError("source is unavailable") from error
    if not stat.S_ISREG(details.st_mode) or source.resolve(strict=True) != source:
        raise RequestError("source is not a canonical regular file")
    return source


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as error:
        raise RequestError("source cannot be read") from error
    return digest.hexdigest()


def _exclusive_write(path: Path, content: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())


def _validate_cache_tree(directory: Path) -> None:
    try:
        entries = list(directory.iterdir())
    except OSError as error:
        raise RequestError("generated output cache is unreadable") from error
    for entry in entries:
        try:
            details = entry.lstat()
        except OSError as error:
            raise RequestError("generated output cache is unstable") from error
        if details.st_uid != os.getuid():
            raise RequestError("generated output cache has the wrong owner")
        if stat.S_ISDIR(details.st_mode):
            _validate_cache_tree(entry)
        elif not stat.S_ISREG(details.st_mode) or details.st_nlink != 1:
            raise RequestError("generated output cache is ambiguous")


def _fresh_target(root: Path) -> bool:
    target = root / "target"
    if not target.exists() and not target.is_symlink():
        target.mkdir(mode=0o700)
        return False
    try:
        details = target.lstat()
        canonical = target.resolve(strict=True)
    except OSError as error:
        raise RequestError("generated output cache is unavailable") from error
    if (
        not stat.S_ISDIR(details.st_mode)
        or details.st_uid != os.getuid()
        or canonical != target
    ):
        raise RequestError("generated output cache is ambiguous")
    _validate_cache_tree(target)
    try:
        shutil.rmtree(target)
        target.mkdir(mode=0o700)
    except OSError as error:
        raise RequestError("generated output cache cannot be reset") from error
    return False


def write_request(
    root: Path, relative_source: str, evidence: Path, retain_key: bool
) -> tuple[str, str, Path, str]:
    """Create the request file; returns (nonce, source sha256, event file, hmac key)."""

    root = _directory(root, "repository root", False)
    evidence = _directory(evidence, "evidence directory", True)
    source = _source(root, relative_source)
    cache_observed = _fresh_target(root)
    nonce = secrets.token_hex(16)
    hmac_key = secrets.token_hex(32)
    source_sha256 = _sha256(source)
    event_file = evidence / f"{nonce}.json"
    key_file = evidence / f"{nonce}.key"
    if event_file.exists() or event_file.is_symlink():
        raise RequestError("event file already exists")
    if retain_key and (key_file.exists() or key_file.is_symlink()):
        raise RequestError("event key file already exists")
    request_parent = root / "target"
    request = request_parent / "sentinel-junit-request-v1"
    content = (
        "sentinel-java-junit-request-v2\n"
        f"{nonce}\n"
        f"{source_sha256}\n"
        f"{event_file}\n"
        f"{hmac_key}\n"
        f"cache-observed={str(cache_observed).lower()}\n"
    ).encode("utf-8")
    try:
        if retain_key:
            _exclusive_write(key_file, f"{hmac_key}\n".encode("ascii"))
        _exclusive_write(request, content)
    except OSError as error:
        if retain_key:
            try:
                key_file.unlink(missing_ok=True)
            except OSError:
                pass
        raise RequestError("request cannot be created") from error
    return nonce, source_sha256, event_file, hmac_key


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("write",))
    parser.add_argument("repository_root", type=Path)
    parser.add_argument("source")
    parser.add_argument("evidence_directory", type=Path)
    parser.add_argument("--retain-key", action="store_true")
    arguments = parser.parse_args()
    try:
        for line in write_request(
            arguments.repository_root,
            arguments.source,
            arguments.evidence_directory,
            arguments.retain_key,
        ):
            print(line)
    except RequestError as error:
        print(f"junit request error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
