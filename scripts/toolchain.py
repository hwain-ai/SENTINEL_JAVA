#!/usr/bin/env python3
"""Cross-platform launcher for the checker's locked JDK, Maven and mutation backend.

Replaces the Linux-only shell launchers. Runs on Linux and macOS (x86_64 and
arm64) with the host's Python 3.9+ and the standard library only.

    scripts/toolchain.py bootstrap             locked JDK and Maven for this platform
    scripts/toolchain.py backends              JaCoCo jars and the mutate4java jar built from its locked source
    scripts/toolchain.py m2                    one online Maven run that fills the offline repository
    scripts/toolchain.py pit-probe             PIT probe jars used by the test suite
    scripts/toolchain.py setup                 bootstrap, backends, m2, offline compile, doctor
    scripts/toolchain.py mvn ARGS...           offline Maven with the checker's own repository (closed argument set)
    scripts/toolchain.py java ARGS...          the locked java executable
    scripts/toolchain.py deps PROJECT          online Maven test build that fills <PROJECT>/.sentinel-m2
    scripts/toolchain.py doctor                JSON summary of the verified installation
    scripts/toolchain.py paths                 JSON with the resolved JAVA_HOME, MAVEN_HOME and repository
    scripts/toolchain.py platform              print the detected platform key
    scripts/toolchain.py describe PLATFORM     (maintenance) lock fields for another platform's JDK archive

Every child process gets a minimal environment: no inherited variables, a
private HOME under .toolchain, and PATH limited to the locked tools.
"""
from __future__ import annotations

import sys

# The launcher may run under any interpreter; callers pass -I -B so no bytecode lands anywhere.
sys.dont_write_bytecode = True

import os  # noqa: E402

# Everything the launcher itself creates (downloads, backends, m2, pit-probe) is private, as the
# bootstrap scripts had it; Maven and java children run under the conventional 022 (see CHILD_UMASK).
os.umask(0o077)

import hashlib  # noqa: E402
import json  # noqa: E402
import platform as platform_module  # noqa: E402
import re  # noqa: E402
import shutil  # noqa: E402
import subprocess  # noqa: E402
import tarfile  # noqa: E402
import tempfile  # noqa: E402
import urllib.request  # noqa: E402
from pathlib import Path  # noqa: E402
from typing import Any, Dict, List, Optional, Sequence  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backend_lock  # noqa: E402
import toolchain_lock  # noqa: E402

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = REPOSITORY_ROOT / "toolchain.lock.json"
BACKEND_LOCK_FILE = REPOSITORY_ROOT / "backend.lock.json"
TOOLCHAIN_ROOT = REPOSITORY_ROOT / ".toolchain"
BACKENDS_ROOT = TOOLCHAIN_ROOT / "backends"
M2_ROOT = TOOLCHAIN_ROOT / "m2"
M2_MARKER = M2_ROOT / ".sentinel-populated-v1"
PIT_PROBE_ROOT = TOOLCHAIN_ROOT / "pit-probe"
PIT_MANIFEST = REPOSITORY_ROOT / "src" / "main" / "resources" / "pit-probe-artifacts.tsv"
PRIVATE_DIRECTORIES = ("home", "downloads")
# mvn.sh and java.sh always ran their child under umask 022: build output and test files stay world-readable.
CHILD_UMASK = 0o022
MAVEN_FLAGS = {"-o", "--offline", "-B", "--batch-mode", "-ntp", "--no-transfer-progress", "-q", "--quiet", "-e", "--errors", "-V", "--show-version", "-v", "--version"}
MAVEN_PHASES = {"clean", "compile", "test", "package", "verify"}
TEST_SELECTOR = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(,[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*)*$")
USAGE = "usage: toolchain.py {bootstrap|backends|m2|pit-probe|setup|mvn|java|deps|doctor|paths|platform|describe} ..."


class ToolchainError(RuntimeError):
    pass


def fail(message: str) -> ToolchainError:
    return ToolchainError(f"toolchain error: {message}")


# ---------------------------------------------------------------- platform

def platform_key(system: Optional[str] = None, machine: Optional[str] = None) -> str:
    if (system or platform_module.system()).lower() == "windows":
        raise ToolchainError("toolchain error: native Windows is not supported; run SENTINEL inside WSL2")
    try:
        return toolchain_lock.platform_key(system, machine)
    except toolchain_lock.LockError as error:
        raise fail(str(error)) from error


def select(tool: str, key: Optional[str] = None) -> Dict[str, Any]:
    try:
        document = toolchain_lock._load(LOCK_FILE)
        return toolchain_lock._select(document, tool, True, key or platform_key())
    except toolchain_lock.LockError as error:
        raise fail(str(error)) from error


# ------------------------------------------------------------------ files

def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _private_directory(path: Path) -> None:
    if path.is_symlink():
        raise fail(f"private directory is a symlink: {path}")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)


