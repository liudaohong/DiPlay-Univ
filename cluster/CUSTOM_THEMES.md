# DiPlay 取景窗自定义主题规范 v1

本规范面向主题作者和 AI。只使用本文列出的 JSON 字段，不要生成脚本、粒子、HTML 或未定义字段。

## 0. 能力总览（先读这一节）

当前 App 与 WPF 制作器已支持的全部能力：

| 能力 | 字段 / 位置 | 说明 |
| --- | --- | --- |
| 图层 | `layers[].type` | `image` / `brush` / `shape` / `text` / `gauge` / `live` / `vector` / `spectrum` |
| 图片替换 | `image.swap` | 车速/转速条件满足时换另一张图片 |
| 文字模板 | `text.bind.template` | `{speed:0} km/h`、`{range:1}`、`{clock}`、导航/歌曲等 |
| 属性绑定 | 任意图层 `bind`（source/property/from/to/colors/ease） | 数值 → alpha/scale/rotation/x/y/color |
| 显示条件 | `visibleIf` | 车速/转速/续航/总里程/夜间/导航/播放/时间段/Apple 地图/档位/转向/音频 |
| 关键帧 | `animations` | alpha/x/y/scale/rotation，循环/非循环 |
| 异形 | `vector` | polygon 点、纯色/渐变、描边、按车速/转速六方向填充 |
| 事件规则 | `rules` | 单条件或一层 AND/OR/NOT + forMs/回差/冷却 + show/hide/flash/pulse/setText/setImage；speedDelta 急加速 |
| 场景时间线 | WPF 规则窗口 | `t_ms,speed,rpm,range` 播放，实时驱动预览 |
| 场景状态 | `states` | 按条件切换状态，过渡 `visible/alpha/scale/x/y/color/dst`，WPF 可编辑 |
| 开场 | `intro` | 挂载时先强制一个状态 `holdMs`，再平滑过渡到当前状态；只播一次 |
| 单层序列 | `sequence` | 指定图层从 from 滑到 to、停留、滑回 from；只播一次 |
| 旧版兼容 | 所有新增字段 | 旧 App 忽略 bind/vector/rules，仍显示基础层 |

**明确未支持，AI 不要生成**：`path` 曲线、`clip` 引用、虚线描边、`switchState/delay` 动作、gauge 的 dst 覆盖、脚本/粒子/HTML/网络资源。


## 1. 主题包位置

App 的主题目录默认与日志目录同级，**车机上固定为**：

```text
/data/data/com.shihab.diplay.hudtest/files/themes/
```

**车机上主题必须放在这个目录**（App 默认就读这里，不需要改设置），制作器推送时也选它。`/data/data` 是 App 私有目录，adb 需要 root 权限才能写入。制作器会先创建目录，再逐个文件推上去。

设置页的“自定义主题目录”行会直接显示当前路径，右侧“修改目录”可以改；留空或点“恢复默认”时回到上面的 `/data/data/...`。**设置自定义目录后只扫描该目录**，旧内部目录/外部目录不再混入主题列表。

只有在手机上测试、且 adb 没有 root 权限时，才建议临时把 App 的自定义目录改成 adb 可写的外部目录，并让制作器目标与它一致：

```text
/sdcard/Android/data/com.shihab.diplay.hudtest/files/themes/
```

设置页会在路径下方显示当前已识别的自定义主题数量。显示 0 时先确认目录里有可读的 `theme.json`：用制作器推送会自动执行 `chmod -R 777`；如果是手工 `adb push` 到 Android/data，文件属于 shell，Android 11+ 的 App 会读不到，表现就是“目录路径正确但主题列表为空”。

切换取景窗主题是同步操作：仪表盘会先显示黑底白字的“正在切换主题中，请稍候…”，清掉当前主题后再加载目标主题；完成前两指滑动和设置里的主题选择都不会响应。这样用户上传大量主题时也不会切换到一半又被第二次选择覆盖。

一个主题是一个子目录，目录内必须有 `theme.json`，图片和 JSON 放在同一目录。



```
themes/
  my_theme/
    theme.json
    background.png
    logo.jpg
    badge.bmp
```

GIF 作为 `image.src` 时自动按帧循环；全屏 GIF 消耗 CPU/内存，建议只做小图标，或改用 PNG 序列帧。旧版 App 可能只显示 GIF 首帧。

主题只作用于**取景窗视图**，不会改变全屏导航 / 整机镜像主题。

## 2. 根对象



```
{
  "schema": 1,
  "id": "my_theme",
  "name": "我的主题",
  "description": "一句话说明",
  "layers": [],
  "animations": []
}
```



| 字段            | 类型     | 必填 | 说明                              |
| ------------- | ------ | -- | ------------------------------- |
| `schema`      | number | 否  | 固定写 `1`                         |
| `id`          | string | 是  | 主题 id，只能包含字母、数字、`_`、`-`，1–64 字符 |
| `name`        | string | 是  | 下拉框显示名称                         |
| `description` | string | 否  | 设置页说明                           |
| `layers`      | array  | 是  | 图层数组，允许为空                       |
| `animations`  | array  | 否  | 关键帧动画数组                         |
| `rules`       | array  | 否  | 事件规则数组，见 7.1 节                  |
| `states`      | array  | 否  | 场景状态数组，见 7.2 节                  |
| `intro`       | object | 否  | 一次性开场状态，见 7.3 节                |
| `sequence`    | object | 否  | 单层滑入→停留→滑出，见 7.4 节             |

图层按 `z` 从低到高绘制。`z` 相同时按数组顺序。`z` 建议范围 0–150。MCU 车速 / 转速 / 挡位 / 状态硬件层固定在最上面，主题不能覆盖或修改它。

## 3. 图层通用字段



```
{
  "id": "speed",
  "type": "gauge",
  "z": 60,
  "dst": [0, 0, 500, 500],
  "alpha": 1.0,
  "visibleIf": {
    "speed": { "gte": 0, "lte": 220 }
  }
}
```



| 字段          | 类型         | 必填 | 说明                                                      |
| ----------- | ---------- | -- | ------------------------------------------------------- |
| `id`        | string     | 是  | 图层 id，同一主题内唯一，动画用它做 target                              |
| `type`      | string     | 是  | `image` / `brush` / `shape` / `text` / `gauge` / `live` / `vector` / `spectrum` |
| `z`         | number     | 否  | 层级，默认 `数组下标 × 10`                                       |
| `dst`       | number\[4] | 否  | `[x, y, width, height]`，面板坐标，默认 `[0,0,1920,720]`        |
| `alpha`     | number     | 否  | 0–1，默认 1                                                |
| `visibleIf` | object     | 否  | 显示条件，见第 6 节                                             |

面板固定为 **1920×720**，左上角 `(0,0)`，右下角 `(1920,720)`。

`dst` 的第三、第四个值是**宽和高**，不是右下角坐标。

### 3.1 安全区域：底部 110px 在真实仪表盘上不可见

面板坐标虽然是 1920×720，但真实仪表盘只有上方 **1920×610** 可见：

| 区域 | 坐标 | 真实仪表盘 |
| --- | --- | --- |
| 可见区 | `y = 0 ～ 610` | 正常显示 |
| **底部安全区域** | `y = 610 ～ 720`（底部 110px） | **不可见**（被仪表台饰板遮挡） |

