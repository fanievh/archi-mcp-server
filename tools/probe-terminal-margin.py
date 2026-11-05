#!/usr/bin/env python3
"""
Terminal-margin probe — measures how far outside an element a connection's terminal
bendpoint must sit before Archi stops drawing the endpoint at the element centre.

WHY THIS EXISTS
---------------
The routing pipeline places terminal bendpoints on a "face line" a fixed number of
pixels outside the element rectangle (EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN,
currently 4). Archi's OrthogonalAnchor classifies the outermost bendpoint into a 5x5
zone grid and returns the element's CENTRE for every combination in which that point
lands inside the figure box. The response is discontinuous: one pixel the wrong side of
the boundary moves the drawn endpoint from the perimeter to the middle of the element.

Archi 5.10 changed OrthogonalAnchor, and a margin of 1 is no longer enough — which is
why the constant is now 4. This script measures the smallest margin that works, by
walking a connection's terminal outward one step at a time and exporting the view at
each step. It is kept as the regression harness: run it against a build to confirm the
shipped margin still clears the threshold on a given machine and display scale.

It answers two questions:
  1. What margin does Archi 5.10 actually need?
  2. Is that margin CONSTANT, or does it grow with container nesting depth? (It probes a
     shallow and a deep connection precisely so the two can be compared.)

METHOD
------
For each probed connection the terminal bendpoint is moved along the terminal segment,
away from its element, toward its own neighbour. That direction is derived from the
path itself, so the script never has to reconstruct absolute element rectangles — which
for nested elements is the very coordinate question under suspicion.

The exported PNGs are the evidence. The pixel-diff column is a HINT for finding the
transition quickly: crossing the threshold makes a whole line segment (face to centre)
appear or disappear, which diffs far larger than the ~delta-pixel shift of moving the
terminal. Read the images before believing the number.

The model is MUTATED while probing and restored from the captured original bendpoints
on the way out, including on error. Nothing is committed to disk by the script; Archi's
own undo stack still holds every step.

Usage:
    python3 tools/probe-terminal-margin.py --view "Target State" --out /tmp/probe
    python3 tools/probe-terminal-margin.py --view <viewId> \
        --connection <viewConnectionId> --connection <viewConnectionId> \
        --margins 1,2,3,4,6,8,12 --port 18090
"""
import argparse
import json
import os
import sys
import urllib.request


# ---------------------------------------------------------------- MCP client
class Mcp:
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
                               "clientInfo": {"name": "terminal-margin-probe",
                                              "version": "1.0"}}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, name, args=None):
        self._id += 1
        r = self._post({"jsonrpc": "2.0", "id": self._id, "method": "tools/call",
                        "params": {"name": name, "arguments": args or {}}})
        if "error" in r:
            raise RuntimeError(f"{name}: {r['error']}")
        return json.loads(r["result"]["content"][0]["text"])


# ---------------------------------------------------------------- geometry
def outward_step(terminal, neighbour):
    """
    Unit step that moves `terminal` away from its element, along the terminal segment.

    Terminal segments are perpendicular to the element face by construction, so the
    direction from the terminal toward its neighbour is the face normal pointing out of
    the element. Returns None when the segment is not axis-aligned or has zero length —
    both mean the path shape cannot tell us which way "outward" is, and a guess here
    would silently probe the wrong axis.
    """
    dx = neighbour["x"] - terminal["x"]
    dy = neighbour["y"] - terminal["y"]
    if dx == 0 and dy == 0:
        return None
    if dx != 0 and dy != 0:
        return None
    if dx != 0:
        return (1 if dx > 0 else -1, 0, abs(dx))
    return (0, 1 if dy > 0 else -1, abs(dy))


def shifted_path(bendpoints, delta):
    """
    Copy of `bendpoints` with both terminals pushed `delta` px further from their
    elements. Returns None when either end cannot be moved that far without reaching
    its own neighbour — moving a terminal past the corner would change the route shape
    and stop measuring the margin.
    """
    if len(bendpoints) < 3:
        return None
    path = [dict(bp) for bp in bendpoints]
    for term_i, nbr_i in ((0, 1), (len(path) - 1, len(path) - 2)):
        step = outward_step(path[term_i], path[nbr_i])
        if step is None:
            return None
        sx, sy, run = step
        if run <= delta + 1:
            return None
        path[term_i]["x"] += sx * delta
        path[term_i]["y"] += sy * delta
    return path


