# mugsun-core

**中文** | [English](./README_EN.md)

本仓库为 mugsun 平台共享内核。提供 BOM、通用工具与 Spring Boot Starter，供业务工程以 Maven 坐标引入。统一响应体、Sa-Token 注解鉴权、接口加解密与 MyBatis-Flex 实体基类在此装配。本仓库不提供可执行 HTTP 入口。

[文档](https://docs.mugsun.com/core/) · [架构](https://docs.mugsun.com/guide/architecture) · [Maven Central](https://central.sonatype.com/search?q=com.mugsun)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring%20Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![Maven%20Central](https://img.shields.io/maven-central/v/com.mugsun/mugsun-starter-web)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

## 概述

| 项 | 说明 |
| --- | --- |
| 坐标 | `com.mugsun:mugsun-*:0.1.0`（已发布至 [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)） |
| 运行时 | JDK 21；Maven 3.9+ |
| 父工程 | Spring Boot 3.5.13 |
| 许可 | [Apache-2.0](./LICENSE) |
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

[Apache-2.0](./LICENSE)
