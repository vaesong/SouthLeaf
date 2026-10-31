# 阶段二 G2A 十一条退出项对照（2A 退出证据）

判定口径取自 `docs/计划/P02_阶段二目标与实施计划.md` §7「G2A：基本功能开发完成」。每条只认**真实代码 + 真实数据库 + 真实浏览器/小程序链路**产出的证据；单元测试和 mock UI 不能单独让某条成立。

- 总判定：**11 / 11 成立**
- 证据根目录：`docs/开发与演示/证据/`
- 最近一次 booking 模块隔离回归：`Test-BookingDatabaseIntegration.ps1` → 111 项（77 单元 + 34 集成）、BUILD SUCCESS、数据目录已删除
- 最近一次全仓库验证：`Verify-StageTwoGate.ps1` → `stage-two-engineering-close.json` 为 PASS。同一次 `mvnw verify -Pc3-isolated-it` 跑完 public、security、booking、账号 CLI 四组 Failsafe
- 最近一次契约校验：`python backend/scripts/check-openapi-contract.py` → `OPENAPI_CONTRACT_PASS`

| # | G2A 要求 | 判定 | 关键证据 | 说明与限制 |
|---|---|---|---|---|
| 1 | 无需改库即可完成内容录入、上传、处理、授权核验、预览、发布、下架与撤回 | ✅ | `stage-two-s2b-isolated-l5-57.json`（PASS，85 checks / 12 项浏览器检查）；`ops29`–`ops31` | 全程走后台页面与 API，未手工改表；媒体为可合法用于测试的合成图 |
| 2 | 无权限、授权不足/过期/撤回、媒体未就绪时拒绝发布与访问；旧 URL、HEAD、历史发布恢复均验证 | ✅ | 同上（`grantNegativeCount=17`）；`PublicIsolationTest`、`source-boundaries` 测试 | 拒绝路径逐项断言；HEAD 由 GET 处理器自动覆盖并有测试 |
| 3 | 文件伪装、过大/超像素、路径穿越、预览泄露、重复处理和失败重试有测试 | ✅ | `ImageIoProcessorTest`（伪装/截断/追加/超大尺寸/不支持格式）、`FileMediaObjectWriterTest`（create-only、拒绝相对根）、`JdbcPublicationRepositoryTest`、S2-B 负向 17 项 | 大文件与超像素在解码前拒绝，避免内存放大 |
| 4 | 本人权限可靠；不能枚举其他测试客户的预约、订单、联系方式，或冒用线下记录 | ✅ | `public-api` 61 项单测（非本人一律 404、不泄露存在性）；`verify-booking-miniapp-isolated.py`（真实小程序会话令牌，仅能读自己的申请） | 客户身份为微信侧指纹派生；同手机号/同微信永不自动合并（Q05） |
| 5 | 多个待确认申请不占档；真实 MySQL 并发确认同档仅一笔成功、仅一张订单；重复申请/确认幂等 | ✅ | `BookingConcurrencyIT#twoConfirmationsForTheSameSlotProduceExactlyOneOrder`、`#twoConfirmationsWithTheSameIdempotencyKeyProduceExactlyOneOrder`；`stage-two-g2a-concurrency-it-d703-01.json` | 断言只看最终数据库事实（订单 1、快照 1、占用 1、占用者为胜者），不看 HTTP 成功码 |
| 6 | 确认故障全事务回滚；套餐变更不覆盖订单快照；改期失败保留原档 | ✅ | `BookingDatabaseIT`（回滚后占档/审计/outbox 均无残留、快照不可变）、`BookingRulesTest`；`s6close05`（409 `SLOT_UNAVAILABLE` 后仍为原时段 + 修訂 2） | 改期被拒时命令账本不落行；只承诺"失败不留痕"，不承诺 exactly-once |
| 7 | 封档/确认、取消/改期、过期/确认竞争及跨日/时区边界、任务重复和重启恢复有测试 | ✅ | `BookingConcurrencyIT#aConfirmationThatWaitsOnTheSlotRowNeverBooksIt`、`#closingASlotAfterConfirmationKeepsTheOrderIntact`、`#cancellingWhileReschedulingLeavesExactlyOneTerminalAnswer`、`#aRequestThatExpiredWhileItWaitedNeverProducesAnOrder`；`OutboxProcessRecoveryIT#aProcessThatExitsWhileHoldingTheLeaseIsContinuedByTheNextProcess` | 封档与确认用档位行锁，确认在锁等待后失败且不成单。进程续跑是两个独立 JVM：第一个领取后直接退出，第二个在租约时点之后投递成功，数据库为 `SENT`、尝试次数 2。进程退出留下的租约行与进程被杀死留下的是同一份持久化事实 |
| 8 | 线上线下共用同一申请与冲突规则；拒绝/取消/失效、通知失败和人工处理可审计 | ✅ | 小程序写入与后台确认写同一批 `booking_*` 表（`V6`/`V11`）；`s5reject04`–`s5reject06`、`s6close05`（通知失败可见 + 人工恢复） | 来源字段区分 `ONLINE`/`OFFLINE`，规则同一份 `BookingRules` |
| 9 | 小程序完整表达申请待确认、预约确认和订单约定；未开放支付时不显示假支付入口或已支付结果 | ✅ | `stage-two-booking-miniapp-isolated-l5-mm14.json`（PASS，9 checks，runId `7023f512`）；`frontend` 65 项测试 | 真实微信开发者工具自动化驱动真实小程序：页面加载 → 选档 → 提交 → 待确认 → 运营确认 → 已确认约定（家庭写真体验 / 2026-10-27 23:09-00:09 / JP¥38,000 /「本阶段不收款」），页面数据与渲染文本均无支付字样。**本轮修出真实缺陷**：客户端只接受 `OK:200`，而接口按契约返回 201，导致提交必然失败 |
| 10 | 阶段一公开链回归通过；后台构建、API 契约、数据库迁移、端到端和安全负向测试可复现 | ✅ | `stage-two-s2b-isolated-l5-57.json`（阶段一公开链回归）、`check-openapi-contract.py`（31+15 路径逐条对齐控制器）、`stage-two-g2a-openapi-contract-01.json`、`Test-BookingDatabaseIntegration.ps1`、admin-web 36 项、admin-runtime 90 项 | 契约版本：admin `1.3.0-s2b-content`（31 路径 / 34 操作）、public `0.4.0-booking`（15 路径 / 16 操作） |
| 11 | 列明未验证的外部平台项、已知限制与进入 2B 的条件；测试适配器通过不等于正式微信/消息接入通过 | ✅ | 各证据文件的 `limitations` 字段；本文末「未验证的外部平台项」 | 微信登录、支付、通知、存储、域名与 TLS 均未接入真实平台 |