**规则：所有需要用户看到的内容（文字、卡片、仪表、图标等）必须完整落在可见区内，即 `y ≥ 0` 且 `y + height ≤ 610`（`dst` 的底边不超过 610）。** 放在底部 110px 的内容在 PC 预览/编辑器里能看到，但装到真实仪表盘上会被饰板遮住，等于没画。

- 背景渐变、整屏底图可以铺满 0～720，底部会被自然裁掉，这不算错误；
- 关键帧的位移、缩放之后，内容的最下沿也不要越过 610；
- 建议重要内容再留 10～20px 内边距（底边 ≤ 590～600），避免贴边。

> 给 AI 的硬性约束：生成的每个 `dst` 都要满足 `x ≥ 0`、`x + width ≤ 1920`、`y ≥ 0`、`y + height ≤ 610`（整屏背景层除外）。

## 4. 各图层类型

### 4.1 image



```
{
  "id": "bg",
  "type": "image",
  "z": 0,
  "src": "background.png",
  "dst": [0, 0, 1920, 720],
  "fit": "cover",
  "alpha": 0.9
}
```



| 字段      | 类型     | 必填 | 说明                                 |
| ------- | ------ | -- | ---------------------------------- |
| `src`   | string | 是  | 主题目录内图片文件名（PNG/JPEG/BMP/GIF）；或 `@cover` 使用当前 CarPlay 专辑封面 |
| `fit`   | string | 否  | `stretch`（默认）/ `cover` / `contain` |
| `alpha` | number | 否  | 0–1                                |

#### 4.1.1 按车速/转速切换图片

图片层可带一个 `swap` 对象：条件成立时，本层改用另一个图片层的图片，但位置、缩放、关键帧仍用本层。适合做“动态 gif”式的多状态图标。

```json
{
  "id": "speed_icon",
  "type": "image",
  "z": 70,
  "dst": [1700, 520, 120, 80],
  "src": "icon_idle.png",
  "swap": {
    "image": "icon_fast",
    "speed": { "gte": 80 },
    "rpm": { "gte": 2500 }
  }
}
```

| 字段           | 说明 |
| ------------ | --- |
| `swap.image` | 条件成立时改用的图片层 `id`（该层必须也是 `image`） |
| `speed.gte`  | 车速 ≥ 该值 |
| `speed.lte`  | 车速 ≤ 该值 |
| `rpm.gte`    | 转速 ≥ 该值 |
| `rpm.lte`    | 转速 ≤ 该值 |

至少填一个阈值；同时填多个时需全部满足。条件不成立时显示自己的 `src`。WPF 图层编辑器“图片”页可直接填这些字段，App 与预览使用同一规则。

### 4.2 brush（渐变 / 纯色背景）



```
{
  "id": "bg_brush",
  "type": "brush",
  "z": 0,
  "dst": [0, 0, 1920, 720],
  "paint": {
    "kind": "linear",
    "colors": ["#0B1E2B", "#020509"],
    "from": [0, 0],
    "to": [0, 720]
  }
}
```



| `paint` 字段 | 类型         | 说明                            |
| ---------- | ---------- | ----------------------------- |
| `kind`     | string     | `linear` / `radial` / `sweep` |
| `colors`   | string\[]  | 1 个或更多颜色，颜色均匀分布               |
| `from`     | number\[2] | linear 起点                     |
| `to`       | number\[2] | linear 终点                     |
| `center`   | number\[2] | radial /sweep 中心              |
| `radius`   | number     | radial 半径                     |

颜色格式只能是 `#RRGGBB` 或 `#AARRGGBB`。纯色建议用 `colors` 单元素，或直接用 `shape` 画满屏矩形。

### 4.3 shape



```
{
  "id": "panel",
  "type": "shape",
  "z": 10,
  "shape": "rect",
  "color": "#331E6FFF",
  "dst": [20, 20, 500, 300]
}
```

`shape`：`rect`（默认）或 `ellipse`。`color` 支持 `#RRGGBB` / `#AARRGGBB`。

### 4.4 text



```
{
  "id": "title",
  "type": "text",
  "z": 20,
  "text": "MY THEME",
  "fontSize": 28,
  "color": "#FF6FE6FF",
  "dst": [40, 560, 600, 40]
}
```

静态文字以 `dst` 左上角为起点绘制。字号单位是面板像素。

#### 4.4.1 数据绑定（可选）

```json
{
  "id": "speed_num",
  "type": "text",
  "dst": [60, 95, 760, 170],
  "text": "-- km/h",
  "fontSize": 104,
  "color": "#FFFFFF",
  "bind": {
    "template": "{speed:0} km/h",
    "fallback": "-- km/h",
    "throttleMs": 150
  }
}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `template` | 必填 | 模板，`{source}` 或 `{source:精度}`（精度 0–6 位小数） |
| `fallback` | 同 `text` | 任一数据源无值时整条文字改用它；旧 App 显示的仍是 `text` |
| `throttleMs` | `0` | 模板重算的最小间隔（毫秒），0 = 每帧；数字建议 150–300，导航/歌曲 500–1000 |

可用数据源：

| 源 | 值 |
| --- | --- |
| `speed` / `rpm` | 数字（km/h、r/min） |
| `odo` / `range` | 数字（km） |
| `clock` | 本地时间 `HH:mm` |
| `night` / `appleFullMap` / `navActive` | `1` / `0` |
| `navRemainingKm` / `navRemainingMin` / `navArrival` / `navDelta` / `navSubtitle` | 导航文字 |
| `songTitle` / `songArtist` | 歌曲两行文字 |
| `gear` / `gearRaw` | 档位统一序号 / 原始码 |
| `gearText` | `P`/`R`/`N`/`D`/`M1`..`M6` |
| `turnLeft` / `turnRight` / `hazard` | `1` / `0` 拨杆请求 |
| `turnLeftBlink` / `turnRightBlink` | `1` / `0` 实际闪烁 |
| `audioLevel` / `audioBass` / `beatPulse` | `0..1` 音乐 PCM |

规则：

- 没有 `bind` 时行为与以前完全一致；旧版 App 忽略 `bind`，只画 `text`。
- 任一占位符没有值（`NaN`、空字符串、未知源）时，**整条文字**改用 `fallback`，不会出现 `NaN` 或半截文字。
- `{speed:0}` 的四舍五入在展开时完成；`throttleMs` 只影响刷新频率，不影响数值精度。

#### 4.4.2 数值 → 外观（属性绑定，阶段 2）

`bind` 也可以出现在图层上（文本以外的类型，或没有 `template` 的文本），把 `speed`/`rpm`/`odo`/`range`/`gear`/`gearRaw`/`turn*`/`audio*` 连续映射到一个视觉属性：

```json
{
  "id": "speed_bar",
  "type": "shape",
  "shape": "rect",
  "dst": [760, 280, 240, 120],
  "color": "#FFB300",
  "bind": {
    "source": "speed",
    "property": "scale",
    "from": [0, 220],
    "to": [0.4, 1.4],
    "ease": 120
  }
}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `source` | 必填 | `speed` / `rpm` / `odo` / `range` / `gear` / `gearRaw` / `turnLeft` / `turnRight` / `hazard` / `turnLeftBlink` / `turnRightBlink` / `audioLevel` / `audioBass` / `beatPulse` |
| `property` | `alpha` | `alpha` / `scale` / `rotation` / `x` / `y` / `color` |
| `from` | 必填 | `[min,max]`，必须 `max>min` |
| `to` | 除 color 外必填 | `[min,max]`，输出值 |
| `colors` | color 必填 | `[起始色, 结束色]`，按 f 插值 |
| `clamp` | `true` | 输入超出 `from` 时夹到 0..1；`false` 可外推（x/y 常用） |
| `ease` | `120` | 毫秒低通；`0` = 立即跟随 |

