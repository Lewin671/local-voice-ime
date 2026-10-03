"""Prototype: correct the words of a fast, well-formatted transcript (SenseVoice) with the words
of an accurate but unformatted one (FireRedASR2), keeping punctuation, digits and casing."""
import re, difflib
CJK = r"㐀-䶿一-鿿"
TOKEN = re.compile(rf"[{CJK}]|[A-Za-z0-9][A-Za-z0-9'.:%-]*[A-Za-z0-9%]|[A-Za-z0-9]")
NUMERAL = set("零〇一二两三四五六七八九十百千万亿点半第")
EN_NUM = {"zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen","twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety","hundred","thousand","million","and","point","percent"}
SENT_END = "。！？.!?"

def tokens(s):
    """[(text, trail)]: content tokens with whatever (punctuation, spaces) follows them."""
    out, pos, lead = [], 0, ""
    for m in TOKEN.finditer(s):
        gap = s[pos:m.start()]
        if out: out[-1][1] += gap
        else: lead = gap
        out.append([m.group(), ""]); pos = m.end()
    if out: out[-1][1] += s[pos:]
    return lead, out

def is_latin(t): return bool(re.match(r"[A-Za-z0-9]", t))

def merge(fast, accurate):
    lead, a = tokens(fast)
    _, b = tokens(accurate)
    if not b: return fast
    if not a: return accurate
    an = [t.lower() for t, _ in a]; bn = [t.lower() for t, _ in b]
    sm = difflib.SequenceMatcher(None, an, bn, autojunk=False)
    if sm.ratio() < 0.4:           # the two disagree almost everywhere: nothing to align
        a, an = [], []
        sm = difflib.SequenceMatcher(None, an, bn, autojunk=False)
        final = fast.strip()[-1:] if fast.strip()[-1:] in "。！？.!?，," else ""
    else: final = ""
    out = []                         # [text, trail]
    def emit_new(text):
        # words from the accurate model arrive upper-case; casing is decided here
        if is_latin(text):
            text = text.lower()
            start = not out or any(c in SENT_END for c in out[-1][1]) and not any(u'一' <= c <= u'鿿' for c in out[-1][0])
            if text == "i" or text.startswith("i'"): text = "I" + text[1:]
            elif start and not out: text = text.capitalize()
            elif start: text = text.capitalize()
        out.append([text, ""])
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            for k in range(i1, i2): out.append(list(a[k]))
        elif op == "delete":
            # words the accurate model did not hear: drop them, keep sentence punctuation
            trail = "".join(t for _, t in a[i1:i2])
            keep = "".join(c for c in trail if c in "，。！？,.!?、；;：:")[-1:]
            if out and keep and not out[-1][1].strip(): out[-1][1] = keep + (" " if keep in ",.!?;:" else "")
        elif op == "insert":
            for k in range(j1, j2): emit_new(b[k][0])
        else:
            sv = a[i1:i2]; fr = [t for t, _ in b[j1:j2]]
            digits = all(re.search(r"\d", t) for t, _ in sv)
            numerals = all((set(t) <= NUMERAL) or t.lower() in EN_NUM for t in fr)
            if digits and numerals:              # same number, already formatted by the fast model
                for k in range(i1, i2): out.append(list(a[k]))
                continue
            inner = "".join(t for _, t in sv[:-1])
            for t in fr: emit_new(t)
            out[-1][1] = sv[-1][1]
    s = lead
    for idx, (text, trail) in enumerate(out):
        s += text
        nxt = out[idx + 1][0] if idx + 1 < len(out) else ""
        if trail: s += trail
        elif nxt and is_latin(text) and is_latin(nxt): s += " "
    s += final
    # tidy: no spaces next to CJK
    s = re.sub(rf"(?<=[{CJK}，。！？、；：]) +| +(?=[{CJK}，。！？、；：])", "", s)
    return re.sub(r" {2,}", " ", s).strip()

if __name__ == "__main__":
    for f, acc in [("今天下午3点，我们在会议室开会讨论一下下个季度的产品规化。你觉得这个方案怎么样？", "今天下午三点我们在会议室开会讨论一下下个季度的产品规划你觉得这个方案怎么样"),
                   ("我刚刚把代码push到gihub上了，你帮我review一下这个pool request。", "我刚刚把代码 PUSH到 GITHUB上了你帮我 REVIEW一下这个 PULL REQUEST"),
                   ("Please send me the report by Friday afternoon. i will review it over the weekend.", "PLEASE SEND ME THE REPORT BY FRIDAY AFTERNOON I WILL REVIEW IT OVER THE WEEKEND"),
                   ("开饭时间早上9点至下午5点。", "开放时间早上九点至下午五点"),
                   ("The tribal chieftain called for the boy and presented him with 50 pieces of code.", "THE TRIBAL CHIEFTAIN CALLED FOR THE BOY AND PRESENTED HIM WITH FIFTY PIECES OF GOLD")]:
        print(merge(f, acc))
