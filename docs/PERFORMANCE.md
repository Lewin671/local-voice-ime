# Voice performance and energy review (2026-10-03)

The largest avoidable cost is repeated whole-prefix recognition for live previews. Final
SenseVoice recognition and the selected FireRedASR2 refinement are retained. This work changes
preview latency and storage overhead, not model weights, quantization, language detection,
inverse text normalization, VAD thresholds, audio rate, endpointing, margins or text merging.

## v0.6.1 follow-up: retired refinement requests

The published v0.6.0 still computes queued refinement after a successful undo, or after an
entry is permanently evicted from the 64-entry edit history. `VoiceEdits.applyRefinements`
already rejects these results because their entries are no longer tracked, so the native
work cannot improve any text.

Version 0.6.1 adds a permanent, volatile retirement flag
to entries and checks it on the native worker before model loading and again before decoding.
Useful requests retain the same samples, model settings and recognition result. Failed undo,
temporary cursor movement, selections, previews and a new session do not retire entries;
their eligibility can recover. An in-flight native call is allowed to finish normally.

JVM regressions verify zero load/decode calls for requests retired while queued, zero decode
calls if retirement occurs during loading, and identical output for retained requests. They
also verify undo scope, history eviction, repeated identical text and recovery after a failed
undo. The updated 71-test JVM suite, debug build, privacy check and complete high-accuracy
end-to-end behavior suite passed, including Battery Saver refinement, undo, pauses and cursor
movement. No additional percentage energy saving is claimed:
this avoids the cost of unused queued work, with benefits depending on undo/history behavior.

## Findings and changes

### Follow-up: capture lifetime and obsolete speculative loads

The microphone reader now releases its source after its last completed read, independently of
queued/final recognition. `VoiceCapture` owns teardown and guards against a second release in
outer error cleanup. The last read is enqueued even if stop is requested during the read; all
chunks retain independent sample arrays. EOF is signalled before source release so that no
extra preview is requested during teardown. `MicrophoneSource` attempts release even if its
stop call fails. The recognition, VAD, sentence and preview policies are unchanged.
The reader also starts before the initial main-thread state callback, so a busy main thread
cannot delay draining AudioRecord at startup. Level reporting starts after that callback to
retain the existing state-before-level notification order. The probe can block this callback
for three seconds and verify that reads proceed meanwhile.

Both model workers recheck demand when a queued load actually starts. A discarded session can
skip a queued standard-model load; normal release still requires full final recognition.
If cancelled before the session coroutine starts, it opens neither capture nor either model.
Speculative refiner loading is withdrawn once the requesting session stops. Committed text's
refinement has its own independent required-load path. Active native loads/decodes are never
interrupted; cancelling the session does not release a model used by another request.

Further preview throttling and shorter warm-up/residency windows were not adopted: they can
increase preview or cold-start latency. Native VAD copying and CPU-pool tuning remain candidates
requiring separate runtime and target-phone measurements. These changes exhaust the confirmed
unnecessary work addressed by this follow-up, not every theoretically possible optimization.

`scripts/bench/session-probe.sh` exercises the production session, VAD and standard recognizer
with controlled WAV input. It records captured/final-audio SHA-256 values, continuation flags,
final transcripts, sentence punctuation, source stop count and whether capture is released
before the finishing phase. This establishes input/output equivalence on the supplied clips,
not broad spontaneous-speech accuracy or physical microphone/battery behavior. Probe JSON and
transcripts stay in debug-only test artifacts; the release build contains no probe activity.

The follow-up passed all 83 voice JVM tests, the debug build and privacy check. The four paired
production-session probes (Chinese, English, repeated long Chinese and concatenated mixed
speech) matched captured sample counts/hashes, final audio counts/hashes, final text,
continuation flags and sentence stops exactly. All four changed capture release before
`Finishing` from false to true, with exactly one source stop in both builds. The complete
standard-model interaction suite passed, including the emulator AudioRecord silence timeout.
These remain public smoke recordings, not independent speakers or physical-phone validation.

### Bounded-spinning experiment (not adopted)

The bundled arm64 ONNX library contains the bounded-spinning/backoff configuration keys.
`CPU_CONFIG` in `scripts/bench/device-bench.sh` tested 100, 500 and 1000 microsecond spin caps
with backoff 8, using the same four derived recordings and four SenseVoice inference threads.
Default spinning was repeated after the three candidates. Warm second-run totals:

| Policy | Process CPU ms | Decode elapsed ms |
|---|---:|---:|
| Default, first | 2,326 | 632 |
| 100 us cap, backoff 8 | 2,257 | 621 |
| 500 us cap, backoff 8 | 2,338 | 653 |
| 1000 us cap, backoff 8 | 2,358 | 646 |
| Default, repeat | 2,390 | 652 |

