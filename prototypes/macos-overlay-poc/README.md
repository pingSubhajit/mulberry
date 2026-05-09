# Mulberry macOS POC

This is a barebones native macOS proof of concept for the desktop overlay behavior discussed for Mulberry. It intentionally lives under `prototypes/` so `apps/macos` remains available for the production macOS app.

It demonstrates:

- A status-bar-only macOS app.
- A 400 x 400 borderless, transparent, shadowless passive overlay.
- Passive mode with the overlay below normal app windows and ignoring mouse events.
- Quick Draw mode that temporarily raises the overlay, accepts drawing input, and shows a small tool palette.
- Escape-to-exit Quick Draw behavior.

Run it from this folder:

```bash
swift run MulberryMacPOC
```

This POC does not connect to the Mulberry backend, persist drawings, authenticate, launch at login, or handle APNs. It is only for validating the AppKit windowing and interaction model.
