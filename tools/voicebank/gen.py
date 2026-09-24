#!/usr/bin/env python3
"""
Flight Deck Sentry voice bank generator (offline).

Renders every clip in phrases.txt with Piper TTS (voice en_US-kristin-medium, public domain) into
app/src/main/assets/voice/<id>.ogg (22.05 kHz mono, Ogg Vorbis q3) and writes
app/src/main/assets/voice/manifest.tsv (id <TAB> grammar match words), which the app's callout
grammar (core VoiceGrammar) loads. Each clip: leading/trailing silence trimmed, peak-normalised to
-1 dBFS, 12 ms of silence on each side, then sped up 1.2x (atempo, pitch kept).

Short inputs (one letter, one word) are where a small neural voice is least stable: the same text
sometimes comes out as mumble. So every clip is rendered several times (varied noise settings and
spellings) and, when faster-whisper is installed, each candidate is transcribed and the one that
is heard as the intended word wins (ties: the most typical duration). Clips no candidate got right
are listed in report.txt. Without faster-whisper the most typical duration wins.

Also renders docs/voice-sample-warning.ogg: the clip sequence in sample.txt (exactly what the app's
grammar produces for that sentence; VoiceGrammarTest checks it) joined with the app's pauses.

--check FILE assembles each "sentence<TAB>clip ids" line of FILE (written by VoiceGrammarTest to
core/build/voice-sentences.tsv) from the bank and transcribes it, to prove whole callouts are
intelligible when stitched from clips.

Setup (once):
  python3 -m venv ~/.venvs/piper && ~/.venvs/piper/bin/pip install piper-tts faster-whisper
  mkdir -p ~/.venvs/piper-voices && cd ~/.venvs/piper-voices && \\
    ~/.venvs/piper/bin/python -m piper.download_voices en_US-kristin-medium
Run (from the repo root):
  ~/.venvs/piper/bin/python tools/voicebank/gen.py
  ~/.venvs/piper/bin/python tools/voicebank/gen.py --check core/build/voice-sentences.tsv
Needs ffmpeg (with libvorbis) on PATH.
"""
import argparse, os, re, subprocess, sys, tempfile, wave
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = REPO / "app/src/main/assets/voice"
SR = 22050
PAD_S = 0.012
# Clips are sped up by this factor after rendering (ffmpeg atempo: pitch kept). Words said on their own come out
# long; this brings a stitched callout closer to natural pace.
TEMPO = 1.2
# Pauses the app inserts for punctuation (must match ClipVoice.PAUSE_SHORT_MS / PAUSE_LONG_MS).
PAUSE_COMMA_S = 0.060
PAUSE_STOP_S = 0.150
VOICE = os.path.expanduser("~/.venvs/piper-voices/en_US-kristin-medium.onnx")

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
# What a listener may write down for each letter name.
LETTER_HEARD = {
    "a": "a ay eh hey", "b": "b be bee", "c": "c see sea", "d": "d dee", "e": "e ee", "f": "f eff ef", "g": "g gee jee",
    "h": "h aitch age", "i": "i eye aye", "j": "j jay", "k": "k kay okay", "l": "l el ell elle", "m": "m em emm",
    "n": "n en and", "o": "o oh", "p": "p pee pea", "q": "q cue queue", "r": "r are ar our", "s": "s ess", "t": "t tee tea",
    "u": "u you", "v": "v vee", "w": "w double", "x": "x ex", "y": "y why", "z": "z zee",
}


def num_words(n):
    if n < 20:
        return ONES[n]
    t, o = divmod(n, 10)
    return TENS[t] + ("" if o == 0 else " " + ONES[o])


