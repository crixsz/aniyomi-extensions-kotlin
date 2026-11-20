#!/usr/bin/env python3
"""Run the Aniyomi extensions inspector one APK at a time.

The inspector loads every extension in a directory in a single JVM. A single
extension that throws while initialising (for example, an extension that does a
DNS lookup in its constructor) aborts the whole run and no metadata is written.
This wrapper inspects each APK in its own process, merges the results, and
removes the APKs that could not be inspected so the index only references
extensions that produced metadata.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

APK_DIR = Path(os.environ.get("APK_DIR", "repo/apk"))
INSPECTOR_JAR = Path(os.environ.get("INSPECTOR_JAR", "Inspector.jar"))
OUTPUT_JSON = Path(os.environ.get("OUTPUT_JSON", "output.json"))
WORKERS = int(os.environ.get("INSPECTOR_WORKERS", "8"))
TIMEOUT = int(os.environ.get("INSPECTOR_TIMEOUT", "120"))

if os.environ.get("JAVA_HOME"):
    JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
else:
    JAVA = os.environ.get("JAVA", "java")


def inspect(apk: Path) -> tuple[Path, dict | None, str | None]:
    with tempfile.TemporaryDirectory(prefix=f"inspector-{apk.stem}-") as tmp:
        work = Path(tmp)
        apk_dir = work / "apk"
        apk_dir.mkdir()
        shutil.copy2(apk, apk_dir / apk.name)
        out = work / "output.json"

        try:
            subprocess.run(
                [
                    JAVA,
                    "-jar",
                    str(INSPECTOR_JAR),
                    str(apk_dir),
                    str(out),
                    str(work / "tmp"),
                ],
                check=True,
                timeout=TIMEOUT,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            with out.open(encoding="utf-8") as f:
                return apk, json.load(f), None
        except subprocess.TimeoutExpired:
            return apk, None, f"timed out after {TIMEOUT}s"
        except subprocess.CalledProcessError as e:
            return apk, None, f"inspector exited with {e.returncode}"
        except Exception as e:  # noqa: BLE001 - report and skip this APK
            return apk, None, str(e)


def main() -> None:
    # Each APK is inspected in isolation so one broken extension cannot abort
    # metadata collection for every other extension.
    apks = sorted(APK_DIR.glob("*.apk"))
    if not apks:
        raise SystemExit(f"No APKs found in {APK_DIR}")

    print(f"Inspecting {len(apks)} APKs with {WORKERS} workers")

    merged: dict = {}
    failed: list[tuple[Path, str]] = []

    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        for apk, data, error in pool.map(inspect, apks):
            if data:
                merged.update(data)
            else:
                failed.append((apk, error or "unknown error"))
                print(f"SKIP {apk.name}: {error}")

    # Remove APKs without metadata so create-repo.py does not look them up.
    for apk, _ in failed:
        apk.unlink(missing_ok=True)

    if not merged:
        raise SystemExit("The inspector could not read any extension")

    with OUTPUT_JSON.open("w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False)

    print(f"Inspected {len(merged)} packages, skipped {len(failed)}")
    if failed:
        print("Skipped:", ", ".join(apk.name for apk, _ in failed))


if __name__ == "__main__":
    main()
