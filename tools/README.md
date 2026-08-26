# tools/

Local helper scripts. No network access, no third-party dependencies.

| Script | Purpose |
|---|---|
| `extract_docx.py` | Extracts `docs/*.docx` to structured plain text using only the Python standard library (`zipfile` + `xml.etree`). Used to produce `docs/REQUIREMENTS.md` from the source-of-truth DOCX without installing `python-docx`. |

## Usage

```sh
python3 tools/extract_docx.py docs/RideLink_Requirements_and_Implementation_Plan.docx
```

Output format: one `[P style=...] text` line per paragraph and
`[TABLE START] / [ROW] cell || cell / [TABLE END]` blocks per table, in document order.

> The DOCX is read-only input. No tool in this directory may modify it.
