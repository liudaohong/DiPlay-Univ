# 自定义主题 vector 图层规范（polygon 定稿草案）

- 状态：**已实现（App + WPF 制作器，2026-11-12）**；`schema` 仍为 1。polygon 已支持填充/描边/数据填充，`path` 仍预留。
- 兼容：**旧版本 App 遇到 `type:"vector"` 会跳过不绘制**（不会像 `shape` 那样兜底画成矩形）。
- 相关：能力评估见 `自定义主题扩展能力评估.md` §11；完整主题规范见 `CUSTOM_THEMES.md`。

---

## 1. 图层类型

```json
{
  "id": "speed_fill",
  "type": "vector",
  "shape": "polygon",
  "z": 2,
  "dst": [120, 300, 400, 260],
  "points": [[0,260],[60,220],[120,240],[200,80],[280,200],[340,150],[400,180],[400,260]],
  "fillRule": "nonzero",
  "color": "#00E5FF",
  "bind": { "source": "speed", "range": [0, 220], "direction": "up", "ease": 120 }
}
```

`type:"vector"`、`shape:"polygon"` 是本期唯一形态；`shape:"path"` 预留给下一期。

## 2. 字段

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `id` | 是 | — | 图层 id，主题内唯一 |
| `type` | 是 | — | 固定 `"vector"` |
| `shape` | 是 | — | `"polygon"`；`"path"` 预留 |
| `z` | 否 | 0 | 层级 |
| `dst` | 建议 | 整屏 | `[x,y,w,h]` 绝对像素；矢量坐标相对这个矩形 |
| `points` | polygon 必填 | — | `[[x,y], …]`，≥3 个点，≤1024 |
| `normalized` | 否 | `false` | `true` 时点坐标用 0..1（相对 dst）；默认用 dst 局部像素 |
| `fillRule` | 否 | `"nonzero"` | `"nonzero"` / `"evenOdd"`；孔洞用 evenOdd |
| `color` | 否 | `#FFFFFFFF` | 纯色填充 |
| `paint` | 否 | — | 渐变填充，优先于 `color`；坐标相对 dst（单位像素） |
| `stroke` | 否 | — | 描边：`{ "color": "#7FE7FF", "width": 2, "cap": "round", "join": "round" }` |
| `bind` | 否 | — | 按车速/转速填充，见 §3 |
| `alpha` | 否 | 1 | 整体透明度，与其他图层一致 |
| `visibleIf` | 否 | — | 与其他图层一致（speed/rpm/appleFullMap） |

`paint` 的 `from` / `to` / `center` / `radius` 与 `points` 一样，**按 dst 局部坐标**解释；这一点和 `brush` 图层的绝对坐标不同，写主题时注意。

## 3. `bind`：按数据填充