# ---------------------------------------------------------------- selection
def depth_map(tree):
    """
    viewObjectId -> containment depth, from get-view-contents format=tree.

    Walks generically rather than against a fixed node shape: any dict carrying a
    viewObjectId is a node, and anything under a 'children' key is one level deeper.
    """
    depths = {}

    def walk(node, d):
        if isinstance(node, list):
            for n in node:
                walk(n, d)
            return
        if not isinstance(node, dict):
            return
        voi = node.get("viewObjectId")
        if isinstance(voi, str):
            depths[voi] = min(depths.get(voi, d), d)
            d += 1
        for key, value in node.items():
            if key == "children":
                walk(value, d)
            elif isinstance(value, (dict, list)):
                walk(value, d if voi is None else d - 1)

    walk(tree, 0)
    return depths


def probeable(conn, min_run):
    """True when the connection's own path can tell us which way 'outward' is."""
    bps = conn.get("absoluteBendpoints") or []
    if len(bps) < 3:
        return False
    for term_i, nbr_i in ((0, 1), (len(bps) - 1, len(bps) - 2)):
        step = outward_step(bps[term_i], bps[nbr_i])
        if step is None or step[2] <= min_run:
            return False
    return True


def select_connections(connections, depths, count, min_run):
    """
    Pick the shallowest and deepest probeable connections, so a depth-dependent margin
    shows up as a disagreement between them rather than being averaged away.
    """
    scored = []
    for c in connections:
        if not probeable(c, min_run):
            continue
        d = max(depths.get(c.get("sourceViewObjectId"), 0),
                depths.get(c.get("targetViewObjectId"), 0))
        scored.append((d, c))
    if not scored:
        return []
    scored.sort(key=lambda t: t[0])
    picked = [scored[0]]
    if len(scored) > 1 and count > 1:
        picked.append(scored[-1])
    return picked[:count]


# ---------------------------------------------------------------- export
def find_png(result):
    """The export result names its file; locate it without pinning a field name."""
    stack = [result]
    while stack:
        node = stack.pop()
        if isinstance(node, str) and node.lower().endswith(".png"):
            return node
        if isinstance(node, dict):
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)
    return None


