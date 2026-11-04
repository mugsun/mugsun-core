# 贡献指南

本仓库的贡献流程以账号默认指南为准（中文）：

**[贡献指南](https://github.com/mugsun/.github/blob/main/CONTRIBUTING.md)** · [English](https://github.com/mugsun/.github/blob/main/CONTRIBUTING_EN.md)

安全漏洞请按 [SECURITY.md](https://github.com/mugsun/.github/blob/main/SECURITY.md) 私密报告，不要公开开 Issue。

## 本仓要点

- 默认分支：`main`
- 提交信息：Conventional Commits，主题中文；详见 [`.github/COMMIT_CONVENTION.md`](./.github/COMMIT_CONVENTION.md)（若存在）
- 变更记录：见 [CHANGELOG.md](./CHANGELOG.md)
- 文档：[docs.mugsun.com](https://docs.mugsun.com/)
- 当前 tip / 开发版：`0.2.0-SNAPSHOT`（以根 `pom.xml` 为准）；下游联调须先 `mvn -B clean install -DskipTests`
- Maven Central 现仅 `0.1.0`（旧发布，勿当 tip）
- 许可：源码与 tip 为 MIT；Central 上 `0.1.0` 发布时声明为 Apache-2.0
- 发布：打 GitHub Release 或手动运行 `Publish to Maven Central` workflow