def _fetch(url: str, destination: Path, size: int, sha256: str) -> None:
    """Download url into destination (a private, not yet existing file), verifying size and SHA-256."""

    if not url.startswith("https://"):
        raise fail("download URL must use HTTPS")
    partial = destination.with_name(destination.name + ".partial")
    if partial.exists():
        partial.unlink()
    request = urllib.request.Request(url, headers={"User-Agent": "sentinel-toolchain/1"})
    with urllib.request.urlopen(request, timeout=300) as response, open(partial, "wb") as stream:
        shutil.copyfileobj(response, stream, 1024 * 1024)
    os.chmod(partial, 0o600)
    if partial.stat().st_size != size:
        partial.unlink()
        raise fail(f"download size mismatch: {destination.name}")
    if _sha256_file(partial) != sha256:
        partial.unlink()
        raise fail(f"download checksum mismatch: {destination.name}")
    os.replace(partial, destination)


def _download_archive(entry: Dict[str, Any]) -> Path:
    downloads = TOOLCHAIN_ROOT / "downloads"
    _private_directory(downloads)
    archive = downloads / (entry["archiveSha256"] + ".tar.gz")
    if archive.is_file() and archive.stat().st_size == entry["archiveSize"] and _sha256_file(archive) == entry["archiveSha256"]:
        return archive
    _fetch(entry["archiveUrl"], archive, entry["archiveSize"], entry["archiveSha256"])
    return archive


# The JDK and Maven trees keep the archive modes minus group/other write bits (umask 022), as the
# lock was first taken with; JDK archives carry read-only 444 files that must stay 444.
DIRECTORY_MODE = 0o755


def _stays_under_root(name: str, parts: Sequence[str], root_name: str) -> bool:
    absolute = name.startswith("/") or name.startswith("\\")
    return bool(parts) and not absolute and ".." not in parts and parts[0] == root_name


def _safe_member(member: tarfile.TarInfo, root_name: str) -> Optional[str]:
    parts = Path(member.name).parts
    if not _stays_under_root(member.name, parts, root_name):
        raise fail(f"archive entry escapes or leaves the expected root: {member.name}")
    if len(parts) == 1:
        return None
    if member.type not in (tarfile.DIRTYPE, tarfile.SYMTYPE, tarfile.REGTYPE, tarfile.AREGTYPE):
        raise fail(f"archive entry has an unsupported type: {member.name}")
    return "/".join(parts[1:])


def _normalized_mode(member: tarfile.TarInfo) -> int:
    if member.isdir():
        return DIRECTORY_MODE
    return member.mode & 0o755


def _extract_entry(tar: tarfile.TarFile, member: tarfile.TarInfo, target: Path) -> None:
    if member.isdir():
        target.mkdir(mode=DIRECTORY_MODE, exist_ok=True)
        return
    target.parent.mkdir(mode=DIRECTORY_MODE, parents=True, exist_ok=True)
    source = tar.extractfile(member)
    if source is None:
        raise fail(f"archive entry is unreadable: {member.name}")
    with open(target, "wb") as stream:
        shutil.copyfileobj(source, stream, 1024 * 1024)
    os.chmod(target, _normalized_mode(member))


