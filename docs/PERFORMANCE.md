# Voice performance and energy review (2026-10-03)

The largest avoidable cost is repeated whole-prefix recognition for live previews. Final
SenseVoice recognition and the selected FireRedASR2 refinement are retained. This work changes
preview latency and storage overhead, not model weights, quantization, language detection,
inverse text normalization, VAD thresholds, audio rate, endpointing, margins or text merging.

## Follow-up: energy measured on a phone (2026-10-08)

The first measurements of energy rather than CPU time. Nothing in the app was changed.

### Method

Pixel 3 (Snapdragon 845: four Cortex-A75 and four Cortex-A55 cores, 3.6 GB of memory), AOSP
Android 11 userdebug, debug build v0.8.1-1-g11fc5997, both models installed. USB charging was
suspended as root (`echo 1 > /sys/class/power_supply/battery/input_suspend`, written back to 0
afterwards), so that the fuel gauge reports what the phone draws; `current_now` times
`voltage_now` was read about 4.5 times per second over adb. The screen stayed on at a fixed
manual brightness. Energy is the mean power of a window minus the mean power of the same
screen idle just before it, times the length of the window. Idle was 0.74 to 0.88 W, except
before the first run of two benchmark series (1.21 and 1.25 W, noted there).

- **Dictation:** hands-free dictation in the debug build's test field from a WAV file, as
  `scripts/e2e-voice.sh` does it. The recording is 22.3 seconds: the public Chinese clip, the
  English one and the Chinese one again, 2 seconds of silence between them (18.3 seconds of
  speech, three utterances). The window is the recording plus 30 seconds, by which time
  refinement had finished; 15 seconds of idle before it. One dictation to load the models,
  then three measured.
- **Decoding alone:** `VoiceBenchActivity`, as `scripts/bench/device-bench.sh` drives it, each
  recording decoded twice; the window runs from its "loaded" log line to "DONE", so loading
  is not part of it. The idle reference is the same activity started on a model directory
  that does not exist.

The microphone is not part of this (the audio comes from a file), nor is loading a model.

**The temperature of the phone decides more than most settings.** The same configuration
(FireRedASR2, 4 threads, no spinning, the same two recordings) cost 130.8 J on a phone that
had rested (battery 34 °C at the start) and 86.2 J after twenty minutes of benchmarks (38 °C),
while taking 23% longer:
a warm phone runs the same work at lower clock rates, which costs less energy. Only
comparisons that were repeated in alternation are relied on below, and a single run is
called a single run.

### One dictation

| | Energy above idle | CPU time of the keyboard process |
|---|---:|---|
| Refinement off | 36.4 / 36.7 J | `voice-engine` 29.1 s, `RenderThread` 3.6 s, session and VAD 2.5 s, main thread 1.2 s |
| Refinement on, battery at 37 °C | 99.1 / 94.2 / 93.6 J | `voice-refiner` 64 s, `voice-engine` 36 to 41 s, the rest as above |
| Refinement on, battery at 33 °C (one run, 45-second tail) | 146.2 J | `voice-refiner` 51.9 s, `voice-engine` 31.0 s |

The third run without refinement drew 69.6 J for the same CPU time as the other two (36.6 s);
it is taken for something else on the phone and left out.

- **Refinement is most of it:** about 60 J of 95, nearly twice what SenseVoice, the
  interface, VAD and capture need together. 95 J is 0.24% of this phone's 2,915 mAh battery
  for 22 seconds of dictation.
