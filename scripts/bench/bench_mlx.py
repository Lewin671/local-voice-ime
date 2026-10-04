"""Benchmark an mlx-audio STT checkpoint (e.g. Qwen3-ASR MLX quantizations) on the usual test sets.

    bench_mlx.py <key> <model dir or hf repo> [language]

Apple silicon only. Extra packages: mlx-audio scipy. Run it from the benchmark working directory
(docs/MODELS.md), which also needs data/kespeech_test_0.parquet and data/cv_zh_test.parquet.

language: "auto" (default) or e.g. "Chinese". One utterance at a time, as a keyboard would call it.
Same sampling and transcript format as bench_all.py: out_<key>_<dataset>.json.
Environment: DS=<comma-separated sets> to run a subset, N=<utterances per set>.
"""
import sys, os, io, glob, json, time, random, re, resource
from math import gcd
import numpy as np, soundfile as sf, pyarrow.parquet as pq, jiwer, mlx.core as mx
from scipy.signal import resample_poly
from mlx_audio.stt.utils import load_model
import bench

SETS = ["ascend_test", "aishell1_test_0", "wenet_test_net_0", "wenet_test_meeting",
        "librispeech_test_clean", "kespeech_test_0", "cv_zh_test"]
if os.environ.get("DS"): SETS = os.environ["DS"].split(",")
N = int(os.environ.get("N", "300"))
key, repo = sys.argv[1], sys.argv[2]
lang = sys.argv[3] if len(sys.argv) > 3 else "auto"
lang = None if lang == "auto" else lang

t = time.time(); m = load_model(repo)
print(f"## {key}: {repo}, language={lang}, loaded in {time.time()-t:.1f}s, "
      f"weights in memory {mx.get_active_memory()/2**20:.0f} MB", flush=True)

def decode(x, sr):
    if sr != 16000:
        g = gcd(sr, 16000); x = resample_poly(x, 16000 // g, sr // g).astype(np.float32)
    return m.generate(mx.array(x), language=lang, max_tokens=256).text

for f in sorted(glob.glob("benchwavs/*.wav")):
    x, sr = sf.read(f, dtype="float32")
    decode(x, sr)                      # first call compiles kernels; time the second
    t = time.time(); hyp = decode(x, sr)
    print(f"   sample {f} ({len(x)/sr:.1f}s audio, {time.time()-t:.2f}s): {hyp!r}", flush=True)

def load_ds(ds, n=300):
    """bench.load for the five standard sets; KeSpeech / Common Voice use other column names (as in bench3.py)."""
    if not ds.startswith(("kespeech", "cv_")): return bench.load(ds, n)
    f = pq.ParquetFile(f"data/{ds}.parquet"); rows = []
    for i in range(f.num_row_groups):
        rows += f.read_row_group(i).to_pylist()
        if len(rows) > n * 6: break
    random.Random(0).shuffle(rows); out = []
    for r in rows:
        try: x, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
        except Exception: continue
        if x.ndim > 1: x = x.mean(1)
        if len(x) / sr < 1.0: continue
        out.append((x, sr, r.get("Text") or r.get("sentence")))
        if len(out) >= n: break
    return out

for ds in SETS:
    data = load_ds(ds, N)
    res, audio, cost = [], 0.0, 0.0
    for x, sr, ref in data:
        t = time.time(); hyp = decode(x, sr); dt = time.time() - t
        cost += dt; audio += len(x) / sr
        res.append({"ref": ref, "hyp": hyp, "dur": round(len(x) / sr, 2), "sec": round(dt, 3)})
    json.dump(res, open(f"out_{key}_{ds}.json", "w"), ensure_ascii=False, indent=0)
    refs = [bench.norm(x["ref"]) for x in res]; hyps = [bench.norm(x["hyp"]) for x in res]
    keep = [i for i in range(len(res)) if refs[i] and not re.search(r"\d", hyps[i])]
    er = jiwer.wer([refs[i] for i in keep], [hyps[i] for i in keep])
    print(f"{key:22s} {ds:24s} n={len(keep)}/{len(res)} ER={er*100:.2f}% RTF={cost/audio:.3f}", flush=True)

print(f"## memory: mlx peak {mx.get_peak_memory()/2**20:.0f} MB, "
      f"process peak rss {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/2**20:.0f} MB", flush=True)
