# 管理运行隔离与 Writer 最小权限设计

> 文档编号：D-506  
> 文档状态：已确认（S1-B 已实现；本地及独立 MySQL 最小权限验证通过；非生产/登录验收）
> 适用范围：阶段二（S1 管理运行边界）  
> 更新日期：2026-10-18  
> 设计责任：项目架构与数据库运维；涉及阶段范围时由业务负责人确认。  
> 上游需求：A10-A16（仅涉及后台安全、身份与权限边界；不改变经营规则）  
> 上游架构：AT04、AT07、AT08、AT09  
> 关联计划：P02  
> 关联设计与决策：[D-104](../01_总体设计/04_阶段二后台运行时模块边界与组合根设计.md)、[D-505](05_后台安全持久化与事务设计.md)、[D-201](../02_通用后端/01_认证与会话设计.md)、[D-202](../02_通用后端/02_权限与资源访问设计.md)、[ADR-003](../08_决策记录/ADR-003_阶段二管理安全与公开读取隔离.md)

## 1. 设计目标与边界

本设计补足 D-505 的持久化/事务边界，定义管理运行入口、Spring 组件扫描、管理专属配置与 DataSource、启动身份校验，以及 `JdbcAdminSessionStore` 所需的 Writer 直表权限。目标是让公开 Reader 与管理 Writer 在进程、配置、连接池、schema 和数据库身份上互相隔离；启动守卫只验证，不创建库、不修复授权、不提升权限。

公开 Reader 运行时保持不变：不调整其入口、配置、DataSource、连接池、账号权限、端口或运行服务。管理运行时不能把公开 Reader 当作 fallback，也不能因管理启动失败而降低公开守卫。

最初设计阶段限于讨论；用户随后确认 P03 并要求继续推进。S1-A 模块隔离和本文件 S1-B 非 Web 守卫代码现已实现；真实管理 schema/账号/授权与生产部署尚未准备或验收，详见第 10–11 节。

**本设计不包含：**密码验证、登录/限流/会话 HTTP 接口、Cookie/CSRF、安全头、后台 UI、管理员初始化、角色分配、真实管理库账号和权限部署、迁移执行、生产凭据操作或生产部署验收。以上仍按 P02/ADR-003 的后续门禁处理。

## 2. 现状与必须保持的不变量

- 公开入口 `cn.zhinan.uniapp.UniappApiApplication` 位于 `cn.zhinan.uniapp` 包根；`PublicRuntimeConfiguration` 与 `RuntimeAccountVerifier` 属公开运行时。它们及现行 Reader 不在本切片改动范围。
- 当前 `adminsecurity` 代码位于 `cn.zhinan.uniapp.adminsecurity`，落在公开入口默认扫描树内；安全类型目前没有 Spring 自动发现注解，因此未自动注册，但这只是约定，不是包/产物隔离。目标迁移至 `cn.zhinan.adminsecurity`，详见 D-104；本轮不移动代码。
- `JdbcAdminSessionStore(DataSource writer)` 是显式构造的管理适配器，不带 Spring 自动发现注解；它用传入的同一 DataSource 创建 JDBC 与事务管理器。管理组合根必须显式接线，不能让公开入口自动注册它。
- 该适配器当前只读写四张管理表；具体 SQL 依据见第 4 节。以后若改变 SQL、表或事务行为，须重新评审权限矩阵，不能静默扩权。
- `AdminDatabaseMigrationMain` 是显式迁移工具，使用独立配置、迁移身份与 `flyway_admin_schema_history`；运行应用不得自动调用 Flyway、baseline、repair、clean 或种子逻辑。
- 现有 `southleaf_public_it` 管理表测试是共用测试 schema 下的事务验证，不证明正式 Reader/Writer 跨 schema 隔离。不得把该测试结果写成正式管理库授权已通过。

## 3. 运行入口、扫描与配置隔离

### 3.1 两个组合根

