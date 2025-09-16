# ERP Spring Project

这个示例按照给定的 Spring Boot POM 结构搭建，并利用 Nop 平台提供的模型驱动能力
在运行时自动生成产品（`ErpProduct`）领域的 CRUD 接口。所有领域语义都位于
`src/main/resources/_vfs/com/company/erp` 目录下的 ORM / Biz 元数据文件中，Spring Boot
应用只负责启动 Nop 运行时并暴露 `/r/ErpProduct__*` REST 端点。

## 主要特性

- **模型驱动**：通过 `_vfs` 下的 `app.orm.xml`、`ErpProduct.xmeta` 与 `ErpProduct.xbiz`
  定义字段、校验规则以及 `active_findPage` 查询的业务约束。
- **内存数据库**：`application.yaml` 默认配置 H2，启动时自动创建表结构，可直接演示。
- **Spring Boot 集成测试**：`@SpringBootTest` 用例调用真正的 `/r/ErpProduct__save`、
  `/r/ErpProduct__get` 与 `/r/ErpProduct__active_findPage` 接口，验证应用能够成功启动并执行业务流。

## 准备工作

- JDK 17
- 可联网的 Maven 环境，用于拉取 `io.github.entropy-cloud` 的 SNAPSHOT 依赖

## 运行集成测试

```bash
cd erp-spring-project
mvn -ntp test
```

Maven 会先执行 `CodeGenTask`（如果存在模板），随后由 Spring Boot Test 自动拉起应用，
并通过 Rest API 完成一次模型驱动的 CRUD 流程。

## 启动应用

```bash
cd erp-spring-project
mvn spring-boot:run
```

应用默认监听 8080 端口，可使用如下示例命令体验 REST 接口：

```bash
curl -X POST "http://localhost:8080/r/ErpProduct__save?@selection=productId,sku,status" \
  -H "Content-Type: application/json" \
  -d '{"data":{"sku":"DEMO-001","name":"演示商品","unitPrice":19.9,"stockQuantity":5,"status":"ACTIVE"}}'
```

接口返回 `code=0` 表示调用成功，`data` 字段即为模型驱动生成的响应。

## 常见问题与开发经验

### Java 进程无法联网导致 Maven 构建失败

在当前容器环境中虽然可以使用 `curl` 等原生命令访问互联网，但 JVM 层面网络连接
会被阻断，`mvn` 运行时会出现诸如 `Network is unreachable`、`Name or service not known`
等错误，导致 Spring Boot 相关依赖与 Nop SNAPSHOT 组件无法下载。

**排查步骤：**

1. 通过 `curl https://repo1.maven.org/maven2/...` 验证宿主网络是否可达。
2. 使用一段简单的 `java.net.URL.openStream()` 代码测试 JVM 是否能访问同一地址；若抛
   出 `java.net.SocketException: Network is unreachable`，说明仅 Java 被限流。
3. Maven 会持续尝试访问远程仓库并缓存失败信息，可在 `~/.m2/repository` 中删除对
   应 `*.lastUpdated` 文件后重试，或准备一个预先下载好的本地仓库。

**建议做法：**

- 在可联网的机器上执行 `mvn -f erp-spring-project/pom.xml dependency:go-offline` 预热
  本地仓库，随后将 `~/.m2/repository` 打包带入受限环境。
- 或者使用自定义脚本（例如基于 `curl` 的镜像下载器）将 Spring Boot 与 Nop 相关的
  依赖批量拉取到一个 `file://` 类型的仓库，再在 `settings.xml` 中添加该仓库条目。
- 当网络恢复后，可直接运行 `mvn -ntp test`，由 `@SpringBootTest` 自动验证 `/r/ErpProduct__*`
  端点的模型驱动 CRUD 流程。

通过上述方式提前准备依赖，可以避免在受限环境中重复触发网络错误，并且确保后续的
Spring Boot 集成测试能够一键通过。
