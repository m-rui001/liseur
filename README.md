# Liseur —— 把数学书装进口袋

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <img src="docs/banner-light.png" alt="A woman reading on a couch under a lamp" width="640">
  </picture>
</p>

<p align="center">
  开源 Android EPUB 阅读器 · 公式是真公式 · 离线可用 · 无广告无追踪 · MIT<br>
  <b>目标：把 STEM 书变成机器读得懂的文本。</b><br>
  <sub>扫描页里的数学，人和模型都检索不到。这个仓库管两端：把书转成文本，和在手机上把它读好。</sub>
</p>

---

**你在手机上读不下去一本数学书，不是你的问题。**

PDF 是为 A4 排的：放大以后一行只看得进五个字，重排以后公式和插图全部错位，
想跳到第 120 页得先划三十下。于是很多人退回去看公式截图——可一张 `\int` 的截图既不能搜索、
不能复制、不能重排，放大就糊，还会把一本书变成几百张图片的压缩包。

Liseur 走另一条路：书是 EPUB，公式是正文里的 LaTeX 源码，渲染器跟着 App 一起装进手机。

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

一本 300 页的书，EPUB 加字体大约几 MB；同样的内容把公式切成图片常常上百 MB，而且从那刻起，
这本书就再也搜不到自己了。

## 这个 App 为 STEM 做的事

