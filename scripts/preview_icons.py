"""合成图标预览，验证矢量图标在系统遮罩下的实际效果。

用法：python scripts/preview_icons.py
输出：build-verify/icon-preview.png

读取 `res/drawable/` 下**已生成**的三个 VectorDrawable，按 Android 渲染自适应
图标的方式合成（背景层 + 前景层，再按遮罩裁切），因此校验的是真正会打进
APK 的资源，而不是源矢量 icon.svg。

渲染用无头 Edge：只把 VectorDrawable 翻译回等价 SVG，真正的栅格化交给浏览器，
避免自己写路径填充导致"看起来对、实际不对"。脚本只在 Windows 上跑得通
（依赖 Edge 的安装路径），这与仓库其余脚本一致。
"""

import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "build-verify" / "icon-preview.png"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
AAPT_NS = "{http://schemas.android.com/aapt}"

# 画布与遮罩参数（单位是 VectorDrawable 的 viewport，即 2048）
VIEWPORT = 2048.0
MASK_DIAMETER_RATIO = 0.667  # 系统圆形遮罩约等于画布的 2/3
SQUIRCLE_RADIUS_RATIO = 0.25  # 接近 Pixel 的圆角方形

# 每个面板的导出边长（像素）
PANEL = 432
GAP = 24

EDGE_CANDIDATES = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
)


def attr(el, name, default=None):
    return el.get(ANDROID_NS + name, default)


def css_color(argb: str) -> str:
    """Android 的 #AARRGGBB → CSS 的 #RRGGBBAA。

    两者都是 8 位十六进制但字节序相反：直接搬过去会把颜色读成
    「红=alpha、绿=红……」的错乱色（蓝紫渐变会显示成粉橙）。
    6 位（无 alpha）两边含义一致，原样返回。
    """
    c = argb.lstrip("#")
    if len(c) == 8:
        return f"#{c[2:4]}{c[4:6]}{c[6:8]}{c[0:2]}"
    return argb


def vector_to_svg(path: Path, *, grad_id: str) -> tuple:
    """把 VectorDrawable 翻译成等价的 SVG 片段。

    @return (defs, body)：渐变定义与绘制内容，坐标仍是 viewport 单位。
    """
    root = ET.parse(path).getroot()
    if root.tag != "vector":
        raise SystemExit(f"{path.name} 不是 <vector>")

    defs, body = [], []
    grad_counter = [0]

    def emit(el, group_tf=""):
        for child in el:
            if child.tag == "group":
                sx = float(attr(child, "scaleX", "1"))
                sy = float(attr(child, "scaleY", "1"))
                tx = float(attr(child, "translateX", "0"))
                ty = float(attr(child, "translateY", "0"))
                # VectorDrawable 的 group 矩阵：先缩放再平移（pivot 为 0 时）
                tf = f"translate({tx},{ty}) scale({sx},{sy})"
                combined = f"{group_tf} {tf}".strip()
                emit(child, combined)
            elif child.tag == "path":
                d = attr(child, "pathData")
                fill_type = attr(child, "fillType", "nonZero")
                rule = ' fill-rule="evenodd"' if fill_type == "evenOdd" else ""
                tf = f' transform="{group_tf}"' if group_tf else ""

                grad = child.find(f"{AAPT_NS}attr/gradient")
                if grad is None:
                    fill = css_color(attr(child, "fillColor", "#000000"))
                    body.append(f'<path d="{d}" fill="{fill}"{rule}{tf} />')
                    continue

                gid = f"{grad_id}{grad_counter[0]}"
                grad_counter[0] += 1
                stops = "".join(
                    f'<stop offset="{attr(s, "offset")}" '
                    f'stop-color="{css_color(attr(s, "color"))}" />'
                    for s in grad
                )
                defs.append(
                    f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                    f'x1="{attr(grad, "startX")}" y1="{attr(grad, "startY")}" '
                    f'x2="{attr(grad, "endX")}" y2="{attr(grad, "endY")}">{stops}</linearGradient>'
                )
                body.append(f'<path d="{d}" fill="url(#{gid})"{rule}{tf} />')

    emit(root)
    return defs, body


