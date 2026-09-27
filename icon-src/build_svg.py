"""Generate icon.svg — a vector restoration of the reference app icon.

Shapes come from sub-pixel contour tracing of the reference bitmap (so the
geometry matches the original to about one pixel), and the background gradient
is sampled from the original along the 45-degree diagonal.
"""
import numpy as np, cv2, sys
import trace as T
import fields as F

VB = 2048.0            # viewBox size
STOPS = 16             # gradient stops
FILLS = {
    "white": "#fefefe",
    "cyan":  "#1feefe",
    "green": "#31e998",
}


def gradient():
    """Sample the background along the diagonal; return (stops, p0, p1)."""
    a = F._load()
    R, G, B, A = a[:, :, 0], a[:, :, 1], a[:, :, 2], a[:, :, 3]
    white = a[:, :, :3].min(2) > 190
    cyan = (B > 205) & (G > 190) & (R < 140)
    green = (G > 150) & (G - R > 50) & (G - B > 40)
    gfx = cv2.dilate((white | cyan | green).astype(np.uint8), np.ones((61, 61), np.uint8))
    m = (A > 250) & (gfx == 0)
    ys, xs = np.nonzero(m)
    cols = a[ys, xs, :3]
    d = np.array([np.cos(np.deg2rad(44.0)), np.sin(np.deg2rad(44.0))])
    t = xs * d[0] + ys * d[1]
    t0, t1 = t.min(), t.max()
    q = (t - t0) / (t1 - t0)
    stops = []
    for i in range(STOPS + 1):
        p = i / STOPS
        sel = np.abs(q - p) < 0.5 / STOPS
        c = cols[sel].mean(0) if sel.sum() > 20 else cols.mean(0)
        stops.append(tuple(int(round(v)) for v in c))
    c = VB / 2
    cproj = c * d[0] + c * d[1]          # projection of the centre onto d
    p0 = (c - d[0] * (cproj - t0), c - d[1] * (cproj - t0))
    p1 = (c + d[0] * (t1 - cproj), c + d[1] * (t1 - cproj))
    return stops, p0, p1


def main():
    tol = float(sys.argv[1]) if len(sys.argv) > 1 else 0.4
    masks = F.hard_masks()
    stops, p0, p1 = gradient()
    stop_xml = "".join(
        f'<stop offset="{i / STOPS:.4f}" stop-color="#{r:02x}{g:02x}{b:02x}"/>'
        for i, (r, g, b) in enumerate(stops)
    )
    body = []
    for name in ["bg", "white", "cyan", "green"]:
        fill = "url(#bg)" if name == "bg" else FILLS[name]
        ds = []
        for pts, _hole in T.contours_of(masks[name], min_area=1500 if name == "bg" else 300):
            ds.append(T.path_from_segs(T.trace_closed(pts, tol=tol)))
        body.append(f'  <path id="{name}" fill="{fill}" fill-rule="evenodd" d="{"".join(ds)}"/>')
    svg = "\n".join([
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2048 2048" width="2048" height="2048">',
        "  <defs>",
        f'    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="{p0[0]:.1f}" y1="{p0[1]:.1f}" x2="{p1[0]:.1f}" y2="{p1[1]:.1f}">',
        "      " + stop_xml,
        "    </linearGradient>",
        "  </defs>",
        *body,
        "</svg>",
        "",
    ])
    open("icon_traced.svg", "w", encoding="utf-8").write(svg)
    print(f"wrote icon_traced.svg  {len(svg)} bytes, {len(body)} paths")


if __name__ == "__main__":
    main()
