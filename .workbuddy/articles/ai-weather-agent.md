<!--
标题：AI Agent 到底是什么？用一个天气助手讲清楚
摘要：不聊框架、不贴代码，用工具箱里的「AI 天气」拆开讲：模型、工具、循环三件套，以及它为什么能先查天气再回答。
Slug：ai-weather-agent
分类：技术笔记（category_id = 1）
标签：AI、Agent、天气
状态：草稿（status = 0）
-->

工具箱里有个「AI 天气」：输入城市，先出现实时天气和未来一周预报，下面再出现一段 AI 建议。

有意思的地方在于——**这段建议不是模型凭空想的，而是它先查了真实天气，再动笔写的。**

这背后就是这两年很火的 Agent。把它拆开看，其实没有多神秘。

## 一、它和聊天机器人差在哪

普通聊天机器人的路径很短：

> 你问 → 模型从训练时记住的东西里组织一个回答

天气这件事，模型没法「记住」明天广州下不下雨。它硬答，就只能编。

Agent 多了一步：

> 你问 → 模型判断「我需要查一下」 → 调用工具 → 拿到真实结果 → 再回答

可以把它想成一个刚入职的新同事：脑子很好使，但今天的数据他看不到。你给他开通几个查询系统的账号，告诉他「查天气用这个，查城市用那个」。遇到不知道的事，他会先去查，查完再写报告。

所以 Agent 不是让模型变得更聪明，而是给它配了一双能查资料的手。

## 二、最小 Agent 只有三样东西

| 零件 | 作用 | 在这个工具里 |
| --- | --- | --- |
| 模型 | 理解问题、决定要不要查、写最终回答 | DeepSeek |
| 工具 | 真正干活的查询函数，由后端执行 | 查城市、查天气 |
| 循环 | 把查询结果喂回模型，直到它给出答案 | 最多 3 轮 |

少了第三样，模型只能「请求查询」却看不到结果，Agent 就退化成一个会喊口号的聊天框。

## 三、整体结构

<div style="margin:28px 0;padding:18px;border:1px solid var(--c-border);border-radius:14px;background:var(--c-surface);overflow-x:auto">
<svg viewBox="0 0 760 300" style="width:100%;min-width:640px;height:auto;font-family:var(--f-sans)" role="img" aria-label="AI 天气工具的整体结构：浏览器调用后端，后端访问中国天气网、天气数据接口和大模型">
  <defs>
    <marker id="wx-arrow-1" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" style="fill:var(--c-text-faint)" />
    </marker>
  </defs>
  <rect x="20" y="110" width="170" height="80" rx="12" style="fill:var(--c-bg);stroke:var(--c-border-strong)" />
  <text x="105" y="143" text-anchor="middle" font-size="15" font-weight="600" style="fill:var(--c-text)">浏览器</text>
  <text x="105" y="166" text-anchor="middle" font-size="12" style="fill:var(--c-text-muted)">工具箱 · AI 天气</text>
  <rect x="250" y="95" width="220" height="110" rx="12" style="fill:var(--c-surface-sunken);stroke:var(--c-brand)" />
  <text x="360" y="130" text-anchor="middle" font-size="15" font-weight="600" style="fill:var(--c-text)">后端 Spring Boot</text>
  <text x="360" y="154" text-anchor="middle" font-size="12" style="fill:var(--c-text-secondary)">/api/tools/weather</text>
  <text x="360" y="178" text-anchor="middle" font-size="12" style="fill:var(--c-text-muted)">预报接口 · Agent 接口</text>
  <rect x="540" y="20" width="195" height="64" rx="10" style="fill:var(--c-surface);stroke:var(--c-border-strong)" />
  <text x="637" y="46" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">中国天气网检索</text>
  <text x="637" y="66" text-anchor="middle" font-size="11" style="fill:var(--c-text-muted)">「广州」→ 城市代码</text>
  <rect x="540" y="118" width="195" height="64" rx="10" style="fill:var(--c-surface);stroke:var(--c-border-strong)" />
  <text x="637" y="144" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">天气数据接口</text>
  <text x="637" y="164" text-anchor="middle" font-size="11" style="fill:var(--c-text-muted)">温度 · 预报 · 空气质量</text>
  <rect x="540" y="216" width="195" height="64" rx="10" style="fill:var(--c-surface);stroke:var(--c-border-strong)" />
  <text x="637" y="242" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">大模型 DeepSeek</text>
  <text x="637" y="262" text-anchor="middle" font-size="11" style="fill:var(--c-text-muted)">理解问题 · 写建议</text>
  <line x1="190" y1="150" x2="240" y2="150" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-1)" />
  <text x="215" y="140" text-anchor="middle" font-size="11" style="fill:var(--c-text-faint)">HTTPS</text>
  <line x1="470" y1="128" x2="530" y2="62" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-1)" />
  <text x="500" y="86" text-anchor="middle" font-size="11" style="fill:var(--c-text-faint)">查代码</text>
  <line x1="470" y1="150" x2="530" y2="150" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-1)" />
  <text x="500" y="140" text-anchor="middle" font-size="11" style="fill:var(--c-text-faint)">取预报</text>
  <line x1="470" y1="172" x2="530" y2="238" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-1)" />
  <text x="500" y="222" text-anchor="middle" font-size="11" style="fill:var(--c-text-faint)">工具调用</text>
</svg>
</div>

浏览器只和后端打交道。后端负责三件事：把城市名变成城市代码、按代码取天气、把结果整理给模型。

**为什么工具一定要放在后端？** 因为模型 Key 不能出现在浏览器里，天气接口也有跨域和请求头限制。更关键的一点是：模型本身不联网，它只能看见后端喂给它的文字。所以答案靠不靠谱，很大程度上取决于后端给它的数据干不干净。

