# 动态配置总览（nop-config）

## 1. 摘要（TL;DR）
1. `ConfigStarter` 在启动阶段依次汇聚环境变量、系统属性、`bootstrap.yaml`、配置中心、K8s ConfigMap、属性文件、数据库与扩展加载器，形成按优先级合并的配置源链。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L108-L177】
2. 所有配置源被封装为 `CompositeConfigSource`，最终再叠加应用级 `application.yaml`/`application-{profile}.yaml`/`nop.config.yaml` 并套用 `ProfileConfigSource` 与 `RouterConfigSource`，确保按 profile、产品名分流配置。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L184-L323】
3. `ConfigStarter` 把合成后的 `IConfigSource` 注入 `DefaultConfigProvider`，通过 `AppConfig.registerConfigProvider` 全局替换配置访问入口并保留已有引用，实现无感切换。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L428-L470】
4. 当配置中心或远程源变更时，`ConfigChangeApplier` 触发 `configProvider.applyChange()`，配合 `IConfigExecutor` 执行串行更新，避免并发写冲突。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L125-L260】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L468-L475】
5. 初始化流程会在配置加载完成后启动虚拟文件系统并发现模块，确保 `_delta`、`_tenant` 差量在配置阶段就具备资源视图。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L206-L215】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L491-L495】
6. 通过 `ConfigConstants` 可追溯每个配置键的语义及默认路径，例如 `nop.config.additional-location`、`nop.config.jdbc.*` 等，方便定位配置覆盖入口。【F:nop-config/src/main/java/io/nop/config/ConfigConstants.java†L21-L102】
7. 若处于分析模式（`CoreInitialization.isForAnalyze()`），`ConfigStarter` 会跳过远程、数据库等动态配置，仅保留本地基础源，适合代码分析或文档生成场景。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L121-L180】
8. 配置中心服务可通过 `nop.config.service.enabled=false` 关闭；此时仍保留环境变量与本地文件链路，确保在无外部服务时也能完整启动。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L242-L254】

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| ConfigStarter | Config Starter | 配置初始化总控，负责组织所有 `IConfigSource` 并激活 `ConfigProvider`，仅在应用启动阶段运行一次。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L105-L221】|
| IConfigSource | Config Source | 配置读取接口，可来自环境变量、文件、远程服务，按优先级组合成 `CompositeConfigSource`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L108-L323】|
| CompositeConfigSource | Composite Config Source | 把多个配置源按顺序合并，后加入的 source 优先级更高；启动过程多次构造新的组合实例。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L112-L200】|
| ProfileConfigSource | Profile-aware Source | 根据 active profiles 选择性覆盖配置，例如 `application-dev.yaml`；在 profiles 为空时跳过。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L191-L198】|
| RouterConfigSource | Router Config Source | 根据产品或应用路由规则过滤配置项，结合配置中心的 `nop.config.app.router` 等开关使用。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L193-L215】【F:nop-config/src/main/java/io/nop/config/ConfigConstants.java†L21-L35】|
| ConfigChangeApplier | Config Change Applier | 负责监听 `IConfigSource` 变化并串行应用变更，避免多线程冲突，是配置热更新的执行体。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L468-L475】|

