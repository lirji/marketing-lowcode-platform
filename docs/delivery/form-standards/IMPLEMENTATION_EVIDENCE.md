# 表单与列表规范：实现和验证

## 用户要求与实现
- 日期时间：裂变活动3项时间、仿真4项时间、人群INSTANT条件（含IN多值）使用DateTimePicker。日历+时分秒选择，明确北京时间，确认转换UTC Instant；取消/Escape保留原值，空日期不能确认。保留已有微秒/纳秒，不用用户电脑时区解释业务时间。时长仍以秒数输入。
- 下拉：30处页面选择入口统一为Radix Select封装，复用已有选项与稳定value；下拉浮层、选中图标、禁用、空值、长选项、滚动与键盘焦点。新增依赖仅@radix-ui/react-select、@radix-ui/react-popover和react-day-picker及其锁定的传递依赖，未升级已有依赖。
- 填写说明：FieldHint常驻展示，关联aria-describedby；活动名/目标、权益标识/金额门槛、发布范围、运营查询、仿真字段和时间项解释含义、单位、示例与限制。原错误反馈保留。
- 列表：活动ID显式标注，创建人与作用范围、完整年份与北京时间清楚展示；缺失创建人/组织/店铺不再由当前用户信息代填。创建时间与最近更新时间区分。运营实例ID和触达标识与查询提示一致。
- 搜索：活动明确名称/ID/目标范围；资产按分类实际列说明搜索项，按展示值匹配中文状态和北京时间日期，保留名称/ID查询。不新增无后端支持的分页查询。
- 用户认可的按钮层级与彩色状态保留。

## 后续规范落盘
- 仓库：frontend/AGENTS.md（后续前端任务自动读取）。
- 跨项目个人默认：/Users/liruijun/.config/engineering-skill-system/frontend-preferences.md的“已确认的表单与列表规范（2026-10-19）”。两端frontend skill通过共享文件读取，不维护竞争副本；该个人文件位于仓库外。

## 验证
| 检查 | 结果 | 证据 |
| --- | --- | --- |
| frontend:build / lint | PASS，lint无警告 | build-final.log / lint-final.log |
| 全量组件 | 28文件、97测试PASS | unit-final.log |
| 最终活动范围提示小修复 | 5项App测试PASS，构建/lint复验 | unit-app-final.log |
| 桌面/Pixel7浏览器 | 36项PASS，13路由布局截图 | e2e-final.log / browser-results |
| 日期/下拉新交互 | 空值、取消、键盘、弹窗中Escape、边界布局、UTC转换/跨年/小数精度PASS | form-controls测试与截图 |
| 搜索一致性 | ID、中文状态、跨UTC日期的展示值查询PASS | AssetsPage和form-controls测试 |
| 原有工作树保护 | 48文件指纹不变 | protected-files.json |

证据目录：/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-form-standards-20261019-xy_w1zfv。当前执行者的验证记录，不代表用户验收或独立审查。源文件指纹见implementation-fingerprint.json。

## 发现和处理
- 原生select测试需适配弹层选项；已保留原业务断言，改用真实点击。JSDOM仅补浏览器缺失的指针捕获/滚动API，完整交互用浏览器验证。
- 初次组件封装类型及未使用变量问题已修复。并发构建/高并发测试曾导致一个懒加载断言超时，2 workers完整复测97项通过。
- 初次新增浏览器测试34/36通过，2项失败为innerText与textContent换行差异，统一读取方式后36项通过；未降低日期、焦点或边界断言。
- 原E2E隔离场景仍有未mock的8080壳层徽标请求日志；不是本轮UI失败，真实预览连接18080。

## 运行边界
新版预览沿用http://127.0.0.1:4175/campaigns，session77992，独立Vite缓存。重启入口见前轮console-polish交付文档。Docker8084未更新。
真实API只读浏览结果见live-readonly-result.json；裂变设计器现有权益SKU目录接口返回502，页面保留错误提示。本次不修复该后端依赖，写请求均在测试中阻断（含错误上报），无真实保存、发布、仿真或发奖。其他读取状态与页面错误按回执记录。

## 资料与交付
组件API核对[Radix Select官方文档](https://www.radix-ui.com/primitives/docs/components/select)与安装版本的DayPicker类型声明（react-day-picker 10.0.2），未根据过期API假设接入。
Git按用户持续授权独立任务分支交付；仅提交本轮frontend、锁文件和交付文档。原README/seed/分析报告保持不动；未创建worktree、不执行删除或Docker部署。最终合并/push回执：证据目录GIT_DELIVERY.json及根CODEX_PROGRESS.md。

实现提交ddd131e已通过正常快进合并并推送origin/main。真实只读浏览26个API响应，0页面异常、0业务写入；唯一现有依赖异常为benefit-skus 502，错误上报在测试中被阻断。
