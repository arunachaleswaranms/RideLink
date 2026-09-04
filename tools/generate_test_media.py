#!/usr/bin/env python3
"""Generates Phase 3's local-music test fixtures into test-media/synthetic/.

Every fixture is synthesized by this script from a sine tone — never real music, never a
copyrighted recording, per REQUIREMENTS' "test-media/ — Only redistributable synthetic/test audio"
and ARCHITECTURE §9's "Only synthetic fixtures under test-media/synthetic/ are committed." The two
audio-generation primitives are `wave` (Python stdlib) to build a raw PCM tone and macOS's own
`afconvert` (Audio Toolbox, not a project dependency — it ships with the OS) to encode it to AAC/M4A.

**Why no MP3 fixture has real encoded audio.** This machine has no MP3 encoder: no ffmpeg, no lame,
no sox, and `afconvert` can *decode* MP3 but confirmed cannot *write* it (Apple ships no licensed
MP3 encoder — `afconvert -f MPG3` fails with `ExtAudioFileSetProperty ('cfmt') failed ('fmt?')`).
`minimal.mp3` is therefore a hand-authored ID3v2 header + one MPEG-1 Layer III frame header with a
zero-filled data section — enough to exercise extension/format *detection* and ID3 *tag parsing*,
never claimed as a verified full decode fixture. Real MP3 decoding is a native platform capability
(ExoPlayer, AVFoundation) exercised only by a real user's own MP3 files, not by anything this script
produces.

Regenerate with: python3 tools/generate_test_media.py
"""
from __future__ import annotations

import hashlib
import json
import math
import shutil
import struct
import subprocess
import sys
import time
import wave
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "test-media" / "synthetic"
SAMPLE_RATE = 44_100
MDLS_MAX_ATTEMPTS = 5
MDLS_RETRY_DELAY_S = 1.0
TRUNCATE_INSIDE_FTYP_BYTES = 15


# ─── Low-level PCM / AAC generation ─────────────────────────────────────────────────────────────


def make_tone_wav(path: Path, freq_hz: float, duration_s: float, sample_rate: int = SAMPLE_RATE) -> None:
    """A deterministic mono 16-bit PCM sine tone. No dependency beyond the stdlib `wave` module."""
    frame_count = int(sample_rate * duration_s)
    amplitude = 6000
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        frames = bytearray()
        for i in range(frame_count):
            sample = int(amplitude * math.sin(2 * math.pi * freq_hz * i / sample_rate))
            frames += struct.pack("<h", sample)
        w.writeframes(bytes(frames))


