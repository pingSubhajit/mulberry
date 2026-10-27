#!/usr/bin/env python3
"""Exercise the generated Shortcut control flow with wallpaper and Focus states.

This checks the shipped graph, not a separate copy of its policy. Hardware testing
is still required for Apple's serialization and wallpaper identity conversions.
"""
import plistlib
from pathlib import Path
import unittest

ACTIONS = plistlib.loads((Path(__file__).resolve().parents[1] /
                         'ShortcutTemplates/Mulberry Sync.plist').read_bytes())['WFWorkflowActions']


def simulate(current, target, focus):
    outputs, stack, switches, receipts = {}, [], [], []

    def resolve(value):
        if isinstance(value, dict):
            if value.get('Type') == 'Variable':
                return resolve(value['Variable'])
            if value.get('WFSerializationType') == 'WFTextTokenString':
                return outputs[value['Value']['attachmentsByRange']['{0, 1}']['OutputUUID']]
            return outputs[value['Value']['OutputUUID']]
        return value

    for action in ACTIONS:
        name = action['WFWorkflowActionIdentifier'].rsplit('.', 1)[-1]
        p = action['WFWorkflowActionParameters']
        enabled = all(stack)
        if name == 'conditional':
            if p['WFControlFlowMode'] == 2:
                stack.pop()
            elif enabled:
                value = resolve(p['WFInput'])
                if p['WFCondition'] == 101:
                    stack.append(value is None)
                elif p['WFCondition'] == 5:
                    stack.append(value != resolve(p['WFConditionalActionString']))
                else:
                    raise AssertionError('Unsupported condition')
            else:
                stack.append(False)
            continue
        if not enabled:
            continue
        result = None
        if name == 'GetLatestWallpaperIntent': result = 'rendered-image'
        elif name == 'set':
            assert resolve(p['WFInput']) == 'rendered-image'
            result = target
        elif name == 'getfocus': result = focus
        elif name == 'get':
            assert p['WFPosterType'] == 'Current'
            result = current
        elif name == 'files':
            assert p['WFContentItemPropertyName'] == 'File Path'
            result = resolve(p['WFInput']) + '.png'
        elif name == 'switch': switches.append(resolve(p['WFPoster']))
        elif name == 'RecordWallpaperResultIntent': receipts.append(resolve(p['image']))
        else: raise AssertionError(name)
        if 'UUID' in p: outputs[p['UUID']] = result
    assert not stack
    return switches, receipts


class WallpaperSwitchTests(unittest.TestCase):
    def test_different_pair_without_focus_switches_to_updated_pair(self):
        self.assertEqual(simulate('Wallpaper 1', 'Wallpaper 2', None),
                         (['Wallpaper 2'], ['rendered-image']))

    def test_same_pair_without_focus_does_not_switch(self):
        self.assertEqual(simulate('Wallpaper 2', 'Wallpaper 2', None),
                         ([], ['rendered-image']))

    def test_any_focus_preserves_active_pair_but_records_update(self):
        for focus in ['Sleep', 'Work', 'Personal', 'Custom Focus']:
            for active in ['Wallpaper 1', 'Wallpaper 2']:
                with self.subTest(focus=focus, active=active):
                    self.assertEqual(simulate(active, 'Wallpaper 2', focus),
                                     ([], ['rendered-image']))


if __name__ == '__main__': unittest.main()
