"""Which way of punctuating the final text is best?

    punct_eval.py [dataset ...]        default: fleurs_zh_test fleurs_en_test cv_zh_test

Compares complete pipelines (recognizer + punctuation source) against references that carry
punctuation (FLEURS zh/en raw transcriptions, Common Voice zh-CN sentences). Punctuation is scored
end to end: hypothesis and reference are aligned on their words, and the mark after each aligned
word is compared (classes: comma / full stop / question mark).

Run it from the benchmark working directory (docs/MODELS.md). It reads the transcripts
out_<key>_<dataset>.json of the keys sensevoice-int8, firered2-aed and, if present, xasr-int8 and
xasr-s480 (bench_fleurs.py writes them for FLEURS, bench_all.py for Common Voice), and needs
  sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/   sherpa-onnx punctuation-models
  fireredpunc/{punc.q8w.onnx,tokenizer.json}                            huggingface.co/jiangzhuo9357/fireredpunc-onnx
and `pip install onnxruntime tokenizers`. The punctuated texts go to punct_<dataset>.json.
"""
import sys, json, re, difflib, os, time
import numpy as np, sherpa_onnx, zhconv, onnxruntime as ort
from tokenizers import Tokenizer
import merge_prototype as M

CJK = r"㐀-䶿一-鿿"
TOKEN = re.compile(rf"[{CJK}]|[A-Za-z0-9][A-Za-z0-9'.:%-]*[A-Za-z0-9%]|[A-Za-z0-9]")
COMMA, STOP, QUES = "，,、；;：:", "。.！!", "？?"


def cls(gap):
    for c in gap:
        if c in COMMA: return 1
        if c in STOP: return 2
        if c in QUES: return 3
    return 0


def toks(s):
    """[(word, class of the mark after it)]"""
    s = zhconv.convert(s, "zh-cn")
    out, pos = [], 0
    for m in TOKEN.finditer(s):
        if out: out[-1][1] = cls(s[pos:m.start()])
        out.append([m.group().lower(), 0]); pos = m.end()
    if out: out[-1][1] = cls(s[pos:])
    return out


def bare(s):
    """Words only, as a punctuation model expects them: lower case, Latin words space-separated."""
    w = [t for t, _ in toks(s)]
    out = ""
    for i, t in enumerate(w):
        if i and re.match(r"[a-z0-9]", t) and re.match(r"[a-z0-9]", w[i - 1][-1]): out += " "
        out += t
    return out


# ---------------------------------------------------------------- punctuation models
_ct = None
def ct(text):
    global _ct
    if _ct is None:
        d = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"
        _ct = sherpa_onnx.OfflinePunctuation(sherpa_onnx.OfflinePunctuationConfig(
            model=sherpa_onnx.OfflinePunctuationModelConfig(ct_transformer=f"{d}/model.int8.onnx", num_threads=2)))
    return _ct.add_punctuation(text) if text else text


_frp = None
FRP_OUT = [" ", "，", "。", "？", "！"]
def frp(text):
    """FireRedPunc (weight-only 8-bit ONNX export), following fireredasr2s/fireredpunc/punc.py."""
    global _frp
    if not text: return text
    if _frp is None:
        _frp = (Tokenizer.from_file("fireredpunc/tokenizer.json"),
                ort.InferenceSession("fireredpunc/punc.q8w.onnx", providers=["CPUExecutionProvider"]))
    tok, sess = _frp
    enc = tok.encode(text, add_special_tokens=False)
    if not enc.ids: return text
    ids = np.array([[tok.token_to_id("[CLS]")] + enc.ids], dtype=np.int64)   # the model drops [CLS]'s output
    pred = sess.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids)})[0][0].argmax(-1)
    tokens = [t if t != "[UNK]" else text[a:b] for t, (a, b) in zip(enc.tokens, enc.offsets)]
    out = ""
    for i, t in enumerate(tokens):
        tag = FRP_OUT[pred[i]]
        if t.startswith("##"): t = t[2:]
        elif re.search("[a-zA-Z0-9#]+", t) and i > 0 and re.search("[a-zA-Z0-9#]+", tokens[i - 1]) and pred[i - 1] == 0:
            t = " " + t
        out += t if tag == " " else t + tag
    out = out.replace("  ", " ")
    for z, e in zip("，。？！", ",.?!"):          # RuleBaedTxtFix, punctuation part
        out = re.sub(rf"([a-z0-9']){z}(?=[a-z0-9])", rf"\1{e} ", out)
        out = re.sub(rf"([a-z0-9']){z}$", rf"\1{e}", out)
    return out


def repunct(text, model):
    """Keep the words of `text` exactly (digits, casing) and replace only its punctuation by the
    model's: what a pipeline 'merge for words/digits/casing, model for punctuation' would give."""
    words = [m.group() for m in TOKEN.finditer(text)]
    marks = toks(model(bare(text)))
    if len(marks) != len(words): return text
    out = ""
    for i, w in enumerate(words):
        latin = bool(re.match(r"[A-Za-z0-9]", w))
        start = i == 0 or marks[i - 1][1] in (2, 3)
        if latin and start and w.islower(): w = w[0].upper() + w[1:]
        if out and latin and re.match(r"[A-Za-z0-9,.?]", out[-1]): out += " "
        out += w
        c = marks[i][1]
        if c: out += (",.?" if latin else "，。？")[c - 1]
    return out


def capfix(s):
    s = re.sub(r"(^|[.?!。？！]\s*)([a-z])", lambda m: m.group(1) + m.group(2).upper(), s)
    return re.sub(r"\bi\b", "I", s)


