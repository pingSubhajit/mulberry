#!/usr/bin/env python3
"""Check that an iCloud Shortcut link holds the template it was made from.

Usage: verify-link.py <https://www.icloud.com/shortcuts/ID> <template .plist from make-shortcut.py>

Downloads the shared shortcut through iCloud's public record API and compares it with the
template source: name, icon, setup question, trigger, actions and app identity. It also checks
that no Lock Screen from the device that shared it came along. Exits 1 on any mismatch.
"""
import json
import plistlib
import subprocess
import sys

NAME = "Mulberry Sync"


def fetch(url: str) -> bytes:
    # curl, because the python.org Python on macOS ships without CA certificates.
    return subprocess.run(["curl", "-sfL", "--max-time", "30", url], check=True, capture_output=True).stdout


def identities(workflow: dict) -> set:
    """Every (bundle, team) the Shortcut's App Intents and trigger name."""
    found = set()
    for action in workflow["WFWorkflowActions"]:
        descriptor = action["WFWorkflowActionParameters"].get("AppIntentDescriptor")
        if descriptor:
            found.add((descriptor["BundleIdentifier"], descriptor["TeamIdentifier"]))
    for trigger in workflow.get("WFWorkflowTriggers", []):
        app = trigger["WFTriggerSerializedParameters"]["SelectedApps"]
        found.add((app["BundleIdentifier"], app["TeamIdentifier"]))
    return found


def main() -> int:
    if len(sys.argv) != 3 or "/shortcuts/" not in sys.argv[1]:
        print(__doc__.strip().splitlines()[2])
        return 2
    record_id = sys.argv[1].rstrip("/").rsplit("/", 1)[-1]
    record = json.loads(fetch(f"https://www.icloud.com/shortcuts/api/records/{record_id}"))
    fields = record["fields"]
    shared = plistlib.loads(fetch(fields["shortcut"]["value"]["downloadURL"].replace("${f}", "shortcut.plist")))
    with open(sys.argv[2], "rb") as handle:
        template = plistlib.load(handle)

    wallpaper = next(a for a in shared["WFWorkflowActions"] if a["WFWorkflowActionIdentifier"] == "is.workflow.actions.wallpaper.set")
    checks = [
        (f'name is "{NAME}"', fields["name"]["value"] == NAME),
        ("icon matches the template", shared["WFWorkflowIcon"] == template["WFWorkflowIcon"]),
        ("setup question matches the template", shared.get("WFWorkflowImportQuestions") == template.get("WFWorkflowImportQuestions")),
        ("notification trigger is present", len(shared.get("WFWorkflowTriggers", [])) == len(template.get("WFWorkflowTriggers", [])) == 1),
        ("actions match the template", [a["WFWorkflowActionIdentifier"] for a in shared["WFWorkflowActions"]]
            == [a["WFWorkflowActionIdentifier"] for a in template["WFWorkflowActions"]]),
        ("app identity matches the template", identities(shared) == identities(template) and len(identities(template)) == 1),
        ("no Lock Screen from the sharing device", "WFSelectedPoster" not in wallpaper["WFWorkflowActionParameters"]),
    ]
    for label, passed in checks:
        print(f"{'ok  ' if passed else 'FAIL'} {label}")
    bundle, team = next(iter(identities(shared)), ("?", "?"))
    print(f"     bundle {bundle}, team {team}")
    if all(passed for _, passed in checks):
        print("LINK MATCHES TEMPLATE")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
