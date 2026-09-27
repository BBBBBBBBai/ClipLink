# 旧版位图图标（已停用）

这里是从 `res/mipmap-*/` 移出来的位图图标，保留仅作对照，**不参与构建**。

改用矢量之前，图标是由 `scripts/gen_icons.py` 从一张 2048x2048 的参考位图
（一张 2048×2048 的 PNG 参考图，路径见 `icon-src/trace.py` 的 SRC 参数）切出图形、按五个密度
导出 PNG，再由 `mipmap-anydpi-v26/ic_launcher*.xml` 引用的。

现在图标全部是 VectorDrawable，素材直接来自仓库根目录的 `icon.svg`，见
`scripts/gen_icons.py`。这批 PNG 之所以可以整体移走，是因为 `minSdk = 26`：
自适应图标在 API 26+ 上一定生效，而 `anydpi` 限定符的优先级高于任何
密度限定符，所以这些按密度分桶的位图在受支持的设备上一张都不会被加载。

需要回退时，把 `mipmap-*` 目录移回 `app/src/main/res/`，并把
`mipmap-anydpi-v26/ic_launcher*.xml` 里的 `@drawable/ic_launcher_foreground`
与 `@drawable/ic_launcher_monochrome` 改回 `@mipmap/...` 即可。
