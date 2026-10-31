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
parser.add_argument('--output-dir', type=Path, help='Write this build\'s template outside the source tree')
parser.add_argument('--unsigned-only', action='store_true')
parser.add_argument('--manual-only', action='store_true', help='Omit the iOS 27 notification trigger')
args = parser.parse_args()
shortcut_name = args.name or 'Mulberry Sync'
app_name = 'Mulberry'
root = Path(__file__).resolve().parents[1]
out = args.output_dir or root / 'ShortcutTemplates'
out.mkdir(parents=True, exist_ok=True)
fetch_id = '91629C27-A53A-4C05-A3DB-7F8FD39A2F99'
def output(uuid, name):
    return {'Value': {'OutputUUID': uuid, 'Type': 'ActionOutput', 'OutputName': name},
            'WFSerializationType': 'WFTextTokenAttachment'}


def text_token(value):
    return {'WFSerializationType': 'WFTextTokenString',
            'Value': {'string': '\ufffc', 'attachmentsByRange': {'{0, 1}': value['Value']}}}


def action(identifier, **parameters):
    return {'WFWorkflowActionIdentifier': f'is.workflow.actions.{identifier}',
            'WFWorkflowActionParameters': parameters}


def condition(group, mode=0, **parameters):
    if 'WFInput' in parameters:
        parameters['WFInput'] = {'Type': 'Variable', 'Variable': parameters['WFInput']}
    return action('conditional', GroupingIdentifier=group, WFControlFlowMode=mode, **parameters)


image = output(fetch_id, 'Get latest Mulberry wallpaper')
set_id = '71C93DBA-499F-470A-9D13-0D46C7DECFD4'
focus_id = '3EEA2666-61AB-46F5-B54B-05BC072E59F1'
current_id = '17610ECB-4471-49EE-B79E-25CB70731A1D'
current_path_id = 'FE961ECF-E73B-4B3C-BBC8-B49251668F31'
target_path_id = '509CDBA1-D780-4731-A2DA-5B71E7657CEB'
focus_group = '75E85080-34D6-4867-99E6-7D7B198B9D41'
pair_group = '46F33DAB-093E-4C59-9F56-2E47D802CBE5'
target = output(set_id, 'Wallpaper')

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
        action('wallpaper.set', UUID=set_id, WFInput=image,
               WFWallpaperShowPreview=False, WFWallpaperSmartCrop=False,
               WFWallpaperLegibilityBlur=False),
        # Set Wallpaper returns the exact pair it updated. Reuse that object so
        # the user chooses a target only once, and Switch cannot target another pair.
        action('dnd.getfocus', UUID=focus_id),
        # 101 = does not have any value. Never switch away from an active Focus.
        condition(focus_group, WFInput=output(focus_id, 'Current Focus'), WFCondition=101),
        # On iOS 27 Get Name returns the generic 'Wallpaper'. File Path converts
        # each pair to its numbered preview name (e.g. 'Wallpaper 2.png'). Compare
        # freshly returned paths in this run; never persist an index or compare pixels.
        action('posters.get', UUID=current_id, WFPosterType='Current'),
        action('properties.files', UUID=current_path_id, WFInput=output(current_id, 'Wallpapers'),
               WFContentItemPropertyName='File Path'),
        action('properties.files', UUID=target_path_id, WFInput=target, WFContentItemPropertyName='File Path'),
        condition(pair_group, WFInput=output(current_path_id, 'File Path'), WFCondition=5,
                  WFConditionalActionString=text_token(output(target_path_id, 'File Path'))),
        action('posters.switch', WFPoster=target),
        condition(pair_group, mode=2),
        condition(focus_group, mode=2),
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
