#!/usr/bin/python3
"""Validate the exact JaCoCo artifacts and mutate4java source provenance."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT_FIELDS = {"schemaVersion", "repository", "artifacts", "mutationBackend"}
ARTIFACT_FIELDS = {"version", "url", "size", "sha256", "fileName"}
MUTATION_FIELDS = {
    "name",
    "repositoryUrl",
    "commit",
    "gitArchiveSha256",
    "status",
    "sourceArchive",
    "runtime",
}
SOURCE_ARCHIVE_FIELDS = {"url", "size", "sha256", "fileName"}
RUNTIME_FIELDS = {
    "fileName",
    "size",
    "sha256",
    "mainClass",
    "sourceFileCount",
    "classFileCount",
    "buildJdk",
}
SHA256 = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
PINNED_ARTIFACTS = {
    "jacoco-agent": (
        "0.8.12",
        "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.12/"
        "org.jacoco.agent-0.8.12-runtime.jar",
        302428,
        "115e8e6e6593ca3a9892dfef695df4d487c706e59e71e64dc0ab95716ee02622",
        "org.jacoco.agent-0.8.12-runtime.jar",
    ),
    "jacoco-cli": (
        "0.8.12",
        "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/0.8.12/"
        "org.jacoco.cli-0.8.12-nodeps.jar",
        586283,
        "594c01125b84864a7fc5a9efab19b71b7de36b27346863f6d7cd0cd45400ceb0",
        "org.jacoco.cli-0.8.12-nodeps.jar",
    ),
}
PINNED_MUTATION = (
    "mutate4java",
    "https://github.com/unclebob/mutate4java.git",
    "7b05fdd71e8fe36327aff837806dfbff86af0572",
    "762c4b91ef592fbd07dace1189626013b3935c0fa41407242c52ebb1ffdeb34f",
    "runtime-locked",
)
PINNED_MUTATION_SOURCE = (
    "https://codeload.github.com/unclebob/mutate4java/tar.gz/"
    "7b05fdd71e8fe36327aff837806dfbff86af0572",
    95493,
    "a866214305d5fff9b5c19ed9421cc1aed33f770a760b04c7e9a2cecc0f2248fd",
    "mutate4java-7b05fdd-source.tar.gz",
)
PINNED_MUTATION_RUNTIME = (
    "mutate4java-7b05fdd.jar",
    116320,
    "117ef17d0dfe32e50f0449a652b83c92a377858cdcdd9c1d191a8d4a69d70ad3",
    "mutate4java.cli.Main",
    96,
    104,
    "17.0.20.1+1",
)


class BackendLockError(ValueError):
    """Raised when backend provenance cannot be verified."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BackendLockError(f"duplicate key {key!r}")
        result[key] = value
    return result


def _invalid_constant(value: str) -> None:
    raise BackendLockError(f"invalid constant {value}")


