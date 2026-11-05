#!/usr/bin/env python3
"""
Builds the terminal-anchor probe matrix view over MCP, from nothing.

WHY THIS EXISTS
---------------
The matrix that measured the Archi 5.10 terminal-anchor defect lived in one person's
local scratch model. A probe fixture that exists on exactly one machine is not a
fixture: it cannot be re-run on a borrowed Windows box, it cannot be handed to anyone
else, and when that machine is reimaged the evidence is gone. This script regenerates
it against any running Archi with the MCP plug-in installed.

WHAT IT BUILDS
--------------
Nine independent probes in a 3x3 grid. Each is one source element, one target element,
and one connection whose two terminal bendpoints are placed by hand at exact offsets:

    columns  = the TARGET terminal's margin: 1 / 4 / 8 px outside the target's top face
    rows     = the target's shape and where the terminal sits on its face:
                 row 1  rounded target, terminal in the CORNER ARC band
                 row 2  rounded target, terminal at MID-FACE
                 row 3  square  target, terminal at MID-FACE

The SOURCE terminal is fixed at 8px in every cell. It is the control: 8 never collapses,
so a cell whose source end also renders wrong indicates something other than the margin
and invalidates that reading rather than confirming it.

HOW TO READ IT
--------------
A connection end is CORRECT when the line meets the box on its edge. It is BROKEN when
the line starts at the middle of the box and crosses the box body to get out — this is
unmistakable, not subtle.

Expected on a machine that reproduces the defect (Archi 5.10, Windows, 125% or 150%):
the whole m=1 column BROKEN at the target end, the m=4 and m=8 columns correct, and every
source end correct. Shape and face position make no difference — that was measured, and
the two rounded rows exist to keep it measured rather than assumed.

    Integer display scales do not reproduce the defect at any margin. At 100% or 200%
    every cell renders correctly and the matrix tells you nothing. The scale must be
    fractional.

THE VIEW IS BUILT WITH connectionRouterType 'manual' ON PURPOSE
--------------------------------------------------------------
A 'manhattan' view discards stored bendpoints and draws its own right-angle path, which
would silently throw away every terminal this script places and leave a matrix that looks
plausible and measures nothing.

VERIFICATION
------------
After building, the script reads the view back and compares each connection's stored
absoluteBendpoints against what it asked for. A cell that did not land is reported and
the exit code is non-zero. Build output is not evidence; the read-back is.

Usage:
    python3 tools/build-terminal-probe-matrix.py
    python3 tools/build-terminal-probe-matrix.py --port 18090 --name "Probe matrix"
    python3 tools/build-terminal-probe-matrix.py --token <bearer>   # if auth is enabled
"""
import argparse
import json
import sys
import urllib.request