def _inside(root: Path, candidate: Path) -> bool:
    try:
        return os.path.commonpath((str(root), str(candidate))) == str(root)
    except ValueError:
        return False


def _create_symlink(root: Path, target: Path, link: str) -> None:
    if link.startswith("/"):
        raise fail(f"archive symlink is absolute: {target}")
    target.parent.mkdir(mode=DIRECTORY_MODE, parents=True, exist_ok=True)
    os.symlink(link, target)
    if not _inside(root.resolve(), target.resolve()):
        target.unlink()
        raise fail(f"archive symlink escapes its root: {target}")


def _set_directory_modes(root: Path, mode: int) -> None:
    for current, directories, _files in os.walk(root):
        for name in directories:
            path = Path(current) / name
            if not path.is_symlink():
                os.chmod(path, mode)


def extract(archive: Path, root_name: str, destination: Path) -> None:
    """Extract the archive's root directory with normalized modes (755 dirs, 644/755 files)."""

    destination.mkdir(mode=DIRECTORY_MODE)
    symlinks: List[tuple] = []
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            relative = _safe_member(member, root_name)
            if relative is None:
                continue
            if member.issym():
                symlinks.append((destination / relative, member.linkname))
            else:
                _extract_entry(tar, member, destination / relative)
    for target, link in symlinks:
        _create_symlink(destination, target, link)
    _set_directory_modes(destination, DIRECTORY_MODE)


# ------------------------------------------------------------- toolchains

def _java_home(entry: Dict[str, Any]) -> Path:
    home = TOOLCHAIN_ROOT / entry["installDirectory"]
    relative = toolchain_lock.java_home_relative(entry)
    return home / relative if relative else home


def _maven_home(entry: Dict[str, Any]) -> Path:
    return TOOLCHAIN_ROOT / entry["installDirectory"]


def _child_environment(java_home: Path, maven_home: Path) -> Dict[str, str]:
    return {
        "HOME": str(TOOLCHAIN_ROOT / "home"),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PATH": os.pathsep.join([str(java_home / "bin"), str(maven_home / "bin"), "/usr/bin", "/bin"]),
        "JAVA_HOME": str(java_home),
        "MAVEN_HOME": str(maven_home),
        "MAVEN_SKIP_RC": "true",
    }


def _version_line(argv: Sequence[str], environment: Dict[str, str]) -> str:
    completed = subprocess.run(
        list(argv), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        env=environment, check=False, text=True,
    )
    if completed.returncode != 0:
        raise fail(f"{Path(argv[0]).name} version probe failed")
    return completed.stdout.splitlines()[0].strip() if completed.stdout else ""


def _verify_tree_installed(tool: str, entry: Dict[str, Any]) -> Path:
    root = TOOLCHAIN_ROOT / entry["installDirectory"]
    if root.is_symlink() or not root.is_dir():
        raise fail(f"verified {tool} tree is missing; run scripts/toolchain.py bootstrap")
    try:
        toolchain_lock._verify_tree(entry, root)
    except toolchain_lock.LockError as error:
        raise fail(str(error)) from error
    return root


def _verify_binary(tool: str, binary: Path, expected: str) -> Path:
    if binary.is_symlink() or not binary.is_file() or not os.access(binary, os.X_OK):
        raise fail(f"verified {tool} executable is missing")
    if _sha256_file(binary) != expected:
        raise fail(f"{tool} binary checksum mismatch")
    return binary


