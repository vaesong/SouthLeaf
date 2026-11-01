# 阶段 4 交付：开放串行化与真实库覆盖

没有连接 3306，没有提交 git。提前期和最远日期仍按绝对时刻比较。

## 锁顺序

开放档期（单个，以及按周里的每一格）在同一事务里先执行：

`SELECT current_version FROM admin_booking_operating_rule WHERE singleton_id = 1 FOR UPDATE`

没有头行或版本小于 1，拒绝为未配置。锁住之后再读规则正文；指针和正文版本不一致则拒绝。然后才锁「当天开始」的档期行（`ORDER BY start_at, id FOR UPDATE`），做重叠和每日 24 条检查，再插入。

关闭只锁这一条档期行，不锁规则头。确认和改期也只锁目标档期行，然后写占用，不锁规则头。因此：

- 两笔开放先抢同一条规则行，后到的会看到先提交的档期，重叠检查不会双双通过。
- 开放持有规则锁再等档期行时，确认、改期、关闭都不需要规则锁，它们结束并放掉档期行后开放才能继续。不会和「先档期、后规则」形成环。
- 保存规则也是先锁这条规则头，不锁档期行。和开放抢的是同一把第一锁。

单测 `openingLocksTheRuleHeadBeforeAnySlotAndClosingDoesNot` 记录顺序为 `rule-head` 然后 `day-slots`；关闭只有 `slot-row`。

## 新增集成测试

脚本直接读 `admin-migration-cli` 的 `db/admin-migration`，按版本执行到 V13。不另存一份。`BookingRulesAndSlotsIT` 还断言测试资源里的 V6 副本和源文件字节相同。旧的预约 IT 仍只套用这份 V6 副本来测原来的确认流程；新用例走完整迁移链。

| 测试 | 覆盖 |
|---|---|
| `BookingRulesAndSlotsIT` | 保存规则与过期 If-Match；单个开放；重叠拒绝；当天 24 条拒绝；窗口外拒绝；按周部分成功；占用拒绝关闭；待确认引用拒绝关闭；已关闭再次关闭仍是当前状态；两个线程同时开放重叠档期只成功一个 |
| `PublicBookingOperatingRuleIT` | 未配置时查档和提交都是 `RULES_UNCONFIGURED`，对外错误码 `BOOKING_RULES_UNCONFIGURED`，不插入申请；配置后窗口外的档期不出现，窗口内可以提交，过期时刻按该版本的 48 小时 |
| `DatabasePublicApiIT.aPublishedHeroSlideIsReturnedInsteadOfTheWorkFallback` | 公开库 V4 允许 `HERO` 绑定；带轮播图片的发布快照出现在首页，不再走作品回退 |

这些测试在总门禁的反应器 `verify` 里执行并通过：规则与档期 5 个，公开预约规则 2 个，公开目录套件 22 个（含轮播）。

## 总门禁

命令：`backend/scripts/Verify-StageTwoGate.ps1`

隔离预约库在 3307，结束后删除自建数据目录。没有指向 3306。

第一次约 2 分 33 秒失败。反应器本身的测试已过，失败在随后的模块边界检查：它仍要求管理端依赖 Element Plus，而当前界面已经不再使用它。检查改为必须有 Vue 与路由，并且不得重新引入 Element Plus。没有跳过检查。

第二次耗时约 4 分 31 秒，退出码 0。

| 步骤 | 结果 |
|---|---|
| 隔离预约库启动（3307）并在结束时删除 | PASS |
| 复用仍有效的 C2 证书夹具，并准备随后删除的 C3 夹具 | PASS |
| 管理库结构校验 | PASS |
| 反应器 `verify`（含上述 IT）与模块边界 | PASS |
| 公开库无演示种子迁移 | PASS |
| 公开包、管理端构建与运行时打包 | PASS |
| 总状态 | PASS |
| 控制台关闭探针 | NOT_RUN（门禁脚本明确不要求这次发控制台信号） |

证据：`docs/开发与演示/证据/stage-two-engineering-close.json`（`status` 为 `PASS`，`bookingDatadirRemoved` 为 true，`originalServerTouched` 为 false）。

## 仍未覆盖

- 控制台关闭探针仍是 `NOT_RUN`。这是门禁脚本自己的设定，不是这次失败。
- 旧的 `BookingDatabaseIT` 等仍只执行 V6 副本。新行为和 V12/V13 由上面的新 IT 覆盖，并核对了 V6 副本没有和源文件分叉。
- 这是隔离夹具上的工程门禁，不是生产证书、生产授权或真机验收。
- 没有在浏览器里点档期抽屉。
