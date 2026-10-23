"""Build the projected Android client with preinstalled, explicitly fixed tools."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import tempfile
import time
import zipfile
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from verify_projection import verify

PACKAGE = "com.jikestardy.flovvy"
DELIVERABLES = ("flovvy.apk", "flovvy.apk.sha256", "build-manifest.json")
TOOL_OUTPUT_BYTES = 65_536
TOOL_TIMEOUT_SECONDS = 60.0
TOOL_TERM_SECONDS = 2.0
TOOL_KILL_SECONDS = 2.0


def version_code(value: str) -> int:
    if re.fullmatch(r"[1-9][0-9]{0,9}", value) is None or int(value) > 2_100_000_000:
        raise ValueError("VERSION_CODE")
    return int(value)


def signing_inputs(env: Mapping[str, str]) -> tuple[bytes, str, str]:
    encoded = env.get("ANDROID_SIGNING_P12_BASE64", "")
    password = env.get("ANDROID_SIGNING_PASSWORD", "")
    certificate = env.get("ANDROID_SIGNING_CERT_SHA256", "").replace(":", "").lower()
    if not encoded or not password or not certificate:
        raise ValueError("MISSING_SIGNING")
    if (
        len(encoded) > 48_000
        or not 12 <= len(password) <= 256
        or any(ord(char) < 33 or ord(char) > 126 for char in password)
        or re.fullmatch(r"[a-f0-9]{64}", certificate) is None
    ):
        raise ValueError("SIGNING_FORMAT")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("SIGNING_FORMAT") from None
    if not 512 <= len(data) <= 32_768:
        raise ValueError("SIGNING_FORMAT")
    return data, password, certificate


def _child_env(env: Mapping[str, str]) -> dict[str, str]:
    result = {
        "PATH": env.get("PATH", os.defpath),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "TZ": "UTC",
    }
    for name in ("JAVA_HOME", "ANDROID_SIGNING_PASSWORD"):
        if name in env:
            result[name] = env[name]
    return result


def _group_exists(process: subprocess.Popen[bytes]) -> bool:
    process.poll()  # Reap the direct child, without confusing its exit with group settlement.
    try:
        os.killpg(process.pid, 0)
    except ProcessLookupError:
        return False
    except OSError:
        raise ValueError("TOOL_CLEANUP_PENDING") from None
    return True


def _terminate(process: subprocess.Popen[bytes]) -> None:
    """Settle our new process group, including children of an exited launcher."""
    for action, grace in ((signal.SIGTERM, TOOL_TERM_SECONDS), (signal.SIGKILL, TOOL_KILL_SECONDS)):
        if not _group_exists(process):
            return
        try:
            os.killpg(process.pid, action)
        except ProcessLookupError:
            process.poll()
            return
        except OSError:
            raise ValueError("TOOL_CLEANUP_PENDING") from None
        deadline = time.monotonic() + grace
        while _group_exists(process):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(0.01, remaining))
    if _group_exists(process):
        raise ValueError("TOOL_CLEANUP_PENDING")


class ToolCleanupError(ValueError):
    """Keep both safe categories when cleanup fails after an operational failure."""

    def __init__(self, primary: ValueError, cleanup: ValueError) -> None:
        self.primary = primary
        self.cleanup = cleanup
        super().__init__(str(primary) + "__" + str(cleanup))


def _capture_output(process: subprocess.Popen[bytes], capture: BinaryIO) -> str:
    if process.stdout is None:
        raise ValueError("TOOL_START_FAILED")
    descriptor = process.stdout.fileno()
    os.set_blocking(descriptor, False)
    total = 0
    eof = False
    deadline = time.monotonic() + TOOL_TIMEOUT_SECONDS
    with selectors.DefaultSelector() as selector:
        selector.register(descriptor, selectors.EVENT_READ)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError("TOOL_TIMEOUT")
            events = [] if eof else selector.select(min(0.05, remaining))
            if events:
                try:
                    chunk = os.read(descriptor, min(8192, TOOL_OUTPUT_BYTES - total + 1))
                except BlockingIOError:
                    continue
                if not chunk:
                    eof = True
                    selector.unregister(descriptor)
                else:
                    room = TOOL_OUTPUT_BYTES - total
                    capture.write(chunk[:room])
                    total += min(len(chunk), room)
                    if len(chunk) > room:
                        raise ValueError("TOOL_OUTPUT_LIMIT")
            code = process.poll()
            if code is not None and (eof or not events):
                if code != 0:
                    raise ValueError("TOOL_FAILED")
                break
            if eof:
                time.sleep(min(0.01, remaining))
    capture.seek(0)
    try:
        return capture.read(TOOL_OUTPUT_BYTES).decode("utf-8", errors="strict")
    except UnicodeError:
        raise ValueError("TOOL_OUTPUT_FORMAT") from None


def run_tool(
    command: list[str], cwd: Path, env: Mapping[str, str], *, public_compiler: bool = False
) -> str:
    """Bounded tools; optional public compiler diagnostics cannot admit secret context."""
    if public_compiler and (
        not command
        or Path(command[0]).name not in {"java", "javac", "aapt2", "d8", "zipalign"}
        or set(env) - {"JAVA_HOME", "PATH"}
    ):
        raise ValueError("DIAGNOSTICS_CONTEXT")
    with tempfile.TemporaryFile() as capture:
        try:
            process = subprocess.Popen(
                command,
                cwd=cwd,
                env=_child_env(env),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        except OSError:
            raise ValueError("TOOL_START_FAILED") from None
        primary: BaseException | None = None
        result = ""
        try:
            result = _capture_output(process, capture)
        except OSError:
            primary = ValueError("TOOL_IO")
        except BaseException as error:  # noqa: BLE001 - settle owned processes before propagating cancellation.
            primary = error
        try:
            try:
                _terminate(process)
            except ValueError as cleanup:
                if isinstance(primary, ValueError):
                    raise ToolCleanupError(primary, cleanup) from None
                if primary is not None:
                    raise BaseExceptionGroup("TOOL_CLEANUP_PENDING", [primary, cleanup]) from None
                raise
            if primary is not None:
                if (
                    public_compiler
                    and isinstance(primary, ValueError)
                    and str(primary) == "TOOL_FAILED"
                ):
                    # Legacy runner commands can start inside a line. Remove all
                    # command delimiter characters before applying the output bound.
                    capture.seek(0)
                    text = capture.read(TOOL_OUTPUT_BYTES).decode("utf-8", errors="replace")
                    escaped = (
                        json.dumps(text, ensure_ascii=True)
                        .replace("#", "\\u0023")
                        .replace(":", "\\u003a")
                    )
                    print("Android public compiler diagnostic: " + escaped[:16_384], flush=True)
                raise primary
            return result
        finally:
            if process.stdout is not None:
                process.stdout.close()


def _sha(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(65_536):
            value.update(data)
    return value.hexdigest()


def _properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("#") or "=" not in line:
            continue
        key, value = (item.strip() for item in line.split("=", 1))
        if key in result:
            raise ValueError("TOOLCHAIN")
        result[key] = value
    return result


@dataclass(frozen=True)
class Toolchain:
    sdk: Path
    jdk: Path
    android_jar: Path
    lambda_stubs: Path
    commands: dict[str, Path]
    java_version: str
    properties: dict[str, str]


def toolchain(env: Mapping[str, str]) -> Toolchain:
    try:
        sdk = Path(env["ANDROID_HOME"]).resolve(strict=True)
        jdk = Path(env["JAVA_HOME_17_X64"]).resolve(strict=True)
        platform = sdk / "platforms/android-36"
        build_tools = sdk / "build-tools/36.0.0"
        platform_properties = _properties(platform / "source.properties")
        build_properties = _properties(build_tools / "source.properties")
        if (
            platform_properties.get("Pkg.Revision") != "2"
            or platform_properties.get("AndroidVersion.ApiLevel") != "36"
            or build_properties.get("Pkg.Revision") != "36.0.0"
        ):
            raise ValueError("TOOLCHAIN")
        commands = {name: build_tools / name for name in ("aapt2", "d8", "zipalign", "apksigner")}
        commands.update({name: jdk / "bin" / name for name in ("java", "javac")})
        android_jar = platform / "android.jar"
        lambda_stubs = build_tools / "core-lambda-stubs.jar"
        for path in [
            *commands.values(),
            android_jar,
            lambda_stubs,
            build_tools / "lib/d8.jar",
            build_tools / "lib/apksigner.jar",
        ]:
            if not path.is_file():
                raise ValueError("TOOLCHAIN")
        if any(not os.access(path, os.X_OK) for path in commands.values()):
            raise ValueError("TOOLCHAIN")
        child_env = {"JAVA_HOME": str(jdk), "PATH": str(jdk / "bin") + os.pathsep + os.defpath}
        java_version = run_tool([str(commands["java"]), "-version"], jdk, child_env)
        javac_version = run_tool([str(commands["javac"]), "-version"], jdk, child_env)
        if not re.search(r'version "17\.', java_version) or not javac_version.startswith(
            "javac 17."
        ):
            raise ValueError("TOOLCHAIN")
        return Toolchain(
            sdk,
            jdk,
            android_jar,
            lambda_stubs,
            commands,
            java_version.strip(),
            {
                "platform_revision": "2",
                "api": "36",
                "build_tools_revision": "36.0.0",
                "javac_version": javac_version.strip(),
            },
        )
    except (KeyError, OSError, UnicodeError):
        raise ValueError("TOOLCHAIN") from None


def _zip_name(name: str) -> bool:
    return (
        not name.startswith(("/", "\\"))
        and "\\" not in name
        and all(part not in {"", ".", ".."} for part in name.rstrip("/").split("/"))
    )


def add_dex(resources: Path, dex: Path, output: Path) -> None:
    inputs = sorted(dex.iterdir())
    if (
        not inputs
        or not (dex / "classes.dex").is_file()
        or any(
            path.is_symlink()
            or not path.is_file()
            or re.fullmatch(r"classes(?:(?:[2-9]|[1-9][0-9]+))?\.dex", path.name) is None
            for path in inputs
        )
    ):
        raise ValueError("DEX_CONTENTS")
    indices = {1 if path.name == "classes.dex" else int(path.name[7:-4]) for path in inputs}
    if indices != set(range(1, len(inputs) + 1)):
        raise ValueError("DEX_CONTENTS")
    with zipfile.ZipFile(resources) as archive:
        names = archive.namelist()
        if (
            len(names) != len(set(names))
            or not {"AndroidManifest.xml", "resources.arsc"}.issubset(names)
            or any(not _zip_name(name) or name.endswith(".dex") for name in names)
        ):
            raise ValueError("ZIP_CONTENTS")
        with zipfile.ZipFile(output, "w") as result:
            for name in sorted(names):
                info = archive.getinfo(name)
                info.date_time = (2020, 1, 1, 0, 0, 0)
                result.writestr(info, archive.read(name))
            for path in inputs:
                info = zipfile.ZipInfo(path.name, (2020, 1, 1, 0, 0, 0))
                result.writestr(info, path.read_bytes())


def _clear_outputs(output: Path) -> None:
    for name in DELIVERABLES:
        path = output / name
        if path.is_dir() and not path.is_symlink():
            raise ValueError("OUTPUT_PATH")
        path.unlink(missing_ok=True)


def _output_path(output: Path, env: Mapping[str, str]) -> Path:
    try:
        temporary = Path(env["RUNNER_TEMP"]).resolve(strict=True)
        if output.is_symlink():
            raise ValueError("OUTPUT_PATH")
        resolved = output.resolve()
        if resolved == temporary or not resolved.is_relative_to(temporary):
            raise ValueError("OUTPUT_PATH")
        output.mkdir(parents=True, exist_ok=True)
        return resolved
    except (KeyError, OSError):
        raise ValueError("OUTPUT_PATH") from None


def _verify_apk(
    package_info: str, signature: str, expected_code: int, expected_version: str, expected_cert: str
) -> None:
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
        package_info,
        re.MULTILINE,
    )
    permissions = re.findall(
        r"^uses-permission(?:-sdk-\d+)?: name='([^']+)'", package_info, re.MULTILINE
    )
    certificates = re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", signature, re.MULTILINE
    )
    if (
        package is None
        or package.group(1) != PACKAGE
        or package.group(2) != str(expected_code)
        or package.group(3) != expected_version
        or "sdkVersion:'36'" not in package_info
        or "targetSdkVersion:'36'" not in package_info
        or set(permissions) != {"android.permission.INTERNET"}
        or "application-debuggable" in package_info
        or "application-testOnly" in package_info
        or "launchable-activity:" not in package_info
        or len(certificates) != 1
        or certificates[0].lower() != expected_cert
    ):
        raise ValueError("APK_VERIFICATION")


def build(
    root: Path,
    output: Path,
    code: int,
    *,
    compile_only: bool = False,
    public_compiler_diagnostics: bool = False,
) -> dict[str, object]:
    if type(code) is not int or version_code(str(code)) != code:
        raise ValueError("VERSION_CODE")
    output = _output_path(output, os.environ)
    _clear_outputs(output)
    try:
        if public_compiler_diagnostics and not compile_only:
            raise ValueError("DIAGNOSTICS_MODE")
        provenance = verify(root)
        key, password, certificate = (b"", "", "") if compile_only else signing_inputs(os.environ)
        tools = toolchain(os.environ)
        revision = os.environ.get("GITHUB_SHA", "")
        if re.fullmatch(r"[a-f0-9]{40}", revision) is None:
            raise ValueError("SOURCE_REVISION")
        commands: list[list[str]] = []
        env = {
            "JAVA_HOME": str(tools.jdk),
            "PATH": str(tools.jdk / "bin") + os.pathsep + os.defpath,
        }
        android = root / "clients/android"
        with tempfile.TemporaryDirectory(
            prefix="flovvy-build-", dir=os.environ["RUNNER_TEMP"]
        ) as directory:
            work = Path(directory)
            work.chmod(0o700)

            def invoke(name: str, args: list[str], *, secret: bool = False) -> str:
                command = [str(tools.commands[name]), *args]
                commands.append(command)
                print("Android build stage: " + name, flush=True)
                child = {**env, "ANDROID_SIGNING_PASSWORD": password} if secret else env
                if public_compiler_diagnostics:
                    return run_tool(command, work, child, public_compiler=True)
                return run_tool(command, work, child)

            generated, classes, dex, harness = (
                work / name for name in ("generated", "classes", "dex", "harness")
            )
            for path in (generated, classes, dex, harness):
                path.mkdir()
            java = android / "src/main/java"
            pure = [
                java / "com/jikestardy/flovvy" / name
                for name in ("SessionState.java", "HostAccess.java", "TextDraft.java")
            ]
            invoke(
                "javac",
                [
                    "-encoding",
                    "UTF-8",
                    "-source",
                    "8",
                    "-target",
                    "8",
                    "-proc:none",
                    "-d",
                    str(harness),
                    *map(str, pure),
                    str(android / "tests/SessionStateTest.java"),
                ],
            )
            invoke("java", ["-cp", str(harness), PACKAGE + ".SessionStateTest"])
            invoke(
                "aapt2",
                ["compile", "--dir", str(android / "src/main/res"), "-o", str(work / "res.zip")],
            )
            version = "0.1.0." + revision[:12]
            invoke(
                "aapt2",
                [
                    "link",
                    "-I",
                    str(tools.android_jar),
                    "--manifest",
                    str(android / "src/main/AndroidManifest.xml"),
                    "--java",
                    str(generated),
                    "--min-sdk-version",
                    "36",
                    "--target-sdk-version",
                    "36",
                    "--version-code",
                    str(code),
                    "--version-name",
                    version,
                    "-0",
                    "arsc",
                    "-o",
                    str(work / "resources.apk"),
                    str(work / "res.zip"),
                ],
            )
            build_info = generated / "com/jikestardy/flovvy/BuildInfo.java"
            build_info.parent.mkdir(parents=True, exist_ok=True)
            build_info.write_text(
                "package "
                + PACKAGE
                + '; public final class BuildInfo { public static final String VERSION = "'
                + version
                + '"; public static final String SOURCE = "'
                + str(provenance["implementation_sha"])
                + '"; }\n',
                encoding="utf-8",
            )
            sources = sorted([*java.rglob("*.java"), *generated.rglob("*.java")])
            invoke(
                "javac",
                [
                    "-encoding",
                    "UTF-8",
                    "-source",
                    "8",
                    "-target",
                    "8",
                    "-bootclasspath",
                    os.pathsep.join((str(tools.android_jar), str(tools.lambda_stubs))),
                    "-proc:none",
                    "-g:none",
                    "-d",
                    str(classes),
                    *map(str, sources),
                ],
            )
            with zipfile.ZipFile(work / "classes.jar", "w") as archive:
                for path in sorted(classes.rglob("*.class")):
                    info = zipfile.ZipInfo(
                        path.relative_to(classes).as_posix(), (2020, 1, 1, 0, 0, 0)
                    )
                    archive.writestr(info, path.read_bytes())
            invoke(
                "d8",
                [
                    "--release",
                    "--min-api",
                    "36",
                    "--lib",
                    str(tools.android_jar),
                    "--output",
                    str(dex),
                    str(work / "classes.jar"),
                ],
            )
            add_dex(work / "resources.apk", dex, work / "unsigned.apk")
            invoke(
                "zipalign",
                ["-P", "16", "-f", "4", str(work / "unsigned.apk"), str(work / "aligned.apk")],
            )
            if compile_only:
                invoke("zipalign", ["-c", "-P", "16", "4", str(work / "aligned.apk")])
                return {
                    "compilation_only": True,
                    "verification": {
                        "java_harness": True,
                        "android_compilation": True,
                        "dex": True,
                        "alignment": True,
                        "signature": False,
                    },
                }
            signing = work / "signing.p12"
            descriptor = os.open(signing, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(key)
            invoke(
                "apksigner",
                [
                    "sign",
                    "--ks",
                    str(signing),
                    "--ks-type",
                    "PKCS12",
                    "--ks-key-alias",
                    "flovvy-android",
                    "--ks-pass",
                    "env:ANDROID_SIGNING_PASSWORD",
                    "--key-pass",
                    "env:ANDROID_SIGNING_PASSWORD",
                    "--v1-signing-enabled",
                    "false",
                    "--v2-signing-enabled",
                    "true",
                    "--v3-signing-enabled",
                    "true",
                    "--v4-signing-enabled",
                    "false",
                    "--out",
                    str(work / "signed.apk"),
                    str(work / "aligned.apk"),
                ],
                secret=True,
            )
            signing.unlink()
            invoke("zipalign", ["-c", "-P", "16", "4", str(work / "signed.apk")])
            signature = invoke(
                "apksigner", ["verify", "--verbose", "--print-certs", str(work / "signed.apk")]
            )
            package_info = invoke("aapt2", ["dump", "badging", str(work / "signed.apk")])
            _verify_apk(package_info, signature, code, version, certificate)
            with zipfile.ZipFile(work / "signed.apk") as archive:
                names = archive.namelist()
                if (
                    len(names) != len(set(names))
                    or not {"classes.dex", "AndroidManifest.xml", "resources.arsc"}.issubset(names)
                    or any(not _zip_name(name) for name in names)
                ):
                    raise ValueError("ZIP_CONTENTS")
                if archive.getinfo("resources.arsc").compress_type != zipfile.ZIP_STORED:
                    raise ValueError("ZIP_CONTENTS")
            digest = _sha(work / "signed.apk")
            replacements = [
                (str(work), "<build>"),
                (str(root), "<source>"),
                (str(tools.sdk), "<sdk>"),
                (str(tools.jdk), "<jdk>"),
            ]

            def redact(value: str) -> str:
                for before, after in replacements:
                    value = value.replace(before, after)
                return value

            manifest: dict[str, object] = {
                "schema": 1,
                "requirement": "REQ-20261022-4919F1",
                "provenance": provenance,
                "projection_sha": revision,
                "source_manifest_sha256": _sha(root / "android-source.json"),
                "run_id": os.environ.get("GITHUB_RUN_ID", ""),
                "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
                "image_os": os.environ.get("ImageOS", ""),
                "image_version": os.environ.get("ImageVersion", ""),
                "python": sys.version.split()[0],
                "java": tools.java_version,
                "sdk": tools.properties,
                "tools_sha256": {name: _sha(path) for name, path in tools.commands.items()},
                "libraries_sha256": {
                    "android.jar": _sha(tools.android_jar),
                    "core-lambda-stubs.jar": _sha(tools.lambda_stubs),
                    "d8.jar": _sha(tools.sdk / "build-tools/36.0.0/lib/d8.jar"),
                    "apksigner.jar": _sha(tools.sdk / "build-tools/36.0.0/lib/apksigner.jar"),
                },
                "commands": [[redact(arg) for arg in command] for command in commands],
                "package": PACKAGE,
                "version_code": code,
                "version_name": version,
                "min_sdk": 36,
                "target_sdk": 36,
                "certificate_sha256": certificate,
                "apk_sha256": digest,
                "verification": {
                    "signature": True,
                    "alignment": True,
                    "compiled_manifest": True,
                    "java_harness": True,
                },
                "device_voice_acceptance": False,
            }
            (work / "flovvy.apk.sha256").write_text(digest + "  flovvy.apk\n", encoding="ascii")
            (work / "build-manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            os.replace(work / "signed.apk", output / "flovvy.apk")
            for name in DELIVERABLES[1:]:
                os.replace(work / name, output / name)
            return manifest
    except BaseException:
        _clear_outputs(output)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version-code", type=version_code, required=True)
    parser.add_argument(
        "--compile-only", action="store_true", help="Verify compilation without signing or delivery"
    )
    parser.add_argument(
        "--public-compiler-diagnostics",
        action="store_true",
        help="Allow bounded escaped public tool errors; requires --compile-only",
    )
    args = parser.parse_args()
    if sys.version_info < (3, 12):  # noqa: UP036 - standalone CI script must check the actual interpreter.
        print("PYTHON_VERSION: requires Python 3.12+")
        return 1
    try:
        build(
            args.source_root.resolve(),
            args.output,
            args.version_code,
            compile_only=args.compile_only,
            public_compiler_diagnostics=args.public_compiler_diagnostics,
        )
    except ValueError as error:
        category = str(error)
        if re.fullmatch(r"[A-Z_]{1,80}", category) is None:
            category = "INVALID_BUILD_INPUT"
        print("ANDROID_BUILD_FAILED: " + category + "; no delivery")
        return 1
    except (OSError, zipfile.BadZipFile):
        print("ANDROID_BUILD_FAILED: BUILD_IO; no delivery")
        return 1
    print(
        "Compilation verified; no APK delivery"
        if args.compile_only
        else "Signed Android APK and sanitized build manifest verified"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