规则：

- `f = (value - fromMin) / (fromMax - fromMin)`，`output = toMin + (toMax - toMin) * f`。
- `alpha` / `scale` 替换图层基准值；`rotation` 加在 0 上；`x` / `y` 是相对 `dst` 的像素偏移。
- 关键帧仍在 bind 之上叠加：alpha/scale 相乘，rotation/x/y 相加（D2 优先级）。
- `color` 只对 shape/text 生效（image/brush/gauge 忽略）；文本图层用属性 bind 时不能再写 `bind.template`。
- 旧版 App 忽略整个 `bind`，显示静态 `color` / `alpha` / `dst`。
- 示例：`custom-theme-test/bind_demo`；WPF 预览与 `--preview-shot` 已支持。

### 4.5 gauge（数据仪表）



```
{
  "id": "speed",
  "type": "gauge",
  "z": 60,
  "gauge": {
    "data": "speed",
    "center": [255, 285],
    "radius": 185,
    "start": 120,
    "sweep": 300,
    "min": 0,
    "max": 220,
    "thickness": 20,
    "colors": ["#BFF8FF", "#FFFFFF", "#FFD391"],
    "track": "#2ADFF4FF",
    "ticks": 24,
    "labels": [0, 40, 80, 120, 160, 200, 220],
    "labelColor": "#FFFFFFFF",
    "labelSize": 18
  }
}
```



| `gauge` 字段    | 类型         | 说明                          |
| ------------- | ---------- | --------------------------- |
| `data`        | string     | `speed`（km/h）或 `rpm`（r/min） |
| `center`      | number\[2] | 圆心，面板坐标                     |
| `radius`      | number     | 半径                          |
| `start`       | number     | 起始角度，度，0 = 右，90 = 下         |
| `sweep`       | number     | 扫过角度；负数表示逆时针                |
| `min` / `max` | number     | 数据范围，必须 max > min           |
| `thickness`   | number     | 圆环厚度                        |
| `colors`      | string\[]  | 有效圆弧渐变颜色                    |
| `track`       | string     | 未点亮底环颜色                     |
| `ticks`       | integer    | 刻度数量，0 表示不画                 |
| `labels`      | number\[]  | 静态刻度数字，单位与 data 一致          |
| `labelColor`  | string     | 数字颜色                        |
| `labelSize`   | number     | 数字字号                        |

仪表只画弧、刻度和静态数字；**车速 / 转速实时数字由 MCU 绘制**，不要在主题里再画一遍数值。

`labels` 用真实值，例如转速写 `[0,1000,2000,3000,4000,5500]`，不要写 `[0,1,2,3,4,5.5]`。

### 4.6 live（实时槽位）



```
{
  "id": "nav",
  "type": "live",
  "z": 30,
  "slot": "nav",
  "dst": [980, 120, 750, 440],
  "scale": 1
}
```



| 字段      | 类型       | 说明                                                                   |
| ------- | -------- | -------------------------------------------------------------------- |
| `slot`  | string   | `nav` / `card112` / `eta` / `song`                                   |
| `dst`   | number[4] | `x/y` 定位；`nav` 的宽/高是地图视口，`eta/song` 的宽/高就是卡片最终尺寸（内容按比例缩放到这个框），`card112` 忽略宽/高 |
| `scale` | number   | 实时视图额外缩放，默认 `1`，建议 0.3–1.5                                           |



* `nav`：取景窗 111 小地图。`width/height` 真正生效，可任意改视口大小。
* `card112`：Apple 完整地图的 112 导航卡；只有手机端导航时发送该流才存在。固定比例视频流 480×200，**宽/高会被忽略**，只能用 `scale` 缩放。
* `card112` 还有一个存在性前提：仪表内容必须是「Apple 完整地图（FLY_CARD）」，并且导航中确实有转向指令、手机正在发 112 流；导航结束或没有转向卡时，这个窗口会被移除，主题里的 `card112` 图层（含关键帧）也就不可见——这是流不存在，不是关键帧失效。
* `eta`：到达卡。样式与“取景窗主题 → 默认主题”右侧的到达信息卡一致（预计到达 / 剩余 / 还需）。`dst` 的宽高就是卡片最终尺寸；没有导航数据时显示 `--:--` 占位，只要主题声明了该槽位，卡片就始终存在。
* `song`：歌曲卡。样式同样取自默认到达信息卡里的两行歌曲信息（第一行歌词/歌名，第二行歌名）。`dst` 的宽高就是卡片最终尺寸；没有歌曲时显示“暂无歌曲”。
* 自定义主题里**不会**再出现 nav-only 页面的那套自绘胶囊卡/歌曲卡；`eta`/`song` 一律按上面两种默认样式单独绘制，编辑器和车机一致。

实时槽位使用 `dst` 的 `x/y` 定位。全屏地图直接把 `nav` 写成：

```json
{
  "id": "nav",
  "type": "live",
  "slot": "nav",
  "dst": [0, 0, 1920, 720],
  "scale": 1
}
```

省略 `scale` 时按默认值 `1` 处理。`card112` 的 `dst` 宽高只是设计参考；其余槽位的宽高参与实际布局。

实时槽位也参与 `z` 排序：一个 `z=45` 的图片会盖住 `z=30` 的 nav 和 `z=40` 的 112，但被 `z=50` 的 ETA 盖住。自定义主题激活时，内置“112 卡永远最上”的强制 `z=100` 会被主题层的 `z` 覆盖；因此 `song` 写 `z=52` 就一定在 `card112(z=40)` 和 `nav(z=30)` 之上。PC 模拟 MCU 固定 `z=3000`，任何主题层都不能盖住它。

没有对应流 / 数据时，该槽位可能不可见，这是正常的。

### 4.8 vector（异形多边形）

`type:"vector"` 用一组点画任意多边形，支持纯色/渐变填充、描边，以及按车速/转速从某个方向灌满：

```json
{
  "id": "speed_fill",
  "type": "vector",
  "shape": "polygon",
  "dst": [120, 300, 400, 260],
  "points": [[0,260],[60,220],[120,240],[200,80],[280,200],[340,150],[400,180],[400,260]],
  "paint": { "kind": "linear", "colors": ["#0044FF", "#00E5FF"], "from": [0,260], "to": [0,0] },
  "stroke": { "color": "#7FE7FF", "width": 2 },
  "bind": { "source": "speed", "range": [0,220], "direction": "up", "ease": 120 }
}
```