def panel(defs: list, content: list, mask: str, plate: str = "") -> str:
    """一个面板：托盘 + （可选）底板 + 遮罩内的图标内容。"""
    r = VIEWPORT * MASK_DIAMETER_RATIO / 2
    if mask == "circle":
        shape = f'<circle cx="{VIEWPORT / 2}" cy="{VIEWPORT / 2}" r="{r}" />'
    elif mask == "squircle":
        rad = VIEWPORT * SQUIRCLE_RADIUS_RATIO
        shape = (
            f'<rect x="0" y="0" width="{VIEWPORT}" height="{VIEWPORT}" rx="{rad}" ry="{rad}" />'
        )
    elif mask == "teardrop":
        # 部分 OEM 的水滴形：内切圆 + 左下角补成直角，是裁切最狠的一种遮罩
        c = VIEWPORT / 2
        shape = f'<path d="M0,{c} A{c},{c} 0 1 1 {c},{VIEWPORT} L0,{VIEWPORT} Z" />'
    else:
        raise ValueError(mask)

    tray = VIEWPORT / PANEL * 12  # 托盘留白换算到 viewport 单位
    return f"""<svg x="0" y="0" width="{PANEL}" height="{PANEL}"
     viewBox="{-tray} {-tray} {VIEWPORT + 2 * tray} {VIEWPORT + 2 * tray}">
  <defs>
    {''.join(defs)}
    <clipPath id="clip-{mask}">{shape}</clipPath>
  </defs>
  <rect x="{-tray}" y="{-tray}" width="{VIEWPORT + 2 * tray}" height="{VIEWPORT + 2 * tray}"
        fill="#f0f0f2" />
  <g clip-path="url(#clip-{mask})">
    {plate}
    {''.join(content)}
  </g>
</svg>"""


def compose_svg() -> str:
    bg_defs, bg_body = vector_to_svg(RES / "drawable" / "ic_launcher_background.xml",
                                     grad_id="bgGrad")
    fg_defs, fg_body = vector_to_svg(RES / "drawable" / "ic_launcher_foreground.xml",
                                     grad_id="fgGrad")
    mono_defs, mono_body = vector_to_svg(RES / "drawable" / "ic_launcher_monochrome.xml",
                                         grad_id="monoGrad")

    # 前景与背景共用 defs，合并即可（id 互不重复）
    color = bg_defs + fg_defs
    mono = mono_defs

    # 主题图标：系统只取 monochrome 的 alpha，再按壁纸配色着色后放在单色底板上
    themed_plate = (
        f'<rect x="0" y="0" width="{VIEWPORT}" height="{VIEWPORT}" fill="#3a3f4b" />'
    )
    themed_tint = (
        f'<g fill="#c8d6f5">{_strip_fill(mono_body)}</g>'
    )

    panels = [
        ("圆形遮罩", panel(color, bg_body + fg_body, "circle")),
        ("圆角方形遮罩", panel(color, bg_body + fg_body, "squircle")),
        ("水滴形遮罩", panel(color, bg_body + fg_body, "teardrop")),
        ("主题图标(模拟)", panel(mono, [themed_tint], "circle", themed_plate)),
    ]

    widths = PANEL * len(panels) + GAP * (len(panels) + 1)
    cells = []
    x = GAP
    for _, svg in panels:
        cells.append(f'<g transform="translate({x},{GAP})">{svg}</g>')
        x += PANEL + GAP

    return f"""<svg xmlns="http://www.w3.org/2000/svg"
     width="{widths}" height="{PANEL + 2 * GAP}" viewBox="0 0 {widths} {PANEL + 2 * GAP}">
  <rect width="100%" height="100%" fill="#fafafc" />
  {''.join(cells)}
</svg>"""


def _strip_fill(body: list) -> str:
    """把 monochrome 的 path 去掉自身 fill，交给外层 <g fill> 统一着色。"""
    out = []
    for item in body:
        if item.startswith("<path"):
            item = item.replace('fill="#FFFFFFFF"', "").replace('fill="#ffffff"', "")
        out.append(item)
    return "".join(out)


def render(svg_text: str) -> None:
    edge = next((Path(p) for p in EDGE_CANDIDATES if Path(p).exists()), None)
    if edge is None:
        raise SystemExit("找不到 Edge，无法栅格化预览（见脚本头部说明）")

    with tempfile.TemporaryDirectory() as tmp:
        svg_path = Path(tmp) / "preview.svg"
        svg_path.write_text(svg_text, encoding="utf-8", newline="\n")
        png_path = Path(tmp) / "preview.png"

        subprocess.run(
            [
                str(edge),
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--force-device-scale-factor=1",
                f"--screenshot={png_path}",
                f"--window-size={PANEL * 4 + GAP * 5},{PANEL + 2 * GAP}",
                svg_path.as_uri(),
            ],
            check=True,
            capture_output=True,
        )
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_bytes(png_path.read_bytes())

    print(f"预览已生成：{OUT}")
    print("左起：圆形遮罩 / 圆角方形遮罩 / 水滴形遮罩 / 主题图标(模拟)")


if __name__ == "__main__":
    render(compose_svg())
