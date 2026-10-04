"""Is another voice activity detector better for dictation than Silero VAD v5?

    vad_eval.py [noise] [speech] [dataset ...]     default: both, on aishell1_test_0 wenet_test_meeting ascend_test

noise   ESC-50 fold 1 (400 clips of 5 s, no speech): how often does the detector open a segment,
        and how much text does SenseVoice then produce from it (what the user would see inserted).
speech  200 utterances per set with 1 s lead-in and 1.5 s tail, clean and with ESC-50 noise mixed
        in: error rate of SenseVoice on the detector's segments against the error rate on the
        whole utterance (no detector), plus utterances lost completely.

Detectors are run as the app runs them: 0.7 s trailing silence, 0.25 s minimum speech, 20 s
maximum, segments padded by 0.3 s before transcription.

Run it from the benchmark working directory (docs/MODELS.md). SenseVoice and Silero VAD are taken
from this repository's voice/ directory (scripts/fetch-voice-assets.sh). Also needed there:
  ten-vad.onnx                     sherpa-onnx asr-models release
  FireRedVAD/{model.pth.tar,cmvn.ark}   huggingface.co/FireRedTeam/FireRedVAD, directory Stream-VAD
  FireRedASR2S/                    git clone https://github.com/FireRedTeam/FireRedASR2S
  esc50/                           meta/esc50.csv and the fold 1 files of audio/ from
                                   https://github.com/karolpiczak/ESC-50 (CC BY-NC, evaluation only)
and `pip install torch scipy kaldi_native_fbank kaldiio`. Results are printed; what was
transcribed goes to out_vad_noise.json and out_vad_speech_<datasets>.json.
"""
import sys, os, csv, json, time, random, io
import numpy as np, soundfile as sf, sherpa_onnx, jiwer
from scipy.signal import resample_poly

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join("FireRedASR2S", "fireredasr2s"))
import bench as B
from fireredvad.stream_vad import FireRedStreamVad, FireRedStreamVadConfig

SR, MARGIN, MIN_SIL, MIN_SPEECH, MAX_SPEECH = 16000, int(0.3 * 16000), 0.7, 0.25, 20.0
VOICE = os.path.join(HERE, "../../voice/assets/voice")
SV = os.path.join(HERE, "../../voice/models/sense-voice-small-int8")
asr = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=f"{SV}/model.int8.onnx", tokens=f"{SV}/tokens.txt", num_threads=4, language="auto", use_itn=False)


def transcribe(x):
    st = asr.create_stream(); st.accept_waveform(SR, x); asr.decode_stream(st)
    return st.result.text


class Sherpa:
    def __init__(self, kind, threshold=0.5):
        c = sherpa_onnx.VadModelConfig()
        m = c.silero_vad if kind == "silero" else c.ten_vad
        m.model = f"{VOICE}/silero_vad.onnx" if kind == "silero" else "ten-vad.onnx"
        m.threshold, m.min_silence_duration, m.min_speech_duration = threshold, MIN_SIL, MIN_SPEECH
        m.max_speech_duration = MAX_SPEECH
        self.window = m.window_size = 512 if kind == "silero" else 256
        c.sample_rate = SR
        self.vad = sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=120)

    def __call__(self, x):
        v = self.vad; v.reset(); out = []
        def drain():
            while not v.empty():
                s = v.front; out.append((s.start, s.start + len(s.samples))); v.pop()
        for i in range(0, len(x) - self.window + 1, self.window):
            v.accept_waveform(x[i:i + self.window]); drain()
        v.flush(); drain()
        return out


class FireRed:
    def __init__(self, threshold):
        self.vad = FireRedStreamVad.from_pretrained("FireRedVAD", FireRedStreamVadConfig(
            speech_threshold=threshold, min_speech_frame=int(MIN_SPEECH * 100),
            min_silence_frame=int(MIN_SIL * 100), max_speech_frame=int(MAX_SPEECH * 100)))

    def __call__(self, x):
        _, r = self.vad.detect_full((x * 32768).astype(np.float32))
        return [(int(a * SR), int(b * SR)) for a, b in r["timestamps"]]


VADS = {"Silero v5 @0.5 (today)": lambda: Sherpa("silero"),
        "TEN-VAD @0.5": lambda: Sherpa("ten"),
        "FireRedVAD stream @0.5": lambda: FireRed(0.5),
        "FireRedVAD stream @0.3": lambda: FireRed(0.3)}


def through(vad, x):
    """What the app would insert: every segment, padded, transcribed."""
    segs = vad(x)
    texts = [transcribe(x[max(0, a - MARGIN):min(len(x), b + MARGIN)]) for a, b in segs]
    return segs, texts