- 点坐标相对 `dst`；`normalized:true` 时用 0..1。
- `paint` / `stroke` 的坐标也是 `dst` 局部坐标（和 `brush` 的绝对坐标不同）。
- `bind.direction`：`up` / `down` / `left` / `right` / `radial` / `sweep`；`radial` 用 `center`，`sweep` 用 `start`。
- 填充区域会被多边形裁剪，描边不会被裁；旧版 App 不绘制 vector，不会出现错误色块。
- 示例主题 `custom-theme-test/vector_demo`。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `shape` | 是 | 固定 `"polygon"`（`path` 未支持） |
| `points` | 是 | `[[x,y], …]`，≥3 且 ≤1024；局部像素或 0..1 |
| `normalized` | 否 | `true` 时 points 用 0..1，默认 false |
| `fillRule` | 否 | `"nonzero"` / `"evenOdd"`；孔洞用 evenOdd |
| `color` | 否 | 纯色 `#RRGGBB` / `#AARRGGBB`；没有 paint 时使用 |
| `paint` | 否 | 渐变，优先于 color；`kind` = linear/radial/sweep，`colors` 数组，坐标局部 |
| `stroke` | 否 | `{ "color", "width", "cap", "join" }`；cap = butt/round/square，join = miter/round/bevel |
| `bind` | 否 | 按数据填充，见下表；没有 bind 时整块填充 |

`bind`（vector 专用，和属性 bind 不同）：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `source` | `"speed"` | `"speed"` / `"rpm"` |
| `range` | 必填 | `[min,max]`，max>min |
| `direction` | `"up"` | `up` / `down` / `left` / `right` / `radial` / `sweep` |
| `ease` | `120` | 平滑毫秒，0 = 立即 |
| `center` | `[0.5,0.5]` | 仅 radial：相对 dst 的归一化中心 |
| `start` | `-90` | 仅 sweep：起始角度（0 = 3 点钟方向） |

填充比例 `f = clamp((value-min)/(max-min),0,1)`，NaN 按 0。`up` 从底边向上、`down` 从顶边向下、`left` 从左、`right` 从右；`radial` 以 center 为圆心、半径 `f × 到 dst 最远角距离`；`sweep` 从 start 扫 `360°×f`。

radial 示例：

```json
{
  "id": "rpm_diamond",
  "type": "vector",
  "shape": "polygon",
  "dst": [1000, 220, 280, 280],
  "points": [[140,0],[280,140],[140,280],[0,140]],
  "fillRule": "evenOdd",
  "color": "#FF6B35",
  "stroke": { "color": "#FFD391", "width": 3 },
  "bind": { "source": "rpm", "range": [0,8000], "direction": "radial", "ease": 100, "center": [0.5,0.5] }
}
```

### 4.7 spectrum（音频频谱）

```json
{
  "id": "bars",
  "type": "spectrum",
  "z": 5,
  "dst": [80, 480, 900, 120],
  "bands": 16,
  "gap": 4,
  "direction": "up",
  "color": "#FF00E5FF"
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `bands` | 16 | 4..32 段 |
| `gap` | 4 | 柱间距 px |
| `direction` | `up` | `up` / `down` |
| `color` | `#FFFFFFFF` | 柱颜色 |

- 数据来自音乐 PCM 的 16 段 Goertzel 频谱，暂停/静音时归零；
- 只随音乐通道变化，导航播报不参与；
- 规则、状态、关键帧、visibleIf 对该层照常生效；
- 旧版 App 不识别 `spectrum`，跳过该层。

## 5. 图层顺序

所有自定义图层和 live 槽位都是同一根 ViewGroup 的真实兄弟 View，因此 `z` 控制真实上下层：



```
z=0     背景
z=30    nav 111
z=35    自定义图片（盖住 nav）
z=40    card112
z=50    eta
z=60    仪表
z=70    装饰
z=2000  主题层上限（实际最大 1900）
z=3000  MCU（固定最上，不属于主题）
```

不要用超过 1900 的 `z`；不要试图覆盖 MCU。

## 6. 显示条件



```
"visibleIf": {
  "speed": { "gte": 100, "lte": 160 },
  "rpm": { "lte": 4000 },
  "appleFullMap": true
}
```



| 条件                        | 类型      | 说明                     |
| ------------------------- | ------- | ---------------------- |
| `speed.gte` / `speed.lte` | number  | 车速范围（km/h）             |
| `rpm.gte` / `rpm.lte`     | number  | 转速范围（r/min）            |
| `appleFullMap`            | boolean | 是否 Apple 完整地图（有 112 卡） |
| `night`                   | boolean | 昼夜：大灯开 = `true`        |
| `range.gte` / `range.lte` | number  | 续航（km）；**无数据时条件不成立** |
| `odo.gte` / `odo.lte`     | number  | 总里程（km）；**无数据时条件不成立** |
| `navActive`               | boolean | 是否有导航数据（转向/ETA）        |
| `songPlaying`             | boolean | 是否有歌名                  |
| `clock.gte` / `clock.lte` | string  | 本地时间 `"HH:mm"`，支持跨午夜（如 22:00–06:00） |
| `gear.gte` / `gear.lte` | number | 档位统一序号：1=P,2=R,3=N,4=D,5=M1..10=M6 |
| `turnLeft` / `turnRight` / `hazard` | boolean | 拨杆/双闪请求 |
| `audioLevel.gte` / `audioLevel.lte` | number | 音乐 PCM 响度 0..1 |
| `audioBass.gte` / `audioBass.lte` | number | 低频包络 0..1 |
| `beatPulse.gte` / `beatPulse.lte` | number | 节拍脉冲 0..1 |

同一个 `visibleIf` 内的条件全部满足才显示。条件不满足时图层或 live 槽位隐藏。

## 7. 关键帧动画



```
"animations": [
  {
    "target": "logo",
    "property": "rotation",
    "duration": 8.0,
    "loop": true,
    "keyframes": [
      [0, 0],
      [1, 360]
    ]
  }
]
```



| 字段          | 类型      | 说明                                         |
| ----------- | ------- | ------------------------------------------ |
| `target`    | string  | 图层 id                                      |
| `property`  | string  | `alpha` / `x` / `y` / `scale` / `rotation` |
| `duration`  | number  | 秒，必须 > 0                                   |
| `loop`      | boolean | 是否循环                                       |
| `keyframes` | array   | `[time, value]`，time 为 0–1                 |

动画作用在图层整体；`x/y` 是相对 `dst` 的偏移，`scale` 以图层中心为基准，`rotation` 单位为度。

所有图层类型和 live 槽位都支持关键帧。`card112` 的 `x/y` 关键帧在 2026-11-11 修复过：此前视图设置位置后又把平移清零，导致编辑器预览会动、车机不动。

多个动画可以指向同一图层；后应用的属性会覆盖前一个。

## 7.1 事件规则（rules，v3）

`rules` 是根对象里的可选数组，让“条件成立”触发 `show/hide/flash/pulse/setText`：

```json
"rules": [
  {
    "id": "overspeed",
    "forMs": 3000,
    "cooldownMs": 30000,
    "when": { "source": "speed", "op": "gt", "value": 120, "release": 110 },
    "actions": [
      { "type": "show", "target": "overspeed_warn" },
      { "type": "flash", "target": "overspeed_warn", "periodMs": 400 }
    ]
  },
  {
    "id": "harsh_accel",
    "forMs": 0,
    "cooldownMs": 10000,
    "when": { "source": "speedDelta", "op": "gt", "value": 20, "windowMs": 1000, "release": 15 },
    "actions": [
      { "type": "show", "target": "accel_warn" },
      { "type": "pulse", "target": "accel_warn", "periodMs": 320, "amplitude": 0.15, "durationMs": 1000 },
      { "type": "setText", "target": "hint", "text": "急加速！1 秒内 +20 km/h 以上" }
    ]
  }
]
```

