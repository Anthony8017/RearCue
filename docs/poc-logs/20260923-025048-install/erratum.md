# erratum -- aborted run (tooling bug, no device facts claimed)

2026-09-23 02:50 的 install session 在 `01-install.ps1` 第 37 行即中止：`$build = @(& $gradle ...)`
把 `[switch] $Build` 参数变量（PowerShell 变量不分大小写）重新赋值成数组，参数绑定把
`System.Object[]` 强转 `SwitchParameter` 直接抛错——脚本从未走到 adb install。
本轮已修（改名 `$buildLog`，注释留痕）。设备侧无任何实验动作（仅 session 头的 getprop 与
`logcat -c`），产物原样保留、不作证据。干净重跑见同日 `-install` / `-lock-survive` / `-wake-cost`
三个 session。