## 3. 事实（Facts）
- 启动第一阶段固定收集环境变量、系统属性与 `bootstrap.yaml`，并声明优先级为 env > system > bootstrap，保持与 Spring Boot 一致。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L108-L147】
- 若启用配置中心，`ConfigStarter` 先调用 `SysServiceLoader.loadConfigService` 获取实现，再按应用名、profile、产品名依次请求配置文件并加入优先级链顶端。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L242-L324】
- K8s Secret/ConfigMap (`KeyFileConfigSource`) 与自定义属性文件 (`PropsFileConfigSource`) 通过 CSV 列表配置路径，若存在即排在配置中心之后、基础源之前。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L132-L155】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L333-L355】
- 当 JDBC 配置有效时，`nop.config.jdbc.*` 参数会初始化 `JdbcConfigSource` 并插入合并链，优先级低于配置中心、K8s、属性文件，高于 bootstrap。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L357-L364】
- 所有基础源与远程源组合后，再加载 `application.yaml`、`application-{profile}.yaml`、`nop.config.yaml`，这些文件与 `nop.config.additional-location` 同级别覆盖应用配置。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L393】
- `loadAppConfigs` 会优先解析 `nop.config.additional-location`，通过 `buildConfigResource` 支持 `classpath:`、`file:` 等前缀，将附加文件源插入列表首位，使其在 profile 与主应用文件之前生效，确保本地覆盖权最高。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L407】
- `ConfigStarter` 最终调用 `updateConfigSource` 将组合结果写入 `DefaultConfigProvider`，并为后续热更新注册监听器。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L201-L219】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L475】
- `DefaultConfigProvider.changeConfigSource` 会重新生成 `configRefs` 并调用 `updateRefs`，对已经被业务代码引用的配置变量执行类型转换与增量更新，避免热更新丢失原有引用。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L90-L143】
- `updateConfigSource` 在替换配置源的同时实例化 `ConfigChangeApplier` 并把 `configSource.addOnChange` 绑定到 `requestUpdate`，借助 `IConfigExecutor` 串行调度 `applyChange()`，防止并发刷新。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L475】【F:nop-config/src/main/java/io/nop/config/impl/ConfigChangeApplier.java†L15-L54】
- `DefaultConfigProvider.applyChange()` 会对比新旧 `configValues` 生成 `changed` 映射，重建 `configRefs`，在更新过程中输出 `nop.config.vars` 快照并在类型转换失败时打印 `nop.config.update-var-value-fail`，随后触发订阅者刷新运行时引用。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L181-L310】
- `DefaultBeanClassIntrospection.getRefreshConfigMethod` 识别实现 `IConfigRefreshable` 的 Bean 并返回其 `refreshConfig()` 或 `@OnConfigRefresh` 方法，使配置热更新能自动回调到业务 Bean 完成参数刷新。【F:nop-ioc/src/main/java/io/nop/ioc/impl/DefaultBeanClassIntrospection.java†L95-L100】
- `initVirtualFileSystem` 会注册新的 `DefaultVirtualFileSystem` 并触发 `ModuleManager.instance().discover()`，保证 `_delta` 等资源在配置后可用。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L206-L215】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L491-L495】
- `ConfigConstants` 统一列出常用配置键与默认路径，包括 `nop.config.bootstrap-location`、`nop.config.location`、`nop.config.additional-location` 等，可直接对照配置文件命名。【F:nop-config/src/main/java/io/nop/config/ConfigConstants.java†L57-L96】
- `nop.orm.session-check-context` 以 `OrmConfigs.CFG_ORM_SESSION_CHECK_CONTEXT` 的 `IConfigReference` 形式暴露，任何被 `CompositeConfigSource` 合并的配置源都可以覆盖该值，并在 `OrmSessionImpl` 构造时决定是否记录上下文引用。【F:nop-orm/src/main/java/io/nop/orm/OrmConfigs.java†L84-L88】【F:nop-orm/src/main/java/io/nop/orm/session/OrmSessionImpl.java†L126-L236】
- `CompositeConfigSource` 逆序合并列表元素，因此越早加入 `configSources` 的配置源优先级越高，可据此解读装配链中各来源的覆盖顺序。【F:nop-config/src/main/java/io/nop/config/source/CompositeConfigSource.java†L34-L41】
- `ConfigRouterProcessor` 解析 `nop.config.product.router`、`nop.config.app.router` 的 JSON 文本，使用 `FilterBeanEvaluator` 评估 `routes[].condition`，命中后将 `routeName` 作为前缀筛选键值并去除前缀后合并到最终配置，形成灰度覆盖。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L23-L75】【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L44】
- `RouterConfigSource` 先复制基础键值，再依次合并产品路由与应用路由的结果，意味着相同键名时应用路由会覆盖产品路由，最终再覆盖基础值，形成“基础 → 产品 → 应用”的覆盖链。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L28-L44】
- `RouterConfigSource` 在执行 `putAll(productRoute)`、`putAll(appRoute)` 时未对空映射做判空处理，若仅配置 `nop.config.app.router` 或产品路由未命中条件导致返回 `null`，会在 `putAll(productRoute)` 处抛出 `NullPointerException`，因此当前使用者必须确保两种路由都返回非空映射或在上线前修复该空指针缺陷。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L30-L45】【F:nop-commons/src/main/java/io/nop/commons/util/CollectionHelper.java†L124-L129】
- `NopConfigRouter` 仅暴露 `enabled`（默认 true）与 `routes` 列表字段；`ConfigRouterProcessor` 在解析 JSON 后直接进入路由选择，并未读取 `enabled` 标志，因此 `enabled:false` 仍会触发路由评估，需要通过删除配置项来停用灰度逻辑。【F:nop-config/src/main/java/io/nop/config/router/NopConfigRouter.java†L16-L33】【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L25-L75】
- `VarScope` 将完整配置映射适配为 `IVariableScope`，供 `FilterBeanEvaluator` 依据名称读取 `ValueWithLocation` 的原始值，从而支持在条件中引用 `nop.application.version` 等键。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L38-L52】【F:nop-core/src/main/java/io/nop/core/model/query/FilterBeanEvaluator.java†L19-L56】
- `chooseRoute` 采用首匹配策略：按配置顺序遍历 `routes`，跳过无条件项，首个评估为 `true` 的路由立即返回，其余路由不会执行；若全部未命中则返回 null。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L53-L72】

