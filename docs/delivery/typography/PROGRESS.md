# 控制台字号统一（2026-10-19）

分支fix/console-typography，基线3c0332b。用户要求各页面统一字号；复用frontend-design及既有主题，不改变接口/权限/保存规则，不部署Docker。

- [x] 盘点：两份公共CSS、登录CSS、图表内联字号；局部覆盖与默认继承造成同类文字不一致。
- [x] 实现与规范：标题26（小屏24）/区块16/弹窗18/正文表单表格14/提示12/指标28（小屏24）；品牌标记单独20。
- [x] 验证：构建、lint通过；原有Playwright 40/40通过；15路由 × 1440/414共30个真实接口只读视图核对，页面无横向溢出，标题/控件/表格/提示/图表字号符合规范。设计器状态覆盖和日历星期默认比例字号发现后修正，双视口弹层/日历/设计器定向复测通过。
- [x] Git交付：实现提交29970a4已由任务分支正常快进合并main并推送origin/main；保护48原文件，不清理文件/分支。此后仅追加本交付记录，不改变已验证实现。

证据/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/console-typography-f4irh3g3；4175/session77992预览继续使用。采用frontend-design、visual-design与已读架构业务建模/并发安全规范。

## 验证边界与证据

- 实现：styles.css语义变量及默认继承；feature-styles.css/login.css局部字号全部引用变量；AnalyticsPage轴刻度引用辅助字号。项目frontend/AGENTS.md与两端共享个人前端规范同步。
- 证据目录内包含audit.json（初次30视图检查）、overlays.json（最后覆盖/日历/弹窗复测）、构建/lint/40项E2E日志、浏览器验证脚本与桌面/窄屏截图。初次audit中的4条状态字号偏差已在overlays复测消除。
- 真实接口保留原有benefit-skus 502；client-error收集入口404不影响渲染，定向复测屏蔽该遥测。未保存业务数据，未伪造成功状态，不将已有服务错误归为本次字号问题。
- 48个原有改动文件SHA256保持不变，不夹带提交。不新建worktree，不删除临时证据/预览。
- 最终Git引用与远程状态在CODEX_PROGRESS.md当前任务标记记录；本文件实现与验证证据随任务提交。