def caps(refs, hyps):
    """F1 of capitalised words, over the words both texts share."""
    tp = fp = fn = 0
    for ref, hyp in zip(refs, hyps):
        r = [m.group() for m in TOKEN.finditer(ref)]; h = [m.group() for m in TOKEN.finditer(hyp)]
        sm = difflib.SequenceMatcher(None, [x.lower() for x in r], [x.lower() for x in h], autojunk=False)
        for a, b, n in sm.get_matching_blocks():
            for k in range(n):
                if k + a == 0: continue          # sentence-initial capital: every pipeline gets it
                x, y = r[a + k][0].isupper(), h[b + k][0].isupper()
                tp += x and y; fp += y and not x; fn += x and not y
    return 200 * tp / (2 * tp + fp + fn) if tp else 0.0


# ---------------------------------------------------------------- scoring
def slots(ref, hyp):
    r, h = toks(ref), toks(hyp)
    sm = difflib.SequenceMatcher(None, [t for t, _ in r], [t for t, _ in h], autojunk=False)
    out = []
    for op, a1, a2, b1, b2 in sm.get_opcodes():
        if op == "equal":
            out += [(r[a1 + k][1], h[b1 + k][1]) for k in range(a2 - a1)]
        else:
            rl = [c for _, c in r[a1:a2]]; hl = [c for _, c in h[b1:b2]]
            out += [(c, 0) for c in rl[:-1] if c] + [(0, c) for c in hl[:-1] if c]
            out.append((rl[-1] if rl else 0, hl[-1] if hl else 0))
    final = bool(r) and bool(h) and (r[-1][1] == h[-1][1])
    return out, final


def score(refs, hyps):
    tp = [0] * 4; fp = [0] * 4; fn = [0] * 4; btp = bfp = bfn = 0; fin = 0
    for ref, hyp in zip(refs, hyps):
        s, f = slots(ref, hyp); fin += f
        for a, b in s:
            if a and b: btp += 1
            elif b: bfp += 1
            elif a: bfn += 1
            if a == b and a: tp[a] += 1
            else:
                if b: fp[b] += 1
                if a: fn[a] += 1
    def f1(t, p, n): return 200 * t / (2 * t + p + n) if t else 0.0
    return [f1(sum(tp), sum(fp), sum(fn)), f1(btp, bfp, bfn), f1(tp[1], fp[1], fn[1]), f1(tp[2], fp[2], fn[2]),
            f1(tp[3], fp[3], fn[3]), 100 * fin / len(refs)]


def load(key, ds):
    p = f"out_{key}_{ds}.json"
    return json.load(open(p)) if os.path.exists(p) else None


def timed(fn, texts):
    t = time.time(); out = [fn(x) for x in texts]
    return out, (time.time() - t) * 1000 / max(1, len(texts))


for ds in sys.argv[1:] or ["fleurs_zh_test", "fleurs_en_test", "cv_zh_test"]:
    sv, xa, xs, fr = (load(k, ds) for k in ("sensevoice-int8", "xasr-int8", "xasr-s480", "firered2-aed"))
    key = "ref" if "ref" in fr[0] else "sentence"
    refs = [x[key] for x in fr]
    n = len(refs)
    rows, dump = [], {"ref": refs}
    def add(name, hyps, ms=None):
        rows.append((name, score(refs, hyps) + [caps(refs, hyps)], ms)); dump[name] = hyps
    frb = [bare(x["hyp"]) for x in fr]
    add("SenseVoice alone", [x["hyp"] for x in sv])
    add("SenseVoice words + CT-Transformer", *timed(ct, [bare(x["hyp"]) for x in sv]))
    add("SenseVoice words + FireRedPunc", *timed(frp, [bare(x["hyp"]) for x in sv]))
    if xa: add("X-ASR offline alone", [x["hyp"] for x in xa])
    if xs: add("X-ASR streaming 480 alone", [x["hyp"] for x in xs])
    add("FireRed words, SenseVoice format (today)", [M.merge(a["hyp"], b["hyp"]) for a, b in zip(sv, fr)])
    if xa: add("FireRed words, X-ASR format", [M.merge(a["hyp"], b["hyp"]) for a, b in zip(xa, fr)])
    add("FireRed + CT-Transformer", *timed(ct, frb))
    add("FireRed + FireRedPunc", *timed(lambda t: capfix(frp(t)), frb))
    merged = dump["FireRed words, SenseVoice format (today)"]
    add("merge (today), punctuation by CT-Transf.", [repunct(t, ct) for t in merged])
    add("merge (today), punctuation by FireRedPunc", [repunct(t, frp) for t in merged])
    rb = [bare(x) for x in refs]
    add("(oracle words) + CT-Transformer", [ct(x) for x in rb])
    add("(oracle words) + FireRedPunc", [frp(x) for x in rb])
    json.dump(dump, open(f"punct_{ds}.json", "w"), ensure_ascii=False, indent=0)
    print(f"\n== {ds}  n={n}   F1: all marks | any boundary | comma | full stop | question   final mark right % | capitals F1 | ms/utt")
    for name, s, ms in rows:
        print(f"{name:42s} {s[0]:5.1f} {s[1]:5.1f} | {s[2]:5.1f} {s[3]:5.1f} {s[4]:5.1f} | {s[5]:5.1f} | {s[6]:5.1f}" + (f" | {ms:5.1f}" if ms else ""), flush=True)
