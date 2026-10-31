# 阶段二工程收口执行计划

实现开始前必须先由用户确认本计划，再执行 `task.py start`。下列顺序不可对调：先有进程证据，再把该套件并入全仓库验证，最后按实际结果改文档。

## Checklist

1. 在 `backend/admin-booking/src/test/java/cn/zhinan/adminbooking/integration/` 增加两个 JVM 的续跑测试和只打印固定标记的子进程入口。子进程复用 `JdbcOutboxWorker` 与 `OutboxDispatcher`，不新建调度器。
2. 运行 `backend/scripts/Test-BookingDatabaseIntegration.ps1`。记录 Surefire/Failsafe 计数和退出码。失败不写入 PASS。
3. 从 `verify-account-cli-isolated.py` 抽出只启动隔离库并导出 `SOUTHLEAF_C3_IT_*` 的准备函数。原 CLI 验证脚本改为调用它，避免两套建库逻辑。
4. 扩展 `verify-admin-isolated.py` 的 `run_reactor_verification`：把 booking 与 C3 变量放进 `clean_env` 的显式环境，超时改为不少于 45 分钟。
5. 扩展 `Test-DatabaseIntegration.ps1`：这两组变量缺失时失败，不回退到本机凭据库。
6. 增加 `backend/scripts/Verify-StageTwoGate.ps1`：启动 booking 实例、准备 C3 实例、调用既有 `verify-admin-isolated.py verify --with-reactor`，最后按 ownership 关闭并删除本次目录。
7. 跑通该门禁一次。把无秘密证据写到 `docs/开发与演示/证据/stage-two-engineering-close.json`。
8. 打包 `public-api` 与打入静态资源的 `admin-runtime`。在一次性隔离库上做管理端 `--validate` 和公开端不带 `--seed-demo` 的迁移。结果写入同一证据文件。
9. 按证据更新 G2A 对照、实施跟踪第 1 节和文末新节、P02 当前位置、P03 文首与第 1 节、部署方案打包句、2B 对接清单进入条件第 1 条。
10. 不执行 commit 或 push。

## Validation

- `powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Test-BookingDatabaseIntegration.ps1`
- `powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/Verify-StageTwoGate.ps1`
- 公开 API：`backend` 目录 `mvnw.cmd -pl public-api -am package`
- 管理端：`admin-web` 的生产构建，然后 `python backend/scripts/build-admin-runtime.py`
- 模块边界：`python backend/scripts/check-module-boundaries.py`

历史 L5 不作为本次必跑项。

## Risks and rollback

- C2 测试证书有效期到 2026-11-23。若 prepare 因证书到期失败，按既有「复用原私钥续发测试证书」处理，不生成生产私钥。
- 3307 或 C3 随机端口被占用时，门禁应失败并清理自己启动的实例，不杀无关 mysqld。
- 改 `clean_env` 时只追加两组已知变量名，不放宽对其它 `SOUTHLEAF_*` 的丢弃。
- 回退点：进程测试提交前、环境转发提交前、文档改写前。文档只在证据 PASS 之后改。

## Before task.py start

- `prd.md`、`design.md`、`implement.md` 已写完。
- `implement.jsonl` 与 `check.jsonl` 指向质量规范、数据库规范和 `research/verify-env-gap.md`。
- 用户已对本计划明确回复可以开始实现。
