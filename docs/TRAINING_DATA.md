# Recordings for fine-tuning

A speech model gets names, jargon and one's own way of speaking right only if it was trained
on them. The app cannot train a model on a phone, but it can keep the material: with
*Settings → Voice input → Keep what I dictate* switched on, every dictated utterance is saved
on the phone, and *Export* writes all of it into one ZIP file that you copy to a computer and
fine-tune a model with. The switch is off unless you turn it on; what is and is not kept, and
that none of it is ever sent anywhere, is in [PRIVACY.md](PRIVACY.md).

This page describes the export, for whoever writes the training script.

## Layout

```
local-voice-ime-recordings-20261004.zip
├── log.jsonl          one JSON object per line, in the order things happened
└── audio/
    └── 20261004-091502-113-01.wav     16 kHz, mono, 16-bit PCM
```

An audio file is one **utterance**: the stretch of speech between two pauses, exactly the
samples the recognizer was given (the voice activity detector's segment plus a short margin).
Exactly, sample for sample, from the version after 0.8.2 on; up to 0.8.2 every non-zero sample
in the file is one step (of 32768) closer to zero than what the recognizer got, which is
inaudible and still enough to change what a model writes for about one utterance in twenty.
Its name is `<session>-<number>`; a **session** is one use of dictation, from holding the space
bar or tapping the microphone pill until it ends, and its name is its starting time on the
phone's clock.

## Records

Every line has `"v": 1` and a `type`. Records about the same utterance share its `id`, records
about the same session its `session`; later lines add to earlier ones and never replace them.

| `type` | Written when | Fields |
|---|---|---|
| `utterance` | an utterance was recognized and its audio saved | `id`, `session`, `timeMs` (Unix time), `audio` (path in the archive), `seconds`, `text`, `continues`, `model`, `app` (version) |
| `refined` | the high-accuracy model transcribed the same audio | `id`, `text`, `model` |
| `field` | the text field was read back after the session | `session`, `dictated`, `text` |
| `undone` | the user removed the session's text with Undo | `session` |

- **`utterance.text`** is what the standard model (`model`) wrote, with its punctuation and
  number formatting. If `continues` is true, the speaker went on after a short pause and the
  sentence was transcribed again as a whole: `text` then covers this audio file **and** the ones
  of the preceding utterances of the session, back to the last one with `continues` false. An
  utterance cut off by the 20-second limit has no closing full stop.
- **`refined.text`** is the large model's raw output for this one audio file: no punctuation,
  English in capitals, numbers spelled out. It is missing when the model is not installed or
  the text had been changed before it finished.
- **`field`** is the nearest thing to a correct transcript. `dictated` is everything the session
  wrote, as dictation left it (refinements applied; punctuation typed while dictating
  included). `text` is what stood in its place when the field was read back: equal to `dictated`
  if the user left it alone, different if they corrected it. A session can have several, as the
  user goes on editing; the last one counts. There is none if the text could not be found any
  more (deleted, rewritten, or the app cleared the field after sending), which says nothing
  about whether it was right. Only the dictated passage is recorded, never the text around it.
- **`undone`** means the user threw the result away at once. The audio is still there; treat
  the transcripts of that session as probably wrong.

## From an export to training pairs

Audio is per utterance, the best text is per session, so a script has to bring the two
together. A workable recipe:

1. Group records by `session`; drop sessions that are `undone` unless you transcribe them again.
2. A session whose last `field.text` equals `dictated` was accepted as it was: its utterances
   can be used with their own transcripts (`refined` merged into `text`, or `text` alone).
   Accepted is not the same as correct; people leave small mistakes.
3. A session whose last `field.text` differs was corrected: concatenate its audio and use
   `field.text` as the transcript, or align the corrected text back to the utterances.
4. Sessions without a `field` record have no confirmation either way. Transcribe them again
   with the strongest model a computer can run and keep the ones where it agrees with the phone.

The utterances that teach a model most are the corrected ones: they are where it was wrong.
