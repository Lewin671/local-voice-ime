"""Benchmark a streaming model (sherpa-onnx OnlineRecognizer) on the same utterances as bench_all.py.

    bench_stream.py <key> <model_dir> <transducer|paraformer>

Run it from the benchmark working directory (docs/MODELS.md). Audio is fed 100 ms at a time, as a
microphone would deliver it, followed by 1.5 s of silence. Prints the error rate and real-time
factor per test set and writes out_<key>_<dataset>.json; "changes" in there is how often the
partial result changed while the utterance was fed. A set whose output file exists is skipped.
"""
import os, sys, json, time, re, glob, io, random
import numpy as np, sherpa_onnx, jiwer, soundfile as sf, pyarrow.parquet as pq, bench

key, d, kind = sys.argv[1:4]
SETS = ["aishell1_test_0", "wenet_test_net_0", "wenet_test_meeting", "ascend_test",
        "librispeech_test_clean", "kespeech_test_0", "cv_zh_test"]


def pick(pattern, int8=True):
    files = sorted(glob.glob(f"{d}/{pattern}"))
    wanted = [f for f in files if ("int8" in f) == int8]
    return (wanted or files)[0]


if kind == "transducer":
    r = sherpa_onnx.OnlineRecognizer.from_transducer(
        # the decoder is tiny, and the int8 decoder of older exports produces garbage: use fp32
        tokens=f"{d}/tokens.txt", encoder=pick("encoder*.onnx"), decoder=pick("decoder*.onnx", False),
        joiner=pick("joiner*.onnx"), num_threads=4)
else:
    r = sherpa_onnx.OnlineRecognizer.from_paraformer(
        tokens=f"{d}/tokens.txt", encoder=pick("encoder*.onnx"), decoder=pick("decoder*.onnx"), num_threads=4)


def decode(x, sr):
    """Feed 100 ms at a time, as a microphone would. Returns the final text and how many times the
    partial result changed."""
    s = r.create_stream()
    step = sr // 10
    x = np.concatenate([x, np.zeros(int(sr * 1.5), dtype=np.float32)])
    last, changes = "", 0
    for i in range(0, len(x), step):
        s.accept_waveform(sr, x[i:i + step])
        while r.is_ready(s): r.decode_stream(s)
        t = r.get_result(s)
        if t != last: last, changes = t, changes + 1
    s.input_finished()
    while r.is_ready(s): r.decode_stream(s)
    return r.get_result(s), changes


def load3(ds, n):
    f = pq.ParquetFile(f"data/{ds}.parquet"); rows = []
    for i in range(f.num_row_groups):
        rows += f.read_row_group(i).to_pylist()
        if len(rows) > n * 6: break
    random.Random(0).shuffle(rows); out = []
    for q in rows:
        text = q.get("Text") or q.get("sentence")
        try: x, sr = sf.read(io.BytesIO(q["audio"]["bytes"]), dtype="float32")
        except Exception: continue
        if x.ndim > 1: x = x.mean(1)
        if len(x) / sr < 1.0: continue
        out.append((x, sr, text, q.get("Dialect") or ""))
        if len(out) >= n: break
    return out


for ds in SETS:
    if os.path.exists(f"out_{key}_{ds}.json"): continue
    data = load3(ds, 300) if ds in ("kespeech_test_0", "cv_zh_test") else [(*t, "") for t in bench.load(ds, 300)]
    res, audio, cost = [], 0.0, 0.0
    for x, sr, ref, dia in data:
        t = time.time(); hyp, changes = decode(x, sr); cost += time.time() - t; audio += len(x) / sr
        res.append({"ref": ref, "hyp": hyp, "dialect": dia, "changes": changes})
    json.dump(res, open(f"out_{key}_{ds}.json", "w"), ensure_ascii=False, indent=0)
    refs = [bench.norm(x["ref"]) for x in res]; hyps = [bench.norm(x["hyp"]) for x in res]
    keep = [i for i in range(len(res)) if refs[i] and not re.search(r"\d", hyps[i])]
    er = jiwer.wer([refs[i] for i in keep], [hyps[i] for i in keep])
    print(f"{key:22s} {ds:24s} n={len(keep)}/{len(res)} ER={er*100:.2f}% RTF={cost/audio:.3f}", flush=True)
