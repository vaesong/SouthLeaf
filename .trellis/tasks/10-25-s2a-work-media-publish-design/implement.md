# S2-A 实施记录（设计任务）

## 工作包

1. **范围核对**：对照 P02/P03、D-205/D-302/D-403/D-507/D-106/D-607/D-703，限定为单作品上传至撤回，不拓展 S3–S6 或 2B。
2. **媒体与业务设计**：补 D-205 §13、D-302 §12，区分对象写入与 MySQL 事务、草稿/公开投影、测试授权与真实授权边界。
3. **API 与 UI 契约**：补 D-403 §8、D-607 S2 操作流程及后续 S4–S6 骨架状态。
4. **存储/事务/G1**：补 D-507 §12、D-106 §13、D-703 §10；按 ADR-005 明确 S2 使用 V2 per-work overlay，精确列出 owner/grants/锁序/过期/幂等/撤回。
5. **决策与任务化**：新增 ADR-005 与 Trellis PRD/design/implement/check/verification；同步 P03 和实施跟踪，但不标记 S2-B 完成。
6. **验证**：运行文档/链接/差异/诊断检查，记录到 `verification.md`。本任务不跑产品构建、数据库或 S2 E2E。

## 不变量

- 只改设计/任务/计划/跟踪文档；没有产品代码、SQL/migration、运行配置或 OpenAPI 实施。
- 不触生产/真实资料/通知、C2 fixture/原服务、默认管理员、凭据；不创建账号/grants/DB，不启动服务，不提交/推送。
- ADR-005 只对 S2 首个作品有效；不可把它扩大解释成 S3 全内容模型或生产批准。

## 设计任务完成定义

- [x] 所有引用文档已读后编辑，文档间范围/owner/状态一致。
- [x] ADR-005 比较 V1 in-place、full V2 catalog 与 per-work overlay 并限定为 S2 首个作品。
- [x] D-507 精确表级权限、同实例/UTC/锁序/恢复边界明确。
- [x] 文档链接、markdown、差异与编辑器诊断通过（详情见 `verification.md`）。
- [x] `S2-A` 的 G1 设计任务关闭；`S2-B` 代码、迁移、测试/证据仍 pending。

## 执行与验证记录（2026-10-25）

- 完成 D-205 §13、D-302 §12、D-403 §8、D-507 §12、D-106 §13、D-607 S2 操作流程、D-703 §10、设计索引及 ADR-005；更新 P03/实施跟踪。
- `node tools/check-docs.mjs` exit 0（133 Markdown/604 本地链接/0 错误）；`git diff --check` exit 0（仅 CRLF advisory）；新增 8 个设计/任务文件 trailing whitespace 0。
- 相关文档与 Trellis 文档 `get_problems`：errors 0 / warnings 0。
- 本任务不跑产品构建、OpenAPI 实施、SQL/migration、数据库、后台 HTTP、媒体处理、浏览器或小程序 E2E。没有产品代码或数据库变更、测试身份/真实资料/通知、生产或 C2 fixture 操作，也未提交/推送。S2-B 仍未开始。