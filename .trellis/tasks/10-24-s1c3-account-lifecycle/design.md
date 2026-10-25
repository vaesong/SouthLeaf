# S1-C3 技术设计：独立账号生命周期 CLI

## 1. 模块与信任边界

新增第八个 Maven 模块 `admin-account-cli`，普通 jar、显式 `main`，包根 `cn.zhinan.adminaccountcli`。编译依赖限定为：`admin-security-core`（纯生命周期用例/端口）、`admin-credential-crypto`（现有 Argon2id hasher）、`admin-db-policy`（CLI 专属 URL/grants 策略）、Spring JDBC/Hikari 与 MySQL runtime driver。它不依赖 `admin-runtime`、`public-api`、Boot、Flyway main 或公开 Reader。

三种管理数据库身份必须不同：

| 进程 | 能力 | 不能做 |
|---|---|---|
| runtime | 会话四表读/写 + `admin_auth_event INSERT` | 账号/权限/命令/guard 写入、DDL |
| account CLI | C3 精确账号/权限/命令/guard SQL | DDL、公开 schema、runtime 会话/审计写入、GRANT |
| migration CLI | 显式 Flyway DDL | 日常账号生命周期、runtime 启动 |

CLI 只接受 `SOUTHLEAF_ACCOUNT_CLI_MODE/DB_URL/DB_USERNAME/DB_PASSWORD/DB_SCHEMA/DB_EXPECTED_USER`。配置解析拒绝未知 `SOUTHLEAF_ACCOUNT_CLI_*`、所有 runtime/migration/admin 配置串用、Spring/Logging/Web 覆盖和命令行 JVM 配置。CLI 在验证 DATABASE/CURRENT_USER/TLS/无角色/精确 grants 前不创建任何生命周期服务；失败关闭池且仅输出稳定错误代码。

## 2. 命令模型与秘密处理

| 子命令 | 非秘密参数 | 秘密输入 | 结果 |
|---|---|---|---|
| `bootstrap` | command ID、new account ID、显式 permissions | 交互式两次密码 | 创建唯一首个维护账号；guard completed |
| `change-password` | command ID、account ID、expectedVersion | 交互式两次密码 | 新 hash、securityVersion+1 |
| `disable-account` | command ID、account ID、expectedVersion | 无 | enabled=false、securityVersion+1 |
| `replace-permissions` | command ID、account ID、expectedVersion、显式 permissions | 无 | 全量替换、securityVersion+1 |
| `command-result` | command ID | 无 | 仅返回既有非秘密命令结果 |

密码绝不作为 argv、环境变量、配置、普通 stdin、日志或返回结果。生产 main 使用 `System.console().readPassword()`；console 缺失/两次输入不同/中断立即失败。不把“支持管道输入”当成方便功能。测试只以 package-private `SecretReader` fake 提供合成 `char[]`，服务在 finally 清零它所拥有的副本。

新账号 ID 为 ASCII `[A-Za-z0-9._-]{3,64}`；已有账号命令使用现有兼容校验（1–64）。创建/重置密码需要 15–128 个 Unicode code point 与 UTF-8 ≤512 byte，允许空格，不作 normalization/trim/case-fold。`PasswordPolicy` 使用版本化、随 jar 打包的最小拒绝词表；词表启动校验为 canonical hash + 非空，上载/缺失/损坏拒绝。该词表仅是 2A 防线，不代表完整泄露库或生产政策。

## 3. 事务、并发与结果查询

密码策略和 Argon2 放在锁外。JDBC 生命周期适配器使用 `REQUIRES_NEW`、`READ_COMMITTED`、5 秒 timeout；进入事务后固定：

1. `SELECT guard ... FOR UPDATE`；若 bootstrap 已完成或 guard 不唯一，失败。
2. 对存在 target 执行 `SELECT ... FOR UPDATE`，检查 expected security version / enabled 状态。
3. 对 disable/replace 权限，在持有单例 guard 下计数 enabled+`STAFF_ACCESS_MANAGE` 维护账号；禁止降到零。所有账号变更均先拿 guard，故计数与任何并发变更串行。
4. 更新账号/权限，增加版本；插入 command result；bootstrap 最后标记 guard；在同一事务提交。

命令表的主键 UUID 是幂等锚点。`bootstrap/change-password` 不保存任何能够校验密码的 payload hash：撞到既有 ID 返回稳定 `SECRET_RETRY_REQUIRES_RESULT_QUERY`，CLI 只能再调用 `command-result`。`disable/replace-permissions` 在命令表保存规范化非秘密参数的 SHA-256；同 ID/相同 hash 返回原成功结果，不同 hash 为冲突。数据库 unique 约束处理 precheck 竞争。

## 4. V4 迁移候选（源码，不自动执行）

```sql
CREATE TABLE admin_account_command (
  command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  command_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_account_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_security_version BIGINT NULL,
  result_security_version BIGINT NOT NULL,
  payload_sha256 BINARY(32) NULL,
  execution_identity VARCHAR(288) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  FOREIGN KEY (target_account_id) REFERENCES admin_account(account_id),
  CHECK (command_type IN ('BOOTSTRAP','CHANGE_PASSWORD','DISABLE_ACCOUNT','REPLACE_PERMISSIONS')),
  CHECK (expected_security_version IS NULL OR expected_security_version >= 0),
  CHECK (result_security_version >= 0),
  CHECK ((command_type IN ('BOOTSTRAP','CHANGE_PASSWORD') AND payload_sha256 IS NULL)
      OR (command_type IN ('DISABLE_ACCOUNT','REPLACE_PERMISSIONS') AND payload_sha256 IS NOT NULL)),
  INDEX ix_admin_account_command_target_time (target_account_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE admin_bootstrap_guard (
  guard_id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  completed_at DATETIME(6) NULL,
  bootstrap_command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
  CHECK (guard_id = 1),
  CHECK ((completed_at IS NULL) = (bootstrap_command_id IS NULL)),
  FOREIGN KEY (bootstrap_command_id) REFERENCES admin_account_command(command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO admin_bootstrap_guard(guard_id, completed_at, bootstrap_command_id) VALUES (1, NULL, NULL);
```

No password/hash/token/request body is a V4 column. `execution_identity` comes from verified `CURRENT_USER()` only, not a CLI flag. `admin_security_audit` is intentionally not reused because V2 constrains it to session actions.

## 5. CLI exact grants (design target)

The CLI policy accepts exactly `USAGE ON *.*` plus:

- `admin_bootstrap_guard`: `SELECT, UPDATE`
- `admin_account`: `SELECT, INSERT, UPDATE`
- `admin_account_permission`: `SELECT, INSERT, DELETE`
- `admin_account_command`: `SELECT, INSERT`

It rejects every other table/column/schema/global right, roles/current role/mandatory roles, GRANT OPTION, unknown syntax and root. SQL design must not grow these rights; `admin_session`, `admin_security_audit`, `admin_auth_event`, Flyway history, public schema and all DDL stay unavailable to CLI.

## 6. Verification

Pure tests cover policy boundaries, canonical payload fingerprints, secret retry refusal, command result shaping and last-maintainer decision rules. Adapter tests use metadata fakes/transaction fakes; real MySQL tests are not run until a separate isolated C3 identity/schema/grants authorization. Architecture checks prove artifact isolation and no Web/Boot dependency in CLI.
