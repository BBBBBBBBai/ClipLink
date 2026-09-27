"""从 icon.svg 生成 Android 矢量图标资源（VectorDrawable）。

用法：python scripts/gen_icons.py

## 为什么改用矢量

几何与配色全部来自仓库根目录的 `icon.svg`（它本身是参考位图的矢量复刻）。
生成的是 VectorDrawable，由系统在运行时按当前屏幕密度栅格化：任何分辨率下
都清晰，APK 里也不必再打包 mdpi~xxxhdpi 五套位图。

## 生成三个文件

| 文件 | 内容 |
|------|------|
| `drawable/ic_launcher_background.xml` | 铺满画布的对角渐变，色标取自 icon.svg 的 `bg` 渐变 |
| `drawable/ic_launcher_foreground.xml` | 白剪贴板 / 青链接 / 绿箭头，缩到安全区内 |
| `drawable/ic_launcher_monochrome.xml` | 同一组图形涂白，供 Android 13+ 主题图标取 alpha 通道 |

三个文件都由 `mipmap-anydpi-v26/ic_launcher*.xml` 引用；minSdk 是 26，
自适应图标在所有支持的设备上都生效，所以不需要任何位图兜底。

## 前景为什么要缩小

icon.svg 里图形（含右上角箭头）的外接矩形占画布约 70%，
而自适应图标的圆形遮罩只显示画布的约 66.7%。原样铺满会把剪贴板顶部的
挂夹和绿色箭头切掉。所以前景层用一个 `<group>` 把图形等比缩小并居中：
外接矩形的最大边缩到画布的 `ART_CIRCLE_RATIO`（62%），比 66.7% 的安全区
再留一点余量，避免不同启动器遮罩直径略有差异时被裁。

62% 与上一版位图资源用的比例完全相同，所以换矢量前后图标的大小和位置不变。

## viewport 为什么是 2048

`android:viewportWidth/Height` 取 2048，与 icon.svg 的 viewBox 一一对应，
pathData 与渐变坐标可以逐字对照，不引入额外的缩放常量。
`android:width/height` 仍是自适应图标标准的 108dp。
"""

import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "icon.svg"
RES = ROOT / "app" / "src" / "main" / "res"

SVG_NS = "http://www.w3.org/2000/svg"

# 与 icon.svg 的 viewBox 一致
VIEWPORT = 2048.0

# 前景图形外接矩形最大边占画布的比例。自适应图标的安全区约 66.7%（圆形遮罩），
# 取 62% 留出余量。
ART_CIRCLE_RATIO = 0.62

# icon.svg 中三个图形层的 id，按绘制顺序（白剪贴板 → 青链接 → 绿箭头）
ART_IDS = ("white", "cyan", "green")

# 主题图标只取 alpha 再按壁纸重新着色，这里填什么颜色不影响结果，用白色最直观
MONO_COLOR = "#FFFFFFFF"

_CMD = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]")
_NUM = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


# --------------------------------------------------------------------------
# pathData 解析：只为了算出图形的几何边界
# --------------------------------------------------------------------------

def _tokens(d: str) -> list:
    """把 pathData 拆成命令字母与数字。分隔符（空格、逗号）直接跳过。"""
    out: list = []
    pos = 0
    while pos < len(d):
        m = _CMD.match(d, pos)
        if m:
            out.append(m.group())
            pos = m.end()
            continue
        m = _NUM.match(d, pos)
        if m:
            out.append(float(m.group()))
            pos = m.end()
            continue
        pos += 1
    return out


def _cubic(p0, p1, p2, p3, n):
    """三次贝塞尔采样（不含起点）。"""
    pts = []
    for k in range(1, n + 1):
        t = k / n
        u = 1.0 - t
        pts.append((
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        ))
    return pts


def _quad(p0, p1, p2, n):
    """二次贝塞尔采样（不含起点）。"""
    pts = []
    for k in range(1, n + 1):
        t = k / n
        u = 1.0 - t
        pts.append((
            u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
            u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
        ))
    return pts


