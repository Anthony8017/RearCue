# erratum — 搜索法找行失败（票 #27 bring-up 轮）

本轮**没有动任何开关**：`iv_search` 打开后 `input text` 没有落进输入框（两次 dump 逐字节相同、列表未过滤），
`search-*.xml` 是未过滤列表的原样快照。ChatGPT 的复原判定也因此误报 “already ON（实为行不可见）”——
该误报只影响本轮 console 文案，不影响任何设备状态；复原最终在 032215 轮完成（见 025010 轮 summary）。