目标管理入口为 `cn.zhinan.adminruntime.AdminRuntimeApplication`，位于独立 `admin-runtime` 模块与 `cn.zhinan.uniapp` 包根之外，只扫描 `cn.zhinan.adminruntime`。管理组合配置由该模块显式创建用例和 `JdbcAdminSessionStore` Bean；后台安全核心目标包为 `cn.zhinan.adminsecurity`，保持纯 Java 并通过端口接入适配器。模块图与依赖方向见 D-104，不把整个 `cn.zhinan.uniapp` 或 `cn.zhinan` 加入扫描。

公开入口仍是默认公开 API 入口，仅扫描当前公开运行范围；不导入管理配置，不创建管理 DataSource、事务管理器或 Writer Bean。管理入口也不扫描或导入 `PublicRuntimeConfiguration`。架构测试须分别证明“公开上下文没有管理 Writer/Store”和“管理上下文没有公开 Reader 运行配置”。

目标采用同一仓库内多 Maven 模块、公开/管理独立可执行产物和**不同进程**；保留公开默认 main class 与公开部署行为。单模块双 main class 可作为隔离开发测试的过渡方案，但不作为生产目标。该方案是安全信任边界拆分，不是拆分预约/订单等业务微服务；具体模块图与源集迁移顺序见 D-104。

### 3.2 管理专属配置与连接池

管理进程使用专属配置名/位置（例如 `application-admin.properties`），在 Spring Boot 读取 Config Data 前显式选择；只激活 `admin` profile 不足以证明公开默认配置未被加载。测试必须证明公开 `application.properties` 中的 Reader 设置不会流入管理 DataSource。管理配置只含非秘密默认项和外部变量占位符，不保存账号密码。

建议外部配置契约如下（仅名称，不代表已有凭据）：

| 变量 | 用途 | 校验要求 |
|---|---|---|
| `SOUTHLEAF_ADMIN_DB_URL` | 管理运行库 JDBC URL | 单主机 MySQL；无 URL 内嵌凭据、fragment、重复/未知参数；按 D-505 的小白名单策略校验 |
| `SOUTHLEAF_ADMIN_DB_USERNAME` / `SOUTHLEAF_ADMIN_DB_PASSWORD` | 管理 Writer 登录凭据 | 从受控凭据源注入；不得写入仓库、脚本、JVM 命令行或日志 |
| `SOUTHLEAF_ADMIN_DB_SCHEMA` | 期望管理 schema | 与连接后的 `DATABASE()` 精确相等，且符合第 4 节命名规则 |
| `SOUTHLEAF_ADMIN_DB_EXPECTED_USER` | 期望的 MySQL 授权身份 | 与连接后的 `CURRENT_USER()` 精确相等；不得用 `USER()` 或仅比较配置用户名代替 |
| `SOUTHLEAF_ADMIN_MODE` | 显式运行模式：`demo` 或 `production` | 缺失或其他值启动失败；TLS 规则按第 6 节执行 |

管理 DataSource 必须独立创建并仅供管理组合根使用；禁止复用公开 Reader 池、将 Writer 标记为公共 `@Primary`，或在连接失败时尝试公开库。管理启动须有可断言的配置标识（例如 `southleaf.admin.runtime.config-id`），错误配置源立即失败。

### 3.3 不提前开放未保护的 HTTP

S1 运行隔离/守卫验证阶段没有已实现的安全登录闭环。建议该阶段管理应用以非 Web 模式启动（无 HTTP listener），只验证运行配置并接线安全存储；不提供空壳端口或任何未保护管理路由。待密码验证、会话 HTTP、Cookie/CSRF 与安全错误映射完成并单独验收后，才允许显式启用 Web 模式。公开服务端口与行为不变。

## 4. 数据库身份与最小权限矩阵

### 4.1 身份/用途分离

| 运行对象 | schema | 身份与权限 | 边界 |
|---|---|---|---|
| 公开 API | `southleaf_public` | 现有 SELECT-only Reader | 不得获得任何管理 schema 授权；本设计不改动 |
| 管理运行时 | 生产 `southleaf_admin`；隔离非生产库可用 `southleaf_admin` 后接一个或多个 `_[a-z0-9]+` 段 | 专用、非 root 的 runtime Writer；只授予下表逐表权限 | 不授予公开 schema 权限，不具备 DDL/授权能力 |
| 管理迁移工具 | 同一管理 schema | 单独迁移身份，依 D-505 执行显式迁移/校验 | 不得注入运行进程；不授予公开 schema 权限 |
| 现有集成测试 | `southleaf_public_it` | 测试专用身份 | 仅验证当前测试夹具；不作为生产隔离证据或管理 runtime 默认库 |

