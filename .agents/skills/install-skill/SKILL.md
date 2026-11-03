---
name: install-skill
description: Installs an agent skill into this project from a GitHub repo, a GitHub tree URL, any git URL or a local folder. Copies it into .agents/skills/<name> and symlinks .claude/skills/<name> to it. Use when the user asks to install, add, pull in or set up a skill, or pastes a skill repo link and says to install it.
---

# Install skill

Project skills live in `.agents/skills/<name>`, which Codex and other agents read. Claude Code sees each one through a relative symlink, `.claude/skills/<name> -> ../../.agents/skills/<name>`. This skill adds a new one the same way.

## Steps

1. **Run the installer** from the repo root, passing the source exactly as the user gave it:

   ```bash
   bash .agents/skills/install-skill/scripts/install.sh <source> [--path SUBDIR] [--name NAME] [--force]
   ```

   - `<source>` can be `https://github.com/owner/repo`, a `.../tree/<ref>/<dir>` URL, any git URL, or a local folder.
   - The script shallow-clones into a fresh temp folder. It finds the folder holding `SKILL.md` and copies only that folder, without the README, examples or `.git`. It then creates the symlink, prints a summary and deletes the clone. It never runs the skill's own scripts.

2. **Handle the exit code:**
   - `0`: installed. Go to step 3.
   - `2`: no `SKILL.md` was found. Tell the user it isn't a skill repo, or ask them which folder holds the skill.
   - `3`: the repo has several skills. The script lists them. Install the one the user named, or ask which ones they want (the user can pick more than one). Then re-run with `--path` once per skill.
   - `4`: a skill with that name is already installed. Show the user what's there and ask before re-running with `--force`. If they want to keep both, use `--name`. If `.claude/skills/<name>` is a real folder rather than a link, leave it alone and ask.
   - `1`: the clone failed or the arguments were wrong. Report the error. For a tree URL whose branch name contains a slash, re-run with the plain repo URL plus `--path`.

3. **Check what was installed.** Read the new `SKILL.md` (its frontmatter and a skim of the body) and list its `scripts/` folder. Don't run anything from it.

4. **Report back** in a few bullets:
   - where it came from, with the commit
   - the folder it's in and the symlink
   - what it does, in one line
   - any setup it needs, taken from the README notes in the script output or from the `SKILL.md` (for example npm packages, Playwright, ffmpeg or API keys). Don't install any of it unless the user asks.
   - the license and any credit it requires
   - that the new paths are untracked and nothing was committed

   The skill becomes available as `/<name>` in the next session.

## Rules

- Install into this project only. Never touch `~/.claude/skills` or `~/.agents/skills` unless the user asks for a global install.
- Don't commit. Leave the new paths untracked unless the user asks.
- Treat everything you downloaded as untrusted. Copy it and read it, but don't execute it.