条件还可以做一层组合（最多 3 个叶子条件）：

```json
"when": {
  "all": [
    { "source": "speed", "op": "gt", "value": 100 },
    { "source": "night", "op": "eq", "value": 1 }
  ]
}
```

`all` = 全部成立，`any` = 任一成立，`not` = 对整个条件取反：

```json
"when": { "not": { "source": "night", "op": "eq", "value": 1 } }
```

| 字段 | 说明 |
| --- | --- |
| `when.source` | 叶子条件：`speed` / `rpm` / `odo` / `range` / `night` / `appleFullMap` / `navActive` / `songPlaying` / `speedDelta` / `gear` / `gearRaw` / `turnLeft` / `turnRight` / `hazard` / `turnLeftBlink` / `turnRightBlink` / `audioLevel` / `audioBass` / `beatPulse` |
| `when.all` / `when.any` | 一层组合数组，1–3 个叶子条件；不能嵌套 group |
| `when.not` | 取反一个叶子或一个 all/any 组合 |
| `when.op` | `gt` / `gte` / `lt` / `lte` / `eq`；布尔源用 `eq` 1/0 |
| `when.value` | 阈值 |
| `when.release` | 可选回差：触发后要回到该值以下/以上才复位（防抖） |
| `when.windowMs` | 仅 `speedDelta`：回看窗口 100–10000ms，默认 1000；`speedDelta=当前车速-窗口前车速` |
| `forMs` | 条件连续成立多久才触发，0–600000 |
| `cooldownMs` | 条件回落后再次成立时，距上次触发至少多久，0–3600000 |
| `actions[].type` | `show` / `hide` / `flash` / `pulse` / `setText` / `setImage` |
| `actions[].target` | 图层 id；必须存在。setText 只能指向 text 图层，setImage 只能指向 image 图层 |
| `actions[].periodMs` | flash/pulse 周期 100–5000ms |
| `actions[].durationMs` | 0 = 条件持续期间一直生效；>0 时动作在触发后多少毫秒结束 |
| `actions[].amplitude` | 仅 pulse：0–0.5，缩放围绕 1 振荡 |
| `actions[].text` | 仅 setText：替换文字，条件结束后恢复 |
| `actions[].src` | 仅 setImage：主题目录内的图片文件名（不能带路径/`..`），条件结束后恢复原图 |

setImage 示例：

```json
{ "type": "setImage", "target": "status_img", "src": "status_alert.png", "durationMs": 3000 }
```

语义：

- 条件成立、持续 `forMs` 后触发一次；条件回落后复位，冷却期内不会再次触发。
- 动作在条件持续期间生效；`hide` 优先于 `show`；`show` 可越过 `visibleIf` 强制显示。
- `flash` 把目标透明度在 1 与 0.25 之间按周期切换；`pulse` 把目标缩放在 `1±amplitude` 之间按周期振荡；`setText` 替换文字。
- 动作 `durationMs` 到期后只结束该动作，规则仍保持触发态；条件回落后所有动作归还。
- `speedDelta` 需要至少一个窗口的历史采样；App 每帧采样，所以正常行驶 1 秒后可用；刚启动/刚切主题时不会误触发。
- 旧版 App 忽略 `rules`，只显示基础图层。
- v3 限制：条件组合只有一层（最多 3 个叶子）；不支持条件内再嵌套 group；没有 switchState/delay。
- 示例：`custom-theme-test/rules_demo`（超速 + setImage 换图 + 急加速 + 夜间高速 AND）。WPF 规则窗口内置场景时间线，可直接输入 `t_ms,speed,rpm,range` 播放。

## 7.2 场景状态（states）

`states` 是根对象的可选数组，按数组顺序匹配第一个 `when`；没有 `when` 的状态作为兜底/base。切换时按 `transitionMs` 插值：

```json
"states": [
  {
    "id": "night",
    "when": { "source": "night", "op": "eq", "value": 1 },
    "transitionMs": 300,
    "overrides": {
      "parking_hint": { "visible": false, "alpha": 0 },
      "driving_badge": { "visible": false, "alpha": 0 },
      "night_badge": { "visible": true, "alpha": 1 }
    }
  },
  {
    "id": "parking",
    "when": { "source": "speed", "op": "lte", "value": 5 },
    "transitionMs": 400,
    "overrides": {
      "parking_hint": { "visible": true, "alpha": 1, "scale": 1, "color": "#FF00B8FF" },
      "driving_badge": { "visible": false, "alpha": 0, "scale": 0.9 }
    }
  },
  { "id": "base", "transitionMs": 300, "overrides": {} }
]
```

| 字段 | 说明 |
| --- | --- |
| `id` | 状态 id，唯一，最多 8 个状态 |
| `when` | 可选；单个条件（source/op/value/release，源同 7.1，但不支持 speedDelta） |
| `transitionMs` | 切换过渡 0–5000ms，默认 300 |
| `overrides` | 图层 id → 覆盖字段；不存在于 overrides 的图层保持基础值 |

覆盖字段：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `visible` | 继承 | `true` / `false`；过渡期间任一端为 true 就保持可见，结束才按目标状态隐藏 |
| `alpha` | 1 | 0–1，乘在图层/规则 alpha 上 |
| `scale` | 1 | 0.5–2，乘在图层/keyframe scale 上 |
| `x` / `y` | 0 | 像素偏移，加在 keyframe x/y 上 |
| `color` | 继承 | `#RRGGBB` / `#AARRGGBB`；覆盖 text/shape/vector 的基础色，过渡时按 ARGB 插值 |
| `dst` | 继承 | `[x,y,w,h]` 面板绝对坐标；替换图层 dst，过渡时插值 x/y/w/h；缺失端使用基础 dst |

语义：

- 状态条件不满足时继续检查下一个；最后一个无 `when` 的状态是兜底。
- 状态变化时从旧状态平滑过渡到新状态；alpha/scale/x/y 线性插值（smoothstep）。
- `visible` 为 false 的图层在过渡完成后隐藏；过渡期间保持可见以避免闪烁。
- `color` 覆盖 text/shape/vector 的基础色；image/gauge 不受影响；过渡时颜色平滑插值。
- `dst` 用面板绝对坐标 `[x,y,w,h]` 直接替换图层布局；过渡时 x/y/w/h 一起平滑插值，缺失的一端使用该图层基础 dst；gauge 不参与。
- 基础层（没有状态覆盖时）仍然由旧版 App 直接显示；旧版 App 忽略 `states`。
- 示例：`custom-theme-test/states_demo`（停车/行驶/夜间/base）。
- v1 限制：状态覆盖支持 visible/alpha/scale/x/y/color/dst；gauge 的 dst 不覆盖。

## 7.3 开场（intro）

`intro` 是一次性开场：Host 挂载时先强制进入指定状态，保持 `holdMs`，然后按正常规则选择状态并过渡。只播一次，不循环。