管理启动时必须校验实际 `DATABASE()`，并将 `CURRENT_USER()` 与显式期望身份逐字比较；缺值、root、错误 host 匹配结果或不匹配一律拒绝。生产 runtime schema 固定为 `southleaf_admin`；非生产 schema 必须显式隔离并使用上述受限命名，不接受 public/system schema。

### 4.2 `JdbcAdminSessionStore` 运行权限

权限集合以当前适配器 SQL 为准，且不授予对 Flyway 历史表的访问：

| 表 | runtime Writer 权限 | 对应当前操作 |
|---|---|---|
| `admin_account` | `SELECT` | 签发时锁定并检查 enabled/security_version；读取当前账号状态 |
| `admin_account_permission` | `SELECT` | 当前会话读取最新权限集合 |
| `admin_session` | `SELECT`, `INSERT`, `UPDATE` | 当前会话读取、签发、撤销；不做自动清理 |
| `admin_security_audit` | `INSERT` | 会话签发/撤销事务内写安全审计 |

仅允许 MySQL 必需的 `USAGE ON *.*` 作为无数据访问的全局基线；除此之外，启动校验只接受精确的上述表级权限。拒绝 schema/global 通配授权、`ALL PRIVILEGES`、额外表或列权限、角色及有效角色、`GRANT OPTION`、`CREATE/ALTER/DROP/INDEX/DELETE` 等未列权限。任何授权语法/权限输出未被校验器识别，均按拒绝处理，不能“尽量解析后放行”。

`admin_account` 与权限映射表不授予 Writer 更新权限；账号、权限配置和管理员初始化将由独立受控切片设计，不因当前会话存储需要而预授。迁移 DDL 权限属于迁移身份，不属于 runtime Writer。

## 5. 启动守卫与启动生命周期

### 5.1 用例与顺序

管理运行的启动用例为“确认当前连接确实是此环境指定的管理 runtime 身份，并且其权限恰好满足 `JdbcAdminSessionStore` 契约”。输入是外部注入配置与单个管理 DataSource；前置条件为独立管理 schema 和账号已由获授权运维人员准备。输出只表示运行环境通过校验，不创建账号或迁移。

推荐顺序：

1. 管理 main 显式选择管理配置并校验运行模式、必填字段和 URL 策略；不得隐式读入公开 Reader 配置。
2. 构造专属 Hikari DataSource；不调用迁移工具、不创建 schema。
3. 在启动阶段查询 `SELECT DATABASE(), CURRENT_USER()` 与 TLS 会话状态 `SHOW SESSION STATUS LIKE 'Ssl_cipher'`；核对期望 schema、精确账号、TLS 模式。
4. 查询当前身份 grants/roles；逐项归一化后对比第 4.2 节白名单。缺失权限、过量权限、角色或未知输出均启动失败。
5. 仅校验成功后，才将同一 DataSource 显式传给 `JdbcAdminSessionStore` 与其事务管理器；禁止由另一个池构造事务管理器。
6. 在当前非 Web 阶段不打开网络 listener。未来启用受保护 Web 模式时，校验必须在应用进入可服务状态前完成；不能只放到可能晚于 listener 启动的 `ApplicationRunner` 中。

启动校验是 fail-fast 配置门禁，不是数据库授权的替代品。MySQL 实际权限仍是最终执行边界；运行期间未经授权的 grant 变更不得被本地守卫修复或默许。授权发生变更后须重新核验运行身份并重启/再验收，不能把启动时一次通过描述为永久权限监控。

### 5.2 TLS、grants 与失败语义

