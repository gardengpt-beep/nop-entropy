# 差量管理（_delta 定制与合并）

## 1. 摘要（TL;DR）
1. `_vfs/_delta/default` 目录中的 DSL 会优先于基线资源加载，通过 `x:extends="super"` 合并原始节点实现无侵入定制。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】
2. `ModuleManager.findModuleResources` 按模块扫描资源路径，配合差量目录可组合所有启用模块的定制文件。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L184】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L32-L52】
3. `DslNodeLoader` 负责解析、校验并在校验阶段输出 `_dump`，确保差量合并后的结果可追溯。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L29-L85】
4. MyBatis、Spring Beans、XMeta、ORM 配置均可通过 `_delta` 覆写重点属性，实现 SQL、Bean、字段域等细粒度扩展。【F:docs/dev-guide/spring/spring-delta.md†L29-L140】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】
5. 运行期定制依赖 `ModuleManager` 的模块发现与 `SqlSessionFactoryBean`/`XmlBeanDefinitionReader` 的二次注册流程，实现差量生效而无需重新打包。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L200】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L26-L52】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/beans/NopBeansAutoConfiguration.java†L29-L63】
6. `DeltaResourceStore` 先尝试租户 `_tenant/{id}` 再按 `nop.core.vfs.delta-layer-ids` 指定顺序遍历 `_delta/<layer>`，最后落回基线路径，形成多层覆盖链路。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L35-L148】【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L117-L127】

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| `_delta` 差量目录 | Delta Directory | `_vfs/_delta/<scope>/...` 中的文件优先于同路径基线资源加载，用于局部覆写或追加配置。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】|
| `x:extends="super"` | Inherit Super Node | XDSL 合并指令，表示基线内容作为父节点，差量节点可选择覆盖/追加属性。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】|
| ModuleManager | Module Manager | 负责扫描 `_module` 元数据并提供跨模块资源查询接口，支持差量资源聚合。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L184】|
| DslNodeLoader | DSL Node Loader | 加载 XDSL 文件、执行差量合并与 schema 校验，可在调试模式输出 `_dump`。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L29-L85】|
| Scope `default` | Default Scope | 差量目录的默认作用域，适用于全局或所有租户。其他作用域命名规则待补充证据。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】|
| 租户资源覆盖 | Tenant Resource Overlay | `nop.core.tenant-resource.enabled` 开启后，`DeltaResourceStore` 会先访问 `_tenant/{tenantId}` 目录，再按差量层级查找资源。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L120-L147】【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L235-L243】|

## 3. 事实（Facts）
- `ModuleManager.discover()` 遍历 `VirtualFileSystem` 中 `*/*/_module` 文件，注册启用模块并允许租户模块叠加，确保差量资源可被定位。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L141】
- `findModuleResources(false, "/mapper", ".mapper.xml")` 会收集所有模块下的 Mapper 文件，`NopMybatisSessionFactoryCustomizer` 再逐个加载并注册到 MyBatis。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L159-L184】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L32-L52】
- `_delta` 中的 Mapper 通过 `x:extends="super"` 定义差量 SQL，示例中追加了删除逻辑与自定义查询。【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】
- XMeta 差量可以调整字段域与排序，例如将 `opResponse` 字段声明为 `csv-list` 并增加排序优先级。【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】
- ORM 差量可开启实体分片，仅需在 `_delta` 中声明新增属性，无需复制完整 `app.orm.xml`。【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】
- Spring Bean 定制可在 `_delta` 中替换 `mapperTypeEx`，组合运行期扩展接口。【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/beans/spring-demo.beans.xml†L1-L8】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/nop/spring/beans/spring-demo.beans.xml†L1-L10】
- `DeltaResourceStore.getResource` 在租户模式开启时优先访问 `_tenant/{tenantId}`，随后按配置的 delta 层逐层回退到基线资源目录，确保覆盖顺序确定。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L120-L148】
- 只有最前面的 delta 层允许写入，`saveResource` 默认写入 `deltaLayerIds[0]`，保障差量输出不会破坏基线文件。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L204-L225】
- Delta 层级顺序由配置 `nop.core.vfs.delta-layer-ids` 指定，可通过配置文件动态调整覆盖优先级。【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L117-L127】
- 当未显式配置 `delta-layer-ids` 时，`DeltaResourceStoreBuilder` 会检测 `_delta/default` 是否存在并自动启用 `default` 层，形成默认覆盖顺序。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStoreBuilder.java†L65-L79】
- `ResourceConstants`/`ApiConstants` 固定了 `/_delta/` 与 `/_tenant/` 命名前缀，`ApiStringHelper.getStdPath` 负责剥离租户/差量前缀保证路径标准化；`TestTenantResourceStore` 通过 `/_tenant/1/...` 验证租户路径可回落到基线文件；`InMemoryCodeCache` 在多租户动态生成时也写入 `v:/_tenant/{id}`。【F:nop-api-core/src/main/java/io/nop/api/core/ApiConstants.java†L288-L290】【F:nop-api-core/src/main/java/io/nop/api/core/util/ApiStringHelper.java†L620-L638】【F:nop-core/src/test/java/io/nop/core/resource/tenant/TestTenantResourceStore.java†L28-L36】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L200-L336】

