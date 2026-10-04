"""Do hot words help Fun-ASR-nano, and what do they break?

    bench_hotwords.py run <model_dir> <dataset> <n> <condition>
    bench_hotwords.py report <dataset>

Fun-ASR-nano takes a list of hot words in its prompt (sherpa-onnx `hotwords`). This measures, on
real speech, how many of the listed words it then gets right, how often it writes a listed word
that was not said, and what happens to the rest of the text.

The list is what a user would build: words taken from the reference transcripts (English words
of four letters or more that are not everyday words, i.e. occur at most twice in the LibriSpeech
test transcripts, and Chinese names of people, places and organisations found by jieba).
Conditions:

    none      no hot words; must be run first, the lists are derived from its transcripts
    hard      the words the model got wrong at least once without hot words
    hard@A-B  a slice of that list, for short lists: hard@0-10, hard@10-20, ...
    hard+dN   the same plus N words that nobody says in the test set (DISTRACTORS)
    all       every word found, including the ones that were already right
    oracle    a list per utterance: exactly the hard words said in it, as a perfect retrieval
              step would supply them; oracle+dN adds N words that are not said

Same working directory as bench.py (see docs/MODELS.md); needs `pip install jieba` in addition.
Transcripts go to out_hot_<dataset>_<condition>.json.
"""
import sys, glob, json, time, re
import soundfile as sf, sherpa_onnx, jiwer
import bench

# Words a user of this keyboard might list, none of which occurs in the test sets.
DISTRACTORS = """GitHub, pull request, Kubernetes, Claude, Anthropic, PyTorch, TypeScript, Kotlin,
sherpa-onnx, SenseVoice, FireRedASR, Obsidian, Gradle, Android Studio, Jetpack Compose, Redis,
PostgreSQL, Grafana, Prometheus, Terraform, WebSocket, OAuth, GraphQL, Docker Compose, Nginx,
Cloudflare, Tailscale, Raspberry Pi, HuggingFace, ModelScope, LangChain, embedding, tokenizer,
quantization, transformer, backpropagation, Zipformer, Paraformer, Whisper, onnxruntime, Xcode,
SwiftUI, Homebrew, Figma, Notion, Linear, Vercel, Supabase, Stripe, Datadog, Sentry, Jira,
Confluence, rebase, cherry-pick, worktree, middleware, idempotent, liveness probe, 刘庆颖, 王梓涵,
欧阳修远, 司马昭然, 慕容清扬, 诸葛明轩, 上官婉清, 贺兰敏之, 尉迟敬宗, 端木子衿, 澹台灭明, 夏侯惇元,
皇甫嵩然, 公孙策远, 轩辕剑鸣, 令狐冲霄, 宇文成都, 长孙无垢, 独孤伽蓝, 拓跋宏远, 飞猪旅行, 携程商旅,
华泰证券, 涨乐财富通, 雪球基金, 蔚来汽车, 理想同学, 小鹏智驾, 鸿蒙座舱, 豆包语音, 通义千问,
文心一言, 智谱清言, 月之暗面, 零一万物, 阶跃星辰, 梅里雪山, 雨崩村, 稻城亚丁, 贡嘎雪山, 四姑娘山,
武功山, 哈巴雪山, 泸沽湖, 色达县, 年保玉则, 冷嘎措, 子梅垭口, 萨普神山, 岗什卡, 那玛峰, 乌孙古道,
喀拉峻, 夏塔古道, 赛里木湖, 可可托海, 禾木村, 白哈巴, 琼库什台, 独库公路""".replace("\n", " ")
DISTRACTORS = [w.strip() for w in DISTRACTORS.split(",")]


def load_model(d, hotwords, threads=4):
    tok = [x for x in glob.glob(f"{d}/*") if "Qwen" in x][0]
    return sherpa_onnx.OfflineRecognizer.from_funasr_nano(
        encoder_adaptor=f"{d}/encoder_adaptor.int8.onnx", llm=f"{d}/llm.int8.onnx",
        embedding=f"{d}/embedding.int8.onnx", tokenizer=tok, num_threads=threads,
        hotwords=",".join(hotwords))


