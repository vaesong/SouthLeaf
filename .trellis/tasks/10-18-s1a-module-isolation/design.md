# S1-A 实施设计

采用已确认 P03/D-104/D-105/ADR-004。public-api 坐标和 main 不变；admin-security-core 纯 Java；admin-security-jdbc 依赖 core；admin-db-policy 从既有迁移类原样提取 URL/target 校验；CLI 依赖 policy/Flyway/JDBC；admin-runtime 依赖 core/JDBC/policy/Boot，初期 main 在创建 Spring context 前显式失败。

运行中的旧服务不操作；旧 backend/target 不清理、不覆写。新模块各自 target 是隔离候选输出，不需要另复制一套工程。必须验证实际运行 classpath 和旧输出哈希。

现有 JDBC IT 和核心 AdminSessionServiceTest 都引用生产 codec；改为每模块 test source 中的小型确定性 TestSessionTokenCodec，不反向依赖 runtime，也不增加生产共享模块。生产 codec 及其两项测试留 runtime。JDBC 的迁移 SQL 从 CLI 的 test-scoped 普通 jar 获取；无第二份 SQL 副本。

架构验证：核心现有规则测试迁移；公开/管理 classpath 拒绝不该出现的类型/资源；标准库检查器检查 Maven 编译/运行/test 图、jdeps 核心类依赖、最终嵌套 jar 条目及真实测试发现映射。资源迁移用 move_path 保持原字节，编辑前 SHA 守卫。

无 schema/外部服务接口变更；回退仅本任务文件逆向迁移，不清理用户内容。政策提取保持原拒绝集，不混入 S1-B 的 demo TLS 放宽。
