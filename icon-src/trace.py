"""Trace the icon bitmaps into smooth SVG paths (contour extraction + cubic Bezier fitting)."""
import numpy as np, cv2, sys
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else "reference.png"
SS = 8  # supersample factor for sub-pixel contours


# ---------------------------------------------------------------- curve fitting
def _bez(seg, t):
    p0, c1, c2, p3 = seg
    mt = 1.0 - t
    return (mt ** 3) * p0 + 3 * (mt ** 2) * t * c1 + 3 * mt * (t ** 2) * c2 + (t ** 3) * p3


def _chord_param(pts):
    d = np.zeros(len(pts))
    for i in range(1, len(pts)):
        d[i] = d[i - 1] + np.linalg.norm(pts[i] - pts[i - 1])
    if d[-1] <= 1e-12:
        return np.linspace(0, 1, len(pts))
    return d / d[-1]


def _gen_bezier(pts, u, t1, t2):
    """Schneider least-squares cubic fit with 2-D unit tangents t1 (start) and t2 (end)."""
    n = len(pts)
    b0 = (1 - u) ** 3
    b1 = 3 * (1 - u) ** 2 * u
    b2 = 3 * (1 - u) * u ** 2
    b3 = u ** 3
    p0, p3 = pts[0], pts[-1]
    A1 = np.outer(b1, t1)                       # Nx2
    A2 = np.outer(b2, t2)                       # Nx2
    # c1 = p0 + t1*a1, c2 = p3 + t2*a2  =>  residual target is
    # p - (b0+b1)*p0 - (b2+b3)*p3  ==  a1*A1 + a2*A2
    tmp = pts - (np.outer(b0 + b1, p0) + np.outer(b2 + b3, p3))
    C00 = float((A1 * A1).sum())
    C01 = float((A1 * A2).sum())
    C11 = float((A2 * A2).sum())
    X0 = float((A1 * tmp).sum())
    X1 = float((A2 * tmp).sum())
    det = C00 * C11 - C01 * C01
    if abs(det) < 1e-12:
        a1 = a2 = np.linalg.norm(p3 - p0) / 3.0
    else:
        a1 = (X0 * C11 - X1 * C01) / det
        a2 = (C00 * X1 - C01 * X0) / det
    seg_len = np.linalg.norm(p3 - p0)
    eps = 1e-6 * seg_len
    if a1 < eps or a2 < eps:
        a1 = a2 = seg_len / 3.0
    return np.array([p0, p0 + t1 * a1, p3 + t2 * a2, p3])


def _max_err(pts, u, seg):
    worst, split = 0.0, len(pts) // 2
    for i, p in enumerate(pts):
        d = np.linalg.norm(_bez(seg, u[i]) - p)
        if d > worst:
            worst, split = d, i
    return worst, split


def _reparam(pts, u, seg):
    out = np.empty_like(u)
    for i, p in enumerate(pts):
        t = u[i]
        for _ in range(6):
            d = _bez(seg, t) - p
            d1 = 3 * (1 - t) ** 2 * (seg[1] - seg[0]) + 6 * (1 - t) * t * (seg[2] - seg[1]) + 3 * t ** 2 * (seg[3] - seg[2])
            d2 = 6 * (1 - t) * (seg[2] - 2 * seg[1] + seg[0]) + 6 * t * (seg[3] - 2 * seg[2] + seg[1])
            num = d @ d1
            den = d1 @ d1 + d @ d2
            if abs(den) < 1e-12:
                break
            t -= num / den
            t = min(1.0, max(0.0, t))
        out[i] = t
    # keep monotone
    for i in range(1, len(out)):
        if out[i] <= out[i - 1]:
            out[i] = min(1.0, out[i - 1] + 1e-6)
    return out


def fit_cubic(pts, t1, t2, tol, depth=0):
    """Recursive Schneider fit. t1/t2 are unit tangents pointing *into* the curve
    at the start and *back into* the curve at the end (i.e. c2 = p3 + t2*a2)."""
    if len(pts) < 3:
        dist = np.linalg.norm(pts[-1] - pts[0]) / 3.0
        return [np.array([pts[0], pts[0] + t1 * dist, pts[-1] + t2 * dist, pts[-1]])]
    u = _chord_param(pts)
    seg = _gen_bezier(pts, u, t1, t2)
    err, split = _max_err(pts, u, seg)
    if err < tol:
        return [seg]
    if depth > 32 or split <= 0 or split >= len(pts) - 1:
        return [seg]
    left, right = pts[:split + 1], pts[split:]
    fwd = right[1] - right[0]
    fwd = fwd / (np.linalg.norm(fwd) or 1.0)      # direction of travel at the split
    return (fit_cubic(left, t1, -fwd, tol, depth + 1) +
            fit_cubic(right, fwd, t2, tol, depth + 1))


