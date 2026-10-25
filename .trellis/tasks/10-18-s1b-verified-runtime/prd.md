# S1-B verified non-Web runtime

用户已确认 P03 并要求继续推进。范围：D-506 独立配置、Hikari 连接池、真实 DATABASE/CURRENT_USER/TLS/grants 校验、验证后才发布 Store、失败关闭和非 Web 生命周期。

不做：HTTP/密码/账号管理、生产部署、创建 schema/user、GRANT/REVOKE、运行迁移、停启原服务、提交推送。真实独立管理账号正向验证需另获授权，缺环境标 BLOCKED，不能以共库 IT 或 mock 通过替代。

验收：错误/缺配置、公开配置 fallback、root/身份不符、无 TLS/弱生产 TLS、权限缺失/多余、列权限、角色/强制角色、未知授权语法全部拒绝；验证失败连接池关闭且无 Store；正确元数据夹具可创建同源 Store/事务管理器；最终包无公开/迁移/Web 内容；原 136 后端与 53 前端测试回归。迁移策略仍严格 VERIFY_IDENTITY。
