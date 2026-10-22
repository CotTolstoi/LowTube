"""Offline integration test against yt-dlp's real format selector.
Usage: CoreTests.exe selectors.json; python validate_selectors.py selectors.json
Requires the yt_dlp Python package. No network or credentials are used.
"""
import json
import sys
from yt_dlp import YoutubeDL


def fmt(fid, height=None, vcodec="none", acodec="none", ext="mp4", fps=None):
    return dict(format_id=fid, height=height, vcodec=vcodec, acodec=acodec,
                ext=ext, fps=fps, url="https://example.invalid/" + fid,
                protocol="https", tbr=100)


formats = [
    fmt("c360", 360, "avc1.4D401E", "mp4a.40.2", fps=30),
    fmt("c1080", 1080, "avc1.4D4028", "mp4a.40.2", fps=60),
    fmt("v360", 360, "avc1.4D401E", fps=30),
    fmt("v720", 720, "avc1.4D401F", fps=30),
    fmt("webm720", 720, "vp9", ext="webm", fps=30),
    fmt("v720_60", 720, "avc1.4D4020", fps=60),
    fmt("v1080", 1080, "avc1.4D4028", fps=60),
    fmt("v2160", 2160, "vp9", ext="webm", fps=60),
    fmt("aac", acodec="mp4a.40.2", ext="m4a"),
    fmt("251", acodec="opus", ext="webm"),
]
selectors = json.load(open(sys.argv[1], encoding="utf-8-sig"))
output_template = selectors.pop("_output_template")
exact_selectors = {key: selectors.pop(key) for key in list(selectors) if key.startswith("_exact_")}


def select(selector, source):
    with YoutubeDL({"quiet": True}) as ydl:
        return list(ydl.build_format_selector(selector)({
            "formats": source, "has_merged_format": False,
            "incomplete_formats": False,
        }))


for name, selector in selectors.items():
    choices = select(selector, formats)
    assert choices, (name, "no selection")
    selected = choices[0].get("requested_formats", choices)
    if name in ("audio_only", "M4A", "MP3", "Opus"):
        assert all(f["vcodec"] == "none" for f in selected), name
    else:
        video = next(f for f in selected if f["vcodec"] != "none")
        assert video["height"] <= 720, name
        if name in ("play_h264", "MP4_30"):
            assert video["fps"] <= 30, name
        if name != "WebM":
            assert video["vcodec"].startswith("avc1"), name
    print("PASS actual yt-dlp selector:", name)

no_audio = [f for f in formats if f["vcodec"] != "none"]
assert not select(selectors["audio_only"], no_audio)
print("PASS audio-only never falls back to downloading video")

combined = [f for f in formats if f["vcodec"] != "none" and f["acodec"] != "none"]
fallback = select(selectors["play_h264"], combined)
assert fallback[0]["format_id"] == "c360"
print("PASS combined fallback preserves 720p / 30 FPS / H.264 limits")

too_high = [f for f in formats if f["height"] and f["height"] > 720] + [formats[-1]]
assert not select(selectors["play_h264"], too_high)
print("PASS no silent overshoot of quality / codec limits")

# Common real-world ladder: 480p30 followed by 720p60, with no 720p30.
ladder = [fmt("480_30", 480, "avc1.4D401E", fps=30),
          fmt("720_60", 720, "avc1.4D4020", fps=60), formats[-2]]
for preset, expected in {"play_h264": "480_30", "play_60": "720_60",
                         "MP4": "720_60", "MP4_30": "480_30"}.items():
    assert select(selectors[preset], ladder)[0]["requested_formats"][0]["format_id"] == expected
    print("PASS 480p30/720p60 ladder:", preset)

# Native ultrawide ladder: 640px is below the height cap; 800px is above it.
wide = [dict(fmt("wide640", 640, "avc1.4D401F", fps=30), width=1536, format_note="720p"),
        dict(fmt("wide800", 800, "avc1.4D4028", fps=30), width=1920, format_note="1080p"), formats[-2]]
chosen = select(selectors["MP4"], wide)[0]["requested_formats"][0]
assert (chosen["width"], chosen["height"]) == (1536, 640)
print("PASS ultrawide retains native size without exceeding height cap")

# A completed lower-quality file must not suppress a higher-quality download.
with YoutubeDL({"quiet": True, "outtmpl": output_template}) as ydl:
    base = dict(id="abcdefghijk", title="Sample", ext="mp4")
    low = ydl.prepare_filename(dict(base, format_id="135+140"))
    high = ydl.prepare_filename(dict(base, format_id="298+140"))
    assert low != high
print("PASS different selected formats do not collide on disk")

for kind, expected in {"MP4": "v720_60", "MKV": "v720_60", "WebM": "webm720",
                       "M4A": "aac", "MP3": "aac", "Opus": "251"}.items():
    result = select(exact_selectors["_exact_" + kind], formats)
    chosen = result[0].get("requested_formats", result)
    assert chosen[0]["format_id"] == expected
    if kind in ("M4A", "MP3", "Opus"):
        assert all(f["vcodec"] == "none" for f in chosen)
    else:
        assert len(chosen) == 2 and chosen[1]["vcodec"] == "none"
    print("PASS exact download dialog format:", kind)

missing = [f for f in formats if f["format_id"] != "v720_60"]
assert not select(exact_selectors["_exact_MP4"], missing)
print("PASS missing chosen format fails instead of silently lowering quality")