- 实际连接的 `Ssl_cipher` 必须非空，不能仅凭 URL 写了 TLS 参数判定链路加密。
- `production` 必须使用 `sslMode=VERIFY_IDENTITY`；禁止 `DISABLED`、`PREFERRED` 或降级。隔离的 `demo`/测试环境允许 `REQUIRED` 或更强模式，但不得用于生产验收。D-505 的管理迁移工具仍保持其更严格的 `VERIFY_IDENTITY` 要求。
- grants 校验须覆盖当前身份的全部直接授权和角色状态；白名单之外的 global/schema/table grant、角色或 `WITH GRANT OPTION` 均拒绝。MySQL 输出格式变化、版本不支持必要的身份/授权查询、解析不完整时 fail closed。
- 缺配置、URL 策略失败、库名/账号不匹配、root、无 TLS、TLS 模式不符、权限缺失/过量、数据库不可用，统一导致管理启动失败；不得 fallback 到 Reader、使用内存 store、自动扩大权限或带病开放 HTTP。

## 6. 事务、迁移、密钥与可观测性

Writer 池、`JdbcAdminSessionStore` 的 `JdbcTemplate` 与 `DataSourceTransactionManager` 必须同源；D-505 已定义签发/撤销及安全审计的事务边界，本设计不改变。启动守卫在应用侧验证数据源配置，但不重写其事务语义。

管理 runtime 不自动运行 Flyway。`SOUTHLEAF_ADMIN_MIGRATION_*` 与 `SOUTHLEAF_ADMIN_DB_*` 是两套不同身份/配置；运行账户无 DDL，迁移继续由 `AdminDatabaseMigrationMain` 显式执行，独立历史表和“不自动 baseline/repair/clean/seed”规则依 D-505。公开 V1、公开 Reader schema 与公开配置保持原状。

凭据只经受控本机凭据管理器或部署密钥管理系统注入进程；不放入 `.properties`、`.env`、版本控制、脚本常量、终端回显或命令行参数。日志只记录通过/拒绝类别、配置标识和可脱敏异常分类；不输出 JDBC URL、密码、token、完整 grant 列表或不必要的账号/host。错误对外不暴露 schema 探测、授权细节；当前切片没有 HTTP 错误接口。

## 7. 测试与验收

| 场景 | 通过条件 |
|---|---|
| 公开/管理架构边界 | 公开 Spring 上下文没有管理 DataSource、Writer、事务管理器或 Store；管理上下文不加载 `PublicRuntimeConfiguration`/公开 Reader |
| 配置来源 | 管理配置缺失或误加载公开配置时启动失败；`SOUTHLEAF_ADMIN_*` 缺项不使用默认值 |
| 身份 | 实际 schema 不符、`CURRENT_USER()` 不符、root、空身份均拒绝 |
| TLS | 无有效 TLS cipher 拒绝；production 非 `VERIFY_IDENTITY` 拒绝；允许的非生产模式必须显式标注 |
| grants | 恰好四表权限矩阵通过；缺一项、额外 global/schema/表权限、角色、grant option、未知 grant 语法全部拒绝 |
| 生命周期 | 校验失败时 Store/服务不可用；未来 Web 模式下失败不得留下可接受请求的 listener |
| 真实 MySQL 隔离验证 | 仅在显式提供的隔离管理测试 schema/账号上验证 `DATABASE()`、`CURRENT_USER()`、TLS、实际 grants 与拒绝项；不连正式库，不复用公开运行库 |
| 回归 | 公开 Reader 既有验证行为保持不变；任何共享策略抽取必须有等价回归证据，不以本设计作为修改 Reader 的理由 |

grant 解析器先以受支持 MySQL 版本的固定输出样例做单测；未知格式必须拒绝。真实授权集成测试需等隔离管理测试身份获明确授权后另行执行。缺少该环境时不得宣称“实际 Writer 权限已验收”，也不得拿 `southleaf_public_it` 共库测试替代。

## 8. 设计决策与后续实施边界