### 来源→覆盖级别速查表
| 优先级（高→低） | 来源/文件 | 触发配置键 | 说明 |
| --- | --- | --- | --- |
| Router 灰度叠加 | `RouterConfigSource`（产品/应用路由） | `nop.config.product.router`、`nop.config.app.router` | 根据 JSON 路由规则选出前缀命名的配置项，再覆盖基础值；在 profile 包装之后执行，属于最终灰度覆盖层。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L195-L200】【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L41】|
| Profile 包装 | `ProfileConfigSource`（active profile、parent profile、Quarkus `%dev.` 语法） | `nop.profile`、`nop.profile.parent` 等 | 先根据 `ConfigStarter` 解析的 profile 列表包装配置，再识别 `%dev.` 等前缀，使 profile 配置优先于应用默认值。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L191-L199】【F:docs/dev-guide/config.md†L12-L27】|
| 应用级追加 | `nop.config.additional-location` 指定文件、`application-{profile}.yaml`、`application.yaml`、`nop.config.yaml` | `nop.config.additional-location`、`nop.config.location`、`nop.config.yaml` | 追加顺序为附加文件→ profile 文件→ 主应用文件→ `nop.config.yaml`；因合并采用逆序覆盖，附加文件拥有最高覆盖能力，可最终修正远程或 JDBC 值。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L405】【F:docs/dev-guide/config.md†L23-L38】|
| 扩展 Loader | SPI `IConfigSourceLoader` 结果 | ServiceLoader | 通过 SPI 增强的额外配置源按注册顺序加入，位于 JDBC 与基础源之间，用于模块自定义覆盖。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L170-L176】|
| JDBC 配置 | `JdbcConfigSource` | `nop.config.jdbc.*` | 当配置有效时插入在基础源之前，覆盖 bootstrap 与系统属性，但仍低于远程、K8s、附加文件。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L357-L365】|
| K8s Secret & Props | `KeyFileConfigSource`、`PropsFileConfigSource` | `nop.config.key-config-source.paths`、`nop.config.props-config-source.paths` | 通过路径 CSV 列表启用，优先级低于配置中心但高于环境变量、系统属性与 bootstrap。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L132-L155】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L333-L355】|
| 配置中心 | `{app}-{profile}.yaml`、`{app}.yaml`、`{product}.yaml` | 依赖 `nop.application.name`、`nop.profile`、`nop.product.name` | 远程源按 profile→应用→产品顺序加入列表，整体高于本地基础源。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L294-L330】|
| 基础源 | 环境变量、系统属性、`bootstrap.yaml` | `System.getenv`、`System.getProperties`、`nop.config.bootstrap-location` | 基础源在链底，形成默认值；若未启用动态能力则仅保留此层。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L108-L180】【F:docs/dev-guide/config.md†L14-L27】|

## 4. 推测（Hypotheses）
- ⚠️ 推测1：`dynamicEntityNames` 集合虽能通过 `SessionFactoryConfig.setDynamicEntityNames` 注入，但默认 Bean 定义与 Starter 均未绑定 `@InjectValue`，推测实际项目需在外部配置源或 `_delta` Bean 中显式声明 `<property name="dynamicEntityNames">`。验证计划：
  1. 继续监控 `config/`、`deploy/`、`_delta` 目录及配置中心样本，一旦出现该 `<property>` 或等效配置即记录来源与实体清单；
  2. 设计 Run-less 验证脚本：准备附加文件在 `nopOrmSessionFactory` Bean 上设置集合，并跟踪 `ConfigStarter` 合并顺序确保高优先级源覆盖默认定义。【F:nop-orm/src/main/java/io/nop/orm/factory/SessionFactoryConfig.java†L73-L134】【F:nop-orm/src/main/resources/_vfs/nop/orm/beans/orm-defaults.beans.xml†L36-L49】【F:docs/ref/merged-app.beans.xml†L266-L287】
- ⚠️ 推测2：`RouterConfigSource` 当前仅对配置键前缀生效，是否需要与 `_tenant` 差量或模块路由联动仍待验证；验证计划：
  1. 继续检索 Demo/部署配置，确认是否存在 `routeName` 与 `_tenant` 目录或模块启停的绑定；
  2. 若缺示例，则设计 Run-less 推演：在文档中列出路由命名与差量资源映射的假设流程，待运行日志或配置样本出现后验证。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L44】【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L23-L75】

