# C1 bounded credential core design

最小行为缺口：会话签发仍要求可信 caller，尚无凭据编排/维护库适配器。本批只在 admin-security-core 增加端口/值对象/用例，新增 ADR-004 既定 admin-credential-crypto 普通 jar；父仅加模块，运行时不加新库/Bean。core 仍 JDK-only，CLI 后续复用 crypto 不依赖 runtime。

依赖：遵循既定 Spring Security Argon2 方案；BOM 实测 spring-security-crypto 7.1.1，显式 spring-core 提供其 API 依赖；新提供者 bcprov-jdk18on 1.86 固定版本。先查询来源/解析依赖/OSV，不改既有 BOM 或公开库。当前写入参数 m=65536 KiB,t=3,p=1,salt16,hash32；只允许校验该 profile 与 ADR 最低 profile m=19456,t=2,p=1；v19/Argon2id/规范 Base64，解析先于 KDF；任意其他参数拒绝，避免算力炸弹/参数缓存膨胀。Dummy 固定当前 profile，结果从不授予身份。历史 profile 时间可能不同，不能宣称恒定时间。

同 JVM/classloader 的静态非阻塞预算最多 2；tryAcquire 失败明确 BUSY，无队列，finally 释放。测试以屏障持有两个预算槽再调用真正 adapter 验证第三个拒绝。维护库有内部 String/char[] 副本，不能承诺 GC 前零化；应用只清理自己拥有的 char[] 副本，不修改 caller 输入，不输出秘密或 PHC。

Login 输入为 accountId、char[]、受信任有界 requestId；无 version 参数。Lookup 只读；snapshot.accountId 必须匹配请求。KDF 在会话事务外；匹配后调用现有 AdminSessionService 再查 enabled/version。事件 append 失败必须向上失败；错误存储 hash/lookup故障与坏密码不同。升级提示在签发前记录，不重写 hash；成功审计仍由原 Store 同事务负责。无生产假 sink/memory adapter。

现有内部 accountId 支持1–64，新建目标3–64；为 D-201 的点号标识扩展同一校验为 ASCII 字母数字点下划线连字符1–64。登录兼容已有短内部ID，不把新建规则强加旧身份；未来CLI单独强制3–64。不规范化账号或密码。

C2/C3 前置决策：V3 不改 V2，事件仅 INSERT 增量；命令秘密重试采用结果查询而非保存密码摘要；所有账号变更先锁 singleton guard 再锁 account，guard 串行化最后维护账号保护/初始化，expectedVersion 防并发旧命令；至少保留一个 enabled+STAFF_ACCESS_MANAGE 账号。恢复使用同一受控CLI/专用身份，不用默认密码或万能runtime。正式 SQL/精确CLI授权、本地拒绝表来源在 C2/C3 G1 冻结，本批不预建迁移/空实现。