```json
"intro": { "state": "intro", "holdMs": 800 },
"states": [
  {
    "id": "intro",
    "when": { "source": "odo", "op": "lt", "value": 0 },
    "transitionMs": 0,
    "overrides": {
      "title": { "alpha": 0 },
      "hint": { "alpha": 0 },
      "parking_hint": { "visible": false }
    }
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `state` | 必须是 `states` 中存在的状态 id |
| `holdMs` | 0–5000ms，默认 800；开场状态保持时长 |

语义：

- Host 每次挂载都会播放一次；切换主题/重新进入自定义主题会重新播放。
- 开场期间不参与正常状态匹配；结束后从开场状态过渡到第一个匹配状态，使用目标状态的 `transitionMs`。
- 开场状态建议加一个基本不会成立的 `when`（如 `odo < 0`），避免它成为正常兜底。
- 开场状态同样支持 `visible/alpha/scale/x/y/color/dst`，所以可以做淡入、缩放、滑入、换色。
- 示例：`custom-theme-test/intro_demo`。
- v1 不支持多阶段序列/滑出/粒子；只有“保持 → 过渡到正常状态”。

## 7.4 单层序列（sequence）

`sequence` 对**一个绘制图层**播放一次“滑入 → 停留 → 滑出”：

```json
"sequence": {
  "targets": ["title", "hint"],
  "delayMs": 100,
  "inMs": 300,
  "holdMs": 800,
  "outMs": 300,
  "fromX": -600,
  "fromY": 0,
  "toX": 0,
  "toY": 0,
  "hideAfter": true,
  "repeat": 1
}
```

| 字段 | 说明 |
| --- | --- |
| `targets` | 图层 id 数组；也兼容单个 `target` 字符串 |
| `delayMs` | 挂载后延迟多久开始，0–10000 |
| `inMs` | 从 from 滑到 to 的时长，0–5000 |
| `holdMs` | 在 to 停留，0–10000 |
| `outMs` | 从 to 滑回 from，0–5000 |
| `fromX`/`fromY` | 起始偏移，默认 -600/0 |
| `toX`/`toY` | 停留偏移，默认 0/0 |
| `hideAfter` | 结束（最后一轮滑出后）隐藏这些图层；默认 false |
| `repeat` | 循环次数 1–5，默认 1；每轮 in→hold→out |

语义：

- 同一组偏移同时作用于 `targets` 中所有绘制图层。
- Host 挂载后先等 `delayMs`，再播放一次；切换主题/重新进入会重播。
- 偏移量与状态 `x/y`、关键帧 x/y **相加**，不覆盖布局。
- 同时配置 `intro` 时二者都从挂载时刻开始；建议序列目标与 intro 改动的图层分开。
- 示例：`custom-theme-test/sequence_demo`。
- 这是单段、多目标序列；多段队列/粒子仍不支持。

## 7.5 车况/音频数据源

以下源可用于**图层 visibleIf、规则条件、状态条件、文本模板、属性绑定**：

| source | 类型 | 说明 |
|---|---|---|
| `gear` | 数值 | 1=P,2=R,3=N,4=D,5=M1,6=M2,7=M3,8=M4,9=M5,10=M6 |
| `gearRaw` | 数值 | 原始码：1..6=M1..M6，7=P，8=R，9=N，10=D |
| `gearText` | 文本 | `"P"/"R"/"N"/"D"/"M1".."M6"` |
| `turnLeft` / `turnRight` | 0/1 | 拨杆请求 |
| `hazard` | 0/1 | 双闪请求 |
| `turnLeftBlink` / `turnRightBlink` | 0/1 | 实际闪烁输出 |
| `audioLevel` | 0..1 | 音乐 PCM 响度 |
| `audioBass` | 0..1 | 低频包络 |
| `beatPulse` | 0..1 | 节拍脉冲 |

示例：

```json
{ "source": "gear", "op": "eq", "value": 5 }
{ "source": "turnLeft", "op": "eq", "value": 1 }
```

文本：

```json
{ "text": "{gearText}  {audioLevel:2}" }
```

绑定：

```json
{ "source": "audioBass", "fromMin": 0, "fromMax": 1, "toMin": 0.5, "toMax": 1.2, "property": "scale" }
```

边界：

- `gear` 的 M2 以上仍需实车挂挡确认；读到才显示。
- 转向请求静息值是 1，开是 2；本主题源已转成 0/1。
- 转向闪烁源需要车机轮询 ≤100ms。
- `audio*` 只来自音乐 PCM，不含导航播报/通话。
- 旧版 App 不认识这些源，主题回退到静态值。

## 8. 完整示例

下面这个主题包含渐变、PNG/JPEG/BMP、形状、文字、两个数据仪表、四个实时槽位、条件、动画：



```
{
  "schema": 1,
  "id": "my_theme",
  "name": "我的主题",
  "description": "完整功能示例",
  "layers": [
    {
      "id": "bg",
      "type": "brush",
      "z": 0,
      "dst": [0, 0, 1920, 720],
      "paint": {
        "kind": "radial",
        "center": [960, 330],
        "radius": 1100,
        "colors": ["#0B1E2B", "#061018", "#020509"]
      }
    },
    {
      "id": "bg_texture",
      "type": "image",
      "src": "bg.png",
      "z": 2,
      "dst": [0, 0, 1920, 720],
      "fit": "cover",
      "alpha": 0.18
    },
    {
      "id": "title",
      "type": "text",
      "z": 4,
      "text": "MY COCKPIT",
      "fontSize": 22,
      "color": "#FF6FE6FF",
      "dst": [40, 560, 500, 30]
    },
    {
      "id": "nav",
      "type": "live",
      "slot": "nav",
      "z": 30,
      "dst": [980, 120, 750, 440],
      "scale": 1
    },
    {
      "id": "bmp_badge",
      "type": "image",
      "src": "badge.bmp",
      "z": 35,
      "dst": [820, 40, 400, 110],
      "fit": "stretch",
      "alpha": 0.92
    },
    {
      "id": "card112",
      "type": "live",
      "slot": "card112",
      "z": 40,
      "dst": [24, 24, 480, 200],
      "scale": 1
    },
    {
      "id": "eta",
      "type": "live",
      "slot": "eta",
      "z": 50,
      "dst": [1320, 430, 500, 160]
    },
    {
      "id": "song",
      "type": "live",
      "slot": "song",
      "z": 52,
      "dst": [1320, 200, 500, 160]
    },
    {
      "id": "speed",
      "type": "gauge",
      "z": 60,
      "gauge": {
        "data": "speed",
        "center": [255, 285],
        "radius": 185,
        "start": 120,
        "sweep": 300,
        "min": 0,
        "max": 220,
        "thickness": 20,
        "colors": ["#BFF8FF", "#FFFFFF", "#FFD391"],
        "track": "#2ADFF4FF",
        "ticks": 24,
        "labels": [0, 40, 80, 120, 160, 200, 220],
        "labelColor": "#FFFFFFFF",
        "labelSize": 18
      }
    },
    {
      "id": "rpm",
      "type": "gauge",
      "z": 61,
      "gauge": {
        "data": "rpm",
        "center": [1660, 285],
        "radius": 185,
        "start": 60,
        "sweep": -300,
        "min": 0,
        "max": 5500,
        "thickness": 20,
        "colors": ["#FFD684", "#FFAC4C", "#FF843E"],
        "track": "#2AFFD2CC",
        "ticks": 24,
        "labels": [0, 1000, 2000, 3000, 4000, 5500],
        "labelColor": "#FFFFE8E0",
        "labelSize": 18
      }
    },
    {
      "id": "logo",
      "type": "image",
      "src": "logo.jpg",
      "z": 70,
      "dst": [870, 250, 180, 180],
      "fit": "contain",
      "alpha": 0.9
    },
    {
      "id": "fast_badge",
      "type": "image",
      "src": "logo.jpg",
      "z": 80,
      "dst": [60, 40, 150, 150],
      "fit": "contain",
      "alpha": 0.85,
      "visibleIf": { "speed": { "gte": 100 } }
    },
    {
      "id": "live_pill",
      "type": "shape",
      "shape": "ellipse",
      "z": 85,
      "color": "#2240E0FF",
      "dst": [600, 90, 140, 60]
    },
    {
      "id": "live_text",
      "type": "text",
      "z": 86,
      "text": "LIVE",
      "fontSize": 22,
      "color": "#FFFFFFFF",
      "dst": [625, 105, 120, 32]
    }
  ],
  "animations": [
    {
      "target": "logo",
      "property": "rotation",
      "duration": 8.0,
      "loop": true,
      "keyframes": [[0, 0], [1, 360]]
    },
    {
      "target": "logo",
      "property": "alpha",
      "duration": 2.4,
      "loop": true,
      "keyframes": [[0, 0.35], [0.5, 0.95], [1, 0.35]]
    },
    {
      "target": "live_pill",
      "property": "x",
      "duration": 3.0,
      "loop": true,
      "keyframes": [[0, 0], [0.5, 40], [1, 0]]
    }
  ]
}
```

配套文件：`bg.png`、`badge.bmp`、`logo.jpg`。

## 9. 给 AI 的生成任务模板

把**本规范全文**交给 AI，并附加下面这段任务约束。AI 只应输出 `theme.json`（合法 JSON）和图片文件清单，不要输出解释性代码。

```text
角色：你是 DiPlay 取景窗主题生成器。
目标：根据用户描述，生成一个可直接放进 themes/<id>/ 的主题目录。