def int_words(n):
    if n < 100:
        return num_words(n)
    if n < 1000:
        return ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + int_words(n % 100))
    if n < 100000:
        return int_words(n // 1000) + " thousand" + ("" if n % 1000 == 0 else " " + int_words(n % 1000))
    return " ".join(ONES[int(c)] for c in str(n))


def norm(s):
    s = s.lower().replace("-", " ")
    # a callsign heard as one token ("N388KM") is compared letter by letter
    s = re.sub(r"\b(?=[a-z0-9]*\d)(?=[a-z0-9]*[a-z])[a-z0-9]+\b", lambda m: " ".join(m.group(0)), s)
    s = re.sub(r"(\d),(\d)", r"\1\2", s)
    s = re.sub(r"(\d+)\.(\d+)", lambda m: m.group(1) + " point " + " ".join(m.group(2)), s)
    s = re.sub(r"\d+", lambda m: " " + int_words(int(m.group(0))) + " ", s)
    s = s.replace("tfr", "t f r").replace("gps", "g p s")
    s = re.sub(r"[^a-z' ]+", " ", s)
    return " ".join(s.split())


def read_phrases():
    rows = []
    for line in (HERE / "phrases.txt").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cid, text, match = (line.split("|") + ["", ""])[:3]
        rows.append((cid.strip(), text.strip(), match.strip()))
    ids = [r[0] for r in rows]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        sys.exit(f"duplicate ids: {sorted(dup)}")
    return rows


def expected(cid, text):
    """(accepted normalised transcripts, text variants to render)."""
    if cid.startswith("l_"):
        k = cid[2:]
        return set(LETTER_HEARD[k].split()), [text, k.upper() + ".", k.upper()]
    if re.fullmatch(r"[ht]\d+", cid):
        n = int(cid[1:]) * (100 if cid[0] == "h" else 1000)
        return {norm(str(n))}, [text, f"{n:,}.", text.rstrip(".")]
    if re.fullmatch(r"n\d+", cid):
        n = int(cid[1:])
        return {norm(num_words(n))}, [text, f"{n}.", num_words(n).capitalize() + "."]
    base = norm(text)
    return {base, "one " + base}, [text, text.rstrip(".,") + ".", text.rstrip(".,")]


def trim_norm_pad(a):
    # generous threshold + margins: a soft onset ("p" in "passing") sits 30-40 dB under the peak
    thr = 10 ** (-50 / 20) * max(1e-9, float(np.max(np.abs(a))))
    idx = np.where(np.abs(a) > thr)[0]
    if len(idx):
        a = a[max(0, idx[0] - int(0.015 * SR)): idx[-1] + int(0.020 * SR)]
    peak = float(np.max(np.abs(a))) or 1.0
    a = a * (10 ** (-1 / 20) / peak)
    pad = np.zeros(int(PAD_S * SR), dtype=np.float32)
    return np.concatenate([pad, a, pad]).astype(np.float32)


def tempo(a):
    """The same speed-up the .ogg gets (atempo), for judging a candidate as it will sound."""
    with tempfile.TemporaryDirectory() as td:
        write_wav(Path(td) / "i.wav", a)
        raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(Path(td) / "i.wav"), "-filter:a", f"atempo={TEMPO}",
                              "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, "<i2").astype(np.float32) / 32768


def write_wav(path, a):
    pcm = (np.clip(a, -1, 1) * 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm)


def to_ogg(wav, ogg):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-ac", "1", "-ar", str(SR),
                    "-filter:a", f"atempo={TEMPO}", "-c:a", "libvorbis", "-q:a", "3", "-map_metadata", "-1", str(ogg)], check=True)


# Clips whose sound depends on what is around them are judged IN CONTEXT, stitched between clips already in the bank
# (a letter inside a callsign, a direction after "Traffic,"), which is how the controller plays them.
DIRECTIONS = "north northeast east southeast south southwest west northwest".split()
TRENDS = "converging diverging passing closing".split()


# Extra spellings to try for words the voice tends to blur in a stitched callout (found with --check).
EXTRA_VARIANTS = {
    "northwest": ["North west.", "North-west.", "Northwest!"],
    "northeast": ["North east.", "North-east.", "Northeast!"],
    "southwest": ["South west.", "South-west."],
    "southeast": ["South east.", "South-east."],
    "diverging": ["Diverging!", "Dye-verging.", "Di-verging."],
    "drone_feed": ["Drone feed.", "Drone feed!"],
}


