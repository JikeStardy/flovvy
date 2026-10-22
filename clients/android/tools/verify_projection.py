"""Verify the Android-only public input tree before executing its builder."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath
from typing import cast

REQUIREMENT = "REQ-20261022-4919F1"
WORKFLOW = ".github/workflows/android-apk.yml"
BASE_FILES = {"README.md", ".gitignore", "android-source.json"}
REQUIRED = {
    WORKFLOW,
    "clients/android/README.md",
    "clients/android/src/main/AndroidManifest.xml",
    "clients/android/src/main/java/com/jikestardy/flovvy/MainActivity.java",
    "clients/android/tools/build_apk.py",
    "clients/android/tools/verify_projection.py",
}


def _object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("MANIFEST_SCHEMA")
        result[key] = value
    return result


def _allowed(name: str) -> bool:
    if name == WORKFLOW:
        return True
    path = PurePosixPath(name)
    return (
        name.startswith("clients/android/")
        and path.as_posix() == name
        and "\\" not in name
        and not path.is_absolute()
        and all(part not in {"", ".", ".."} and not part.startswith(".") for part in path.parts)
        and path.suffix in {".java", ".py", ".xml", ".md"}
        and "__pycache__" not in path.parts
    )


def verify(root: Path) -> dict[str, object]:
    """Return checked provenance; no arbitrary path is read from the manifest."""
    if root.is_symlink() or not root.is_dir():
        raise ValueError("PROJECTION_ROOT")
    manifest_path = root / "android-source.json"
    if manifest_path.is_symlink():
        raise ValueError("SYMLINK")
    try:
        with manifest_path.open("rb") as stream:
            encoded = stream.read(262_145)
        if len(encoded) > 262_144:
            raise ValueError("MANIFEST_SCHEMA")
        raw: object = json.loads(encoded, object_pairs_hook=_object)
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ValueError("MANIFEST_SCHEMA") from None
    if not isinstance(raw, dict):
        raise ValueError("MANIFEST_SCHEMA")  # noqa: TRY004 - invalid external JSON, not a caller type error.
    manifest = cast(dict[str, object], raw)
    if (
        set(manifest) != {"schema", "requirement", "implementation_sha", "baseline_sha", "files"}
        or type(manifest.get("schema")) is not int
        or manifest["schema"] != 1
        or manifest.get("requirement") != REQUIREMENT
        or not all(
            isinstance(manifest.get(key), str) and re.fullmatch(r"[a-f0-9]{40}", str(manifest[key]))
            for key in ("implementation_sha", "baseline_sha")
        )
        or not isinstance(manifest.get("files"), dict)
    ):
        raise ValueError("MANIFEST_SCHEMA")
    files = cast(dict[str, object], manifest["files"])
    if not files or len(files) > 2048:
        raise ValueError("MANIFEST_SCHEMA")
    for name, digest in files.items():
        if not _allowed(name):
            raise ValueError("FORBIDDEN_PATH")
        if not isinstance(digest, str) or re.fullmatch(r"[a-f0-9]{64}", digest) is None:
            raise ValueError("MANIFEST_SCHEMA")
    if not REQUIRED.issubset(files):
        raise ValueError("FILE_SET")
    observed: set[str] = set()
    total = 0
    pending = [root]
    while pending:
        directory = pending.pop()
        for path in directory.iterdir():
            name = path.relative_to(root).as_posix()
            if path.is_symlink():
                raise ValueError("SYMLINK")
            if name == ".git":
                if not (path.is_dir() or path.is_file()):
                    raise ValueError("FILE_SET")
                continue
            if path.is_dir():
                if not any(item.startswith(name + "/") for item in files):
                    raise ValueError("FILE_SET")
                pending.append(path)
                continue
            if not path.is_file():
                raise ValueError("FILE_SET")
            if name in BASE_FILES:
                if path.stat().st_size > 262_144:
                    raise ValueError("FILE_SET")
                continue
            if name not in files:
                raise ValueError("FILE_SET")
            with path.open("rb") as stream:
                data = stream.read(1_048_577)
            total += len(data)
            if len(data) > 1_048_576 or total > 8_388_608:
                raise ValueError("FILE_SET")
            if hashlib.sha256(data).hexdigest() != files[name]:
                raise ValueError("HASH_MISMATCH")
            observed.add(name)
    if observed != set(files):
        raise ValueError("FILE_SET")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    try:
        verify(args.root)
    except (ValueError, OSError) as exc:
        print(str(exc) if isinstance(exc, ValueError) else "PROJECTION_IO")
        return 1
    print("Android projection verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
