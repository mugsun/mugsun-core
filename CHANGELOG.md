# 更新日志

本文件记录 [mugsun-core](https://github.com/mugsun/mugsun-core) 的重要变更，格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循
[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 不兼容变更

- 升级到 Spring Boot 4.1.3，开发版本改为 `0.3.0-SNAPSHOT`
  - `0.2.x` 仍基于 Spring Boot 3.5，在分支 `release/0.2.0`
  - 随 Boot 升级：Spring Framework 7、Spring Security 7.1、Servlet 6.1
  - BOM 里的构件换成各库的 Boot 4 版，工程里直接写构件名的要跟着改：
  - `sa-token-spring-boot3-starter` → `sa-token-spring-boot4-starter`
  - `warm-flow-mybatis-sb3-starter` → `warm-flow-mybatis-sb4-starter`
  - `liteflow-spring-boot-starter` → `liteflow-spring-boot4-starter`
  - 新增 `mybatis-flex-spring-boot4-starter`，`mugsun-starter-mybatis` 改引它
  - `mugsun-starter-web` 改引 `spring-boot-starter-webmvc`
  - 版本：Sa-Token 1.46.0、warm-flow 1.8.9、LiteFlow 2.16.3、PowerJob 5.1.7
  - 版本：springdoc 3.1.1（2.x 只支持 Boot 3）、mybatis-spring 4.0.0
  - JSON 暂时仍用 Jackson 2：`mugsun-starter-web` 引 `spring-boot-jackson2`
  - 新增 `Jackson2PreferenceEnvironmentPostProcessor`，给三个配置项设默认值
  - `spring.http.converters.preferred-json-mapper=jackson2`
  - `spring.http.codecs.preferred-json-mapper=jackson2`
  - `spring.websocket.messaging.preferred-json-mapper=jackson2`
  - 默认值排在所有配置来源最后，应用写了同名配置项以应用的为准
  - 不设的话接口不报错，但 Long 不再转字符串，前端的大整数会丢精度
  - 全部换到 Jackson 3 是下一步，到时去掉这个类和 `spring-boot-jackson2`

- 开发版本改为 `0.2.0-SNAPSHOT`；mugsun-boot 依赖这个版本

- 接口加密去掉内置密钥，改为 SM4-GCM；不配也能启动，`MUGSUN_API_KEY` 可选覆盖
  - 未配置时首次启动生成 16 字节密钥，存 `~/.mugsun/api-transport.key`
  - 文件 600、目录 700，原子写入，文件锁防并发首启，重启复用
  - 目录可用 `mugsun.home` / `MUGSUN_HOME` 覆盖；日志只写路径
  - 权限比 600 宽、非 POSIX、容器内未配密钥时打 WARN，不拒绝启动
  - Docker 不挂卷或多实例部署必须配 `MUGSUN_API_KEY` 或挂共享卷
  - 配置值解码后等于已删除的旧内置默认值时拒绝启动（hex/base64 写法同样）
  - 移除 `allow-ephemeral-keys`；`strict-keys` 不再影响接口加密
  - 新增 `transportKeyHex()`，只供已登录会话的启动下发接口使用
  - 密钥须为 32 位 hex 或带填充的 16 字节标准 base64，不补零不截断
  - 密文改为 base64(IV 12 字节 ‖ 密文 ‖ 标签)，旧 CBC 密文不再兼容
  - 解密失败统一返回同一错误；只是传输混淆，不能代替 HTTPS
  - 不防重放、不绑定接口（暂无 AAD）
  - `encrypt(null)`、`decrypt(null)` 都返回 null，不再抛异常
  - 跨端向量补 4 条：U+3000、U+2028 被 strip 去掉，NBSP、BOM 不去判非法

### 新增

- 新增 `mugsun-starter-lock`：分布式锁，BOM 里已登记版本
  - 两种用法：方法上加 `@DistributedLock`，或者注入 `LockTemplate`
  - 零配置：应用里有 Redis 连接就用 Redis，没有就退回单机锁并打一条 WARN
  - `mugsun.lock.type`：`auto`（默认）、`redis`（找不到 Redis 拒绝启动）、`local`
  - 不写租期时占到方法结束，期间自动续期；进程崩溃后 30 秒内自动放开
  - 写了 `leaseTime` 是固定租期，到时间一定放开，不续期
  - 同一线程可重入；释放和续期都比对持有者令牌，不会放掉别人的锁
  - 切面排在事务外面：先拿锁再开事务，事务提交后才放锁
  - 没抢到锁默认抛 `LockFailedException`（`ServiceException` 的子类）
  - `onFail = SKIP` 时不执行也不报错，给多实例的定时任务用
  - 不引入新的第三方库：Redis 走 Spring Data Redis，是可选依赖

- 新增 `mugsun-starter-i18n`：后端多语言，BOM 里已登记版本
  - 提示语跟着请求头 `Accept-Language` 走，默认简体中文，不用写配置
  - 代码里写死的中文提示不用改：返回前拿 `R.msg` 当键查翻译
  - 查不到翻译原样返回，不报错；只动 `msg`，不动 `data`
  - 翻译文件放 `i18n/` 目录，文件名 `名字_语言.properties`，用 UTF-8
  - 一行里第一个等号左边整个是键，空格和冒号直接写
  - 类路径根目录的 `messages*.properties` 也读，按 Spring Boot 的习惯
  - 带变量的句子用 `I18n.get("用户 {0} 不存在", name)`，也可以用码做键
  - 占位里的值和用加号拼字符串时一样显示（数字不加千分位），要千分位写 `{0,number}`
  - 校验注解的提示语、`{码}` 写法的提示语查的是同一套翻译文件
  - 过滤器里取到的语言和控制器里一样，自己写响应时用 `I18n.translate`
  - 网关这类拿不到当前语言的地方：先 `I18n.negotiate(请求头)` 再翻译
  - 支持的语言自动定：默认语言、简体中文，再加上有翻译文件的语言
  - 找文案从具体到笼统再到兜底文案，不回退到服务器操作系统的语言
  - 框架自己的提示语自带英文翻译，应用写同一个键可以换掉
  - 文案放数据库时实现 `MessageProvider`，查文案时先问它
  - 查文案出错不让请求失败：改用翻译文件或原文，并在日志里记一条 WARN
  - `mugsun.i18n.default-locale`：默认语言，默认 `zh-CN`
  - `mugsun.i18n.supported-locales`：写了就只认默认语言和写的这几种
  - `mugsun.i18n.translate-response=false`：不翻译返回体
  - `mugsun.i18n.enabled=false`：整个关掉
  - 不是 Spring MVC 的应用也能引入，只是不按请求头定语言、不翻译返回体
  - 不引入新的第三方库

### CI

- CI 改为推送任意分支即运行，分支先绿再快进 main
  - `ci.yml` 触发改为 push `**`，新增 `workflow_dispatch`
  - 加 concurrency：同一分支新推送取消旧运行
  - main 上不取消正在跑的运行
  - main 按 SHA 单独分组，排队中的运行不被后来的推送顶掉
  - `'**'` 只匹配分支，不匹配 tag

- 收紧 workflow 权限并把 Actions 钉到 commit SHA
  - 每个 workflow 顶层 `permissions: contents: read`，禁用 `pull_request_target`
  - Actions 钉 40 位 SHA，行尾注释版本号
  - 新增 `scripts/ci/check-workflows.py` 和 actionlint（钉版本、校验 sha256）

- 接入 gitleaks 全历史扫描
  - gitleaks 8.30.1 钉版本、校验 sha256，`--redact` 不输出密钥值
  - 历史上的测试假值和开发默认值写进 `.gitleaks.toml` allowlist
  - allowlist 按 commit + 文件匹配，改动须安全官审

### 变更

- README 中英文重写：安装、接口示例、模块图和接口加解密说明
- 许可证由 Apache-2.0 改为 MIT（见 `LICENSE`）。Maven Central 上已发布的 `0.1.0` 构件仍保留发布时声明的
  Apache-2.0
- 唯一约束冲突（`org.springframework.dao.DuplicateKeyException` 及子类）的 HTTP 状态从 500 改为
  400：响应体 `code` 为 400、`msg` 为「数据已存在，请勿重复提交」，不带 SQL、约束名和字段值。这类错误不再经过
  `GlobalExceptionHandler` 的兜底 500，也就不再发布 `ErrorLogListener`，不再写入错误日志表（boot 的
  `sys_error_log`；boot 里只有 `ErrorLogRecorder` 写这张表，它收不到这类错误，所以不会再记录），只在服务端记一条
  WARN（异常类名、SQLState、厂商错误码）。处理放在 `mugsun-starter-mybatis` 的
  `DuplicateKeyExceptionHandler`（最高优先级，仅 Servlet Web 应用装配），只引
  `mugsun-starter-web` 不引持久层的应用不受影响。其它数据库异常仍是 500
- `mugsun-starter-mybatis` 新增 classpath 根目录的 `sql-error-codes.xml`，
  补上达梦（`DM DBMS`）的唯一键错误码 -6602、-6612、-6625，达梦的唯一约束冲突也会翻译成
  `DuplicateKeyException`。应用自己在 classpath 根目录放了 `sql-error-codes.xml` 时会遮住这份，
  需要把达梦条目抄过去

## [0.1.0] - 2026-10-12

### 新增

- 首发 Maven Central：命名空间 `com.mugsun`，版本 `0.1.0`
- 多模块 BOM 与 starters（含 `mugsun-starter-web` 等）
- 发布配置：`central-publishing-maven-plugin`、GPG 签名、GitHub Actions
  `publish-central.yml`（Release / `workflow_dispatch`）

### 说明

- 坐标示例：`com.mugsun:mugsun-starter-web:0.1.0`
- 构件已在 [Maven Central](https://repo1.maven.org/maven2/com/mugsun/) 可拉取；GitHub
  Release tag 另择时补打

[Unreleased]: https://github.com/mugsun/mugsun-core/commits/main
[0.1.0]: https://repo1.maven.org/maven2/com/mugsun/
