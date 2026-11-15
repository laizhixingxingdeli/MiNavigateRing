#!/usr/bin/env python3
"""Print and record the identity of the debug APK.

CLI only. Importing this module must not start any process and must not
write any file: all side effects live inside main(), which is only invoked
when the module is executed as ``__main__``.
"""

import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

APK_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
SOURCE_PATH = (
    REPO_ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "minavigatering"
    / "probe"
    / "MainActivity.kt"
)
RECORD_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "apk-identity.txt"

EXPECTED_PACKAGE = "com.minavigatering.probe"
EXPECTED_VERSION_CODE = "2"
EXPECTED_VERSION_NAME = "0.2.0"

# Matches the badging first line, e.g.
# package: name='com.minavigatering.probe' versionCode='1' versionName='0.1.0' ...
BADGING_RE = re.compile(
    r"name='([^']*)'\s+versionCode='([^']*)'\s+versionName='([^']*)'"
)


def candidate_sdk_roots():
    """Return SDK root candidates in the documented lookup order.

    Order: ANDROID_HOME, ANDROID_SDK_ROOT, then local.properties sdk.dir (if
    the file exists and declares it).
    """
    roots = []
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_name)
        if value:
            roots.append(Path(value).resolve())

    local_properties = REPO_ROOT / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if line.startswith("sdk.dir"):
                _, _, value = line.partition("=")
                value = value.strip()
                if value:
                    roots.append(Path(value).resolve())

    return roots


def find_aapt():
    """Locate aapt.exe.

    Gathers every ``build-tools/*/aapt.exe`` across all candidate roots, sorts
    the full path strings, and returns the lexicographically last one. Returns
    ``(None, searched_roots)`` if none is found.
    """
    searched = []
    found = []
    for root in candidate_sdk_roots():
        searched.append(str(root))
        build_tools = root / "build-tools"
        if not build_tools.is_dir():
            continue
        for candidate in build_tools.glob("*/aapt.exe"):
            if candidate.is_file():
                found.append(str(candidate))

    if not found:
        return None, searched
    return sorted(found)[-1], searched


def sha256_of(path):
    """Return the lowercase hex SHA-256 of the file's full contents."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    if not APK_PATH.is_file():
        print("FAIL missing apk: {}".format(APK_PATH))
        return 1

    if APK_PATH.stat().st_mtime < SOURCE_PATH.stat().st_mtime:
        print("FAIL stale apk")
        return 1

    aapt, searched = find_aapt()
    if aapt is None:
        print("FAIL aapt not found; searched roots:")
        for root in searched:
            print("  " + root)
        return 2

    try:
        proc = subprocess.run(
            [aapt, "dump", "badging", str(APK_PATH)],
            shell=False,
            capture_output=True,
            text=True,
            timeout=20,
        )
    except subprocess.TimeoutExpired:
        print("FAIL aapt timed out after 20s: {}".format(aapt))
        return 1

    if proc.returncode != 0:
        print("FAIL aapt exit {}".format(proc.returncode))
        print(proc.stdout)
        print(proc.stderr)
        return 1

    lines = proc.stdout.splitlines()
    first_line = lines[0] if lines else ""
    match = BADGING_RE.search(first_line)
    if not match:
        print("FAIL cannot parse badging first line: {!r}".format(first_line))
        return 1

    package, version_code, version_name = match.group(1), match.group(2), match.group(3)

    if package != EXPECTED_PACKAGE:
        print("FAIL package mismatch: {!r} != {!r}".format(package, EXPECTED_PACKAGE))
        return 1
    if version_code != EXPECTED_VERSION_CODE:
        print("FAIL versionCode mismatch: {!r} != {!r}".format(version_code, EXPECTED_VERSION_CODE))
        return 1
    if version_name != EXPECTED_VERSION_NAME:
        print("FAIL versionName mismatch: {!r} != {!r}".format(version_name, EXPECTED_VERSION_NAME))
        return 1

    size = APK_PATH.stat().st_size
    digest = sha256_of(APK_PATH)

    out_lines = [
        "path={}".format(APK_PATH),
        "package={}".format(EXPECTED_PACKAGE),
        "versionCode={}".format(EXPECTED_VERSION_CODE),
        "versionName={}".format(EXPECTED_VERSION_NAME),
        "size={}".format(size),
        "sha256={}".format(digest),
        "OK",
    ]

    RECORD_PATH.parent.mkdir(parents=True, exist_ok=True)
    RECORD_PATH.write_text("\n".join(out_lines) + "\n", encoding="utf-8")

    for line in out_lines:
        print(line)

    return 0


if __name__ == "__main__":
    sys.exit(main())
