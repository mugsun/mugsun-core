# AGENTS.md — mugsun-core

本文件写给在本仓工作的开发者和 AI 助手。内容以本仓代码为准；与代码不一致时以代码为准，并请顺手修正本文件。

## 仓库是什么

mugsun 的基础库：BOM 加若干 starter，发布到 Maven Central（版本见根 `pom.xml`）。当前开发版 `0.3.0-SNAPSHOT`（基于 Spring Boot 4；`0.2.x` 是 Spring Boot 3.5，在分支 `release/0.2.0`）不在 Central，mugsun-boot 依赖它，要先在本仓 `mvn -B install` 装到本地。**业务代码不放这里**，放在 mugsun-boot。

| 模块 | 包 | 内容 |
| --- | --- | --- |
| `mugsun-bom` | — | 依赖版本（MyBatis-Flex、Sa-Token 等），只有 `pom.xml` |
| `mugsun-core-tool` | `com.mugsun.core.tool` | `api.R` / `api.ResultCode`，`exception.ServiceException` / `ForbiddenException`，`tree.TreeUtil` / `INode` |
| `mugsun-starter-i18n` | `com.mugsun.core.i18n` | `message.I18n` / `MessageTranslator`，`source.MugsunMessageSource` / `MessageBundles` / `MessageProvider`，`locale.LocaleNegotiator`，`web.TranslateResponseAdvice` / `AcceptLanguageLocaleResolver` / `LocaleContextFilter`，`config.MugsunI18nAutoConfiguration` |
| `mugsun-starter-lock` | `com.mugsun.core.lock` | `annotation.DistributedLock`，`template.LockTemplate` / `LockHandle`，`client.LockClient`（`RedisLockClient` / `LocalLockClient`），`exception.LockFailedException`，`config.MugsunLockAutoConfiguration` |
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

## 分布式锁

`mugsun-starter-lock`：`@DistributedLock`（方法上）和 `LockTemplate`（代码里）两种用法，锁存在哪里由 `LockClient` 决定。

- 零配置：应用里有 Redis 连接就用 `RedisLockClient`，没有就退回 `LocalLockClient` 并打一条 WARN；`mugsun.lock.type=redis` 时找不到 Redis 拒绝启动。Redis 是可选依赖，引用 Redis 类的代码只能放在 `RedisLockClient` 和 `RedisLockClientFactory` 里。
- 不传租期 = 占到释放为止并自动续期（默认租期 30 秒，每三分之一续一次）；传了租期 = 固定租期不续。改这条默认行为属于破坏性变更。
- 切面顺序 `DistributedLockAspect.ORDER`（`LOWEST_PRECEDENCE - 1000`）：在事务外面、在大多数切面里面。
- 释放和续期必须比对持有者令牌（Redis 里用脚本一次做完），不能改成直接删键。
- `RedisLockClientTest` 用 Testcontainers 起真的 Redis，没有 Docker 的机器自动跳过；改了 Redis 命令或脚本要在有 Docker 的机器上跑过它。

## 多语言

`mugsun-starter-i18n`：返回前把 `R.msg` 翻译成请求的语言（`TranslateResponseAdvice`），代码里取文案用 `I18n.get`，语言由 `LocaleNegotiator` 按请求头 `Accept-Language` 定。

