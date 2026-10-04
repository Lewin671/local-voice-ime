## What and why

<!-- One or two sentences. Link the issue if there is one. -->

## How it was verified

<!-- Follow docs/TESTING.md#verification-policy. Remove inapplicable checks and explain any
required checks that remain pending. -->

- [ ] `./scripts/check.sh` passes (code changes)
- [ ] Relevant device checks pass for Android wiring, recording, permissions, editor interaction, or runtime/model changes
- [ ] Real-phone recording/permission checks pass when those behaviors changed
- [ ] Diff and local links checked (documentation/comment-only changes)

## UI changes

<!-- Delete this section if nothing visible changed. -->

- [ ] `docs/design/mockup.html` and `docs/design/DESIGN.md` describe the new behaviour
- [ ] Screenshots from `./scripts/ui-shots.sh` attached (light and dark)

## Housekeeping

- [ ] New upstream files touched are listed in `docs/ARCHITECTURE.md`
- [ ] New dependencies, models or assets are recorded in `NOTICE.md` and `app/licenses/libraries/`
- [ ] Network use is unchanged: only `VoiceModelFetch.kt`, only model downloads (`docs/PRIVACY.md`)