_everyday = None


def everyday():
    """English words nobody would put on a hot word list."""
    global _everyday
    if _everyday is None:
        import collections, pyarrow.parquet as pq
        text = pq.read_table("data/librispeech_test_clean.parquet", columns=["text"])["text"].to_pylist()
        count = collections.Counter(w for t in text for w in re.findall(r"[a-z']+", t.lower()))
        _everyday = {w for w, k in count.items() if k > 2}
    return _everyday


def terms_of(ref):
    """Words of one reference transcript that a user might put on a hot word list."""
    import jieba.posseg
    out = set(re.findall(r"[a-z][a-z']{3,}", ref.lower())) - everyday()
    for w in jieba.posseg.cut(re.sub(r"[^一-鿿]", " ", ref)):
        if w.flag[:2] in ("nr", "ns", "nt", "nz") and len(w.word) >= 2:
            out.add(w.word)
    return out


def has(term, normalized):
    """Whether [normalized] (output of bench.norm) contains [term]."""
    t = bench.norm(term)
    if re.search(r"[一-鿿]", t):
        return t.replace(" ", "") in normalized.replace(" ", "")
    return f" {t} " in f" {normalized} "


def lists(ds):
    """(hard, all) hot word lists for a data set, from the transcripts made without hot words."""
    res = json.load(open(f"out_hot_{ds}_none.json"))
    hard, every = set(), set()
    for x in res:
        hyp = bench.norm(x["hyp"])
        for t in terms_of(x["ref"]):
            every.add(t)
            if not has(t, hyp):
                hard.add(t)
    return sorted(hard), sorted(every)


def hotwords_for(ds, cond):
    if cond == "none":
        return []
    hard, every = lists(ds)
    if cond == "all":
        return every
    m = re.fullmatch(r"hard(@(\d+)-(\d+))?(\+d(\d+))?", cond)
    if not m:
        raise SystemExit(cond)
    if m.group(1):
        hard = hard[int(m.group(2)):int(m.group(3))]
    refs = " | ".join(bench.norm(x["ref"]) for x in json.load(open(f"out_hot_{ds}_none.json")))
    spare = [w for w in DISTRACTORS if not has(w, refs)]
    return hard + spare[:int(m.group(5) or 0)]


def run_oracle(d, ds, n, cond):
    """Every utterance gets its own list: the hard words that are said in it (plus N others)."""
    base = json.load(open(f"out_hot_{ds}_none.json"))
    hard = set(lists(ds)[0])
    extra = int(cond.split("+d")[1]) if "+d" in cond else 0
    res, audio, cost, used = [], 0.0, 0.0, 0
    for (x, sr, ref), b in zip(bench.load(ds, n), base):
        words = sorted(hard & terms_of(ref))
        if not words:  # no hot words: the transcript made without them
            res.append(b)
            continue
        words += DISTRACTORS[used % 50:][:extra]
        used += 1
        r = load_model(d, words)
        t = time.time()
        st = r.create_stream(); st.accept_waveform(sr, x); r.decode_stream(st)
        cost += time.time() - t; audio += len(x) / sr
        res.append({"ref": ref, "hyp": st.result.text, "hotwords": words})
    json.dump({"hotwords": sorted(hard), "rtf": cost / audio, "audio": audio, "results": res},
              open(f"out_hot_{ds}_{cond}.json", "w"), ensure_ascii=False, indent=0)
    print(f"{ds} {cond}: {used} of {len(res)} utterances had hot words, RTF={cost/audio:.3f} on those")