# ---------------------------------------------------------------- MCP client
class Mcp:
    """Minimal MCP client. Same transport contract as tools/probe-terminal-margin.py."""

    def __init__(self, port, token=None):
        self.url = f"http://127.0.0.1:{port}/mcp"
        self.sid = None
        self._id = 0
        self.head = {"Content-Type": "application/json",
                     "Accept": "application/json, text/event-stream",
                     "MCP-Protocol-Version": "2025-06-18"}
        if token:
            self.head["Authorization"] = f"Bearer {token}"

    def _post(self, payload):
        h = dict(self.head)
        if self.sid:
            h["Mcp-Session-Id"] = self.sid
        req = urllib.request.Request(self.url, data=json.dumps(payload).encode(),
                                     headers=h, method="POST")
        resp = urllib.request.urlopen(req, timeout=120)
        sid = resp.headers.get("Mcp-Session-Id")
        if sid:
            self.sid = sid
        body = resp.read().decode()
        if "text/event-stream" in resp.headers.get("Content-Type", ""):
            for line in body.splitlines():
                if line.startswith("data:"):
                    body = line[5:].strip()
                    break
        return json.loads(body) if body.strip() else {}

    def initialize(self):
        self._id += 1
        self._post({"jsonrpc": "2.0", "id": self._id, "method": "initialize",
                    "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                               "clientInfo": {"name": "terminal-probe-matrix-builder",
                                              "version": "1.0"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def result(self, name, args=None):
        """A tool's `result` payload. Every response uses the { result, nextSteps, _meta }
        envelope, so a missing `result` is a contract breach and not something to work around."""
        payload = self.call(name, args)
        if "result" not in payload:
            raise RuntimeError(f"{name}: response carried no 'result' — "
                               f"{json.dumps(payload)[:300]}")
        return payload["result"]

    def call(self, name, args=None):
        self._id += 1
        r = self._post({"jsonrpc": "2.0", "id": self._id, "method": "tools/call",
                        "params": {"name": name, "arguments": args or {}}})
        if "error" in r:
            raise RuntimeError(f"{name}: {r['error']}")
        payload = json.loads(r["result"]["content"][0]["text"])
        if isinstance(payload, dict) and payload.get("error"):
            raise RuntimeError(f"{name}: {json.dumps(payload['error'])}")
        return payload


def need(obj, key, where):
    """Read a required response field, or say which call broke the contract and stop.

    Silently tolerating a missing id here would build half a matrix and only surface as a
    confusing failure on the machine that can actually run it."""
    val = obj.get(key)
    if val is None:
        raise RuntimeError(f"{where}: response has no '{key}' — got keys "
                           f"{sorted(obj.keys())}")
    return val


# ---------------------------------------------------------------- matrix spec
# One entry per cell, in the order they are laid out (row-major).
#   key      column id, used as the target element's name so a photograph is self-labelling
#   margin   px the TARGET terminal sits outside the target's TOP face — the variable
#   rounded  target drawn with rounded corners (Capability) vs square (ApplicationComponent)
#   corner   terminal sits in the corner arc band rather than at mid-face
CELLS = [
    {"key": "R-m1-corner", "margin": 1, "rounded": True,  "corner": True},
    {"key": "R-m4-corner", "margin": 4, "rounded": True,  "corner": True},
    {"key": "R-m8-corner", "margin": 8, "rounded": True,  "corner": True},
    {"key": "R-m1-mid",    "margin": 1, "rounded": True,  "corner": False},
    {"key": "R-m4-mid",    "margin": 4, "rounded": True,  "corner": False},
    {"key": "R-m8-mid",    "margin": 8, "rounded": True,  "corner": False},
    {"key": "S-m1-mid",    "margin": 1, "rounded": False, "corner": False},
    {"key": "S-m4-mid",    "margin": 4, "rounded": False, "corner": False},
    {"key": "S-m8-mid",    "margin": 8, "rounded": False, "corner": False},
]

SOURCE_MARGIN = 8       # the control: fixed in every cell, never expected to collapse
ELEM_W, ELEM_H = 120, 55
CELL_W, CELL_H = 340, 300
ORIGIN_X, ORIGIN_Y = 40, 140     # leaves room for the legend note above the grid
V_GAP = 180                      # source top -> target top, so a 125px clear run between them
CORNER_INSET = 10                # inside Archi's 20x20 corner arc
ROUNDED_TYPE = "Capability"
SQUARE_TYPE = "ApplicationComponent"
SQUARE_FIGURE = 1                # rectangle-with-icon: square corners, no component tabs

LEGEND = (
    "TERMINAL ANCHOR PROBE MATRIX\n"
    "\n"
    "Each cell is one connection with both terminal bendpoints placed by hand.\n"
    "Columns vary the TARGET terminal's distance outside the target's top face:\n"
    "left = 1px, middle = 4px, right = 8px. Rows vary the target's shape and where\n"
    "the terminal sits on its face. Every SOURCE terminal is fixed at 8px and is the\n"
    "control — a source end that renders wrong invalidates that cell's reading.\n"
    "\n"
    "CORRECT: the line meets the box on its EDGE.\n"
    "BROKEN:  the line starts in the MIDDLE of the box and crosses the body to get out.\n"
    "\n"
    "This only reproduces on Archi 5.10, Windows, at a FRACTIONAL display scale\n"
    "(125% or 150%). At 100% or 200% every cell renders correctly and means nothing."
)


def cell_geometry(index, cell):
    """Absolute canvas geometry for one cell. Row-major: margin varies across, shape down."""
    row, col = divmod(index, 3)
    x = ORIGIN_X + col * CELL_W
    y = ORIGIN_Y + row * CELL_H
    src = {"x": x, "y": y, "width": ELEM_W, "height": ELEM_H}
    tgt = {"x": x, "y": y + V_GAP, "width": ELEM_W, "height": ELEM_H}
    # Both terminals share an x so the connection is a straight vertical line; that x is
    # what decides whether the TARGET terminal lands in the corner arc or at mid-face.
    term_x = x + (CORNER_INSET if cell["corner"] else ELEM_W // 2)
    return src, tgt, term_x


def main():
    ap = argparse.ArgumentParser(description="Build the terminal-anchor probe matrix over MCP.")
    ap.add_argument("--port", type=int, default=18090, help="MCP server port (default 18090)")
    ap.add_argument("--token", help="bearer token, if the server enforces one")
    ap.add_argument("--name", default="Terminal anchor probe matrix",
                    help="name for the created view")
    args = ap.parse_args()

    m = Mcp(args.port, args.token)
    m.initialize()

    info = m.result("get-model-info")
    model_name = info.get("name", "<unknown>")
    print(f"model: {model_name}")

    # 'manual' is load-bearing: a manhattan view ignores stored bendpoints entirely.
    view = m.result("create-view", {"name": args.name, "connectionRouterType": "manual"})
    view_id = need(view, "id", "create-view")
    router = view.get("connectionRouterType")
    # Assert rather than trust: on a manhattan view every bendpoint below is discarded and
    # the matrix would look plausible while measuring nothing.
    if router and str(router).lower() not in ("manual", "0"):
        raise RuntimeError(f"view was created with connectionRouterType={router!r}; "
                           f"stored bendpoints are ignored unless it is 'manual'")
    print(f"view:  {args.name}  ({view_id})  router={router}")

    m.call("add-note-to-view", {"viewId": view_id, "content": LEGEND,
                                "x": ORIGIN_X, "y": 20, "width": 3 * CELL_W - 20,
                                "height": 100})

    expected = {}
    for i, cell in enumerate(CELLS):
        src_geo, tgt_geo, term_x = cell_geometry(i, cell)
        key = cell["key"]

        src_el = m.result("create-element", {"type": SQUARE_TYPE, "name": f"src {key}",
                                            "force": True})
        tgt_el = m.result("create-element",
                          {"type": ROUNDED_TYPE if cell["rounded"] else SQUARE_TYPE,
                           "name": key, "force": True})
        src_id = need(src_el, "id", f"create-element src {key}")
        tgt_id = need(tgt_el, "id", f"create-element {key}")

        # Association: valid between anything, and drawn as a plain line, so nothing about
        # the relationship type competes with reading where the line meets the box.
        rel = m.result("create-relationship", {"type": "Association",
                                               "sourceId": src_id, "targetId": tgt_id,
                                               "name": key})
        rel_id = need(rel, "id", f"create-relationship {key}")

        src_place = {"viewId": view_id, "elementId": src_id, **src_geo}
        tgt_place = {"viewId": view_id, "elementId": tgt_id, **tgt_geo}
        if not cell["rounded"]:
            tgt_place["figureType"] = SQUARE_FIGURE
        src_place["figureType"] = SQUARE_FIGURE
        svo = m.result("add-to-view", src_place)
        tvo = m.result("add-to-view", tgt_place)
        src_vo = need(need(svo, "viewObject", f"add-to-view src {key}"),
                      "viewObjectId", f"add-to-view src {key}")
        tgt_vo = need(need(tvo, "viewObject", f"add-to-view {key}"),
                      "viewObjectId", f"add-to-view {key}")

        # bp[0] is the source terminal, bp[-1] the target terminal. With bendpoints present
        # each anchor takes its own outermost bendpoint as its reference point, which is
        # exactly the classification this matrix probes.
        bps = [
            {"x": term_x, "y": src_geo["y"] + ELEM_H + SOURCE_MARGIN},
            {"x": term_x, "y": tgt_geo["y"] - cell["margin"]},
        ]
        conn = m.result("add-connection-to-view",
                        {"viewId": view_id, "relationshipId": rel_id,
                         "sourceViewObjectId": src_vo, "targetViewObjectId": tgt_vo,
                         "absoluteBendpoints": bps, "showLabel": False})
        conn_id = need(conn, "viewConnectionId", f"add-connection-to-view {key}")
        expected[key] = {"connectionId": conn_id, "bendpoints": bps,
                         "margin": cell["margin"], "termX": term_x}
        print(f"  built {key:<12} target terminal {cell['margin']}px above y={tgt_geo['y']}"
              f"  at x={term_x}")

    # ---- read back. What the build call returned is not evidence that the model holds it.
    print("\nverifying against the model...")
    contents = m.result("get-view-contents", {"viewId": view_id})
    if contents.get("connectionRouterType") \
            and str(contents["connectionRouterType"]).lower() not in ("manual", "0"):
        print(f"  WARNING: view reports connectionRouterType="
              f"{contents['connectionRouterType']!r}; stored bendpoints are ignored "
              f"unless it is 'manual'")
    stored = {c.get("viewConnectionId"): (c.get("absoluteBendpoints") or [])
              for c in contents.get("connections", [])}

    bad = []
    for key, want in expected.items():
        got = stored.get(want["connectionId"])
        if got is None:
            bad.append(f"{key}: connection {want['connectionId']} not found in the view")
            continue
        if len(got) != len(want["bendpoints"]):
            bad.append(f"{key}: stored {len(got)} bendpoints, asked for "
                       f"{len(want['bendpoints'])} — a router or a fit may have rewritten them")
            continue
        for n, (w, g) in enumerate(zip(want["bendpoints"], got)):
            if int(g.get("x", -10**9)) != w["x"] or int(g.get("y", -10**9)) != w["y"]:
                bad.append(f"{key}: bendpoint {n} stored ({g.get('x')},{g.get('y')}), "
                           f"asked for ({w['x']},{w['y']})")

    if bad:
        print("\nFAILED — the matrix does not hold the geometry it was asked for.")
        for b in bad:
            print("  " + b)
        print("\nDo not photograph this view. A cell whose terminal is not where this script\n"
              "put it measures the thing that moved it, not the anchor.")
        return 1

    print(f"  all {len(expected)} connections hold their terminals exactly.\n")
    print(f"View '{args.name}' is ready.\n")
    print("  Grid: margin 1 / 4 / 8 left to right; rounded-corner, rounded-mid-face,")
    print("        square-mid-face top to bottom. Source terminals fixed at 8px.")
    print("  On Archi 5.10 / Windows / 125% or 150%, expect the LEFT column broken at the")
    print("  target end and everything else correct. On an integer scale expect nothing.")
    print("\n  Nothing is saved to disk — Archi's undo stack still holds every step.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - a CLI should report, not traceback
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)