> 差量层优先级速查表（基于源码与示例模块）

| 场景 | `nop.core.vfs.delta-layer-ids` 实际值 | 覆盖顺序 | 模块示例 / 证据 |
| --- | --- | --- | --- |
| 默认检测 `_delta/default` | 未显式配置（默认 `null`） | `default → 基线`（若 `_delta/default` 存在自动启用） | `CFG_VFS_DELTA_LAYER_IDS` 缺省为 `null`，`DeltaResourceStoreBuilder` 自动检查 `/_delta/default`。【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L117-L119】【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStoreBuilder.java†L65-L79】 |
| Demo 产品差量 | 建议显式设置为 `default` 以匹配随模块发布的 `_delta/default` | `default → 基线` | `nop-delta-demo` 在 `_vfs/_delta/default` 覆盖登录 BizModel/Beans。【F:nop-demo/nop-delta-demo/src/main/resources/_vfs/_delta/default/nop/auth/model/LoginApi/LoginApi.xbiz†L1-L15】【F:nop-demo/nop-delta-demo/src/main/resources/_vfs/_delta/default/nop/auth/beans/auth-service.beans.xml†L1-L14】 |
| Quarkus Demo 扩展 | 同步采用 `default` 层扩展系统通知 ORM | `default → 基线` | `nop-quarkus-demo` 在 `_vfs/_delta/default` 增补别名字段，依赖同名 ORM 自动覆盖。【F:nop-demo/nop-quarkus-demo/src/main/resources/_vfs/_delta/default/nop/sys/orm/app.orm.xml†L1-L10】 |

- ⚠️ 推测1：差量可覆盖 REST/GraphQL 暴露配置（如 `*.service.xml`）。验证计划：搜索 `_delta` 中的 `service`、`graphql` 文件并对比基线模板。
- ⚠️ 推测2：`DslNodeLoader` 的 `_dump` 输出可与 CI 工具联动进行差量快照比对。验证计划：查找 `ResourceHelper.getDumpPath` 使用场景与测试脚本。

## 5. 规则（IF-THEN，可执行）
- IF 需要定制自动生成的 SQL THEN 在 `_delta/default/.../mapper` 下创建同名文件并添加 `x:extends="super"`，否则 MyBatis 会因重复语句报错。【F:docs/dev-guide/spring/spring-delta.md†L23-L83】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】
- IF 想启用差量 Bean 定义 THEN 保持文件名以 `spring-` 开头，以便 `NopBeansRegistrar` 过滤并加载。【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/beans/NopBeansAutoConfiguration.java†L37-L51】
- IF 需修改 ORM 分片配置 THEN 在 `_delta` 的 `app.orm.xml` 中声明增量实体属性并确保引用相同 schema，避免完全复制基线。【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】
- IF 希望跟踪合并结果 THEN 开启 `AppConfig.isDebugMode()`，`DslNodeLoader` 会将合并后的 XML 写入 `_dump` 目录。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L77-L85】
- IF 模块需禁用某些差量 THEN 配置 `CFG_MODULE_DISABLED_MODULE_NAMES`，`ModuleManager` 会在扫描阶段跳过对应模块资源。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L83】
- IF 需要自定义差量层优先级 THEN 设置 `nop.core.vfs.delta-layer-ids`，系统会按照配置顺序在 `_delta/<layer>` 目录中查找资源。【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L117-L127】【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L135-L148】
- IF 需要租户级覆盖 THEN 打开 `nop.core.tenant-resource.enabled` 并提供租户资源存储，`DeltaResourceStore` 才会访问 `_tenant/{tenantId}` 覆盖层。【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L235-L243】【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L120-L147】【F:nop-core/src/main/java/io/nop/core/resource/tenant/ResourceTenantManager.java†L59-L221】

