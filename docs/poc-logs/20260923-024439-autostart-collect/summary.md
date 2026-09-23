# 采集轮补记（票 #27 自启动探针，收口时补）

本轮 = 包清单定位（只读，PC 侧过滤）。关键原始产物：
- `raw-dumpsys-pkg-seccenter.txt`：`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` 的
  intent filter = **`miui.intent.action.OP_AUTO_START`** + `android.intent.category.DEFAULT`（跳转入口的 resolver 证据）
- `raw-dumpsys-pkg-lbe.txt`：`AutoStartManagerProvider`（authority `com.lbe.security.miui.autostartmgr`）+
  `miui.permission.READ_AND_WIRTE_PERMISSION_MANAGER: prot=signature|privileged`（第三方读不到的证据）
- `raw-dumpsys-pkg-rearcue-uid.txt`：本应用包记录。
