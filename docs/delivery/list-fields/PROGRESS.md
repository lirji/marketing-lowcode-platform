# 全部列表字段独立展示（2026-10-19）

目标：所有记录列表一字段一列，不再通过斜杠/点号/上下叠放合并不同字段；保留操作、状态颜色、权限、分页、脱敏及真实来源。用户明确要求全范围页面列表。分支feat/all-list-fields，基线d2794c8，持续授权正常合并推送main，无Docker部署。

- [x] 盘点：活动剩余范围/时间/类型、5类资产、审核队列、发布矩阵、3类裂变运营、发放指令、旅程/触达/隔离消息、设计器权益选择/字段来源/仿真候选/校验问题、总览发布就绪。
- [x] S1 共用纯展示表格及各列表改造；图表/统计卡不当作记录列表。
- [x] S2 规范、表头语义、搜索匹配和长值核对。
- [x] S3 构建lint、组件/浏览器与真实只读预览。
- [x] S4 Git交付与既有改动保护。

证据：/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/all-list-fields-20261019-zhx76vbc，protected.json保存48原文件指纹。采用frontend-design（列表/交互/运行验证）、architecture-standards业务建模/并发安全验证；规则已在本会话读取。4175/session77992预览可复用，8084未变。

实现：RecordTable/RecordTime统一语义表格；活动11列、5类资产、3类裂变运营、发放19列、轨迹/实例/触达/隔离消息、审核队列/职责/已审、发布矩阵、权益选择、字段来源、两类校验问题、候选奖励、节点大纲、总览就绪。图表、选择器选项、导航节点目录和单项就绪检查保持其原控件语义。构建/lint及原有97项单测通过；追加行为与浏览器验证进行中。

验证：构建/lint通过；97项原组件测试通过，最终受影响设计器7项复验通过；原有36项桌面/Pixel7浏览器回归通过；新增4项字段归属/隐私/空值/固定操作与审核、重放确认测试通过。新增fixture首轮遗漏分页查询参数和审核updatedAt，已修正，非产品缺陷。真实4175只读验证26个API响应，无页面异常/业务写入；既有SKU目录502保持显式错误。48原文件SHA256一致。截图与日志见证据目录。实现提交0d31453已从feat/all-list-fields快进合并并推送origin/main，随后同步本交付记录。

交付：无新增worktree；原48文件及忽略的运行数据保持。只读预览4175/session77992继续运行；构建、测试任务均已结束。未删除任务分支或临时证据。证据目录与frontend/apps/console/test-results可在无需复查且预览停止后按需清理，目前保留。最终提交与远程一致性回执见证据目录GIT_DELIVERY.json。

## 后续局部修正：查询类型双边框（2026-10-19）

用户截图中的外层lookup标签与Radix Select同时绘制边框；原选择器仍匹配原生select，未适配组件。fix/lookup-select-border只修改feature-styles.css：组件包装层取消边框/内边距，保留Select自身边框及焦点态，宽度240px。构建/lint通过；1440/414px真实只读浏览器验证单层边框、订单类型切换、Escape焦点恢复、无页面溢出、旅程/触达查询输入框边框保留。证据/tmp/lookup-border-{build,lint,browser}.log与/tmp/lookup-border-{1440,414}.png。未更新Docker，预览4175已生效。

## 后续局部优化：圈选规则tips对齐（2026-10-19）

fix/audience-condition-alignment：将FieldHint从值控件容器移到独立说明行，控件统一38px并顶端对齐；固定有效期列宽，避免24h/1h造成跨行错列。按条件列表容器宽度重排，窄屏的多时间值独占一行；日期组件关联aria-describedby，保留原字段/算子/规则与保存逻辑。项目及共享前端规范补充“控件与tips分层”约定。

验证通过：build/lint，AudienceBuilder及表单控件8项测试；1728/1440/1280/1024/414/320px真实只读页面核对列位置、控件高度、提示与边界；测试fixture覆盖多个日期值添加/取消/删除及提示关联。末次日期窄屏优化复验1280/414/320px通过。无业务写入，原48文件指纹未变。证据/tmp/condition-alignment-{build,lint,unit,browser,narrow-final}.log与/tmp/condition-alignment-*.png；4175预览生效，Docker未更新。
