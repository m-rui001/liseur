# Liseur —— 把数学书装进口袋

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <img src="docs/banner-light.png" alt="A woman reading on a couch under a lamp" width="640">
  </picture>
</p>

<p align="center">
  开源 Android EPUB 阅读器 · 公式是真公式 · 离线可用 · 无广告无追踪 · MIT<br>
  <b>目标：让 STEM 书变成机器读得懂的文本，而不是一摞扫描页。</b>
</p>

---

**你在手机上读不下去一本数学书，不是你的问题。**

PDF 是为 A4 排的：放大以后一行只看得进五个字，重排以后公式和插图全部错位，
想跳到第 120 页得先划三十下。于是很多人退回去看公式截图——可一张 `\int` 的截图既不能搜索、
不能复制、不能重排，放大就糊，还会把一本书变成几百张图片的压缩包。

Liseur 走另一条路：**书是 EPUB，公式是正文里的 LaTeX 源码，渲染器跟着 App 一起装进手机。**

## 为什么 EPUB 而不是 PDF

| | PDF | 重排式 EPUB |
|---|---|---|
| 6 寸屏 | 放大、平移、来回划 | 字号随便调，一行始终这么长 |
| 深色 / 护眼 | 通常白底一片 | 四种主题（Light / Sepia / Dark / OLED Black），页面跟随 |
| 公式 | 要么是矢量要么是图 | 一段文本：可搜索、可复制、可重排 |
| 进度 | "第 120 页"取决于你用哪台设备 | 精确到句子的定位，跨设备同步 |
| 脚注 | 跳到最后，回不来 | 在原位开一张卡片 |
| 亮度 / 行距 / 边距 | 没有 | 按全书或按单本调 |
| 自动续读 | 无 | 按设定速度滚动，跨章节连续 |

一本 300 页的书，EPUB 加字体大约几 MB；同样的内容把公式切成图片常常上百 MB，
而且从那刻起，这本书**再也搜不到自己**。

## 这个 App 为 STEM 做的事