Raw texts were identical across all five runs. The 100-us candidate's 3.0% CPU reduction
against the first baseline is close to the default's 2.8% repeat drift; the other caps provided
no improvement against the first baseline. This small experiment does not establish stable
whole-pipeline energy or latency gains. Production SenseVoice scheduling therefore remains
unchanged. Artifacts: `build/device-bench/demand-spin-*.json`. The benchmark loads complete
unsegmented clips and is not a substitute for the production-session accuracy comparison.

| Area | Evidence in the current pipeline | Change / decision |
|---|---|---|
| Whole-prefix previews | Every preview creates a stream and runs SenseVoice on all audio retained for the current utterance. Fast hardware can repeat increasingly long audio every 300 ms. | Keep 300 ms minimum for the first 3 seconds, then increase proportionally to audio length, capped at 900 ms from 9 seconds. Battery Saver doubles these intervals. The existing minimum idle time of twice the last decode cost and quiet-audio gate still apply. Full final recognition is independent of this pacing. |
| Previews after capture ends | Queued audio was still eligible for a preview after releasing/cancelling, before the required final decode. | Suppress previews once capture stops or reaches EOF. Continue consuming all queued audio and flushing VAD; cancellation still discards text as before. In-flight native decoding is not interrupted. |
| VAD window allocations | One new 512-float array every 32 ms, including silence: 112,500 arrays / 230.4 MB of sample storage per hour (excluding object headers). | Reuse one session-owned window. Native processing and the sample sequence are unchanged. |
| Audio buffer copies | Dropping an audio prefix copied the remaining lead-in or queued audio to index zero. | Advance a head index; compact only when an append exhausts the tail capacity. Final / refinement snapshots still own independent arrays. |
| Capture waits for the UI | The microphone reader awaited `onLevel` on the main thread for each nonquiet chunk. A main-thread stall could prevent timely AudioRecord reads despite a separate decoder thread. | Conflate visual levels in a separate reporter. The audio channel stays lossless and unbounded, with independent owned chunks. |
| FireRedASR2 worker spinning | ONNX workers may spin waiting for work; the pinned runtime exposes session configuration through a provider file. | Disable intra-op and inter-op spinning for background refinement only. Keep its 4 threads and all model/decoder settings. Preserve SenseVoice spinning for preview responsiveness. If private configuration storage fails, fall back to default CPU scheduling rather than losing refinement. |
| Cancelled release timers | Each decode cancelled and rescheduled an idle timer. The default scheduled executor retained cancelled tasks until their deadline. | Enable immediate removal of cancelled tasks on both single-thread executors. Model lifetimes and native thread confinement remain unchanged. |
| Battery Saver accuracy | Existing policy automatically skipped the large model in Battery Saver or severe thermal throttling, even when refinement was enabled. | Keep user-selected refinement enabled; only preview pacing and speculative loading change. This preserves the selected accuracy but costs more than the old policy under these conditions. |

The VAD scratch array is safe to reuse in the pinned sherpa-onnx 1.13.8: the
[JNI call](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/jni/voice-activity-detector.cc)
is synchronous, and the
[native detector](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/voice-activity-detector.cc)
copies incoming samples before returning. Recheck this contract before upgrading the runtime.

## Desktop measurements

`scripts/bench/audio-buffer.sh` runs the old buffer and production Kotlin code, alternating
order for nine measurements after warm-up. Each iteration simulates ten minutes of 16 kHz
audio, with the same 512-sample windows and either a retained silent lead-in or 20-second
utterances. Window checksums must match. Model inference, capture chunk allocations, snapshots
and UI work are excluded; these numbers isolate storage overhead on the development Mac/JVM.
The ten-minute silent input is an allocation stress test: ordinary hands-free mode already
stops after ten seconds of silence, so it is not a typical real session.

| Synthetic input | Before: median ms | After: median ms | Before: allocated bytes | After: allocated bytes |
|---|---:|---:|---:|---:|
| Silence / retained lead-in | 11.631 | 9.891 | 38,956,080 | 258,168 |
| 20-second utterances | 10.654 | 10.546 | 42,540,128 | 3,842,216 |

Allocation falls by 99.3% and 91.0% respectively. Elapsed time is almost unchanged for speech,
and this overhead was already small relative to recognition. Do not interpret the allocation
reduction as an equivalent reduction in total CPU time or energy.

A deterministic pacing simulation uses the actual `PartialPacer`, audio arriving every 100 ms,
always-changing text and negligible decode time. For 20 seconds of continuous speech:

| Preview policy | Decodes | Total prefix audio decoded |
|---|---:|---:|
| Previous interval | 66 | 663.3 s |
| Audio-length-aware interval | 31 | 237.8 s |