def esc50():
    out = []
    for r in csv.DictReader(open("esc50/esc50.csv")):
        if r["fold"] != "1": continue
        x, sr = sf.read(f"esc50/{r['filename']}", dtype="float32")
        if x.ndim > 1: x = x.mean(1)
        out.append((int(r["target"]) // 10, r["category"], resample_poly(x, 160, 441).astype(np.float32)))
    return out


GROUPS = ["animals", "nature", "human non-speech", "indoor", "outdoor"]


def noise_test(vads, clips):
    print(f"\n== noise only: ESC-50 fold 1, {len(clips)} clips, {sum(len(c[2]) for c in clips) / SR / 60:.1f} min")
    print(f"{'':26s} clips triggered % (all | {' | '.join(GROUPS)})   segments/min  speech %  words inserted/min  ms per s")
    dump = {}
    for name, vad in vads.items():
        trig = [0] * 5; tot = [0] * 5; nseg = 0; sp = 0; words = 0; cost = 0.0; rows = []
        for g, cat, x in clips:
            t = time.time(); segs = vad(x); cost += time.time() - t
            texts = [transcribe(x[max(0, a - MARGIN):min(len(x), b + MARGIN)]) for a, b in segs]
            w = sum(len(B.norm(t).split()) for t in texts)
            tot[g] += 1; trig[g] += bool(segs); nseg += len(segs); sp += sum(b - a for a, b in segs); words += w
            if segs: rows.append((cat, len(segs), " / ".join(texts)))
        mins = sum(len(c[2]) for c in clips) / SR / 60
        dump[name] = rows
        print(f"{name:26s} {100 * sum(trig) / sum(tot):5.1f} | " + " | ".join(f"{100 * a / b:5.1f}" for a, b in zip(trig, tot))
              + f"   {nseg / mins:6.1f}  {100 * sp / SR / 60 / mins:6.1f}  {words / mins:6.1f}  {cost * 1000 / (mins * 60):6.2f}", flush=True)
    json.dump(dump, open("out_vad_noise.json", "w"), ensure_ascii=False, indent=1)


def stream(x, noise, snr, rng):
    """1 s lead-in + utterance + 1.5 s tail; optional noise over all of it, level set against the utterance."""
    y = np.concatenate([np.zeros(SR, np.float32), x, np.zeros(int(1.5 * SR), np.float32)])
    y += rng.normal(0, 1e-4, len(y)).astype(np.float32)
    if noise is None: return y
    n = np.tile(noise, len(y) // len(noise) + 1)[:len(y)]
    g = np.sqrt(np.mean(x ** 2) / max(np.mean(n ** 2), 1e-12)) / 10 ** (snr / 20)
    y = y + n * g
    return (y / max(1.0, np.abs(y).max())).astype(np.float32)


def speech_test(vads, clips, datasets, n):
    # steady background noise only: clips that are not mostly digital silence
    noises = [x for g, _, x in clips if g in (1, 3, 4) and np.mean(np.abs(x) > 1e-3) > 0.8]
    dump = {}
    for ds in datasets:
        data = B.load(ds, n)
        for cond in ("clean", 10, 0):
            rng = random.Random(1); nrng = np.random.default_rng(1)
            ys, refs = [], []
            for x, sr, ref in data:
                if sr != SR: x = resample_poly(x, SR, sr).astype(np.float32)
                ys.append(stream(x, None if cond == "clean" else rng.choice(noises), cond, nrng)); refs.append(B.norm(ref))
            base = [B.norm(transcribe(y)) for y in ys]
            rows = {"no detector": (base, None, None, None)}
            for name, vad in vads.items():
                hyps, lost, nseg, cov = [], 0, 0, 0
                for y in ys:
                    segs, texts = through(vad, y)
                    hyps.append(B.norm(" ".join(texts))); lost += not segs; nseg += len(segs)
                    cov += sum(b - a for a, b in segs) / len(y)
                rows[name] = (hyps, lost, nseg / len(ys), 100 * cov / len(ys))
            keep = [i for i in range(len(refs)) if refs[i]]
            print(f"\n== {ds}  {cond if cond == 'clean' else f'noise at {cond} dB SNR'}  n={len(keep)}")
            print(f"{'':26s} error %   lost utts   segments/utt   speech %")
            for name, (hyps, lost, seg, cov) in rows.items():
                er = 100 * jiwer.wer([refs[i] for i in keep], [hyps[i] or "" for i in keep])
                print(f"{name:26s} {er:6.2f}" + (f"   {lost:5d}   {seg:8.2f}   {cov:8.1f}" if lost is not None else ""), flush=True)
                dump[f"{ds}|{cond}|{name}"] = hyps
            dump[f"{ds}|{cond}|ref"] = refs
    json.dump(dump, open(f"out_vad_speech_{'_'.join(datasets)}.json", "w"), ensure_ascii=False, indent=0)


if __name__ == "__main__":
    what = [a for a in sys.argv[1:] if a in ("noise", "speech")] or ["noise", "speech"]
    sets = [a for a in sys.argv[1:] if a not in ("noise", "speech")] or ["aishell1_test_0", "wenet_test_meeting", "ascend_test"]
    vads = {k: f() for k, f in VADS.items()}
    clips = esc50()
    if "noise" in what: noise_test(vads, clips)
    if "speech" in what:
        speech_test(vads, clips, sets, 200)