## 5. 规则（IF-THEN）
- IF 需要禁用远程配置中心 THEN 在 `bootstrap.yaml` 或环境变量设置 `nop.config.service.enabled=false`，`ConfigStarter` 将跳过 `loadFromConfigCenter` 并仅使用本地源。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L242-L324】
- IF 希望在应用级覆盖远程参数 THEN 在 `nop.config.additional-location` 指定的文件或 `application-{profile}.yaml` 中写入键值，因其被加入到 `configSources` 列表末尾，会在 profile 包装后拥有更高优先级。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L393】
- IF 需要引用额外 YAML/Properties 文件且保持可移植性 THEN 可在 `nop.config.additional-location` 使用 `classpath:`、`file:` 等 URI 前缀，`buildConfigResource` 会解析路径并交由 `ResourceConfigSourceLoader` 构造成新的配置源。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L407】
- IF 在路由 JSON 中声明多个规则 THEN 需按优先级排列列表项，因为 `chooseRoute` 命中首个条件后即返回并忽略后续路由。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L53-L72】
- IF 试图通过设置 `enabled:false` 停用路由 THEN 需要注意 `ConfigRouterProcessor` 未检查该字段，正确做法是移除 `nop.config.*.router` 配置或清空 `routes` 列表。【F:nop-config/src/main/java/io/nop/config/router/NopConfigRouter.java†L16-L33】【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L25-L75】
- IF 仅配置 `nop.config.app.router` 或产品路由未命中任何条件 THEN `RouterConfigSource` 在执行 `putAll(productRoute)` 时会因为传入 `null` 直接抛出 `NullPointerException`，需在上线前补充产品路由映射或修正源码以处理空映射。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L30-L45】
- IF 需要允许跨线程使用 ORM Session THEN 在高优先级配置源（附加文件、配置中心或 profile 文件）写入 `nop.orm.session-check-context=false`，因为 `OrmSessionImpl` 在构造时读取该配置并缓存结果；重新启用守护需先调整配置再创建新的 session。【F:nop-orm/src/main/java/io/nop/orm/OrmConfigs.java†L84-L88】【F:nop-orm/src/main/java/io/nop/orm/session/OrmSessionImpl.java†L126-L236】
- IF 需要定期从 K8s Secret/ConfigMap 更新敏感参数 THEN 在配置中填入 `nop.config.key-config-source.paths` 与刷新间隔，`ConfigStarter` 会创建 `KeyFileConfigSource` 并加入远程源之后。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L333-L343】
- IF 要启用 JDBC 配置存储 THEN 提供完整的 `nop.config.jdbc.*` 参数，使 `JdbcConfig.valid()` 返回 true，`ConfigStarter` 会把数据库配置插入链路并确保其优先于本地文件。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L357-L364】
- IF 需要按路由灰度配置 THEN 在配置中心或应用 YAML 中声明 `nop.config.router`/`nop.config.app.router` JSON，并将待灰度键写成 `<routeName>.{key}`；命中条件后 `ConfigRouterProcessor` 会去除前缀覆盖原键值。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L23-L75】【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L44】
- IF 同时配置产品与应用路由 THEN 需注意 `RouterConfigSource` 先合并产品路由、再合并应用路由，相同键名会被应用路由覆盖，应将环境级灰度写入应用路由以确保最终值。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L28-L44】