def _arc(p0, rx, ry, phi_deg, large, sweep, p1, n):
    """椭圆弧采样（SVG 端点参数 → 圆心参数）。"""
    if rx == 0 or ry == 0:
        return [p1]
    phi = math.radians(phi_deg)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    rx, ry = abs(rx), abs(ry)

    dx2, dy2 = (p0[0] - p1[0]) / 2.0, (p0[1] - p1[1]) / 2.0
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2

    lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s

    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co

    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (p0[0] + p1[0]) / 2.0
    cy = sin_p * cxp + cos_p * cyp + (p0[1] + p1[1]) / 2.0

    t1 = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    dt = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - t1
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi

    pts = []
    for k in range(1, n + 1):
        th = t1 + dt * k / n
        pts.append((
            cx + rx * math.cos(th) * cos_p - ry * math.sin(th) * sin_p,
            cy + rx * math.cos(th) * sin_p + ry * math.sin(th) * cos_p,
        ))
    return pts


def path_points(d: str, samples: int = 64) -> list:
    """把 pathData 折线化，返回采样点，用于求几何边界。

    曲线按 [samples] 段折线化：画布是 2048 单位，64 段足够把边界误差
    压到远小于 1 单位，对居中和缩放没有可见影响。
    """
    tok = _tokens(d)
    pts: list = []
    i = 0
    cmd = None
    cur = (0.0, 0.0)
    sub = (0.0, 0.0)   # 当前子路径起点，供 Z 回退
    ctrl = None        # 上一段曲线的末控制点，供 S/T 反射

    def take(n: int) -> list:
        nonlocal i
        chunk = tok[i:i + n]
        if len(chunk) < n or any(isinstance(v, str) for v in chunk):
            raise ValueError("pathData 参数不足")
        i += n
        return chunk

    while i < len(tok):
        if isinstance(tok[i], str):
            cmd = tok[i]
            i += 1
        elif cmd is None:
            raise ValueError("pathData 以数字开头")

        rel = cmd.islower()
        c = cmd.upper()

        if c == "Z":
            cur = sub
            ctrl = None
            pts.append(cur)
            cmd = None  # 规范要求 Z 之后必须显式给出命令
            continue

        if c == "M":
            x, y = take(2)
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            sub = cur
            ctrl = None
            pts.append(cur)
            # M 之后跟多组坐标时按 L 处理
            cmd = "l" if rel else "L"
            continue

        if c == "L":
            x, y = take(2)
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            ctrl = None
            pts.append(cur)
        elif c == "H":
            x = take(1)[0]
            cur = (cur[0] + x, cur[1]) if rel else (x, cur[1])
            ctrl = None
            pts.append(cur)
        elif c == "V":
            y = take(1)[0]
            cur = (cur[0], cur[1] + y) if rel else (cur[0], y)
            ctrl = None
            pts.append(cur)
        elif c == "C":
            x1, y1, x2, y2, x, y = take(6)
            if rel:
                x1, y1 = cur[0] + x1, cur[1] + y1
                x2, y2 = cur[0] + x2, cur[1] + y2
                x, y = cur[0] + x, cur[1] + y
            pts.extend(_cubic(cur, (x1, y1), (x2, y2), (x, y), samples))
            ctrl = (x2, y2)
            cur = (x, y)
        elif c == "S":
            x2, y2, x, y = take(4)
            if rel:
                x2, y2 = cur[0] + x2, cur[1] + y2
                x, y = cur[0] + x, cur[1] + y
            x1, y1 = (2 * cur[0] - ctrl[0], 2 * cur[1] - ctrl[1]) if ctrl else cur
            pts.extend(_cubic(cur, (x1, y1), (x2, y2), (x, y), samples))
            ctrl = (x2, y2)
            cur = (x, y)
        elif c == "Q":
            x1, y1, x, y = take(4)
            if rel:
                x1, y1 = cur[0] + x1, cur[1] + y1
                x, y = cur[0] + x, cur[1] + y
            pts.extend(_quad(cur, (x1, y1), (x, y), samples))
            ctrl = (x1, y1)
            cur = (x, y)
        elif c == "T":
            x, y = take(2)
            if rel:
                x, y = cur[0] + x, cur[1] + y
            x1, y1 = (2 * cur[0] - ctrl[0], 2 * cur[1] - ctrl[1]) if ctrl else cur
            pts.extend(_quad(cur, (x1, y1), (x, y), samples))
            ctrl = (x1, y1)
            cur = (x, y)
        elif c == "A":
            rx, ry, phi, large, sweep, x, y = take(7)
            if rel:
                x, y = cur[0] + x, cur[1] + y
            pts.extend(_arc(cur, rx, ry, phi, int(large), int(sweep), (x, y), samples))
            ctrl = None
            cur = (x, y)
        else:
            raise ValueError(f"未支持的命令：{cmd}")

    return pts