def context_for(cid):
    """(clip ids before, clip ids after, expected normalised text with {} for the word) or None."""
    if cid.startswith("l_"):
        # inside a callsign and followed by a direction, as in every traffic callout
        return ["l_n", "n3", "n8", "l_k"], [",", "north"], "n three eight k {} north"
    if cid in DIRECTIONS:
        return ["traffic", ","], [",", "t1", "h5", "feet"], "traffic {} one thousand five hundred feet"
    if cid in TRENDS:
        return ["same_altitude", ","], [], "same altitude {}"
    return None


def load_asr():
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        print("faster-whisper not installed: candidates chosen by duration only")
        return None
    return WhisperModel("small.en", device="cpu", compute_type="int8")


def transcribe(asr, a, prompt=None):
    # whisper wants 16 kHz; a little silence around a single word helps it
    x = np.concatenate([np.zeros(int(0.3 * SR), np.float32), a, np.zeros(int(0.3 * SR), np.float32)])
    idx = np.linspace(0, len(x) - 1, int(len(x) * 16000 / SR))
    x16 = np.interp(idx, np.arange(len(x)), x).astype(np.float32)
    segs, _ = asr.transcribe(x16, beam_size=5, language="en", vad_filter=False, initial_prompt=prompt,
                             condition_on_previous_text=False)
    return " ".join(s.text.strip() for s in segs)


def ogg_to_float(path):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(path), "-f", "s16le", "-ac", "1", "-ar", str(SR), "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, "<i2").astype(np.float32) / 32768


def assemble(seq, clips):
    parts = []
    for tok in seq:
        if tok == ",":
            parts.append(np.zeros(int(PAUSE_COMMA_S * SR), np.float32))
        elif tok == ".":
            parts.append(np.zeros(int(PAUSE_STOP_S * SR), np.float32))
        else:
            parts.append(clips[tok])
    return np.concatenate(parts)


