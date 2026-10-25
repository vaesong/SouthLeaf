# 实施与检查顺序

1. 已读相关 specs、源码/脚本及方案；保存哈希/历史测试清单，确认旧服务输出。
2. 迁移源树到 public-api，再抽离 admin core/JDBC/codec/CLI 及资源/测试；建立六子 POM。
3. 提取 policy，替换 test-only token fixture；保留全部原测试意图并新增隔离/入口测试。
4. 修改脚本显式定位模块，增加产物检查；不执行公开迁移/初始化/恢复脚本，不更改 Local-Credentials。
5. 串行格式检查/单元测试/隔离 MySQL verify，模块产物/jdeps 检查；失败后先修复不并行重跑。
6. 用新 public jar 与原 Reader 配置、候选端口启动只读进程，执行公开 smoke/前端契约/负向启动测试后只结束本任务进程。旧 j8/j354 不动。
7. 前端 verify、文档、差异、编辑器诊断；检查旧 runtime classes/迁移哈希和发现测试实例；回写结构 spec/跟踪/证据。

测试命令基于 backend/scripts/Test-DatabaseIntegration.ps1（调用 reactor verify），node 模块产物检查、frontend npm verify/contract。真实 DB 只用原受控 southleaf_public_it fixture，不新增授予权限。缺环境标 BLOCKED。

当前平台无 Trellis 子代理调度工具，主会话按同一实施/检查清单执行，并单独进行复核，不声称独立子代理审查。用户已确认上轮完整计划，本批 PRD/design/implement 是其无新增范围的落地。
