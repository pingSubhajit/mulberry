# What's New (server-side changelogs)

Version-addressed "What's new" notes, one folder per app. Each app has its own release schedule and
its own version numbers, so a note belongs to exactly one platform.

## Files

- `<platform>/X.Y.Z.md`: the note for that platform's app version `X.Y.Z`
- `<platform>/assets/X.Y.Z/*`: optional images the note refers to, such as its hero image

`<platform>` is `android`, `ios` or `macos`. When a feature ships on several apps, write a note in
each folder, with each app's own version and screenshots.

Not every version needs a note. An app shows a note on its own once, the first time it opens on
that exact version. A version with no note is marked as seen, and a note added later never appears
on its own for people who already opened that version, so commit the note before the first build
of its version reaches people.

Leave `released_at` out while the version isn't public. The note then stays out of What's New
history and `latest.md`, but an app on that version (a TestFlight build, say) still gets it. The
public-release step (`.agents/skills/mulberry-skills/public-release`) writes the release day in.

## Markdown format

Notes start with a small frontmatter block:

```text
---
version: 1.1.5
released_at: 2026-05-06
title: Reactions on your wallpaper
hero_image: /whats-new/android/assets/1.1.5/hero.webp
cta_label: Sounds awesome!
---
```

`hero_image` is a path on this server (or a full URL), so it includes the platform folder.

After the frontmatter, write normal markdown: headings, paragraphs, bullet and numbered lists, bold,
italics and links.

## Backend endpoints

- `GET /whats-new/:platform/:version.md`
- `GET /whats-new/:platform/latest.md`
- `GET /whats-new/:platform?limit=5&cursor=0`
- `GET /whats-new/:platform/assets/*`

The same routes without `:platform` (`/whats-new/:version.md`, `/whats-new/latest.md`,
`/whats-new?…`, `/whats-new/assets/*`) serve the `android` folder, for Android builds released
before the notes were split by platform. Keep them until the minimum Android build is one that calls the `/whats-new/android` routes.
