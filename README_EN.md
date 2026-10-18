![mugsun-core](https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/repos/mugsun-core-en.png)

# mugsun-core

[简体中文](./README.md) | English

This repository is the shared kernel of the mugsun platform. It publishes a BOM, common utilities, and Spring Boot starters for business applications to consume via Maven coordinates. Unified response types, Sa-Token annotation auth, API crypto, and MyBatis-Flex base entities are assembled here. This repository does not expose a runnable HTTP entrypoint.

[Documentation](https://docs.mugsun.com/en/core/) · [Architecture](https://docs.mugsun.com/en/guide/architecture) · [Maven Central](https://central.sonatype.com/search?q=com.mugsun)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring%20Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![Maven%20Central](https://img.shields.io/maven-central/v/com.mugsun/mugsun-starter-web)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

## Overview

| Item | Detail |
| --- | --- |
| Coordinates | `com.mugsun:mugsun-*:0.1.0` (published on [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)) |
| Runtime | JDK 21; Maven 3.9+ |
| Parent | Spring Boot 3.5.13 |
| License | [MIT](./LICENSE) |
| Docs | [docs.mugsun.com/en/core](https://docs.mugsun.com/en/core/) |

Stack (per current POM): MyBatis-Flex 1.11.8, Sa-Token 1.44.0, JetCache, Hutool Crypto / BouncyCastle (API crypto), FastExcel, and related libraries — versions locked by `mugsun-bom`.

## Deployment

Prefer Maven Central. Sibling `mvn install` is not required for normal consumption.

Import the BOM in `dependencyManagement`, then declare starters as needed:

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

For a runnable backend, use [mugsun-boot](https://github.com/mugsun/mugsun-boot). Full vertical slice: [Documentation · Getting started](https://docs.mugsun.com/en/guide/getting-started).

**Local install** (offline use or Central sync fallback):

```bash
git clone https://github.com/mugsun/mugsun-core.git
cd mugsun-core
mvn clean install -DskipTests
```

Publishing to Central: create a GitHub Release on this repository, or run the **Publish to Maven Central** Actions workflow (`workflow_dispatch` / `release` published). Local equivalent: `mvn -Prelease clean deploy -DskipTests` (Portal token and GPG required).

## Composition

```text
mugsun-core/
  mugsun-bom/            # version lock (incl. mybatis-spring → 3.0.5 override)
  mugsun-core-tool/      # R / ResultCode / service exceptions / TreeUtil
  mugsun-starter-web/    # global exceptions, Sa-Token annotation interceptor, ApiCrypto, SafeNumber, Excel
  mugsun-starter-mybatis/ # MyBatis-Flex starter + BaseEntity (snowflake id, audit times, logic delete)
```

| Module | Role |
| --- | --- |
| `mugsun-bom` | Pins this repository’s artifacts and third-party versions (Flex, Sa-Token, JetCache, …) |
| `mugsun-core-tool` | Spring-free common API types and utilities |
| `mugsun-starter-web` | `AutoConfiguration.imports` wires exception handling, Sa interceptor, crypto advice, and related beans; all `@ConditionalOnMissingBean` |
| `mugsun-starter-mybatis` | Brings in the Flex Spring Boot starter and `BaseEntity`; explicitly overrides Flex’s transitive older `mybatis-spring` |

There is no `spring-boot:run` goal in this repository. Use [mugsun-boot](https://github.com/mugsun/mugsun-boot) for a runnable server.

## Scope

**Provided**

- BOM plus `mugsun-starter-web` / `mugsun-starter-mybatis` starters
- Maven Central coordinates aligned with boot **0.1.0**
- Sa-Token annotation auth interceptor, unified responses and service exceptions, API crypto, safe large-number serialization
- Flex base entity and an explicit override for the older transitive `mybatis-spring`

**Not provided**

- A standalone HTTP service or domain business modules
- Treating uniappx-ui / cloud as shipped product dependencies

Class lists and configuration keys are defined in the [documentation](https://docs.mugsun.com/en/core/) and source tree.

## Related projects

Open-source line: `boot` · `core` · `pc` · `track` · [mugsun-docs](https://github.com/mugsun/mugsun-docs).

| Repository | Relationship |
| --- | --- |
| [mugsun-boot](https://github.com/mugsun/mugsun-boot) | Primary consumer; depends via Central coordinates or local install |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | Admin console; not a direct dependency of this repository |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | Browser SDK; related indirectly through boot’s track module |
| [mugsun-docs](https://github.com/mugsun/mugsun-docs) | Documentation site [docs.mugsun.com](https://docs.mugsun.com/) |

## License

[MIT](./LICENSE)