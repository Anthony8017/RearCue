# Erratum（事后标注，2026-09-22 review 收口时补写；非本轮原始输出）

本轮是 E9 的 display #0 对照组：窗口实际加在**主屏（display 0）**并成功撤下。
`e9-overlay.txt` 里的 verdict 文案 "overlay window really on the rear display" 出自当时的
脚本旧版（PASS 文案硬编码 "rear display"）；收口后的脚本按目标屏措辞
（"on the target display 0"）。判定本身（加窗成功 + dumpsys 可见 + 撤除干净）不受影响，
`dumpsys-window-before-remove.txt` 里 `Window{... u0 RearCueOverlayProbe} mDisplayId=0` 是原始事实。