## 6. 流程（Text-Sequence）
1. **基础源装载**：收集 env、system properties、`bootstrap.yaml` 并组合为 `baseSource`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L108-L147】
2. **远程服务初始化**：若允许，加载配置中心、执行器，并从远程获取应用、profile、产品配置，置于优先级顶部。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L121-L324】
3. **扩展源合并**：按顺序加载 K8s KeyFile、PropsFile、JDBC 源以及 SPI 扩展 loader，依次加入 `configSources`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L132-L177】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L333-L364】
4. **应用文件叠加**：加载 `application.yaml`、`application-{profile}.yaml`、`nop.config.yaml` 与附加文件，并记录 active profiles。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L184-L393】
5. **Profile/Router 包装**：构造 `ProfileConfigSource` 与 `RouterConfigSource`，实现按 profile、产品名筛选配置项。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L191-L215】【F:nop-config/src/main/java/io/nop/config/ConfigConstants.java†L21-L35】
6. **ConfigProvider 激活**：调用 `updateConfigSource` 将组合源注入 `DefaultConfigProvider`，并注册变更监听器与 `ConfigChangeApplier`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L201-L219】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L468-L475】
7. **运行期准备**：调节日志级别、初始化虚拟文件系统、加载配置模型、国际化消息与错误码映射，最后启用热更新能力。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L204-L220】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L477-L535】
   - 配置变更链：`configSource.addOnChange` → `ConfigChangeApplier.requestUpdate()` → `IConfigExecutor.execute(applyChange)` → `DefaultConfigProvider.applyChange()` → 触发 `subscriptions` → `IConfigRefreshable.refreshConfig()`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L475】【F:nop-config/src/main/java/io/nop/config/impl/ConfigChangeApplier.java†L21-L54】【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L255-L294】【F:nop-ioc/src/main/java/io/nop/ioc/impl/DefaultBeanClassIntrospection.java†L95-L100】
   - 日志线索：`DefaultConfigProvider` 在成功应用配置时输出 `nop.config.vars`，若转换失败会追加 `nop.config.update-var-value-fail`，可结合 `changed` 映射与 `ValueWithLocation` 定位问题键值。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L181-L310】
   - 日志链路速查：① `ConfigStarter.updateConfigSource` 把 `configSource.addOnChange` 绑定到 `ConfigChangeApplier.requestUpdate()`，触发链路入口。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L470】② `ConfigChangeApplier` 通过 `shouldUpdate.compareAndSet` 与 `executor.execute` 确保同一时刻只有一次 `applyChange` 任务在队列中。【F:nop-config/src/main/java/io/nop/config/impl/ConfigChangeApplier.java†L40-L54】③ `DefaultConfigProvider.applyChange()` 重新拉取 `configValues`、构建 `changed` 映射并重建 `configRefs`。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L255-L294】④ `applyChangeToUsed` 将最新值推送给既有引用，若类型转换失败会打印 `nop.config.update-var-value-fail`。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L297-L311】⑤ `traceConfigVars` 根据 `CFG_CONFIG_TRACE` 控制开关输出 `nop.config.vars` 快照，包含 `ValueWithLocation` 供排查覆盖来源。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L174-L206】⑥ `subscriptions.trigger` 通知 IoC 容器，`OrmSessionFactoryBean.refreshConfig()` 等实现 `IConfigRefreshable` 的 Bean 会重新读取缓存参数完成最终落地。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L293-L294】【F:nop-orm/src/main/java/io/nop/orm/factory/OrmSessionFactoryBean.java†L55-L65】【F:nop-ioc/src/main/java/io/nop/ioc/impl/DefaultBeanClassIntrospection.java†L95-L100】

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| Idle | 调用 `ConfigStarter.start()` | 无 | BaseSourcesReady | 创建 `CompositeConfigSource` 聚合 env/system/bootstrap。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L105-L147】|
| BaseSourcesReady | 远程配置可用 | `nop.config.service.enabled` 为 true | RemoteSourcesMerged | 加载配置中心、KeyFile、PropsFile 并追加到链顶。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L121-L177】|
| RemoteSourcesMerged | JDBC 配置有效 | `JdbcConfig.valid()` | JdbcSourceAttached | 加入 `JdbcConfigSource` 并重建 Composite。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L357-L364】|
| JdbcSourceAttached | 读取应用文件 | `application.yaml` 存在 | AppConfigsStacked | 合并应用及 profile 配置，记录 active profiles。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L184-L393】|
| AppConfigsStacked | 构建 Profile/Router | profiles 非空或配置路由启用 | ProviderActivated | 包装 `ProfileConfigSource`、`RouterConfigSource` 并注入 `DefaultConfigProvider`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L191-L215】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L428-L470】|
| ProviderActivated | 配置变更 | `changeApplier` 激活 | Updating | `ConfigChangeApplier` 触发 `configProvider.applyChange()` 并串行执行更新。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L468-L475】|
| Updating | 更新完成 | `changeApplier` 正常执行 | ProviderActivated | 继续监听下一轮变更；若停机则进入 `Stopped` 状态并清理资源。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L509-L535】|

## 8. 接口契约（I/O 表：端点、字段、约束、错误码）
| 接口/方法 | 输入 | 输出 | 约束 | 错误/异常 |
| --- | --- | --- | --- | --- |
| `ConfigStarter.doStart()` | 无 | 初始化后的配置环境 | 需先设置 `CoreInitialization` 上下文；多次调用需确保生命周期管理 | 缺失应用名导致抛出 `ERR_CONFIG_MISSING_APPLICATION_NAME`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L105-L331】|
| `loadFromConfigCenter` | `baseSource` | 远程配置源列表 | `nop.application.name` 必填 | 远程调用异常会释放已加载源并抛 `NopException`。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L294-L330】|
| `loadKeyFileConfigSource` | `configSource` | `KeyFileConfigSource` | 需提供路径 CSV | 路径为空返回 null，不报错。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L333-L343】|
| `loadJdbcSource` | `configSource` | `JdbcConfigSource` | `nop.config.jdbc.*` 需构成有效配置 | 校验失败返回 null；数据库连接失败会抛异常。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L357-L364】|
| `updateConfigSource` | `configSource` | 无 | 需要有效的 `DefaultConfigProvider` | 会注册 `ConfigChangeApplier` 并监听源变更。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L468-L475】|
| `ConfigStarter.doStop()` | 无 | 无 | 必须在停止阶段调用以释放资源 | 停止配置中心失败会记录 `nop.config.stop-fail` 日志。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L509-L535】|
| `ConfigRouterProcessor.getRouteConfigVars` | 全量配置映射、路由变量名 | 命中路由的键值（去除前缀） | 需提供 JSON 文本，字段包含 `routes[].condition` 与 `routes[].routeName` | JSON 解析失败抛 `JsonTool` 异常；未命中条件返回 null。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L23-L75】|

