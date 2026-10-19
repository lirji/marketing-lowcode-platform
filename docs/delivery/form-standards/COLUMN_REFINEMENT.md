# 活动字段分列（2026-10-19）

用户明确要求活动名称、活动ID、目标独立展示，便于一眼定位。
- 已实现：独立三列、分别分配列宽，ID/目标可换行；保留活动类型、状态、范围、时间和操作，展开详情跨7列。窄屏只在表格内部横向滚动。
- 已验证：构建、lint、既有collections桌面/Pixel7共6项浏览器回归通过；真实4175预览已目视检查，搜索/菜单保留。
- 规范：frontend/AGENTS.md与两端共享frontend-preferences.md追加“关键字段独立列展示”。
- 证据：/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/campaign-list-columns-_hyy5jut；原有48文件指纹不变。没有新建worktree、删除数据或更新Docker。
- 分支fix/campaign-list-columns，基线4033094；按持续授权正常合并推送main，最终SHA/远程一致性见根CODEX_PROGRESS.md与证据目录GIT_DELIVERY.json。