def _verify_private_files() -> None:
    if TOOLCHAIN_ROOT.is_symlink() or not TOOLCHAIN_ROOT.is_dir():
        raise fail("verified local toolchain root is missing")
    for name in ("", "home", "m2"):
        try:
            toolchain_lock._verify_private_directory(TOOLCHAIN_ROOT / name)
        except toolchain_lock.LockError as error:
            raise fail(f"private directory .toolchain/{name}: {error}") from error
    for relative in (".m2/settings.xml", ".m2/settings-security.xml", ".m2/toolchains.xml", ".mavenrc"):
        path = TOOLCHAIN_ROOT / "home" / relative
        if path.exists() or path.is_symlink():
            raise fail(f"Maven user configuration is not allowed: .toolchain/home/{relative}")
    dot_mvn = REPOSITORY_ROOT / ".mvn"
    if dot_mvn.exists() or dot_mvn.is_symlink():
        raise fail("repository .mvn overrides are not allowed")


class Installation:
    """The verified JDK and Maven of this platform plus the environment their children get."""

    def __init__(self, check_version: bool = False) -> None:
        self.java = select("java")
        self.maven = select("maven")
        _verify_private_files()
        _verify_tree_installed("java", self.java)
        _verify_tree_installed("maven", self.maven)
        self.java_home = _java_home(self.java)
        self.maven_home = _maven_home(self.maven)
        self.java_binary = _verify_binary("java", self.java_home / "bin" / "java", self.java["binarySha256"])
        self.maven_binary = _verify_binary("maven", self.maven_home / "bin" / "mvn", self.maven["binarySha256"])
        self.environment = _child_environment(self.java_home, self.maven_home)
        if check_version:
            self._check_versions()

    def _check_versions(self) -> None:
        observed = _version_line([str(self.java_binary), "-version"], self.environment)
        if observed != self.java["versionOutput"]:
            raise fail(f"java version output mismatch: expected {self.java['versionOutput']!r}, got {observed!r}")
        observed = _version_line([str(self.maven_binary), "--version"], self.environment)
        if observed != self.maven["versionOutput"]:
            raise fail(f"maven version output mismatch: expected {self.maven['versionOutput']!r}, got {observed!r}")

    def run(self, argv: Sequence[str], cwd: Path = REPOSITORY_ROOT, quiet: bool = False) -> int:
        completed = subprocess.run(
            list(argv), cwd=str(cwd), env=self.environment, check=False,
            stdout=subprocess.DEVNULL if quiet else None,
        )
        return completed.returncode

    def execute(self, argv: Sequence[str]) -> int:
        sys.stdout.flush()
        sys.stderr.flush()
        os.umask(CHILD_UMASK)
        os.execve(argv[0], list(argv), self.environment)
        return 3  # not reached


def _install_tree(tool: str, entry: Dict[str, Any]) -> None:
    root = TOOLCHAIN_ROOT / entry["installDirectory"]
    if root.exists() or root.is_symlink():
        _verify_tree_installed(tool, entry)
        return
    archive = _download_archive(entry)
    staging = Path(tempfile.mkdtemp(prefix=f".staging-{tool}-", dir=TOOLCHAIN_ROOT))
    try:
        extracted = staging / "tree"
        extract(archive, entry["archiveRoot"], extracted)
        try:
            toolchain_lock._verify_tree(entry, extracted)
        except toolchain_lock.LockError as error:
            raise fail(str(error)) from error
        os.rename(extracted, root)
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def bootstrap() -> Installation:
    # Read the lock before touching the disk: a pending lock leaves no partial .toolchain behind.
    java = select("java")
    maven = select("maven")
    _private_directory(TOOLCHAIN_ROOT)
    for name in PRIVATE_DIRECTORIES + ("m2",):
        _private_directory(TOOLCHAIN_ROOT / name)
    _install_tree("java", java)
    _install_tree("maven", maven)
    return Installation(check_version=True)


# --------------------------------------------------------------- backends

def _backend_document() -> Dict[str, Any]:
    try:
        return backend_lock._load(BACKEND_LOCK_FILE)
    except backend_lock.BackendLockError as error:
        raise fail(str(error)) from error


def _verify_backend_file(artifact: Dict[str, Any], path: Path) -> None:
    try:
        backend_lock._verify_artifact(artifact, path)
    except backend_lock.BackendLockError as error:
        raise fail(str(error)) from error