# --------------------------------------------------------------------------
# 读 icon.svg
# --------------------------------------------------------------------------

def load_svg(path: Path) -> tuple:
    """返回 (渐变定义, {id: (fill, pathData)})。"""
    root = ET.parse(path).getroot()
    ns = {"svg": SVG_NS}

    grad_el = root.find(".//svg:linearGradient[@id='bg']", ns)
    if grad_el is None:
        raise SystemExit("icon.svg 里找不到 id=bg 的 linearGradient")
    gradient = {
        "x1": float(grad_el.get("x1")),
        "y1": float(grad_el.get("y1")),
        "x2": float(grad_el.get("x2")),
        "y2": float(grad_el.get("y2")),
        "stops": [
            (float(s.get("offset")), s.get("stop-color"))
            for s in grad_el.findall("svg:stop", ns)
        ],
    }

    paths = {}
    for el in root.findall("svg:path", ns):
        pid = el.get("id")
        if pid:
            paths[pid] = (el.get("fill"), el.get("d"))

    missing = [i for i in ART_IDS if i not in paths]
    if missing:
        raise SystemExit(f"icon.svg 缺少图形层：{missing}")
    return gradient, paths


def art_transform(paths: dict) -> tuple:
    """算出让图形落在安全区内的缩放与平移。

    `<group>` 的变换矩阵是「先缩放再平移」（pivot 为 0 时）：
    点 p 映射到 `p * scale + translate`。因此把缩放后的外接矩形居中即可。
    """
    xs, ys = [], []
    for pid in ART_IDS:
        for x, y in path_points(paths[pid][1]):
            xs.append(x)
            ys.append(y)

    x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
    w, h = x1 - x0, y1 - y0

    # 目标：外接矩形的最大边缩到画布的 ART_CIRCLE_RATIO / sqrt(2)。
    # 除以 sqrt(2) 是按外接圆口径约束——圆形遮罩的可见范围是一个圆，
    # 用对角线长度约束才能保证图形在任何遮罩形状下都不出界。
    target = VIEWPORT * ART_CIRCLE_RATIO / math.sqrt(2)
    scale = target / max(w, h)
    tx = (VIEWPORT - w * scale) / 2.0 - x0 * scale
    ty = (VIEWPORT - h * scale) / 2.0 - y0 * scale

    return (x0, y0, x1, y1), (scale, tx, ty)


# --------------------------------------------------------------------------
# 写 VectorDrawable
# --------------------------------------------------------------------------

def fmt(v: float, nd: int = 4) -> str:
    s = f"{v:.{nd}f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def argb(color: str) -> str:
    """#rrggbb → #aarrggbb（VectorDrawable 的渐变与填充色用 8 位更明确）。"""
    c = color.lstrip("#")
    if len(c) == 6:
        c = "FF" + c
    return "#" + c.upper()


def write_xml(path: Path, text: str) -> Path:
    """统一按 LF 落盘：res 目录下其余 XML 都是 LF，避免混入 CRLF。"""
    path.write_text(text, encoding="utf-8", newline="\n")
    return path


