"""Benchmark a Qwen3-ASR family checkpoint (PyTorch, qwen-asr package) on the usual test sets.

    bench_qwen_pt.py <key> <hf_repo> [language]

language: "auto" (default, None passed to the model) or e.g. "Chinese".
Same sampling and transcript format as bench_all.py: out_<key>_<dataset>.json.
"""
import sys, glob, json, time, re
import numpy as np, soundfile as sf, torch, jiwer
from qwen_asr import Qwen3ASRModel
import bench

SETS = ["ascend_test", "aishell1_test_0", "wenet_test_net_0", "wenet_test_meeting",
        "librispeech_test_clean", "kespeech_test_0", "cv_zh_test"]

import os
if os.environ.get("DS"): SETS = os.environ["DS"].split(",")
BS = int(os.environ.get("BS", "4"))
key, repo = sys.argv[1], sys.argv[2]
lang = sys.argv[3] if len(sys.argv) > 3 else "auto"
lang = None if lang == "auto" else lang
dev = "mps" if torch.backends.mps.is_available() else "cpu"
t = time.time()
m = Qwen3ASRModel.from_pretrained(repo, dtype=torch.float32, device_map=dev, max_new_tokens=256, max_inference_batch_size=BS)
print(f"## {key}: {repo} on {dev}, language={lang}, loaded in {time.time()-t:.1f}s", flush=True)

def decode(x, sr):
    return m.transcribe(audio=(np.asarray(x, dtype=np.float32), sr), language=lang)[0].text

for f in sorted(glob.glob("benchwavs/*.wav")):
    x, sr = sf.read(f, dtype="float32")
    print(f"   sample {f}: {decode(x, sr)!r}", flush=True)

def load_ds(ds, n=300):
    """bench.load for the five standard sets; KeSpeech / Common Voice use other column names (as in bench3.py)."""
    if not ds.startswith(("kespeech", "cv_")): return bench.load(ds, n)
    import io, random, pyarrow.parquet as pq
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
    data = load_ds(ds)
    res, audio, cost = [], 0.0, 0.0
    data.sort(key=lambda d: len(d[0]) / d[1])   # similar lengths per batch; order is the same for every model
    for i in range(0, len(data), BS):
        chunk = data[i:i + BS]; t = time.time()
        outs = m.transcribe(audio=[(np.asarray(x, dtype=np.float32), sr) for x, sr, _ in chunk], language=lang)
        cost += time.time() - t; audio += sum(len(x) / sr for x, sr, _ in chunk)
        res += [{"ref": ref, "hyp": o.text} for (_, _, ref), o in zip(chunk, outs)]
        if dev == "mps": torch.mps.empty_cache()   # the MPS allocator otherwise grows to many GB
    json.dump(res, open(f"out_{key}_{ds}.json", "w"), ensure_ascii=False, indent=0)
    refs = [bench.norm(x["ref"]) for x in res]; hyps = [bench.norm(x["hyp"]) for x in res]
    keep = [i for i in range(len(res)) if refs[i] and not re.search(r"\d", hyps[i])]
    er = jiwer.wer([refs[i] for i in keep], [hyps[i] for i in keep])
    print(f"{key:22s} {ds:24s} n={len(keep)}/{len(res)} ER={er*100:.2f}% RTF={cost/audio:.3f}", flush=True)
