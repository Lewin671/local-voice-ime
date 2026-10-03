# Speech models

## Current choice

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
(18.29 / 29.15 / 32.10 / 36.58 / 4.96), Whisper turbo int8 (31 % on AISHELL-1, RTF 0.34; stopped).
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

Not measured: speed and memory of the large models on a phone. That decides whether a second
pass is practical, and is the next thing to do.

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
