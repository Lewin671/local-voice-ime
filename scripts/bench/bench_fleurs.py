"""Transcribe the FLEURS zh/en test sets, whose references carry punctuation, with the models that
matter for the punctuation comparison (punct_eval.py).

    bench_fleurs.py

Run it from the benchmark working directory (docs/MODELS.md) with data/fleurs_zh_test.parquet and
data/fleurs_en_test.parquet (google/fleurs, cmn_hans_cn and en_us, test split). SenseVoice and
FireRedASR2 are taken from this repository's voice/ directory (scripts/fetch-voice-assets.sh),
the X-ASR models from the working directory; models that are missing are skipped. Writes
out_<key>_<dataset>.json like the other scripts, 300 utterances per set.
"""
import sys, io, json, random, glob, os
import numpy as np, soundfile as sf, pyarrow.parquet as pq, sherpa_onnx
V = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../voice")
R = sherpa_onnx.OfflineRecognizer
XA = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
XS = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"

def load(ds, n=300):
    f = pq.ParquetFile(f"data/{ds}.parquet"); rows = []
    for i in range(f.num_row_groups): rows += f.read_row_group(i).to_pylist()
    random.Random(0).shuffle(rows); out = []
    for q in rows:
        x, sr = sf.read(io.BytesIO(q["audio"]["bytes"]), dtype="float32")
        if x.ndim > 1: x = x.mean(1)
        if len(x) / sr < 1.0: continue
        out.append((x, sr, q["raw_transcription"]))
        if len(out) >= n: break
    return out

def offline(r):
    def f(x, sr):
        st = r.create_stream(); st.accept_waveform(sr, x); r.decode_stream(st); return st.result.text
    return f

def online(r):
    def f(x, sr):
        s = r.create_stream(); s.accept_waveform(sr, x); s.accept_waveform(sr, np.zeros(int(sr * 1.5), dtype=np.float32)); s.input_finished()
        while r.is_ready(s): r.decode_stream(s)
        return r.get_result(s)
    return f

MODELS = {
    "sensevoice-int8": lambda: offline(R.from_sense_voice(model=f"{V}/models/sense-voice-small-int8/model.int8.onnx", tokens=f"{V}/models/sense-voice-small-int8/tokens.txt", num_threads=4, language="auto", use_itn=True)),
    "xasr-int8": lambda: offline(R.from_transducer(encoder=f"{XA}/encoder-epoch-99-avg-1.int8.onnx", decoder=f"{XA}/decoder-epoch-99-avg-1.onnx", joiner=f"{XA}/joiner-epoch-99-avg-1.int8.onnx", tokens=f"{XA}/tokens.txt", num_threads=4)),
    "xasr-s480": lambda: online(sherpa_onnx.OnlineRecognizer.from_transducer(encoder=f"{XS}/encoder.int8.onnx", decoder=f"{XS}/decoder.onnx", joiner=f"{XS}/joiner.int8.onnx", tokens=f"{XS}/tokens.txt", num_threads=4)),
    "firered2-aed": lambda: offline(R.from_fire_red_asr(encoder=f"{V}/models/fire-red-asr2-aed-int8/encoder.int8.onnx", decoder=f"{V}/models/fire-red-asr2-aed-int8/decoder.int8.onnx", tokens=f"{V}/models/fire-red-asr2-aed-int8/tokens.txt", num_threads=4)),
}
for ds in ["fleurs_zh_test", "fleurs_en_test"]:
    data = load(ds)
    for key, make in MODELS.items():
        out = f"out_{key}_{ds}.json"
        if os.path.exists(out): continue
        try: dec = make()
        except Exception as e:
            print(key, "skipped:", e, flush=True); continue
        res = [{"ref": ref, "hyp": dec(x, sr)} for x, sr, ref in data]
        json.dump(res, open(out, "w"), ensure_ascii=False, indent=0)
        print(key, ds, "done", flush=True)
