# Speech models

## Current choice

The standard build uses the models in the table below. The high-accuracy build adds
**FireRedASR2 AED int8** (1.2 GB) as a second stage that re-checks every utterance; see
"Refinement" further down for why and how well that works.

| Role | Model | Size in APK | Source |
|---|---|---|---|
| Recognition | SenseVoice Small, int8 (`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`) | 229 MB | [sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Voice activity detection | Silero VAD v5 | 2 MB | same |
| Runtime | sherpa-onnx 1.13.8 (onnxruntime), CPU | ~20 MB (arm64) | [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) |

Why SenseVoice Small:

- one non-autoregressive model gives text **with punctuation and inverse text normalization**
  (digits, percentages, …) for Mandarin, English and code-switched speech, with automatic
  language detection;
- it is fast enough to be re-run several times per second for live previews
  (see "simulated streaming" in `ARCHITECTURE.md`), so no second, streaming model is needed;
- accuracy is on par with much larger models (below).

Versions and checksums are pinned in `scripts/fetch-voice-assets.sh`; the model is configured in
`VoiceEngine.kt`.

## Benchmark (2026-10)

300 randomly sampled utterances (≥ 1 s) per test set, greedy decoding, int8 models, error rate
counted per Chinese character / per English word with punctuation and case ignored, numbers
excluded. RTF measured on an Apple M-series laptop with 4 threads — useful only for comparing
models with each other.

| Test set | What it represents | SenseVoice Small | X-ASR zipformer (2026-06) | Qwen3-ASR 0.6B |
|---|---|---|---|---|
| AISHELL-1 test | read Mandarin | **3.06 %** | **3.06 %** | 3.58 % |
| WenetSpeech test_net | Mandarin, internet video | 9.59 % | **7.66 %** | 7.74 % |
| WenetSpeech test_meeting | Mandarin, far-field meetings | 9.63 % | **9.15 %** | 9.43 % |
| ASCEND test (mixed only) | spontaneous Mandarin–English code-switching | 12.80 % | **11.28 %** | 15.17 % |
| LibriSpeech test-clean | read English | 3.06 % | **2.70 %** | 3.11 % |
| RTF (lower is faster) | | 0.02 | 0.02 – 0.05 | 0.13 – 0.21 |
| Download size | | 155 MB | 130 MB | 838 MB |
| Punctuation / ITN | | yes / yes | yes / no | yes / no |

Reading the table:

- The three models are within a few points of each other; none is a clear win over SenseVoice
  once speed, size and built-in number formatting are taken into account.
- **X-ASR** (offline zipformer transducer with punctuation) is the strongest alternative: slightly
  more accurate on noisy and code-switched speech, better English casing ("McGrady", "I"), same
  speed. It does not format numbers (ITN would have to be added with sherpa-onnx rule FSTs), puts
  a space after Chinese commas, and its license and long-term maintenance were not verified.
  It is the first thing to try if SenseVoice's accuracy turns out to be the bottleneck.
- **Qwen3-ASR 0.6B** is 6–10× slower and 5× larger without being more accurate on these sets in
  its int8 form. It does spell technical terms better ("GitHub", "pull request").
- Known weakness of SenseVoice: English technical terms inside Chinese sentences
  ("pull request" → "pool request"). See the roadmap in the README.

On-device speed: on an arm64 emulator (Apple silicon host, 4 threads) a decode takes 5–10 % of the
audio duration, and loading the model takes 2–6 s after a cold start (audio is buffered meanwhile,
so nothing is lost). **Numbers from real phones are still missing** — please add them here.

## Second round: every plausible candidate (2026-10)

Same 300 utterances per set, ITN on where a model has it, error rates computed on the utterances
where no model produced digits (256–291 per set). In brackets: 95 % bootstrap interval of the
difference to SenseVoice, in points; an interval that excludes 0 is a real difference.

| Model | AISHELL-1 | Wenet net | Wenet meeting | ASCEND mixed | LibriSpeech | RTF | Size | Punct. |
|---|---|---|---|---|---|---|---|---|
| SenseVoice Small int8 (current) | 2.84 | 9.58 | 9.68 | 14.85 | 3.41 | 0.015 | 155 MB | yes + ITN |
| X-ASR zipformer int8 | 2.84 (−0.5..+0.5) | 7.72 (−2.9..−0.8) | 9.19 (−1.1..+0.1) | 11.17 (−4.8..−2.5) | 2.76 (−1.2..−0.1) | 0.015 | 130 MB | yes |
| Fun-ASR-nano int8 | 2.23 (−1.3..+0.2) | 7.65 (−2.9..−0.9) | 8.47 (−1.9..−0.5) | 11.92 (−4.1..−1.7) | 2.39 (−1.6..−0.3) | 0.07–0.12 | 802 MB | yes |
| **FireRedASR2 AED int8** | **0.85** (−2.5..−1.5) | **5.79** (−4.8..−2.9) | **6.29** (−4.2..−2.6) | 11.24 (−4.9..−2.3) | **1.86** (−2.1..−1.0) | 0.19–0.24 | 799 MB | no, upper-case English |
| Paraformer-zh | 2.15 | 8.86 | 9.00 | 14.01 | 13.65 | 0.015 | 223 MB | no |
| Zipformer CTC zh int8 | 1.96 | 7.28 | 9.23 | 24.30 | 100 | 0.02 | 287 MB | no |
| FireRedASR2 CTC int8 | 3.69 | 9.67 | 9.43 | 18.27 | 10.42 | 0.14 | 496 MB | no |

Also measured and ruled out (raw numbers, AISHELL / Wenet net / meeting / ASCEND / LibriSpeech):
SenseVoice "funasr-nano" variant 2025-12-17 (4.46 / 10.77 / 11.74 / 14.79 / 7.16, no punctuation),
Dolphin small (4.21 / 14.58 / 14.19 / 41.72 / ~100), TeleSpeech (7.07 / 37.87 / 36.87 / 45.94 / –),
Moonshine base zh (6.42 / 35.27 / 55.86 / 37.03 / 72.62), Omnilingual 300M v2
(18.29 / 29.15 / 32.10 / 36.58 / 4.96), Whisper turbo int8 (31 % on AISHELL-1, RTF 0.34; stopped),
Nemotron 3.5 ASR streaming 0.6B int8, 1120 ms chunks, automatic language
(14.31 / 29.76 / 24.75 / 26.93 / 3.11, RTF 0.07–0.11, 680 MB; KeSpeech 57.36, Common Voice
zh-CN 31.98). Nemotron is fine for English and unusable for Mandarin: it drops short utterances
and emits runs of Thai characters on a quarter to a third of the KeSpeech and Common Voice
utterances; forcing the language to `zh` does not help. Its model card reports about 19 % CER
for Mandarin on FLEURS, so this is the model, not the export. It is a streaming model
(`OnlineRecognizer`), which `bench_all.py` does not cover.

Looked at and not measured (2026-10), with the reason: Qwen3-ASR 1.7B (heavier than the 0.6B
model, whose peak memory is already 2.5 GB, and behind FireRedASR2 AED in the FireRedASR2S
report), FireRedASR2-LLM and Xiaomi MiMo-V2.5-ASR (too large for a phone; MiMo is 8B), Cohere
Transcribe 03-2026 (2B; its model card says code-switched audio is handled inconsistently),
GLM-ASR-Nano-2512 (1.5B; sherpa-onnx support not checked). No open model released up to
2026-10 was found that beats FireRedASR2 on Mandarin.
Non-quantized SenseVoice and X-ASR score the same as their int8 versions: quantization is free.

What this says:

- **FireRedASR2 AED is the only model that is clearly more accurate**: roughly a third to two
  thirds fewer errors on Mandarin and half on English. It is also 13× slower than SenseVoice,
  800 MB, has no punctuation and writes English in capitals, so it cannot replace the current
  model; it would have to be a second pass with punctuation and casing restored afterwards.
- **Fun-ASR-nano** is the best model that is usable as-is: consistently 1–3 points better than
  SenseVoice, punctuated, and the only one besides Qwen3 that spelled "GitHub … pull request"
  correctly in our code-switching sample. 5–8× slower, 800 MB.
- **X-ASR** is the only upgrade at the same speed and size; it lacks ITN.
- Mandarin-only models (Paraformer, Zipformer CTC) are slightly better on Mandarin and useless
  for English.
- SenseVoice's ASCEND and LibriSpeech numbers are about 2 and 0.4 points worse here than in the
  first table because ITN was on: part of that is ITN rewriting words, i.e. a scoring artifact.

### Accents and consumer microphones

300 utterances each, common subset without digits. KeSpeech is read Mandarin recorded on phones
by speakers from eight Mandarin dialect regions; Common Voice zh-CN is read speech recorded by
volunteers on their own devices.

| Model | KeSpeech, all | …standard Mandarin (n=56) | …regional accents (n=194) | Common Voice zh-CN |
|---|---|---|---|---|
| SenseVoice Small int8 (current) | 12.24 | 3.11 | 14.92 | 13.94 |
| X-ASR zipformer int8 | 16.69 | 2.24 | **20.94** | 12.21 |
| Fun-ASR-nano int8 | 10.24 | 4.97 | 11.78 | 10.18 |
| Qwen3-ASR 0.6B int8 | 10.38 | 3.23 | 12.48 | 10.74 |
| FireRedASR2 AED int8 | **5.47** | **1.37** | **6.68** | **6.18** |

X-ASR, slightly ahead of SenseVoice on the standard sets, is clearly worse with regional accents.
That rules it out as a like-for-like replacement of the default model.

By utterance length (Mandarin sets pooled): all models are much worse on utterances under 3 s
(SenseVoice 15.1 %, X-ASR 13.9 %, Fun-ASR-nano 13.3 %, FireRedASR2 10.7 %) than on 3–6 s ones
(8.1 / 6.6 / 6.4 / 5.0 %). Short phrases are the weak spot of every model, and keyboards get a
lot of them.

### On a device

`scripts/bench/device-bench.sh`, arm64 emulator on an Apple M1 Pro with 4 cores and 6 GB RAM,
4 threads. The emulator runs at nearly the speed of the host, so a phone will be slower: expect
roughly 1.5–2× for a current flagship and 3× or more for a mid-range phone (an estimate from
single-core benchmark ratios, **not measured**).

| Model | Load | Memory after load / peak | 3 s audio | 6 s | 15 s | 21 s |
|---|---|---|---|---|---|---|
| SenseVoice Small int8 | 1.3 s | 390 / 466 MB | 0.06 s | 0.09 s | 0.25 s | 0.33 s |
| X-ASR zipformer int8 | 2.9 s | 385 / 634 MB | 0.15 s | 0.11 s | 0.23 s | 0.33 s |
| Fun-ASR-nano int8 | 5.0 s | 1154 / 1414 MB | 0.33 s | 0.53 s | 1.6 s | 2.1 s |
| Qwen3-ASR 0.6B int8 | 3.5 s | 1134 / 2516 MB | 0.50 s | 0.67 s | 1.8 s | 3.0 s |
| FireRedASR2 AED int8 | 3.4 s | 1367 / 1834 MB | 0.73 s | 1.1 s | 3.8 s | 5.8 s |

Other things learned while testing:

- X-ASR is Apache-2.0, trained on about a million hours, actively maintained, and supports
  hot words (contextual biasing) with beam search. Beam search alone fixed "GitHub" in our
  code-switching sample; the hot word list did not fix "pull request". It has no ITN, and
  sherpa-onnx's rule-based `itn_zh_number.fst` is not a substitute (it turns "一下" into "1下").
- Fun-ASR-nano's `itn` option did not produce digits in our samples ("九点", "fifty").
- Qwen3-ASR occasionally answers in Traditional Chinese.

### Conclusions

1. **Keep SenseVoice Small as the default.** It is the only model that is fast, small, robust to
   accents, and formats numbers. Nothing of the same size beats it across the board.
2. **X-ASR is not a drop-in upgrade**: better on clean and code-switched speech, clearly worse
   with accents, no number formatting.
3. **The large models are more accurate but cost 1.1–1.8 GB of memory inside the keyboard
   process**, a ~1 GB APK, and seconds of latency. FireRedASR2 AED is the most accurate by a wide
   margin and the most expensive; Fun-ASR-nano gains about 2–3 points for 0.3–2 s of extra
   latency on this emulator.
4. A second pass with a large model is only worth building as an opt-in for phones with plenty
   of memory, and only after `device-bench.sh` has been run on real phones.

### Refinement: fast model for format, accurate model for words

FireRedASR2 AED halves the error rate but returns bare text (no punctuation, upper-case English,
numbers spelled out). `scripts/bench/merge_prototype.py` aligns its output with SenseVoice's for
the same audio and replaces only the words that differ, keeping SenseVoice's punctuation, digits
and casing. Measured on the saved transcripts of both models:

| Test set | SenseVoice | FireRedASR2 | Merged | Punctuation kept | Digits kept |
|---|---|---|---|---|---|
| AISHELL-1 | 2.84 | 0.85 | 0.85 | 357 / 358 | 48 / 57 |
| WenetSpeech net | 9.58 | 5.79 | 5.79 | 598 / 600 | 15 / 16 |
| WenetSpeech meeting | 9.68 | 6.29 | 6.29 | 778 / 781 | 19 / 28 |
| ASCEND mixed | 14.85 | 11.24 | 11.38 | 508 / 512 | 9 / 11 |
| LibriSpeech | 3.41 | 1.86 | 1.95 | 766 / 774 | 14 / 21 |
| KeSpeech (accents) | 12.24 | 5.47 | 5.47 | 351 / 358 | 51 / 66 |
| Common Voice zh-CN | 13.94 | 6.18 | 6.18 | 337 / 340 | 17 / 23 |

The merged text has FireRedASR2's accuracy and SenseVoice's formatting. This makes a two-stage
design possible: insert SenseVoice's result immediately, then replace it in place with the merged
text when the large model has finished. Known flaws of the prototype: a word inserted at a clause
boundary lands after the comma, and English words that only the large model heard come out in
lower case.

Still not measured: any real phone. Run
`WAVS=<dir> scripts/bench/device-bench.sh <key> <model dir> <kind>` with a phone attached and
record the numbers here.

## Cloud reference: Doubao streaming ASR 2.0 (2026-10)

An evaluation only. The app never sends audio or text off the device, and nothing here changes
that; this section records how far a commercial cloud model is ahead of what runs on the phone.

Volcengine's "Doubao streaming speech recognition model 2.0" (`volc.seedasr.sauc.duration`),
whole-utterance endpoint (`bigmodel_nostream`), ITN and punctuation on, measured with
`scripts/bench/bench_volc.py`. Same 300 utterances per set; error rates on the utterances where
none of the three models produced digits; in brackets the 95 % bootstrap interval of the
difference to SenseVoice.

| Model | AISHELL-1 | Wenet net | Wenet meeting | ASCEND mixed | LibriSpeech |
|---|---|---|---|---|---|
| SenseVoice Small int8 | 2.80 | 9.58 | 9.53 | 14.85 | 3.41 |
| FireRedASR2 AED int8 | **0.83** (−2.6..−1.5) | 5.79 (−4.7..−2.8) | 6.28 (−4.0..−2.5) | 11.24 (−4.9..−2.3) | **1.86** (−2.2..−1.0) |
| Doubao streaming ASR 2.0 | 1.34 (−2.0..−1.0) | **5.05** (−5.8..−3.4) | **6.24** (−4.0..−2.5) | **9.19** (−7.0..−4.5) | 2.87 (−1.1..−0.1) |

- On Mandarin it is level with FireRedASR2, the refiner of the high-accuracy build. It is ahead
  only on code-switched speech (9.2 against 11.2), and behind on read English.
- What the numbers do not show: it returns punctuation, ITN, and English with proper casing and
  spacing in one pass ("push 到 GitHub 上了", "Pull Request", "NLP", "R 语言"), which locally takes
  two models and a merge step.
- Latency: the final result arrives 0.6–1.0 s (median) after the last packet when a whole
  utterance is uploaded at once, and about 0.3 s after the end of speech when audio is streamed
  in real time on the bidirectional endpoint (`bigmodel_async` with `enable_nonstream`). That
  endpoint drops the end of an utterance when audio is sent faster than real time, so it was
  not used for the error rates.
- Cost and data: 1 CNY per hour of audio, pay-as-you-go, after 20 free hours. The service terms
  say customer data is not stored or used for training, with exceptions for troubleshooting,
  legal compliance and content moderation; request logs are kept. The audio leaves the device
  either way.

## Reproducing / evaluating another model

```sh
mkdir bench && cd bench
python3 -m venv venv && ./venv/bin/pip install sherpa-onnx numpy soundfile pyarrow jiwer zhconv

# models: extract archives from https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models here
# test sets, as parquet files with embedded audio:
mkdir data
H=https://huggingface.co/datasets
curl -L -o data/aishell1_test_0.parquet        $H/AudioLLMs/aishell_1_zh_test/resolve/main/data/test-00000-of-00003.parquet
curl -L -o data/wenet_test_net_0.parquet       $H/TwinkStart/WenetSpeech/resolve/main/data/test_net-00000-of-00002.parquet
curl -L -o data/wenet_test_meeting.parquet     $H/TwinkStart/WenetSpeech/resolve/main/data/test_meeting-00000-of-00001.parquet
curl -L -o data/ascend_test.parquet            $H/CAiRE/ASCEND/resolve/main/main/test-00000-of-00001.parquet
curl -L -o data/librispeech_test_clean.parquet $H/openslr/librispeech_asr/resolve/main/all/test.clean/0000.parquet

./venv/bin/python ../scripts/bench/bench.py sensevoice ascend_test
```

To evaluate a new model, run `scripts/bench/bench_all.py <key> <model_dir> <kind>` (it covers all
model families sherpa-onnx supports and runs all five sets), and record the numbers in the table above before changing `VoiceEngine.kt`. Then run
`scripts/e2e-voice.sh` on a device: desktop numbers say nothing about load time and memory.
