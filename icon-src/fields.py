"""Sub-pixel shape masks: model the background colour, then measure how far each
pixel lies along the background->fill colour ramp.  The 0.5 level set is the
geometric edge, which is where the reference renderer put it."""
import numpy as np, cv2, sys
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else "reference.png"
FILLS = {
    "white": np.array([253.4, 253.4, 253.7]),
    "cyan":  np.array([32.0, 240.0, 254.0]),
    "green": np.array([49.0, 234.0, 152.0]),
}


def _load():
    return np.array(Image.open(SRC).convert("RGBA")).astype(np.float64)


def background_model(a, deg=2):
    """Fit the background colour as a polynomial in (x, y) using pixels that are
    far away from every graphic."""
    R, G, B, A = a[:, :, 0], a[:, :, 1], a[:, :, 2], a[:, :, 3]
    white = a[:, :, :3].min(2) > 190
    cyan = (B > 205) & (G > 190) & (R < 140)
    green = (G > 150) & (G - R > 50) & (G - B > 40)
    gfx = cv2.dilate((white | cyan | green).astype(np.uint8), np.ones((61, 61), np.uint8))
    m = (A > 250) & (gfx == 0)
    ys, xs = np.nonzero(m)
    X = xs / 2048.0 - 0.5
    Y = ys / 2048.0 - 0.5
    terms = []
    for i in range(deg + 1):
        for j in range(deg + 1 - i):
            terms.append((X ** i) * (Y ** j))
    M = np.stack(terms, 1)
    coef, *_ = np.linalg.lstsq(M, a[ys, xs, :3], rcond=None)
    return coef, deg


def background_at(coef, deg, shape):
    h, w = shape
    ys, xs = np.mgrid[0:h, 0:w]
    X = xs / 2048.0 - 0.5
    Y = ys / 2048.0 - 0.5
    terms = []
    for i in range(deg + 1):
        for j in range(deg + 1 - i):
            terms.append((X ** i) * (Y ** j))
    M = np.stack([t.ravel() for t in terms], 1)
    bg = (M @ coef).reshape(h, w, 3)
    return bg


def soft_fields(a=None):
    """Return {name: float field 0..1} where 1 == fully the fill colour."""
    a = _load() if a is None else a
    coef, deg = background_model(a)
    bg = background_at(coef, deg, a.shape[:2])
    rgb = a[:, :, :3]
    out = {}
    for name, fg in FILLS.items():
        v = fg - bg
        denom = (v * v).sum(2)
        t = ((rgb - bg) * v).sum(2) / np.maximum(denom, 1e-6)
        # how far off the ramp the pixel is, relative to the ramp length
        proj = bg + t[:, :, None] * v
        off = np.linalg.norm(rgb - proj, axis=2) / np.sqrt(np.maximum(denom, 1e-6))
        t = np.clip(t, 0.0, 1.0)
        t[off > 0.30] = 0.0                 # not on this ramp at all
        out[name] = t
    return out


def hard_masks(a=None, thr=0.5):
    f = soft_fields(a)
    best = np.zeros_like(f["white"])
    who = np.full(f["white"].shape, "", dtype=object)
    for name in FILLS:
        upd = f[name] > best
        best = np.where(upd, f[name], best)
        who[upd] = name
    out = {}
    for name in FILLS:
        out[name] = (best >= thr) & (who == name)
    A = (_load() if a is None else a)[:, :, 3]
    out["bg"] = A > 127.5
    return out


if __name__ == "__main__":
    m = hard_masks()
    for k, v in m.items():
        print(k, v.sum())
