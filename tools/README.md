# tools/

Local helper scripts. No network access, no third-party dependencies.

| Script | Purpose |
|---|---|
| `sideload/android.sh` | The Android sideload build (ADR-029 Amendment A2, `docs/SIDELOAD.md`): create the local signing key once, build/align/sign/verify exactly one clean commit with a provenance record, and install to an explicitly named physical phone, proving the installed bytes. Gradle may use its dependency cache; the script itself contacts nothing. |
| `extract_docx.py` | Extracts `docs/*.docx` to structured plain text using only the Python standard library (`zipfile` + `xml.etree`). Used to produce `docs/REQUIREMENTS.md` from the source-of-truth DOCX without installing `python-docx`. |

## Usage

```sh
python3 tools/extract_docx.py docs/RideLink_Requirements_and_Implementation_Plan.docx
```

Output format: one `[P style=...] text` line per paragraph and
`[TABLE START] / [ROW] cell || cell / [TABLE END]` blocks per table, in document order.

> The DOCX is read-only input. No tool in this directory may modify it.
