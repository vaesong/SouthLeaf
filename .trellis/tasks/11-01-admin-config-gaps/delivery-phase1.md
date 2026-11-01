# 阶段 1 交付：首页轮播 heroSlides

只做了首页轮播。预约规则、单个档期、按周批量没有动。没有连接或修改 3306，没有提交 git。

## 行为

- 草稿增加 `heroSlides`。旧 JSON 没有这个字段时读成空列表，不拒绝旧修订。
- 最多 5 张，隐藏的也计入。主标题 1–16 字，按钮文字 1–8 字，副标题可空、最长 64 个码点。`ABOUT` 的目标 id 固定为 `about`。
- 保存仍走整份目录草稿。发布和预览只检查**启用**的幻灯片：作品并入现有作品资格，套餐/业务在领域层核对，图片仍走现有就绪检查。隐藏项不进公开快照，也不写公开绑定。
- 公开首页仍输出原来的 `HeroItem`。有可展示的启用项时按顺序投影（目标小写）；目标已不可见或图片绑定不上则跳过；一张都不剩，或本来就没有轮播，则回到精选作品前 3 个。
- 公开绑定 `owner_type` 增加 `HERO`。迁移脚本：`backend/public-api/src/main/resources/db/migration/V4__public_catalog_hero_binding.sql`。未改 `V3`。
- 小程序 `openHero` 按 `work` / `package` / `business` / `about` 跳转。未知类型不跳转。`about` 的 `targetId` 使用 `about`，解析器不用放宽空 id。
- 管理端「首页布置」可编辑轮播。没有启用项时，预览仍用精选作品，并提示会自动显示精选作品前 3 个。

## 验证

| 命令 | 结果 |
|---|---|
| `backend` 下 `.\mvnw.cmd -pl public-api test` | 通过。68 个测试，0 失败。含 `PublicQueryServiceTest`、`HeroSlideJsonTest`。 |
| `backend` 下 `.\mvnw.cmd -pl admin-content test` | 通过。22 个测试，0 失败。含草稿与发布资格。 |
| `backend` 下 `.\mvnw.cmd -pl admin-runtime -am test` | 通过。`admin-runtime` 112 个测试，0 失败。含旧草稿 JSON 缺 `heroSlides` 的反序列化。同一次反应器里依赖模块的单元测试也通过。 |
| `frontend` 下 `npm run verify` | 通过。格式检查、78 个测试、文档链接检查均无错误。 |
| `admin-web` 下 `npm run typecheck` | 通过。 |
| `admin-web` 下 `npm test` | 通过。54 个测试。 |
| `admin-web` 下 `npm run build` | 通过。 |
| `admin-web` 下 `npm run format:check` | 通过。 |
| 已有的 mock 开发服务 `http://localhost:5173/home-layout` | 打开后能看到三张示例轮播；编辑第一张能改标题、按钮和跳转；选「关于我们」后作品下拉消失，并出现「打开后进入关于我们。」手机预览第一屏文案是「本地样例 / 虚构窗边 / 查看详情」。 |

单独执行 `-pl admin-runtime test`（不带 `-am`）会用到仓库里过期的已安装依赖，编译报缺符号。带 `-am` 从源码编译后通过。这不是本阶段引入的源码错误。

## 未运行

- `backend/scripts/Test-DatabaseIntegration.ps1` 和 `DatabasePublicApiIT` 没有跑。脚本在缺少显式隔离环境变量时会去读本机凭据库；本任务不使用真实凭据，也不连接 3306。因此 **V4 还没有在 MySQL 上执行过**。公开库要先用现有迁移入口应用 `V4`，发布带轮播图片的目录时 `HERO` 绑定才能写入。在此之前，带启用轮播的发布会在绑定插入处失败。
- 没有跑小程序模拟器。跳转覆盖在 `frontend/tests/native-pages.test.mjs` 里用页面方法断言。

## 不确定

- 决策没有给副标题上限。实现里用 64 个码点，超长为 `HERO_FIELD_INVALID`。主标题和按钮按码点计「字」。
- 隐藏幻灯片的图片在**保存**时也必须出现在图片描述集合里，否则仍是 `MEDIA_ALT_SET_MISMATCH`。发布时「目标是否可公开、图片是否就绪」只查启用项。
- 作品/套餐/业务选择器的可见文字是名称。选项的 value 仍是 id，无障碍树里能看到，页面上不把 id 当标题展示。
