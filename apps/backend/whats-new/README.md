# What's New (server-side changelogs)

This folder contains version-addressed "What's new" entries for the mobile app.

## Files

- `X.Y.Z.md` — the entry for app version `X.Y.Z`
- `assets/X.Y.Z/*` — optional images referenced by the entry (hero images, etc.)

## Markdown format

Entries may start with a small frontmatter block:

```text
---
version: 1.1.5
released_at: 2026-05-06
title: Reactions on your wallpaper
hero_image: /whats-new/assets/1.1.5/hero.webp
cta_label: Sounds awesome!
---
```

After the frontmatter, write normal markdown content (headings, paragraphs, bullet lists).

## Backend endpoints

- `GET /whats-new/:version.md`
- `GET /whats-new/latest.md`
- `GET /whats-new/assets/*`

