"""Behavioral boundaries for the Android-only public build projection."""

from __future__ import annotations

import base64
import hashlib
import importlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))


def projection(root: Path) -> dict[str, object]:
    """Small real tree; compiler/package behavior is tested separately."""
    paths = {
        "clients/android/README.md": b"public build instructions\n",
        "clients/android/src/main/AndroidManifest.xml": b"<manifest/>\n",
        "clients/android/src/main/java/com/jikestardy/flovvy/MainActivity.java": b"class MainActivity{}\n",
        "clients/android/tools/build_apk.py": b"# build input\n",
        "clients/android/tools/verify_projection.py": b"# verifier input\n",
        ".github/workflows/android-apk.yml": b"name: Android\n",
    }
    for name, data in paths.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (root / "README.md").write_text("remote initialization\n")
    (root / ".gitignore").write_text("ignored\n")
    value: dict[str, object] = {
        "schema": 1,
        "requirement": "REQ-20261022-4919F1",
        "implementation_sha": "a" * 40,
        "baseline_sha": "b" * 40,
        "files": {p: hashlib.sha256(data).hexdigest() for p, data in paths.items()},
    }
    (root / "android-source.json").write_text(json.dumps(value))
    return value


def fake_sdk(root: Path, mode: str) -> dict[str, str]:
    """External-tool fixture only: never proves Java compilation or a real APK."""
    sdk, jdk = root / "sdk", root / "jdk"
    platform, tools = sdk / "platforms/android-36", sdk / "build-tools/36.0.0"
    for folder in (platform, tools / "lib", jdk / "bin"):
        folder.mkdir(parents=True, exist_ok=True)
    (platform / "source.properties").write_text("Pkg.Revision=2\nAndroidVersion.ApiLevel=36\n")
    (tools / "source.properties").write_text("Pkg.Revision=36.0.0\n")
    for path in (platform / "android.jar", tools / "lib/d8.jar", tools / "lib/apksigner.jar"):
        path.write_bytes(b"test-only external tool library")
    (tools / "core-lambda-stubs.jar").write_bytes(b"compile-only lambda fixture")
    script = f"""#!{sys.executable}
import json, os, pathlib, shutil, sys, zipfile
tool = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
mode = {mode!r}
if mode == 'requires_lambda_stub':
    with pathlib.Path({str(root / "sdk-commands.jsonl")!r}).open('a') as output:
        output.write(json.dumps({{'tool': tool, 'args': args}}) + '\\n')
def argument(name): return pathlib.Path(args[args.index(name) + 1])
if tool == 'java':
    print('openjdk version "17.0.20"' if args == ['-version'] else 'fixture harness only')
elif tool == 'javac':
    if args == ['-version']: print('javac 17.0.20')
    else:
        if mode == 'requires_lambda_stub' and '-bootclasspath' in args:
            boot = args[args.index('-bootclasspath') + 1].split(os.pathsep)
            if len(boot) != 2 or [pathlib.Path(p).name for p in boot] != ['android.jar', 'core-lambda-stubs.jar'] or not all(pathlib.Path(p).is_file() for p in boot):
                print('fixture missing lambda bootstrap method')
                sys.exit(7)
        target = argument('-d') / 'Fixture.class'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'fixture')
elif tool == 'aapt2':
    if args[:2] == ['dump', 'badging']:
        version = 'wrong' if mode == 'wrong_version_name' else '0.1.0.' + 'c' * 12
        print("package: name='com.jikestardy.flovvy' versionCode='7' versionName='" + version + "'")
        print("sdkVersion:'36'\\ntargetSdkVersion:'36'\\nuses-permission: name='android.permission.INTERNET'\\nlaunchable-activity: name='com.jikestardy.flovvy.MainActivity'")
    else:
        with zipfile.ZipFile(argument('-o'), 'w') as archive:
            if args[0] == 'link':
                archive.writestr('AndroidManifest.xml', b'fixture')
                archive.writestr('resources.arsc', b'fixture', compress_type=zipfile.ZIP_STORED)
elif tool == 'd8':
    if mode == 'compile_failure':
        print('COMPILE_PRIVATE_CANARY')
        sys.exit(7)
    if mode == 'public_diagnostic_failure':
        print('::error::public compiler fixture ##[error]legacy' + chr(27) + '[31m' + chr(13) + 'line')
        print('##[error]::error::' * 2000)
        sys.exit(7)
    (argument('--output') / 'classes.dex').write_bytes(b'dex\\n035\\0fixture')
elif tool == 'zipalign':
    if '-c' not in args: shutil.copyfile(args[-2], args[-1])
elif tool == 'apksigner':
    if args[0] == 'sign':
        if mode == 'sign_failure':
            print(os.environ.get('ANDROID_SIGNING_PASSWORD', ''))
            sys.exit(7)
        shutil.copyfile(args[-1], argument('--out'))
    else: print('Signer #1 certificate SHA-256 digest: ' + 'a' * 64)
"""
    for path in [
        *(tools / name for name in ("aapt2", "d8", "zipalign", "apksigner")),
        jdk / "bin/java",
        jdk / "bin/javac",
    ]:
        path.write_text(script)
        path.chmod(0o700)
    return {
        "RUNNER_TEMP": str(root),
        "ANDROID_HOME": str(sdk),
        "JAVA_HOME_17_X64": str(jdk),
        "ANDROID_SIGNING_P12_BASE64": base64.b64encode(b"fixture only" * 64).decode(),
        "ANDROID_SIGNING_PASSWORD": "fixture-password-only",
        "ANDROID_SIGNING_CERT_SHA256": "a" * 64,
        "GITHUB_SHA": "c" * 40,
    }


class ProjectionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.module = importlib.import_module("verify_projection")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = projection(self.root)

    def test_valid_projection_returns_bound_implementation(self) -> None:
        result = self.module.verify(self.root)
        self.assertEqual(result["implementation_sha"], "a" * 40)

    def test_changed_file_rejected_before_execution(self) -> None:
        (self.root / "clients/android/tools/build_apk.py").write_text("changed\n")
        with self.assertRaisesRegex(ValueError, "HASH_MISMATCH"):
            self.module.verify(self.root)

    def test_unlisted_public_input_is_rejected(self) -> None:
        (self.root / "clients/android/tools/extra.py").write_text("extra\n")
        with self.assertRaisesRegex(ValueError, "FILE_SET"):
            self.module.verify(self.root)

    def test_secret_file_refused_even_if_manifest_lists_it(self) -> None:
        target = self.root / "clients/android/signing.p12"
        target.write_bytes(b"not a key")
        files = self.manifest["files"]
        assert isinstance(files, dict)
        files["clients/android/signing.p12"] = hashlib.sha256(b"not a key").hexdigest()
        (self.root / "android-source.json").write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, "FORBIDDEN_PATH"):
            self.module.verify(self.root)

    def test_symlink_cannot_import_external_content(self) -> None:
        target = self.root / "clients/android/tools/build_apk.py"
        target.unlink()
        target.symlink_to(self.root / "README.md")
        with self.assertRaisesRegex(ValueError, "SYMLINK"):
            self.module.verify(self.root)

    def test_parent_escape_and_self_reference_refused(self) -> None:
        for name in ("../outside.py", "android-source.json", "/absolute.py"):
            with self.subTest(name=name):
                bad = dict(self.manifest)
                bad["files"] = {name: "0" * 64}
                (self.root / "android-source.json").write_text(json.dumps(bad))
                with self.assertRaisesRegex(ValueError, "FORBIDDEN_PATH"):
                    self.module.verify(self.root)

    def test_private_tree_or_unknown_manifest_fields_refused(self) -> None:
        (self.root / "private.txt").write_text("private\n")
        with self.assertRaisesRegex(ValueError, "FILE_SET"):
            self.module.verify(self.root)
        (self.root / "private.txt").unlink()
        self.manifest["api_key"] = "fixture"
        (self.root / "android-source.json").write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, "MANIFEST_SCHEMA"):
            self.module.verify(self.root)

    def test_duplicate_json_key_and_boolean_schema_refused(self) -> None:
        (self.root / "android-source.json").write_text('{"schema":1,"schema":1}')
        with self.assertRaisesRegex(ValueError, "MANIFEST_SCHEMA"):
            self.module.verify(self.root)
        self.manifest["schema"] = True
        (self.root / "android-source.json").write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, "MANIFEST_SCHEMA"):
            self.module.verify(self.root)


class BuilderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.module = importlib.import_module("build_apk")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_version_code_rejects_invalid_android_values(self) -> None:
        for value in ("0", "-1", "1.0", "01", "2100000001", "1;exit"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.module.version_code(value)
        self.assertEqual(self.module.version_code("12"), 12)

    def test_signed_inputs_are_required_and_strictly_decoded(self) -> None:
        with self.assertRaisesRegex(ValueError, "MISSING_SIGNING"):
            self.module.signing_inputs({})
        with self.assertRaisesRegex(ValueError, "SIGNING_FORMAT"):
            self.module.signing_inputs(
                {
                    "ANDROID_SIGNING_P12_BASE64": "%%%",
                    "ANDROID_SIGNING_PASSWORD": "fixture-pass",
                    "ANDROID_SIGNING_CERT_SHA256": "a" * 64,
                }
            )

    def test_duplicate_or_missing_dex_does_not_yield_apk(self) -> None:
        resources = self.root / "resources.apk"
        dex = self.root / "dex"
        dex.mkdir()
        result = self.root / "unsigned.apk"
        with zipfile.ZipFile(resources, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"binary fixture")
            archive.writestr("resources.arsc", b"resources")
            archive.writestr("classes.dex", b"old")
        (dex / "classes.dex").write_bytes(b"new")
        with self.assertRaisesRegex(ValueError, "ZIP_CONTENTS"):
            self.module.add_dex(resources, dex, result)
        self.assertFalse(result.exists())
        (dex / "classes.dex").unlink()
        with self.assertRaisesRegex(ValueError, "DEX_CONTENTS"):
            self.module.add_dex(resources, dex, result)

    def test_dex_packaging_preserves_resources_and_exact_payload(self) -> None:
        resources = self.root / "resources.apk"
        dex = self.root / "dex"
        dex.mkdir()
        result = self.root / "unsigned.apk"
        with zipfile.ZipFile(resources, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"binary fixture")
            archive.writestr("resources.arsc", b"resources", compress_type=zipfile.ZIP_STORED)
        (dex / "classes.dex").write_bytes(b"dex\n035\0fixture")
        self.module.add_dex(resources, dex, result)
        with zipfile.ZipFile(result) as archive:
            self.assertEqual(archive.read("classes.dex"), b"dex\n035\0fixture")
            self.assertEqual(archive.read("resources.arsc"), b"resources")
            self.assertEqual(archive.getinfo("resources.arsc").compress_type, zipfile.ZIP_STORED)

    def test_failed_subprocess_hides_output_and_injection_environment(self) -> None:
        tool = self.root / "failing-tool"
        tool.write_text("#!/bin/sh\nprintf 'secret-fixture'\nexit 7\n")
        tool.chmod(0o700)
        with self.assertRaisesRegex(ValueError, "TOOL_FAILED") as caught:
            self.module.run_tool([str(tool)], self.root, {"JAVA_TOOL_OPTIONS": "bad"})
        self.assertNotIn("secret-fixture", str(caught.exception))
        child = self.root / "env-tool"
        child.write_text("#!/bin/sh\nprintf '%s' \"${JAVA_TOOL_OPTIONS-unset}\"\n")
        child.chmod(0o700)
        output = self.module.run_tool([str(child)], self.root, {"JAVA_TOOL_OPTIONS": "bad"})
        self.assertEqual(output.strip(), "unset")

    def test_output_limit_stops_real_producer_and_bounds_capture_storage(self) -> None:
        marker = self.root / "producer-completed"
        sizes: list[int] = []
        original = tempfile.TemporaryFile

        @contextmanager
        def measured_capture() -> Iterator[BinaryIO]:
            with original() as capture:
                try:
                    yield capture
                finally:
                    sizes.append(os.fstat(capture.fileno()).st_size)

        program = (
            "import os; from pathlib import Path\n"
            "for _ in range(1024): os.write(1, b'x' * 8192)\n"
            f"Path({str(marker)!r}).write_text('all 8 MiB produced')\n"
        )
        with (
            patch.object(self.module.tempfile, "TemporaryFile", measured_capture),
            self.assertRaisesRegex(ValueError, "^TOOL_OUTPUT_LIMIT$"),
        ):
            self.module.run_tool([sys.executable, "-B", "-c", program], self.root, {})
        self.assertLessEqual(max(sizes), 65_536)
        self.assertFalse(marker.exists(), "overflow must stop the producer before it finishes")

    def test_exited_leader_settles_remaining_and_term_resistant_group_children(self) -> None:
        for status, resistant in ((0, False), (0, True), (7, False)):
            with self.subTest(status=status, resistant=resistant):
                suffix = str(status) + "-" + str(resistant)
                ready = self.root / ("ready-" + suffix)
                release = self.root / ("release-" + suffix)
                marker = self.root / ("late-write-" + suffix)
                group_file = self.root / ("group-" + suffix)
                child = (
                    "from pathlib import Path; import signal,time\n"
                    + ("signal.signal(signal.SIGTERM, signal.SIG_IGN)\n" if resistant else "")
                    + f"Path({str(ready)!r}).write_text('ready')\n"
                    + "deadline=time.monotonic()+3\n"
                    + f"while not Path({str(release)!r}).exists() and time.monotonic()<deadline: time.sleep(.01)\n"
                    + f"if Path({str(release)!r}).exists(): Path({str(marker)!r}).write_text('late write')\n"
                )
                parent = (
                    "from pathlib import Path; import os,subprocess,sys,time\n"
                    f"Path({str(group_file)!r}).write_text(str(os.getpgrp()))\n"
                    f"subprocess.Popen([sys.executable,'-B','-c',{child!r}])\n"
                    "deadline=time.monotonic()+1\n"
                    f"while not Path({str(ready)!r}).exists() and time.monotonic()<deadline: time.sleep(.01)\n"
                    f"sys.exit({status})\n"
                )
                try:
                    with patch.object(self.module, "TOOL_TERM_SECONDS", 0.15, create=True):
                        command = [sys.executable, "-B", "-c", parent]
                        if status:
                            with self.assertRaisesRegex(ValueError, "^TOOL_FAILED$"):
                                self.module.run_tool(command, self.root, {})
                        else:
                            self.assertEqual(self.module.run_tool(command, self.root, {}), "")
                    self.assertTrue(ready.exists())
                    release.write_text("released only after run_tool returned")
                    time.sleep(0.1)
                    self.assertFalse(marker.exists())
                    with self.assertRaises(ProcessLookupError):
                        os.killpg(int(group_file.read_text()), 0)
                finally:
                    if group_file.exists():
                        try:
                            os.killpg(int(group_file.read_text()), signal.SIGKILL)
                        except ProcessLookupError:
                            pass

    def test_real_wall_timeout_settles_term_resistant_leader_before_return(self) -> None:
        group_file = self.root / "timed-group"
        program = (
            "from pathlib import Path; import os,signal,time\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            f"Path({str(group_file)!r}).write_text(str(os.getpgrp()))\n"
            "time.sleep(1)\n"
        )
        started = time.monotonic()
        with (
            patch.object(self.module, "TOOL_TIMEOUT_SECONDS", 0.3, create=True),
            patch.object(self.module, "TOOL_TERM_SECONDS", 0.1, create=True),
            self.assertRaisesRegex(ValueError, "^TOOL_TIMEOUT$"),
        ):
            self.module.run_tool([sys.executable, "-B", "-c", program], self.root, {})
        self.assertLess(time.monotonic() - started, 2)
        with self.assertRaises(ProcessLookupError):
            os.killpg(int(group_file.read_text()), 0)

    def test_primary_and_cleanup_failures_are_both_reported_without_output(self) -> None:
        from build_apk import ToolCleanupError

        with (
            patch.object(self.module, "_terminate", side_effect=ValueError("TOOL_CLEANUP_PENDING")),
            self.assertRaisesRegex(
                ToolCleanupError, "^TOOL_FAILED__TOOL_CLEANUP_PENDING$"
            ) as caught,
        ):
            self.module.run_tool(
                [sys.executable, "-B", "-c", "import sys; print('private fixture'); sys.exit(7)"],
                self.root,
                {},
            )
        self.assertEqual(str(caught.exception.primary), "TOOL_FAILED")
        self.assertEqual(str(caught.exception.cleanup), "TOOL_CLEANUP_PENDING")
        self.assertNotIn("private fixture", str(caught.exception))

    def test_cleanup_pending_after_success_prevents_build_delivery(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "ok")
        output = self.root / "delivery"
        output.mkdir()
        for name in self.module.DELIVERABLES:
            (output / name).write_text("stale")
        with (
            patch.dict(os.environ, env, clear=True),
            patch.object(self.module, "_terminate", side_effect=ValueError("TOOL_CLEANUP_PENDING")),
            self.assertRaisesRegex(ValueError, "^TOOL_CLEANUP_PENDING$"),
        ):
            self.module.build(source, output, 7)
        self.assertEqual(list(output.iterdir()), [])

    def test_missing_signing_removes_stale_delivery(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        output = self.root / "delivery"
        output.mkdir()
        for name in ("flovvy.apk", "flovvy.apk.sha256", "build-manifest.json"):
            (output / name).write_text("old delivery")
        with (
            patch.dict(os.environ, {"RUNNER_TEMP": str(self.root)}, clear=True),
            self.assertRaisesRegex(ValueError, "MISSING_SIGNING"),
        ):
            self.module.build(source, output, 7)
        self.assertEqual(list(output.iterdir()), [])

    def test_preflight_requires_real_fixed_tool_versions(self) -> None:
        sdk = self.root / "sdk"
        jdk = self.root / "jdk"
        jdk.mkdir()
        with self.assertRaisesRegex(ValueError, "TOOLCHAIN"):
            self.module.toolchain({"ANDROID_HOME": str(sdk), "JAVA_HOME_17_X64": str(jdk)})

    def test_native_compile_uses_sdk_lambda_stubs_only_on_bootclasspath(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "requires_lambda_stub")
        for name in tuple(env):
            if name.startswith("ANDROID_SIGNING_"):
                del env[name]
        output = self.root / "delivery"
        result = subprocess.run(
            [
                sys.executable,
                "-B",
                str(TOOLS / "build_apk.py"),
                "--compile-only",
                "--source-root",
                str(source),
                "--output",
                str(output),
                "--version-code",
                "7",
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = [
            json.loads(line) for line in (self.root / "sdk-commands.jsonl").read_text().splitlines()
        ]
        compiled = [
            call["args"]
            for call in calls
            if call["tool"] == "javac" and "-bootclasspath" in call["args"]
        ]
        self.assertEqual(len(compiled), 1)
        boot = compiled[0][compiled[0].index("-bootclasspath") + 1].split(os.pathsep)
        self.assertEqual(
            boot,
            [
                str((self.root / "sdk/platforms/android-36/android.jar").resolve()),
                str((self.root / "sdk/build-tools/36.0.0/core-lambda-stubs.jar").resolve()),
            ],
        )
        dex = [call["args"] for call in calls if call["tool"] == "d8"]
        self.assertEqual(len(dex), 1)
        self.assertEqual(dex[0][dex[0].index("--lib") + 1], boot[0])
        self.assertTrue(dex[0][-1].endswith("classes.jar"))
        self.assertNotIn(boot[1], dex[0])
        self.assertEqual(list(output.iterdir()), [])
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_missing_sdk_lambda_stubs_fails_before_any_tool_starts(self) -> None:
        env = fake_sdk(self.root, "requires_lambda_stub")
        (self.root / "sdk/build-tools/36.0.0/core-lambda-stubs.jar").unlink()
        with self.assertRaisesRegex(ValueError, "^TOOLCHAIN$"):
            self.module.toolchain(env)
        self.assertFalse((self.root / "sdk-commands.jsonl").exists())

    def test_all_jdk_harnesses_execute_before_native_compiler(self) -> None:
        """Tool-fixture orchestration only; actual Java requires the real runner."""
        source = self.root / "source"
        source.mkdir()
        manifest = projection(source)
        indexed = manifest["files"]
        assert isinstance(indexed, dict)
        for name in (
            "src/main/java/com/jikestardy/flovvy/AndroidPcmOutput.java",
            "tests/AndroidPcmOutputTest.java",
            "tests/platform/android/media/AudioAttributes.java",
            "tests/platform/android/media/AudioFormat.java",
            "tests/platform/android/media/AudioTrack.java",
        ):
            relative = "clients/android/" + name
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            data = b"// Java input enumeration fixture; not compiled by this fake SDK.\n"
            target.write_bytes(data)
            indexed[relative] = hashlib.sha256(data).hexdigest()
        (source / "android-source.json").write_text(json.dumps(manifest))
        env = fake_sdk(self.root, "requires_lambda_stub")
        for name in tuple(env):
            if name.startswith("ANDROID_SIGNING_"):
                del env[name]
        with patch.dict(os.environ, env, clear=True):
            self.module.build(source, self.root / "delivery", 7, compile_only=True)
        calls = [
            json.loads(line) for line in (self.root / "sdk-commands.jsonl").read_text().splitlines()
        ]
        execution = [
            (index, call["args"][-1])
            for index, call in enumerate(calls)
            if call["tool"] == "java" and "-cp" in call["args"]
        ]
        self.assertEqual(
            [name for _, name in execution],
            [
                "com.jikestardy.flovvy.SessionStateTest",
                "com.jikestardy.flovvy.PcmOutputTest",
                "com.jikestardy.flovvy.AndroidPcmOutputTest",
            ],
        )
        first_android = next(index for index, call in enumerate(calls) if call["tool"] == "aapt2")
        self.assertTrue(all(index < first_android for index, _ in execution))
        compilers = [
            call["args"] for call in calls if call["tool"] == "javac" and "-d" in call["args"]
        ]
        self.assertEqual(len(compilers), 2)
        harness_sources = [value for value in compilers[0] if value.endswith(".java")]
        fixtures = [value for value in harness_sources if "/tests/platform/" in value]
        self.assertEqual(
            {Path(value).name for value in fixtures},
            {"AudioAttributes.java", "AudioFormat.java", "AudioTrack.java"},
        )
        self.assertTrue(any(value.endswith("/AndroidPcmOutput.java") for value in harness_sources))
        self.assertTrue(
            any(value.endswith("/AndroidPcmOutputTest.java") for value in harness_sources)
        )
        self.assertNotIn("-bootclasspath", compilers[0])
        native_sources = [value for value in compilers[1] if value.endswith(".java")]
        self.assertTrue(any(value.endswith("/AndroidPcmOutput.java") for value in native_sources))
        self.assertFalse(any("/tests/" in value for value in native_sources))
        self.assertIn("-bootclasspath", compilers[1])
        self.assertNotIn("-cp", compilers[1])
        self.assertNotEqual(
            compilers[0][compilers[0].index("-d") + 1],
            compilers[1][compilers[1].index("-d") + 1],
        )

    def test_signed_manifest_hashes_compile_only_lambda_library(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "ok")
        output = self.root / "delivery"
        with patch.dict(os.environ, env, clear=True):
            result = self.module.build(source, output, 7)
        libraries = result.get("libraries_sha256")
        assert isinstance(libraries, dict)
        self.assertEqual(
            libraries.get("core-lambda-stubs.jar"),
            hashlib.sha256(b"compile-only lambda fixture").hexdigest(),
        )
        self.assertNotIn(env["ANDROID_SIGNING_PASSWORD"], json.dumps(result))
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_fake_pipeline_only_proves_orchestration_and_manifest_redaction(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "ok")
        output = self.root / "delivery"
        with patch.dict(os.environ, env, clear=True):
            result = self.module.build(source, output, 7)
        self.assertEqual(
            {path.name for path in output.iterdir()},
            {
                "flovvy.apk",
                "flovvy.apk.sha256",
                "build-manifest.json",
            },
        )
        self.assertNotIn(env["ANDROID_SIGNING_PASSWORD"], json.dumps(result))
        self.assertEqual(result["projection_sha"], "c" * 40)
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_compile_only_cli_needs_no_signing_and_publishes_no_delivery(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "ok")
        for name in tuple(env):
            if name.startswith("ANDROID_SIGNING_"):
                del env[name]
        output = self.root / "delivery"
        output.mkdir()
        for name in self.module.DELIVERABLES:
            (output / name).write_text("stale")
        result = subprocess.run(
            [
                sys.executable,
                "-B",
                str(TOOLS / "build_apk.py"),
                "--compile-only",
                "--source-root",
                str(source),
                "--output",
                str(output),
                "--version-code",
                "7",
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for tool in ("javac", "java", "aapt2", "d8", "zipalign"):
            self.assertIn("Android build stage: " + tool, result.stdout)
        self.assertNotIn("Android build stage: apksigner", result.stdout)
        self.assertIn("Compilation verified; no APK delivery", result.stdout)
        self.assertEqual(result.stderr, "")
        self.assertEqual(list(output.iterdir()), [])
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_compile_only_failure_is_safe_and_cannot_deliver(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "compile_failure")
        for name in tuple(env):
            if name.startswith("ANDROID_SIGNING_"):
                del env[name]
        output = self.root / "delivery"
        result = subprocess.run(
            [
                sys.executable,
                "-B",
                str(TOOLS / "build_apk.py"),
                "--compile-only",
                "--source-root",
                str(source),
                "--output",
                str(output),
                "--version-code",
                "7",
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("TOOL_FAILED", result.stdout)
        self.assertNotIn("COMPILE_PRIVATE_CANARY", result.stdout + result.stderr)
        self.assertNotIn("Compilation verified", result.stdout)
        self.assertNotIn("Android build stage: apksigner", result.stdout)
        self.assertEqual(list(output.iterdir()), [])
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_explicit_public_compiler_diagnostics_are_bounded_and_escaped(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "public_diagnostic_failure")
        for name in tuple(env):
            if name.startswith("ANDROID_SIGNING_"):
                del env[name]
        output = self.root / "delivery"
        result = subprocess.run(
            [
                sys.executable,
                "-B",
                str(TOOLS / "build_apk.py"),
                "--compile-only",
                "--public-compiler-diagnostics",
                "--source-root",
                str(source),
                "--output",
                str(output),
                "--version-code",
                "7",
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("TOOL_FAILED", result.stdout)
        diagnostic = [
            line
            for line in result.stdout.splitlines()
            if line.startswith("Android public compiler diagnostic: ")
        ]
        self.assertEqual(len(diagnostic), 1, result.stdout)
        self.assertIn("public compiler fixture", diagnostic[0])
        self.assertNotIn("##[", result.stdout)
        self.assertNotIn("::", result.stdout)
        self.assertIn("\\u0023\\u0023[error]", diagnostic[0])
        self.assertIn("\\u003a\\u003aerror\\u003a\\u003a", diagnostic[0])
        self.assertIn("\\u001b", diagnostic[0])
        self.assertIn("\\r", diagnostic[0])
        self.assertNotIn(chr(27), result.stdout)
        self.assertFalse(any(line.startswith("::") for line in result.stdout.splitlines()))
        self.assertLessEqual(len(diagnostic[0]), 16421)
        self.assertLess(len(result.stdout), 17000)
        self.assertNotIn("Android build stage: apksigner", result.stdout)
        self.assertEqual(result.stderr, "")
        self.assertEqual(list(output.iterdir()), [])
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_public_compiler_diagnostics_cannot_enable_signing_logs(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "sign_failure")
        output = self.root / "delivery"
        output.mkdir()
        for name in self.module.DELIVERABLES:
            (output / name).write_text("stale")
        result = subprocess.run(
            [
                sys.executable,
                "-B",
                str(TOOLS / "build_apk.py"),
                "--public-compiler-diagnostics",
                "--source-root",
                str(source),
                "--output",
                str(output),
                "--version-code",
                "7",
            ],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("DIAGNOSTICS_MODE", result.stdout)
        self.assertNotIn("Android build stage:", result.stdout)
        self.assertNotIn("Android public compiler diagnostic:", result.stdout)
        self.assertNotIn(env["ANDROID_SIGNING_PASSWORD"], result.stdout + result.stderr)
        self.assertEqual(list(output.iterdir()), [])
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])

    def test_failed_signer_cleans_key_and_all_delivery_outputs(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "sign_failure")
        output = self.root / "delivery"
        with (
            patch.dict(os.environ, env, clear=True),
            self.assertRaisesRegex(ValueError, "TOOL_FAILED") as caught,
        ):
            self.module.build(source, output, 7)
        self.assertEqual(list(output.iterdir()), [])
        self.assertEqual(list(self.root.glob("flovvy-build-*")), [])
        self.assertNotIn(env["ANDROID_SIGNING_PASSWORD"], str(caught.exception))

    def test_compiled_version_name_mismatch_prevents_delivery(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        env = fake_sdk(self.root, "wrong_version_name")
        output = self.root / "delivery"
        with (
            patch.dict(os.environ, env, clear=True),
            self.assertRaisesRegex(ValueError, "APK_VERIFICATION"),
        ):
            self.module.build(source, output, 7)
        self.assertEqual(list(output.iterdir()), [])

    def test_cli_rejects_missing_configuration_without_secret_traceback(self) -> None:
        result = subprocess.run(
            [sys.executable, str(TOOLS / "build_apk.py"), "--help"],
            capture_output=True,
            check=False,
            text=True,
        )
        self.assertEqual(result.returncode, 0)
        self.assertNotIn("ANDROID_SIGNING_PASSWORD=", result.stdout)

    def test_cli_reports_safe_failure_category_without_raw_tool_output(self) -> None:
        source = self.root / "source"
        source.mkdir()
        projection(source)
        result = subprocess.run(
            [
                sys.executable,
                "-B",
                str(TOOLS / "build_apk.py"),
                "--source-root",
                str(source),
                "--output",
                str(self.root / "delivery"),
                "--version-code",
                "7",
            ],
            capture_output=True,
            check=False,
            text=True,
            env={"RUNNER_TEMP": str(self.root)},
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("MISSING_SIGNING", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_all_contiguous_multidex_outputs_including_tenth_are_packaged(self) -> None:
        resources, output = self.root / "resources.apk", self.root / "unsigned.apk"
        dex = self.root / "dex"
        dex.mkdir()
        with zipfile.ZipFile(resources, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr("resources.arsc", b"fixture")
        for index in range(1, 11):
            name = "classes.dex" if index == 1 else f"classes{index}.dex"
            (dex / name).write_bytes(b"dex\n035\0fixture")
        self.module.add_dex(resources, dex, output)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read("classes10.dex"), b"dex\n035\0fixture")
            self.assertEqual(
                len([name for name in archive.namelist() if name.endswith(".dex")]), 10
            )


if __name__ == "__main__":
    unittest.main()
