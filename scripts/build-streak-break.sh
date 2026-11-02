#!/usr/bin/env bash
# Builds the breaking streak bolt for every app from one transparent master video, and its last
# frame as the static broken bolt, so the animation ends exactly on the picture shown afterwards.
#
#   scripts/build-streak-break.sh <master.mov> [still.png]
#
# The master is a transparent QuickTime (Animation/ProRes 4444) at 540 x 840, 60 fps, no sound.
# `still.png` optionally replaces the last frame with an upscaled copy of it (say 2x from Topaz
# Gigapixel); it must keep the frame's proportions.
#
# Writes:
#   iOS      Resources/Assets.xcassets/StreakBoltBreak.dataset/streak_bolt_break.mov  (HEVC with alpha)
#            Resources/Assets.xcassets/StreakBrokenBolt.imageset/streak_bolt_broken_large.png
#   macOS    Resources/Streak/streak_bolt_break.mov, Resources/Streak/streak_bolt_broken_large.png
#   Android  res/raw/streak_bolt_break.mp4  (H.264, premultiplied colour on the left, alpha as grey on the right)
#            res/drawable-nodpi/streak_bolt_broken_large.png
set -euo pipefail

master="${1:?usage: build-streak-break.sh <master.mov> [still.png]}"
still="${2:-}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ios_assets="$root/apps/ios/Resources/Assets.xcassets"
mac_streak="$root/apps/macos/Resources/Streak"
android_res="$root/apps/mobile/app/src/main/res"

size="$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$master")"
[[ "$size" == "540x840" ]] || { echo "expected a 540x840 master, got $size" >&2; exit 1; }

# The static broken bolt: the master's last frame, or the upscaled copy of it.
if [[ -n "$still" ]]; then
  cp "$still" "$work/still.png"
else
  # Every frame overwrites the last, leaving the final one.
  ffmpeg -v error -y -i "$master" -an -update 1 -pix_fmt rgba "$work/still.png"
fi

# Both films carry premultiplied colour: the master keeps bright colour under its nearly clear glow,
# which players that composite premultiplied frames, as AVPlayerLayer does, would show as a pink box.
#
# Apple: HEVC with an alpha layer, which AVFoundation decodes in hardware.
ffmpeg -v error -y -i "$master" -an -vf "format=rgba,premultiply=inplace=1,format=bgra" \
  -c:v hevc_videotoolbox -alpha_quality 0.75 -b:v 1600k \
  -tag:v hvc1 -movflags +faststart "$work/streak_bolt_break.mov"

# Android: MediaCodec has no alpha, so the alpha travels as a grey picture beside the colour and a
# shader puts them back together. Main-profile H.264 at 1080 x 840 plays on every device.
ffmpeg -v error -y -i "$master" -an -filter_complex \
  "[0:v]format=rgba,premultiply=inplace=1,format=rgba,split[colour][mask];[mask]alphaextract,format=gray[alpha];[colour]format=rgb24[rgb];[alpha]format=rgb24[grey];[rgb][grey]hstack=inputs=2,format=yuv420p[out]" \
  -map "[out]" -c:v libx264 -profile:v main -preset slow -crf 24 -movflags +faststart "$work/streak_bolt_break.mp4"

mkdir -p "$ios_assets/StreakBoltBreak.dataset" "$mac_streak" "$android_res/raw"
cp "$work/streak_bolt_break.mov" "$ios_assets/StreakBoltBreak.dataset/streak_bolt_break.mov"
cat > "$ios_assets/StreakBoltBreak.dataset/Contents.json" <<'JSON'
{
  "data" : [
    {
      "filename" : "streak_bolt_break.mov",
      "idiom" : "universal",
      "universal-type-identifier" : "com.apple.quicktime-movie"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
JSON
cp "$work/streak_bolt_break.mov" "$mac_streak/streak_bolt_break.mov"
cp "$work/streak_bolt_break.mp4" "$android_res/raw/streak_bolt_break.mp4"
for destination in "$ios_assets/StreakBrokenBolt.imageset" "$mac_streak" "$android_res/drawable-nodpi"; do
  cp "$work/still.png" "$destination/streak_bolt_broken_large.png"
done

ls -l "$work"/streak_bolt_break.* "$work/still.png"
