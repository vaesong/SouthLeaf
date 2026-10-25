# S1-C2 JDBC credential persistence and non-Web composition

用户要求继续，普通技术决定自主推进；仅已授权独立本机测试实例可进行管理迁移/测试授撤权，原服务/生产数据/公开授权不动，无提交推送。

交付 V3 admin_auth_event、真实 JDBC CredentialLookup/AuthenticationEventSink、verified-pool 后的非 Web AdminLoginService 接线与版本化精确授权。无默认账号、无 lifecycle CLI、无 HTTP。

验收：原值密码真实 KDF→JDBC→Store→原子会话审计；未知/禁用/错误/竞争统一拒绝；错误哈希和事件/存储故障 fail-closed；事件不泄漏秘密；REQUIRES_NEW 不被外层只读/回滚影响；V2保全；精确 Writer 真实权限负向；旧用例保留；公开/旧运行不漂移。

额外必要范围：旧管理 IT 会自动迁移未来 V3 到原3306历史测试schema，故本批将其移至已授权独立实例（保留21个用例/并发断言）。公开21 IT不变。不通过清理/改V2/改原实例授权绕过。
