#!/usr/bin/env python3
"""Copy Android's owned artwork and fonts without changing its visual identity."""
from pathlib import Path
import json
import shutil
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
android = root.parent / 'mobile/app/src/main/res'
assets = root / 'Resources/Assets.xcassets'
assets.mkdir(parents=True, exist_ok=True)
info = {'info': {'author': 'xcode', 'version': 1}}
(assets / 'Contents.json').write_text(json.dumps(info, indent=2) + '\n')

def image(name, source):
    folder = assets / (name + '.imageset')
    folder.mkdir(exist_ok=True)
    shutil.copy2(source, folder / source.name)
    (folder / 'Contents.json').write_text(json.dumps({**info, 'images': [{'filename': source.name, 'idiom': 'universal'}]}, indent=2) + '\n')

def vector(name, filename):
    source = ET.parse(android / 'drawable' / filename).getroot()
    attr = lambda element, key, default=None: element.get('{http://schemas.android.com/apk/res/android}' + key, default)
    width, height = attr(source, 'viewportWidth'), attr(source, 'viewportHeight')
    paths = []
    for element in source:
        if element.tag != 'path':
            raise ValueError('Unsupported vector node: ' + element.tag)
        color = attr(element, 'fillColor').replace('@color/mulberry_primary', '#B31329')
        if color.startswith('#') and len(color) == 9:
            color = '#' + color[3:] + color[1:3]  # Android AARRGGBB -> SVG RRGGBBAA
        rule = 'evenodd' if attr(element, 'fillType') == 'evenOdd' else 'nonzero'
        paths.append(f'<path fill="{color}" fill-rule="{rule}" d="{attr(element, "pathData")}"/>')
    folder = assets / (name + '.imageset')
    folder.mkdir(exist_ok=True)
    (folder / (name + '.svg')).write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}">' + ''.join(paths) + '</svg>\n')
    (folder / 'Contents.json').write_text(json.dumps({**info, 'images': [{'filename': name + '.svg', 'idiom': 'universal'}], 'properties': {'preserves-vector-representation': True}}, indent=2) + '\n')

image('AuthBackground', android / 'drawable-nodpi/auth_login_bg.jpg')
image('WallpaperDefault', android / 'drawable-nodpi/wallpaper_default_bg.jpg')
for index in range(1, 5):
    image('WallpaperPreset' + str(index), root.parent / f'mobile/wallpaper_pack/src/main/assets/wallpapers/wallpaper_preset_{index}.jpg')
vector('BrandMark', 'brand_iconmark_color.xml')
vector('GoogleMark', 'ic_google_logo.xml')
vector('CanvasText', 'canvas_action_text.xml')
vector('HomeWordmark', 'home_header_wordmark.xml')
image('HomeStreakBolt', android / 'drawable-nodpi/ic_home_streak_bolt.png')
for name in ['undo', 'redo', 'brush', 'eyedropper', 'erase', 'sticker', 'clear']:
    image('Canvas' + name.capitalize(), android / f'drawable-nodpi/canvas_action_{name}.png')
fonts = root / 'Resources/Fonts'
fonts.mkdir(parents=True, exist_ok=True)
for name in ['poppins_regular.ttf', 'poppins_medium.ttf', 'poppins_semibold.ttf', 'poppins_bold.ttf', 'virgil_regular.ttf']:
    shutil.copy2(android / 'font' / name, fonts / name)
# Android delivers these in a fast-follow asset pack; iOS bundles them for text.
for name in ['dm_sans', 'space_mono', 'playfair_display', 'bangers', 'permanent_marker', 'kalam', 'oswald']:
    shutil.copy2(root.parent / f'mobile/canvas_fonts/src/main/assets/fonts/{name}_regular.ttf', fonts / f'{name}_regular.ttf')
print('Prepared existing Android artwork and fonts for iOS.')