## 四、一次调用到底发生了什么

<div style="margin:28px 0;padding:18px;border:1px solid var(--c-border);border-radius:14px;background:var(--c-surface);overflow-x:auto">
<svg viewBox="0 0 760 390" style="width:100%;min-width:640px;height:auto;font-family:var(--f-sans)" role="img" aria-label="天气 Agent 的调用时序：用户提问，后端把消息和工具清单发给模型，模型请求调用工具，后端查天气后把结果回填，模型再给出建议">
  <defs>
    <marker id="wx-arrow-2" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,0 L8,4 L0,8 z" style="fill:var(--c-text-faint)" />
    </marker>
  </defs>
  <rect x="30" y="12" width="120" height="40" rx="9" style="fill:var(--c-surface-sunken);stroke:var(--c-border-strong)" />
  <text x="90" y="37" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">用户</text>
  <rect x="205" y="12" width="130" height="40" rx="9" style="fill:var(--c-surface-sunken);stroke:var(--c-brand)" />
  <text x="270" y="37" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">后端</text>
  <rect x="405" y="12" width="130" height="40" rx="9" style="fill:var(--c-surface-sunken);stroke:var(--c-border-strong)" />
  <text x="470" y="37" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">模型</text>
  <rect x="590" y="12" width="140" height="40" rx="9" style="fill:var(--c-surface-sunken);stroke:var(--c-border-strong)" />
  <text x="660" y="37" text-anchor="middle" font-size="13" font-weight="600" style="fill:var(--c-text)">天气接口</text>
  <line x1="90" y1="52" x2="90" y2="370" style="stroke:var(--c-border);stroke-width:1.5;stroke-dasharray:5 5" />
  <line x1="270" y1="52" x2="270" y2="370" style="stroke:var(--c-border);stroke-width:1.5;stroke-dasharray:5 5" />
  <line x1="470" y1="52" x2="470" y2="370" style="stroke:var(--c-border);stroke-width:1.5;stroke-dasharray:5 5" />
  <line x1="660" y1="52" x2="660" y2="370" style="stroke:var(--c-border);stroke-width:1.5;stroke-dasharray:5 5" />
  <text x="95" y="82" font-size="12" style="fill:var(--c-text-muted)">1 问「明天适合跑步吗」</text>
  <line x1="92" y1="90" x2="262" y2="90" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="276" y="117" font-size="12" style="fill:var(--c-text-muted)">2 消息 + 工具清单</text>
  <line x1="272" y1="125" x2="462" y2="125" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="276" y="152" font-size="12" style="fill:var(--c-text-muted)">3 请调用 get_weather(广州)</text>
  <line x1="462" y1="160" x2="278" y2="160" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="276" y="187" font-size="12" style="fill:var(--c-text-muted)">4 查真实数据</text>
  <line x1="272" y1="195" x2="652" y2="195" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="276" y="222" font-size="12" style="fill:var(--c-text-muted)">5 温度 / 预报 / 风力</text>
  <line x1="652" y1="230" x2="278" y2="230" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="276" y="257" font-size="12" style="fill:var(--c-text-muted)">6 这是工具结果</text>
  <line x1="272" y1="265" x2="462" y2="265" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="276" y="292" font-size="12" style="fill:var(--c-text-muted)">7 生成最终建议</text>
  <line x1="462" y1="300" x2="278" y2="300" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
  <text x="95" y="327" font-size="12" style="fill:var(--c-text-muted)">8 建议 + 调用轨迹</text>
  <line x1="262" y1="335" x2="98" y2="335" style="stroke:var(--c-text-faint);stroke-width:1.5" marker-end="url(#wx-arrow-2)" />
</svg>
</div>

换成大白话：

1. 用户问「明天适合跑步吗」；
2. 后端把问题、以及「你可以用哪些工具」一起发给模型；
3. 模型说：先帮我查一下广州的天气；
4. 后端真的去查，拿到温度和预报；
5. 后端把结果按协议回填给模型；
6. 模型看着数据写建议，后端再连同「刚才查了什么」一起返回页面。

最容易出错的是第 5 步：查询结果必须带着「这是第几次调用」的编号回填，否则模型会不记得自己查过，轻则重复查，重则答非所问。

另外加了两条保险：最多 3 轮，防止绕不出来；关闭深度思考，限制输出长度。线上实测，一次完整调用大约 3 秒。

## 五、一个容易被忽略的坑

一开始我选了一个国外的免费天气接口，不用注册、不用 Key，看起来很省事。结果在服务器上请求，等了 30 秒没有任何响应；本机也一样——接口没问题，是网络到不了。

这就是写演示代码和做上线工具的区别：演示时能跑通就行，上线时得先确认「这台机器能不能访问」。后来换成国内可达的天气接口，问题才解决。

## 六、模型会犯错，所以要留后路

真正让工具能用的，不是主流程，而是几个兜底：

- **工具失败，把错误交给模型。** 查不到就告诉它失败原因，模型通常会换个城市写法重试，或者如实说查不到。
- **没有 AI，也要能用。** 没配置模型 Key 时，页面退回到按规则生成的天气摘要，不会白屏。
- **公开接口要限流。** 每次建议都要花真钱调用模型，所以限制单 IP 和全站每天的调用次数。

## 七、结尾

一句话总结：

> Agent 不是让模型更聪明，而是给它配了几个可以查资料的账号，再留一个「查完继续说」的循环。

天气只是最小的例子。真正可复用的是这套结构：模型负责理解，工具负责事实，后端负责边界。想试试的话，工具箱里的 `/tools/weather` 可以直接用。
