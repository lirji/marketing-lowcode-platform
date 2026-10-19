# 前端Docker部署与偏好沉淀（2026-10-19）

用户授权先将今天修改的前端部署到当前Docker，再沉淀前端skill。源版本b6274f3；目标local marketing-r1/console，8084。其余服务不重建，不执行迁移或种子。

- [x] 核对：旧控制台4e847da，今天提交仅前端及文档；源码验证证据见beijing-time等交付记录。内存约11.7GiB。
- [x] 已归档b6274f3构建marketing/console:b6274f3-20261019，配置一致；仅console已重建，构建/启动退出0。镜像sha256:bb99ed6fb34df7fb3c783c6eeb3872e9920073ad1e2b4777b7a25eadfd5a8253。
- [x] 新镜像、容器healthy与/healthz通过；7路由×1440/414共14视图通过，无原始ISO、页面溢出、JS异常或HTTP失败；活动分列、下拉交互及5张关键截图已检查。旧镜像保留可回退。
- [x] 已更新frontend-design共享入口与个人偏好：补齐北京时间、双框防重复、卡片纵排、保存区间距、提示正常流和实际验证检查。Codex/Claude同源、引用有效，quick_validate通过（系统Python缺PyYAML，使用uv隔离依赖完成，未修改项目依赖）。
- Git交付：按既有授权正常合并推送main；最终提交及远程一致性写入本机evidence/GIT_DELIVERY.json和根检查点。

证据目录：/Users/liruijun/.local/share/marketing-deployments/20261019-b6274f3-frontend。48原有改动保持；private文件仅本机保存，不提交配置秘密。新分支chore/frontend-rollout-preferences，基线b6274f3；沿用frontend-design、deployment-execution、skill-creator及发布与数据治理规范。

## 结果与恢复

- 访问：http://127.0.0.1:8084。容器marketing-r1-console-1，镜像来源b6274f3（今天所有前端修改）。本次仅增加交付文档，不需再构建文档提交。
- Docker证据：上述目录中的result.json、evidence/build.log、deploy.log、smoke.json和截图；manifest.json保存旧镜像及回退目标。旧镜像未删除。仅console重建，无数据库迁移或种子操作。
- 已观察的范围：本次7路由真实GET及下拉交互通过；未执行保存/发布业务写入。offer-decision-service部署前后均unhealthy，未将前端上线称作全部后端健康。
- Skill入口：~/.codex/skills/frontend-design/SKILL.md与~/.claude/skills/frontend-design/SKILL.md均指向~/.cursor/skills/frontend-design/SKILL.md；偏好权威为~/.config/engineering-skill-system/frontend-preferences.md。它们是本机共享文件，不复制进项目仓库；修订前备份与修订后SHA见evidence/下的skill文件。
- 偏好将“用户明确要求”与“从修复归纳的检查方法”区分；具体像素值留在项目主题，不将本轮实现截图写成用户验收。
- 48个原有改动SHA核对不变；无新增worktree，无清理。镜像、回退资料与验证证据保留；原4175预览属于此前任务，未停止。
