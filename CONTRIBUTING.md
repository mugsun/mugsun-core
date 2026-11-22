# 贡献指南

本仓库的贡献流程以账号默认指南为准（中文）：

**[贡献指南](https://github.com/mugsun/.github/blob/main/CONTRIBUTING.md)** · [English](https://github.com/mugsun/.github/blob/main/CONTRIBUTING_EN.md)

安全漏洞请按 [SECURITY.md](https://github.com/mugsun/.github/blob/main/SECURITY.md) 私密报告，不要公开开 Issue。

## 本仓要点

- 默认分支：`main`
- 提交信息：Conventional Commits，主题中文；详见 [`.github/COMMIT_CONVENTION.md`](./.github/COMMIT_CONVENTION.md)（若存在）
- 变更记录：见 [CHANGELOG.md](./CHANGELOG.md)
- 文档：[docs.mugsun.com](https://docs.mugsun.com/)
- 最新发布版：`0.3.0`，基于 Spring Boot 4，在 Maven Central；`0.2.x` 是 Spring Boot 3.5，在分支 `release/0.2.0`
- 主干的开发版本以根 `pom.xml` 为准，不在 Central；下游要联调还没发布的改动，须先 `mvn -B clean install -DskipTests`
- Maven Central 上的 `0.1.0` 是最早的发布，不要再用
- 许可：源码与 tip 为 MIT；Central 上 `0.1.0` 发布时声明为 Apache-2.0
- 发布：打 GitHub Release 或手动运行 `Publish to Maven Central` workflow
