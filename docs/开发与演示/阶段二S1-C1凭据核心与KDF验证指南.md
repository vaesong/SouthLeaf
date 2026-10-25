# 阶段二 S1-C1 凭据核心与 KDF 验证指南

> 日期：2026-10-18；状态：C1 代码与本地验证完成。**不是整个 S1-C 完成，也尚不能从运行中的后台执行密码登录。**
> 计划：[P03](../计划/P03_阶段二分批交付与编码执行计划.md)；契约：[D-201](../设计笔记集/02_通用后端/01_认证与会话设计.md)；[机器证据](证据/stage-two-s1c1-credential-validation.json)。

## 1. 本批交付与未开放边界

- `admin-security-core`：凭据快照、原值输入边界、lookup/verifier/hasher/认证事件端口及 `AdminLoginService`。主代码仍仅依赖 JDK。
- 新增普通 jar `admin-credential-crypto`：Spring Security Argon2id 实现与 Bouncy Castle provider。它只向内依赖核心端口，不依赖 JDBC、Boot runtime 或公开 API。
- 当前 reactor 为七个模块。`admin-runtime` **尚不依赖或接线 crypto/login**；没有虚假持久化事件 sink、默认账号、默认密码、HTTP 或账号管理入口。
- 既有账号标识校验增加点号，登录保留已有内部 1–64 位 ASCII 标识的兼容性，大小写敏感、不 trim。新建账号 3–64 的独立策略留给 C3；密码创建策略与本地泄露密码拒绝表也未冒充已完成。
- 本批没有新迁移、没有修改 V1/V2 或产品 grants 矩阵。独立实例只重跑原 S1-B 授权范围内的测试夹具；没有修改原服务器、公开运行库或生产凭据。

## 2. 登录编排与失败契约

`login(accountId, char[] password, UUID requestId)` 不接受外部 `verifiedVersion`。requestId 由可信调用方生成。

1. 拒绝空、过大或未配对代理项输入，先于复制、查询和 KDF。登录最多 128 个 Unicode scalar、UTF-8 512 字节；不强加创建时的 15 字符下限，不裁剪、不归一化。
2. 有界但账号格式错误、未知、禁用：执行当前 profile 的 dummy，再统一拒绝。dummy 即使匹配也不返回身份。
3. 启用账号：快照 ID 必须与查询键完全一致；KDF 在锁外执行。坏密码统一 `AdminAuthenticationException`。
4. 成功核验后，向原 `AdminSessionService` 传递本次快照版本；Store 仍在事务内重检 enabled/version，签发与会话审计一起提交。竞争导致旧版本失效时拒绝，不重试绕过。
5. `upgradeRequired` 在签发前追加运维提示，不在线重写 hash。提示意味着凭据已匹配且参数需升级，**不是登录成功事件**；之后的版本重检仍可能拒绝。
6. lookup、损坏 hash、容量不足、事件持久化或会话存储错误不能伪装成坏密码/账号不存在。事件 append 失败不发 token；成功签发审计继续由既有 Store 同事务负责。

认证事件仅携带白名单类型、已知内部账号 ID 或 null、UUID 和时间；未知输入账号、密码、hash 不进入事件。核心清理自己克隆的 char[]，不修改调用方数组；调用方仍须清理原数组。

**限制：**Spring 库内部会生成 String/char[]，不能承诺所有明文在 GC 前可擦除。dummy 只与当前 profile 同档；历史 profile 耗时可能不同，不宣称恒定时间或完全消除时间侧信道。尚无 HTTP 状态码映射、外部限流或可调用登录端点。

## 3. 维护库与严格资源边界