- **公式是排出来的。** [KaTeX](https://katex.org)（Knuth TeX 血统的排版器）打包在 App 里，
  通过阅读器自己的资源服务器注入书页。所以它不联网——地铁、飞机、山里都能读——也不新增依赖，
  App 照样离线构建，照样符合 F-Droid。
- **比页宽的公式可以拖着看。** 一个显示公式是不可折行的一行，而手机栏宽只有 40 个字符左右，
  于是几乎每一个有价值的公式都比它所在的那页宽。横向按住公式拖动，移动的是公式本身，
  翻页和滚动两种阅读模式行为一致。
- 没有开关。排版跟脚注、表格是同一类"修复"：没听说过 LaTeX 的读者不该为了读数学书去翻设置，
  听说过的也不该因为某个开关关着，就在一本书里丢掉全部公式。
- 降级也可读。用别的软件打开同一本书，看到的是 `\(x\)`，一段能读的源码。
- 划词高亮（三色可选）、页边注、书签、站内字典、脚注卡片、目录、进度统计、
  笔记与批注跨设备同步——这些来自上游，见下文。

原理与取舍写在
[`docs/adr/0037-katex-mathematics-and-a-dragged-formula.md`](docs/adr/0037-katex-mathematics-and-a-dragged-formula.md)：
为什么不能"放个滚动框让浏览器自己处理"（Readium 判断翻页只看位移和速度，**从不问文档里手指
底下是什么**），为什么要给公式盒子量一个页面的宽度，为什么拖动写绝对位置而不是增量。

## 另一半：把你的 PDF 变成这种书

能画公式的阅读器只解决了一半，书里得先有 LaTeX。这一步很少人做。现成的 PDF→EPUB 工具几乎
都往 MathML 走，而 MathML 在 EPUB 里的支持薄到 pdf-craft 自己在文档里都不背书：Kindle 全系
不认，Apple Books 会忽略 `mrow`，微信读书整段跳过。

我选了另一条看起来更笨的路——公式以 LaTeX 源码留在正文流里。它短，它能 `grep`，能被全文
索引；LLM 读 LaTeX 是母语，读 MathML 是在啃标签树。还有一个理由更现实：EPUB 最终排到什么
质量，本来由阅读器说了算，把渲染搬进 App，这部分主动权就回到读书的人手里了。

管线大致是：扫描版或数字版 STEM PDF 先过 OCR，落成一个装着 LaTeX、`bbox` 和图片哈希的中间
归档，再从同一份归档出 EPUB3、`.tex` 和 Markdown——改排版参数不用重跑 OCR。质检有三层：
本地确定性检查打底（最强的一项是把每条公式无头编译一遍），文本模型做主筛，视觉模型专治那些
被切成图片的公式。

模型全部来自免费额度：OCR 是硅基流动的 DeepSeek-OCR，主筛和视觉修复用 Z.ai 的免费 flash 模型
和 Google AI Studio 的免费模型，第二套抽取引擎 MinerU 走它 2000 页/天的免费档。所以现金成本
是 0，要算计的只有每天的请求额度。目前跑通 123 本 / 23,207 页，里面有 Tao《Analysis II》、
《Topological Picturebook》、《数值分析》。

搭这条流水线用的是 Workbuddy 的免费额度，改 Liseur 用的是 Qoder 的免费额度，我一行代码没手写。

完整记录——判据、实测数字、Windows 上那几个坑、可复现命令——写在
[`docs/stem-pdf-to-epub.zh-CN.md`](docs/stem-pdf-to-epub.zh-CN.md) 里。

> 如果你手边正好有一本只剩扫描 PDF 的书，转掉它。公式一旦退化成图片，这本书就再也搜不到
> 自己了：每个人得重新读一遍，每个模型都读不到。三百页的活儿挂一个晚上就完，不花钱。
> 这批书不会因为有谁在等它们就变短。

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

这不只是为了我读书舒服。

一本实变函数，作者写三四年，拿走零售价的 8~12%，定价权还在出版方手里；同一本书加密的电子版
常常比纸质的还贵。知识生产出来以后，头一件事是被锁进三处：价格、版面、图片格式。前两条是
钱包的问题，第三条是技术问题，而技术这条恰好是整个仓库在管的。

### 大模型把这件事变得很急

人类今天写下的数学，相当一部分还躺在扫描页里。它进不了 tokenizer，进不了检索，进不了证明
检查器。于是所谓"读遍人类数学的模型"，读到的其实是一份按付费墙和扫描质量抽出来的样：新一点
的英文教材清楚，越老、越难、母语不是英语的那些越是空白，而那堆里面偏偏存着最难的东西。
JPEG 上盖不起这样的模型——这是数据问题，跟立场没关系。

再往下说更要紧。通用智能如果被跨过，它读过什么，就会决定以后所有人怎么被回答，而这件事不该
由几家公司的路线图来定。它们的路线图里不会有"把一本 1987 年的中文实变函数变成能 `grep` 的
文本"这一条，不可能有：没人为了这一条付钱，也没人因为缺这一条流失用户。

所以开源模型那条路得有人走，把最强的东西做到人人买得起、查得到、改得动。我在更下游，做的事
土得多——把书变成文本。模型每回答一次"一致收敛怎么定义"，背后总得有人先把写着这句话的那本
书从像素里捞出来。两头少任何一头，"最前沿的智能应该开放、廉价地供应给所有人"就是一句宣传。

### 这些产物其实不是电子书

全库 128,532 条公式是 LaTeX 源码，`\int` 能被 `grep`，能被全文索引。L0 门禁的干净率约
99.93%，因为 KaTeX 是个免费且不留情面的裁判，语法不过就是不过。已入库的 23,207 页都带着
`bbox` 和页码，任何一句都能翻回原书的那一版去核对——可核对才可引用，这才是它跟"一份 AI 摘要"
的区别。候选库里一共 447 本、183,046 页，还剩 324 本在排队，约 66 小时机时。

这些是一个人、一台 Windows 机器、四个免费额度的 key 跑出来的。一本三百页的书，净速率 5.87
秒一页，纯机时不到半小时，把排队和重跑算上，挂一晚也就完了。谁都能跑。

还有一件事我本来会写在脚注里：这条流水线是用 Workbuddy 的免费额度搭起来的，Liseur 上面那些
改动是用 Qoder 的免费额度改的。一分钱没花，也没有手写一行代码。这话在某些场合近乎自贬，我
的看法正相反——门槛低到这个地步，它就不该再被当成门槛。剩下的问题只有一个：那 324 本谁去跑。

### 一本书不等于它版权登记在谁名下

版税买的是印刷、发行，和书名页上那个名字占的位置，内容本身不在交易里。实变函数里那个积分号
不是哪家出版社发明的，它是两百年间几百个人写下、改过、纠错、重新解释过的东西，最后一个人
只是把它排成了铅字。把搬运工叫作作者，这套说法就站不住了：凭什么一份人类写下的数学，仅仅
因为它有权利人，就同时禁止被检索、被索引、被机器读懂。

### 转格式本身就是在保存

扫描件比想象中易碎。同样一本三百页的书，扫描 PDF 动辄几百 MB，每页一张图；转出来的 EPUB
加 LaTeX 只要几百 KB 到几 MB，差两三个数量级。前者改不了——版面就是像素，改一处要重做整页；
后者是文本、公式源码和结构，谁都能修。至于十年后还打不打得开，扫描件要赌某个阅读器还认得
那套编码，文本只要赌 UTF-8 和 LaTeX 还活着，这基本不用赌。存一千本扫描要 TB 级，存一千本
文本几 GB，一个同步盘就装得下。能被复制很多份的东西活得久，能被编辑的东西才谈得上被修正和
续写：一张扫描页坏了就是坏了，一行 LaTeX 写错了，会有一个我从来没见过的人把它改对，然后
往后所有人读到的都是改对的那一版。

把书从像素里搬出来，不是为了在手机上看得清楚一点。是为了让它们在格式更迭、纸张酸化、硬盘
消磁、某家出版社悄悄清掉一个域名之后，还有下半段命。

### 这只是开头

123 本进了库，324 本在排队，机时不等人。这套东西还要往几个方向长：一个可验证的数学语料层
（只有公式结构、编译判据和页码指纹，不含任何书本文字），更多的书，以及现在还不方便写在这里
的几步。

我不认为知识应该按钱包分配，也不认为 1998 年那套规则该原样管住 2026 年的机器能读什么。这是
政治判断，不是技术结论，所以我把它写在技术文档里，不藏进脚注。

本仓库不托管、不分发任何书或成品。这里只有代码：把 PDF 变成机器读得懂的书的那部分代码。

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

An open-source Android EPUB reader that typesets the mathematics a book wrote in TeX. KaTeX ships
inside the app, so it works offline and adds no dependency; it stays F-Droid-compatible. A formula
wider than the page drags sideways instead of turning the page out from under your thumb.

Formulae are LaTeX source sitting in the text flow — no images, no MathML — so they stay
searchable, copyable, reflowable, and readable by an LLM. Forked from upstream v0.18.0 with the
history intact; also fixes on-link detection on mobile data (#241), a page-curl that showed a page
that had not loaded, and a crash every launch after a book was deleted outside the app. All the
base functionality is Chmouel Boudjnah's ([chmouel/liseur](https://github.com/chmouel/liseur), MIT).

The companion pipeline that turns STEM PDFs into these books is written up in
[`docs/stem-pdf-to-epub.zh-CN.md`](docs/stem-pdf-to-epub.zh-CN.md) (Chinese): 128,532 formulae as
LaTeX, 23,207 pages converted, a three-tier quality gate. Every cent of it came from free quotas —
DeepSeek-OCR, Z.ai, MinerU, Google AI Studio — and the build work itself ran on free agent quotas
too: the pipeline was assembled with Workbuddy, this fork's changes with Qoder, and not one line
of that code was hand-written. Zero spend, start to finish.

What it is for: a scanned page cannot be indexed, searched, or trained on, and 128,532 formulae
still are one. Nobody behind a paywall is going to fix that, and no closed lab's roadmap contains
a line for an old Chinese real-analysis book. 324 volumes are still queued. Convert yours.

</details>
