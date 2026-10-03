"""Benchmark Volcengine (Doubao) cloud speech recognition on the same test sets as bench_all.py.

    VOLC_APP_ID=... VOLC_ACCESS_TOKEN=... bench_volc.py <key> [mode] [resource] [n=300] [dataset ...]

This is an evaluation tool only: it uploads the test audio to a third-party cloud service. The
app itself never sends audio or text off the device (see AGENTS.md).

<mode> picks the WebSocket endpoint of the "big model" streaming API:
  nostream  whole utterance, one result after the last packet (default; most accurate)
  async     bidirectional streaming with a second, non-streaming pass per sentence
<resource> is the X-Api-Resource-Id: volc.seedasr.sauc.duration (model 2.0, default) or
volc.bigasr.sauc.duration (model 1.0). With VOLC_API_KEY set, the new console's single-key
authentication is used instead of app id + access token.

Same working directory layout and scoring as bench.py (see docs/MODELS.md). Transcripts go to
out_<key>_<dataset>.json, together with the time from the last audio packet to the final result.
"""
import sys, os, json, time, re, uuid, struct, asyncio, statistics
import numpy as np, websockets, jiwer
import bench

HOST = "wss://openspeech.bytedance.com/api/v3/sauc/"
ENDPOINT = {"nostream": "bigmodel_nostream", "async": "bigmodel_async"}
CHUNK = 3200 * 2  # 200 ms of 16 kHz 16-bit mono
FULL_REQUEST, AUDIO, AUDIO_LAST = b"\x11\x10\x10\x00", b"\x11\x20\x00\x00", b"\x11\x22\x00\x00"

def headers(resource):
    h = {"X-Api-Resource-Id": resource, "X-Api-Connect-Id": str(uuid.uuid4())}
    if os.environ.get("VOLC_API_KEY"):
        h["X-Api-Key"] = os.environ["VOLC_API_KEY"]
    else:
        h["X-Api-App-Key"] = os.environ["VOLC_APP_ID"]
        h["X-Api-Access-Key"] = os.environ["VOLC_ACCESS_TOKEN"]
    return h

def frame(header, payload):
    return header + struct.pack(">I", len(payload)) + payload

def parse(msg):
    """Returns (is_last, payload dict). Raises on a server error frame."""
    kind, flags = msg[1] >> 4, msg[1] & 0x0F
    body = msg[(msg[0] & 0x0F) * 4:]
    if kind == 0xF:
        code, size = struct.unpack(">II", body[:8])
        raise RuntimeError(f"server error {code}: {body[8:8 + size].decode('utf-8', 'replace')}")
    if flags & 0x1: body = body[4:]  # sequence number
    size = struct.unpack(">I", body[:4])[0]
    return bool(flags & 0x2), json.loads(body[4:4 + size] or b"{}")

def pcm16(x, sr):
    if sr != 16000:
        n = int(len(x) * 16000 / sr)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x)
    return (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()

async def recognize(x, sr, mode, resource, realtime=False):
    request = {"model_name": "bigmodel", "enable_itn": True, "enable_punc": True}
    if mode == "async": request["enable_nonstream"] = True
    config = {"user": {"uid": "bench"}, "audio": {"format": "pcm", "codec": "raw", "rate": 16000, "bits": 16, "channel": 1},
              "request": request}
    data = pcm16(x, sr)
    async with websockets.connect(HOST + ENDPOINT[mode], additional_headers=headers(resource), max_size=None) as ws:
        await ws.send(frame(FULL_REQUEST, json.dumps(config).encode()))
        chunks = [data[i:i + CHUNK] for i in range(0, len(data), CHUNK)] or [b""]
        async def send():
            for i, c in enumerate(chunks):
                await ws.send(frame(AUDIO_LAST if i == len(chunks) - 1 else AUDIO, c))
                if realtime: await asyncio.sleep(0.2)
            return time.time()
        sender = asyncio.create_task(send())
        text, first = "", None
        while True:
            last, payload = parse(await ws.recv())
            t = payload.get("result", {}).get("text")
            if t is not None:
                text = t
                if t and first is None: first = time.time()
            if last: break
        sent = await sender
        return text, time.time() - sent

async def run(data, mode, resource, parallel):
    gate, out = asyncio.Semaphore(parallel), [None] * len(data)
    async def one(i, x, sr, ref):
        async with gate:
            for attempt in range(4):
                try:
                    hyp, wait = await recognize(x, sr, mode, resource)
                    out[i] = {"ref": ref, "hyp": hyp, "wait": round(wait, 3), "audio": round(len(x) / sr, 2)}
                    return
                except Exception as e:
                    err = repr(e)
                    await asyncio.sleep(1 + attempt * 2)
            out[i] = {"ref": ref, "hyp": "", "error": err}
    await asyncio.gather(*(one(i, *d) for i, d in enumerate(data)))
    return out

if __name__ == "__main__":
    key = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else "nostream"
    resource = sys.argv[3] if len(sys.argv) > 3 else "volc.seedasr.sauc.duration"
    n = int(sys.argv[4]) if len(sys.argv) > 4 else 300
    sets = sys.argv[5:] or ["aishell1_test_0", "wenet_test_net_0", "wenet_test_meeting", "ascend_test", "librispeech_test_clean"]
    for ds in sets:
        data = bench.load(ds, n)
        res = asyncio.run(run(data, mode, resource, int(os.environ.get("VOLC_PARALLEL", "4"))))
        json.dump(res, open(f"out_{key}_{ds}.json", "w"), ensure_ascii=False, indent=0)
        failed = [x for x in res if "error" in x]
        ok = [x for x in res if "error" not in x]
        refs = [bench.norm(x["ref"]) for x in ok]; hyps = [bench.norm(x["hyp"]) for x in ok]
        keep = [i for i in range(len(ok)) if refs[i] and not re.search(r"\d", hyps[i])]
        er = jiwer.wer([refs[i] for i in keep], [hyps[i] for i in keep]) if keep else float("nan")
        waits = sorted(x["wait"] for x in ok) or [float("nan")]
        print(f"{key:22s} {ds:24s} n={len(keep)}/{len(res)} failed={len(failed)} ER={er*100:.2f}% "
              f"audio={sum(x['audio'] for x in ok):.0f}s wait median={statistics.median(waits):.2f}s p95={waits[int(len(waits) * 0.95) - 1]:.2f}s", flush=True)
        if failed: print("   first error:", failed[0]["error"][:300], flush=True)
