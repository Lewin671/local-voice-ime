# Speech models

## Current choice

The APK contains the models in the table below. **FireRedASR2 AED int8** (1.2 GB) is an optional
download inside the app (*Settings → Voice input*) and works as a second stage that re-checks
every utterance; see "Refinement" further down for why and how well that works.

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

## Downloadable models

Models that are too large for the APK are listed in `VoiceModels.kt` and fetched by the app.

| Model | Files | Source | License |
|---|---|---|---|
| FireRedASR2 AED int8 | `encoder.int8.onnx` (817 MB), `decoder.int8.onnx` (417 MB), `tokens.txt` | [ModelScope `csukuangfj/FireRedASR2-AED-onnx`](https://www.modelscope.cn/models/csukuangfj/FireRedASR2-AED-onnx), directory `aed/` | Apache-2.0 |

The files are byte-identical to those in sherpa-onnx's GitHub release archive
`sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26` (same SHA-256), published file by file by the
same maintainer. ModelScope was chosen because it is reachable from mainland China without a
proxy (GitHub releases and Hugging Face are not, reliably), needs no account, and supports
resuming (HTTP range requests). FireRedTeam's own ModelScope repository only has the PyTorch
weights, which sherpa-onnx cannot load.

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
| Reference words + FireRedPunc (upper bound) | 88.7 | 90.4 | 81.3 |

- **FireRedPunc is level with SenseVoice**: +1.7 and +0.5 on the Mandarin sets, −1.0 on English.
  That does not pay for 163 MB more to download, 50–260 ms per utterance on a laptop (short
  Common Voice sentences to long FLEURS ones), and a BERT tokenizer and a direct onnxruntime
  session in Kotlin.
- It could not replace the merge either: it knows four marks (`，。？！`, no `、；：`), and digits
  and casing would still have to come from SenseVoice (capitalised words, F1 on FLEURS en: 14.6
  for FireRedASR2 + FireRedPunc with sentence-initial capitals restored, 78.3 for the merge).
- CT-Transformer is clearly worse than SenseVoice's own punctuation.
- Not measured: question marks (none of the 900 references has one) and spontaneous dictation;
  all three sets are read speech.

Decision: keep SenseVoice's punctuation.

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
`scripts/bench/punct_eval.py` (with `bench_fleurs.py`) and `scripts/bench/vad_eval.py` reproduce
the punctuation and voice activity detection comparisons; each lists the extra models, data and
packages it needs at the top of the file.
