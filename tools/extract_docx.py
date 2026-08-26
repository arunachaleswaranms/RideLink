#!/usr/bin/env python3
"""Extract a DOCX to structured text using only the standard library.

Walks the body in document order, emitting paragraphs (with style name and
list level) and tables (as rows of cell text). Output is a plain-text
intermediate that we then hand-convert to REQUIREMENTS.md.
"""
import sys, zipfile, xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def q(tag):
    return W + tag

def para_text(p):
    """Concatenate all runs, honouring tabs/breaks, skipping deleted text."""
    out = []
    for node in p.iter():
        t = node.tag
        if t == q('t'):
            out.append(node.text or '')
        elif t == q('tab'):
            out.append('\t')
        elif t in (q('br'), q('cr')):
            out.append('\n')
    return ''.join(out)

def para_style(p):
    ppr = p.find(q('pPr'))
    if ppr is None:
        return None, None, False
    style = None
    ps = ppr.find(q('pStyle'))
    if ps is not None:
        style = ps.get(q('val'))
    level = None
    numpr = ppr.find(q('numPr'))
    if numpr is not None:
        ilvl = numpr.find(q('ilvl'))
        level = int(ilvl.get(q('val'))) if ilvl is not None else 0
    bold = False
    rpr = ppr.find(q('rPr'))
    if rpr is not None and rpr.find(q('b')) is not None:
        bold = True
    return style, level, bold

def all_runs_bold(p):
    runs = p.findall(q('r'))
    runs = [r for r in runs if r.find(q('t')) is not None]
    if not runs:
        return False
    for r in runs:
        rpr = r.find(q('rPr'))
        if rpr is None or rpr.find(q('b')) is None:
            return False
    return True

def walk(body, depth=0):
    for child in body:
        if child.tag == q('p'):
            txt = para_text(child)
            style, level, bold = para_style(child)
            if not txt.strip():
                continue
            meta = []
            if style:
                meta.append('style=' + style)
            if level is not None:
                meta.append('list=' + str(level))
            if bold or all_runs_bold(child):
                meta.append('bold')
            print('[P %s] %s' % (','.join(meta) if meta else '-', txt.strip()))
        elif child.tag == q('tbl'):
            print('[TABLE START]')
            for row in child.findall(q('tr')):
                cells = []
                for tc in row.findall(q('tc')):
                    parts = [para_text(p).strip() for p in tc.findall(q('p'))]
                    cells.append(' / '.join(x for x in parts if x))
                print('[ROW] ' + ' || '.join(cells))
            print('[TABLE END]')

with zipfile.ZipFile(sys.argv[1]) as z:
    root = ET.fromstring(z.read('word/document.xml'))
    body = root.find(q('body'))
    walk(body)
