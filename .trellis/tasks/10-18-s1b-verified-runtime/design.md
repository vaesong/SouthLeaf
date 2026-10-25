# S1-B design

延续已确认 D-506；实现前细节补充：管理参数只来自六个 SOUTHLEAF_ADMIN_* 变量，拒绝未知管理变量和 Spring/logging 外部覆盖；Spring 环境仅显式 bootstrap 属性及 classpath application-admin.properties，不导入系统/公开属性。仅显式 @Configuration，无组件扫描/自动配置。

production schema 固定 southleaf_admin；demo 必须后缀隔离 schema，不用 production 基库。URL 统一解析形状，迁移旧规则不变；runtime 进一步校验 schema、TLS、禁止 public-key retrieval、UTC、有界连接/套接字超时，生成规范 URL。

bootstrap 私有拥有池，先固定 SQL 读取 DATABASE/CURRENT_USER/CURRENT_ROLE/@@GLOBAL.mandatory_roles、Ssl_cipher 和 SHOW GRANTS FOR CURRENT_USER()。仅接受受支持 MySQL 8.4 backtick 格式、一次 USAGE 与精确六项表权限；未知输出拒绝。池验证完成后产生不可外部构造的 VerifiedAdminDataSource，内部缓存同源 JdbcAdminSessionStore，不提供裸池 getter/Bean。

非 Web 默认存活至关闭；--check 只验证并关闭。日志固定专属配置关闭库级输出；入口仅输出成功/拒绝分类，错误不携带原异常/URL/凭据/grants。Hikari 关闭 JMX，固定连接预算，MySQL 日志接入受控 SLF4J。池/元数据测试 seam 仅包内构造，不提供环境开关或生产替身。

真实数据库正向矩阵本轮不预设身份，不创建库/账号；单测使用明确 JDBC 元数据 double，仅证明控制逻辑。实际权限验收等待独立授权。迁移策略、公有配置和业务 SQL 不改。

## L4 explicit authorization (2026-10-18)

用户在授权选择中明确选择“授权独立本机测试实例（推荐）”：使用现有 MySQL 程序另建仅监听本机的测试实例、独立数据目录和测试证书；仅在新实例创建测试库、专用账号及角色并授撤测试权限。凭据不进仓库或命令行；不安装新软件、不修改或重启现有 MySQL/公开服务，不触及生产数据。

先做只读工具/端口预检，再准备独立本机目录及当前用户访问控制。实例首次启动禁止 TCP，以唯一 Windows 本地共享内存通道设置随机管理密码；随后仅监听 loopback，使用测试 CA/证书。秘密通过受控本机存储与进程环境/标准输入，不使用密码命令行、init SQL 明文文件或普通日志。实际迁移仍走严格 VERIFY_IDENTITY，不为测试降低迁移策略。所有账号、角色和全局角色负向实验仅限新实例。记录每个进程与目录；完成后正常关闭新实例，不停止旧 j8/j354。具体路径/端口/工具依据预检，不猜测现有凭据。