def _install_backend_file(artifact: Dict[str, Any]) -> Path:
    destination = BACKENDS_ROOT / artifact["fileName"]
    if destination.exists() or destination.is_symlink():
        _verify_backend_file(artifact, destination)
        return destination
    _fetch(artifact["url"], destination, artifact["size"], artifact["sha256"])
    _verify_backend_file(artifact, destination)
    return destination


def _build_mutation_jar(installation: Installation, mutation: Dict[str, Any], source: Path) -> Path:
    runtime = mutation["runtime"]
    jar = BACKENDS_ROOT / runtime["fileName"]
    if jar.exists() or jar.is_symlink():
        _verify_backend_file(runtime, jar)
        return jar
    build = Path(tempfile.mkdtemp(prefix=".mutation-build.", dir=BACKENDS_ROOT))
    try:
        source_root = build / "source"
        classes_root = build / "classes"
        classes_root.mkdir(mode=0o700)
        source_root.mkdir(mode=0o700)
        with tarfile.open(source, "r:gz") as tar:
            root_name = Path(tar.getmembers()[0].name).parts[0]
            for member in tar:
                if member.issym() or member.islnk():
                    raise fail("mutation source contains a symlink")
                relative = _safe_member(member, root_name)
                if relative is not None:
                    _extract_entry(tar, member, source_root / relative)
        sources = sorted(p for p in (source_root / "src" / "mutate4java").rglob("*.java") if p.is_file())
        if len(sources) != runtime["sourceFileCount"]:
            raise fail("mutation source count mismatch")
        javac = [str(installation.java_home / "bin" / "javac"), "-encoding", "UTF-8", "--release", "17", "-proc:none",
                 "-classpath", "", "-sourcepath", "", "-d", str(classes_root), *map(str, sources)]
        if installation.run(javac, cwd=build) != 0:
            raise fail("mutation backend compilation failed")
        classes = [p for p in classes_root.rglob("*.class") if p.is_file()]
        if len(classes) != runtime["classFileCount"]:
            raise fail("mutation class count mismatch")
        temporary_jar = build / runtime["fileName"]
        jar_tool = [str(installation.java_home / "bin" / "jar"), "--create", "--file", str(temporary_jar),
                    "--date=2000-01-01T00:00:00Z", "--main-class", runtime["mainClass"], "-C", str(classes_root), "."]
        if installation.run(jar_tool, cwd=build) != 0:
            raise fail("mutation backend jar creation failed")
        os.chmod(temporary_jar, 0o600)
        _verify_backend_file(runtime, temporary_jar)
        os.replace(temporary_jar, jar)
    finally:
        shutil.rmtree(build, ignore_errors=True)
    _verify_backend_file(runtime, jar)
    return jar


def backends(installation: Installation) -> Dict[str, Path]:
    _private_directory(BACKENDS_ROOT)
    document = _backend_document()
    artifacts = document["artifacts"]
    mutation = document["mutationBackend"]
    paths = {
        "jacoco-agent": _install_backend_file(artifacts["jacoco-agent"]),
        "jacoco-cli": _install_backend_file(artifacts["jacoco-cli"]),
    }
    source = _install_backend_file(mutation["sourceArchive"])
    paths["mutate4java"] = _build_mutation_jar(installation, mutation, source)
    return paths


# --------------------------------------------------------------------- m2

def populate_m2(installation: Installation) -> None:
    """One online run through compile, one real test (resolves the Surefire provider) and package."""

    _private_directory(M2_ROOT)
    if M2_MARKER.is_file():
        return
    argv = [str(installation.maven_binary), "-B", "-ntp", "-q", f"-Dmaven.repo.local={M2_ROOT}",
            "-Dtest=CanonicalDecimalTest", "-Dsurefire.failIfNoSpecifiedTests=false", "package"]
    if installation.run(argv) != 0:
        raise fail("online Maven resolution failed")
    M2_MARKER.write_text("populated by scripts/toolchain.py m2\n", encoding="utf-8")
    os.chmod(M2_MARKER, 0o600)


