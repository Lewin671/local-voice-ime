# Voice diagnostics

*Settings → Voice input → Keep voice diagnostics* is off unless the user turns it on. While it
is on, the app keeps a journal of what happened to each dictation session, so that a session
that ended unexpectedly can be explained afterwards. This file lists everything the journal
contains and how to read it. The promise to users is in [PRIVACY.md](PRIVACY.md).

## What it is, and what it cannot contain

- One event per line, each a flat JSON object. An event is a fixed name with numbers, truth
  values and fixed words. `VoiceDiagnosticStore.encode` is the only code that makes a line, and
  it writes nothing else: a value that is not a number, a truth value or a token of at most 48
  characters from `A-Z a-z 0-9 _ . -` is written as `refused`.
- No audio and no text: not what was said, previewed, typed or corrected, and not what is in
  the text field. Lengths and counts only.
- Not the app or the field that was dictated into. Nothing is kept of a session in a field that
  an app marked as private (incognito), and voice input is not offered on password fields.
- Turning the switch off stops it at once, also for a session that is still finishing. When a
  private field gets the keyboard's attention while a session that began elsewhere is still
  finishing, nothing more is noted until the next session begins in a field that is not private.
- Events are taken only while a dictation session runs. Typing with the keyboard leaves no
  trace: the key events below are those of a touch ending while dictation is on.
- The journal is in the app's private storage (`no_backup/voice-diagnostics/`, left out of
  Android's backups and device transfers), two files of at most 1 MB together; when the newer
  one is half of that, it replaces the older one. *Delete* removes both.
- It leaves the phone in one way only: *Export* writes one text file to a place the user picks
  in Android's file dialog. Nothing is ever sent.
- Writing happens on a thread of its own; dictation never waits for it, and a journal that
  cannot be written changes nothing else.

## Getting an export to whoever looks into a problem

1. Turn *Keep voice diagnostics* on and keep using the keyboard.
2. After dictation stopped when it should not have: *Settings → Voice input → Diagnostics →
   Export*, and save the file, for example to *Downloads*. Do this the same day: the journal
   holds roughly the last few hundred sessions.
3. Hand the file over yourself: attach it to an issue, or with the phone on a cable
   `adb pull /sdcard/Download/local-voice-ime-diagnostics-<time>.txt`.
4. Say roughly when it happened; times in the journal are in milliseconds since 1970 (`t`).

## Reading it

The first line of an export is `export`: when it was written, the app version and build type,
Android's API level, maker and model of the phone, and the settings dictation depends on
(`refine`, `keep_recordings`, `long_press_ms`, `space_swipe`, `vivo_workaround`).

Every other line has `e` (the event), `n` (a counter, to see gaps; it restarts with the
process), `t` (wall clock, ms), `up` (ms since boot, monotonic), `s` (the session's number;
absent for events of the process) and `th` (the thread).

A session normally reads `session_start` … `stop_request` … `state` `Finishing` … `state`
`Stopped`, `session_end`. **Why it ended is the `reason` of the first `stop_request`** (also
`reason` in `session_end`); the events just before it say what led there. A `session_start`
without a `session_end`, followed by `process_start`, means the process died: `previous_exit`
then gives Android's reason.

| Event | Fields | Meaning |
|---|---|---|
| `process_start` | `app`, `build`, `sdk` | first session of a new process |
| `previous_exit` | `at`, `reason`, `status`, `importance`, `pss_kb`, `rss_kb` | how earlier processes of the app ended, from Android's `ApplicationExitInfo` (API 30+; `reason` is its numeric constant, e.g. 3 low memory, 4 crash, 5 native crash, 6 ANR) |
| `session_start` | `mode` (`push_to_talk`, `hands_free`), `source` (`microphone`, `test_file`), `refine`, `saving`, `loaded` (the model was in memory), `replaces` (another session was still running), `connection` (the editor could be written to) | |
| `capture_start` | `ok`, `cold` | the microphone was opened |
| `state` | `state` | `Preparing`, `Listening`, `Finishing`, `Stopped` |
| `progress` | `samples`, `consumed`, `empty_reads`, `speaking` | every 5 s of audio, from the thread that reads the microphone: samples read, samples the recognition side has taken (a growing difference means decoding or the main thread is stuck), reads that returned nothing, whether speech is being heard |
| `final` | `audio_ms`, `chars`, `continues`, `discard` | an utterance was transcribed: how long it was and how many characters came out |
| `stop_request` | `reason`, `discard`, `first` | somebody asked the session to stop, see below |
| `capture_end` | `by_source`, `code`, `chunks`, `samples`, `empty_reads` | reading stopped; `by_source` if the microphone ended it, `code` what `AudioRecord.read` returned then (e.g. -6 `ERROR_DEAD_OBJECT`) |
| `error` | `class`, `kind`, `cause` | the session failed: class of the exception, `VoiceException.Kind`, class of its cause |
| `session_end` | `reason`, `discard`, `ms`, `samples`, `empty_reads`, `partials`, `finals`, `decodes`, `slowest_decode_ms`, `abandoned`, `recoveries` | summary |
| `preview_lost` | `to`, `recovered`, `earlier` | the editor no longer shows the preview as composing text; `to` is what took it (see `composing_lost`), `recovered` whether it was taken back and dictation went on, `earlier` how often that had happened before |
| `preview_deferred`, `foreign_preview_finished` | | a session that replaced another waited for, or closed, the other's preview |
| `preview_refused` | | a preview could not be written (no connection to the editor) |
| `composing_lost` | `to` | the service's composing text was cleared: `cursor` (the cursor was reported outside of it), `restart` (input restarted in the same editor), `new_editor`, `view_finish` (the keyboard was hidden), `preedit` (the pinyin engine cleared it), `commit`, `finish` |
| `selection` | `predicted`, `collapsed`, `composing`, `in_composing`, `editor_composing` | the editor reported the cursor: whether it is where the service expected it after its own writes, and where it is relative to the composing text. Positions themselves are not kept |
| `input_start`, `input_view_start` | `restarting`, `composing` | Android (re)started input, or showed the keyboard |
| `input_view_finish` | `finishing_input` | the keyboard was hidden |
| `input_finish`, `input_unbind`, `service_destroy`, `input_view_replaced`, `config_change` | | lifecycle of the service and its view |
| `trim_memory` | `level` | Android asked for memory |
| `fcitx_commit`, `fcitx_preedit` | `composing`, `empty` | the pinyin engine wrote something while dictation was on |
| `touch_up`, `touch_cancel` | `long_press` | a touch on a key ended: lifted, or taken away by Android (`touch_cancel` on the space bar ends push-to-talk like a release) |
| `model_release`, `model_reload` | | the standard model was freed while a session ran, and loaded again for it |

Reasons of a `stop_request`:

| `reason` | Who |
|---|---|
| `release`, `release_cancel` | the space bar was released, below or above the cancel line (look for a `touch_cancel` right before it) |
| `panel_button`, `panel_detached` | the dictation panel's microphone button, or the panel was closed |
| `idle_timeout` | 10 s without speech in the dictation panel |
| `input_view_finish` | the keyboard was hidden |
| `preview_lost` | the preview was taken away and could not be taken back; the `preview_lost` event before it says by what |
| `replaced` | another session started |
| `capture_eof`, `capture_error` | (in `session_end` only) the source ended by itself |
| `error`, `scope_cancelled` | (in `session_end` only) an exception, or the service went away |

What the journal cannot tell: why Android took a touch away or restarted input, what the audio
service of a phone did before a read failed, and a microphone read that blocks for ever (the
`progress` events simply stop).
