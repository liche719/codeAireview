# 安全规范

1. 禁止在代码中硬编码 `password`、`token`、`secret`、`apiKey`、`privateKey`。
2. 敏感配置优先使用环境变量、配置中心或密钥管理。
3. 日志中不要输出完整密钥、token、Webhook secret。
4. 外部输入参与路径、SQL、排序字段时必须做白名单校验。
5. Webhook、第三方回调和签名校验必须优先失败关闭。
