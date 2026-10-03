# Contributing

Thanks for helping. This page is the contract for changes to this repository, whether they are
written by a person or by an AI agent. `AGENTS.md` is the orientation guide; read it first.

## Ground rules

1. **No network.** The app never gets the `INTERNET` permission or anything that phones home.
   Features that would need it are out of scope.
2. **English only** in code, comments, docs, commit messages and `values/strings.xml`.
   Translations go to `values-<locale>/`.
3. **Small diff against upstream.** New code goes into new files (voice code under
   `app/src/main/java/org/fcitx/fcitx5/android/input/voice/`). Hooks in upstream files stay
   minimal and are listed in `docs/ARCHITECTURE.md`.
4. **License hygiene.** The project is LGPL-2.1-or-later. Keep existing copyright headers, put the
   SPDX header on new files, and record every new dependency, model or asset in `NOTICE.md` and
   `app/licenses/libraries/` after checking that its license allows redistribution.

## Workflow

| Kind of change | Do this first | Then | Verify with |
|---|---|---|---|
| Anything visible (layout, colour, wording, gesture) | Update `docs/design/mockup.html` and `docs/design/DESIGN.md`; agree on the design in the issue or PR | Implement | `scripts/ui-shots.sh`, compare with the mockup, attach screenshots |
| Voice pipeline (`input/voice/`) | — | Implement, add unit tests for pure logic and a scenario for each behaviour rule | `scripts/e2e-voice.sh`, `scripts/e2e-scenarios.sh` |
| Speech model or runtime | Benchmark as described in `docs/MODELS.md`, record the numbers there | Update `scripts/fetch-voice-assets.sh`, `NOTICE.md`, `app/licenses/` | `scripts/e2e-voice.sh` on a device |
| Merging upstream | `git fetch upstream && git merge upstream/master` | Re-apply hooks listed in `docs/ARCHITECTURE.md` if they conflict | everything below |

Every change: `./scripts/check.sh` must pass (unit tests, build, privacy check). CI runs it on
each push and pull request.

## Commits and pull requests

- Branch from `main`; one topic per pull request.
- Commit subject: imperative mood, ≤ 72 characters, no trailing period
  ("Add cancel zone to push-to-talk"). The body says why, not what the diff already shows.
- Fill in the pull request template; do not tick boxes for checks you did not run.
- Releases are tags `vX.Y.Z` on `main`; the tag is the version name of the APK built from it.

## Code style

- Kotlin official style, 4 spaces, lines up to ~100 characters, matching the file you are in.
- Upstream files use the splitties view DSL; plain Android views are fine in `input/voice/`.
- Comments explain why. Reference the design ("see Hold space in docs/design/mockup.html")
  rather than describing pixels in code.
- No user-visible string literals in code; add them to `values/strings.xml` (and `values-zh-rCN/`).
- Colours come from `VoicePalette` / the active `Theme`, never from literals in views.

## Reporting bugs

Use the issue templates. This is a fork: do not report problems with it to fcitx5-android.
Do not attach recordings or text you would not want to be public.