def _resample(pts, step):
    """Uniformly resample a closed polyline."""
    pts = np.vstack([pts, pts[:1]])
    seg = np.linalg.norm(np.diff(pts, axis=0), axis=1)
    total = seg.sum()
    n = max(8, int(round(total / step)))
    targets = np.linspace(0, total, n, endpoint=False)
    cum = np.concatenate([[0], np.cumsum(seg)])
    out = []
    j = 0
    for t in targets:
        while j < len(seg) - 1 and cum[j + 1] < t:
            j += 1
        f = (t - cum[j]) / (seg[j] or 1.0)
        out.append(pts[j] + f * (pts[j + 1] - pts[j]))
    return np.array(out)


def _corners(pts, win=6, thresh_deg=38.0):
    """Indices where the closed polyline turns sharply (corners of the shape)."""
    n = len(pts)
    ang = np.zeros(n)
    for i in range(n):
        v1 = pts[i] - pts[(i - win) % n]
        v2 = pts[(i + win) % n] - pts[i]
        n1 = np.linalg.norm(v1); n2 = np.linalg.norm(v2)
        if n1 < 1e-9 or n2 < 1e-9:
            continue
        ang[i] = np.degrees(np.arccos(np.clip((v1 @ v2) / (n1 * n2), -1, 1)))
    idx = []
    for i in range(n):
        if ang[i] < thresh_deg:
            continue
        if ang[i] >= max(ang[(i + k) % n] for k in range(-win, win + 1)) - 1e-9:
            if not idx or min((i - idx[-1]) % n, (idx[-1] - i) % n) > win:
                idx.append(i)
    return sorted(idx)


def _smooth_closed(pts, k):
    """Circular moving average over a closed polyline (k = half window)."""
    if k <= 0 or len(pts) < 4 * k:
        return pts
    n = len(pts)
    off = np.arange(-k, k + 1)
    return np.stack([np.mean(pts[(np.arange(n)[:, None] + off) % n, d], axis=1) for d in range(2)], 1)


def _line_ok(pts, line_tol):
    """True if every point lies within line_tol of the chord."""
    p0, p1 = pts[0], pts[-1]
    v = p1 - p0
    L = np.linalg.norm(v)
    if L < 1e-9:
        return False
    n = np.array([-v[1], v[0]]) / L
    return np.abs((pts - p0) @ n).max() <= line_tol


def _line_err(pts):
    """Max perpendicular deviation of pts from the chord, plus the chord unit normal."""
    p0, p1 = pts[0], pts[-1]
    v = p1 - p0
    L = np.linalg.norm(v)
    if L < 1e-9:
        return np.inf, None
    nrm = np.array([-v[1], v[0]]) / L
    return float(np.abs((pts - p0) @ nrm).max()), nrm


def fit_piece(pts, t1, t2, tol, line_tol, min_line=40.0, depth=0):
    """Fit one piece: a straight line when the points are collinear, otherwise one or
    more cubics.  t1/t2 are unit tangents at the ends (t2 pointing back into the piece)."""
    if len(pts) < 3:
        dist = np.linalg.norm(pts[-1] - pts[0]) / 3.0
        return [("C", pts[0], pts[0] + t1 * dist, pts[-1] + t2 * dist, pts[-1])]

    lerr, _ = _line_err(pts)
    if lerr <= line_tol and np.linalg.norm(pts[-1] - pts[0]) >= min_line:
        return [("L", pts[0], pts[-1])]

    u = _chord_param(pts)
    seg = _gen_bezier(pts, u, t1, t2)
    err, split = _max_err(pts, u, seg)
    if err < tol:
        return [("C", seg[0], seg[1], seg[2], seg[3])]
    if depth > 32 or split <= 0 or split >= len(pts) - 1:
        return [("C", seg[0], seg[1], seg[2], seg[3])]

    left, right = pts[:split + 1], pts[split:]
    fwd = right[1] - right[0]
    fwd = fwd / (np.linalg.norm(fwd) or 1.0)
    return (fit_piece(left, t1, -fwd, tol, line_tol, min_line, depth + 1) +
            fit_piece(right, fwd, t2, tol, line_tol, min_line, depth + 1))


