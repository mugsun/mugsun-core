![mugsun-core](https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/repos/mugsun-core.png)

# mugsun-core

**中文** | [English](./README_EN.md)

本仓库为 mugsun 平台共享内核。提供 BOM、通用工具与 Spring Boot Starter，供业务工程以 Maven 坐标引入。统一响应体、Sa-Token 注解鉴权、接口加解密与 MyBatis-Flex 实体基类在此装配。本仓库不提供可执行 HTTP 入口。

[文档](https://docs.mugsun.com/core/) · [架构](https://docs.mugsun.com/guide/architecture) · [Maven Central](https://central.sonatype.com/search?q=com.mugsun)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring%20Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![Maven%20Central](https://img.shields.io/maven-central/v/com.mugsun/mugsun-starter-web)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

## 概述

| 项 | 说明 |
| --- | --- |
| 坐标 | `com.mugsun:mugsun-*:0.1.0`（已发布至 [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)） |
| 运行时 | JDK 21；Maven 3.9+ |
| 父工程 | Spring Boot 3.5.13 |
| 许可 | [MIT](./LICENSE) |
| 文档 | [docs.mugsun.com/core](https://docs.mugsun.com/core/) |

技术组件（以当前 POM 为准）：MyBatis-Flex 1.11.8、Sa-Token 1.44.0、JetCache、Hutool Crypto / BouncyCastle（接口国密）、FastExcel 等，版本由 `mugsun-bom` 统一锁定。

## 部署

业务工程优先从 Maven Central 引入，无需平级 `mvn install`。

在 `dependencyManagement` 中导入 BOM，再按需声明 Starter：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.mugsun</groupId>
      <artifactId>mugsun-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.mugsun</groupId>
    <artifactId>mugsun-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>com.mugsun</groupId>
    <artifactId>mugsun-starter-mybatis</artifactId>
  </dependency>
</dependencies>
```

可运行后端见 [mugsun-boot](https://github.com/mugsun/mugsun-boot)。联调整条竖切见 [文档 · 快速开始](https://docs.mugsun.com/guide/getting-started)。

**本地 install（离线或未同步 Central 时的回退）：**

```bash
git clone https://github.com/mugsun/mugsun-core.git
cd mugsun-core
mvn clean install -DskipTests
```

发布至 Central：对本仓库打 GitHub Release，或手动运行 Actions 工作流 **Publish to Maven Central**（`workflow_dispatch` / `release` published）。本地等价命令为 `mvn -Prelease clean deploy -DskipTests`（须配置 Portal 令牌与 GPG）。

## 接口加密配置（开箱即用、可选覆盖）

`mugsun-starter-web` 的 `@ApiDecrypt`/`@ApiEncrypt` 用 SM4-GCM，带完整性校验。

**这只是传输混淆，不能代替 HTTPS。** 对称密钥要下发到浏览器，对客户端不保密。

不防重放、不绑定接口：密文没有绑定方法和路径，可被原样重发或搬到另一个加密接口。

| 配置 | 默认 | 说明 |
|---|---|---|
| `mugsun.crypto.api-key` | 空 | 环境变量 `MUGSUN_API_KEY`；可选，配了就用配的 |
| `mugsun.home` | `~/.mugsun` | 环境变量 `MUGSUN_HOME`；自动生成的密钥文件放这里 |

- 不配也能启动：首次启动用 SecureRandom 生成 16 字节密钥，
  写入 `<mugsun.home>/api-transport.key`（目录 700、文件 600），之后重启复用。
- 写入方式：先写临时文件再原子 rename；并发首启用文件锁，只生成一次。
- 日志只写文件路径，不写密钥；文件权限比 600 宽时打 WARN，不拒绝启动。
- 配了 `MUGSUN_API_KEY` 时：须为 32 位 hex，或带填充的标准 base64 且解码后正好 16 字节，
  格式不对就拒绝启动，不补零也不截断。生成示例：`openssl rand -hex 16`。
- 等于已删除的旧内置默认值时拒绝启动。
- 以下部署必须配 `MUGSUN_API_KEY` 或给 `mugsun.home` 挂共享卷：
  - Docker 没给该目录挂卷：每次重启都会换新密钥；
  - 多实例：各自生成的密钥不一致，互相解不开。
  检测到容器环境（`/.dockerenv`、cgroup）且没配密钥时打 WARN。
- 这把密钥只用于传输混淆，会下发给已登录的前端；不得用于落库加密、派生签名，也不能和签名密钥共用。
- `allow-ephemeral-keys` 已移除；`strict-keys` 不再影响接口加密。
- 密文格式：`base64(IV 12 字节 ‖ 密文 ‖ 标签 16 字节)`，必须带填充。
- 跨端测试数据：`mugsun-starter-web/src/test/resources/crypto/api-crypto-vectors.json`。
- 旧的 CBC 密文不再兼容，前端要同步改成 SM4-GCM。
- 解密失败一律返回同一个错误，不区分原因。

## 组成

```text
mugsun-core/
  mugsun-bom/            # 依赖版本锁定（含 mybatis-spring → 3.0.5 覆盖）
  mugsun-core-tool/      # R / ResultCode / 业务异常 / TreeUtil
  mugsun-starter-web/    # 全局异常、Sa-Token 注解拦截、ApiCrypto、SafeNumber、Excel
  mugsun-starter-mybatis/ # MyBatis-Flex starter + BaseEntity（雪花主键、审计时间、逻辑删除）
```

| 模块 | 职责 |
| --- | --- |
| `mugsun-bom` | 统一锁定本仓构件及 Flex、Sa-Token、JetCache 等第三方版本 |
| `mugsun-core-tool` | 无 Spring 依赖的通用 API 与工具 |
| `mugsun-starter-web` | `AutoConfiguration.imports` 装配异常处理、Sa 拦截器、加解密 Advice 等；Bean 以 `@ConditionalOnMissingBean` 可覆盖 |
| `mugsun-starter-mybatis` | 引入 Flex Spring Boot starter，并提供 `BaseEntity`；显式覆盖 Flex 传递的旧版 `mybatis-spring` |

本仓库无 `spring-boot:run` 目标。需要可运行服务时使用 [mugsun-boot](https://github.com/mugsun/mugsun-boot)。

## 能力范围

**已提供**

- BOM 与 `mugsun-starter-web` / `mugsun-starter-mybatis` 两个 Starter
- 与 boot **0.1.0** 对齐的 Maven Central 坐标
- Sa-Token 注解鉴权拦截、统一响应与业务异常、接口国密加解密、大整数安全序列化
- Flex 实体基类及对旧 `mybatis-spring` 的版本覆盖

**暂未提供**

- 可独立运行的 HTTP 服务或业务领域模块
- 将 uniappx-ui / cloud 写成已交付产品的依赖说明

类清单与配置键以 [文档](https://docs.mugsun.com/core/) 及源码为准。

## 关联项目

开源主线：`boot` · `core` · `pc` · `track` · [mugsun-docs](https://github.com/mugsun/mugsun-docs)。

| 仓库 | 与本仓库的关系 |
| --- | --- |
| [mugsun-boot](https://github.com/mugsun/mugsun-boot) | 主要消费方；通过 Central 坐标或本地 install 引用本仓 |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | 管理端；不直接依赖本仓 |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | 浏览器 SDK；经 boot track 模块间接相关 |
| [mugsun-docs](https://github.com/mugsun/mugsun-docs) | 文档站 [docs.mugsun.com](https://docs.mugsun.com/) |

## 许可

[MIT](./LICENSE) · 版权见 [NOTICE](./NOTICE) · © 2026 北京定旭晨 / mugsun