def encode_aac(wav_path: Path, m4a_path: Path) -> None:
    """Shells out to the OS-provided `afconvert` — not a project dependency, and the only tool in
    this environment that can produce real, decodable AAC/M4A (see module docstring)."""
    result = subprocess.run(
        ["afconvert", "-f", "m4af", "-d", "aac", str(wav_path), str(m4a_path)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"afconvert failed for {m4a_path}: {result.stdout} {result.stderr}")


# ─── Minimal MP4 (ISO base media) box parsing, just enough to inject iTunes-style metadata ───────


def read_top_level_boxes(data: bytes) -> list[tuple[int, int, str]]:
    """Returns (offset, size, fourcc) for each top-level box. 32-bit sizes only — afconvert's
    output never needs a 64-bit `size==1` box, and this script only ever reads what it just wrote."""
    boxes = []
    pos = 0
    while pos < len(data):
        size = struct.unpack(">I", data[pos : pos + 4])[0]
        fourcc = data[pos + 4 : pos + 8].decode("latin1")
        if size < 8:
            raise ValueError(f"malformed box at offset {pos}: size {size}")
        boxes.append((pos, size, fourcc))
        pos += size
    return boxes


def build_data_atom(type_indicator: int, payload: bytes) -> bytes:
    body = struct.pack(">II", type_indicator, 0) + payload  # type indicator + locale (always 0)
    return struct.pack(">I", 8 + len(body)) + b"data" + body


def build_metadata_item(fourcc: bytes, data_atom: bytes) -> bytes:
    body = data_atom
    return struct.pack(">I", 8 + len(body)) + fourcc + body


def build_ilst(title: str | None, artist: str | None, album: str | None, artwork_png: bytes | None) -> bytes:
    items = b""
    if title is not None:
        items += build_metadata_item(b"\xa9nam", build_data_atom(1, title.encode("utf-8")))
    if artist is not None:
        items += build_metadata_item(b"\xa9ART", build_data_atom(1, artist.encode("utf-8")))
    if album is not None:
        items += build_metadata_item(b"\xa9alb", build_data_atom(1, album.encode("utf-8")))
    if artwork_png is not None:
        items += build_metadata_item(b"covr", build_data_atom(14, artwork_png))  # 14 == PNG
    return struct.pack(">I", 8 + len(items)) + b"ilst" + items


def build_hdlr() -> bytes:
    """The minimal `meta`-box handler declaration strict parsers (ExoPlayer, AVFoundation) expect
    before `ilst` — version/flags(4) + pre-defined(4) + handler_type(4, 'mdir') + reserved(12) +
    empty name(1)."""
    body = struct.pack(">I", 0) + struct.pack(">I", 0) + b"mdir" + (b"\x00" * 12) + b"\x00"
    return struct.pack(">I", 8 + len(body)) + b"hdlr" + body


def build_udta(title: str | None, artist: str | None, album: str | None, artwork_png: bytes | None) -> bytes:
    ilst = build_ilst(title, artist, album, artwork_png)
    meta_body = struct.pack(">I", 0) + build_hdlr() + ilst  # meta's own version/flags(4) is always 0
    meta = struct.pack(">I", 8 + len(meta_body)) + b"meta" + meta_body
    return struct.pack(">I", 8 + len(meta)) + b"udta" + meta


def read_child_boxes(data: bytes, start: int, end: int) -> list[tuple[int, int, str]]:
    """Direct children of a container box occupying `data[start:end]` — used to find `moov`'s
    existing top-level children without recursing into grandchildren."""
    boxes = []
    pos = start
    while pos < end:
        size = struct.unpack(">I", data[pos : pos + 4])[0]
        fourcc = data[pos + 4 : pos + 8].decode("latin1")
        if size < 8:
            raise ValueError(f"malformed child box at offset {pos}: size {size}")
        boxes.append((pos, size, fourcc))
        pos += size
    return boxes


def inject_metadata(
    m4a_path: Path,
    title: str | None,
    artist: str | None,
    album: str | None,
    artwork_png: bytes | None = None,
) -> None:
    """Replaces `moov`'s existing `udta` box (afconvert always writes one, carrying its own
    encoder-identifying `----` freeform tag) with a fresh one carrying exactly the requested tags —
    **not** appended as a second sibling `udta`, which real parsers only look past to find the
    first one and silently ignore.

    Whatever `moov` grows or shrinks by is absorbed by resizing the `free` padding box afconvert
    always leaves immediately after it, so `mdat`'s absolute file offset — which every `stco`/
    `co64` chunk-offset table inside `moov` points at directly — never moves and no offset table
    anywhere in the file needs recomputing. If `free` cannot absorb the change, this raises rather
    than silently writing a file whose chunk offsets are wrong.
    """
    data = m4a_path.read_bytes()
    boxes = read_top_level_boxes(data)
    box_by_type = {fourcc: (offset, size) for offset, size, fourcc in boxes}
    if "moov" not in box_by_type or "free" not in box_by_type:
        raise RuntimeError(f"{m4a_path}: expected afconvert to emit moov and a free padding box, got {boxes}")
    moov_offset, moov_size = box_by_type["moov"]
    free_offset, free_size = box_by_type["free"]
    if free_offset != moov_offset + moov_size:
        raise RuntimeError(f"{m4a_path}: free box is not immediately after moov, cannot safely absorb growth")

    moov_children = read_child_boxes(data, moov_offset + 8, moov_offset + moov_size)
    kept_children = b"".join(
        data[offset : offset + size] for offset, size, fourcc in moov_children if fourcc != "udta"
    )
    new_udta = build_udta(title, artist, album, artwork_png)
    new_moov_body = kept_children + new_udta
    new_moov_size = 8 + len(new_moov_body)

    size_delta = new_moov_size - moov_size
    new_free_size = free_size - size_delta
    if new_free_size < 8:
        raise RuntimeError(
            f"{m4a_path}: free box ({free_size} bytes) is too small to absorb a {size_delta}-byte "
            "moov growth without moving mdat; shrink the metadata or lengthen the source tone."
        )

    new_moov = struct.pack(">I", new_moov_size) + b"moov" + new_moov_body
    new_free = struct.pack(">I", new_free_size) + b"free" + data[free_offset + 8 : free_offset + new_free_size]
    rebuilt = data[:moov_offset] + new_moov + new_free + data[free_offset + free_size :]
    m4a_path.write_bytes(rebuilt)


# ─── A tiny, deterministic, hand-encoded PNG (no Pillow/libpng dependency) ───────────────────────


def _png_chunk(tag: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)


def make_tiny_png(size: int = 8) -> bytes:
    """An `size`x`size` solid-color 8-bit RGB PNG, built directly from the PNG spec — small enough
    that Phase 3's artwork bound tests can also use it as a "this is a reasonable size" baseline."""
    header = b"\x89PNG\r\n\x1a\n"
    ihdr = _png_chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
    raw = bytearray()
    for _ in range(size):
        raw += b"\x00"  # filter type 0 (none) for this scanline
        for _ in range(size):
            raw += bytes((200, 40, 40))  # a solid dark-red pixel, RGB
    idat = _png_chunk(b"IDAT", zlib.compress(bytes(raw), level=9))
    iend = _png_chunk(b"IEND", b"")
    return header + ihdr + idat + iend


# ─── A hand-built minimal MP3 (see module docstring for exactly what this proves) ────────────────


def make_minimal_mp3(title: str, artist: str, album: str) -> bytes:
    def id3_frame(frame_id: bytes, text: str) -> bytes:
        payload = b"\x00" + text.encode("latin1", errors="replace")  # encoding byte 0 == ISO-8859-1
        return frame_id + struct.pack(">I", len(payload)) + b"\x00\x00" + payload

    frames = id3_frame(b"TIT2", title) + id3_frame(b"TPE1", artist) + id3_frame(b"TALB", album)

    def synchsafe(n: int) -> bytes:
        return bytes(((n >> (7 * i)) & 0x7F) for i in (3, 2, 1, 0))

    id3_header = b"ID3\x03\x00\x00" + synchsafe(len(frames))
    id3 = id3_header + frames

    # One MPEG-1 Layer III frame header: sync(11) + version(2)=11 + layer(2)=01 + protection(1)=1
    # (no CRC) + bitrate_index=1001 (128 kbps) + sampling_rate_index=00 (44100 Hz) + padding=0 +
    # private=0 + channel_mode=11 (mono) + mode_ext=00 + copyright=0 + original=0 + emphasis=00.
    header_bits = 0b11111111111_11_01_1_1001_00_0_0_11_00_0_0_00
    frame_header = struct.pack(">I", header_bits)
    # 128 kbps / 44100 Hz mono frame length = floor(144 * 128000 / 44100) + 0 padding = 417 bytes.
    frame_length = 417
    frame_body = b"\x00" * (frame_length - len(frame_header))
    return id3 + frame_header + frame_body


# ─── Fixture manifest ────────────────────────────────────────────────────────────────────────────


def sha256_hex(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR)
    OUT_DIR.mkdir(parents=True)
    tmp = OUT_DIR / "_tmp"
    tmp.mkdir()

    manifest: dict[str, dict] = {}

    def build(name: str, freq_hz: float, duration_s: float, **tag_kwargs) -> Path:
        wav = tmp / f"{name}.wav"
        out = OUT_DIR / f"{name}.m4a"
        make_tone_wav(wav, freq_hz, duration_s)
        encode_aac(wav, out)
        if tag_kwargs:
            inject_metadata(out, **tag_kwargs)
        return out

    artwork = make_tiny_png()

    normal = build(
        "normal",
        freq_hz=440.0,
        duration_s=0.5,
        title="Test Track",
        artist="Test Artist",
        album="Test Album",
        artwork_png=artwork,
    )
    manifest["normal.m4a"] = {
        "description": "A normal track: title/artist/album tags and embedded artwork.",
        "expected_title": "Test Track",
        "expected_artist": "Test Artist",
        "expected_album": "Test Album",
        "has_artwork": True,
        "sha256": sha256_hex(normal),
    }

    no_metadata = build("no_metadata", freq_hz=440.0, duration_s=0.5)
    manifest["no_metadata.m4a"] = {
        "description": "No tags at all — exercises the filename-fallback title and "
        "Unknown Artist/Album normalization.",
        "expected_title": None,
        "expected_artist": None,
        "expected_album": None,
        "has_artwork": False,
        "sha256": sha256_hex(no_metadata),
    }

    unicode_title = "テスト楽曲 \U0001f3b5"  # Japanese + a musical-note emoji
    unicode_artist = "É́lève"  # combining-mark-heavy Unicode
    unicode_track = build(
        "unicode_metadata",
        freq_hz=523.25,
        duration_s=0.5,
        title=unicode_title,
        artist=unicode_artist,
        album="中文專輯",
    )
    manifest["unicode_metadata.m4a"] = {
        "description": "Unicode tags (Japanese, an emoji, and combining marks) — exercises NFC "
        "normalization end to end, not just in the pure normalizer's own unit tests.",
        "expected_title": unicode_title,
        "expected_artist": unicode_artist,
        "expected_album": "中文專輯",
        "has_artwork": False,
        "sha256": sha256_hex(unicode_track),
    }

    no_artwork = build(
        "no_artwork",
        freq_hz=659.25,
        duration_s=0.5,
        title="No Artwork Track",
        artist="Test Artist",
        album="Test Album",
    )
    manifest["no_artwork.m4a"] = {
        "description": "Full text metadata, deliberately no artwork — exercises the placeholder-"
        "artwork UI path.",
        "expected_title": "No Artwork Track",
        "expected_artist": "Test Artist",
        "expected_album": "Test Album",
        "has_artwork": False,
        "sha256": sha256_hex(no_artwork),
    }

    # Duplicate content, different filenames: literally the same bytes, copied. Both sides must
    # compute the same content_hash and the indexer must dedup them to one library entry (FR-010).
    duplicate_source = normal
    duplicate_a = OUT_DIR / "duplicate_a.m4a"
    duplicate_b = OUT_DIR / "duplicate_b.m4a"
    duplicate_a.write_bytes(duplicate_source.read_bytes())
    duplicate_b.write_bytes(duplicate_source.read_bytes())
    manifest["duplicate_a.m4a"] = manifest["duplicate_b.m4a"] = {
        "description": "Byte-identical to normal.m4a under a different filename — same sha256, "
        "the FR-010 dedup case.",
        "sha256": sha256_hex(duplicate_a),
    }
    assert sha256_hex(duplicate_a) == sha256_hex(normal), "duplicate fixture must byte-match normal.m4a"

    # Same metadata, genuinely different audio bytes (different tone frequency): must NOT dedup.
    same_meta_a = build(
        "same_metadata_different_bytes_a",
        freq_hz=220.0,
        duration_s=0.5,
        title="Shared Title",
        artist="Shared Artist",
        album="Shared Album",
    )
    same_meta_b = build(
        "same_metadata_different_bytes_b",
        freq_hz=880.0,
        duration_s=0.5,
        title="Shared Title",
        artist="Shared Artist",
        album="Shared Album",
    )
    assert sha256_hex(same_meta_a) != sha256_hex(same_meta_b), "these two fixtures must NOT byte-match"
    for path in (same_meta_a, same_meta_b):
        manifest[path.name] = {
            "description": "Same title/artist/album as its pair, genuinely different audio bytes — "
            "must NOT be deduplicated; metadata alone is never identity (REQUIREMENTS §9.2).",
            "expected_title": "Shared Title",
            "expected_artist": "Shared Artist",
            "expected_album": "Shared Album",
            "has_artwork": False,
            "sha256": sha256_hex(path),
        }

    # A real AAC/M4A file truncated inside its very first box (`ftyp`) — a decoder must report
    # CORRUPT, not crash. Measured directly against `MediaMetadataRetriever` on a real Android
    # emulator, Android's own parser turned out to be considerably more forgiving than expected:
    # truncating to two-thirds of the file (damaging only the trailing `mdat` audio payload) still
    # parsed cleanly, and truncating a third of the way through `moov` *also* still parsed cleanly
    # (both tried and confirmed insufficient before this one) — M4A's metadata atoms sit entirely
    # before `mdat`, and the platform parser reads only as much of `moov` as it needs. Truncating
    # inside `ftyp` itself removes even a complete top-level box header, which is the point where a
    # correctly-behaving parser has no choice but to fail.
    corrupt = OUT_DIR / "corrupt.m4a"
    normal_bytes = normal.read_bytes()
    corrupt.write_bytes(normal_bytes[:TRUNCATE_INSIDE_FTYP_BYTES])
    manifest["corrupt.m4a"] = {
        "description": f"normal.m4a truncated to its first {TRUNCATE_INSIDE_FTYP_BYTES} bytes — cut "
        "off partway through its own ftyp box, before a single complete top-level box exists. A "
        "real file, deliberately damaged at the container-structure level (two much later "
        "truncation points were tried first and confirmed MediaMetadataRetriever still parses them "
        "cleanly). Must classify as CORRUPT, never crash the indexer.",
        "sha256": sha256_hex(corrupt),
    }

    # Valid audio bytes under an extension the indexer does not recognize at all.
    unsupported = OUT_DIR / "unsupported.xyz"
    unsupported.write_bytes(normal_bytes)
    manifest["unsupported.xyz"] = {
        "description": "normal.m4a's exact bytes under an unrecognized extension. Must classify as "
        "UNSUPPORTED (extension gate), never CORRUPT or crash.",
        "sha256": sha256_hex(unsupported),
    }

    # The hand-built minimal MP3 (see module docstring: format/tag-parsing coverage only).
    minimal_mp3 = OUT_DIR / "minimal.mp3"
    minimal_mp3.write_bytes(make_minimal_mp3("Minimal MP3", "Minimal Artist", "Minimal Album"))
    manifest["minimal.mp3"] = {
        "description": "Hand-built ID3v2 header + one zero-filled MPEG-1 Layer III frame — proves "
        "MP3 extension/ID3 tag detection only. NOT a verified decodable audio fixture (no MP3 "
        "encoder exists in this environment; see the module docstring). Real MP3 files decode via "
        "the platform's own native MP3 decoder, exercised only by a real user's own files.",
        "expected_title": "Minimal MP3",
        "expected_artist": "Minimal Artist",
        "expected_album": "Minimal Album",
        "sha256": sha256_hex(minimal_mp3),
    }

    shutil.rmtree(tmp)
    (OUT_DIR / "MANIFEST.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n")

    # Round-trip verification against a parser this script did not write: macOS Spotlight's own
    # metadata importer (`mdls`), independent of afconvert and of this script's atom builder.
    # (afinfo's `-i` InfoDictionary was tried first and does not surface iTunes-style atoms for
    # plain AAC/M4A at all — confirmed by inspecting its output against a hand-verified box dump —
    # so it is not a suitable check here; mdls is.)
    #
    # Spotlight indexes asynchronously, so the very first query right after a fresh write can race
    # its indexer — confirmed by re-running the identical query moments later and getting the
    # correct answer. Retried rather than treated as a one-shot check for exactly that reason.
    mdls_ok = False
    mdls_output = ""
    for attempt in range(MDLS_MAX_ATTEMPTS):
        if attempt > 0:
            time.sleep(MDLS_RETRY_DELAY_S)
        mdls = subprocess.run(
            ["mdls", "-name", "kMDItemTitle", "-name", "kMDItemAuthors", "-name", "kMDItemAlbum", str(normal)],
            capture_output=True,
            text=True,
        )
        mdls_output = mdls.stdout + mdls.stderr
        if "Test Track" in mdls.stdout and "Test Artist" in mdls.stdout and "Test Album" in mdls.stdout:
            mdls_ok = True
            break
    if not mdls_ok:
        print(f"ERROR: mdls did not read back the injected metadata for normal.m4a after {MDLS_MAX_ATTEMPTS} attempts", file=sys.stderr)
        print(mdls_output, file=sys.stderr)
        sys.exit(1)

    print(f"Wrote {len(manifest)} fixtures to {OUT_DIR}")
    for name in sorted(manifest):
        print(f"  {name}")


if __name__ == "__main__":
    main()
