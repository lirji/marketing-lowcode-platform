# 远程 CI 核验

核验提交：`4ee92170a253524184f7c3f8bbfcccc0f7d4b9db`，源码与 [source.sha256](source.sha256) 一致。本记录为验收后的文档更新，不修改代码和流水线配置。

| 检查 | 状态 | 证据 |
| --- | --- | --- |
| 后端 reactor、合同、架构 | PASS | [CI job](https://github.com/lirji/marketing-lowcode-platform/actions/runs/39117769282/job/117647035027) |
| 前端 lint、单测、构建、浏览器 | PASS | [CI job](https://github.com/lirji/marketing-lowcode-platform/actions/runs/39117769282/job/117647035040) |
| Compose / Helm / 迁移门禁 | BLOCKED | [CI job](https://github.com/lirji/marketing-lowcode-platform/actions/runs/39117769282/job/117647035212)；checkout `lirji/dev-infra` 返回 404，后续检查未运行 |
| CodeQL JavaScript / TypeScript | PASS | [Security job](https://github.com/lirji/marketing-lowcode-platform/actions/runs/39117769374/job/117646938332) |
| CodeQL Java / Kotlin | PASS | [Security job](https://github.com/lirji/marketing-lowcode-platform/actions/runs/39117769374/job/117646938405) |
| 仓库安全扫描 | BLOCKED | [Security job](https://github.com/lirji/marketing-lowcode-platform/actions/runs/39117769374/job/117646938279)；setup 无法解析 `aquasecurity/trivy-action@0.32.0`，扫描未执行 |

两个阻塞均来自本轮未修改的 `.github/workflows` 配置。404 不能区分仓库地址错误与令牌无读取权限，需要核对权威仓库及跨仓读取配置；不能绕过部署检查。安全扫描需单独核对受信任的 Action 版本并恢复执行，不能把 setup 失败当作无安全问题。

本地必要验证已通过、代码与文档已进入 main，但远程整条 CI / Security gates 不能宣称 PASS；这也不构成部署就绪或上线完成。

最终核验：后端 job 7m9s 通过，前端 job 1m12s 通过；两项 CodeQL 通过。GitHub 将 CI 与 Security gates 总结为 failure，分别由上表的两个配置阻塞造成，未将其改写为 PASS。此后仅提交本回执等文档，源码指纹不变；不需要重复运行本地代码测试。

- `remote-ci-final.json` SHA-256：`1aadaccccd3f1a7c9313c482c8f3648c16409f787c0d86cd89ee55c22ccabeea`
- `remote-security-final.json` SHA-256：`d89a5c8ba9cc8e490862c8f2229c0c031e46d5708e007eda36b21a10aee0605d`
- `remote-shared-infra-final.log` SHA-256：`da94afaa153045b24f7bdbead5db93af60eaae3c167c5f2b77d8354916fa97d0`
- `remote-scan-setup.log` SHA-256：`1b536e5e8d396ffdb949339265ed4095d24fa7d8429ee94f507406d1ca12a3cf`
- `remote-backend.log` SHA-256：`a65ec2f64ef4a07719e4beba5402c30a5217b54aee6583abd06416d27fba0016`

原始日志保存在 [EVIDENCE.md](EVIDENCE.md) 所列本机证据目录。
