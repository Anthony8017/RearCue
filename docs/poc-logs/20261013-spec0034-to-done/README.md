# Spec 0034 to-done 执行记录

日期：2026-10-13。
集成分支：`codex/spec-0034-home-clipboard`；[Draft PR #376](https://github.com/Anthony8017/RearCue/pull/376)。

## 各票结果

| 工单 | verdict | 实现提交 | 遗留 |
| --- | --- | --- | --- |
| #373 主屏主页归位收口 | 代码已收口，部分 AC 待设备 | `94ec7c0` | 一屏、大字体、安全区、返回、设置写回及批准的实机证明 |
| #374 重装首次剪贴板连接 | 代码与行为测试完成 | `ee4a02a` | 安装时间、焦点与旧摘要恢复的实机证明归 #375 |
| #375 两端部署与实机验收 | 受阻、未执行部署 | 无 | 手机 ADB 连接与实际手机操作 |

单一审查修复实现：`0e77b7d`；合并审查修复的代码快照：`25c56df`。最新 main `4a5f4fc` 已合入，保留显示优先级、长按清通知与播报改进。

## 验证

- 全集成检查见 [原始结果](integration-before-review-fixes.txt)：当时 994 项 JVM 测试及 APK 构建通过。
- 审查修复后重跑 APP 检查与最终 APK 构建，[原始结果](integration-final-app.txt)：205 项 APP 测试通过。其他模块源码在修复中未变，沿用全集成有效结果。
- [各模块有效结果](test-counts.json)合计 1001 项：APP 205、rear 307、agent 168、core 297、notification 24；失败与错误均为 0。
- 桥 55 项隔离测试通过，见 #374 的验证记录。其桥源码对象与当前集成相同，均为 `4856e66a3e1d949aa7aa3637acc3b6d1bf28e20c`，未无故重复桥测试。
- 差异检查通过；双轴发现各 1 项 P2，均修复并复核通过，见 [审查](review.md)与 [red-green 证据](../20261013-review-fixes/README.md)。
- 最新 APK 在 `app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`605da730a3b14f524a5d646fda2be6f5ba3d0a7b889f5482708ebc7b7fdc149e`。APK 是生成文件，不纳入版本控制。

## 实机卡点与恢复

ADB 连续 3 次恢复检查无设备，额外重启 ADB daemon 后仍没有设备。手机未安装本次 APK，生产桥未更新。当前生产 checkout 仍为 `607d626`，本地 `/health` 为 200；没有停计划任务或硬杀桥。

已按 to-done 通过命命机器人向机主发送卡点消息，要求将手机用 USB 连接电脑并允许调试。设备连接恢复后，从现有集成分支与 #375 继续：先补 #373 的设备 AC，再进行可恢复的安装/恢复去重验证和受控桥部署。保留工作进度，不重做已完成实现。

不会把构建、静态检查、公开行为测试或 PR 发布当成手机部署成功。全部设备 AC 完成前保持 PR 为 Draft，工单仍保持 open。
