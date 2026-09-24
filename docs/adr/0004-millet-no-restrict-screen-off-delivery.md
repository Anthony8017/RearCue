# 4. 采纳 HyperOS「省电无限制」用户设置解除熄屏通知冻结

日期：2026-09-24
状态：已接受（#41）

## 背景

Main Display 熄屏后 HyperOS GreezeManager 以 cgroup 冻结 RearCue 的通知监听进程
（`/sys/fs/cgroup/apps/uid_10339/pid_*/cgroup.freeze=1`），系统已收下新通知而监听回调
在观察窗内不出现（#35/#36 症状，#37 同口径判据）。spec #36 要求在 #37–#40 的实机
证据下比较候选、只接最小机制，或给出诚实 no-go。

## 候选与证据（全部同口径 `ex.ps1 -Task screen-off-chain`，串号 94250f9e）

| 候选 | 结论 | 关键证据 |
| --- | --- | --- |
| 系统侧 Wake Keep-alive 注入循环（#38-A） | NO-GO | `20260924-181719-issue38-system-wake-keepalive`：循环全程 rc=0，两 OFF 腿仍 RED-NO-CALLBACK；背屏唤醒不解冻主屏 OFF 进程 |
| shell `cmd activity unfreeze`（#38-B） | NO-GO | `20260924-181859-issue38-system-wake-unfreeze` + `20260924-issue38-unfreeze-probe.txt`：回执 `Unfreezing` 但 cgroup.freeze 仍为 1，接收器不响应；亮屏 Greeze `THAW` 后才变 0 |
| 真正前台服务 specialUse（#40） | NO-GO | `20260924-182255`、`20260924-184312`：`isForeground=true types=0x40000000` 且 Greeze 收到 `onForegroundServicesChanged fg=true`，熄屏后仍 `FZ reason=screen off success`；另占用 allowlist 基线与常驻通知 |
| HyperOS 省电「无限制」（`settings system MILLET_NO_RESTRICT_APP` 含 com.rearcue.poc，#39） | **采纳** | OFF 窗内无 FZ 记录，回调 277–416ms；修复 #43 后全链两轮 GREEN（`20260924-191544`、`20260924-191713`） |

## 决定

采纳 #39 的用户设置为唯一防冻结机制；同时修复 #43（`RearDashboardActivity` 的
`turnScreenOn` 在锁屏兜底把 Activity 建于 Display 0 时唤亮主屏，违反 spec #36 story 8/12）：
manifest 移除 `android:turnScreenOn`，运行期仅 `displayId == 1` 时开屏，`RearDashboardManifestTest`
守卫（变异验证红/绿）。ADR 0001 的 Activity 投送与 ADR 0003 的 Wake Keep-alive 原样保留。

## 后果

- 无常驻通知、无系统侧循环残留、无 root、无需常驻电脑；代价是机主接受该应用后台不受省电限制。
- 设置需机主保持（电量详情 → 省电策略 → 无限制；或 `settings put system MILLET_NO_RESTRICT_APP`）。
  重装后保持（`20260924-180537-install` 实测）；重启后保持未单独实测。
- 原始值存档于 `docs/poc-logs/issue39-millet-original.txt`，回退即恢复该值。
- FGS 分支 `exp/issue-40-fgs`（APK SHA-256 `15d4b674…f620c512`）保留供复核，未进默认构建。
- 主验收仅覆盖合成白名单通知；真实飞书新通知未在熄屏窗内单测，见 #41 评论标注。