def merge_collinear(segs, ang_tol_deg=3.0, dist_tol=0.5):
    """Join neighbouring line segments that describe the same straight edge."""
    if len(segs) < 2:
        return segs
    out = []
    for s in segs:
        if s[0] == "L" and out and out[-1][0] == "L":
            a, b = out[-1][1], s[2]
            v1 = out[-1][2] - out[-1][1]
            v2 = s[2] - s[1]
            n1, n2 = np.linalg.norm(v1), np.linalg.norm(v2)
            if n1 > 1e-9 and n2 > 1e-9:
                ang = np.degrees(np.arccos(np.clip((v1 @ v2) / (n1 * n2), -1, 1)))
                mid = s[1]
                v = b - a
                L = np.linalg.norm(v)
                if ang <= ang_tol_deg and L > 1e-9:
                    nrm = np.array([-v[1], v[0]]) / L
                    if abs((mid - a) @ nrm) <= dist_tol:
                        out[-1] = ("L", a, b)
                        continue
        out.append(s)
    # a merge can wrap around the closed path
    if len(out) > 2 and out[0][0] == "L" and out[-1][0] == "L":
        a, b = out[-1][1], out[0][2]
        v1 = out[-1][2] - out[-1][1]
        v2 = out[0][2] - out[0][1]
        n1, n2 = np.linalg.norm(v1), np.linalg.norm(v2)
        if n1 > 1e-9 and n2 > 1e-9:
            ang = np.degrees(np.arccos(np.clip((v1 @ v2) / (n1 * n2), -1, 1)))
            v = b - a
            L = np.linalg.norm(v)
            if ang <= ang_tol_deg and L > 1e-9:
                nrm = np.array([-v[1], v[0]]) / L
                if abs((out[0][1] - a) @ nrm) <= dist_tol:
                    out = [("L", a, b)] + out[1:-1]
    return out


def trace_closed(contour_px, tol=0.4, step=4.0, smooth=2, line_tol=0.3, min_line=0.0):
    """Trace a closed contour into a list of ('L', p0, p1) / ('C', p0, c1, c2, p1)."""
    pts = _resample(np.asarray(contour_px, float), step)
    if len(pts) < 4:
        return []
    pts = _smooth_closed(pts, smooth)
    loop = np.vstack([pts, pts[:1]])
    t1 = loop[1] - loop[0]; t1 = t1 / (np.linalg.norm(t1) or 1.0)
    t2 = loop[-2] - loop[-1]; t2 = t2 / (np.linalg.norm(t2) or 1.0)
    return merge_collinear(fit_piece(loop, t1, t2, tol, line_tol, min_line))


# ---------------------------------------------------------------- masks
def build_masks():
    import fields
    return fields.hard_masks()


def contours_of(mask, min_area=400):
    big = cv2.resize(mask.astype(np.uint8) * 255, None, fx=SS, fy=SS, interpolation=cv2.INTER_LINEAR)
    _, big = cv2.threshold(big, 127, 255, cv2.THRESH_BINARY)
    cnts, hier = cv2.findContours(big, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    out = []
    for i, c in enumerate(cnts):
        if cv2.contourArea(c) < min_area * SS * SS:
            continue
        pts = c.reshape(-1, 2).astype(float) / SS
        out.append((pts, hier[0][i][3] >= 0))
    return out


def path_from_segs(segs):
    """Serialise traced segments as an SVG path, using relative commands."""
    if not segs:
        return ""
    cur = segs[0][1]
    d = [f"M{cur[0]:.1f} {cur[1]:.1f}"]
    for s in segs:
        if s[0] == "L":
            p = s[2]
            d.append(f"L{p[0]:.1f} {p[1]:.1f}")
        else:
            c1, c2, p = s[2], s[3], s[4]
            d.append(f"C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p[0]:.1f} {p[1]:.1f}")
        cur = s[-1]
    d.append("Z")
    return "".join(d)


def main():
    masks = build_masks()
    tol = float(sys.argv[1]) if len(sys.argv) > 1 else 1.1
    parts = []
    for name in ["bg", "white", "cyan", "green"]:
        cs = contours_of(masks[name], min_area=1500 if name == "bg" else 400)
        ds = []
        for pts, is_hole in cs:
            segs = trace_closed(pts, tol=tol)
            ds.append(path_from_segs(segs))
            print(f"  {name} hole={is_hole} pts={len(pts)} segs={len(segs)}", file=sys.stderr)
        fill = {"bg": "url(#bgGrad)", "white": "#FDFDFD", "cyan": "#20F0FE", "green": "#31EA98"}[name]
        parts.append(f'<path fill="{fill}" fill-rule="evenodd" d="{"".join(ds)}"/>')
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2048 2048" width="2048" height="2048">'
           '<defs><linearGradient id="bgGrad" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="2048" y2="2048">'
           '<stop offset="0" stop-color="#0085f8"/><stop offset="1" stop-color="#6e09fa"/>'
           '</linearGradient></defs>' + "".join(parts) + "</svg>")
    with open("icon_traced.svg", "w", encoding="utf-8") as f:
        f.write(svg)
    print("wrote icon_traced.svg", len(svg), "bytes", file=sys.stderr)


if __name__ == "__main__":
    main()
