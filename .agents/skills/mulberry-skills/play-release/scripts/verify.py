#!/usr/bin/env python3
"""Checks an Android release bundle before upload. Prints one line per check, then
ALL CHECKS PASSED, or exits non-zero.

Usage: verify.py <release-dir>   (as left by `release.sh build`)
"""
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]
PACKAGE = "com.subhajit.mulberry"
API = "https://api.mulberry.my/"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def signing_values() -> dict[str, str]:
    values = {}
    for line in (ROOT / "secrets/android/signing.env").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def sha256_lines(output: str) -> set[str]:
    return {m.upper() for m in re.findall(r"SHA256:\s*([0-9A-Fa-f:]{95})", output)}


def main() -> None:
    release = pathlib.Path(sys.argv[1]).resolve()
    results: list[tuple[bool, str]] = []
    check = lambda ok, label: results.append((bool(ok), label))

    props = (ROOT / "apps/mobile/gradle.properties").read_text()
    code = re.search(r"^MULBERRY_VERSION_CODE=(\d+)$", props, re.M)[1]
    name = re.search(r"^MULBERRY_VERSION_NAME=(.+)$", props, re.M)[1].strip()

    bundle = release / "Mulberry.aab"
    check(bundle.exists(), "bundle present")

    # Signed with the upload key Play knows.
    verified = subprocess.run(["jarsigner", "-verify", str(bundle)], capture_output=True, text=True)
    check("jar verified" in verified.stdout, "bundle signature verifies")
    signing = signing_values()
    env = dict(os.environ, MULBERRY_STOREPASS=signing.get("RELEASE_STORE_PASSWORD", ""))
    expected = sha256_lines(subprocess.run(
        ["keytool", "-list", "-v", "-keystore", signing.get("RELEASE_STORE_FILE", ""), "-alias", signing.get("RELEASE_KEY_ALIAS", ""),
         "-storepass:env", "MULBERRY_STOREPASS"], capture_output=True, text=True, env=env).stdout)
    actual = sha256_lines(subprocess.run(["keytool", "-printcert", "-jarfile", str(bundle)], capture_output=True, text=True).stdout)
    check(expected and expected & actual, "signed by the upload key (" + (next(iter(actual))[:23] + "…" if actual else "unsigned") + ")")

    # The manifest the bundle was built from.
    manifest = (release / "AndroidManifest.xml").read_text()
    check(re.search(rf'package="{re.escape(PACKAGE)}"', manifest), f"package = {PACKAGE}")
    check(re.search(rf'android:versionCode="{code}"', manifest), f"versionCode = {code}")
    check(re.search(rf'android:versionName="{re.escape(name)}"', manifest), f"versionName = {name}")
    check(not re.search(r'android:debuggable="true"', manifest), "not debuggable")
    check(not re.search(r'android:usesCleartextTraffic="true"', manifest), "no cleartext traffic")

    # The production environment.
    config = (release / "BuildConfig.java").read_text()
    field = lambda key: (re.search(rf"\b{key}\s*=\s*(.+?);", config) or [None, ""])[1].strip()
    check(field("APPLICATION_ID") == f'"{PACKAGE}"', "application id")
    check(field("DEBUG") in ('false', 'Boolean.parseBoolean("false")'), "release build type")
    check(field("APP_ENVIRONMENT") == '"prod"', "environment = prod")
    check(field("API_BASE_URL") == f'"{API}"', f"api = {API}")
    check(field("ENABLE_DEBUG_MENU") == "false", "debug menu off")
    check(field("VERSION_CODE") == code and field("VERSION_NAME") == f'"{name}"', "BuildConfig version")

    for ok, label in results:
        print(f"  {'ok  ' if ok else 'FAIL'}  {label}")
    if not all(ok for ok, _ in results):
        sys.exit("CHECKS FAILED")
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
