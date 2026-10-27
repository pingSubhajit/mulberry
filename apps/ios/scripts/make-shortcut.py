#!/usr/bin/env python3
"""Build a shareable Shortcut. Import and actions were verified on iOS 27.0.

The simulator uses FAKETEAMID. For an iPhone build supply the real signing team.
Signing uses Apple's CLI and may require an iCloud account/network connection.
Notification trigger schema comes from an iOS 27.0.1 device export.
The user enables the trigger after import and grants first-run permissions.
"""
import argparse
import plistlib
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--team-id', default='FAKETEAMID')
parser.add_argument('--bundle-id', default='com.subhajit.mulberry.dev')
parser.add_argument('--name', default=None, help='Shortcut name; defaults to Mulberry Sync')
parser.add_argument('--unsigned-only', action='store_true')
parser.add_argument('--manual-only', action='store_true', help='Omit the iOS 27 notification trigger')
args = parser.parse_args()
shortcut_name = args.name or 'Mulberry Sync'
app_name = 'Mulberry'
root = Path(__file__).resolve().parents[1]
out = root / 'ShortcutTemplates'
out.mkdir(exist_ok=True)
fetch_id = '91629C27-A53A-4C05-A3DB-7F8FD39A2F99'
image = {'Value': {'OutputUUID': fetch_id, 'Type': 'ActionOutput', 'OutputName': 'Get latest Mulberry wallpaper'}, 'WFSerializationType': 'WFTextTokenAttachment'}

def intent(name, uuid, **parameters):
    return {'WFWorkflowActionIdentifier': f'{args.bundle_id}.{name}', 'WFWorkflowActionParameters': {
        'AppIntentDescriptor': {'TeamIdentifier': args.team_id, 'BundleIdentifier': args.bundle_id,
                                'Name': app_name, 'AppIntentIdentifier': name},
        'UUID': uuid, **parameters}}

workflow = {
    'WFWorkflowName': shortcut_name,
    'WFWorkflowClientVersion': '2602.0.11',
    'WFWorkflowMinimumClientVersion': 900,
    'WFWorkflowMinimumClientVersionString': '900',
    'WFWorkflowIcon': {'WFWorkflowIconGlyphNumber': 61440, 'WFWorkflowIconStartColor': 2071128575},
    'WFWorkflowTypes': [], 'WFWorkflowInputContentItemClasses': [],
    'WFWorkflowOutputContentItemClasses': [], 'WFWorkflowHasOutputFallback': False,
    'WFWorkflowIsDisabledOnLockScreen': False,
    # iOS 27 can stall on Add Shortcut with a wallpaper import question.
    # Choose the target in the editor, then do a manual permission run.
    'WFWorkflowImportQuestions': [],
    'WFWorkflowActions': [
        intent('GetLatestWallpaperIntent', fetch_id),
        {'WFWorkflowActionIdentifier': 'is.workflow.actions.wallpaper.set', 'WFWorkflowActionParameters': {
            'UUID': '71C93DBA-499F-470A-9D13-0D46C7DECFD4', 'WFInput': image,
            'WFWallpaperShowPreview': False, 'WFWallpaperSmartCrop': False, 'WFWallpaperLegibilityBlur': False}},
        intent('RecordWallpaperResultIntent', '08D65735-480A-4B64-AC99-7818253B527E', image=image),
    ]
}
# Captured from Shortcuts on a physical iPhone running iOS 27.0.1.
# No device wallpaper UUID is bundled; the user selects a local pair in the editor.
if not args.manual_only:
    workflow['WFWorkflowClientVersion'] = '5037.111'
    workflow['WFWorkflowTriggers'] = [{
        'WFTriggerIdentifier': 'WFNotificationTrigger',
        'WFTriggerUUID': 'AE71BFD5-F64B-4BDE-BDAC-882D8C1436A8',
        'WFTriggerSerializedParameters': {
            'SelectedApps': {'BundleIdentifier': args.bundle_id, 'Name': app_name,
                             'TeamIdentifier': args.team_id},
            'Conditions': {'Value': {
                'WFActionParameterFilterPrefix': 1,
                'WFContentPredicateBoundedDate': False,
                'WFActionParameterFilterTemplates': [{
                    'Operator': 0, 'Values': {'Title': 'Mulberry canvas updated'},
                    'Removable': True, 'Property': 'Title'}]},
                'WFSerializationType': 'WFContentPredicateTableTemplate'}
        }
    }]

source = out / f'{shortcut_name}.plist'
source.write_bytes(plistlib.dumps(workflow, fmt=plistlib.FMT_XML, sort_keys=False))
print(f'Written source: {source}')
if not args.unsigned_only:
    build = root / '.build'
    build.mkdir(exist_ok=True)
    binary = build / f'{shortcut_name}.unsigned.shortcut'
    binary.write_bytes(plistlib.dumps(workflow, fmt=plistlib.FMT_BINARY))
    subprocess.run(['shortcuts', 'sign', '--mode', 'anyone', '--input', str(binary),
                    '--output', str(out / f'{shortcut_name}.shortcut')], check=True)
