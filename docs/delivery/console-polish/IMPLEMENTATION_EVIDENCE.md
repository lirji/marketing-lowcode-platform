# 控制台页面优化：实现与验证

## 用户可见变化
- 活动：原操作列把“就绪/邀请有礼”挤成竖排；改为原生表格、合理列宽、查看就绪和设计菜单。菜单保留Offer/Journey/邀请有礼真实路由，并传递活动上下文。筛选与表格放在同一面板，增加刷新/清除，查询写入URL，关闭创建弹窗保留筛选。
- 资产：原列表没有列标题，整行按钮没有动作。现在提供分类页签、按当前完整API列表搜索、实际条数、明确列名、只读详情与长字段完整查看；展示可读状态和日期。字段/节点目录改为足够宽的纵向布局。
- 运营：查询输入不再被固定150px压缩，查询按钮不再拉满整行；旅程与触达补列标题，无动作记录使用普通列表项。隔离区真实重放动作及确认流程保留。
- 公共外观：沿用Meridian深色侧栏与青绿色主色，调整字号、控件高度、留白、边框、状态标签、页签与页面标题；侧栏内部可滚动，折叠导航有可访问名称。
- 交互：共享PageTabs支持方向键/Home/End及有效tabpanel关联；行菜单支持方向键/Escape/外部关闭和焦点恢复，并通过portal避免被宽表裁切；窄屏在表格自身横向滚动。

## 实现边界
使用frontend-design，复用原React、CSS主题、Button/Modal/Badge及API，不新增依赖、后端、正式契约或业务演示数据。未改业务权限、发布/审核命令与状态判断；未执行真实创建、审核、激活或发奖。
原来未接入的营销日历按钮从活动页主操作区移除；它原本不可用。原有演示模式fixture保留，真实浏览始终读取现有API。

## 验证结果
| 检查 | 结果 | 证据 |
| --- | --- | --- |
| pnpm frontend:build | PASS | build-final.log |
| pnpm frontend:lint | PASS，0警告 | lint-final.log |
| pnpm frontend:test | PASS，27文件/92测试 | unit-final.log |
| pnpm frontend:e2e --workers=2 | PASS，桌面/Pixel 7共30测试 | e2e-final.log |
| 13个路由的桌面/窄屏布局 | PASS，页面无横向溢出；表格/画布内部滚动 | final-browser-run/下26张布局截图 |
| 真实本机API只读浏览 | PASS，6个主页面及资产分类/详情/菜单，22个API响应均成功，0页面异常、0写请求 | live-readonly-result.json、live-*.png |
| 原有工作树保护 | 48个文件内容不变 | protected-files.json |
| git diff --check | PASS | 交付时再次检查 |

证据目录：`/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-console-polish-20261019-_mph2y93`。此报告由当前执行者记录，不冒充独立审查或用户验收。

## 发现并修正的问题
- 窄屏键盘聚焦表格会触发滚动事件，初版菜单因监听scroll关闭而消失；改为跟随锚点定位。首轮29/30，复测30/30。
- 新增组件测试误用了Testing Library不支持的exact选项；已改为精确正则，构建通过。
- 同一项目同时启动默认E2E开发服务与真实API预览时，Vite依赖缓存冲突，分析页曾报React useContext错误。预览改为独立缓存目录后，等待dashboard/series真实响应并重跑全部只读浏览通过。无产品代码绕过。
- 旧E2E的一些隔离场景未mock壳层徽标请求，日志中出现8080连接拒绝；这些是原测试环境请求，30项行为断言通过。真实API预览代理18080且全部响应成功。未修改无关测试来隐藏日志。

## 预览与限制
- 新版预览：`http://127.0.0.1:4175/campaigns`，使用原Docker公开运行配置与真实网关；独立cacheDir。恢复：`node /var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-console-polish-20261019-_mph2y93/start-preview.mjs`。
- 预览进程exec session77992，日志preview.log；此前session56830已正常停止。需要保留预览时不要清理该证据目录。
- 当前Docker控制台8084仍是原部署版本；本任务完成页面实现/Git交付，未替换Docker镜像或执行生产部署。
- 浏览器自动化的写操作只在隔离fixture里验证，真实浏览只做查询。未宣称生产全链路、完整无障碍合规或远程CI全部通过；原CI配置问题不在本次修复范围。

## Git交付与保留项
- 实现提交：`365a7ed`（feat(console): 优化列表布局与导航交互），由feat/console-ui-polish正常快进合入main并推送origin/main。
- 本次未创建worktree；原目录中的README/seed/分析报告48个已有文件保持原样，不纳入本任务提交。
- 保留本地预览进程、临时证据、已有依赖与构建输出；本轮没有自动清理授权，不执行删除。最终提交/远程一致性与保护文件复核见证据目录`GIT_DELIVERY.json`。
