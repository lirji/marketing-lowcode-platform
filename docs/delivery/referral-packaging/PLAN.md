# 裂变服务可选打包入口

仅补运行模型：复用已有非root JVM Dockerfile，不新建公共组件；Compose新增referral profile，默认不启用、无host port、固定OIDC/DEV头关闭、专属REFERRAL_DB/MIGRATION配置。Helm服务enabled=false，所有Deployment/Service/HPA/ServiceMonitor遵守enabled；显式启用才渲染，专属Secret键，网络仅同平台内部8090，不进入网关或Ingress。

application.yml补PORT映射及健康探针，不开放业务Ports或默认调度。共享DB初始化、真实账号、KMS/身份/许可、生产参数仍待确认；不执行部署、共享迁移或镜像下载。只做离线Compose/Helm模型和定向配置验证，保留原R1服务渲染兼容。新增10th数据库服务不能由旧9库迁移报告冒充已验收。