def _load(path: Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise BackendLockError("lock is missing or is a symlink")
    try:
        document = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_invalid_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BackendLockError(f"cannot read lock: {error}") from error
    _validate_document(document)
    return document


def _validate_document(document: Any) -> None:
    if not isinstance(document, dict) or set(document) != ROOT_FIELDS:
        raise BackendLockError("root fields are invalid")
    if document["schemaVersion"] != "sentinel-java-backend-lock-v1":
        raise BackendLockError("schema version is invalid")
    if document["repository"] != "SENTINEL_JAVA":
        raise BackendLockError("repository identity is invalid")
    artifacts = document["artifacts"]
    if not isinstance(artifacts, dict) or set(artifacts) != set(PINNED_ARTIFACTS):
        raise BackendLockError("artifact set is invalid")
    for name, expected in PINNED_ARTIFACTS.items():
        _validate_artifact(name, artifacts[name], expected)
    _validate_mutation(document["mutationBackend"])


def _validate_artifact(name: str, value: Any, expected: tuple[Any, ...]) -> None:
    if not isinstance(value, dict) or set(value) != ARTIFACT_FIELDS:
        raise BackendLockError(f"{name} fields are invalid")
    actual = tuple(value[field] for field in ("version", "url", "size", "sha256", "fileName"))
    if actual != expected:
        raise BackendLockError(f"{name} provenance differs from the pinned release")
    if isinstance(value["size"], bool) or not isinstance(value["size"], int):
        raise BackendLockError(f"{name} size is invalid")
    if SHA256.fullmatch(value["sha256"]) is None:
        raise BackendLockError(f"{name} digest is invalid")


def _validate_mutation(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != MUTATION_FIELDS:
        raise BackendLockError("mutation backend fields are invalid")
    actual = tuple(
        value[field]
        for field in ("name", "repositoryUrl", "commit", "gitArchiveSha256", "status")
    )
    if actual != PINNED_MUTATION:
        raise BackendLockError("mutation backend provenance differs from the pinned source")
    if COMMIT.fullmatch(value["commit"]) is None:
        raise BackendLockError("mutation commit is invalid")
    if SHA256.fullmatch(value["gitArchiveSha256"]) is None:
        raise BackendLockError("mutation archive digest is invalid")
    _validate_mutation_source(value["sourceArchive"])
    _validate_mutation_runtime(value["runtime"])


def _validate_mutation_source(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != SOURCE_ARCHIVE_FIELDS:
        raise BackendLockError("mutation source archive fields are invalid")
    actual = tuple(value[field] for field in ("url", "size", "sha256", "fileName"))
    if actual != PINNED_MUTATION_SOURCE:
        raise BackendLockError("mutation source archive differs from the pinned release")


def _validate_mutation_runtime(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != RUNTIME_FIELDS:
        raise BackendLockError("mutation runtime fields are invalid")
    fields = (
        "fileName",
        "size",
        "sha256",
        "mainClass",
        "sourceFileCount",
        "classFileCount",
        "buildJdk",
    )
    actual = tuple(value[field] for field in fields)
    if actual != PINNED_MUTATION_RUNTIME:
        raise BackendLockError("mutation runtime differs from the reproducible build")


def _verify_artifact(artifact: dict[str, Any], path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise BackendLockError("artifact is missing or is a symlink")
    try:
        if path.stat().st_size != artifact["size"]:
            raise BackendLockError("artifact size mismatch")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as error:
        raise BackendLockError("cannot read artifact") from error
    if digest.hexdigest() != artifact["sha256"]:
        raise BackendLockError("artifact digest mismatch")


def _print_artifact(artifact: dict[str, Any]) -> None:
    print(artifact["version"])
    print(artifact["url"])
    print(artifact["size"])
    print(artifact["sha256"])
    print(artifact["fileName"])


def _print_mutation(value: dict[str, Any]) -> None:
    print(value["name"])
    print(value["repositoryUrl"])
    print(value["commit"])
    print(value["gitArchiveSha256"])
    print(value["status"])
    source = value["sourceArchive"]
    print(source["url"])
    print(source["size"])
    print(source["sha256"])
    print(source["fileName"])
    runtime = value["runtime"]
    print(runtime["fileName"])
    print(runtime["size"])
    print(runtime["sha256"])
    print(runtime["mainClass"])
    print(runtime["sourceFileCount"])
    print(runtime["classFileCount"])
    print(runtime["buildJdk"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("lock", type=Path)
    parser.add_argument("backend", choices=("jacoco-agent", "jacoco-cli", "mutate4java"))
    verification = parser.add_mutually_exclusive_group()
    verification.add_argument("--verify", type=Path)
    verification.add_argument("--verify-source", type=Path)
    verification.add_argument("--verify-jar", type=Path)
    arguments = parser.parse_args()
    try:
        document = _load(arguments.lock)
        if arguments.backend == "mutate4java":
            if arguments.verify is not None:
                raise BackendLockError("use the typed mutation verification option")
            mutation = document["mutationBackend"]
            if arguments.verify_source is not None:
                _verify_artifact(mutation["sourceArchive"], arguments.verify_source)
            elif arguments.verify_jar is not None:
                _verify_artifact(mutation["runtime"], arguments.verify_jar)
            else:
                _print_mutation(mutation)
        else:
            if arguments.verify_source is not None or arguments.verify_jar is not None:
                raise BackendLockError("mutation verification option used for coverage artifact")
            artifact = document["artifacts"][arguments.backend]
            if arguments.verify is not None:
                _verify_artifact(artifact, arguments.verify)
            else:
                _print_artifact(artifact)
    except BackendLockError as error:
        print(f"backend lock error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
