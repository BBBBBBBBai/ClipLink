"""Compare a rendered SVG against the reference image."""
import sys
import numpy as np
from PIL import Image

REF = sys.argv[2] if len(sys.argv) > 2 else "reference.png"


def load(p, size=2048):
    im = Image.open(p).convert("RGBA")
    if im.size != (size, size):
        im = im.resize((size, size), Image.LANCZOS)
    return np.array(im).astype(float)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "render.png"
    a = load(REF)
    b = load(out)
    # composite both over black to compare appearance
    def over_black(x):
        al = x[:, :, 3:4] / 255.0
        return x[:, :, :3] * al
    A = over_black(a)
    B = over_black(b)
    diff = np.abs(A - B).max(2)
    print(f"mean abs diff : {diff.mean():.2f}")
    print(f"p99 abs diff  : {np.percentile(diff, 99):.1f}")
    print(f"max abs diff  : {diff.max():.1f}")
    print(f"pixels >30    : {(diff > 30).mean() * 100:.3f}%")
    print(f"pixels >80    : {(diff > 80).mean() * 100:.3f}%")
    Image.fromarray(np.clip(diff * 3, 0, 255).astype(np.uint8)).resize((640, 640)).save("diffmap.png")
    # side-by-side
    sbs = np.concatenate([A, B, np.zeros((2048, 16, 3))], axis=1).astype(np.uint8)
    Image.fromarray(sbs).resize((960, 480)).save("sidebyside.png")


if __name__ == "__main__":
    main()