def check(path):
    asr = load_asr()
    if asr is None:
        sys.exit("--check needs faster-whisper")
    clips = {p.stem: ogg_to_float(p) for p in OUT.glob("*.ogg")}
    ok = total = 0
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        sentence, seq = line.split("\t")
        heard = transcribe(asr, assemble(seq.split(), clips), prompt="Drone traffic alert.")
        a, b = norm(sentence).split(), norm(heard).split()
        # word error rate by edit distance
        d = list(range(len(b) + 1))
        for i in range(1, len(a) + 1):
            prev, d[0] = d[0], i
            for j in range(1, len(b) + 1):
                cur = d[j]
                d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
                prev = cur
        wer = d[len(b)] / max(1, len(a))
        total += 1; ok += wer <= 0.15
        print(f"WER {wer:4.2f}  {sentence}\n           heard: {heard}")
    print(f"{ok}/{total} sentences with WER <= 0.15")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice", default=VOICE)
    ap.add_argument("--length-scale", type=float, default=0.9, help="< 1 speaks a little faster")
    ap.add_argument("--check", help="transcribe the sentences in this file, stitched from the bank")
    ap.add_argument("--only", help="comma-separated clip ids to (re)render; others are kept")
    args = ap.parse_args()
    if args.check:
        check(args.check); return

    from piper import PiperVoice
    from piper.config import SynthesisConfig
    voice = PiperVoice.load(args.voice)
    asr = load_asr()
    rows = read_phrases()
    only = set(args.only.split(",")) if args.only else None
    OUT.mkdir(parents=True, exist_ok=True)
    # Existing clips are overwritten in place (not deleted first), so in-context judging of a letter or direction
    # can use its neighbours from the previous run; clips no longer in phrases.txt are removed at the end.
    report = []
    with tempfile.TemporaryDirectory() as td:
        for cid, text, match in rows:
            if only is not None and cid not in only:
                continue
            accept, variants = expected(cid, text)

            def render(settings):
                out = []
                for v, ls, ns, nw in settings:
                    cfg = SynthesisConfig(length_scale=ls, noise_scale=ns, noise_w_scale=nw)
                    a = np.concatenate([c.audio_float_array for c in voice.synthesize(v, cfg)]).astype(np.float32)
                    out.append((v, ns, trim_norm_pad(a)))
                return out

            ctx = context_for(cid)
            if ctx and not all((OUT / f"{c}.ogg").exists() for c in ctx[0] + ctx[1] if c not in ",."):
                ctx = None                     # first run: neighbours not rendered yet; judge in isolation
            nb = {c: ogg_to_float(OUT / f"{c}.ogg") for c in (ctx[0] + ctx[1] if ctx else []) if c not in ",."}

            def judge(a):
                if not asr:
                    return True, ""
                if ctx:
                    stitched = assemble(ctx[0] + ["#"] + ctx[1], {**nb, "#": tempo(a)})
                    heard = norm(transcribe(asr, stitched, "Drone traffic alert."))
                    squash = lambda x: x.replace(" ", "")
                    return any(squash(heard) == squash(ctx[2].format(w)) for w in accept), heard
                heard = norm(transcribe(asr, a, "Spelled callsign letter:" if cid.startswith("l_") else "Drone traffic alert:"))
                return heard in accept or (cid.startswith("l_") and bool(heard.split()[:1]) and heard.split()[0] in accept), heard

            def pick(cands, best=None):
                # try the most typical-length candidates first; stop at the first one heard as intended
                med = float(np.median([len(c[2]) for c in cands]))
                for v, ns, a in sorted(cands, key=lambda c: abs(len(c[2]) - med)):
                    good, heard = judge(a)
                    if best is None:
                        best = (True, v, ns, a, heard)
                    if good or not asr:
                        return (not good, v, ns, a, heard)
                return best

            vs = list(dict.fromkeys(variants + EXTRA_VARIANTS.get(cid, [])))
            best = pick(render([(v, args.length_scale, ns, nw) for v in vs for ns, nw in ((0.667, 0.8), (0.5, 0.6), (0.333, 0.5))]))
            if asr and best[0]:
                # short inputs are where the model wobbles: widen the search (pace, noise, punctuation)
                more = vs + [v.rstrip(".,!") + "!" for v in vs] + [v.lower() for v in vs]
                best = pick(render([(v, ls, ns, 0.6) for v in dict.fromkeys(more) for ls in (0.85, 1.0, 1.15) for ns in (0.25, 0.8)]), best)
            bad, v, ns, a, heard = best
            if asr and bad:
                report.append(f"{cid}: no candidate heard as {sorted(accept)}; kept '{v}' noise {ns} (heard '{heard}')")
            w = Path(td) / f"{cid}.wav"
            write_wav(w, a)
            to_ogg(w, OUT / f"{cid}.ogg")
            print(f"{cid:40s} {'?' if bad and asr else ' '} '{v}' ns={ns} {len(a)/SR:.2f}s heard='{heard}'", flush=True)
        for old in OUT.glob("*.ogg"):
            if old.stem not in {r[0] for r in rows}:
                old.unlink()
        (OUT / "manifest.tsv").write_text(
            "# generated by tools/voicebank/gen.py from phrases.txt; id<TAB>match words\n" +
            "".join(f"{cid}\t{match}\n" for cid, _, match in rows))
        if only is None:
            (HERE / "report.txt").write_text(
                "# Clips no rendered candidate was transcribed as the intended word (small.en ASR; letters, directions and\n"
                "# trend words judged in context, stitched between their bank neighbours). Empty = every clip passed.\n"
                "# The test that matters is the stitched sentences: gen.py --check core/build/voice-sentences.tsv\n"
                + "".join(r + "\n" for r in report))
        lines = [l for l in (HERE / "sample.txt").read_text().splitlines() if l.strip() and not l.startswith("#")]
        if len(lines) >= 2:
            clips = {p.stem: ogg_to_float(p) for p in OUT.glob("*.ogg")}
            w = Path(td) / "sample.wav"
            write_wav(w, assemble(lines[1].split(), clips))
            to_ogg(w, REPO / "docs/voice-sample-warning.ogg")
            print(f"sample: {lines[0]}")
    total = sum(f.stat().st_size for f in OUT.glob("*"))
    print(f"{len(rows)} clips, {total / 1024:.0f} KiB in {OUT.relative_to(REPO)}; {len(report)} flagged (report.txt)")


if __name__ == "__main__":
    main()
