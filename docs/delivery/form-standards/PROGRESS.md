# 表单与列表规范落实（2026-10-19）

## 目标与授权
用户要求：时间选择组件、输入说明、字段充分直白、搜索与展示字段一致、美观的下拉组件；保留已认可按钮和彩色状态。适用于后续前端规范。复用frontend-design；分支feat/console-form-standards，基线191e797。持续授权提交/正常合并推送main，未授权生产部署。本次不改Docker。

## 清单
- [x] 定位：30余处原生选择框；裂变三个ISO时间文本框；Audience INSTANT条件；列表搜索和列语义。
- [x] S1 共享选择/日期时间组件、输入说明，接入现有页面。
- [x] S2 搜索/字段语义及长期规范。
- [x] S3 构建lint、交互测试、真实预览与桌面/窄屏检查。
- [x] S4 实现ddd131e正常快进合入并推送main，原改动保留；交付记录随本文件提交。

## 当前状态
S1–S3已实现并验证通过：97组件、36浏览器、构建/lint；最终活动提示复验5项通过。详细见IMPLEMENTATION_EVIDENCE.md。S4实现ddd131e已合并推送；最终文档提交与远程一致性回执见证据目录GIT_DELIVERY.json、根检查点。原有48文件保持不变；证据目录：/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-form-standards-20261019-xy_w1zfv。真实预览4175/session77992保留；Docker未变。SKU目录502作为现有后端依赖缺口保留，UI可正常选择时间；不冒充全链路通过。

## 采用规范
frontend-design及interaction-design的表单/搜索/列表，project-adaptation-and-verification，architecture-standards的业务建模与并发安全验证。个人前端偏好使用两端共用的~/.config/engineering-skill-system/frontend-preferences.md。
