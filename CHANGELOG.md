# 更新日志

本文件记录 [mugsun-core](https://github.com/mugsun/mugsun-core) 的重要变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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

- 许可证由 Apache-2.0 改为 MIT（见 `LICENSE`）。Maven Central 上已发布的 `0.1.0` 构件仍保留发布时声明的 Apache-2.0
- 唯一约束冲突（`org.springframework.dao.DuplicateKeyException` 及子类）的 HTTP 状态从 500 改为 400：响应体 `code` 为 400、`msg` 为「数据已存在，请勿重复提交」，不带 SQL、约束名和字段值。这类错误不再经过 `GlobalExceptionHandler` 的兜底 500，也就不再发布 `ErrorLogListener`，不再写入错误日志表（boot 的 `sys_error_log`；boot 里只有 `ErrorLogRecorder` 写这张表，它收不到这类错误，所以不会再记录），只在服务端记一条 WARN（异常类名、SQLState、厂商错误码）。处理放在 `mugsun-starter-mybatis` 的 `DuplicateKeyExceptionHandler`（最高优先级，仅 Servlet Web 应用装配），只引 `mugsun-starter-web` 不引持久层的应用不受影响。其它数据库异常仍是 500
- `mugsun-starter-mybatis` 新增 classpath 根目录的 `sql-error-codes.xml`，补上达梦（`DM DBMS`）的唯一键错误码 -6602、-6612、-6625，达梦的唯一约束冲突也会翻译成 `DuplicateKeyException`。应用自己在 classpath 根目录放了 `sql-error-codes.xml` 时会遮住这份，需要把达梦条目抄过去

## [0.1.0] - 2026-10-12

### 新增

- 首发 Maven Central：命名空间 `com.mugsun`，版本 `0.1.0`
- 多模块 BOM 与 starters（含 `mugsun-starter-web` 等）
- 发布配置：`central-publishing-maven-plugin`、GPG 签名、GitHub Actions `publish-central.yml`（Release / `workflow_dispatch`）

### 说明

- 坐标示例：`com.mugsun:mugsun-starter-web:0.1.0`
- 构件已在 [Maven Central](https://repo1.maven.org/maven2/com/mugsun/) 可拉取；GitHub Release tag 另择时补打

[Unreleased]: https://github.com/mugsun/mugsun-core/commits/main
[0.1.0]: https://repo1.maven.org/maven2/com/mugsun/
