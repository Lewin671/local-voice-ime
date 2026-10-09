# Speech models

## Current choice

Recognition is done by **SenseVoice Small**. **FireRedASR2 AED int8** (1.2 GB) is optional and
works as a second stage that re-checks every utterance; see "Refinement" further down for why
and how well that works. Neither is part of the APK: the app downloads them
(*Settings → Voice input*, see [Downloadable models](#downloadable-models)), and without the
first there is no voice input.

| Role | Model | Size | Where it comes from |
|---|---|---|---|
| Recognition | SenseVoice Small, int8 (`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`) | 240 MB | downloaded by the app |
| Voice activity detection | Silero VAD v5 | 2 MB | in the APK; [sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Runtime | sherpa-onnx 1.13.8 (onnxruntime), CPU | ~20 MB (arm64) | in the APK; [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) |

Why SenseVoice Small:

- one non-autoregressive model gives text **with punctuation and inverse text normalization**
  (digits, percentages, …) for Mandarin, English and code-switched speech, with automatic
  language detection;
- it is fast enough to be re-run several times per second for live previews
  (see "simulated streaming" in `ARCHITECTURE.md`), so no second, streaming model is needed;
- accuracy is on par with much larger models (below).

Versions and checksums are pinned in `VoiceModels.kt` (what the app downloads) and
`scripts/fetch-voice-assets.sh` (runtime, VAD, and the same models for tests); the model is
configured in `VoiceEngine.kt`.


## Downloadable models

Speech models are kept out of the APK, which would otherwise be 300 MB and more; they are listed
in `VoiceModels.kt` and fetched by the app.

| Model | Files | Source | License |
|---|---|---|---|
| SenseVoice Small int8 | `model.int8.onnx` (239 MB), `tokens.txt` | [ModelScope `pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue`](https://www.modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue) | FunASR Model Open Source License Agreement 1.1 |
| SenseVoice Small int8, fine-tuned (2026-10-09) | `model.int8.onnx` (239 MB), `tokens.txt` | [GitHub release `model-20261009` of `Lewin671/sensevoice-finetune`](https://github.com/Lewin671/sensevoice-finetune/releases/tag/model-20261009) | FunASR Model Open Source License Agreement 1.1 |
| FireRedASR2 AED int8 | `encoder.int8.onnx` (817 MB), `decoder.int8.onnx` (417 MB), `tokens.txt` | [ModelScope `csukuangfj/FireRedASR2-AED-onnx`](https://www.modelscope.cn/models/csukuangfj/FireRedASR2-AED-onnx), directory `aed/` | Apache-2.0 |

The FireRedASR2 files are byte-identical to those in sherpa-onnx's GitHub release archive
`sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26` (same SHA-256), published file by file by the
same maintainer. The SenseVoice files are byte-identical to those in the release archive
`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`, but the sherpa-onnx maintainer does
not publish them on ModelScope: the repository used is a third party's copy, the most
downloaded of several identical ones (2026-10). That is acceptable because the app trusts the
pinned SHA-256, not the host; if the repository disappears, any other copy with the same
checksums can take its place by changing `baseUrl`. ModelScope was chosen because it is reachable from mainland China without a
proxy (GitHub releases and Hugging Face are not, reliably), needs no account, and supports
resuming (HTTP range requests). FireRedTeam's own ModelScope repository only has the PyTorch
weights, which sherpa-onnx cannot load.

The fine-tuned SenseVoice Small is the standard model trained further on 24 minutes of one
speaker's dictation (Mandarin with English technical terms) with
[sensevoice-finetune](https://github.com/Lewin671/sensevoice-finetune), whose README has the method and the measurements: on that
speaker's held-out utterances 2.94 % → 1.73 % errors in five-fold cross-validation and hardly
any gain on a later day with new subjects; on the seven public sets used here slightly worse
on six, measurably on KeSpeech (12.15 % → 13.63 %), and better on ASCEND (14.80 % → 13.50 %),
which that README does not take for a general gain. (0.9.0 offered an earlier training run of
the same data, `model-20261008`; the two are within noise of each other.)
It is offered as an example of what training on one's own recordings gives, not as a better
model for everyone: the settings say so, and the standard model stays the default for anyone
who does not download it. The app knows that version by itself; when the model is trained
again, the newer release is found by *Check for a newer fine-tuned model* in the settings and
downloaded there, without a new version of the app (`VoiceTunedStore`; every release of that
repository must therefore be a model, tagged `model-<yyyymmdd>` with `model.int8.onnx` and
`tokens.txt`). When it is installed and *Use the fine-tuned model* is on, it
recognizes instead of the standard model (`VoiceModels.recognition`); same graph, size, speed
and memory. It is hosted on GitHub, which breaks the rule below about mainland China: it is
optional, and the app already depends on GitHub for its own updates.

To offer another model for download:

1. benchmark it as described below and record the numbers here;
2. host: an official repository of the model's or the converter's authors, reachable from
   mainland China, plain HTTPS GET with range support, license allowing the use;
3. add a `VoiceModel` to `VoiceModels.all` with the size and SHA-256 of every file (they are
   what the app trusts; the URL is not), and the code that loads it;
4. record it in `NOTICE.md`, `app/licenses/libraries/` and `docs/PRIVACY.md` (the host is named
   there).

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

Looked at and not measured (2026-10), with the reason: FireRedASR2-LLM and Xiaomi MiMo-V2.5-ASR (too large for a phone; MiMo is 8B), Cohere
Transcribe 03-2026 (2B; its model card says code-switched audio is handled inconsistently),
GLM-ASR-Nano-2512 (1.5B; sherpa-onnx support not checked). Qwen3-ASR 1.7B was first skipped
as too heavy and later measured on a Mac; see "Qwen3-ASR 1.7B" below. No open model released up to
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

### A code-switching fine-tune: TEA-ASR-1.1-mini (2026-10)

[TEA-ASR-1.1-mini](https://huggingface.co/JacobLinCool/TEA-ASR-1.1-mini) is Qwen3-ASR 0.6B with a
merged LoRA, trained on under 10 hours of Taiwan Mandarin and Mandarin–English code-switched
speech (MIT; published by one developer, used by a few Taiwanese subtitle and meeting-transcript
projects, no independent benchmark found). There is no sherpa-onnx export, so it and its base
were both run with the `qwen-asr` PyTorch package, fp32, automatic language
(`scripts/bench/bench_qwen_pt.py`). Same 300 utterances per set, common subset without digits;
the last column is the difference to the base with its 95 % bootstrap interval.

| Test set | TEA-ASR-1.1-mini | Qwen3-ASR 0.6B (base) | SenseVoice | FireRedASR2 AED | TEA − base |
|---|---|---|---|---|---|
| ASCEND mixed | **10.35** | 12.59 | 14.85 | 11.26 | −2.24 (−3.33..−1.26) |
| AISHELL-1 | 2.34 | 2.20 | 2.84 | **0.85** | +0.13 (−0.19..+0.47) |
| WenetSpeech net | 8.64 | 7.78 | 9.58 | **5.79** | +0.85 (−0.11..+2.25) |
| WenetSpeech meeting | 10.40 | 9.07 | 9.68 | **6.29** | +1.33 (+0.74..+1.94) |
| LibriSpeech | 2.62 | 2.49 | 3.41 | **1.86** | +0.13 (−0.26..+0.49) |
| KeSpeech (accents) | 8.55 | 8.35 | 12.20 | **5.46** | +0.20 (−0.48..+0.90) |
| Common Voice zh-CN | 9.27 | 8.60 | 13.58 | **6.05** | +0.67 (+0.02..+1.31) |

- **The fine-tune does what it says on code-switched speech and nothing else.** It removes about
  a sixth of the base model's errors on ASCEND ("entry level", "girls night", "hard choice" where
  the base wrote Chinese sound-alikes) and is level or slightly worse everywhere else, clearly
  worse on far-field meetings.
- **The ASCEND gain is partly a home advantage**: the ASCEND training split is in its training
  data, so this is in-domain for TEA and out-of-domain for every other model in the table. Against
  FireRedASR2 the difference on ASCEND is within noise (−0.79, −1.91..+0.24, on the 296
  utterances the two share), while FireRedASR2 is 1.7–4 points better on every Mandarin set.
- **Its output is not usable as typed text.** It writes Traditional Chinese with Taiwan vocabulary
  (257 of 300 ASCEND transcripts), almost no punctuation (9 marks on ASCEND against 670 for the
  base; 34 against 808 on LibriSpeech), and lower-case English. Forcing `language="Chinese"`, as
  its model card recommends, changes nothing (10.47 on ASCEND). It could only serve as a
  words-only second stage, like FireRedASR2.
- Cost is that of Qwen3-ASR 0.6B: 5–10× slower than SenseVoice and 2.5 GB peak memory on a device.

Verdict: **not adopted.** As a refiner it would trade a code-switching gain that is not
distinguishable from FireRedASR2's for an error rate on Mandarin that is 1.5–1.7× as high
(3× on AISHELL-1).

A side result: the base model scored better here than in its sherpa-onnx int8 form above
(KeSpeech 8.35 against 10.33, Common Voice 8.60 against 10.32 on the same utterances; ASCEND
12.7 against 15.2 on slightly different subsets). Either int8 quantization or the export costs
Qwen3-ASR about two points, unlike SenseVoice and X-ASR. The fp32 model is still behind
FireRedASR2 on everything but code-switching, so this does not change any conclusion.

### Qwen3-ASR 1.7B, 4-bit (2026-10)

The larger Qwen3-ASR, to see what the size buys. There is no sherpa-onnx export; this is the MLX
conversion [mlx-community/Qwen3-ASR-1.7B-4bit](https://huggingface.co/mlx-community/Qwen3-ASR-1.7B-4bit)
(Apache-2.0, 1.6 GB) run with mlx-audio 0.5.7 on the GPU of an Apple M1 Pro, one utterance at a
time, automatic language (`scripts/bench/bench_mlx.py`). MLX runs on Apple silicon only, so this
measures the model, not something a phone can run. Same 300 utterances per set, common subset
without digits (250–291 per set); the last two columns are differences with their 95 % bootstrap
intervals.

| Test set | Qwen3-ASR 1.7B 4-bit | FireRedASR2 AED | SenseVoice | Fun-ASR-nano | Qwen3-ASR 0.6B fp32 | 1.7B − FireRedASR2 | 1.7B − SenseVoice |
|---|---|---|---|---|---|---|---|
| AISHELL-1 | 1.75 | **0.85** | 2.84 | 2.23 | 2.20 | +0.90 (+0.51..+1.34) | −1.09 (−1.52..−0.64) |
| WenetSpeech net | 9.12 | **5.79** | 9.58 | 7.65 | 7.78 | +3.32 (+2.24..+4.61) | −0.46 (−1.80..+0.93) |
| WenetSpeech meeting | 8.07 | **6.29** | 9.68 | 8.47 | 9.07 | +1.78 (+1.08..+2.53) | −1.61 (−2.43..−0.82) |
| ASCEND mixed | 12.10 | **11.24** | 14.85 | 11.92 | 12.59 | +0.86 (−0.71..+2.54) | −2.75 (−4.40..−0.93) |
| LibriSpeech | 2.27 | **1.86** | 3.41 | 2.39 | 2.49 | +0.41 (−0.05..+0.84) | −1.14 (−1.75..−0.56) |
| KeSpeech (accents) | 6.68 | **5.47** | 12.24 | 10.24 | 8.40 | +1.21 (+0.20..+2.35) | −5.56 (−7.14..−4.02) |
| Common Voice zh-CN | 6.70 | **5.39** | 13.22 | 9.42 | 7.99 | +1.31 (+0.26..+2.38) | −6.51 (−7.91..−5.17) |

Cost on the Mac: 2.7–4.4 s to load, 1.5 GB of weights in memory, 2.8 GB peak, RTF 0.08–0.12.
Median / 95th percentile time per utterance: 0.30 / 0.41 s for audio under 3 s, 0.42 / 0.57 s
for 3–6 s, 0.58 / 0.82 s for 6–12 s, 1.15 / 1.69 s above 12 s.

- **Clearly better than SenseVoice, still behind FireRedASR2.** Errors are roughly halved on
  accented speech and consumer microphones, and significantly lower on every set but WenetSpeech
  net. Against FireRedASR2 it is 0.9–3.3 points worse on every Mandarin set and level, within
  noise, on code-switching and English.
- **Little gain over the 0.6B model**: 0.5–1.7 points better on five sets, level on ASCEND and
  LibriSpeech within noise, and 1.3 points worse on WenetSpeech net (+0.30..+2.44). The fp32
  1.7B model was not run, so whether 4-bit quantization causes that is not known.
- **Its text is usable as typed**: punctuation, English casing, and the code-switching sample
  came out exactly right ("我刚刚把代码 push 到 GitHub 上了，你帮我 review 一下这个 pull request。").
  It still answers in Traditional Chinese now and then (13 of 2100 transcripts), spells numbers
  out ("九点", "fifty"; digits in 6 transcripts), and never returned empty or looping output.

Verdict: **not adopted.** As a refiner it is less accurate than FireRedASR2 on Mandarin and at
least as heavy (the 0.6B int8 model already peaks at 2.5 GB on a device), and no phone runtime
for it was measured. What it would save is the merge step, since it punctuates by itself.

## Hot words in the prompt: Fun-ASR-nano (2026-10)

Neither model the app uses can be told which names and terms a user cares about: sherpa-onnx
applies its hot word files to transducer models only. Fun-ASR-nano and Qwen3-ASR take a list of
hot words in their prompt instead (`hotwords` in their sherpa-onnx configuration). Fun-ASR-nano
was measured, as it formats its output and could take FireRedASR2's place as the refiner:
`sherpa-onnx-funasr-nano-int8-2025-12-30`, sherpa-onnx 1.13.8, `scripts/bench/bench_hotwords.py`,
on 300 ASCEND utterances (English terms in Mandarin sentences) and 1000 of AISHELL-1 (names of
people, places and organisations). The lists are what a user would write down: the words of the
reference transcripts that the model got wrong without hot words, 70 for ASCEND (said 144 times)
and 44 for AISHELL-1 (said 62 times).

| Hot word list | ASCEND: listed words right | utterances better / worse | broken | error rate | AISHELL-1: listed words right | better / worse | broken | error rate |
|---|---|---|---|---|---|---|---|---|
| none | 48 of 144 | – | 0 | 12.09 | 16 of 62 | – | 0 | 3.49 |
| 10 words that nobody says (control) | 68 of 144 | 49 / 39 | 0 | 11.44 | 19 of 62 | 15 / 23 | 0 | 3.61 |
| the list in slices of 10, summed | 82 of 144 | 45–53 / 29–49 each | 0–2 each | 11.27–21.16 | 19 of 56 (4 slices) | 12–15 / 22–27 each | 0–1 each | 3.55–3.63 |
| the whole list (70 / 44 words) | 81 of 144 | 56 / 36 | 1 | 14.70 | 20 of 62 | 15 / 25 | 1 | 3.72 |
| the list plus 50 unrelated words | 65 of 144 | 45 / 60 | 20 | 29.25 | 12 of 62 | 8 / 419 | 310 | 50.68 |
| the list plus 119 unrelated words | 0 | 2 / 296 | 285 | 100 | 0 | 0 / 925 | 930 | 100 |
| per utterance: only the listed words said in it | 86 of 144 | 43 / 12 | 0 | 10.71 | 32 of 62 | 14 / 1 | 1 | 3.37 |
| the same plus 4 unrelated words | 91 of 144 | 43 / 11 | 0 | 10.49 | 34 of 62 | 15 / 2 | 1 | 3.37 |

"Listed words right" counts, over the utterances where a listed word is said, how often the
transcript has it; in the control row no listed word is said, and the figure is for the words
of the full list. "Broken" is an empty transcript or a loop ("home home home …"). Error rates as
in the tables above, on the utterances without digits in any transcript.

- **A fixed list does little.** Most of what looks like a gain comes from the other prompt that
  sherpa-onnx uses as soon as there is any hot word: with ten words that nobody says, the hard
  words are right 68 times instead of 48, and with the right words on the list 82 times. For
  Chinese names the list adds next to nothing over the control (5 more right of 56, against 3
  of 62). It also changes one ASCEND utterance in three, for the worse nearly as often as for
  the better, and switches some numbers between digits and characters.
- **The list has to be short.** This export has 512 tokens for prompt and audio together. From
  about a hundred words on, the transcripts are empty or loops; 70 words already cost accuracy.
- **Hot words that were not said are not written**: at most a handful of cases per run, no more
  than without hot words. The damage is to the rest of the text, not invented hot words.
- **A list made for the utterance works**: with exactly the listed words that are said, half of
  them come out right (86 of 144, 32 of 62), few utterances get worse, and nothing breaks. Four
  unrelated words next to them do no harm. This is what the "retrieve, then prompt" designs in
  the literature do: a first transcript is used to look up similar-sounding entries in a large
  personal vocabulary, and only those go into the prompt. The numbers here are its upper bound,
  with a retrieval step that never misses and never adds a similar-sounding wrong word.
- **Against the current pipeline it is not an upgrade.** On the same 144 ASCEND occurrences,
  FireRedASR2 without any hot word is right 89 times, and the merged text the app produces 88
  times. Of the 80 occurrences of listable words that the merged text gets wrong, Fun-ASR-nano
  is right on 26 without hot words and on 50 with the per-utterance list. On Mandarin it stays
  far behind FireRedASR2 (AISHELL-1, 156 common utterances: 2.36 against 0.96).
- A list of 10 to 70 words makes decoding 1.5 to 3 times slower (the prompt is longer); a list
  of one to five words costs nothing measurable. The runs shared the machine, so this is rough.

Verdict: **not adopted.** A fixed hot word list is not worth offering. A per-utterance list would
fix about half of the words the user lists, but only as a third model next to FireRedASR2 (the
two do not fit in memory together) or in exchange for FireRedASR2's accuracy on everything
else. Not measured: a real retrieval step, a user's own vocabulary and recordings, an export
with a longer context, Qwen3-ASR's `hotwords`, and whether sherpa-onnx can change the hot words
per utterance (here a recognizer was created for each list).

## Punctuation from a separate model? (2026-10)

FireRedASR2 belongs to a system, FireRedASR2S, that punctuates with a model of its own:
FireRedPunc, a BERT-base token classifier (407 MB of PyTorch weights; a third-party weight-only
8-bit ONNX export, `jiangzhuo9357/fireredpunc-onnx`, is 163 MB). sherpa-onnx does not run it; it
ships the older CT-Transformer (64 MB int8). Would either punctuate better than SenseVoice, whose
punctuation the refinement keeps?

`scripts/bench/punct_eval.py` scores whole pipelines against references that carry punctuation:
300 utterances each of FLEURS Mandarin, FLEURS English and Common Voice zh-CN. Hypothesis and
reference are aligned on their words and the mark after each aligned word is compared. F1 over all
marks:

| Pipeline | FLEURS zh | Common Voice zh-CN | FLEURS en |
|---|---|---|---|
| SenseVoice alone | 84.8 | 89.1 | 77.7 |
| **FireRedASR2 words, SenseVoice format (the app)** | **84.3** | **89.3** | **78.5** |
| …punctuation replaced by FireRedPunc | 86.0 | 89.8 | 77.5 |
| …punctuation replaced by CT-Transformer | 77.7 | 84.5 | 73.0 |
| FireRedASR2 + FireRedPunc, no SenseVoice | 85.7 | 89.9 | 77.5 |
| FireRedASR2 + CT-Transformer, no SenseVoice | 77.8 | 84.3 | 73.1 |
| X-ASR offline alone | 86.7 | 79.9 | 81.1 |
| FireRedASR2 words, X-ASR offline format | 86.3 | 80.1 | 81.5 |
| X-ASR streaming (480 ms) alone | 59.9 | – | 59.3 |
| Reference words + FireRedPunc (upper bound) | 88.7 | 90.4 | 81.3 |

- **FireRedPunc is level with SenseVoice**: +1.7 and +0.5 on the Mandarin sets, −1.0 on English.
  That does not pay for 163 MB more to download, 50–260 ms per utterance on a laptop (short
  Common Voice sentences to long FLEURS ones), and a BERT tokenizer and a direct onnxruntime
  session in Kotlin.
- It could not replace the merge either: it knows four marks (`，。？！`, no `、；：`), and digits
  and casing would still have to come from SenseVoice (capitalised words, F1 on FLEURS en: 14.6
  for FireRedASR2 + FireRedPunc with sentence-initial capitals restored, 78.3 for the merge).
- CT-Transformer is clearly worse than SenseVoice's own punctuation.
- X-ASR's offline model as the source of the format is 2–3 points ahead on the long FLEURS
  sentences and 9 behind on the short Common Voice ones, where it often leaves out the final
  full stop (final mark right in 73 % of the sentences, SenseVoice 91 %). Dictation is mostly
  short sentences, and X-ASR has no ITN, so that is not a gain either.
- The streaming X-ASR model rarely closes a sentence (full stop F1 8 on FLEURS zh, 53 on en): its
  punctuation cannot be the final text's.
- Not measured: question marks (none of the 900 references has one) and spontaneous dictation;
  all three sets are read speech.

Decision: keep SenseVoice's punctuation.

## A streaming model for the preview? (2026-10)

The preview is "simulated streaming": SenseVoice re-decodes the utterance so far a few times per
second (`ARCHITECTURE.md`). Would a real streaming model do that job better? Every streaming
Mandarin–English model sherpa-onnx offers was run with `scripts/bench/bench_stream.py`, which
feeds the audio 100 ms at a time. Same 300 utterances per set as above; error rates on the
utterances where none of the models in the table produced digits (250–291 per set).

| Model | AISHELL-1 | Wenet net | Wenet meeting | ASCEND mixed | LibriSpeech | KeSpeech | Common Voice zh-CN | RTF | Size |
|---|---|---|---|---|---|---|---|---|---|
| SenseVoice Small int8, whole utterance (the app) | **2.84** | 9.58 | **9.68** | 14.85 | 3.41 | **12.24** | **13.94** | 0.015 | 155 MB |
| X-ASR zipformer int8, whole utterance | **2.84** | **7.72** | 9.19 | **11.17** | **2.76** | 16.69 | 12.21 | 0.015 | 130 MB |
| X-ASR streaming, 960 ms chunks, int8 | 3.64 | 9.34 | 11.17 | 11.85 | 2.91 | 23.21 | 14.48 | 0.06 | 127 MB |
| X-ASR streaming, 480 ms chunks, int8 | 4.19 | 9.64 | 11.78 | 12.29 | 3.04 | 26.40 | 14.65 | 0.05 | 127 MB |
| X-ASR streaming, 160 ms chunks, int8 | 4.67 | 10.72 | 13.78 | 15.17 | 3.15 | 29.47 | 16.37 | 0.17 | 127 MB |
| Streaming paraformer bilingual zh-en | 3.72 | 13.73 | 16.99 | 21.53 | 25.64 | 30.15 | 21.42 | 0.08 | 1 GB archive |
| Streaming zipformer bilingual zh-en (2023-02-20), int8 | 3.87 | 13.01 | 14.19 | 26.30 | 8.01 | 29.58 | 20.11 | 0.045 | 490 MB archive |

Models: `sherpa-onnx-x-asr-{160,480,960}ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05`,
`sherpa-onnx-streaming-paraformer-bilingual-zh-en`,
`sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` (int8 encoder and joiner, fp32
decoder: with the int8 decoder its output is garbage). RTF on an Apple M-series laptop, 4 threads,
including the 100 ms feeding loop; the X-ASR 480 ms and 160 ms runs shared the machine with other
jobs for part of the time, so only the order of magnitude counts.

- **Only the X-ASR streaming models are usable**; the two older bilingual models are far behind
  on everything but read Mandarin.
- **X-ASR streaming is level with SenseVoice on standard Mandarin, English and code-switched
  speech and about twice as wrong with regional accents** (KeSpeech 23–29 % against 12 %), the
  weakness its whole-utterance model already has.
- Shorter chunks cost accuracy and compute: 160 ms chunks are 0.2–3.3 points worse than 960 ms
  ones on the standard sets and take three times the CPU. With 960 ms chunks text appears about
  once a second, slower than the current preview (a re-decode every ≥300 ms).
- The partial result changed 5.4 / 9.5 / 17.7 times per utterance (960 / 480 / 160 ms).
- A streaming model would be a third recognizer: it does not end sentences (see the punctuation
  section) and has no ITN, so the final text still needs SenseVoice, and FireRedASR2 for the
  words when refinement is on.
- Not measured: how stable the previews are (how often already shown words are rewritten) for
  either approach, which is the one thing a streaming model might do better; latency and
  battery on a phone.

Decision: keep the simulated streaming with SenseVoice. If the preview is to become truly
streaming, X-ASR with 480 ms chunks is the candidate, and preview stability is what to measure
first.

## Voice activity detection: Silero, TEN-VAD or FireRedVAD? (2026-10)

FireRedASR2S also has a detector, FireRedVAD (DFSMN, 2.3 MB), reported by its authors to beat
Silero VAD and TEN-VAD on FLEURS (97.57 % F1 for the non-streaming model). Dictation needs the
streaming one. sherpa-onnx runs Silero and TEN-VAD; FireRedVAD exists only as PyTorch weights
and would need an ONNX export plus filterbank features and its state machine in Kotlin.

`scripts/bench/vad_eval.py` runs the detectors as the app does (0.7 s trailing silence, 0.25 s
minimum speech, 20 s maximum, segments padded by 0.3 s and transcribed by SenseVoice) and measures
what the user would notice.

**Noise without speech**: 400 clips of 5 s from ESC-50 (animals, weather, coughing and laughing,
household and street noise), 33 minutes.

| Detector | Clips that opened a segment | Segments per minute | Words inserted per minute |
|---|---|---|---|
| **Silero v5, threshold 0.5 (the app)** | **4.8 %** | **0.6** | **0.2** |
| TEN-VAD, 0.5 | 24.2 % | 3.0 | 0.9 |
| FireRedVAD stream, 0.5 | 41.5 % | 6.3 | 1.3 |
| FireRedVAD stream, 0.3 (its documented setting) | 55.2 % | 8.5 | 1.4 |

FireRedVAD takes 89 % of the animal clips and more than half of the human non-speech clips for
speech; Silero 10 % of each.

**Speech**: 200 utterances per set between 1 s of lead-in and 1.5 s of tail, clean and with a
steady ESC-50 noise mixed in. Error rate (%) of SenseVoice on what the detector passed on, and in
brackets the utterances it lost completely; "none" is the whole utterance without a detector.

| Test set | Noise | none | Silero v5 | TEN-VAD | FireRedVAD 0.5 | FireRedVAD 0.3 |
|---|---|---|---|---|---|---|
| AISHELL-1 | clean | 2.79 | 2.58 | 2.89 | 2.68 | 2.61 |
| | 10 dB SNR | 3.54 | 3.27 | 11.73 (7) | 3.44 | 3.58 |
| | 0 dB SNR | 8.01 | 8.53 (1) | 47.28 (62) | 7.74 | 7.43 |
| ASCEND mixed | clean | 14.31 | 12.43 | 13.61 (1) | 13.58 | 13.42 |
| | 10 dB SNR | 18.74 | 18.29 | 25.97 (9) | 18.26 | 18.07 |
| | 0 dB SNR | 33.01 | 33.49 (1) | 49.01 (47) | 32.31 (1) | 31.07 |
| WenetSpeech meeting | clean | 9.99 | 12.30 | 44.22 (51) | 11.97 | 10.89 |
| | 10 dB SNR | 14.69 | 16.91 | 72.76 (112) | 20.24 (1) | 16.65 |
| | 0 dB SNR | 34.64 | 49.03 (28) | 88.89 (139) | 45.26 (19) | 39.18 (5) |

- **TEN-VAD is out**: it loses whole utterances in noise and on far-field speech.
- **On close-talking speech** (AISHELL-1, ASCEND: what dictating into a phone resembles) Silero
  and FireRedVAD are mostly within a point of each other; at 0 dB FireRedVAD at 0.3 is up to
  2.4 points ahead.
- **On far-field speech** (meeting recordings) FireRedVAD at 0.3 is better, clearly so in loud
  noise: 5 utterances lost against Silero's 28.
- It buys that by calling more things speech: 10 to 14 times as many segments opened by noise
  alone.
- Silero itself costs 2.3 points on clean far-field speech and drops utterances at 0 dB. If
  dictation is reported to miss quiet or distant speech, a lower Silero threshold is the first
  thing to measure (not done here; it will raise the false triggers).
- Not measured: endpoint delay (all three use the same trailing silence), real dictation on a
  phone (the noise is mixed in digitally).

Decision: keep Silero VAD v5.

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

`scripts/bench/bench_qwen_pt.py` (PyTorch) and `scripts/bench/bench_mlx.py` (MLX, Apple silicon)
run checkpoints that have no sherpa-onnx export on the same utterances.
`scripts/bench/bench_stream.py` runs streaming models (`OnlineRecognizer`) on the same utterances.
`scripts/bench/bench_hotwords.py` measures hot words in Fun-ASR-nano's prompt (needs `jieba` as well).
`scripts/bench/punct_eval.py` (with `bench_fleurs.py`) and `scripts/bench/vad_eval.py` reproduce
the punctuation and voice activity detection comparisons; each lists the extra models, data and
packages it needs at the top of the file.

## Refinement CPU scheduling (2026-10-03)

The model weights, decoding parameters, 4 threads and CPU provider are unchanged. ONNX
worker spinning is disabled for FireRedASR2 background refinement through the pinned runtime
provider configuration file. A paired arm64-emulator experiment on four derived recordings
returned identical raw text, used 35.3% less warm-decode process CPU time, and took 27.7% longer.
SenseVoice keeps spinning enabled for preview responsiveness. This is a scheduling / latency
tradeoff, not a new accuracy benchmark or a measured phone-battery gain. Full results and
reproduction steps: [PERFORMANCE.md](PERFORMANCE.md#native-worker-spinning-ab-experiment).