- **The two models get in each other's way on a phone.** With refinement running, the same
  SenseVoice decodes took 9.0 to 10.4 s instead of 7.2 s, and `voice-engine` 36 to 41 s of CPU
  time instead of 29 s. The emulator did not show this (see "A lower priority for the
  refinement threads" below).
- **Previews are already rationed by the cost limit here.** SenseVoice ran 14 to 16 times per
  dictation on 43.5 to 49.1 seconds of audio; on the emulator it was 27 to 30 times.
- **Interface, VAD and capture are small:** about 7 s of CPU time against 29 s for SenseVoice
  alone.

Refinement changed the text, as it is meant to: without it this recording came out with
"开饭时间" for "开放时间" and "good" for "gold".

### SenseVoice: threads and spinning

Seven recordings of 1 to 16.8 seconds (48.3 seconds in all), each decoded twice; raw text
identical in all eight runs. In the order they ran:

| Threads | Spinning | Energy | Window | Second decodes, sum | CPU time of those | Battery |
|---:|---|---:|---:|---:|---:|---:|
| 4 (current) | default | 75.8 J | 14.9 s | 7.05 s | 27.7 s | 34.5 °C |
| 2 | default | 83.5 J | 24.6 s | 11.87 s | 23.5 s | 35.3 °C |
| 3 | default | 78.5 J | 18.4 s | 8.81 s | 26.0 s | 36.0 °C |
| 1 | default | 88.9 J | 49.2 s | 24.88 s | 24.7 s | 35.8 °C |
| 4 | off | 77.0 J | 15.9 s | 7.56 s | 26.6 s | 36.8 °C |
| 2 | off | 67.1 J | 29.4 s | 14.41 s | 27.7 s | 36.8 °C |
| 4 | default | 76.6 J | 15.4 s | 7.31 s | 28.7 s | 37.3 °C |
| 2 | default | 60.5 J | 32.3 s | 15.59 s | 30.9 s | 37.0 °C |

Four threads are the fastest and cost the same both times. Three threads and one thread cost
more and take longer; four threads without spinning cost the same and take 3 to 7% longer. Two
threads gave 83.5 J and 60.5 J for the same work, the cheaper run being 31% slower than the
other, so it shows the phone's state, not a saving. This settles the question left open under
"Fewer SenseVoice threads on phones with eight or more cores": unchanged. CPU time did not
predict energy here: one thread had nearly the lowest CPU time and the highest energy.

### FireRedASR2: threads and spinning

The Chinese and the English clip (12.7 seconds), each decoded twice; raw text identical in
all runs. In the order they ran:

| Threads | Spinning | Energy | Second decodes, sum | CPU time of those | Battery |
|---:|---|---:|---:|---:|---:|
| 4 (current) | off | 130.8 J | 15.76 s | 42.8 s | 37.0 °C |
| 2 | off | 101.3 J | 27.13 s | 46.8 s | 37.0 °C |
| 4 | off | 115.2 J | 17.73 s | 47.9 s | 37.8 °C |
| 2 | off | 98.6 J | 27.69 s | 47.8 s | 37.5 °C |
| 3 | off | 91.9 J | 21.75 s | 49.4 s | 37.8 °C |
| 4 | on | 98.0 J | 17.75 s | 60.2 s | 38.5 °C |

The series started at 34.3 °C; the temperatures are those after each run. The idle reference
of the first row was 1.21 W, so its 130.8 J is if anything too low (144 J against a reference
of 0.8 W). Energy fell from run to run whatever the setting: the last
run, with spinning and 25% more CPU time, cost less than both runs of the current setting
before it. The differences between thread counts are within that drift, and two threads take
1.6 to 1.7 times as long. Nothing here justifies a change. Spinning was not measured in
alternation on the phone; the emulator result that switched it off stands untested here.

### FireRedASR2 on the small cores

The same two recordings with 4 threads and no spinning, with all threads of the process
confined from outside (`taskset -a -p <mask> <pid>` as root, repeated every second) to the
four small cores (mask `0f`) or the four big ones (`f0`). Raw text identical in all runs.
In the order they ran, battery at 37.5 to 38.8 °C throughout:

| Cores | Energy | Second decodes, sum | 5.6 s clip | 7.2 s clip |
|---|---:|---:|---:|---:|
| Any (current) | 86.2 J | 19.41 s | 7.69 s | 11.73 s |
| Small only | 69.1 J | 35.63 s | 14.65 s | 20.98 s |
| Any (current) | 94.7 J | 18.80 s | 7.69 s | 11.12 s |
| Small only | 69.2 J | 35.59 s | 14.60 s | 20.99 s |
| Big only | 107.6 J | 17.51 s | 6.38 s | 11.14 s |

On the small cores refinement costs 20 to 27% less energy and takes 1.9 times as long; both
runs agree to 0.1 J. With refinement at about 60 J of a 95 J dictation, that would be about
15% of the dictation. The first row's idle reference was 1.25 W; against 0.8 W it would be
104 J, and the saving larger.

**Not adopted, and not built.** What it would cost: the corrected text arrives about twice as
late (a 5.6-second sentence after about 14.6 s instead of 7.7 s). What is not known:

- Loading the model took 26.5 s on the small cores against about 10 s, so loading would have
  to stay where it is; only decoding was measured.
- The measurement confined the process from outside. Inside the app the candidate is ONNX
  Runtime's `session.intra_op_thread_affinities` in the configuration file that
  `VoiceRuntimeOptions` already writes; whether the pinned sherpa-onnx passes that entry on
  was not checked, the thread that calls the runtime is not covered by it, and the app would
  have to find out which cores are the small ones.
- Whether it also ends the slowdown of SenseVoice during refinement (above) was not measured.
- Another chip may answer differently, in particular one with three kinds of cores.

It could also apply only in Battery Saver or on a hot device. This is a decision about how
late corrections may arrive, not only a measurement.

### What this leaves

On this phone the order is: refinement (about 60 J per 22-second dictation), SenseVoice
(about 30 J), everything else (a few joules). The only candidate that showed a repeatable saving is running refinement on the
small cores. Thread counts and spinning of either model, fewer previews, and the waveform are
not worth changing on this evidence.

Not measured: the microphone, cold loads, a session with long silences, push-to-talk, a
release build, and any phone other than this one. Results are single runs or pairs, not
statistics.

## Follow-up: where the CPU time goes, and the large model's lifetime (2026-10-07)

### Per-thread profile

The CPU time of every thread of the keyboard process was read from `/proc/<pid>/task/*/stat`
before and after one hands-free dictation of a WAV file in the debug build's test field. Native
worker threads keep the name of the thread that created them, so `voice-engine` is SenseVoice
(previews and final text) and `voice-refiner` is FireRedASR2. Arm64 Android 16 emulator on an
Apple M1 Pro; high-accuracy refinement on; both models already loaded. The recording holds 15.1
seconds of speech in three utterances with two 2-second pauses (the two public test recordings).

| Thread | 8 cores (4 SenseVoice threads) | 4 cores (2 SenseVoice threads) |
|---|---:|---:|
| `voice-engine` | 9.1 s (47%) | 5.6 s (34%) |
| `voice-refiner` | 7.2 s (37%) | 8.2 s (50%) |
| `RenderThread` (waveform, text field) | 1.8 s (9%) | 1.5 s (9%) |
| Session and VAD (`DefaultDispatcher`) | 0.6 s (3%) | 0.6 s (4%) |
| Main thread | 0.4 s (2%) | 0.4 s (2%) |
| Whole process | 19.3 s | 16.4 s |

SenseVoice ran 27 to 30 times per dictation and was given 77 to 86 seconds of audio for those
15.1 seconds of speech; the three final decodes account for 19.9 seconds of it, previews for the
rest. Capture, VAD, the session and the main thread together stay near 5%: the Kotlin side has
no avoidable work left that would show in this profile. The two emulator configurations are
separate boots and are not a controlled comparison of core counts.

### Reading the large model again

The same 5.6-second recording was dictated on the 4-core emulator with the large model in
memory, and again after it had been freed by its 3-minute idle timer (SenseVoice still loaded,
model files still in the page cache):

| | Whole process CPU | `voice-refiner` CPU | Wait before refinement starts |
|---|---:|---:|---:|
| Large model in memory | 5.6 s | 2.6 s | none |
| Freed, read again | 8.8 s | 5.7 s | 4.2 s |
| Read again right after boot (cold page cache; SenseVoice is loaded as well) | 16.3 s | 10.9 s | 14.8 s |

Reading the model costs more CPU time than refining the utterance (3.1 s against 2.6 s), so
somebody who dictates one message every few minutes paid for it with every message. The model
now stays for 3 minutes as before, and beyond that for up to 10 minutes while the system reports
at least 2 GB available and no memory pressure, asked again every minute (`VoiceModelResidency`).
A device without that much to spare behaves exactly as before. Occupied memory that nothing else
needs costs no energy; the price is about 1.4 GB held for up to 7 more minutes on devices that
have it. The timer itself does not advance while the device
is in deep sleep, so the model can stay through a sleep and for the rest of the timer's delay
(at most 3 minutes) after waking; the idle time, however, is measured on a clock that includes
sleep, so that next check frees a model that has been idle for 10 minutes without asking again.
The old fixed timer behaved the same way across sleep. Suspend and resume were not exercised on
a device; the JVM tests cover the decision for an idle time beyond the maximum.

On the emulator started with 8 cores and 9 GB (5.5 GB available with the model loaded), a
dictation 4.5 minutes after the previous one found the model in memory: no load, 2.7 s of
`voice-refiner` CPU instead of 5.6 s. Left alone after that, it was freed 600.0 seconds after its last use. On the 6 GB emulator the
system still reported 2.4 GB available with the model loaded, so it stayed there as well.
Freeing it at 3 minutes when less is available is covered by the JVM tests of the policy, not
by a device run.

### Measured and not adopted

**Graph optimization level of the large model.** If graph optimization dominated loading, a
lower level would shorten it. Load time on the 4-core emulator, model files in the page cache:

| `GraphOptimizationLevel` | 99 (default) | 2 | 1 | 0 |
|---|---:|---:|---:|---:|
| Load | 3.65 s | 3.55 s | 4.19 s | 3.34 s |

Decode times and raw text were the same at every level. Loading is not graph optimization;
nothing to gain, and a lower level would need an accuracy comparison. Unchanged.

**A lower priority for the refinement threads.** One early run on the 4-core emulator, shortly
after boot, showed previews taking 400 to 670 ms instead of 60 to 180 ms while the previous
utterance was being refined. Running the refiner's thread just above Android's background
priority (nice 9; the runtime's worker threads inherit it, checked with `ps -T`) was tried
against that. Three warm dictations of 18.3 seconds of continuous speech per build and round,
builds alternated; sums of the decode times each dictation logged:

| | SenseVoice decodes | Refinement decodes |
|---|---:|---:|
| Before, round 1 | 2.95 / 2.99 / 2.82 s | 5.55 / 5.33 / 5.27 s |
| Lower priority, round 1 | 3.10 / 2.96 / 2.83 s | 6.15 / 5.55 / 5.41 s |
| Before, round 2 | 3.07 / 2.92 / 2.97 s | 5.37 / 5.19 / 5.26 s |
| Lower priority, round 2 | 2.97 / 2.81 / 2.85 s | 5.47 / 5.20 / 5.20 s |

Live recognition was not faster and refinement was, if anything, slower. In a warm process the
two recognizers do not get in each other's way here, the slow run was not reproduced, and a
lower priority would delay corrections whenever another app is busy. Unchanged.

**Fewer SenseVoice threads on phones with eight or more cores.** `device-bench.sh` with
`THREADS` 1 to 4 on the 8-core emulator, seven recordings from 1 to 18.3 seconds, warm second
decode, each setting run twice except one thread. Raw text was identical in all runs.

| Threads | Decode time, sum | Process CPU, sum | 1 s recording | 3 s | 10.5 s | 18.3 s |
|---|---:|---:|---:|---:|---:|---:|
| 4 (current on 8+ cores) | 765 / 779 ms | 2,839 / 2,919 ms | 43 ms | 52 ms | 158 ms | 274 ms |
| 3 | 964 / 1,022 ms | 2,737 / 2,920 ms | 44 ms | 69 ms | 193 ms | 367 ms |
| 2 | 1,170 / 1,177 ms | 2,255 / 2,255 ms | 35 ms | 75 ms | 252 ms | 452 ms |
| 1 | 2,016 ms | 1,994 ms | 57 ms | 125 ms | 427 ms | 804 ms |

Two threads use 22% less CPU time and take 52% longer; three save nothing. Two threads are
both faster and cheaper only for the first second of an utterance, and the count is fixed per
loaded model. A final decode of a long sentence would take about 0.1 to 0.2 s longer on this
emulator and more on a phone. That is the same kind of trade that was rejected for SenseVoice
worker spinning, and an emulator's identical cores say little about a phone's mix of fast and
slow ones. Unchanged until measured on a phone as described under "Measuring energy on the
target phone". Measured on a Pixel 3 on 2026-10-08 (see "SenseVoice: threads and
spinning" above): four threads stay.

### Validation

All 141 JVM tests (seven new ones for `VoiceModelResidency`), the debug build and the privacy
check passed. On the arm64 Android 16 emulator: both editor transcripts matched their references
exactly, all 28 standard-model and all 35 high-accuracy interaction checks passed, and the UI
screenshots were generated (no visible change). The model's lifetime was observed on the
emulator as described above. Nothing was measured on a phone: neither its energy nor how much
memory it reports as available with the model loaded.

## Follow-up: preview scheduling across a pause (2026-10-04)

Two pacing states could make a pause feel sluggish. A voiced audio observation remained
latched until the next preview, authorizing a decode after newer audio had become quiet.
Conversely, an unchanged transcript retained double-length backoff when speech resumed.

`PartialPacer` now gates previews on the latest observed level, retaining the existing
1.5-second relative-loudness fallback for a soft voice after a loud transient. Quiet-to-voiced
resumption clears unchanged-result backoff, including after a quiet fallback decode. It
still waits for the ordinary audio-length interval and twice the previous decode cost;
stopping still suppresses every preview. Continuous voiced audio without a pause keeps
exactly the previous policy, including unchanged-result backoff and Battery Saver pacing.
No models, endpoint thresholds, final decoding, refinement or sample storage are changed.

The five added JVM regressions cover stale voiced observations, normal/saving resumption,
resumption after a quiet fallback, compute/stopping limits and sustained voiced backoff.
A controlled state with 12 seconds of audio and negligible decode cost allows the next
preview 900 ms after an unchanged decode following resumption, rather than 1,800 ms;
Battery Saver allows it after 1,800 ms rather than 3,600 ms. These are scheduling thresholds,
not measured phone latency. Existing native decodes cannot be interrupted.

This avoids previews authorized only by stale speech and moves useful work closer to new
speech. It is not a guarantee of lower total energy for every pattern: clearing backoff can
produce an additional useful preview or change the lengths of decoded prefixes. The existing
per-preview compute idle limit remains. Real-phone energy and timing remain unmeasured;
do not describe deterministic scheduling tests as a battery benchmark.

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
The final startup-order build also passed the 83-test local/build/privacy checks, the complete
24-check high-accuracy interaction suite (including Battery Saver refinement), and exact Chinese
and English editor transcripts. With a three-second initial UI callback stall, all four probes
read during the stall and still matched the baseline audio/text fields exactly. All four cold
pre-cancelled probes opened no source, captured no samples, loaded no standard model and emitted
no final text. Physical microphone tail/cold-start and target-phone energy checks remain pending.

The final standard-model suite passed all 18 interaction checks as well. A stricter transcript
review found SenseVoice alone rendered the English reference's "gold" as "code" in the warm
editor scenario (4.5% normalized error, below the existing script's 15% passing threshold).
The pre-optimization `08da3041` APK was rebuilt and tested on the same emulator with the
refiner absent: it returned exactly the same Chinese and English editor text, including that
error. The final exact-reference editor run after the high-accuracy suite had the large model
available; that configuration returned the correct word on this sample.
Do not confuse a threshold pass or refinement-enabled run with a perfect standard-model
transcript. Cold standalone probes still matched raw final text and audio exactly; these
different scenarios are reported separately. Paired logs and both APKs are retained in the
workspace's `outputs/local-voice-ime-power-review/validation/` directory.

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

A first set of such measurements, with the battery's own current as the meter, is under
"Follow-up: energy measured on a phone (2026-10-08)"; its method works on any device that lets
charging be suspended while adb stays connected. Alternate the builds or settings compared and
note the battery temperature of every run: it moved the result of one unchanged setting by a
third.

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
