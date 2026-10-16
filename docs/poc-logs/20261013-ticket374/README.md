# #374：重装首次剪贴板连接

日期：2026-10-13

## 交付

- 工作分支：`codex/spec0034-ticket374`，基于集成分支 `c7470a3`；代码提交 `ee4a02a`，最终验证前合并集成分支 `0aab70b`，验证代码快照 `4308c7f`。
- 复用 `ClipboardBridgeImporter.inspect`；安装后首次检查可越过旧内容摘要和同地址判定，其他格式、验证、保存与异步失效规则沿用同一入口。
- 配置已加载、主屏 APP 有焦点、Agent Mirror 开启且未连接才检查。首次机会在检查内容时消费，失败不循环；后续新内容和明确的手动重试沿用既有规则。
- 设置提示明确：重装首次尝试当前剪贴板；输入法历史条目由机主先选中并复制。

## 安装期识别

使用手机 PackageInfo 的 `firstInstallTime:lastUpdateTime` 作为安装期标识；覆盖重新安装 APK 时保留首次安装时间、更新最后安装时间，普通 APP 重开不改变标识。更新 APK 也会获得一次首次检查机会。平台字段定义见 [Android PackageInfo](https://developer.android.com/reference/android/content/pm/PackageInfo#lastUpdateTime)。

已消费的安装期通过 AtomicFile 保存到 `noBackupFilesDir/bridge-clipboard-installation`，独立于既有内容摘要；该目录不参加自动备份，恢复旧内容摘要不会把新安装误判为已检查。见 [Android Context.getNoBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir())。

配置未加载、未获得焦点、镜像关闭或已连接时不消费首次机会，也不联网、不保存。首次符合格式时最多验证一次；普通文字、混合文本、裸域名、用户信息、缺凭据、额外路径、查询参数和控制字符都先被过滤。

## Red → Green

测试 seam 已由机主确认：既有 `ClipboardBridgeImporter` 公开导入行为，输入安装期、内容与连接事实，观察验证次数和保存结果；不读取私有字段或断言源码布局。

| 片段 | Red | Green |
| --- | --- | --- |
| 恢复旧摘要后，首次仍验证一次；失败保留配置，重复事件与普通重开不再验证 | [1 项行为失败](01-restored-content-behavior-red.txt)，首次被旧摘要忽略 | [通过](01-restored-content-green.txt) |
| 与已配置地址相同的候选，首次仍验证并保存一次 | [1 项行为失败](02-same-address-red.txt)，同地址被忽略 | [通过](02-same-address-green.txt) |
| 配置未加载或页面无焦点时保留首次机会 | [1 项行为失败](03-ready-red.txt)，初始化阶段提前导入 | [通过](03-ready-green.txt) |

补充回归覆盖真实临时文件持久化后的普通重开、覆盖安装、新安装且恢复内容摘要、无格式内容只消费一次、镜像关闭/已连接和手动重试限制，以及首次验证被新内容、手动配置、开关变化、链路恢复取代。既有判例全部保留；网络契约沿用 `BridgeConnectionVerifyTest`。

## 最终验证

- [`:app:testDebugUnitTest :app:assembleDebug`](05-app-test-build.txt)：198 项 APP 单测，0 失败/错误；APK 构建成功。
- [`ClipboardBridgeImporterTest` + `BridgeConnectionVerifyTest`](04-importer-regression.txt)：13 项导入行为 + 1 项网络契约通过。
- [测试数量](tests-summary.txt)由最终 JUnit XML 统计；本票新增 7 项行为测试。
- [`node --test tools/bridge/bridge.test.mjs`](06-bridge-contract.txt)：55 项通过；独立随机测试端口，不切换生产桥。
- `git diff --check`：通过。

APK：`app/build/outputs/apk/debug/app-debug.apk`（不纳入版本控制）

SHA-256：`AB28B1FB47C6E055D551E5AB253E7A4D1DA3BA74F3AE7578E0D22B463FB8885E`

未安装手机、未切换生产桥、未执行实机焦点/安装时间/备份恢复/剪贴板验收；这些检查归 #375。单测与构建不替代两端部署和真实链路成功证明。