# -------------------------------------------------------------- pit probe

def pit_probe() -> None:
    _private_directory(PIT_PROBE_ROOT)
    lines = PIT_MANIFEST.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "sentinel-java-pit-artifacts-v1":
        raise fail("PIT probe manifest header is invalid")
    for line in lines[1:]:
        role, version, size, sha256, file_name, url, license_name, license_url = line.split("\t")
        if not re.fullmatch(r"[a-z0-9-]+", role) or not re.fullmatch(r"[0-9.]+", version):
            raise fail(f"PIT probe manifest entry is invalid: {role}")
        if not re.fullmatch(r"[a-z0-9.-]+\.jar", file_name) or not url.startswith("https://repo.maven.apache.org/maven2/") or not url.endswith("/" + file_name):
            raise fail(f"PIT probe artifact is invalid: {file_name}")
        if not license_name or not license_url.startswith("https://"):
            raise fail(f"PIT probe license is invalid: {file_name}")
        destination = PIT_PROBE_ROOT / file_name
        if destination.exists() or destination.is_symlink():
            if destination.stat().st_size != int(size) or _sha256_file(destination) != sha256:
                raise fail(f"PIT probe artifact checksum mismatch: {file_name}")
            continue
        _fetch(url, destination, int(size), sha256)


# ---------------------------------------------------------------- commands

def _maven_arguments(arguments: List[str]) -> List[str]:
    for argument in arguments:
        if argument in MAVEN_FLAGS or argument in MAVEN_PHASES:
            continue
        if argument.startswith(("-Dtest=", "-Dit.test=")) and TEST_SELECTOR.match(argument.split("=", 1)[1]):
            continue
        raise fail(f"Maven argument is not allowed: {argument}")
    return arguments


def command_mvn(arguments: List[str]) -> int:
    checked = _maven_arguments(arguments)
    installation = Installation(check_version=True)
    argv = [str(installation.maven_binary), "-o", "-B", "-ntp", f"-Dmaven.repo.local={M2_ROOT}", *checked]
    return installation.execute(argv)


def command_java(arguments: List[str]) -> int:
    installation = Installation(check_version=True)
    return installation.execute([str(installation.java_binary), *arguments])


def command_deps(arguments: List[str]) -> int:
    """deps PROJECT: build a private copy without SENTINEL-owned entries and resolve online into PROJECT/.sentinel-m2."""

    if len(arguments) != 1:
        raise fail("usage: toolchain.py deps PROJECT")
    project = Path(arguments[0])
    if not project.is_absolute() or project.is_symlink() or not project.is_dir() or not (project / "pom.xml").is_file():
        raise fail(f"project root with pom.xml is required: {project}")
    project = project.resolve()
    installation = Installation()
    repository = project / ".sentinel-m2"
    repository.mkdir(mode=0o700, exist_ok=True)
    os.chmod(repository, 0o700)
    build = Path(tempfile.mkdtemp(prefix="sentinel-java-deps."))
    try:
        def ignore(directory: str, names: List[str]) -> List[str]:
            if Path(directory) == project:
                return [n for n in names if n == ".git" or n.startswith(".sentinel") or n in ("sentinel.workspace.json", "sentinel.config.json", "target")]
            return []
        copy = build / "project"
        shutil.copytree(project, copy, symlinks=False, ignore=ignore)
        argv = [str(installation.maven_binary), "-B", "-ntp", f"-Dmaven.repo.local={repository}", "test"]
        os.umask(CHILD_UMASK)
        return installation.run(argv, cwd=copy)
    finally:
        shutil.rmtree(build, ignore_errors=True)