def run(d, ds, n, cond):
    if cond.startswith("oracle"):
        return run_oracle(d, ds, n, cond)
    words = hotwords_for(ds, cond)
    r = load_model(d, words)
    res, audio, cost = [], 0.0, 0.0
    for x, sr, ref in bench.load(ds, n):
        t = time.time()
        st = r.create_stream(); st.accept_waveform(sr, x); r.decode_stream(st)
        cost += time.time() - t; audio += len(x) / sr
        res.append({"ref": ref, "hyp": st.result.text})
    json.dump({"hotwords": words, "rtf": cost / audio, "audio": audio, "results": res} if cond != "none" else res,
              open(f"out_hot_{ds}_{cond}.json", "w"), ensure_ascii=False, indent=0)
    print(f"{ds} {cond}: {len(words)} hot words, {len(res)} utterances, {audio:.0f}s audio, RTF={cost/audio:.3f}")


def errors(ref, hyp):
    if not ref:
        return 0
    if not hyp:
        return len(ref.split())
    o = jiwer.process_words(ref, hyp)
    return o.substitutions + o.deletions + o.insertions


def report(ds):
    base = json.load(open(f"out_hot_{ds}_none.json"))
    refs = [bench.norm(x["ref"]) for x in base]
    base_hyps = [bench.norm(x["hyp"]) for x in base]
    ref_terms = [terms_of(x["ref"]) for x in base]
    runs = {"none": {"hotwords": [], "results": base, "rtf": None}}
    for f in sorted(glob.glob(f"out_hot_{ds}_*.json")):
        c = f[len(f"out_hot_{ds}_"):-5]
        if c != "none":
            runs[c] = json.load(open(f))
    hyps = {c: [bench.norm(x["hyp"]) for x in v["results"]] for c, v in runs.items()}
    # as in bench_all.py: utterances with digits in a transcript are not scored
    keep = [i for i in range(len(base)) if refs[i] and not any(re.search(r"\d", h[i]) for h in hyps.values())]
    hard, every = lists(ds)
    print(f"## {ds}: {len(base)} utterances, {len(keep)} scored; {len(every)} listable words, {len(hard)} hard")
    print("condition | list | listed words right: without -> with hot words | hard words right | all listable right | listed words written but not said"
          " (same list, no hot words) | scored utterances changed: better / worse / same errors"
          " | empty or looping | error rate | RTF")
    for c, v in runs.items():
        words = v["hotwords"]

        def recall(terms):
            said = [(i, t) for i in range(len(base)) for t in ref_terms[i] if t in terms]
            return f"{sum(has(t, hyps[c][i]) for i, t in said)}/{len(said)}"

        def false_hits(hs):
            return sum(has(w, hs[i]) and not has(w, refs[i]) for i in range(len(base)) for w in words)

        better = worse = same = broken = 0
        for i in keep:
            n = len(hyps[c][i].split())
            # nothing at all, or a loop ("home home home ...")
            broken += (n == 0 and len(refs[i].split()) > 3) or n > 2 * len(refs[i].split()) + 10
            if hyps[c][i] == base_hyps[i]:
                continue
            a, b = errors(refs[i], base_hyps[i]), errors(refs[i], hyps[c][i])
            better += b < a; worse += b > a; same += a == b
        er = jiwer.wer([refs[i] for i in keep], [hyps[c][i] for i in keep]) * 100
        rtf = f"{v['rtf']:.3f}" if v["rtf"] else "-"
        said = [(i, t) for i in range(len(base)) for t in ref_terms[i] if t in set(words)]
        listed = f"{sum(has(t, base_hyps[i]) for i, t in said)} -> {sum(has(t, hyps[c][i]) for i, t in said)} of {len(said)}"
        print(f"{c} | {len(words)} | {listed} | {recall(set(hard))} | {recall(set(every))} | {false_hits(hyps[c])}"
              f" ({false_hits(base_hyps)}) | {better} / {worse} / {same} | {broken} | {er:.2f} | {rtf}")


if __name__ == "__main__":
    if sys.argv[1] == "run":
        run(sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5])
    else:
        report(sys.argv[2])
