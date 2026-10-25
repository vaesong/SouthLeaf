# Execution

1. 读取最新规范/已有核心/ADR；保存 S1-B 源和旧运行基线。任务只覆盖 C1，细分全 S1-C 门禁而非把 CLI 未定条款当已关闭。
2. 新增纯核心值对象/端口/登录编排与单元测试；仅兼容性扩展 accountId 的点号，不改变会话事务/SQL。
3. 新增共享 crypto 模块、维护库适配器、严格参数与全进程预算；独立 Python argon2-cffi 25.1.0 生成的固定合成向量验证互操作。新增仅 test 源 benchmark，不含真实密码/hash 输出。
4. 更新声明/字节码/嵌套产物边界；保持 runtime 暂无 crypto 依赖，避免无持久认证事件接线。扩展现有 OSV 脚本支持 allowlist 模块参数，默认 public 行为不变。
5. 串行 Spotless/full verify（原42真实MySQLIT不可跳过）、实际 KDF benchmark 与双并发、两份解析依赖清单/OSV。记录时间/样本/堆，不把本机基准冒充生产容量。
6. 最终 jar 负向、已有隔离实例回归（只测试已授权实例，不改权限矩阵）、公开实际候选/前端/文档。get_problems/get_changes 与 SHA 保全。
7. 同步 D-201/ADR/P03/跟踪/指南/证据；C1 可完成而 C2/C3/整个S1-C明确未完成。无子代理，不声称独立审计，无commit/push/archive。
