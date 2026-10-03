"""A/B benchmark of on-device ASR model candidates on a desktop machine.

    bench.py <model> <dataset> [n=300]

Run it from a working directory laid out as described in docs/MODELS.md:
extracted sherpa-onnx model directories next to each other, and test sets as
data/<dataset>.parquet. Prints the error rate (per CJK character / per Latin word, punctuation
and case ignored) and the real-time factor, and writes the transcripts to
out_<model>_<dataset>.json for manual inspection.

models:   sensevoice | sensevoice_itn | xasr | qwen3   (add new ones in model())
"""
import sys, io, json, time, random, re, glob
import numpy as np, soundfile as sf, pyarrow.parquet as pq, sherpa_onnx, zhconv, jiwer

D = "."
def model(name, threads=4):
    R = sherpa_onnx.OfflineRecognizer
    if name.startswith("sensevoice"):
        d = glob.glob(f"{D}/sherpa-onnx-sense-voice-*2024-07-17")[0]
        return R.from_sense_voice(model=f"{d}/model.int8.onnx", tokens=f"{d}/tokens.txt", num_threads=threads,
                                  language="auto", use_itn=name.endswith("itn"))
    if name == "xasr":
        d = glob.glob(f"{D}/sherpa-onnx-x-asr-zipformer-*")[0]
        return R.from_transducer(encoder=f"{d}/encoder-epoch-99-avg-1.int8.onnx", decoder=f"{d}/decoder-epoch-99-avg-1.onnx",
                                 joiner=f"{d}/joiner-epoch-99-avg-1.int8.onnx", tokens=f"{d}/tokens.txt", num_threads=threads)
    if name == "qwen3":
        d = glob.glob(f"{D}/sherpa-onnx-qwen3-asr-*")[0]
        return R.from_qwen3_asr(conv_frontend=f"{d}/conv_frontend.onnx", encoder=f"{d}/encoder.int8.onnx",
                                decoder=f"{d}/decoder.int8.onnx", tokenizer=f"{d}/tokenizer", num_threads=threads)
    raise SystemExit(name)

def load(ds, n):
    f = pq.ParquetFile(f"{D}/data/{ds}.parquet")
    rows = []
    for i in range(f.num_row_groups):
        rows += f.read_row_group(i).to_pylist()
        if len(rows) > n * 6 and not ds.startswith('ascend'): break
    random.Random(0).shuffle(rows)
    out = []
    for r in rows:
        if ds.startswith('ascend') and r['language'] != 'mixed': continue
        a = r.get("context") or r.get("audio")
        text = r.get("answer") or r.get("text") or r.get("transcription")
        x, sr = sf.read(io.BytesIO(a["bytes"]), dtype="float32")
        if x.ndim > 1: x = x.mean(1)
        if len(x) / sr < 1.0: continue
        out.append((x, sr, text))
        if len(out) >= n: break
    return out

def norm(s):
    s = zhconv.convert(s, "zh-cn").lower()
    s = re.sub(r"<[^>]*>", "", s)
    s = re.sub(r"[^\w一-鿿' ]", "", s).replace("_", "")
    # CJK chars as separate tokens, latin words kept whole
    toks = re.findall(r"[一-鿿]|[a-z0-9']+", s)
    return " ".join(toks)

if __name__ == "__main__":
    name, ds = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 300
    data = load(ds, n)
    r = model(name)
    res, audio, cost = [], 0.0, 0.0
    for x, sr, ref in data:
        t = time.time()
        st = r.create_stream(); st.accept_waveform(sr, x); r.decode_stream(st)
        cost += time.time() - t; audio += len(x) / sr
        res.append({"ref": ref, "hyp": st.result.text})
    json.dump(res, open(f"out_{name}_{ds}.json", "w"), ensure_ascii=False, indent=0)
    refs = [norm(x["ref"]) for x in res]; hyps = [norm(x["hyp"]) for x in res]
    keep = [i for i in range(len(res)) if refs[i] and not re.search(r"\d", hyps[i])]
    cer = jiwer.wer([refs[i] for i in keep], [hyps[i] for i in keep])
    print(f"{name:16s} {ds:20s} n={len(keep)}/{len(res)} audio={audio:.0f}s  ER={cer*100:.2f}%  RTF={cost/audio:.3f}")
