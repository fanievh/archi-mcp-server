#!/usr/bin/env python3
"""
Archi Model → PDF Architecture Report generator.

Talks to the Archi MCP Server over its loopback HTTP endpoint, harvests the
whole model (info, views, per-view contents), exports each view to PNG, and
renders a polished, print-ready PDF via WeasyPrint.

Model-agnostic: no element/view names are hardcoded. Optimal view order is
derived from the modeller's numbering + folder structure. Provenance is built
honestly from model name/purpose/properties + the report timestamp (the MCP
`modelVersion` is a session counter, NOT the model version — it is not used as
a version here).

Usage:
    python3 build_archi_report.py [--port 18090] [--out DIR] [--session-log FILE]
"""
import argparse, datetime, glob, html, json, os, re, subprocess, sys, urllib.request

# ---------------------------------------------------------------- MCP client
class Mcp:
    def __init__(self, port):
        self.url = f"http://127.0.0.1:{port}/mcp"
        self.sid = None
        self._id = 0
        self.head = {"Content-Type": "application/json",
                     "Accept": "application/json, text/event-stream",
                     "MCP-Protocol-Version": "2025-06-18"}

    def _post(self, payload):
        h = dict(self.head)
        if self.sid:
            h["Mcp-Session-Id"] = self.sid
        req = urllib.request.Request(self.url, data=json.dumps(payload).encode(),
                                     headers=h, method="POST")
        resp = urllib.request.urlopen(req, timeout=60)
        sid = resp.headers.get("Mcp-Session-Id")
        if sid:
            self.sid = sid
        ct = resp.headers.get("Content-Type", "")
        body = resp.read().decode()
        if "text/event-stream" in ct:
            for line in body.splitlines():
                if line.startswith("data:"):
                    body = line[5:].strip()
                    break
        return json.loads(body) if body.strip() else {}

    def initialize(self):
        self._id += 1
        self._post({"jsonrpc": "2.0", "id": self._id, "method": "initialize",
                    "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                               "clientInfo": {"name": "pdf-report-builder", "version": "1.0"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, name, args=None):
        self._id += 1
        r = self._post({"jsonrpc": "2.0", "id": self._id, "method": "tools/call",
                        "params": {"name": name, "arguments": args or {}}})
        if "error" in r:
            raise RuntimeError(f"{name}: {r['error']}")
        txt = r["result"]["content"][0]["text"]
        return json.loads(txt)

# ---------------------------------------------------------------- helpers
LAYER_ORDER = ["Motivation", "Strategy", "Business", "Application",
               "Technology", "Physical", "Implementation & Migration", "Other"]
LAYER_CLASS = {"Motivation": "Motivation", "Strategy": "Strategy", "Business": "Business",
               "Application": "Application", "Technology": "Technology", "Physical": "Physical",
               "Implementation & Migration": "Implementation", "Other": "Other"}

def esc(s):
    return html.escape(s or "")

def num_key(name):
    """Leading dotted-number token → sortable tuple; else large sentinel."""
    m = re.match(r"\s*(\d+(?:\.\d+)*)", name or "")
    if not m:
        return (999,)
    return tuple(int(x) for x in m.group(1).split("."))

def prop_map(props):
    """properties may be list[{key,value}] or dict."""
    d = {}
    if isinstance(props, list):
        for p in props:
            if isinstance(p, dict) and "key" in p:
                d[p["key"]] = p.get("value", "")
    elif isinstance(props, dict):
        d = dict(props)
    return d

def evidence_mark(props):
    return prop_map(props).get("evidenceMark", "")

def what_line(doc):
    """Extract the 'What:' sentence for compact per-view tables."""
    if not doc:
        return ""
    m = re.search(r"What:\s*(.+)", doc)
    line = m.group(1) if m else doc.split("\n")[0]
    return line.strip()

def doc_html(doc):
    """Render a structured element doc as HTML: bold the section labels."""
    if not doc:
        return "<em>No documentation recorded in the model.</em>"
    out = esc(doc)
    for label in ["Evidence:", "What:", "Responsibility:", "Collaborators:",
                  "Rationale (documented):", "Rationale:", "Part of:"]:
        out = out.replace(esc(label), f"<strong>{esc(label)}</strong>")
    return out.replace("\n", "<br>")

# ---------------------------------------------------------------- harvest
def slugify(name):
    return re.sub(r"[^a-z0-9]+", "-", (name or "model").lower()).strip("-") or "model"

def harvest(m, assets_dir, include_all=False, include_all_rels=False):
    info = m.call("get-model-info")["result"]
    views = m.call("get-views", {"fields": "full", "limit": 500})["result"]
    views.sort(key=lambda v: (num_key(v.get("name")), v.get("name") or ""))

    os.makedirs(assets_dir, exist_ok=True)
    elements = {}          # id -> element dict
    appears = {}           # id -> [view display numbers]
    rel_reg = {}           # (src,type,tgt) -> label
    rel_names = {}          # elementId -> name (fallback resolver for off-view endpoints)
    view_data = []

    for idx, v in enumerate(views, 1):
        vid = v["id"]
        vc = m.call("get-view-contents",
                    {"viewId": vid, "fields": "full",
                     "exclude": ["visualMetadata", "connections"]})["result"]
        # export PNG
        try:
            ex = m.call("export-view", {"viewId": vid, "format": "png", "inline": False,
                                        "scale": 2, "outputDirectory": assets_dir})["result"]
            fig = ex.get("filePath")
        except Exception as e:
            fig = None
            print(f"  ! export failed for {v.get('name')}: {e}", file=sys.stderr)

        disp = re.match(r"\s*([\d.]+)", v.get("name") or "")
        disp = disp.group(1).rstrip(".") if disp else str(idx)

        for el in vc.get("elements", []):
            elements.setdefault(el["id"], el)
            appears.setdefault(el["id"], [])
            if disp not in appears[el["id"]]:
                appears[el["id"]].append(disp)
        for r in vc.get("relationships", []):
            key = (r.get("sourceId"), r.get("type"), r.get("targetId"))
            rel_reg.setdefault(key, r.get("name") or "")

        view_data.append({
            "num": disp, "seq": idx, "name": v.get("name"),
            "vp": v.get("viewpointType"), "folder": v.get("folderPath"),
            "doc": v.get("documentation"),
            "props": prop_map(v.get("properties")),
            "fig": fig,
            "elements": [e["id"] for e in vc.get("elements", [])],
            "notes": [n.get("content", "") for n in vc.get("notes", [])],
        })
        print(f"  · [{idx}/{len(views)}] {v.get('name')}  ({len(vc.get('elements', []))} elems)")

    # Optional: sweep ALL model elements (incl. those not placed on any view)
    if include_all:
        cursor, added = None, 0
        while True:
            args = {"cursor": cursor} if cursor else {"query": "", "fields": "full", "limit": 500}
            resp = m.call("search-elements", args)
            for el in resp.get("result", []):
                if el["id"] not in elements:
                    elements[el["id"]] = el
                    appears.setdefault(el["id"], [])
                    added += 1
            cursor = resp.get("_meta", {}).get("cursor")
            if not cursor:
                break
        print(f"  + full sweep added {added} off-view element(s) → {len(elements)} total")

    # Optional: sweep ALL model relationships (incl. those not drawn on any view)
    if include_all_rels:
        cursor, added = None, 0
        while True:
            args = {"cursor": cursor} if cursor else {"query": "", "fields": "full", "limit": 500}
            resp = m.call("search-relationships", args)
            for r in resp.get("result", []):
                s, t, tg = r.get("sourceId"), r.get("type"), r.get("targetId")
                if r.get("sourceName"):
                    rel_names[s] = r["sourceName"]
                if r.get("targetName"):
                    rel_names[tg] = r["targetName"]
                key = (s, t, tg)
                if key not in rel_reg:
                    rel_reg[key] = r.get("name") or ""
                    added += 1
            cursor = resp.get("_meta", {}).get("cursor")
            if not cursor:
                break
        print(f"  + full sweep added {added} off-view relationship(s) → {len(rel_reg)} total")

    return info, view_data, elements, appears, rel_reg, rel_names

# ---------------------------------------------------------------- render
def render_html(info, views, elements, appears, rel_reg, session_log, out_dir,
                gen_method_override=None, gen_note=None, all_elements=False, rel_names=None,
                all_rels=False):
    rel_names = rel_names or {}
    props = prop_map(info.get("properties"))
    name = info.get("name", "Untitled Model")
    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    # provenance fields (generic scan + repoToArchi.* if present)
    def first(*keys):
        for k in list(props.keys()):
            for want in keys:
                if k.lower() == want or k.lower().endswith("." + want) or k.lower() == "repotoarchi." + want:
                    if props[k]:
                        return props[k]
        return None
    src_version = props.get("repoToArchi.sourceVersion") or first("version", "sourceversion", "release")
    gen_date = props.get("repoToArchi.lastRun") or first("date", "created", "lastrun", "last modified", "modified")
    author = first("author", "owner")
    tooling = None
    if props.get("repoToArchi.tooling.search"):
        tooling = f"{props.get('repoToArchi.tooling.search')} + {props.get('repoToArchi.tooling.callgraph','')}".strip(" +")
    if gen_method_override:
        gen_method = gen_method_override
    elif session_log:
        gen_method = "Prompt-generated (see Appendix C)"
    elif any(k.startswith("repoToArchi") for k in props):
        gen_method = "Prompt-generated (repo-to-archi)"
    else:
        gen_method = "Not recorded"

    ec = info.get("elementCount"); rc = info.get("relationshipCount")
    vc_ = info.get("viewCount"); sc = info.get("specializationCount")
    layer_dist = info.get("layerDistribution", {})
    etype = info.get("elementTypeDistribution", {})
    total_layer = sum(layer_dist.values()) or 1

    css = f"""
:root{{--ink:#1a1d21;--muted:#5b636b;--line:#d9dee3;--bg-alt:#f6f8fa;--accent:#2f5d8a;
--Motivation:#B39DDB;--Strategy:#E7C79A;--Business:#F2E4A6;--Application:#9FD3E8;
--Technology:#A9DCB4;--Physical:#8FC7A0;--Implementation:#F3C1A0;--Other:#C4CBD2;}}
@page{{size:A4;margin:22mm 18mm 20mm 18mm;
  @bottom-left{{content:"{esc(name)}{(' · ' + esc(src_version)) if src_version else ''}";font:8pt sans-serif;color:#8a9099;}}
  @bottom-right{{content:"Page " counter(page) " / " counter(pages);font:8pt sans-serif;color:#8a9099;}}
  @top-right{{content:string(sectitle);font:8pt sans-serif;color:#aab0b8;}}}}
@page:first{{@bottom-left{{content:""}}@bottom-right{{content:""}}@top-right{{content:""}}}}
*{{box-sizing:border-box;}}
body{{font:10.5pt/1.5 Georgia,"Times New Roman",serif;color:var(--ink);margin:0;}}
h1,h2,h3,h4,.sans{{font-family:"Helvetica Neue",Arial,sans-serif;color:var(--accent);line-height:1.2;}}
h2{{string-set:sectitle content();font-size:18pt;border-bottom:2px solid var(--line);padding-bottom:4pt;margin:0 0 4pt;}}
h3{{font-size:13pt;margin:14pt 0 4pt;}}
h4{{font-size:11pt;color:var(--muted);margin:10pt 0 3pt;}}
em{{color:var(--muted);}}
.cover{{height:250mm;display:flex;flex-direction:column;justify-content:center;page-break-after:always;}}
.cover .title{{font-size:34pt;color:var(--accent);}}
.cover .subtitle{{font-size:15pt;color:var(--muted);margin-top:6pt;}}
.cover .kicker{{font:11pt sans-serif;letter-spacing:2px;text-transform:uppercase;color:#9aa0a8;}}
.provenance{{margin-top:26pt;border-left:4px solid var(--accent);padding:10pt 16pt;background:var(--bg-alt);font:10pt sans-serif;}}
.provenance div{{margin:3pt 0;}} .provenance b{{color:var(--ink);}}
section{{page-break-before:always;}}
.metrics{{display:flex;gap:10pt;margin:12pt 0;}}
.metric{{flex:1;background:var(--bg-alt);border:1px solid var(--line);border-radius:6px;padding:10pt;text-align:center;}}
.metric .n{{font:20pt sans-serif;color:var(--accent);font-weight:bold;}}
.metric .l{{font:8.5pt sans-serif;color:var(--muted);text-transform:uppercase;letter-spacing:.5px;}}
table{{width:100%;border-collapse:collapse;font:9pt sans-serif;margin:8pt 0;}}
th{{text-align:left;background:var(--bg-alt);border-bottom:2px solid var(--line);padding:5pt 7pt;}}
td{{border-bottom:1px solid var(--line);padding:5pt 7pt;vertical-align:top;overflow-wrap:break-word;}}
th{{overflow-wrap:break-word;}}
table.fixed{{table-layout:fixed;}}
tr{{page-break-inside:avoid;}} tbody tr:nth-child(even){{background:#fbfcfd;}}
.nm{{font-weight:bold;color:var(--ink);}} .ty{{font-style:italic;color:var(--muted);}}
.mark{{font-size:11pt;}}
.dot{{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:5px;vertical-align:middle;}}
figure{{margin:12pt 0;text-align:center;page-break-inside:avoid;}}
figure img{{max-width:100%;max-height:170mm;border:1px solid var(--line);border-radius:4px;}}
figcaption{{font-style:italic;color:var(--muted);font-size:9.5pt;margin-top:6pt;}}
.badge{{display:inline-block;font:8.5pt sans-serif;background:var(--accent);color:#fff;padding:2pt 8pt;border-radius:10px;}}
.crumb{{color:var(--muted);font:8.5pt sans-serif;margin-left:8pt;}}
.viewnote{{background:#fcfcf7;border:1px solid #ece7cf;border-radius:4px;padding:8pt 10pt;font:9.5pt/1.45 Georgia,serif;margin:8pt 0;white-space:pre-wrap;}}
.toc li{{margin:2pt 0;}} .toc .lvl2{{margin-left:16pt;font-size:9.5pt;color:var(--muted);}}
.cat-item{{padding:6pt 0 6pt 10pt;margin:5pt 0;page-break-inside:avoid;}}
.cat-item{{overflow-wrap:break-word;}}
.cat-item .doc{{margin-top:3pt;font-size:9.5pt;}} .cat-item .xref{{font-size:8.5pt;color:var(--muted);margin-top:3pt;}}
.layer-head{{margin-top:14pt;padding:4pt 8pt;background:var(--bg-alt);border-radius:4px;}}
pre.prompt{{font:8pt/1.4 "SF Mono",Consolas,monospace;background:var(--bg-alt);border:1px solid var(--line);
border-left:4px solid var(--accent);border-radius:4px;padding:10pt 12pt;white-space:pre-wrap;overflow-wrap:break-word;}}
.prompt-src{{font-size:9pt;color:var(--muted);font-style:italic;margin-bottom:6pt;}}
"""
    for lyr, cls in LAYER_CLASS.items():
        css += f".layer-{cls}{{border-left:4px solid var(--{cls});}} .dot.{cls}{{background:var(--{cls});}}\n"

    def layer_dot(lyr):
        cls = LAYER_CLASS.get(lyr, "Other")
        return f'<span class="dot {cls}"></span>'

    P = []  # html parts
    P.append(f"<style>{css}</style>")

    # ---- cover
    P.append(f"""<div class="cover">
      <div class="kicker">Architecture Report</div>
      <div class="title">{esc(name)}</div>
      <div class="subtitle">Model documentation &amp; view catalogue</div>
      <div class="provenance">
        <div><b>Model:</b> {esc(name)}</div>
        {f'<div><b>Source version:</b> {esc(src_version)}</div>' if src_version else ''}
        {f'<div><b>Model generated:</b> {esc(gen_date)}</div>' if gen_date else ''}
        {f'<div><b>Author / owner:</b> {esc(author)}</div>' if author else ''}
        <div><b>Generation method:</b> {esc(gen_method)}</div>
        {f'<div><b>Tooling:</b> {esc(tooling)}</div>' if tooling else ''}
        <div><b>Report generated:</b> {esc(ts)}</div>
        <div><b>Scope:</b> {ec} elements · {rc} relationships · {vc_} views</div>
      </div>
      <div style="margin-top:18pt;font:9pt sans-serif;color:#9aa0a8;">
        Generated from the live Archi model via the Archi MCP Server. Evidence grading where present:
        ● code · ◐ documented · ○ inferred.
      </div>
    </div>""")

    # ---- executive summary
    P.append('<section><h2>Executive summary</h2>')
    if info.get("purpose"):
        P.append(f'<p>{doc_html(info["purpose"])}</p>')
    P.append(f"""<div class="metrics">
      <div class="metric"><div class="n">{ec}</div><div class="l">Elements</div></div>
      <div class="metric"><div class="n">{rc}</div><div class="l">Relationships</div></div>
      <div class="metric"><div class="n">{vc_}</div><div class="l">Views</div></div>
      <div class="metric"><div class="n">{sc}</div><div class="l">Specializations</div></div>
    </div>""")
    P.append('<h3>Layer distribution</h3><table><thead><tr><th>Layer</th><th>Elements</th><th>Share</th></tr></thead><tbody>')
    for lyr in LAYER_ORDER:
        if lyr in layer_dist:
            pct = round(100 * layer_dist[lyr] / total_layer)
            P.append(f'<tr><td>{layer_dot(lyr)}{esc(lyr)}</td><td>{layer_dist[lyr]}</td><td>{pct}%</td></tr>')
    P.append('</tbody></table>')
    P.append('<h3>Element types</h3><table><thead><tr><th>Type</th><th>Count</th></tr></thead><tbody>')
    for t, c in sorted(etype.items(), key=lambda kv: -kv[1]):
        P.append(f'<tr><td class="ty">{esc(t)}</td><td>{c}</td></tr>')
    P.append('</tbody></table></section>')

    # ---- TOC
    P.append('<section><h2>Contents</h2><ol class="toc">')
    for v in views:
        P.append(f'<li>{esc(v["name"])}</li>')
    P.append('<li>Appendix A — Element catalogue</li>')
    P.append('<li>Appendix B — Relationship register</li>')
    if session_log:
        P.append('<li>Appendix C — Model generation record</li>')
    P.append('</ol></section>')

    # ---- view sections
    for v in views:
        P.append('<section class="view">')
        P.append(f'<h2>{esc(v["name"])}</h2>')
        vp = v["vp"] or v["props"].get("repoToArchi.level")
        badge = f'<span class="badge">{esc(vp)}</span>' if vp else ''
        P.append(f'<div>{badge}<span class="crumb">{esc(v["folder"])}</span></div>')
        if v.get("doc"):
            P.append(f'<p>{doc_html(v["doc"])}</p>')
        if v["fig"]:
            rel = os.path.relpath(v["fig"], out_dir)
            P.append(f'<figure><img src="{esc(rel)}"><figcaption>Figure {v["seq"]} — {esc(v["name"])}</figcaption></figure>')
        else:
            P.append('<p><em>Diagram unavailable.</em></p>')
        # elements table
        els = [elements[i] for i in v["elements"] if i in elements]
        if els:
            P.append('<h4>Elements in this view</h4><table class="fixed">'
                     '<colgroup><col style="width:20%"><col style="width:28%">'
                     '<col style="width:14%"><col style="width:38%"></colgroup>'
                     '<thead><tr><th>Name</th><th>Type</th><th>Layer</th><th>Description</th></tr></thead><tbody>')
            for el in els:
                mk = evidence_mark(el.get("properties"))
                P.append(f'<tr><td class="nm">{esc(el.get("name"))} <span class="mark">{mk}</span></td>'
                         f'<td class="ty">{esc(el.get("type"))}</td>'
                         f'<td>{layer_dot(el.get("layer"))}{esc(el.get("layer"))}</td>'
                         f'<td>{esc(what_line(el.get("documentation")))}</td></tr>')
            P.append('</tbody></table>')
        # notes
        for n in v["notes"]:
            if n and n.strip():
                P.append(f'<h4>Diagram annotation</h4><div class="viewnote">{esc(n.strip())}</div>')
        # view properties (skip internal repoToArchi.* noise unless meaningful)
        shown = {k: val for k, val in v["props"].items() if not k.startswith("repoToArchi.level")}
        if shown:
            P.append('<h4>View properties</h4><table><tbody>')
            for k, val in shown.items():
                P.append(f'<tr><td class="ty">{esc(k)}</td><td>{esc(val)}</td></tr>')
            P.append('</tbody></table>')
        P.append('</section>')

    # ---- Appendix A: element catalogue
    P.append('<section><h2>Appendix A — Element catalogue</h2>')
    scope_txt = (f'All {len(elements)} elements in the model, grouped by ArchiMate layer '
                 '(elements not placed on any view are marked accordingly)'
                 if all_elements else
                 f'All {len(elements)} elements appearing across the documented views, grouped by ArchiMate layer')
    P.append(f'<p><em>{scope_txt}. Evidence grade where recorded: ● code · ◐ documented · ○ inferred.</em></p>')
    by_layer = {}
    for eid, el in elements.items():
        by_layer.setdefault(el.get("layer") or "Other", []).append(el)
    for lyr in LAYER_ORDER:
        group = by_layer.get(lyr)
        if not group:
            continue
        cls = LAYER_CLASS.get(lyr, "Other")
        P.append(f'<div class="layer-head sans"><b>{layer_dot(lyr)}{esc(lyr)} layer</b> — {len(group)} elements</div>')
        for el in sorted(group, key=lambda e: (e.get("name") or "").lower()):
            mk = evidence_mark(el.get("properties"))
            spec = el.get("specialization")
            spec_txt = f' · «{esc(spec)}»' if spec and spec not in ("documented", "inferred") else ''
            xref = ", ".join(appears.get(el["id"], []))
            pm = prop_map(el.get("properties"))
            extra = {k: val for k, val in pm.items() if k not in ("evidence", "evidenceMark")}
            prop_txt = ""
            if extra:
                prop_txt = '<div class="xref">' + " · ".join(f"{esc(k)}: {esc(val)}" for k, val in extra.items()) + '</div>'
            P.append(f'<div class="cat-item layer-{cls}">'
                     f'<span class="nm">{esc(el.get("name"))}</span> <span class="mark">{mk}</span> '
                     f'<span class="ty">{esc(el.get("type"))}{spec_txt}</span>'
                     f'<div class="doc">{doc_html(el.get("documentation"))}</div>'
                     f'{prop_txt}'
                     f'<div class="xref">Appears in: {esc(xref) if xref else "— not placed on any view"}</div></div>')
    P.append('</section>')

    # ---- Appendix B: relationship register
    P.append('<section><h2>Appendix B — Relationship register</h2>')
    rel_scope = "in the model" if all_rels else "across the documented views"
    P.append(f'<p><em>{len(rel_reg)} distinct relationships {rel_scope}.</em></p>')
    P.append('<table class="fixed"><colgroup><col style="width:30%"><col style="width:16%">'
             '<col style="width:30%"><col style="width:24%"></colgroup>'
             '<thead><tr><th>Source</th><th>Type</th><th>Target</th><th>Label</th></tr></thead><tbody>')
    def nm(i):
        return elements.get(i, {}).get("name") or rel_names.get(i) or "(external)"
    rows = sorted(rel_reg.items(), key=lambda kv: (nm(kv[0][0]).lower(), nm(kv[0][2]).lower()))
    for (s, t, tg), label in rows:
        P.append(f'<tr><td class="nm">{esc(nm(s))}</td><td class="ty">{esc((t or "").replace("Relationship",""))}</td>'
                 f'<td class="nm">{esc(nm(tg))}</td><td>{esc(label)}</td></tr>')
    P.append('</tbody></table></section>')

    # ---- Appendix C: generation record
    if session_log and os.path.exists(session_log):
        with open(session_log, encoding="utf-8") as f:
            log = f.read()
        note = gen_note or (f'Source: {os.path.basename(session_log)} — the generation record for this model, '
                            'reproduced verbatim.')
        P.append('<section><h2>Appendix C — Model generation prompt</h2>')
        P.append(f'<div class="prompt-src">{esc(note)}</div>')
        P.append(f'<pre class="prompt">{esc(log)}</pre></section>')

    out_html = os.path.join(out_dir, "report.html")
    doc = ('<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">'
           f'<title>{esc(name)} — Architecture Report</title></head><body>'
           + "".join(P) + "</body></html>")
    with open(out_html, "w", encoding="utf-8") as f:
        f.write(doc)
    return out_html

# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18090)
    ap.add_argument("--out", default=None)
    ap.add_argument("--session-log", default=None,
                    help="File to embed verbatim as Appendix C (generating prompt or session log).")
    ap.add_argument("--gen-method", default=None,
                    help="Override the provenance 'Generation method' line.")
    ap.add_argument("--gen-note", default=None,
                    help="Preface sentence shown above the Appendix C block.")
    ap.add_argument("--all-elements", action="store_true",
                    help="Catalogue ALL model elements, including those not placed on any view.")
    ap.add_argument("--all-relationships", action="store_true",
                    help="Register ALL model relationships, including those not drawn on any view.")
    ap.add_argument("--full", action="store_true",
                    help="Shorthand for --all-elements --all-relationships.")
    a = ap.parse_args()
    if a.full:
        a.all_elements = a.all_relationships = True

    m = Mcp(a.port)
    m.initialize()
    model_name = m.call("get-model-info")["result"].get("name", "model")
    out_dir = a.out or os.path.join(os.getcwd(), "archi-report", slugify(model_name))
    assets = os.path.join(out_dir, "assets")
    os.makedirs(assets, exist_ok=True)
    print(f"Model: {model_name}\nOutput: {out_dir}")

    print("Harvesting model over MCP…")
    info, views, elements, appears, rel_reg, rel_names = harvest(
        m, assets, include_all=a.all_elements, include_all_rels=a.all_relationships)
    print(f"Harvested {len(elements)} unique elements, {len(rel_reg)} relationships, {len(views)} views.")

    print("Composing HTML…")
    html_path = render_html(info, views, elements, appears, rel_reg, a.session_log, out_dir,
                            gen_method_override=a.gen_method, gen_note=a.gen_note,
                            all_elements=a.all_elements, rel_names=rel_names,
                            all_rels=a.all_relationships)
    pdf_path = os.path.join(out_dir, "report.pdf")

    print("Rendering PDF with WeasyPrint…")
    subprocess.run(["weasyprint", html_path, pdf_path], check=True)
    print(f"\n✓ PDF: {pdf_path}")

if __name__ == "__main__":
    main()