- **公式排出来，不是显示出来。** [KaTeX](https://katex.org)（Knuth TeX 血统的排版器）打包在
  App 里，通过阅读器自己的资源服务器注入书页。**不联网**——地铁、飞机、山里都能读；
  **不新增依赖**，App 仍然可以离线构建、仍然符合 F-Droid。
- **比页宽的公式可以拖着看。** 一个显示公式是不可折行的一行，而手机栏宽只有 40 个字符左右——
  几乎每一个有价值的公式都比它所在的那页宽。横向按住公式拖动，**移动的是公式，不是整页**，
  翻页和滚动两种阅读模式行为一致。
- **它不是一个开关。** 排版和脚注、表格属于同一类"修复"：不知道"LaTeX"这个词的读者
  不该为了读数学书去找一个设置项；知道的也不该因为某个开关关着，在一本书里丢掉全部公式。
- **`\(x\)` 降级也可读。** 用别的软件打开同一本书，看到的是可读的 LaTeX 源码，不是空白或乱码。
- 划词高亮（三色可选）、页边注、书签、站内字典、脚注卡片、目录、进度统计、
  笔记与批注跨设备同步——这些来自上游，见下文。

原理与取舍写在
[`docs/adr/0037-katex-mathematics-and-a-dragged-formula.md`](docs/adr/0037-katex-mathematics-and-a-dragged-formula.md)：
为什么不能"放个滚动框让浏览器自己处理"（Readium 判断翻页只看位移和速度，**从不问文档里手指
底下是什么**），为什么要给公式盒子量一个页面的宽度，为什么拖动写绝对位置而不是增量。

## 另一半：把你的 PDF 变成这种书

能画公式的阅读器只解决了一半——**书里得先有 LaTeX**。这不是常见做法：现成的 PDF→EPUB 工具
几乎都输出 MathML，而 MathML 在 EPUB 里的支持薄到 pdf-craft 自己在文档里都不背书
（Kindle 全系不支持，Apple Books 会忽略 `mrow`，微信读书直接忽略）。

我的选择是把公式**以 LaTeX 源码留在正文流里**：更简洁、可 `grep`、可全文检索，
而且今天最要紧的一条——**LLM 读 LaTeX 是母语，读 MathML 是在啃标签树**。
死结在于"EPUB 的质量上限由阅读器决定"，所以把渲染这一半拿回自己手里。

为此我建了一条管线：扫描版/数字版 STEM PDF → OCR → **LaTeX + bbox + 图片哈希的中间归档** →
EPUB3 / `.tex` / Markdown，配**三层质检**（本地确定性检查 + 文本模型主筛 + 视觉模型修正）。
全程跑在免费额度上——DeepSeek-OCR、Z.ai 的免费 flash 模型、MinerU 免费档、Google AI Studio
免费模型，**现金成本 0**，真正的预算是每天的请求额度。目前跑通 **123 本 / 23,207 页**
（Tao《Analysis II》、《Topological Picturebook》、《数值分析》……）。

完整记录、判据、实测数字和可复现命令：
**[`docs/stem-pdf-to-epub.zh-CN.md`](docs/stem-pdf-to-epub.zh-CN.md)**。

> **把你手边的 PDF 转成 EPUB。** 公式一旦退化成图片，这本书就再也搜不到自己了——它从"可检索的
> 知识"掉回"一摞扫描页"，谁都得重新读一遍，包括模型。转一本的机时是几个小时，换来的是一段
> 能查、能引、能喂给机器的文本。

## 界面

<table>
  <tr>
    <td width="33%"><img src="docs/screenshots/01-library.png" alt="Library"></td>
    <td width="33%"><img src="docs/screenshots/02-reading.png" alt="Reading"></td>
    <td width="33%"><img src="docs/screenshots/04-typography.png" alt="Typography"></td>
  </tr>
  <tr>
    <td align="center"><sub>书架，按你正在读什么排序。</sub></td>
    <td align="center"><sub>无干扰页面：笔记与书签在手边。</sub></td>
    <td align="center"><sub>主题、字体、间距、亮度。</sub></td>
  </tr>
</table>

<sub>更多截图见 <a href="docs/SCREENSHOTS.md">docs/SCREENSHOTS.md</a>。</sub>

## 一个书架，不管书从哪来

本地文件夹、[calibre-web](https://github.com/janeczku/calibre-web)、
[Komga](https://komga.org)、[liseur-sync](https://github.com/chmouel/liseur-sync)
或任意 [OPDS](https://specs.opds.io/opds-1.2) 目录，都并成一个书架；系列按阅读顺序堆叠，
追踪进度与缺卷。阅读位置跨设备同步，后三种服务器能同步到**具体哪一句**。
空书架还会送上一排 [Project Gutenberg](https://www.gutenberg.org) 公版书，挑类别和数量即可。

没有追踪、没有统计、没有广告、没有订阅：App 只连你自己配置的服务器和词典源。
隐私政策见上游 [PRIVACY](https://chmouel.github.io/liseur/PRIVACY)。

## 相对上游改了什么

基线是上游 **v0.18.0**，**提交历史完整保留**，所以差异一眼可查：
[compare/v0.18.0...main](https://github.com/m-rui001/liseur/compare/v0.18.0...main)
（源码与测试 16 个文件，KaTeX 及其许可资源 24 个，其余是文档）。

| # | 改动 | 类型 |
|---|---|---|
| 1 | KaTeX 数学排版 + 可拖动的超宽公式 | 新功能 |
| 2 | 蜂窝网络下把每台服务器都误判为"本地"从而全部拒连（#241） | 缺陷修复 |
| 3 | 翻页卷曲动画揭开还没到达的那一页 | 缺陷修复 |
| 4 | 书在 App 外被删除之后，每次启动都崩溃 | 缺陷修复 |
| 5 | 默认阅读字体改为出版方自带字体 | 偏好调整 |

每一条的**问题、为什么常见解法不行、做了什么**：
[`docs/fork-changes.zh-CN.md`](docs/fork-changes.zh-CN.md)。

## 构建

需要 JDK 17 与 Android SDK（`compileSdk`/`targetSdk` 37，`minSdk` 26）。

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
make check                       # 测试 + lint + debug 构建
```

装到手机与模拟器的流程、架构与协议细节见 [`DEVELOPER.md`](DEVELOPER.md)。
本仓库**不附带二进制**：APK 请自己构建，或者用上游的正式发布（见下一节）。

## 安装上游原版

想让公式显示，就得用这个仓库的版本。如果你不需要数学排版，上游本身就有正式渠道：

<p align="center">
  <a href="https://f-droid.org/en/packages/com.chmouel.liseur/">
    <img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80" />
  </a>
  <a href="https://github.com/chmouel/liseur/releases">
    <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png"
         alt="Get it on GitHub" height="80" />
  </a>
</p>

## 为什么要做这件事

**这不只是为了我读书舒服。**

一本实变函数，作者写三四年，拿走零售价的 8~12%；定价权在出版方手里，同一本书加密的电子版
常常比纸质还贵。知识生产出来之后，第一件事是被锁进三样东西里：**价格、版面、图片格式**。
前两条是钱包的问题，第三条是技术问题——而技术问题恰好是这一整个仓库在解决的。

大模型把这一步变得紧急：今天人类写下的数学，有相当一部分以**扫描页**的形式存在，
对任何语料都是隐形的。它进不了 tokenizer，进不了检索，进不了证明检查器。
一个读遍人类数学的模型不能建立在 JPEG 之上——**这不是立场，是数据问题**。

所以这条管线的产物严格来说不是"电子书"，是**语料**：

| | |
|---|---|
| **128,532** 条公式（全库） | LaTeX 源码，不是裁图；`\int` 能 `grep`，能被全文索引 |
| **~99.93%** L0 门禁干净率 | KaTeX 是免费且不留情面的裁判：语法不过就是不过 |
| **23,207** 页（123 本已入库） | 带 `bbox` 与页码，能回溯原书版面，可核对可引用 |
| **447 本 / 183,046 页**（候选库） | 还排着 324 本，约 66 小时机时 |

而这件事是一个人、一台 Windows 机器、四个免费额度的 key 做完的：现金成本 **0**，
瓶颈只有各家的日请求额度。这个事实比任何口号都重要——**它意味着这不需要授权、不需要经费、
不需要等机构醒过来**。一本 300 页的书，净速率 5.87 秒/页，不到半小时机时就从"扫描件"
变成"文本"；并发跑起来更短。任何人都跑得起。
我只跑了我那一份，候选库里还有 324 本在等着；你书架上那本，也不该继续是图片。

我不认为知识应该按钱包分配，也不认为 1998 年的那套规则该原样管住 2026 年的机器能读什么。
这是政治判断，不是技术结论，所以我把它写在技术文档里。
本仓库不托管、不分发任何书或成品：这里只有代码——把 PDF 变成机器读得懂的书的那部分代码。

## 归属与许可

**MIT**（[`LICENSE`](LICENSE)），与上游一致；我**不**对上游代码主张任何额外权利。
上游的英文原版说明完整保留在 [`docs/UPSTREAM-README.en.md`](docs/UPSTREAM-README.en.md)。

- **[Liseur](https://github.com/chmouel/liseur) 的全部基础功能与设计来自
  [Chmouel Boudjnah](https://github.com/chmouel)** —— 阅读引擎、书架、同步协议、批注、
  界面、翻译、F-Droid 就绪、以及 `DEVELOPER.md` 里那些年的判断。这个分支只是接上了数学这一块。
  名字来自法语 *liseur*（[li.zœʁ]，"嗜读者"），读音近英语 *leisure*，
  也来自雷诺阿笔下那幅《读书人》（画的是莫奈）。
- 捆绑字体为 SIL OFL；KaTeX 为 MIT（Copyright (c) 2013-2020 Khan Academy and other contributors），
  已列入设置页的开源许可。渲染引擎为 [Readium Kotlin Toolkit](https://readium.org)（Apache-2.0）。

---

<details>
<summary>English (short version)</summary>

An open-source Android EPUB reader that renders the mathematics a book wrote in TeX — offline,
with KaTeX bundled in the app, no new dependency, still F-Droid-compatible — and lets you drag a
formula that is wider than the page sideways instead of turning the page or sliding the whole
book out from under your thumb.

Formulas live in the book as **LaTeX source, inline in the text flow**, not as images and not as
MathML: they stay searchable, copyable, reflowable, and readable by an LLM. This fork is based on
upstream **v0.18.0** with full history preserved, and also fixes local-network detection on
mobile data (#241), the page-curl animation revealing a page that had not arrived, and a
crash-on-every-launch when a book was deleted outside the app.

Upstream is [chmouel/liseur](https://github.com/chmouel/liseur) by Chmouel Boudjnah, MIT licensed;
all base functionality is his. The companion pipeline that converts STEM PDFs into these EPUBs is
documented in [`docs/stem-pdf-to-epub.zh-CN.md`](docs/stem-pdf-to-epub.zh-CN.md) (Chinese):
128,532 formulae as LaTeX rather than images, 23,207 pages converted so far, three-tier quality
gate, all of it on free API quotas and no cash spent. The point is not a nicer ebook — it is that
a scanned page cannot be indexed, searched, or trained on, and a text page can. **Convert your
PDFs.** A formula that became an image is a book that can no longer search itself.

</details>