- 零配置：类路径 `i18n/*.properties` 自动读（文件名 `名字_语言.properties`，键是中文原文，一行里第一个等号左边整个是键），类路径根目录的 `messages*.properties` 也读。支持的语言 = 默认语言 + 简体中文 + 有翻译文件的语言；`mugsun.i18n.supported-locales` 写了就只认默认语言和写的那几种。
- 找文案从具体到笼统（`zh_TW` → `zh` → 不带语言的兜底文案），不回退到服务器操作系统的语言；找不到原样返回，不报错。改这些默认行为属于破坏性变更。
- 各模块返回给用户的提示语（`ResultCode`、抛给前端的 `ServiceException`）新增或改字时，同步改 `mugsun-starter-i18n/src/main/resources/i18n/mugsun-core_en.properties`，键要和代码里的中文一字不差。`BuiltInTranslationsTest` 只查得出 `ResultCode` 漏翻译，别的靠自己核对。
- 只有 `web` 包和 `MugsunI18nAutoConfiguration.ServletI18nConfiguration` 可以引用 Servlet、Spring MVC、spring-web 的类：网关和定时任务也要能引入这个模块，`MugsunI18nAutoConfigurationTest` 里有一条测试把关。
- `TranslateResponseAdvice` 只动 `msg` 不动 `data`；换提示语时复制一份 `R`，不改原对象（原对象可能是共用的常量）。顺序 `TranslateResponseAdvice.ORDER`（`HIGHEST_PRECEDENCE + 1000`）排在响应加密前面。
- 过滤器里的语言由 `LocaleContextFilter`（顺序 `HIGHEST_PRECEDENCE + 5`）管：用名为 `localeResolver` 的 Bean 定语言、放成当前语言，并把请求包一层让 `request.getLocale()` 返回它。它必须排在 Spring Boot 的 `RequestContextFilter`（顺序 -105）前面，包请求那一步也不能去掉：`RequestContextFilter` 会按 `request.getLocale()` 重设当前语言。`localeResolver` 到第一次请求时才取，不要改成创建时注入。请求处理完往回走时，排在 `RequestContextFilter` 前面的过滤器取到的是默认语言，那里要用 `I18n.negotiate(请求头)` 自己定。
- `messageSource` 在容器启动的很早阶段创建，`MessageProvider` 要等第一次查文案才去容器里找（`LazyProviders`），不要改成创建时注入，否则会把数据库连接过早拉起来。
- 查文案的路上出错（`MessageProvider` 报错或建不起来、应用自己的 `messageSource` 报错）不能让请求失败：改用翻译文件里的文案或原文，同时记一条 WARN（同一类错一分钟最多一条）。这些地方不要改成不记日志的空 `catch`；`MessageProvider` 建不起来时一分钟后才重试，不要改成每次查文案都重试。

## 组件速查

| 需求 | 类 |
| --- | --- |
| 统一返回 | `com.mugsun.core.tool.api.R` |
| 结果码 | `com.mugsun.core.tool.api.ResultCode` |
| 实体基类 | `com.mugsun.core.mybatis.base.BaseEntity` |
| 树构建 | `com.mugsun.core.tool.tree.TreeUtil` |
| Excel | `com.mugsun.core.web.excel.ExcelUtil` |
| 加解密注解 | `com.mugsun.core.web.crypto.ApiEncrypt` / `ApiDecrypt` |
| 分布式锁 | `com.mugsun.core.lock.annotation.DistributedLock` / `com.mugsun.core.lock.template.LockTemplate` |
| 多语言取文案 | `com.mugsun.core.i18n.message.I18n` |

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
- 提交的作者和提交者一律用 `mugsun <bydongxin@gmail.com>`：只在仓库级配置（`git config --local`）里设，或用 `git -c user.name=… -c user.email=…` 传入，不改全局配置。已推送的历史不改；未推送的提交推送前用 `git rebase --exec 'git commit --amend --no-edit --reset-author'` 重做，并确认提交者也是 bydongxin@gmail.com。这条 rebase 只用于未推送的提交；已推送的提交改身份要从 origin/main 新开分支 cherry-pick 后 `commit --amend --reset-author`，旧分支不动。
- 合入 main 后，作者自己删掉远端分支（`git push origin --delete <分支>`）。
- 接口加密公开测试向量在 `mugsun-starter-web/src/test/resources/crypto/api-crypto-vectors.json`，`.gitleaks.toml` 要路径、精确值和单条规则（generic-api-key）同时匹配才放行；`.gitleaks.toml` 的任何改动都要安全官审查后再合。
- **只有分支最新的 commit 已经在分支上跑过同名必需检查、并且全绿，才可以快进推入 main。推送前用 `gh run list --commit <sha>` 自己确认。不要 force push。**
- 推 main 前的防误推（同步清单 3.20）：
  1. 先核对这个完整 SHA 已经审过（审查结论里写的就是它），并且 `gh run list --commit <完整SHA>` 显示同名检查全绿；短 SHA 查到空不算核对。
  2. 推送一律写全 refspec：`git push origin <sha>:refs/heads/<分支>`，不用裸 `git push`、`HEAD` 或依赖 upstream 跟踪。
  3. 还没审的本地提交，不放在跟踪 main（upstream 为 origin/main）的分支上。
  4. 改动 `.github/` 下任何文件的提交，必须先有安全官签字，才能进 main。
  5. 一个 worktree 只给一条工作线用，另开的工作线一律新建自己的 worktree；推送前用 `git rev-parse HEAD` 对照审过的 SHA。被打断的 push 先用 `git ls-remote` 和 `ps` 确认没有在后台跑完，再做下一步。
- 改 `.github/workflows/`、BOM 依赖版本或加解密实现属于敏感改动，需安全审查。
