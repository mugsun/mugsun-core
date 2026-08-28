# mugsun-core

**中文** | [English](README_EN.md)

![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)
![MyBatis-Flex](https://img.shields.io/badge/MyBatis--Flex-1.11-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

mugsun 平台的公共内核：全栈依赖版本锁定（BOM）加两个可复用 starter。业务工程导入后即可获得统一响应、全局异常处理、接口加解密与审计基类，无需在各工程内重复实现。

版本兼容问题在内核层统一处理：MyBatis-Flex 传递的旧版 `mybatis-spring` 与 Spring Boot 3.5 / Spring 6.2 冲突（表现为 `NoSuchMethodError`），BOM 与 starter 中已显式覆盖为 3.0.5。

## 模块总表

全仓四个模块，职责单一：

| 模块 | 职责 | 关键类 |
| --- | --- | --- |
| `mugsun-bom` | 全栈依赖版本锁定（含 `mybatis-spring` 3.0.5 显式覆盖） | —（pom） |
| `mugsun-core-tool` | 统一响应、统一状态码、业务异常与通用工具 | `R`、`ResultCode`、`ServiceException`、`ForbiddenException`、`TreeUtil` |
| `mugsun-starter-web` | Web 基座：全局异常、注解鉴权、接口加解密、Long 精度安全、Excel 读写 | `MugsunWebAutoConfiguration`、`GlobalExceptionHandler`、`SafeNumberModule`、`ApiCryptoService`、`ExcelUtil` |
| `mugsun-starter-mybatis` | 持久层基座：MyBatis-Flex 装配与实体基类 | `BaseEntity` |

## 模块关系

```mermaid
graph TD
    BIZ["业务工程<br/>（如 mugsun-boot）"]
    BOM["mugsun-bom<br/>全栈版本锁定"]
    TOOL["mugsun-core-tool<br/>统一响应 / 异常 / 工具"]
    WEB["mugsun-starter-web<br/>Web 基座 starter"]
    MB["mugsun-starter-mybatis<br/>持久层基座 starter"]

    BIZ -.->|dependencyManagement<br/>scope=import| BOM
    BIZ --> WEB
    BIZ --> MB
    BOM -.->|锁定版本| TOOL
    BOM -.->|锁定版本| WEB
    BOM -.->|锁定版本| MB
    WEB --> TOOL
    MB --> TOOL
```

## 快速接入

在本仓根目录构建并安装到本地 Maven 仓库：

```bash
mvn clean install
```

在业务工程 `pom.xml` 中导入 BOM，版本号与本仓当前版本一致：

```xml
<dependencyManagement>
	<dependencies>
		<dependency>
			<groupId>com.mugsun</groupId>
			<artifactId>mugsun-bom</artifactId>
			<version>0.0.1-SNAPSHOT</version>
			<type>pom</type>
			<scope>import</scope>
		</dependency>
	</dependencies>
</dependencyManagement>
```

按需引入 starter，版本由 BOM 接管，不必再写：

```xml
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

## starter 提供的能力

- **统一响应 `R<T>` 与 `ResultCode`**：所有接口返回同一结构（`code`/`success`/`data`/`msg`），前端只需一套解析逻辑；`dataType` 标记位用于承接加密响应。
- **全局异常处理**：`ServiceException` 按业务码返回；未登录 401、越权 403、参数校验失败 400、资源不存在 404、请求方法不允许 405，与 HTTP 语义对齐。兜底 500 会同步发布 `ErrorLogListener` 事件，业务侧实现该接口即可把错误日志落库。
- **注解鉴权**：自动注册 `SaInterceptor` 并拦截 `/**`，`@SaCheckLogin`、`@SaCheckPermission` 等 Sa-Token 注解可直接使用。
- **接口国密加解密**：`@ApiDecrypt` / `@ApiEncrypt` 为注解级开关，采用 SM4-CBC 且每次请求随机 IV（密文非确定性，与前端 sm-crypto 互通）。密钥经 `mugsun.crypto.api-key` 外部注入；`mugsun.crypto.strict-keys=true` 时密钥缺失将拒绝启动，避免带默认密钥上生产。
- **Long 精度安全**：`SafeNumberModule` 随 Jackson 全局生效，超出 JS 安全整数范围（±2^53）的 `Long` 与 `BigInteger` 序列化为字符串，雪花主键不会在前端丢精度；范围内数值照常输出，分页总数等字段不受影响。
- **实体基类 `BaseEntity`**：提供雪花主键（flexId）、由数据库 `now()` 填充的创建与更新时间、逻辑删除。`sanitizeForInsert()` 与 `sanitizeForUpdate()` 在写入前剥离请求体中伪造的审计字段，客户端输入一律不信任。
- **Excel 读写**：`ExcelUtil` 基于 FastExcel，支持上传文件读为对象列表、对象列表导出 xlsx 触发下载，字段映射由 `@ExcelProperty` 声明。
- **树构建带环保护**：`TreeUtil.build()` 将扁平列表转为树，父指针成环的脏数据随环整体剔除，避免 `StackOverflow` 导致持续 500。

一次 HTTP 请求经过 starter 自动装配件的完整链路：

```mermaid
sequenceDiagram
	participant FE as 前端
	participant SA as SaInterceptor
	participant DA as DecryptRequestAdvice
	participant CTL as Controller
	participant EA as EncryptResponseAdvice
	participant EH as GlobalExceptionHandler

	FE->>SA: HTTP 请求
	SA->>SA: 注解鉴权（@SaCheckLogin 等）
	SA-->>FE: 未登录 401 / 无权限 403
	SA->>DA: 鉴权通过，进入参数绑定
	DA->>DA: @ApiDecrypt → SM4 解密 {encryptData}
	DA->>CTL: 明文 JSON 绑定为对象
	CTL->>EA: 返回 R<T>
	EA->>EA: @ApiEncrypt → data 加密并置 dataType=ENCRYPT
	EA->>FE: 统一 R 响应（超长 Long 自动转字符串）
	CTL--xEH: 任一环节抛出异常
	EH-->>FE: 对齐 HTTP 状态码的 R 响应（400/401/403/404/405/500）
```

## 设计约定

- **Java 21+**：全仓以 `<java.version>21</java.version>` 编译。
- **Tab 缩进**：全仓统一使用 Tab，不混用空格。
- **显式自动装配**：starter 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 声明装配，不做组件扫描兜底，引入才生效，排除即完全关闭。
- **可被覆盖**：装配的 Bean 均带 `@ConditionalOnMissingBean`，业务工程声明同类型 Bean 即可替换默认实现。

## 与 mugsun-boot 的关系

本仓是内核，不直接运行。[mugsun-boot](https://github.com/mugsun/mugsun-boot) 是它的第一个消费者，以 `scope=import` 导入 `mugsun-bom` 后按需引入两个 starter，组装为可执行的单体后端。两仓平级放置于同一目录即可联调。

提交信息规范见 [.github/COMMIT_CONVENTION.md](.github/COMMIT_CONVENTION.md)。

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=mugsun/mugsun-core&type=Date)](https://star-history.com/#mugsun/mugsun-core&Date)

## 许可

[Apache License 2.0](LICENSE)