| 项目 | 本批值 |
|---|---|
| Spring Security crypto | 7.1.1，沿用 Boot BOM，不引入整个 Security Web 栈 |
| Spring Core | 7.0.9，显式声明；孤立 crypto jar 不会自动带齐所需工具类 |
| Bouncy Castle provider | bcprov-jdk18on 1.86，固定版本 |
| 另两项解析运行依赖 | commons-logging 1.3.6、jspecify 1.0.1 |
| 新 hash | Argon2id v19；m=65536 KiB、t=3、p=1；随机 salt 16 字节、输出 32 字节 |
| 仅校验的旧 profile | m=19456、t=2、p=1；匹配才返回 upgradeRequired |
| PHC 输入 | 只接受上述两档、固定长度、规范无填充 Base64；先校验再调用库 |
| 并发 | 同一应用 classloader 的共享静态预算最多 2 个 KDF；tryAcquire 立即拒绝，无等待队列 |

不接受 Spring 较低的默认参数、未知算法、任意“更强”参数、额外字段、不规范编码或超大参数。拒绝不是降级到其他密码算法。预算覆盖 hash、verify 和 dummy，并在异常后释放；多 JVM/多个隔离 classloader 不共享额度。

低层 `hash` 只实施资源边界，**不是可直接用作账号创建/重置策略的接口**。C3 必须先执行长度、标识、拒绝表及运维授权策略。