硬性规则：
1. 只输出 theme.json + 图片文件清单；theme.json 必须是合法 JSON（双引号、无注释、无尾逗号）。
2. 只使用《DiPlay 取景窗自定义主题规范 v1》列出的字段；不要发明字段。
3. 不要生成脚本、粒子、HTML、网络资源、绝对路径、`..` 路径。
4. 主题只影响取景窗；不要绘制 MCU 的车速/转速/挡位/NORMAL/指示灯。
5. 所有需要看到的内容 dst 必须满足 x≥0、x+w≤1920、y≥0、y+h≤610；整屏背景层除外。
6. 图层 id 唯一，rules 的 target 必须引用存在的图层 id。
7. 至少包含背景 brush；没有图片素材时不要生成 image 层，用 brush/shape/text/vector 代替。
8. 旧版兼容：新增能力必须放在 bind/vector/rules 里，基础 text/color/dst 仍要能看。

可用能力（完整字段见规范正文）：
- 图层：image/brush/shape/text/gauge/live/vector/spectrum；image.src 可用 `@cover` 画当前专辑封面，`.gif` 自动循环；spectrum 是 16 段音频柱
- 文字模板：text.bind { template, fallback, throttleMs }，模板用 {speed:0}/{range:1}/{clock}/{songTitle}/{gearText}/{audioLevel:2} 等
- 属性绑定：任意图层 bind { source(speed/rpm/odo/range/gear/gearRaw/turnLeft/turnRight/hazard/turnLeftBlink/turnRightBlink/audioLevel/audioBass/beatPulse), property(alpha/scale/rotation/x/y/color), from[2], to[2], colors[2], clamp, ease }
- 显示条件：visibleIf { speed/rpm/range/odo:{gte,lte}, gear:{gte,lte}, turnLeft/turnRight/hazard, audioLevel/audioBass/beatPulse:{gte,lte}, night, appleFullMap, navActive, songPlaying, clock:{gte,lte "HH:mm"} }
- 动画：animations[{target, property(alpha/x/y/scale/rotation), duration, loop, keyframes[[t,v]]}]
- 异形：vector { shape:"polygon", points[[x,y]...], normalized, fillRule, color/paint, stroke, bind{source,range,direction,ease,center,start} }
- 规则：rules[{id, forMs, cooldownMs, when(leaf | all/any[1..3 leaf] | not), actions[{type:show/hide/flash/pulse/setText/setImage, target, periodMs, durationMs, amplitude, text, src}]}]；when.source 支持 speed/gear/turn*/audio* 等 7.1 表
- 状态：states[{id, when?(单条件，源同 7.1), transitionMs, overrides{layerId:{visible,alpha,scale,x,y,color,dst}}}]；按数组顺序首个匹配，无 when 为兜底
- 开场：intro{state, holdMs}；挂载时强制该状态 holdMs，再按规则过渡；只播一次
- 序列：sequence{targets[], delayMs, repeat, inMs, holdMs, outMs, fromX/Y, toX/Y, hideAfter}；多图层滑入→停留→滑出；只播一次
- 明确不支持：path、clip、虚线、条件嵌套超过一层、switchState/delay、gauge 的 dst 覆盖、多段队列/粒子。