## 未验证的外部平台项（进入 2B 的前置条件）

| 项 | 现状 | 2B 需要 |
|---|---|---|
| 微信登录（`wx.login` → `code2session`） | 只有 `TEST` sink；`WECHAT` 分支代码存在但未真机验证 | 正式 AppID、AppSecret 托管方式、合法域名与 HTTPS |
| 手机号（`getPhoneNumber`） | 未接入；该能力**只对非个人主体且已认证的小程序开放**，且按次计费 | 主体认证、类目资质、费用确认 |
| 通知投递 | 仅 `TEST_SINK`，没有真实通道 | 订阅消息模板、发送配额、失败重试策略 |
| 支付/定金 | **明确不实现**，全链路无支付入口；Q02 决定阶段二不收款 | 2B 之后的独立立项 |
| 生产存储与媒体 CDN | 本地文件系统适配器 | 对象存储、访问策略、回源与缓存 |
| 生产数据库与备份 | 只有隔离实例 | 生产实例、最小授权、备份恢复演练 |

## 已知限制

- 并发结论建立在 InnoDB 唯一约束与条件更新上，属于**单实例、单事务边界**内的正确性；未做多实例压测或故障注入。
- 隔离 L5 的 MySQL 账号只在本次 owned schema 内有重建权限，不能作为生产最小授权证据。
- outbox 只承诺"至少一次 + 人工恢复"，不承诺 exactly-once，也不承诺外部已读。
- 小程序证据来自微信开发者工具的自动化通道，不是真机；真机 UAT 属 2B。
