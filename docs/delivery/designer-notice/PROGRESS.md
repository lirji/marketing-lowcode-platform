# 设计器提示遮挡修复（2026-10-19）

- 目标：修复Offer/Journey提示条覆盖标题按钮与画布。基线b4347a9，任务分支fix/designer-notice-overlap。
- 已实现：移除designer-notice绝对坐标与断点偏移，提示按正常文档流依次排列；统一留白；标题不压缩，长内容时设计器可滚动，画布最低320px。项目规范同步。
- 验证通过：build、lint；原有Playwright 40/40；Offer/Journey在1440/1024/414下单提示及本地保存后双提示共12个布局检查，无重叠/页面横向溢出，保存及线性大纲可点击。浏览器独立上下文保存本地草稿，未调用业务写接口。
- 已查看桌面Offer单提示、窄屏Journey双提示截图。证据目录：/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/designer-notice-20261019-134n3tj3
- 保护原有48个文件；不部署Docker，不改接口/状态/业务数据，4175预览保留。Git最终结果记录于根CODEX_PROGRESS.md当前任务标记。
