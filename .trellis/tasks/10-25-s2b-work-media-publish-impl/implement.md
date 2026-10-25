# S2-B 单作品媒体发布闭环实施记录

状态：本地隔离验收完成（不表示阶段二整体完成）。

## 实施与修复

- 完成 admin V5 / public V2 双 schema 的真实 MySQL/InnoDB 内容发布、授权快照、public overlay、游标 epoch、媒体处理与读取链路。
- withdraw 503 的实际根因是 MySQL `TINYINT(1)` 通过 Connector/J 映射成 `Boolean`，应用却仅接受 `Number`。仓储改用显式 Boolean/Number 解析，并新增 `JdbcPublicationRepositoryTest` 回归覆盖。
- browser E2E 使用组件实际 `data-testid` input 节点；发布后等待 Vue 异步刷新显示 withdraw 控件，再验证撤回入口。
- 临时诊断日志已移除。Harness 的失败门禁未放宽。

## 验证

- 最终证据：`docs/开发与演示/证据/stage-two-s2b-isolated-l5-57.json`。
- runId：`0691412c`；PASS；85 checks，其中 browserChecks=12、grantNegativeCount=17。
- cleanup：fixtureRemoved、MySQL/runtime/public/browser stopped 和 graceful shutdown 均为 true。
- `mvnw.cmd -B -o -q -DskipTests package`；`mvnw.cmd -B -o -q test -am -pl admin-content,admin-runtime,public-api`；owned L5 isolated verifier 全部通过。
- `admin-web`: Vitest 18/18、Vue/TypeScript production build、Prettier format check PASS；module-boundary verifier PASS（10 modules，2 个负向自测）。

## 范围边界

仅 synthetic data / disposable local TLS loopback fixture。没有触碰原 3306 服务、C2 fixture、生产、真实客户资料、通知或平台凭据；没有默认 admin。S3–S7 及生产部署验收未包含在本任务。本记录不包含 commit/push 声明。