def write_background(gradient: dict) -> Path:
    stops = "\n".join(
        f'                <item android:offset="{fmt(off, 4)}" android:color="{argb(col)}" />'
        for off, col in gradient["stops"]
    )
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
    自适应图标的背景层：铺满画布的对角渐变。

    17 个色标与坐标直接取自 icon.svg 的 `bg` 渐变（gradientUnits="userSpaceOnUse"，
    从左上 166.7,196.2 到右下 1880.2,1850.9）。用户看到的是渐变正中间那
    约 66.7% 的圆形区域，两端最深/最亮的色标只在遮罩之外，所以铺满整张画布
    才能让圆形边缘的取色与源图一致。

    由 scripts/gen_icons.py 生成，不要手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="2048"
    android:viewportHeight="2048">
    <path android:pathData="M0,0H2048V2048H0Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="{fmt(gradient['x1'])}"
                android:startY="{fmt(gradient['y1'])}"
                android:endX="{fmt(gradient['x2'])}"
                android:endY="{fmt(gradient['y2'])}">
{stops}
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""
    return write_xml(RES / "drawable" / "ic_launcher_background.xml", xml)


def _art_group(paths: dict, transform: tuple, mono: bool) -> str:
    scale, tx, ty = transform
    body = []
    for pid in ART_IDS:
        fill, d = paths[pid]
        color = MONO_COLOR if mono else argb(fill)
        body.append(
            f'        <path\n'
            f'            android:fillColor="{color}"\n'
            f'            android:fillType="evenOdd"\n'
            f'            android:pathData="{d}" />'
        )
    return (
        f'    <group\n'
        f'        android:scaleX="{fmt(scale, 6)}"\n'
        f'        android:scaleY="{fmt(scale, 6)}"\n'
        f'        android:translateX="{fmt(tx, 3)}"\n'
        f'        android:translateY="{fmt(ty, 3)}">\n'
        + "\n".join(body)
        + "\n    </group>"
    )


def write_foreground(paths: dict, transform: tuple) -> Path:
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
    自适应图标的前景层：白剪贴板 / 青链接 / 绿箭头，背景透明。

    图形本身的外接矩形占画布 63.8% x 70.2%，但外接圆要占 94.8%，
    原样铺满会被圆形遮罩切掉（剪贴板顶部的挂夹与右上角箭头）。
    这里用 <group> 把图形缩到画布的 62% 并居中，落在约 66.7% 的安全区内。
    fillType 必须是 evenOdd，否则链条中间的镂空会被填实。

    由 scripts/gen_icons.py 生成，不要手改。
-->
{_vector_open_tag()}
{_art_group(paths, transform, mono=False)}
</vector>
"""
    return write_xml(RES / "drawable" / "ic_launcher_foreground.xml", xml)


def write_monochrome(paths: dict, transform: tuple) -> Path:
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!--
    Android 13+ 主题图标用的单色层。

    系统只取本层的 alpha 通道，再按壁纸配色重新着色，所以这里填什么颜色都
    一样（用白色最直观）。形状与前景层同源、同一套变换，保证主题图标与
    普通图标完全一致——两套形状不同会显得产品不统一。

    由 scripts/gen_icons.py 生成，不要手改。
-->
{_vector_open_tag()}
{_art_group(paths, transform, mono=True)}
</vector>
"""
    return write_xml(RES / "drawable" / "ic_launcher_monochrome.xml", xml)


def _vector_open_tag() -> str:
    return """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="2048"
    android:viewportHeight="2048">"""


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"找不到源矢量：{SRC}")

    gradient, paths = load_svg(SRC)
    box, transform = art_transform(paths)
    scale, tx, ty = transform
    w, h = box[2] - box[0], box[3] - box[1]

    print(f"图形边界：({fmt(box[0], 1)}, {fmt(box[1], 1)}) - ({fmt(box[2], 1)}, {fmt(box[3], 1)})"
          f"  尺寸 {fmt(w, 1)}x{fmt(h, 1)}  占画布 {w / VIEWPORT * 100:.1f}%")
    print(f"前景变换：scale={fmt(scale, 6)}  translate=({fmt(tx, 2)}, {fmt(ty, 2)})")
    print(f"渐变：{len(gradient['stops'])} 个色标，"
          f"({fmt(gradient['x1'], 1)},{fmt(gradient['y1'], 1)}) → "
          f"({fmt(gradient['x2'], 1)},{fmt(gradient['y2'], 1)})")

    for path in (
        write_background(gradient),
        write_foreground(paths, transform),
        write_monochrome(paths, transform),
    ):
        print(f"已写入 {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
