# 更新日志

本文件记录 [mugsun-core](https://github.com/mugsun/mugsun-core) 的重要变更，格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循
[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 不兼容变更

- 开发版本改为 `0.2.0-SNAPSHOT`；mugsun-boot 依赖这个版本

- 接口加密去掉内置密钥，改为 SM4-GCM；不配也能启动，`MUGSUN_API_KEY` 可选覆盖
  - 未配置时首次启动生成 16 字节密钥，存 `~/.mugsun/api-transport.key`
  - 文件 600、目录 700，原子写入，文件锁防并发首启，重启复用
  - 目录可用 `mugsun.home` / `MUGSUN_HOME` 覆盖；日志只写路径
  - 权限比 600 宽、非 POSIX、容器内未配密钥时打 WARN，不拒绝启动
  - Docker 不挂卷或多实例部署必须配 `MUGSUN_API_KEY` 或挂共享卷
  - 配置值等于已删除的旧内置默认值时拒绝启动
  - 移除 `allow-ephemeral-keys`；`strict-keys` 不再影响接口加密
  - 新增 `transportKeyHex()`，只供已登录会话的启动下发接口使用
  - 密钥须为 32 位 hex 或带填充的 16 字节标准 base64，不补零不截断
  - 密文改为 base64(IV 12 字节 ‖ 密文 ‖ 标签)，旧 CBC 密文不再兼容
  - 解密失败统一返回同一错误；只是传输混淆，不能代替 HTTPS
  - 不防重放、不绑定接口（暂无 AAD）
  - `encrypt(null)`、`decrypt(null)` 都返回 null，不再抛异常
  - 跨端向量补 4 条：U+3000、U+2028 被 strip 去掉，NBSP、BOM 不去判非法

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
