# iPhone 17 hardware results — 26 October 2026

Xcode 27.1 built and installed Mulberry on iPhone 17 running iOS 27.0.1 using the owner's Personal Team. No Techaboard signing resources were used. The signed app has no APNs entitlement. The owner trusted the profile. Its current provisioning expiry is 2 November 2026 at 09:54 UTC; rebuild/reinstall for later local testing.

Revision 1 was applied by a manual Shortcut run and visibly appeared on the Home Screen. Revision 2 arrived through a local notification while the app was in the background, without tapping the notification. Revision 3 arrived through a local notification while the phone was locked; the screenshot shows a closed padlock and the new revision. Revision 4 passed after replacing the Shortcut with the regenerated bundled template and enabling its imported automation. Each revision has a completion receipt. Screenshots are separate proof of visible rendering.

The working Shortcut was exported as File / Anyone and transferred to this Mac. Its signed Apple Archive was verified using the signing certificate's public key, then unpacked. `exported-automation.plist` shows the actual WFNotificationTrigger, app descriptor, and Title-contains filter. The generator now includes that trigger. The regenerated file imported with Automation off. Enabling the switch was enough; the trigger did not need to be built manually. The existing app/wallpaper permissions were retained during replacement, so fresh-install consent still needs a separate check.

The wallpaper import question retained Wallpaper 5. Add Shortcut did not advance after selection in this replacement test. Skip Setup opened the duplicate-name prompt; Replace completed import and kept Wallpaper 5. A clean first-install check is needed before calling this setup polished.

The mock server is running at http://192.168.0.105:8788. The app points to it. Keep the Mac reachable while testing. The supplied template is bundled in the installed app, and one Mulberry notification automation is enabled.

Local notification scheduling to completion took 6.28 seconds for revision 2, 10.45 seconds for revision 3, and 6.60 seconds for revision 4; each includes the intentional five-second notification delay. These are observed samples, not a rate limit or latency guarantee. There was no sustained/burst, Focus, Low Power Mode, reboot, or unattended overnight test. Device Hub screen sharing and USB were connected; no debugger was attached. Real APNs delivery remains pending paid personal developer membership and push-enabled signing.

The signed device build passed. Five Swift core tests and two mock-server tests passed after the changes. `results.json`, `delivery-events.json`, and the PNG captures contain the evidence. The fixture is a low-resolution test PNG, not a production wallpaper-quality asset.