本节保留最初实施前复核基线；当时仅更新设计文档。后续 S1-B 编码和本地验证见第 11 节；未迁移真实管理数据库、未部署 Writer 权限、未操作生产账号/凭据、未干预运行服务。推荐采用 [D-104](../01_总体设计/04_阶段二后台运行时模块边界与组合根设计.md) 的同仓库多模块、多产物、分进程方案；同一 Maven 模块内的双 main class/profile 只适合短期隔离开发或测试，不作为生产交付目标。

设计基线明确如下：

1. 生产 runtime 与迁移工具使用独立产物、进程、配置命名空间和凭据；公开 API 产物不携带管理 Store、管理运行配置或管理迁移 CLI。
2. 生产 runtime 一律使用 `sslMode=VERIFY_IDENTITY`；隔离的 demo/测试可显式使用 `REQUIRED` 或更强模式，但不能据此宣称生产证书身份已验证。管理迁移 CLI 按 D-505 始终要求 `VERIFY_IDENTITY`。
3. 在密码验证、会话 HTTP、Cookie/CSRF、限流和安全错误映射完成并验收前，管理 runtime 保持非 Web，不打开管理 HTTP listener。
4. 实际 schema、`CURRENT_USER()` 预期值、账号 host 与凭据属于获授权运维配置；本文不虚构、不预置任何真实身份或秘密。

后续实现顺序遵循 D-104 §9 与本文件第 5 节：先保持公开行为不变地完成模块/产物边界，再接入管理配置、Verified DataSource 与启动守卫；实际非生产 MySQL 授权验证需要隔离账号和明确授权，生产验证属于 P02 的 2B/运维门禁。

## 9. 与后续凭据/发布切片的契约分界

执行顺序见 [P03](../../计划/P03_阶段二分批交付与编码执行计划.md)，技术取舍见 [ADR-004](../08_决策记录/ADR-004_阶段二分批交付与后台安全闭环.md)。本文四表矩阵专属 S1-B 的安全会话 Store，不是阶段二所有数据库能力的总白名单。

- S1-C 新增认证失败审计时，依据 D-201 的实际增量 SQL 显式增加认证事件表 INSERT 权限，并更新版本化 expected grants 与负向测试；不得先放宽四表校验器等待未来需求。
- 初始化/改密/禁用/权限变更由独立账号 CLI、身份和连接池执行；runtime 仍没有账号 UPDATE、DDL、GRANT 能力。CLI 完整最小授权矩阵是 S1-C G1，不沿用迁移身份。
- S2 的 business/publication Writer 是不同 adapter/身份/连接池；publication Writer 可按 D-507 在同一 MySQL 连接上访问精确私有发布状态表和公开投影表，不复用安全 Store，也不借用 Reader。
- grants 白名单必须与每个产物的迁移/SQL 版本对应，缺失、过量、未知一律拒绝。只有获授权的隔离身份验证能证明真实权限；真实身份验证前仍不创建账号或执行授权；代码验证见第 11 节。

## 10. S1-B 编码前精确契约（2026-10-18）

用户已确认 P03 并要求继续推进；第 1–9 节中的“当前只设计”和旧包位置为历史分析记录。模块隔离已由 S1-A 完成，本批只实现非 Web 守卫，不创建/授权任何真实数据库身份。