This removes 53.0% of preview calls and 64.1% of re-decoded prefix audio in that simulation.
Actual CPU savings are not necessarily proportional to audio duration. On slow hardware the
existing decode-cost limiter can dominate and leave little additional benefit. Short
utterances retain their old minimum preview interval. Long utterances trade preview freshness
for fewer repeated recognitions; final text still uses complete audio.

## Native worker-spinning A/B experiment

The pinned runtime's [session configuration parser](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/session.cc)
accepts a `cpu:<local-file>` provider, forwarding `SessionConfig.*` entries to ONNX Runtime.
The bundled AAR was also checked for this parser. [ONNX Runtime's threading documentation](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)
explains that worker spinning trades CPU / power for lower dispatch latency. The experimental
configuration changes only `session.intra_op.allow_spinning` and
`session.inter_op.allow_spinning`, both set to `0`; thread counts, operators, weights and graph
optimizations are unchanged.

`CPU_SPIN=1` / `CPU_SPIN=0` with `scripts/bench/device-bench.sh` ran identical four recordings
(Chinese, English, repeated long Chinese, concatenated Chinese–English), twice per load, on the
4-core arm64 Android emulator, with 4 inference threads. These are derived from the two public
checked-in recordings, not four independent speakers. The table sums warm second-run times:

| Model | Spinning: CPU ms | Sleeping: CPU ms | Spinning: elapsed ms | Sleeping: elapsed ms | Raw final text |
|---|---:|---:|---:|---:|---|
| SenseVoice | 2,743 | 2,279 | 762 | 1,271 | Identical on all 4 recordings |
| FireRedASR2 | 29,434 | 19,040 | 10,260 | 13,101 | Identical on all 4 recordings |

For FireRedASR2, CPU time fell 35.3% and elapsed time increased 27.7%. Repeating in reverse
order gave CPU totals 33,644 / 18,210 ms and elapsed totals 11,603 / 12,303 ms, again with
identical text (the repeat partially overlapped a development build, so its wall times are less
controlled). This supports less CPU work; it does not measure power rails or phone battery.

**Adopted for background refinement only.** Its corrected text arrives later, but live
SenseVoice text is still inserted promptly. Disabling spinning in SenseVoice saves only 16.9%
CPU in the first run while increasing elapsed time 66.8%, so it is left unchanged.
`VoiceRuntimeOptions` writes the two fixed options atomically in app-private non-backed-up
storage, repairs truncated/stale files, and falls back to the original CPU provider if storage
is unavailable. Benchmark JSON is retained in `build/device-bench/spin-*.json`.

## Accuracy and validation limits

The audio buffer regression compares every sample by raw float bits across 10,000 randomized
appends, prefix drops, growth, compaction and reusable windows. Snapshots remain immutable
after subsequent buffer writes. Pacing tests cover initial preview latency, long audio, Battery
Saver, stopping, quiet input and decode-cost limits. These establish storage and scheduling
properties, not recognition accuracy on every possible recording.

The initial emulator A/B run, before the audio-length pacing addition, returned identical final
Chinese and English transcripts, both exactly matching the checked-in references. Process CPU
time was 25.1 s before and 25.4 s after: no measurable whole-process gain from those storage
changes on these short samples. This counter includes UI / keyboard work and model loading; it
is not a power measurement.

The preview/storage build passed 62 JVM tests, the debug build and privacy check, the Chinese / English
transcript test (both references matched exactly), and all standard-model and high-accuracy
behavior scenarios. The latter explicitly enabled Battery Saver, observed a FireRedASR2
decode, and verified the refined transcript and formatting. Cancel, undo, pauses, cursor
moves and silence timeout passed with both model selections. Final sample-run process CPU
time was 23.4 s; this single run is not sufficient to establish an energy improvement. Logs
and synthetic benchmark output are retained locally in `build/performance-review/`.

With the final worker-scheduling configuration, 65 JVM tests, the debug build and privacy
check passed. Three additional tests cover fixed options, repair/reuse and the default-provider
fallback on unavailable storage. With sleeping workers enabled in normal dictation, the
complete high-accuracy behavior suite passed again, including Battery Saver, and both Chinese /
English final transcripts matched their references exactly. A readback of the private runtime
file confirmed both spinning options were `0`, rather than the default-provider fallback.
The final sample-run process CPU counter was 19.4 s; the single earlier counters above are not
a controlled whole-app energy comparison. All 16 light/dark UI screenshots were generated,
and the changed settings summary was inspected in both themes without clipping.

No physical-phone battery or thermal measurements have been made. No broad paired CER/WER
evaluation of spontaneous dictation has been made for the changed schedule. Keeping the models
and audio rules unchanged, exact buffer tests, and end-to-end samples support the change; they
do not prove a universal zero regression in real microphone conditions.

## Remaining bottlenecks and why they were not changed

- **Final recognition and FireRedASR2 decoding.** These are necessary to retain accuracy.
  Disabling refinement, lowering beam-search effort, using a smaller model, changing
  quantization, or skipping quiet audio inside an utterance would need paired accuracy
  evaluation. No such shortcut is used here.
- **Native VAD long-utterance copying.** In the pinned runtime, the native detector copies the
  accumulating speech segment on each speech window even though the app only requests
  finalized segments. A long segment therefore has growing internal copy overhead. Removing
  that requires rebuilding and validating the native runtime; Java-side scratch reuse cannot
  eliminate it. Batch-feeding multiple windows would change the speech-state observations and
  is not an equivalent fix.
- **Concurrent CPU pools.** SenseVoice uses 2 or 4 threads; FireRedASR2 uses 4, plus a VAD
  thread. On a phone they can contend for fast cores and memory bandwidth. Fewer threads may
  lower peak power but lengthen the active interval, so energy per utterance needs measurement.
  No device-independent claim that fewer threads save energy is justified.
- **Resuming a sentence.** The fast model re-decodes earlier audio when speech resumes within
  the sentence gap, preserving punctuation and sentence context. Simply concatenating cached
  words would change output. The large model already refines only the new utterance and joins
  its words with earlier refinement; that optimization is retained.
- **Cold loads and memory.** Loading 240 MB / 1.2 GB of model files is costly. The large model
  is already loaded only when speech is heard, and idle models are released. Shortening idle
  lifetimes can cause more reloads; extending them competes with other apps for memory. The
  existing 5 / 3 minute lifetimes are retained until phone measurements justify a change.
- **Silence, animation and audio capture.** VAD must continue to hear quiet speech. Its
  threshold is unchanged. Existing 10-second hands-free timeout, quiet waveform rest and
  approximately 30 fps animation already avoid background work; no audio chunk is dropped or
  conflated. An unbounded audio queue remains a deliberate lossless policy; a prolonged native
  stall can grow memory use. A bounded queue that silently drops audio is not acceptable.

## Measuring energy on the target phone

Use the same pinned models, release build configuration, recordings, screen brightness and
power mode for both builds. Keep the starting battery level and temperature comparable; let
the device cool between groups and alternate baseline / optimized runs. Measure cold start
separately from warm dictation. Include short speech, 10–20 second speech, mid-sentence pauses,
silence, mixed Mandarin–English speech and refinement on/off.

Collect process CPU time, first-preview / final / refinement latency, thermal status and
device energy counters or power rails where supported (Perfetto / an external power meter).
Compare energy per minute of captured speech and per completed utterance, including refinement
finishing after the microphone closes. Battery percentage alone is too coarse for a short run;
emulator CPU time is only a development proxy. Compare paired final transcripts and CER/WER
with punctuation / ITN evaluated separately. A thread/provider/runtime change should not ship
until this comparison passes.

## Reproduce the native experiment recordings

After `scripts/fetch-voice-assets.sh --refiner`, generate the four synthetic smoke recordings:

```sh
python3 - <<'PY'
from pathlib import Path
import wave
out = Path("build/performance-review/spin-wavs")
out.mkdir(parents=True, exist_ok=True)
audio = {}
for language in ("zh", "en"):
    with wave.open(f"voice/test-wavs/{language}.wav", "rb") as w:
        audio[language] = w.readframes(w.getnframes())
        params = w.getparams()
for name, data in (("zh", audio["zh"]), ("en", audio["en"]),
                   ("long-zh", audio["zh"] * 3), ("mixed", audio["zh"] + audio["en"])):
    with wave.open(str(out / f"{name}.wav"), "wb") as w:
        w.setparams(params)
        w.writeframes(data)
PY

# Requires the latest debug APK installed on a dedicated test device/emulator.
CPU_SPIN=1 WAVS=build/performance-review/spin-wavs scripts/bench/device-bench.sh \
    spin-refiner-on voice/models/fire-red-asr2-aed-int8 firered_aed
CPU_SPIN=0 WAVS=build/performance-review/spin-wavs scripts/bench/device-bench.sh \
    spin-refiner-off voice/models/fire-red-asr2-aed-int8 firered_aed
```

Reverse the order for a repeat. Compare `runs[].text` exactly and sum `runs[].secondCpuMs`
separately from `runs[].secondMs`. Use `sensevoice` with
`voice/models/sense-voice-small-int8` for the fast-model comparison. These derived clips isolate
runtime scheduling; they do not represent a diverse accuracy test set.
