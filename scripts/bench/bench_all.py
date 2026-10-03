"""Benchmark any sherpa-onnx offline model on all five test sets.

    bench_all.py <key> <model_dir> <kind> [fp32]

<kind> is one of: sensevoice, transducer, firered_aed, firered_ctc, funasr_nano, paraformer,
zipformer_ctc, telespeech, dolphin, omnilingual, whisper, moonshine. Model files are located by
glob inside <model_dir>; pass "fp32" to prefer the non-quantized files. Same working directory
layout as bench.py (see docs/MODELS.md). Transcripts go to out_<key>_<dataset>.json.
"""
import sys, glob, json, time, re, os
import soundfile as sf, sherpa_onnx, jiwer
import bench

def pick(d, pattern, fp32):
    fs = sorted(glob.glob(f"{d}/{pattern}"))
    fs = [f for f in fs if os.path.isfile(f)]
    if not fs: raise FileNotFoundError(f"{d}/{pattern}")
    int8 = [f for f in fs if "int8" in f or "quant" in f]
    full = [f for f in fs if f not in int8]
    return (full or fs)[0] if fp32 else (int8 or fs)[0]

def load(d, kind, fp32, threads=4):
    R = sherpa_onnx.OfflineRecognizer
    P = lambda pat: pick(d, pat, fp32)
    tokens = lambda: pick(d, "*tokens*.txt", False)
    if kind == "sensevoice":
        return R.from_sense_voice(model=P("model*.onnx"), tokens=tokens(), num_threads=threads, language="auto", use_itn=True)
    if kind == "transducer":
        return R.from_transducer(encoder=P("encoder*.onnx"), decoder=pick(d, "decoder*.onnx", True), joiner=P("joiner*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "firered_aed":
        return R.from_fire_red_asr(encoder=P("encoder*.onnx"), decoder=P("decoder*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "firered_ctc":
        return R.from_fire_red_asr_ctc(model=P("model*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "funasr_nano":
        tok = [x for x in glob.glob(f"{d}/*") if os.path.isdir(x) and "test" not in x][0]
        return R.from_funasr_nano(encoder_adaptor=P("encoder_adaptor*.onnx"), llm=P("llm*.onnx"), embedding=P("embedding*.onnx"), tokenizer=tok, num_threads=threads)
    if kind == "paraformer":
        return R.from_paraformer(paraformer=P("model*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "zipformer_ctc":
        return R.from_zipformer_ctc(model=P("model*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "telespeech":
        return R.from_telespeech_ctc(model=P("model*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "dolphin":
        return R.from_dolphin_ctc(model=P("model*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "omnilingual":
        return R.from_omnilingual_asr_ctc(model=P("model*.onnx"), tokens=tokens(), num_threads=threads)
    if kind == "whisper":
        return R.from_whisper(encoder=P("*encoder*.onnx"), decoder=P("*decoder*.onnx"), tokens=tokens(), language="", num_threads=threads)
    if kind == "moonshine":
        return R.from_moonshine_v2(encoder=P("encoder*"), decoder=P("*decoder*"), tokens=tokens(), num_threads=threads)
    raise SystemExit(kind)

def decode(r, x, sr):
    st = r.create_stream(); st.accept_waveform(sr, x); r.decode_stream(st); return st.result.text

if __name__ == "__main__":
    key, d, kind = sys.argv[1:4]; fp32 = len(sys.argv) > 4
    t = time.time(); r = load(d, kind, fp32); print(f"## {key}: loaded in {time.time()-t:.1f}s", flush=True)
    # optional: a few recordings of your own in ./samples/*.wav, printed for a qualitative look
    for f in sorted(glob.glob("samples/*.wav")):
        x, sr = sf.read(f, dtype="float32")
        print(f"   sample {f}: {decode(r, x, sr)!r}", flush=True)
    for ds in ["aishell1_test_0", "wenet_test_net_0", "wenet_test_meeting", "ascend_test", "librispeech_test_clean"]:
        data = bench.load(ds, 300)
        res, audio, cost = [], 0.0, 0.0
        for x, sr, ref in data:
            t = time.time(); hyp = decode(r, x, sr); cost += time.time() - t; audio += len(x) / sr
            res.append({"ref": ref, "hyp": hyp})
        json.dump(res, open(f"out_{key}_{ds}.json", "w"), ensure_ascii=False, indent=0)
        refs = [bench.norm(x["ref"]) for x in res]; hyps = [bench.norm(x["hyp"]) for x in res]
        keep = [i for i in range(len(res)) if refs[i] and not re.search(r"\d", hyps[i])]
        er = jiwer.wer([refs[i] for i in keep], [hyps[i] for i in keep])
        print(f"{key:22s} {ds:24s} n={len(keep)}/{len(res)} ER={er*100:.2f}% RTF={cost/audio:.3f}", flush=True)