def diff_pixels(path_a, path_b):
    """
    Count of differing pixels, or None when it cannot be computed. Different image
    sizes mean the export bounds moved, so the two are not comparable pixel-for-pixel
    and no number is reported rather than a misleading one.
    """
    try:
        from PIL import Image, ImageChops
    except ImportError:
        return None
    try:
        a = Image.open(path_a).convert("RGB")
        b = Image.open(path_b).convert("RGB")
    except Exception:
        return None
    if a.size != b.size:
        return None
    return sum(1 for px in ImageChops.difference(a, b).getdata() if px != (0, 0, 0))


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--view", required=True,
                    help="View id, or a case-insensitive substring of the view name")
    ap.add_argument("--connection", action="append", default=[],
                    help="viewConnectionId to probe (repeatable). Default: auto-select")
    ap.add_argument("--count", type=int, default=2,
                    help="How many connections to auto-select (default 2)")
    ap.add_argument("--margins", default="1,2,3,4,6,8,12",
                    help="Face-line margins to try, assuming the view is currently "
                         "routed at margin 1 (default 1,2,3,4,6,8,12)")
    ap.add_argument("--out", default="probe-out", help="Directory for exported PNGs")
    ap.add_argument("--port", type=int, default=18090)
    ap.add_argument("--token", default=os.environ.get("ARCHI_MCP_TOKEN"),
                    help="Bearer token, if the server requires one")
    ap.add_argument("--scale", type=float, default=1.0)
    args = ap.parse_args()

    margins = [int(m) for m in args.margins.split(",") if m.strip()]
    if 1 not in margins:
        margins.insert(0, 1)
    os.makedirs(args.out, exist_ok=True)

    m = Mcp(args.port, args.token)
    m.initialize()

    info = m.call("get-model-info")
    # approvalMode is published beside the result on some shapes and inside it on others;
    # reading only one of the two would let the probe run silently against proposals.
    if info.get("approvalMode") or (info.get("result") or {}).get("approvalMode"):
        sys.exit("Approval mode is ON. Mutations return proposals instead of being "
                 "applied, so every probe step would export an unchanged view. Turn it "
                 "off in Archi and re-run.")

    views = m.call("get-views", {"limit": 500})["result"]
    match = ([v for v in views if v.get("id") == args.view]
             or [v for v in views if args.view.lower() in (v.get("name") or "").lower()])
    if not match:
        sys.exit(f"No view matching {args.view!r}")
    view = match[0]
    print(f"View: {view.get('name')}  ({view.get('id')})")

    contents = m.call("get-view-contents", {"viewId": view["id"]})
    connections = contents.get("connections") or contents.get("result", {}).get("connections") or []
    tree = m.call("get-view-contents", {"viewId": view["id"], "format": "tree"})
    depths = depth_map(tree)

    max_delta = max(margins) - 1
    if args.connection:
        by_id = {c.get("viewConnectionId"): c for c in connections}
        chosen = []
        for cid in args.connection:
            if cid not in by_id:
                sys.exit(f"Connection {cid} is not on this view")
            c = by_id[cid]
            if not probeable(c, max_delta + 1):
                sys.exit(f"Connection {cid} has no axis-aligned terminal segment long "
                         f"enough to walk {max_delta}px — pick another, or lower --margins")
            d = max(depths.get(c.get("sourceViewObjectId"), 0),
                    depths.get(c.get("targetViewObjectId"), 0))
            chosen.append((d, c))
    else:
        chosen = select_connections(connections, depths, args.count, max_delta + 1)
    if not chosen:
        sys.exit("No connection on this view has a terminal segment long enough to "
                 "probe. Route the view first, or lower --margins.")

    originals = {}
    rows = []
    try:
        for depth, conn in chosen:
            cid = conn["viewConnectionId"]
            originals[cid] = conn.get("absoluteBendpoints") or []
            print(f"\nProbing {cid}  (containment depth {depth}, "
                  f"{len(originals[cid])} bendpoints)")
            for margin in margins:
                path = shifted_path(originals[cid], margin - 1)
                if path is None:
                    print(f"  margin {margin:>3}: skipped — terminal would reach its neighbour")
                    continue
                m.call("update-view-connection",
                       {"viewConnectionId": cid, "absoluteBendpoints": path})
                res = m.call("export-view", {"viewId": view["id"], "format": "png",
                                             "inline": False, "scale": args.scale,
                                             "outputDirectory": args.out})
                src = find_png(res)
                dst = os.path.join(args.out, f"probe_{cid[:8]}_m{margin:02d}.png")
                if src and os.path.exists(src) and os.path.abspath(src) != os.path.abspath(dst):
                    os.replace(src, dst)
                elif src:
                    dst = src
                rows.append((cid, depth, margin, path[0], path[-1], dst))
                print(f"  margin {margin:>3}: source terminal {path[0]}  "
                      f"target terminal {path[-1]}  -> {os.path.basename(dst)}")
    finally:
        for cid, bps in originals.items():
            try:
                m.call("update-view-connection",
                       {"viewConnectionId": cid, "absoluteBendpoints": bps})
                print(f"restored {cid}")
            except Exception as exc:
                print(f"WARNING: could not restore {cid}: {exc}\n"
                      f"         Undo in Archi (Ctrl/Cmd-Z) to recover the original route.",
                      file=sys.stderr)

    print("\n=== pixel change between consecutive margins (HINT — read the PNGs) ===")
    print(f"{'connection':<12}{'depth':>6}{'margin':>8}{'changed px':>13}")
    for cid in dict.fromkeys(r[0] for r in rows):
        series = [r for r in rows if r[0] == cid]
        for prev, cur in zip(series, series[1:]):
            d = diff_pixels(prev[5], cur[5])
            shown = "n/a (size moved or no PIL)" if d is None else f"{d}"
            print(f"{cid[:10]:<12}{cur[1]:>6}{prev[2]:>4}->{cur[2]:<3}{shown:>13}")
    print("\nThe threshold is the smallest margin whose PNG shows the arrowhead ON the\n"
          "element perimeter rather than at its centre. Compare that margin across the\n"
          "two connections: if the deeper one needs more, the margin is depth-dependent\n"
          "and a single constant will not close this.")


if __name__ == "__main__":
    main()