- 六个管理环境变量为本批唯一凭据/目标输入；未知 SOUTHLEAF_ADMIN_*、Spring/config/profile/logging 外部覆盖被拒绝，不因同进程有公开变量就 fallback。Spring 环境移除默认 system/env 属性源，只使用显式 bootstrap 配置和唯一 classpath 管理配置。无宽扫描/自动 DataSource/Flyway。
- demo 只接受带后缀的 southleaf_admin_* 隔离库；production 固定 southleaf_admin。运行 URL 的 sslMode 必填，demo 允许 REQUIRED/VERIFY_CA/VERIFY_IDENTITY，production 仅 VERIFY_IDENTITY。允许的非秘密 URL 参数进一步约束为 UTC、false public-key retrieval、1000–15000 ms 连接/socket 超时、UTF-8；缺省补为 5000 ms，不接受未知/重复/编码别名。迁移 CLI 原有更严格规则保持。
- JDBC 查询固定、有超时、有行数/长度上界；读取 CURRENT_ROLE() 和 @@GLOBAL.mandatory_roles，分别要求 NONE/空；SHOW GRANTS 拒绝角色授予，即使角色未激活。支持 MySQL 8.4 backtick grantee/table 输出，未知格式失败，不做近似容错。
- grants 必须出现一次 USAGE ON *.* 与四表对应的六项精确权限；拆分同表不同权限行可合并，重复权限、额外表/列/全局/schema 授权、grant option 和任何其他语法都拒绝。实际 grantee 必须与期望 CURRENT_USER 完全相同。
- 私有池最大 4、minIdle=0、池等待 5 秒、验证超时 2 秒、JMX 禁用。验证前不发布任何 DataSource/Store Bean；成功后的 wrapper 不提供裸池 getter，Store 和事务管理器同源；失败/上下文关闭必关闭池。
- 默认入口非 Web 存活至关闭；仅增加 --check 只读验证并退出，拒绝其他参数。专属日志配置关闭库级原始输出，主入口仅记录稳定成功/失败代码，不打印 URL、秘密或异常 cause。不提供绕过守卫的测试环境开关。

验收拆为 L1/L2（策略、元数据 double、组合根、真实 jar 负向启动）与 L4（独立 MySQL 身份/实际权限）。L1/L2 通过不关闭 L4；新建测试库/用户、迁移或 grants 需用户单独明确授权。生产证书校验、真实管理登录及完整 S1 均不由本批自动验收。

## 11. S1-B 实施与门禁状态（2026-10-18）

第 2 节旧包路径为 S1-A 之前的历史观察；当前独立 admin-runtime 已有显式配置组合根，不使用组件扫描。第 10 节契约已编码：共享 URL shape parser 保留迁移严格规则，新增 runtime target/grants 纯策略；不可外部构造的 VerifiedAdminDataSource 只在身份/TLS/直接 grants/角色验证后接线 Store；同一已验证 DataSource 只取一次再传入 JDBC/事务管理器。无裸池 Bean，无 HTTP/Flyway/SQL 初始化。

完整 verify 为 200 单元/架构 + 原 39 MySQL IT，全部通过；真实 jar 24 项负向启动与脱敏检查、公开回归、模块产物边界通过。**真实管理身份和实际最小权限尚未验证，不能判定 S1-B 整体安全验收完成。** 元数据 double 只证明控制逻辑；既有共库 IT 不代替隔离证据。

[实现/复验指南](../../开发与演示/阶段二S1-B管理启动守卫与验证指南.md)；[证据](../../开发与演示/证据/stage-two-s1b-runtime-validation.json)。下一步需单独授权隔离测试环境/身份准备；不变更现有账号授权、服务器全局角色/TLS 设置或运行服务。S1-C/D 仍未实施。

## 12. 实际 SQL 与权限一致性修正

真实隔离 Writer 发现原账号 FOR UPDATE 需要额外权限，与本设计 SELECT-only 冲突。用户已委托助手自主决策；采用 D-505 §8 的 FOR SHARE 账号行锁，维持四表六权限，不给 runtime 账号更新权。须以真实并发签发、改密/禁用竞争及最小权限 Store 验证闭环；会话撤销 FOR UPDATE 保持。修正验证未完成前不标 S1-B 通过。

## 13. 独立验证最终结果

用户授权的独立 MySQL 8.4.11 实例已完成 29 个真实场景，最终 j512 PASS；j509 共享锁修正后完整 200 单元/架构 + 42 MySQL IT PASS；j513 最终 jar 24 启动负向 PASS。账号 FOR SHARE 保持 SELECT-only，真实 Store 签发/读取/撤销及审计已成功；全局/表/列/角色/跨库权限反例与证书链、主机名拒绝已验证。默认非 Web 进程正常退出后连接归零。

第 11–12 节的待授权/待修正状态为实施过程记录，现已关闭本地门禁。原服务与授权、公开产物和 V1/V2 保持，独立实例正常关闭。测试 production 模式/本地 CA 不是生产部署验收；后续 S1-C/D 按新 SQL/权限契约另行验证。当前结论及失败恢复详见 S1-B 最终指南和证据。
