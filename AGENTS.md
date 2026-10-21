# AGENTS.md — mugsun-core

本文件写给在本仓工作的开发者和 AI 助手。内容以本仓代码为准；与代码不一致时以代码为准，并请顺手修正本文件。

## 仓库是什么

mugsun 的基础库：BOM 加若干 starter，发布到 Maven Central（版本见根 `pom.xml`）。当前开发版 `0.2.0-SNAPSHOT` 不在 Central，mugsun-boot 依赖它，要先在本仓 `mvn -B install` 装到本地。**业务代码不放这里**，放在 mugsun-boot。

| 模块 | 包 | 内容 |
| --- | --- | --- |
| `mugsun-bom` | — | 依赖版本（MyBatis-Flex、Sa-Token 等），只有 `pom.xml` |
| `mugsun-core-tool` | `com.mugsun.core.tool` | `api.R` / `api.ResultCode`，`exception.ServiceException` / `ForbiddenException`，`tree.TreeUtil` / `INode` |
| `mugsun-starter-mybatis` | `com.mugsun.core.mybatis` | `base.BaseEntity`，`web.DuplicateKeyExceptionHandler`，`config.MugsunMybatisWebAutoConfiguration` |
| `mugsun-starter-web` | `com.mugsun.core.web` | `handler.GlobalExceptionHandler`，`crypto.*`，`excel.ExcelUtil`，`jackson.SafeNumberModule`，`config.MugsunWebAutoConfiguration` |

## 分层与命名

- 包名：`com.mugsun.core.<模块>.<子包>`。
- starter 通过 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册自动配置；新增自动配置类必须登记到该文件。
- 这里的类被所有下游仓依赖：改公开类签名、字段或默认行为属于破坏性变更，提交用 `type!:` 并在 CHANGELOG 写明。

本仓**没有** Flyway、租户上下文或数据权限实现；那些在 mugsun-boot。

## 实体基类

`com.mugsun.core.mybatis.base.BaseEntity`：

- `id`：雪花主键（`KeyGenerators.flexId`）
- `createTime` / `updateTime`：数据库 `now()` 填充
- `isDeleted`：逻辑删除（0 正常 / 1 删除）
- `sanitizeForInsert()` / `sanitizeForUpdate()`：清掉请求体伪造的审计字段

`tenantId` 不在基类里，由下游实体按需声明。

## 返回体与异常

- `R.data(data)` / `R.success(msg)` / `R.fail(msg)` / `R.fail(ResultCode)` / `R.fail(ResultCode, msg)`
- 业务错误抛 `ServiceException`，无权限抛 `ForbiddenException`，由 `GlobalExceptionHandler` 转换

## 接口加解密

`@ApiEncrypt`（响应加密）、`@ApiDecrypt`（请求解密）可标在类或方法上，由 `EncryptResponseAdvice`、`DecryptRequestAdvice`、`ApiCryptoService` 处理。改加密格式时须与前端（mugsun-pc）同步，并补齐本仓 `mugsun-starter-web` 下相关测试。

## 组件速查

| 需求 | 类 |
| --- | --- |
| 统一返回 | `com.mugsun.core.tool.api.R` |
| 结果码 | `com.mugsun.core.tool.api.ResultCode` |
| 实体基类 | `com.mugsun.core.mybatis.base.BaseEntity` |
| 树构建 | `com.mugsun.core.tool.tree.TreeUtil` |
| Excel | `com.mugsun.core.web.excel.ExcelUtil` |
| 加解密注解 | `com.mugsun.core.web.crypto.ApiEncrypt` / `ApiDecrypt` |

## 测试与构建

```bash
mvn -B install              # CI 同款：编译、测试、安装到本地仓库
mvn -B install -DskipTests  # 仅为下游仓提供本地快照
python3 scripts/ci/check-workflows.py .github/workflows
```

发布中央仓库走 `.github/workflows/publish-central.yml`（`-Prelease`），日常开发不要本地 `deploy`。

## 本地构建

- 重型任务（构建、类型检查、e2e、测试容器、dev server 等）一律经仓库父目录 `mugsun/` 下的 `_locks/heavy.sh` 排队，同一时间只跑一个：
  ```bash
  ../_locks/heavy.sh <名字> <做什么> -- <命令...>
  # 例：../_locks/heavy.sh 平台后端工程师 "core 单测" -- mvn -q test
  ```
- 前面有任务时脚本每 30 秒重试并打印当前占用者；空闲内存低于 30% 时不开跑（退出码 75），先停掉闲置的 dev server 再试。
- 脚本会在未设置时自动设 `NODE_OPTIONS=--max-old-space-size=3072`，不要在命令里再调大。
- dev server 用完即停，不要挂着。

## 提交与推送

- Conventional Commits，主题中文，不超过 50 字，不用 emoji；详见 `.github/COMMIT_CONVENTION.md`。
- `CHANGELOG.md` 写在 `## [Unreleased]` 下，**每行不超过 80 字**。
- 不用 `git stash`；切换工作用 `git worktree`。
- 提交的作者和提交者一律用 `Mugsun <100790155+mugsun@users.noreply.github.com>`：只在仓库级配置（`git config --local`）里设，或用 `git -c user.name=… -c user.email=…` 传入，不改全局配置。已推送的历史不改；未推送的提交推送前用 `git rebase --exec 'git commit --amend --no-edit --reset-author'` 重做，并确认提交者也是 noreply。这条 rebase 只用于未推送的提交；已推送的提交改身份要从 origin/main 新开分支 cherry-pick 后 `commit --amend --reset-author`，旧分支不动。
- 接口加密公开测试向量在 `mugsun-starter-web/src/test/resources/crypto/api-crypto-vectors.json`，`.gitleaks.toml` 按路径放行；`.gitleaks.toml` 的任何改动都要安全官审查后再合。
- **只有分支最新的 commit 已经在分支上跑过同名必需检查、并且全绿，才可以快进推入 main。推送前用 `gh run list --commit <sha>` 自己确认。不要 force push。**
- 改 `.github/workflows/`、BOM 依赖版本或加解密实现属于敏感改动，需安全审查。