## 9. 用例（Few-shot：Given-When-Then）
- 正向：Given 线上环境启用了配置中心且 K8s Secret 中存放数据库密码，When `ConfigStarter` 启动时加载远程配置、KeyFile 源，Then 应用以 Secret 值覆盖 bootstrap 中的占位符并完成数据库连接配置。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L128-L343】
- 反向：Given 未在 `bootstrap.yaml` 指定 `nop.application.name`，When `loadFromConfigCenter` 执行时，Then 抛出 `ERR_CONFIG_MISSING_APPLICATION_NAME` 并终止启动，提示必须提供应用名。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L294-L330】
- 边界：Given 分析模式下执行文档生成，When `CoreInitialization.isForAnalyze()` 返回 true，Then `ConfigStarter` 跳过远程与数据库加载，只使用本地基础源，避免外部依赖阻塞分析流程。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L121-L180】
- 边界：Given 在 `application.yaml` 写入 `nop.orm.session-check-context=false`，When `ConfigStarter` 合并应用文件并激活 `DefaultConfigProvider`，Then 新建的 `OrmSessionImpl` 会读到该值并跳过上下文校验，因此需在操作手册中附带跨线程同步策略与手动回收步骤。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L184-L219】【F:nop-orm/src/main/java/io/nop/orm/OrmConfigs.java†L84-L88】【F:nop-orm/src/main/java/io/nop/orm/session/OrmSessionImpl.java†L126-L236】
- 冲突：Given 同时配置了 `nop.config.product.router` 与 `nop.config.app.router`，When `RouterConfigSource` 聚合灰度结果时，Then 因 `merged.putAll(productRoute)` 后紧接着执行 `merged.putAll(appRoute)`，应用路由的键值覆盖产品路由，确保实例级灰度优先生效。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L28-L44】
- 诊断：Given 配置刷新后日志中出现 `nop.config.update-var-value-fail`，When 对照 `nop.config.vars` 输出的 `ValueWithLocation` 行，Then 可定位触发异常的键名与来源文件，并决定是否需要调整配置或补充类型转换。【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L181-L310】
- 日志链：Given 监控到 `configSource.addOnChange` 触发后 `ConfigChangeApplier` 仅执行一次 `applyChange`，When `DefaultConfigProvider.applyChange()` 输出 `nop.config.vars`/`nop.config.update-var-value-fail` 并触发 `subscriptions`，Then `OrmSessionFactoryBean.refreshConfig()` 重新调整查询计划与全局缓存配置，表明整个热更新链路闭环完成。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L475】【F:nop-config/src/main/java/io/nop/config/impl/ConfigChangeApplier.java†L40-L54】【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L255-L311】【F:nop-orm/src/main/java/io/nop/orm/factory/OrmSessionFactoryBean.java†L55-L65】
- 组合：Given `bootstrap.yaml` 中通过 `nop.config.additional-location=classpath:config/override.yaml` 指向附加文件，且同时启用了 `application-dev.yaml` 与 `nop.config.app.router` 灰度路由，When `ConfigStarter` 装载应用配置与路由包装时，Then 附加文件的值先于 profile 文件写入列表、最终由 Router 叠加灰度键值，实现“附加文件覆盖远程 → profile 微调 → Router 面向特定版本灰度”的叠加链路。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L405】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L195-L200】【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L44】【F:docs/dev-guide/config.md†L23-L38】
- 正向灰度：Given `nop.config.app.router` 配置为 `{"routes":[{"condition":{$type:"eq",name:"nop.application.version",value:"2.0"},"routeName":"app2"}]}`，且配置中存在 `app2.nop.xxx=2` 与基础键 `nop.xxx=1`，When `ConfigRouterProcessor` 根据 `nop.application.version=2.0` 评估条件时，Then 命中 `app2` 路由并返回去前缀的 `nop.xxx=2` 覆盖默认值。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L31-L75】
- 边界灰度：Given `nop.config.app.router` JSON 中声明 `enabled:false` 但仍保留 `routes` 列表，When `ConfigRouterProcessor` 解析配置时，Then 因未检查 `enabled` 标志仍会执行 `chooseRoute` 并返回匹配结果，需要通过删除配置或清空 `routes` 才能停用灰度逻辑。【F:nop-config/src/main/java/io/nop/config/router/NopConfigRouter.java†L16-L33】【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L25-L75】
- 反向（仅启用应用路由）：Given 只在配置中心写入 `nop.config.app.router` 且产品路由未配置，When `RouterConfigSource` 计算灰度时，Then `productRoute` 为 `null`，在 `putAll(productRoute)` 处立即抛出 `NullPointerException`，导致整批配置加载失败，需要补充产品路由映射或修复源码空指针。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L30-L45】
- Run-less 缺陷复现计划（Router 空指针）：
  1. 构造仅包含 `nop.config.app.router` 路由声明与匹配键的配置映射，例如 `app2.nop.xxx=2`、`nop.xxx=1` 及 `nop.config.app.router={"routes":[{"condition":{$type:"eq",name:"nop.application.version",value:"2.0"},"routeName":"app2"}]}`，同时缺失 `nop.config.product.router`，保证产品路由返回 `null`。【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L31-L75】
  2. 在配置装配阶段调用 `ConfigStarter` 包装路由源，即 `new RouterConfigSource(baseSource)`，触发 `RouterConfigSource.getConfigValues()` 读取上述映射。【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L193-L215】【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L41】
  3. 由于 `merged.putAll(productRoute)` 接收到 `null`，预期 `HashMap.putAll` 在 `RouterConfigSource.getConfigValues()` 内抛出 `NullPointerException`，将堆栈定位到 `RouterConfigSource` 行 36 左右，为缺陷报告提供可复现证据。【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L33-L41】