依据：Spring Security 7.1.1 的 [Argon2 实现源码](https://github.com/spring-projects/spring-security/blob/7.1.1/crypto/src/main/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.java)、[OWASP 密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)、[Bouncy Castle 发布入口](https://www.bouncycastle.org/download/bouncy-castle-java/)。本批参数不是 RFC 9106 的 p=4 原样配置；没有手写 KDF。

## 4. 实际 KDF 负载

最终基准 j534 在停止临时候选服务、完成独立实例回归后执行；原服务仍运行。JDK 21.0.12、可见处理器 16、`-Xms128m -Xmx512m`；先生成一次合成 hash，预热 3 次验证，再做单并发 8 次和双并发 4 组共 8 次。

| 模式 | 中位数 | 本批样本最大值 |
|---|---:|---:|
| 单并发 | 223.699 ms | 265.013 ms |
| 双并发每次核验 | 353.471 ms | 368.798 ms |

5ms 采样的已用 Java 堆峰值为 **352 MiB**，堆上限 512 MiB；双 KDF 的名义 Argon2 内存为 128 MiB，但不能用它替代 JVM/数组/GC 的总预算。

这是共享开发机上的小样本工程基准，**不是生产容量、P95/P99、进程 RSS 或长期压力验收**。上线硬件、完整管理运行时、持续负载和外部限流仍需复验；不据此自动给生产进程设置堆大小。

## 5. 验证结果

| 检查 | 结果与范围 |
|---|---|
| 完整 Maven verify | j525：234 单元/架构 + 42 真实 MySQL IT，0 失败/错误/跳过 |
| 本批新增 JUnit | 22 核心编排 + 12 crypto；原测试源保持，未删除旧断言 |
| 独立向量 | argon2-cffi 25.1.0 生成的两档 UTF-8 向量；覆盖 é、组合 emoji、末尾两空格，错误密码/分解 Unicode 不匹配 |
| 资源与故障 | 畸形/超大 PHC 拒绝；两个共享槽已占用时第三个 adapter 立即拒绝；异常释放槽；事件/查询/Store 故障不成功 |
| 模块与最终产物 | 七模块 compile/test 图无环；core JDK-only；crypto 向内依赖；public/runtime 无新 KDF/provider；2 项结构负向自测 |
| 最终管理 jar | j532：24 项启动负向，输出脱敏；公开启动两项负向也通过 |
| 真实受限身份 | j531：29 项独立 MySQL 场景，含 Store、精确权限/TLS 和非 Web 正常关闭；不是密码 JDBC 登录验收 |
| 公开候选/前端 | 8091 新候选公开 230、只读安全 8、前端契约 10 调用/9 操作；前端 53 项及格式/文档检查通过 |
| resolved OSV | j537：crypto 5 个第三方包、公开 API 52 个包，均 0 命中；不是 57 个去重包，也不覆盖构建插件或证明零风险 |
| 扫描失败语义 | j536：4 项自测，覆盖网络失败、过期清单、不完整响应不保留旧成功报告，以及成功替换报告 |
| 保护基线 | j538：98 项旧源/build/script 中仅预定 4 项改变，94 项保持；旧运行目录 85 文件保持；公开 jar SHA 与 S1-B 相同 |

公开 jar SHA-256：`ece61cbc3a198aa1de9d78d2215e5017996a04c96792d512f36aeb61f0914b33`。其余 jar、115 项当前源/build/script 指纹、依赖 jar 指纹、解析清单与 OSV 响应保存在机器证据中。

未隐藏过程失败：Windows 无 rg，改用编辑器搜索；首次前端契约调用误传位置参数，按脚本要求改为显式环境变量后通过；j534 公开 OSV 网络超时，补强旧报告失效逻辑并测试，j537 两份真实扫描重新成功。j535 只是中间聚合快照，最终采用修复后 j538，不把旧报告或中间失败冒充最终通过。

## 6. 复验命令

仓库根目录（沿用已准备的本机测试身份，不输出秘密）：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Test-DatabaseIntegration.ps1
node --test backend/scripts/test-runtime-advisories.mjs
python backend/scripts/test-admin-runtime-guards.py
npm --prefix frontend run verify
```

在 `backend` 目录生成真实依赖清单并执行基准、扫描；Maven 命令串行执行，不 clean 旧 `backend/target`：

```bat
mvnw.cmd -pl public-api dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
mvnw.cmd -pl admin-credential-crypto -am package dependency:list dependency:copy-dependencies -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt -DoutputDirectory=target/benchmark-libs
java -Xms128m -Xmx512m -cp "admin-credential-crypto\target\test-classes;admin-credential-crypto\target\classes;admin-credential-crypto\target\benchmark-libs\*" cn.zhinan.admincredentialcrypto.CredentialKdfBenchmark
node scripts/check-runtime-advisories.mjs admin-credential-crypto
node scripts/check-runtime-advisories.mjs
python scripts/check-module-boundaries.py
```

命令非零退出、没有最新完整报告都不是通过。基准只在 test classpath，正式 jar 不含该入口。独立实例复验沿用 [S1-B 指南](阶段二S1-B管理启动守卫与验证指南.md) 的已授权私有 manifest；不要重复 prepare 或复制真实凭据到命令行。

## 7. 下一切片及剩余门禁

- **C2**：冻结新的增量迁移、JDBC CredentialLookup/AuthenticationEventSink 和独立事件事务；只增加实际需要的 INSERT 授权，版本化守卫矩阵；在精确权限下验证真实密码登录/审计故障后才接线 runtime。
- **C3**：独立账号 CLI/数据库身份、首次与并发初始化、改密/禁用/权限变更、最后维护账号保护、恢复 SOP、秘密命令结果查询式重试、版本递增与事实审计同事务；创建策略/拒绝表来源及精确 grants 必须在编码前冻结。
- **S1-D**：HTTP、Cookie、Origin/CSRF、外部限流、后台 UI。生产身份/CA、真实部署和多实例容量仍另验收。

C1 关闭的只是纯核心与 KDF 门禁。原 8090 API 保持健康；8091/8092/独立 MySQL 临时端口关闭。未提交、推送或归档；本轮为主会话实施和分步复核，没有独立子代理安全审计。

## 8. 文档同步中断已恢复

MCP 中断曾使最后一批文档尚未写入；连接恢复后，j539 对齐 115 项源/build/script 和 7 个产物，确认没有漂移，再完成 17 个文档/规范/任务文件的原子同步。首次补丁 dry-run 因混用 hunk 格式拒绝，未写入文件；修正格式并重新校验后通过。

j540 重新通过前端 53 项、扫描失败语义 4 项、七模块/jdeps/嵌套产物、格式、任务上下文与文档检查（130 个 Markdown、562 个本地链接，无错误）；git diff --check 通过，只有既有换行转换提示。j541 再次确认源码/产物和旧运行目录保持、原公开 API 正常、临时端口关闭。234+42 后端结果仍指此前 j525 的完整构建，本次没有把指纹复核描述成重新执行该套测试。

此前“远端文档同步阻塞”已解除；本地 C1 交付闭环，C2/C3/HTTP/生产门禁及不提交推送的边界不变。
