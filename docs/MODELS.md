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

To evaluate a new model, add a branch to `model()` in `scripts/bench/bench.py`, run all five
sets, and record the numbers in the table above before changing `VoiceEngine.kt`. Then run
`scripts/e2e-voice.sh` on a device: desktop numbers say nothing about load time and memory.