```json
"bind": {
  "source": "speed",
  "range": [0, 220],
  "direction": "up",
  "ease": 120,
  "center": [0.5, 0.5],
  "start": -90
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `source` | `"speed"` | `"speed"` 或 `"rpm"` |
| `range` | 必填 | `[min,max]`，`max > min` |
| `direction` | `"up"` | `up` / `down` / `left` / `right` / `radial` / `sweep` |
| `ease` | `120` | 平滑时间（ms）；0 = 不平滑；用于吸收 150 ms 的数据轮询 |
| `center` | `[0.5,0.5]` | 仅 `radial`：相对 dst 的归一化中心 |
| `start` | `-90` | 仅 `sweep`：起始角度（度，0 = 3 点钟方向） |

填充比例：

```
f = clamp((value - min) / (max - min), 0, 1)
value 非有限值（NaN）时按 f = 0 处理
```

填充区域（再被 polygon 裁剪）：

| direction | 填充区域 |
|---|---|
| `up` | 从底边向上：`y + h*(1-f)` 到 `y+h` |
| `down` | 从顶边向下：`y` 到 `y + h*f` |
| `left` | 从左边向右：`x` 到 `x + w*f` |
| `right` | 从右边向左：`x + w*(1-f)` 到 `x+w` |
| `radial` | 以 `center` 为圆心，半径 `f × 到 dst 四角的最大距离` |
| `sweep` | 以 `center` 为圆心，从 `start` 扫 `360° × f` 的扇形 |

`ease` 的平滑：

```
level += (f - level) * (1 - exp(-dt / ease))
```

`dt` 为距上一次刷新的秒数；`ease = 0` 时直接取 `f`。

没有 `bind` 时：整块 polygon 用 `color`/`paint` 填充。

## 4. 渲染语义

1. 用 `points`（或未来 `path.d`）构建 `Path`，平移 `dst.x/dst.y`，按 `fillRule` 设 `FillType`；
2. `canvas.save()` → `clipPath(path)` → 画填充区域（矩形/圆/扇形）→ `restore()`；
3. 描边在 `restore()` 之后画在形状本身上，保证轮廓不被裁掉；
4. 渐变 shader 的坐标按 dst 局部坐标平移后再创建；
5. 旧 App：`type` 不认识 → 不绘制（天然降级，不会出现错误的大色块）。

性能：`clipPath` 走硬件加速，开销低；避免 `saveLayer`（软边遮罩除外）。

## 5. 审计规则（`tools/theme_audit.py`）

新增 `vector` 图层检查：

1. `type:"vector"` 时校验 polygon 字段；App/WPF 已渲染，`path` 仅提示“尚未定义”；
2. `shape` 必须是 `"polygon"`；`"path"` 只 warn“尚未定义”；
3. `points`：数组、≥3 个点、≤1024；每点必须是 2 个有限数字；超过 1024 告警；
4. `normalized:false` 时：
   - 点落在 `[0,w]×[0,h]` 之外 → warn“会被裁剪”；
   - 点落在 `0..1920 × 0..720` 之外 → warn“超出面板”；
5. `normalized:true` 时：每个分量必须在 `0..1`，否则 warn；
6. `fillRule ∈ {nonzero, evenOdd}`；
7. `color` 必须 `#RRGGBB` / `#AARRGGBB`；
8. `paint` 若存在：`kind ∈ {linear, radial, sweep}`，`colors` 非空且颜色合法；`radial` 的 `radius > 0`；
9. `stroke` 若存在：`width ≥ 0`，`cap ∈ {butt, round, square}`，`join ∈ {miter, round, bevel}`，颜色合法；
10. `bind` 若存在：
    - `source ∈ {speed, rpm}`；
    - `range` 是两个数字且 `max > min`；
    - `direction ∈ {up, down, left, right, radial, sweep}`；
    - `ease` 是 `0..2000` 的数字；
    - `radial` 的 `center` 两个分量在 `0..1`；
    - `sweep` 的 `start` 是数字；
11. `dst` 缺失 → warn（会按整屏 1920×720 解释）；底边 >610 的现有安全区规则照常生效。

## 6. 示例

### 6.1 车速从下往上灌满异形（polygon）

```json
{
  "id": "speed_fill",
  "type": "vector",
  "shape": "polygon",
  "z": 2,
  "dst": [120, 300, 400, 260],
  "points": [[0,260],[60,220],[120,240],[200,80],[280,200],[340,150],[400,180],[400,260]],
  "fillRule": "nonzero",
  "paint": { "kind": "linear", "colors": ["#0044FF", "#00E5FF"], "from": [0, 260], "to": [0, 0] },
  "stroke": { "color": "#7FE7FF", "width": 2 },
  "bind": { "source": "speed", "range": [0, 220], "direction": "up", "ease": 120 }
}
```

### 6.2 转速从中心扩散

```json
{
  "id": "rpm_radial",
  "type": "vector",
  "shape": "polygon",
  "z": 3,
  "dst": [900, 260, 280, 280],
  "points": [[140,0],[280,140],[140,280],[0,140]],
  "fillRule": "evenOdd",
  "color": "#FF6B35",
  "bind": { "source": "rpm", "range": [0, 8000], "direction": "radial", "ease": 100, "center": [0.5, 0.5] }
}
```

### 6.3 整块填充（不带 bind）

```json
{ "id": "badge", "type": "vector", "shape": "polygon",
  "dst": [1500, 80, 200, 80],
  "points": [[0,0],[200,0],[160,80],[0,80]],
  "color": "#22D3EE" }
```

## 7. 待定 / 下一期

- `shape:"path"`：SVG `d` 子集，支持曲线、圆弧、多子路径；`d` 长度上限 4096；
- `clip`：让任意图层引用一个 vector 形状做裁剪；
- `stroke` 虚线（`dash`）与渐变描边；
- 归一化坐标下的 `scale` 动画一致性；
- WPF 制作器：已支持 points 文本编辑、归一化、fillRule、纯色/渐变、描边、填充绑定（source/range/direction/ease/center/start）与实时预览；
  拖点编辑留到下一版；
- WPF 的 `sweep` **填充**已实现；`paint.kind:"sweep"` 用 120 片扇形近似（WPF 没有原生锥形渐变），linear/radial 精确。
