# 20260930-2342 spec 0018 DSH 只读插件装载验证（票 #170 人工项 2 第一步）

前情：机主报「第一步 安装只读插件导致 DSH 无法启动」。本验证在实机复现安装→启动全链，
定位根因并修复，真会话冒烟打通（DSH→插件→桥→快照能力/来源会话）。

## 判定表

| # | 项目 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | DSH 本体是否「无法启动」 | **可启动**（进程/窗口/会话正常；rc.2 不落盘 logs 属正常，非故障信号） | 见「现象澄清」 |
| 2 | 插件安装（清单命令路径） | **装得上**：`dsh plugin --profile desktop add <绝对路径>` EXIT=0，依赖＋bundles＋symlink 齐 | node_modules/dsh-bridge-readonly → 仓内 adapters/dsh |
| 3 | 插件挂载 | **复现失败→修复后 PASS**：修复前组件 `rearcue-dsh-bridge` 标「异常」不挂载；修复后 apply 全量订阅 9 事件 | 01-trace.txt |
| 4 | 桥端活性（心跳→能力露面） | **PASS**：`/snapshot` `dsh:["waiting","approve"]`（修复前 60s 内恒为 `["waiting"]`） | 01-trace.txt |
| 5 | 真会话冒烟 | **PASS**：DSH 真会话 `session-84e35959-1`（workspace RearCue）上桥 | 01-trace.txt |
| 6 | 判例回归 | **PASS**：adapters/dsh 44/44；桥面 bridge/tray/make-icons fail 0 | node --test 输出 |

## 根因

`dsh-bridge-plugin.mjs` 的 `engineVersionOf(ctx)` 直接读 `ctx.engine`。cordis 宿主 ctx 是
Proxy：**未注册的 service 属性访问直接抛异常**（不是回 undefined），apply 在版本门槛判定前
被打断，loader 捕获后把插件标「异常」。判例里 ctx 一律是注入替身（普通对象），从未暴露此形状。

修复（fail-open，与「认不出回 null＝不挡」的既有注释语义对齐）：`engineVersionOf` 整段
try/catch，任何抛错回 null。trace 埋点证实：修复前 trace 停在 `apply entered`，修复后走完
`apply completed, all subscriptions set`。

## 现象澄清（「无法启动」的真相）

- DSH 0.2.0-rc.2 桌面壳**不再写** `logs/dsh-*.log` 与 `logs/host/*`（9-28 前的旧版本才有）——
  「没有日志」不能当故障证据；本次全程无日志但进程/窗口/会话全正常。
- 装**我们的**插件不会挡 DSH 启动；挡的是插件自身挂载（面板显示异常）→ 桥端 DSH 来源不出现
  → 人工项 2 联调走不下去。修复后该链路通。
- 真正会把 DSH「搞出事」的是另一件事（见附带发现 3）：装插件触发全量兼容复扫，
  三个旧插件豁免失效被拒载，插件面板出现成片「异常」。

## 附带发现（三条，均已实证）

1. **仓内 `dsh-plugin-toggle.ps1` 不是安装器**：只改 manifest（dependencies＋bundles），
   不跑包管理器，node_modules 里没有包；bundle 在列而包不在时 host 只 stderr 跳过、
   不报错、面板也不显示（比「异常」更隐蔽）。真安装命令：
   ```powershell
   # 先完全退出 DSH
   & "$env:LOCALAPPDATA\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd" `
     plugin --profile desktop add C:\Users\13691\Desktop\RearCue\tools\bridge\adapters\dsh
   ```
2. **验收清单人工项 2 的 `--profile web` 是错的**：桌面壳实际用 profile `desktop`
   （host 进程命令行可证）。按清单跑 `--profile web` 会装进 web profile，桌面壳根本不加载。
3. **DSH 自动升级 rc.1→rc.2（9-30 凌晨）令旧插件版本豁免全部失效**：豁免精确到版本号
   （`~/.dsh/profiles/desktop/compatibility.json` 里登记的都是 0.2.0-rc.1），
   browser-skill / better-sidebar / find-plugin 三插件被拒载标「异常」。
   恢复属机主风险决定，命令（对每个插件）：
   ```powershell
   dsh plugin --profile desktop allow-version <pkg>@<ver> --dsh-version 0.2.0-rc.2 --accept-risk
   ```

## 代码改动（未提交，现落在 feat/211-picker-flat-fill 工作区）

- `tools/bridge/adapters/dsh/dsh-bridge-plugin.mjs`：engineVersionOf 防御化（根因修复）。
- `tools/bridge/adapters/dsh/dsh-events.test.mjs`：源码扫描判例加固 CRLF
  （autocrlf 检出下 `.` 不匹配 `\r`，行注释剥不干净导致预存失败；与本根因无关，顺手修）。

## 现场状态

- 插件已装且启用（desktop profile），DSH 运行中，桥快照 dsh 含 approve 能力；
  人工项 2 后续（真审批 waterfall 应答）可在该状态上继续。
- 旧三插件维持「异常」待机主拍板是否 allow-version。