## 10. 证据矩阵（结论↔证据↔置信度↔Run-less验证）
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| 配置源优先级遵循 env > system > bootstrap > 远程 > K8s/Props > JDBC > 应用文件 | `ConfigStarter.doStart()` 的合并顺序 | 高 | 对照 `ConfigStarter` 日志关键字（如 `nop.profiles.active`）确认加载顺序。 |【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L108-L364】|
| 应用文件与 profiles 会在远程源之后覆盖参数 | `loadAppConfigs` 与 `ProfileConfigSource` 包装逻辑 | 高 | 检查 Demo `application-dev.yaml`，确认是否覆盖远程值。 |【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L393】【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L191-L215】|
| `ConfigChangeApplier` 保证热更新串行执行 | `updateConfigSource` 绑定 `changeApplier::requestUpdate`，`ConfigChangeApplier` 借助 `IConfigExecutor` 串行调度 `applyChange` | 高 | 查看配置中心示例或日志以验证变更回调顺序。 |【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L475】【F:nop-config/src/main/java/io/nop/config/impl/ConfigChangeApplier.java†L15-L54】|
| 配置刷新日志可串联到缓存刷新动作 | `ConfigChangeApplier` 触发 `DefaultConfigProvider.applyChange()`，输出 `nop.config.vars`/`nop.config.update-var-value-fail` 后通知订阅者，`OrmSessionFactoryBean.refreshConfig()` 响应更新缓存参数 | 中 | 收集一次完整日志序列，验证 `nop.config.vars` → `refreshConfig()` 顺序与缓存容量调整效果。 |【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L465-L475】【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L255-L311】【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L174-L206】【F:nop-orm/src/main/java/io/nop/orm/factory/OrmSessionFactoryBean.java†L55-L65】|
| `DefaultConfigProvider.applyChange` 输出日志用于定位变更失败项 | `nop.config.vars`/`nop.config.update-var-value-fail` 日志在应用变更时打印 | 中 | 在调试记录中比对日志与 `changed` map，确认失败项与来源路径。 |【F:nop-config/src/main/java/io/nop/config/impl/DefaultConfigProvider.java†L181-L310】|
| Router 灰度需要 JSON 路由声明并以前缀键命名 | `ConfigRouterProcessor` 解析 JSON 并去除 `routeName` 前缀覆盖原键 | 中 | 在 Demo/配置样例中搜集 `nop.config.app.router`、`app2.xxx` 等前缀配对，验证静态分析推演。 |【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L23-L75】【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L23-L44】|
| 应用路由优先于产品路由覆盖灰度键值 | `RouterConfigSource` 依次 `putAll(vars)` → `putAll(productRoute)` → `putAll(appRoute)`，后者覆盖前者 | 中 | 在真实配置中验证应用路由覆盖效果，记录冲突键的最终值。 |【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L28-L44】|
| 仅启用应用路由会触发空指针异常 | `RouterConfigSource` 未对 `productRoute` 判空，`putAll(productRoute)` 传入 `null` | 高 | 已整理 Run-less 复现计划（见上一节），可基于 `ConfigStarter`→`RouterConfigSource` 链路静态触发异常并跟踪后续补丁。 |【F:nop-config/src/main/java/io/nop/config/source/RouterConfigSource.java†L30-L45】|
| 路由选择遵循首匹配策略，需要将高优先级规则排在前面 | `chooseRoute` 按顺序遍历 `routes` 并在首个条件命中后立即返回 | 中 | 收集实际灰度配置，核对路由数组顺序与预期优先级是否一致。 |【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L53-L72】|
| `enabled:false` 不会阻止路由执行 | `NopConfigRouter` 定义 `enabled` 字段但 `ConfigRouterProcessor` 未读取该标志 | 低 | 搜集后续版本或配置示例确认是否引入开关支持，并记录升级差异。 |【F:nop-config/src/main/java/io/nop/config/router/NopConfigRouter.java†L16-L33】【F:nop-config/src/main/java/io/nop/config/router/ConfigRouterProcessor.java†L25-L75】|
| 附加文件在应用层拥有最高覆盖优先级 | `loadAppConfigs` 先处理 `nop.config.additional-location` 并将其插入列表首位 | 中 | 收集 Demo/部署脚本中的附加文件路径，验证覆盖顺序与路径写法。 |【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L366-L407】|
| 分析模式跳过远程加载，适用于文档/静态分析 | `isForAnalyze()` 条件分支 | 中 | 在 `docs` 或 CLI 工具中搜索是否启用该模式并记录路径。 |【F:nop-config/src/main/java/io/nop/config/starter/ConfigStarter.java†L121-L180】|
| `ConfigConstants` 提供默认路径与键名，便于定位配置覆盖入口 | 常量定义列表 | 高 | 结合 `bootstrap.yaml` 示例确认常量与文件实际命名一致。 |【F:nop-config/src/main/java/io/nop/config/ConfigConstants.java†L57-L96】|
| `CompositeConfigSource` 逆序覆盖确保“先加入→后覆盖”的优先级模型 | `CompositeConfigSource#getConfigValues()` 实现 | 高 | 结合速查表回溯列表插入顺序，校验特定来源是否被预期覆盖。 |【F:nop-config/src/main/java/io/nop/config/source/CompositeConfigSource.java†L34-L41】|
| `nop.orm.session-check-context` 由配置源驱动 | `CFG_ORM_SESSION_CHECK_CONTEXT` 暴露为 `IConfigReference`，`OrmSessionImpl` 在构造时读取结果并决定是否校验上下文 | 中 | 收集设置该键的真实配置片段，并确认关闭后需重新创建 session 才能生效。 |【F:nop-orm/src/main/java/io/nop/orm/OrmConfigs.java†L84-L88】【F:nop-orm/src/main/java/io/nop/orm/session/OrmSessionImpl.java†L126-L236】|

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.10 | 2024-06-16 | 补充 Router 空指针 Run-less 复现计划并在证据矩阵记录计划已就绪，便于后续跟踪修复。 |
| v0.9 | 2024-06-15 | 标记 `RouterConfigSource` 在仅启用应用路由时会抛出 `NullPointerException`，补充规则、反向用例与证据矩阵条目提醒上线风险。 |
| v0.8 | 2024-06-12 | 增补热更新日志链路速查，明确 `nop.config.vars`/`nop.config.update-var-value-fail` 到缓存刷新之间的触发顺序。 |
| v0.7 | 2024-06-11 | 梳理配置热更新触发顺序、`ConfigChangeApplier` 串行机制与 `nop.config.vars` 调试日志，用于排查运行期覆盖异常。 |
| v0.6 | 2024-06-10 | 记录 Router JSON 结构、首匹配规则与 `enabled` 标志的实际行为，补充灰度停用边界案例与证据矩阵条目。 |
| v0.5 | 2024-06-09 | 解析 RouterConfigSource 的合并顺序，明确应用路由覆盖产品路由并补充冲突用例与证据。 |
| v0.4 | 2024-06-09 | 明确 `nop.config.additional-location` 的解析顺序与路径支持，补充 `DefaultConfigProvider` 热更新引用机制。 |
| v0.3 | 2024-06-08 | 梳理 `ConfigRouterProcessor` 的 JSON 路由机制，补充灰度规则、接口契约与正向用例，明确路由前缀覆盖逻辑。 |
| v0.2 | 2024-06-07 | 新增来源→覆盖级别速查表与附加文件/Router 叠加用例，明确 `CompositeConfigSource` 优先级规则。 |
| v0.1 | 2024-06-07 | 首次整理 `nop-config` 启动流程、优先级链、热更新机制与配置键参考。 |

## 12. 检索标签（Tags）
`#nop-config` `#ConfigStarter` `#ConfigSource` `#Profile` `#Router` `#RunLess`