def command_doctor() -> int:
    installation = Installation()
    document = _backend_document()
    artifacts = document["artifacts"]
    mutation = document["mutationBackend"]
    for name in ("jacoco-agent", "jacoco-cli"):
        _verify_backend_file(artifacts[name], BACKENDS_ROOT / artifacts[name]["fileName"])
    _verify_backend_file(mutation["sourceArchive"], BACKENDS_ROOT / mutation["sourceArchive"]["fileName"])
    _verify_backend_file(mutation["runtime"], BACKENDS_ROOT / mutation["runtime"]["fileName"])
    print(json.dumps({
        "schemaVersion": "sentinel-java-doctor-v1",
        "passed": True,
        "platform": installation.java["platform"],
        "java": installation.java["version"],
        "maven": installation.maven["version"],
        "jacoco": artifacts["jacoco-agent"]["version"],
        "jacocoAgentSha256": artifacts["jacoco-agent"]["sha256"],
        "jacocoCliSha256": artifacts["jacoco-cli"]["sha256"],
        "mutate4javaCommit": mutation["commit"],
        "mutate4javaArchiveSha256": mutation["gitArchiveSha256"],
        "mutate4javaSourceArchiveSha256": mutation["sourceArchive"]["sha256"],
        "mutate4javaJarSha256": mutation["runtime"]["sha256"],
        "mutationBackendStatus": mutation["status"],
    }, separators=(",", ":")))
    return 0


def command_paths() -> int:
    installation = Installation()
    print(json.dumps({
        "platform": installation.java["platform"],
        "javaHome": str(installation.java_home),
        "mavenHome": str(installation.maven_home),
        "repository": str(M2_ROOT),
        "backends": str(BACKENDS_ROOT),
    }, separators=(",", ":")))
    return 0


def command_setup() -> int:
    installation = bootstrap()
    backends(installation)
    populate_m2(installation)
    if installation.run([str(installation.maven_binary), "-o", "-B", "-ntp", "-q", f"-Dmaven.repo.local={M2_ROOT}", "compile"]) != 0:
        raise fail("offline compile failed")
    if command_doctor() != 0:
        raise fail("doctor failed")
    print("sentinel-tool: java checker ready", file=sys.stderr)
    return 0


def command_describe(key: str) -> int:
    """Maintenance: download and extract another platform's JDK archive and print its fingerprints."""

    entry = select("java", key)
    archive = _download_archive(entry)
    with tempfile.TemporaryDirectory(prefix="describe-java-") as directory:
        tree = Path(directory) / "tree"
        extract(archive, entry["archiveRoot"], tree)
        relative = toolchain_lock.java_home_relative(entry)
        home = tree / relative if relative else tree
        output = {
            "archiveSize": entry["archiveSize"],
            "binarySha256": _sha256_file(home / "bin" / "java"),
            "installedTreeSha256": toolchain_lock._tree_digest(tree),
        }
    print(json.dumps(output, indent=2, sort_keys=True))
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if not arguments:
        raise fail(USAGE)
    mode, rest = arguments[0], arguments[1:]
    simple = {
        "platform": lambda: print(platform_key()) or 0,
        "bootstrap": lambda: print(f"verified java: {bootstrap().java_home.relative_to(REPOSITORY_ROOT)}") or 0,
        "backends": lambda: print(json.dumps({k: str(v.relative_to(REPOSITORY_ROOT)) for k, v in backends(Installation()).items()})) or 0,
        "m2": lambda: populate_m2(Installation()) or 0,
        "pit-probe": lambda: pit_probe() or 0,
        "setup": command_setup,
        "doctor": command_doctor,
        "paths": command_paths,
    }
    if mode in simple:
        return simple[mode]()
    if mode == "mvn":
        return command_mvn(rest)
    if mode == "mvn-or-deps":
        # scripts/mvn.sh keeps both roles it always had: `deps PROJECT` and offline Maven.
        return command_deps(rest[1:]) if rest[:1] == ["deps"] else command_mvn(rest)
    if mode == "java":
        return command_java(rest)
    if mode == "deps":
        return command_deps(rest)
    if mode == "describe":
        if len(rest) != 1:
            raise fail("usage: toolchain.py describe PLATFORM")
        return command_describe(rest[0])
    raise fail(USAGE)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ToolchainError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)