## 6. 流程（Text-Sequence：编号步骤）
1. `ModuleManager.discover()` 读取 `_module` 与 `app.module.yaml`，建立启用模块列表。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L118】
2. 运行期（如 Spring Boot 启动）调用 `findModuleResources` 收集指定类型资源（Mapper、Beans 等）。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L159-L184】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L32-L52】
3. 若启用了租户资源，`DeltaResourceStore` 先尝试 `_tenant/{tenantId}`，再按配置依次命中差量层。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L120-L148】
4. `DslNodeLoader.loadFromResource` 解析差量与基线，执行 schema 校验与 `x:extends` 合并。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L29-L75】
5. 生成的 `XNode` 移除差量辅助命名空间后转换为 Spring/MyBatis 所需的资源对象。【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L41-L49】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/beans/NopBeansAutoConfiguration.java†L46-L63】
6. 注册器（MyBatis 或 Spring）将合并结果注入运行时容器，实现差量生效。【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L45-L52】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/beans/NopBeansAutoConfiguration.java†L41-L52】
7. 调试模式下 `_dump` 输出用于比对差量与基线的最终合成结果，辅助排查冲突。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L77-L85】

- 差量冲突排查步骤：
  1. 启用调试模式并检查 `_dump` 是否生成目标文件，若缺失则确认差量路径与命名。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L77-L85】
  2. 查看 `ModuleManager` 启动日志 `nop.core.add-module`，确保差量模块已被发现。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L84】
  3. 对比差量文件与基线 schema，确认 `x:schema` 与命名空间一致，避免校验失败导致差量被忽略。【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| 模块发现中 | 扫描 `_module` | 模块未禁用 | 模块启用 | 注册模块元数据，供后续资源检索。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L118】|
| 模块启用 | 请求资源 | 存在差量文件 | 差量合并中 | `DslNodeLoader` 装载差量节点。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L29-L75】|
| 差量合并中 | 校验通过 | schema 匹配 | 合并完成 | 生成合并后的 `XNode` 并可写入 `_dump`。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L63-L85】|
| 合并完成 | 注册资源 | 目标容器接受 | 差量生效 | MyBatis/Spring 接收到定制配置。【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L32-L52】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/beans/NopBeansAutoConfiguration.java†L37-L52】|
| 差量生效 | 调试启用 | `AppConfig.isDebugMode()` | 输出比对 | 写入 `_dump` 供分析。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L77-L85】|

## 8. 接口契约（I/O 表：端点、字段、约束、错误码）
| 组件/入口 | 输入 | 输出 | 约束 | 错误/异常 |
| --- | --- | --- | --- | --- |
| `ModuleManager.findModuleResources` | `filePathInModule`、`suffix` | `List<IResource>` | 依赖已启用模块、正确路径格式 | 模块禁用或路径缺失导致返回空集合。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L159-L200】|
| `DslNodeLoader.loadFromResource` | `IResource`、可选 `requiredSchema` | `XDslExtendResult` | 需提供 `x:schema`，合并阶段自动校验 | 缺少 schema 抛出 `ERR_XDSL_NO_SCHEMA`；schema 不匹配抛出 `ERR_XDSL_NOT_REQUIRED_SCHEMA`。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L45-L60】|
| `NopMybatisSessionFactoryCustomizer.customize` | `SqlSessionFactoryBean` | 添加 Mapper 资源 | 过滤 `_` 前缀文件，仅注册差量/手写 Mapper | 模板缺失或合并失败将导致 Mapper 未注册，需检查日志。【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L32-L52】|
| `NopBeansRegistrar.registerBeanDefinitions` | `BeanDefinitionRegistry` | Bean 定义注册 | 文件需以 `spring-` 开头并符合 schema | schema 校验失败抛出异常或跳过注册。【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/beans/NopBeansAutoConfiguration.java†L37-L52】|
| `DeltaResourceStore.getResource` | 虚拟路径、`returnNullIfNotExists` | `IResource`（租户/差量/基线） | 租户模式需开启，差量层顺序由 `deltaLayerIds` 决定 | `ERR_RESOURCE_CURRENT_PATH_CONTAINS_INVALID_DELTA_LAYER_ID`、`ERR_RESOURCE_INVALID_DELTA_LAYER_ID` 等异常提醒路径非法。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L120-L315】|