输出前自检：
[ ] JSON 可解析、id 唯一、颜色 #RRGGBB/#AARRGGBB
[ ] 所有 dst 底边 ≤610（背景除外）
[ ] text.bind 的每个 {source} 都在允许列表内
[ ] vector.points ≥3，bind.range max>min
[ ] rules.target 存在，actions 1..8 个，flash/pulse periodMs 100..5000
[ ] 条件组合最多一层、最多 3 个叶子；not 只包一层
[ ] speedDelta 规则有 windowMs 100..10000；setText 有 text
[ ] setImage 的 src 是主题目录内文件名，且目标图层是 image
[ ] states 最多 8 个、id 唯一、override 图层存在；transitionMs 0..5000
[ ] intro.state 存在于 states；holdMs 0..5000
[ ] sequence.targets/target 存在；delay 0..10000，in/out 0..5000，hold 0..10000，repeat 1..5[ ] 没有未定义字段
```

可直接改的完整骨架（覆盖当前所有能力）：

```json
{
  "schema": 1,
  "id": "ai_theme",
  "name": "AI 生成主题",
  "description": "按本规范生成",
  "layers": [
    {
      "id": "bg",
      "type": "brush",
      "z": 0,
      "dst": [0, 0, 1920, 720],
      "paint": {
        "kind": "linear",
        "colors": ["#0B1220", "#16283D"],
        "from": [0, 0],
        "to": [0, 720]
      }
    },
    {
      "id": "speed_text",
      "type": "text",
      "z": 20,
      "dst": [700, 80, 600, 120],
      "text": "-- km/h",
      "fontSize": 72,
      "color": "#FFFFFFFF",
      "bind": {
        "template": "{speed:0} km/h",
        "fallback": "-- km/h",
        "throttleMs": 150
      }
    },
    {
      "id": "range_bar",
      "type": "shape",
      "z": 30,
      "dst": [700, 300, 240, 80],
      "color": "#FFB300",
      "bind": {
        "source": "range",
        "property": "scale",
        "from": [0, 400],
        "to": [0.4, 1.2],
        "ease": 120
      }
    },
    {
      "id": "speed_fill",
      "type": "vector",
      "shape": "polygon",
      "z": 40,
      "dst": [120, 300, 400, 260],
      "points": [[0,260],[60,220],[120,240],[200,80],[280,200],[340,150],[400,180],[400,260]],
      "paint": {
        "kind": "linear",
        "colors": ["#0044FF", "#00E5FF"],
        "from": [0, 260],
        "to": [0, 0]
      },
      "stroke": { "color": "#7FE7FF", "width": 2 },
      "bind": { "source": "speed", "range": [0, 220], "direction": "up", "ease": 120 }
    },
    {
      "id": "speed_gauge",
      "type": "gauge",
      "z": 50,
      "gauge": {
        "data": "speed",
        "center": [255, 285],
        "radius": 185,
        "start": 120,
        "sweep": 300,
        "min": 0,
        "max": 220,
        "thickness": 20,
        "colors": ["#BFF8FF", "#FFFFFF", "#FFD391"],
        "track": "#2ADFF4FF",
        "ticks": 24,
        "labels": [0, 40, 80, 120, 160, 200, 220],
        "labelColor": "#FFFFFFFF",
        "labelSize": 18
      }
    },
    {
      "id": "eta",
      "type": "live",
      "slot": "eta",
      "z": 60,
      "dst": [1320, 430, 430, 165]
    },
    {
      "id": "overspeed_warn",
      "type": "text",
      "z": 70,
      "dst": [560, 260, 800, 120],
      "text": "超速",
      "fontSize": 88,
      "color": "#FF1744",
      "visibleIf": { "speed": { "gte": 999 } }
    }
  ],
  "animations": [
    {
      "target": "speed_text",
      "property": "alpha",
      "duration": 2.0,
      "loop": true,
      "keyframes": [[0, 1], [1, 0.7]]
    }
  ],
  "rules": [
    {
      "id": "overspeed",
      "forMs": 3000,
      "cooldownMs": 30000,
      "when": { "source": "speed", "op": "gt", "value": 120, "release": 110 },
      "actions": [
        { "type": "show", "target": "overspeed_warn" },
        { "type": "flash", "target": "overspeed_warn", "periodMs": 400 }
      ]
    },
    {
      "id": "harsh_accel",
      "forMs": 0,
      "cooldownMs": 10000,
      "when": { "source": "speedDelta", "op": "gt", "value": 20, "windowMs": 1000, "release": 15 },
      "actions": [
        { "type": "pulse", "target": "speed_text", "periodMs": 300, "amplitude": 0.12, "durationMs": 1000 },
        { "type": "setText", "target": "speed_text", "text": "急加速" }
      ]
    },
    {
      "id": "night_fast",
      "forMs": 0,
      "cooldownMs": 0,
      "when": {
        "all": [
          { "source": "speed", "op": "gt", "value": 100 },
          { "source": "night", "op": "eq", "value": 1 }
        ]
      },
      "actions": [
        { "type": "show", "target": "overspeed_warn" }
      ]
    }
  ]
}
```

AI 生成图片时，图片只放 `background.png` 等相对文件名；`src` 不要带目录。

## 10. 常见错误



* `dst` 写成 `[x1,y1,x2,y2]`：正确是 `[x,y,width,height]`。

* 在主题里画车速数字：MCU 已经画，重复会出现两套数字。

* `rpm` 的 labels 用 0..5.5：必须用真实转速值 0..5500。

* `z` 超过 1900：会压到 MCU，系统会限制；不要这样做。

* 图片写绝对路径或 `../`：会被拒绝。

* 主题目录里没有 `theme.json`：不会被扫描到。

* 图片文件不在主题目录：加载失败。

* 把文字/卡片放在 `y > 610` 的底部安全区：PC 预览能看到，真实仪表盘上被饰板遮挡，完全不可见。

* 使用单引号、注释、尾逗号：不是合法 JSON。

* `text.bind` 使用未知数据源（如 `{battery}`）：只允许 4.4.1 表里的源；整条文字会回退。

* 属性 `bind` 缺 `to`（color 除外）或 `from` max≤min：该 bind 会被忽略。

* vector 的 `points` 写成面板绝对坐标：它们是相对 `dst` 的局部坐标；只有 `normalized:true` 才是 0..1。

* vector 的 `paint.from/to` 写成面板坐标：vector 的 paint/stroke 坐标同样是 dst 局部坐标。

* `rules[].actions[].target` 写不存在的图层 id：该动作被忽略；保存时审计会报错。

* 在 rules 里期待持续触发：条件持续期间只触发一次；回落后受 `cooldownMs` 限制。

* `speedDelta` 在主题刚启动/刚切换后 1 秒内不会触发：没有历史采样时按“条件不成立”处理，这是防误触。

* `pulse`/`flash` 的 `durationMs` 只结束该动作，不会让规则重新触发；要重新触发必须先让条件回落。

* `when.all`/`when.any` 里再嵌套 `all`/`any`/`not`：不支持；组合只允许一层，`not` 只包一层。

* 条件组合写了多个叶子但仍是 `single`：解析器只取第一个；WPF 保存会直接报错，AI 应显式写 `all` 或 `any`。

* `setText` 指向 image/gauge 等非 text 图层：文字覆盖不会显示；审计只校验 target 存在，不校验类型。

* `setImage` 的 `src` 写路径或 `..`：只允许主题目录内的文件名；条件结束后会自动恢复原图。

* 状态 `color` 写在 image/gauge 图层：不会生效；只支持 text/shape/vector。
* 状态 `dst` 写成归一化 0..1 或相对坐标：必须是面板绝对 `[x,y,w,h]`；gauge 不支持。

* 状态按数组顺序首个匹配：把 `speed lte 5` 放在 `navActive` 前面，导航状态永远不会命中；导航状态要放前面。

* 把 vector 放到底部 610 以下：旧 App 不画 vector 是预期降级，不是错误；但真实仪表盘同样看不到。

## 11. 主题制作工具

`DiPlayThemeMakerWpf.exe`（WPF / C# / .NET Framework 4.5.2，内置 adb）可可视化编辑上述字段，支持：



* 图片、渐变、形状、文字、仪表、实时槽位、异形（vector）；

* 文字模板、属性绑定、显示条件、关键帧、事件规则（规则… 按钮）、场景状态（状态… 按钮）；

* 保存 theme.json 和图片；

* 通过 adb push 把主题推到车机目录（默认 `/data/data/<包名>/files/themes/`）：推送前先尝试 `adb root`，再建目录、逐个文件推送；单个文件失败只记录不中断，界面显示进度条；

* 推送完成**不会自动重启 App**：需要从系统设置里强制停止 App，把车机切换到「U 盘模式」，再重新打开 App 检查是否加载到自定义主题。

车机主题目录固定为：



```
/data/data/com.shihab.diplay.hudtest/files/themes
```

App 默认就读这个目录，不需要改设置。手机测试可临时把 App 的自定义目录改成 `/sdcard/Android/data/<包名>/files/themes/`，并把制作器目标改成同一个。预览画面底部 110px 的红色区域就是安全区域，编辑器里能看见，真实仪表盘上不可见。