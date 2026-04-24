# Contributing to Mulberry

Thanks for taking the time to improve Mulberry. This repository contains the
Android app, backend API, and public web app, so most changes should be scoped
to one area unless the feature genuinely crosses boundaries.

## Code of conduct

By participating in this project, you agree to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Before opening a pull request

- Search existing issues and pull requests to avoid duplicate work.
- Open an issue first for large product, architecture, privacy, or API changes.
- Keep pull requests focused. Smaller changes are easier to review and test.
- Do not include production secrets, local `.env` files, signing keys,
  `google-services.json`, service account files, or personal test data.
- Preserve the paired-device product model unless a maintainer has agreed to a
  product direction change.

## Development setup

Install JavaScript workspace dependencies from the repository root:

```bash
pnpm install
```

Create a local environment file:

```bash
cp .env.example .env
```

Start the local backend and Postgres database:

```bash
docker compose up --build
```

Run the Android development build from `apps/mobile`:

```bash
./gradlew :app:installDevDebug
```

The `devDebug` Android variant talks to `http://10.0.2.2:8080/`, which lets an
Android emulator reach the Dockerized backend.

## Useful commands

From the repository root:

```bash
pnpm build
pnpm test
pnpm typecheck
pnpm --filter @mulberry/backend test
pnpm --filter @mulberry/web typecheck
pnpm --filter @mulberry/mobile test
```

Android commands can also be run directly from `apps/mobile` with `./gradlew`.

## Pull request checklist

- The change is limited to the smallest practical scope.
- Relevant tests, type checks, or builds have been run.
- Public docs are updated when behavior, setup, configuration, or API contracts
  change.
- New environment variables are added to `.env.example` and documented in the
  README.
- User-facing privacy, security, or data retention changes are reflected in the
  app-facing privacy policy and public legal pages when applicable.
- Screenshots or short notes are included for visible UI changes.

## Style notes

- Prefer existing project patterns over new abstractions.
- Keep backend API behavior covered by tests when adding or changing routes.
- Keep Android changes compatible with the existing Kotlin, Compose, Hilt,
  Room, DataStore, and WorkManager stack.
- Keep web changes compatible with the existing Next.js and Tailwind setup.
- Avoid broad formatting-only diffs unless the pull request is explicitly about
  formatting.

## Reporting security issues

Please do not open public issues for vulnerabilities. Follow
[SECURITY.md](SECURITY.md) instead.