## 9. 用例（Few-shot：Given-When-Then）
- 正向：Given 需要扩展 `SysUser` 查询，When 在 `_delta/default/.../SysUser.mapper.xml` 添加新 SQL 并继承基线，Then MyBatis 启动后加载差量 SQL 覆盖默认实现。【F:docs/dev-guide/spring/spring-delta.md†L64-L83】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】
- 反向：Given 差量文件缺少 `x:schema` 或 schema 不匹配，When `DslNodeLoader` 校验时，Then 抛出 `ERR_XDSL_NO_SCHEMA` 或 `ERR_XDSL_NOT_REQUIRED_SCHEMA`，差量失效。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L45-L60】
- 边界：Given 需对元数据追加排序字段，When 在 `_delta` 的 `NopAuthOpLog.xmeta` 中添加 `orderBy` 与 `csv-list` 域，Then 运行期加载合并后的元数据用于 GraphQL/REST 查询。【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】
- 正向（租户）：Given 启用 `nop.core.tenant-resource.enabled` 并配置租户资源存储，When 请求 `_tenant/{tenantId}/...` 已存在的模型，Then `DeltaResourceStore` 返回租户版本，缺失时再回退差量层与基线资源。【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L235-L243】【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L120-L315】【F:nop-core/src/main/java/io/nop/core/resource/tenant/ResourceTenantManager.java†L59-L221】

## 10. 证据矩阵（结论↔证据↔置信度↔Run-less验证）
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| `_delta` 目录通过 `x:extends` 覆盖基线资源 | Spring/MyBatis 差量指南与示例 Mapper | 高 | 扩展检查更多 Mapper/Beans 差量文件，确认继承模式一致。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】|
| 模块扫描与资源聚合由 `ModuleManager` 提供 | `ModuleManager` 实现与运行期调用 | 高 | 追踪日志关键字 `nop.core.add-module` 验证模块注册顺序。【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L184】【F:nop-spring/nop-spring-delta/src/main/java/io/nop/spring/delta/mybatis/NopMybatisSessionFactoryCustomizer.java†L32-L52】|
| `DslNodeLoader` 在校验阶段输出 `_dump` | 代码中 `dumpMergedResult` 实现 | 中 | 查找 `_dump` 目录样例或触发条件说明，补充截图/路径描述。【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L77-L85】|
| XMeta/ORM 差量可覆盖字段域与分片 | `NopAuthOpLog.xmeta` 与 `app.orm.xml` 差量文件 | 高 | 枚举更多实体差量，汇总字段/分片配置对照表。【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】|
| Spring Bean 差量扩展 Mapper 接口 | `spring-demo.beans` 基线与差量文件 | 高 | 对比差量与基线属性差异，记录扩展接口加载顺序。【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/nop/spring/beans/spring-demo.beans.xml†L1-L10】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/beans/spring-demo.beans.xml†L1-L8】|
| DeltaResourceStore 支持租户优先和多层覆盖 | DeltaResourceStore 源码 + 配置项定义 | 高 | 枚举常见 `delta-layer-ids` 组合与 `_tenant` 目录示例，验证多层回退顺序。【F:nop-core/src/main/java/io/nop/core/resource/store/DeltaResourceStore.java†L35-L315】【F:nop-core/src/main/java/io/nop/core/CoreConfigs.java†L117-L243】|

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.3 | 2024-05-29 | 新增差量层优先级速查表与租户目录命名证据，补强多层覆盖研究基线。|
| v0.2 | 2024-05-28 | 补充租户覆盖与差量层级配置机制，完善 `DeltaResourceStore` 相关规则与接口说明。|
| v0.1 | 2024-05-27 | 创建差量管理模块，整理 `_delta` 目录结构、运行期合并流程与典型用例。|

## 12. 检索标签（Tags）
`#Delta` `#ModuleManager` `#DslNodeLoader` `#MyBatis` `#SpringBeans` `#RunLess`
