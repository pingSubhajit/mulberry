#!/bin/zsh
# Drives the device mirrored in Xcode's Device Hub, so an agent can tap a real iPhone.
# Device Hub's window is pinned at (100, 60), 1400 × 1080 points. X and Y below are points inside
# that window: read them off the latest screenshot, which is saved at that same scale.
#
#   devicehub.sh open                 open Device Hub (select the iPhone in its sidebar once)
#   devicehub.sh shot                 screenshot the window to $SHOT and print the path
#   devicehub.sh tap X Y [WAIT]       tap, wait (default 2 s), screenshot
#   devicehub.sh hold X Y [SECONDS]   long-press (default 1.2 s), screenshot
#   devicehub.sh type TEXT            select all in the focused field, type TEXT, screenshot
set -e
DIR=${0:A:h}
SHOT=${SHOT:-/tmp/mulberry-devicehub.png}
POINTER=/tmp/mulberry-devicehub-pointer
[[ $POINTER -nt $DIR/pointer.swift ]] || swiftc -O $DIR/pointer.swift -o $POINTER

# Device Hub drifts between displays and falls behind other windows, so pin it before every action.
front() {
  osascript -e 'tell application "System Events" to tell process "DeviceHub"' \
    -e 'set frontmost to true' -e 'set position of window 1 to {100, 60}' -e 'set size of window 1 to {1400, 1080}' \
    -e 'end tell' >/dev/null
  sleep 0.5
}
shot() {
  front
  screencapture -x -R100,60,1400,1080 "$SHOT"
  sips -Z 1400 "$SHOT" >/dev/null
  echo "$SHOT"
}

case $1 in
  open) open -a /Applications/Xcode.app/Contents/Applications/DeviceHub.app; sleep 4; shot ;;
  shot) shot ;;
  tap) front; $POINTER tap $((100 + $2)) $((60 + $3)); sleep ${4:-2}; shot ;;
  hold) front; $POINTER hold $((100 + $2)) $((60 + $3)) ${4:-1.2}; sleep 1.2; shot ;;
  type)
    front
    osascript -e 'tell application "System Events" to keystroke "a" using command down'; sleep 0.6
    osascript -e "tell application \"System Events\" to keystroke \"$2\""; sleep 1
    shot ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
