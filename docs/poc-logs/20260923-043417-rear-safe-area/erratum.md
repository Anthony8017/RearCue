# erratum（票 #26 实机取证轮，2026-09-23）

1. **`screencap -d` 的合法 id 是 SurfaceFlinger display id，不是逻辑 displayId**（票 #25 的
   「screencap -d <id> 在本机非法」就此勘误）：`-d 1` 报 `Display Id '1' is not valid`，而
   `dumpsys SurfaceFlinger --display-id` 给的 `4630946949513469332`（背屏）/`...331`（主屏）
   直接可用——**背屏真截图由此可得**，不必退到拍照。且**不带 `-d` 的缺省抓取不保证稳定**
   （本轮缺省抓到的是背屏 904×572，20260923-030129 轮抓到的是主屏），取证一律显式带 `-d`。

2. **UTF-8 无 BOM 的 .ps1 会被 PowerShell 按 ANSI 码页解析，注释行尾的全角「）」会吞掉换行**：
   `）` 的 UTF-8 尾字节 0x89 在 GBK 解码里咬掉紧随的 0x0A，下一行代码并进注释行，
   整个脚本结构崩（`Parser::ParseFile` 报「意外的 `}`」「try 缺 catch/finally」这类**假位置**错）。
   修法：脚本落盘存 **UTF-8 with BOM**（`[IO.File]::WriteAllText($f, $text, [Text.UTF8Encoding]::new($true))`），
   落地前先用 `Parser::ParseFile` 做语法检查（票 #16 惯例）照旧。本轮 evidence.ps1 首版即踩中。
   同源变体：`Get-Content` 不带 `-Encoding` 读中文 UTF-8 文件也会被 ANSI 解码吞换行（整份
   logcat 并成一行）——读一律显式 `-Encoding UTF8` 或 `[IO.File]::ReadAllLines`。

3. **取证时手机处于 PIN 锁屏 + 主屏息屏**（`mIsShowing=true secure=true`、`mWakefulness=Dozing`，
   KEYCODE_WAKEUP 被距离传感器吃掉不醒）：不影响取证——通知自动链路在锁屏稳态**先在主屏建
   Dashboard 实例、约 150ms 后经「锁屏首投」任务搬运（票 #22 产品路径）落到背屏**
   （logcat：`task-move word=OK taskId=13073 displayId=1 reason=应用内投送未获确认（锁屏首投走任务搬运）`），
   三级兜底（am start + 手工事务）没有用到。证据因此是「锁屏稳态自动上屏」形态。

4. **measure 首测拿错屏的 contentRect**：锁屏首投先在主屏建实例再搬上背屏，logcat 里**两块屏
   的 rear-safe-geometry 各有一条**（主屏 1220×2656 在前、背屏 904×572 在后）；首测脚本取
   第一条 content= 做判定基准，把背屏截图判成越界（假 OUT-OF-SAFE）。修法：取**最后一条**
   rear-safe-geometry（背屏实例）；已复测更正。

5. **`param([string]$X)` 的变量是强类型，同名（不分大小写）再赋数组会被静默转字符串**：
   measure.ps1 首版 `param([string]$Logcat)` + `$logcat = Get-Content ...`——PowerShell 变量
   名不分大小写，`$logcat` 就是 `$Logcat`，4558 行的数组被强制转成**空格拼接的一行字符串**
   （`Get-Content` 单独跑一切正常，只在带强类型 param 的脚本里翻车，极难定位）。修法：
   数组用别的变量名（`$logLines`），读文件直接走 `[IO.File]::ReadAllLines`。

6. **首轮 POST_TEST 的测试通知被系统静默丢弃**：`POST_NOTIFICATIONS` 运行时权限未授权
   （调试旁路不走按钮的权限请求分支），Icon Set 首轮只有 shell 一枚。补拍轮
   `pm grant com.rearcue.poc android.permission.POST_NOTIFICATIONS` 后两枚齐（本应用 + shell）。
   grant 会随重装重置（同 MIUIOP(10020)）。

7. **补拍轮（evidence2）整轮作废（反面记录保留 04/05 截图 + measure2.txt）**：开跑时应用已被
   并行实验留在 **force-stopped 态**——显式广播（`am broadcast -n ...`）被系统**静默丢弃**、
   `cmd notification post` 没有监听者，背屏出的是 Native Rear Screen 天气面（截图即物证），
   measure.ps1 以 `E15-RUN-INVALID（logcat 里没有 contentRect）` 兜住假判定。教训入下一轮
   （evidence3）：**先 `am start` 拉起应用 + `pidof` 校验，落位（Display #1 块有
   RearDashboardActivity）校验通过才开拍**；同一时段并行实验还会杀进程/发通知（logcat
   05:00:46-50 可见别的实验在用本应用投送/清场），取证轮必须自证链路、不能假设应用活着。

8. **补拍轮 2（evidence3）同样作废（反面记录保留 06/07 截图 + measure3.txt）：共用设备上的
   包竞争 + 冻结队列迟到投递**——05:00 前后并行票的实验装走/替换了包（其构建没有本票的
   `rear-safe-*` 词面，logcat 零命中），且被 HyperOS 冻结的应用解冻后**把上一轮排队的广播
   一次性吐出来**（logcat 05:12:42-50 两分钟内四次 onCreate、两次任务搬运、通知补投交织），
   落位检查通过后到快门之间背屏又被 Native Rear Screen 收回（06/07 = 天气面）。
   教训入终轮（evidence4）：**取证段必须在自己的互斥锁内重装本分支 APK**（杀进程清队列 +
   保证构建）**并校验本构建词面 rear-safe-geometry 出现在应用日志**（构建自证），
   **每拍前新鲜 dumpsys 复核落位**（丢了补投一次，再丢作废该拍）。
