# S1-D implementation plan

## Work packages

1. **Task and contract baseline**
   - Add curated Trellis context and record the explicit local S1-D authorization.
   - Materialize the four D-403 auth routes in `docs/接口/admin-api.openapi.json` with strict schemas, error codes and cookie/security notes.

2. **Opt-in HTTP runtime**
   - Add only the Spring Web/Security dependencies and configuration necessary for the dedicated admin runtime.
   - Keep default runtime non-Web and reject unsafe/missing local S1-D configuration before listener bind.
   - Implement request IDs, safe error mapping, strict request DTO parsing, exact Origin checks, CSRF cookie integration, secure session cookies, bounded local rate/KDF gates and session/auth bridges.

3. **Independent client**
   - Create `admin-web/` with locked package configuration, Vite/Vue/TypeScript, relative API client and memory-only auth state.
   - Implement login, protected identity page, forbidden, 404 and recoverable status states; no business dashboard placeholders.

4. **Tests and boundaries**
   - Add backend unit/slice tests for the D-403 and D-703 S1D-01..09 assertions that do not require a database.
   - Add frontend unit/component tests and build/format checks.
   - Extend module-boundary/packaging checks so public-api and native frontend remain isolated from admin web/runtime dependencies.

5. **Real L5 acceptance**
   - First review the dedicated S1-D fixture design and exact impact list.
   - Use only a manifest-owned, disposable TLS MySQL/server/browser fixture; controlled C3 CLI creates the staff test identity.
   - Run HTTPS browser/API/MySQL checks, record evidence, stop and delete owned resources. If fixture prerequisites are missing/fail, preserve BLOCKED/FAIL; never substitute C2 management/public results.

## Invariants

- No production, original service, C2 public fixture, system trust, actual customer/notification, default administrator, automatic migration, commit or push.
- No HTTP path for account lifecycle writes; C3 CLI remains the account-writer authority.
- No secrets in argv, files, logs, test reports or evidence.
- Builds/tests that share `backend/target` run serially.

## Definition of done

The implementation is complete only when the source and contracts are consistent, L1/L2 checks pass, and the authorized L5 fixture result is explicitly PASS or is honestly recorded as not runnable. S1-D 不操作或改动 C2；C2 状态独立跟踪，不由本任务推断。

## 执行与验证记录（2026-10-24）

- S1-D 四个认证端点与独立 `admin-web/` 已实现；没有添加占位业务页面。
- `mvnw.cmd -pl admin-runtime -am test`（j205）通过；`admin-runtime` 63 项测试通过，所需上游模块的测试与 Spotless 检查也通过。
- `admin-web` 的 `npm test && npm run build && npm run format:check`（j207）通过：9 项测试通过，类型检查/生产构建及 Prettier 检查成功。
- 全模块 `mvnw.cmd package`（j210）通过，9 个 Maven 模块均成功打包；本命令未进入 Failsafe 集成测试阶段。
- `python scripts/check-module-boundaries.py`（j211）通过：依赖图、纯模块 `jdeps`、嵌套 JAR 与 admin-web/native frontend 隔离检查成功；结果只证明产物/依赖边界，不证明数据库授权、管理登录或生产安全。
- 文档检查（j216）通过：131 份 Markdown / 589 个本地链接、0 错误；`git diff --check` 退出码 0（仅有既有 LF→CRLF 提示），OpenAPI JSON 可解析。
- 全 reactor `mvnw.cmd verify`（j208）未通过：`public-api` 的 `DatabasePublicApiIT` 在所需环境变量 `SOUTHLEAF_TEST_DB_URL` 缺失时中止（Failsafe 报告 21 项均为环境上下文错误）。未设置该变量、未重跑该入口；此结果不作为 C2 或 S1-D 验收。
- **S1-D L5：`NOT_RUN`。** 目前没有已评审的 S1-D 专用 manifest、隔离 TLS MySQL/HTTPS candidate/browser runner 与精确影响清单；仓库现有 C2 `verify-admin-isolated.py` 是其他范围的隔离验证入口，不能拿来替代本任务专用 fixture。`admin-web/package.json` 也没有 Playwright 浏览器验收脚本。未创建测试身份、未执行管理迁移、未启动候选服务/浏览器或触碰数据库。取得专用 fixture 设计及影响清单并单独确认前，不运行 L5，也不报告通过。
- Trellis 任务保持 `in_progress`。以上不表示生产验收、C2 回归或完整 S1 关闭；本轮未触碰原服务/生产、真实用户数据或通知，未提交/推送。

## 最终验收补充（2026-10-25）

- 专用隔离 L5 attempt 27（runId `6fa82949`）通过：`status=PASS`、35 项检查、27 项浏览器检查，`cleanup.complete=true`；Java/MySQL/浏览器均正常停止且 owned fixture 已删除。最终证据：`docs/开发与演示/证据/stage-two-s1d-isolated-l5-attempt-27-20261025.json`。候选 JAR SHA-256：`da91decdf559d92069b8446d30673fc784b708c0614f720be0447c6aeb4b0567`。
- `admin-web` Vitest 4 个文件共 10 项通过；`npm run format:check`、`npm run build` 通过。`python scripts/check-module-boundaries.py` 通过。此前 `mvnw.cmd -pl admin-runtime -am package` 的 66 项测试通过。
- 全 reactor `mvnw.cmd verify` 仍因缺少 `SOUTHLEAF_TEST_DB_URL` 环境变量而未通过；未补设变量，也不计为通过或 L5 证据。
- 证据明确 `realOrProductionDataUsed=false`、`secretsRecorded=false`、`c2FixtureUsed=false`；未使用生产/真实数据、默认管理员或 C2 fixture，未提交/推送。
- 本 Trellis 任务现已完成；这只关闭 S1-D 本地切片，不代表 S2–S7 或阶段二整体完成。按 P03，下一顺序为 S2-A 设计、再到 S2-B 单作品媒体发布/撤回闭环。
