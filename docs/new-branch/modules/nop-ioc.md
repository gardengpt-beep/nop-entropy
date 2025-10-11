# IoC 容器机制（nop-ioc）

## 1. 摘要（TL;DR）
1. `AppBeanContainerLoader` 依据配置优先检查合并后的 `merged-app.beans.xml`，否则加载自动配置列表与各模块 `beans/app-*.beans.xml`，并允许追加自定义文件，最终构建应用容器。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L76-L150】【F:nop-ioc/src/main/java/io/nop/ioc/IocConfigs.java†L17-L60】
2. `BeanContainerBuilder` 使用 `DslModelParser` 解析 `beans` DSL，递归处理 `<import>`、默认 Bean 归并以及 util constant/list/map/set/config，统一收敛为 `BeanDefinition` 集合。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L97-L239】
3. `BeanContainerImpl` 会拓扑排序 Bean 依赖、维护别名映射、支持父容器委托，并在 `start()` 中按启动模式决定同步或异步初始化单例，再通过 `stop()` 关闭作用域与任务。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L47-L624】
4. `BeanScopeImpl` 为每个作用域维护线程安全的 Bean Map，将容器暴露到 `IEvalScope` 扩展并在关闭时逐一销毁实例，确保资源回收。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanScopeImpl.java†L27-L108】
5. IoC 配置项覆盖启动模式、并发装载、自动配置过滤、合并文件开关与 AOP 开启状态，是部署时约束容器行为的主入口。【F:nop-ioc/src/main/java/io/nop/ioc/IocConfigs.java†L17-L60】
6. `IocCoreInitializer` 在核心初始化阶段装载应用容器、注册为全局 `BeanContainer`，并在高初始化等级时立即启动，销毁阶段恢复父容器。【F:nop-ioc/src/main/java/io/nop/ioc/initialize/IocCoreInitializer.java†L21-L55】
7. 当启用并发启动时，容器会借助 `TaskExecutionGraph` 分析依赖并异步创建单例 Bean，完成后才触发延迟方法并置位 `started`。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L470-L599】
8. 调试模式下容器会把合并后的配置导出到 `_dump/merged-app.beans.xml`，可用于验证默认/差量 Bean 的最终状态。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L152-L163】

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| BeanContainer | Bean Container | `BeanContainerImpl` 的实例，负责解析、创建、管理 Bean，支持父子容器与别名映射，不负责读取配置文件。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L47-L199】|
| BeansModel | Beans DSL Model | 通过 `DslModelParser` 解析 `.beans.xml` 后的模型，包含 `<bean>`、`<import>`、`<util:list>` 等节点，构建阶段可被多次合并。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L97-L211】|
| Auto Config | Auto Configuration | `/nop/auto-config/*.beans` 文件列出待加载资源，受 `nop.ioc.auto-config.*` 开关与过滤器约束，仅注入 Builder，不直接创建 Bean。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L107-L149】|
| Bean Scope | Bean Scope | 通过 `BeanScopeImpl` 管理的命名作用域，支持单例、原型及自定义范围，并在关闭时调用 `destroyBean`。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanScopeImpl.java†L27-L108】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L419-L639】|
| Start Mode | Bean Container Start Mode | 控制单例初始化策略，支持同步或异步启动与惰性加载，由 `nop.ioc.app-beans-container.start-mode` 与 `concurrent-start` 配置决定。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L440-L599】【F:nop-ioc/src/main/java/io/nop/ioc/IocConfigs.java†L17-L24】|
| Merged Beans | Merged Beans File | `/nop/main/beans/merged-app.beans.xml` 的快照，若存在可跳过逐文件解析，常用于部署优化或调试导出。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L95-L166】|

## 3. 事实（Facts）
- `AppBeanContainerLoader.loadAppContainer` 依序应用启动模式、并发配置、合并文件、自动配置列表、模块级 `app-*.beans.xml` 以及额外文件列表，构建完成后按调试开关导出最终配置。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L76-L166】
- `docs/ref/merged-app.beans.xml` 展示 `_dump/merged-app.beans.xml` 导出的 Bean 快照会带 `<!--LOC:...-->` 注释指向原始配置路径，并保留 `x:validated="true"` 标记；例如 `nopOrmSessionFactory`、`nopOrmTemplate`、`nopDaoProvider` 均能追溯到 `nop/orm/beans/orm-defaults.beans.xml`，`nopDataSource` 则来源于 Quarkus 默认数据源 Bean，证明调试输出可直接反查每个 Bean 的归属与属性。【F:docs/ref/merged-app.beans.xml†L1-L132】
- 当前快照的 `LOC` 注释全部指向默认模块（`nop/orm/beans/orm-defaults.beans.xml`、`nop/auth/beans/auth-core-defaults.beans.xml`、`nop/quarkus/beans/quarkus-defaults.beans.xml` 等），尚未出现 `_delta` 或自定义 Bean 的来源记录，提示需要额外收集带差量 Bean 的环境以核对覆盖策略。【F:docs/ref/merged-app.beans.xml†L10-L132】
- `BeanContainerBuilder.registerBean` 支持编程方式注入 Supplier，并把 `BeanModel` 标记为 primary，方便在测试或差量场景覆盖默认定义。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L118-L139】
- Builder 在处理 default Bean 时会自动给 ID 添加 `Default$` 前缀并补充 `missingBean` 条件，避免与显式 Bean 冲突，兼容条件加载。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L253-L273】
- `BeanContainerImpl` 在构造阶段通过 `BeanTopologySorter` 拓扑排序 Bean，并根据 `iocAfter` 注册下游依赖，保证延迟触发的 Bean 也能按顺序初始化。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L60-L118】
- `getBean` 会根据 Bean 范围在单例作用域缓存或委派到自定义作用域，若 `nextBeans` 非空则顺次触发依赖 Bean 的创建，确保延迟方法所需 Bean 就绪。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L397-L416】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L419-L428】
- `start()` 根据启动模式筛选需要提前创建的单例，支持 `ALL_LAZY` 仅初始化带延迟方法或 `iocForceInit` 的 Bean，并在并发模式下用任务图执行。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L470-L599】
- `BeanContainerBuilder.concurrentStart` 会把 `nop.ioc.app-beans-container.concurrent-start` 标志写入容器，`start()` 进入异步分支后调用 `asyncStartBeans` 基于 `TaskExecutionGraph` 注册依赖、生成 `Cancellable`，并在回调中记录 `nop.ioc.async-start-finished`、执行 `runDelayMethod()`；调用方可通过 `awaitStartFinished()` 等待异步初始化完成。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L68-L118】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L520】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L564-L579】
- `stop()` 会标记容器停止、取消并发任务、关闭单例作用域并通知 `BeanScopeContext`，若销毁失败会记录错误并重新抛出。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L601-L624】
- `IocCoreInitializer` 若检测到已有父容器会暂存引用，加载完成后注册新的应用容器，并在销毁阶段恢复原先实例，避免覆盖系统级 Bean。【F:nop-ioc/src/main/java/io/nop/ioc/initialize/IocCoreInitializer.java†L30-L55】

## 4. 推测（Hypotheses，⚠️ + 验证计划）
- ⚠️ 并发启动下 `TaskExecutionGraph` 的依赖排序是否与 `BeanTopologySorter` 完全一致？计划：搜集一次 `nop.ioc.async-start-finished` 日志与任务图 dump，确认并发执行不会破坏初始化顺序。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L564-L599】
- ⚠️ `_dump/merged-app.beans.xml` 是否记录 `<validated>true</validated>` 之外的校验信息？计划：在调试环境触发 `AppConfig.isDebugMode()`，对比 `_dump` 输出与原始合并结果，确认差量标记是否保留。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L152-L163】

## 5. 规则（IF-THEN，可执行）
- IF `CFG_IOC_MERGED_BEANS_FILE_ENABLED` 为 true 且 `/nop/main/beans/merged-app.beans.xml` 存在 THEN Loader 仅加载该文件并跳过模块扫描，避免重复解析。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L95-L105】
- IF `CFG_IOC_AUTO_CONFIG_ENABLED` 为 true THEN Loader 逐条读取 `/nop/auto-config/*.beans` 指定的资源列表，并在解析失败时抛出 `NopException` 中断启动。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L107-L124】
- IF `CFG_IOC_APP_BEANS_FILE_ENABLED` 为 false THEN 模块级 `app-*.beans.xml` 会被整体跳过，需通过 `nop.ioc.app-beans.files` 明确列出额外资源。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L128-L148】
- IF `BeanDefinition` 标记了 `iocDefault=true` THEN Builder 自动重写 ID、补充别名与 `missingBean` 条件，确保默认实现仅在缺少显式 Bean 时装载。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L253-L273】
- IF `CFG_IOC_APP_BEANS_CONTAINER_CONCURRENT_START` 为 true THEN `BeanContainerBuilder` 会调用 `concurrentStart(true)`，`BeanContainerImpl.start()` 将提交 `TaskExecutionGraph.executeAsync` 并仅在回调中执行 `runDelayMethod()`、置位 `started`，需要在依赖延迟方法前调用 `awaitStartFinished()` 同步等待异步启动完成。【F:nop-ioc/src/main/java/io/nop/ioc/IocConfigs.java†L17-L24】【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L68-L118】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L520】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L564-L579】
- IF 容器尚未 `start()` 而调用 `getBean` THEN `checkStarted()` 抛出 `ERR_IOC_CONTAINER_NOT_STARTED`，提示先启动容器或等待异步启动完成。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L626-L639】

## 6. 流程（Text-Sequence）
1. `IocCoreInitializer.initialize()` 发现父容器（如 CLI/测试环境）并暂存引用。【F:nop-ioc/src/main/java/io/nop/ioc/initialize/IocCoreInitializer.java†L30-L36】
2. 创建 `AppBeanContainerLoader`，根据 `nop.ioc.*` 配置构建 `BeanContainerBuilder` 并加载合并/自动/模块/附加 beans 资源。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L76-L149】
3. `BeanContainerBuilder.build("app")` 解析 Beans DSL、导入依赖、归并默认 Bean，得到 `BeanContainerImpl`。【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L97-L239】
4. 应用容器注册到全局 `BeanContainer` 并在需要时立即调用 `start()` 初始化单例。【F:nop-ioc/src/main/java/io/nop/ioc/initialize/IocCoreInitializer.java†L35-L44】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L459-L505】
5. 若启用并发启动，`start()` 会收集需要预创建的单例，调用 `asyncStartBeans` 基于 `TaskExecutionGraph` 提交任务、注册 `Cancellable`，并在回调中执行 `runDelayMethod()` 与记录 `nop.ioc.async-start-finished`；未启用时同步执行并打印 `nop.ioc.start-finished`。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L599】
6. 运行期通过 `BeanContainerImpl.getBean`/`getBeanByType` 获取实例，容器根据作用域缓存或委派到父容器，并处理 `nextBeans` 延迟依赖。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L347-L416】
7. 调试模式将容器配置导出到 `_dump` 目录，便于复核差量与默认配置；销毁时调用 `stop()` 关闭作用域并恢复父容器注册。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L152-L163】【F:nop-ioc/src/main/java/io/nop/ioc/initialize/IocCoreInitializer.java†L48-L55】

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| NEW | `initialize()` | Loader 构建成功 | BUILT | 注册 `BeanContainerImpl`，保留父容器引用。【F:nop-ioc/src/main/java/io/nop/ioc/initialize/IocCoreInitializer.java†L30-L37】|
| BUILT | `start()` | 未运行且配置允许 | RUNNING | 创建单例作用域，初始化需要预加载的 Bean；可能异步执行任务图。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L459-L599】|
| RUNNING | 异步任务完成/非并发启动 | - | STARTED | 执行延迟方法，记录启动完成日志。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L499】|
| RUNNING/STARTED | `stop()` | - | STOPPED | 取消任务、关闭作用域、清理上下文并抛出销毁异常（若有）。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L601-L624】|

## 8. 接口契约（I/O 表：端点、字段、约束、错误码）
| 组件/入口 | 输入 | 输出 | 约束 | 错误/异常 |
| --- | --- | --- | --- | --- |
| `AppBeanContainerLoader.loadAppContainer` | 父容器（可为空） | `IBeanContainerImplementor` | 受 `nop.ioc.*` 配置控制资源来源；若自动配置/附加文件解析失败立即抛错 | 读取失败抛 `NopException` 并记录 `nop.ioc.process-auto-config-fail`。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L76-L149】|
| `BeanContainerImplementor.start` | - | - | 只能调用一次；根据启动模式选择同步或异步创建单例 | 重复启动抛 `ERR_IOC_CONTAINER_ALREADY_STARTED`；未启动访问 Bean 抛 `ERR_IOC_CONTAINER_NOT_STARTED`。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L459-L639】|
| `BeanContainerImplementor.injectTo` | 外部实例 | - | 容器需已启动；通过反射生成临时 `BeanDefinition` 注入属性 | 缺少依赖时抛 `NopException` 并打印 `nop.inject-props-to-bean` 调试日志。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L646-L654】|
| `BeanScope.close` | - | - | 每个作用域只关闭一次；关闭后访问将抛异常 | 重复访问抛 `ERR_IOC_BEAN_SCOPE_ALREADY_CLOSED`；销毁 Bean 失败记录 `nop.err.ioc.destroy-bean-fail`。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanScopeImpl.java†L65-L108】|

## 9. 用例（Few-shot：Given-When-Then）
- 正向（调试导出）：Given 以调试模式启动并启用 `CFG_IOC_MERGED_BEANS_FILE_ENABLED`，When 查看 `_dump/merged-app.beans.xml`，Then 可以在 `<!--LOC:...-->` 注释中读到各 Bean 的来源文件，例如 `nopOrmSessionFactory` 与 `nopDaoProvider` 指向 `nop/orm/beans/orm-defaults.beans.xml`、`nopDataSource` 指向 `nop/quarkus/beans/quarkus-defaults.beans.xml`，从而核对差量或默认 Bean 是否生效。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L95-L163】【F:docs/ref/merged-app.beans.xml†L1-L132】
- 反向（差量缺口）：Given 目前的 `_dump/merged-app.beans.xml` 仅包含默认模块 Bean，When 搜索 `<!--LOC:` 注释，Then 只会看到 `nop/orm`、`nop/auth`、`nop/quarkus` 等默认路径而没有 `_delta` 记录，需要另行导出包含差量 Bean 的快照才能验证覆盖优先级。【F:docs/ref/merged-app.beans.xml†L10-L132】
- 正向：Given 启用 `nop.ioc.auto-config.enabled=true` 并在 `/nop/auto-config` 声明若干 `.beans`，When 容器启动时读取列表并逐个添加资源，Then 所有列出的 Bean 将在运行期可用。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L107-L149】
- 反向：Given 关闭 `nop.ioc.app-beans-file.enabled=false` 但未在 `nop.ioc.app-beans.files` 指定任何资源，When 启动容器时扫描模块路径，Then 因过滤条件返回空列表仅加载显式追加文件，导致默认模块 Bean 不会注册。【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L128-L148】
- 边界：Given 设置 `nop.ioc.app-beans-container.start-mode=ALL_LAZY` 且某些单例定义了 `delayMethod`，When 调用 `start()`，Then 容器只会初始化带延迟方法或 `iocForceInit` 的 Bean，其余单例在首次访问时创建。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L470-L505】
- 正向（并发）：Given 开启 `nop.ioc.app-beans-container.concurrent-start=true`，When 执行 `start()`，Then 容器会构建任务图并异步初始化 Bean，完成后打印 `nop.ioc.async-start-finished` 并执行延迟方法。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L599】
- 边界（取消）：Given 并发启动尚未完成时调用 `stop()`，When `cancellable.cancel` 被触发，Then 未完成的启动任务会收到停止信号，容器关闭单例作用域并在必要时抛出销毁异常供外层处理。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L609】
- 反向：Given 在容器未启动时直接调用 `getBean`，When `checkStarted()` 发现 `running=false`，Then 抛出 `ERR_IOC_CONTAINER_NOT_STARTED`，提醒先执行 `start()` 或等待异步完成。【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L626-L639】

## 10. 证据矩阵（结论↔证据↔置信度↔Run-less验证）
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| 应用容器按合并文件→自动配置→模块→附加文件顺序加载资源 | `AppBeanContainerLoader.loadAppContainer` 的控制流 | 高 | 枚举示例模块的 `app-*.beans.xml` 并核对 `_dump` 输出验证顺序。 |【F:nop-ioc/src/main/java/io/nop/ioc/loader/AppBeanContainerLoader.java†L76-L166】|
| `_dump/merged-app.beans.xml` 可直接定位 Bean 来源路径并校验默认/差量是否生效 | 调试快照中的 `<!--LOC:...-->` 注释与 `nopOrmSessionFactory`、`nopDaoProvider`、`nopDataSource` 示例 | 高 | 收集更多模块的 `_dump` 快照，记录差量 Bean 的 `LOC` 注释与属性是否覆盖默认值。 |【F:docs/ref/merged-app.beans.xml†L1-L132】|
| 当前调试快照尚未覆盖 `_delta` Bean，需补充差量环境证据 | `<!--LOC:...-->` 注释全部指向默认模块目录（`nop/orm`、`nop/auth`、`nop/quarkus` 等） | 中 | 获取包含 `_delta` Bean 的 `_dump` 文件，统计差量属性与默认属性的重叠关系。 |【F:docs/ref/merged-app.beans.xml†L10-L132】|
| 默认 Bean 通过 ID 前缀与 `missingBean` 条件避免与显式 Bean 冲突 | `normalizeDefaultBean` 实现 | 高 | 对比 `nop-orm`、`nop-config` 默认 Bean，确认差量覆盖行为。 |【F:nop-ioc/src/main/java/io/nop/ioc/loader/BeanContainerBuilder.java†L253-L273】|
| 并发启动依赖 `TaskExecutionGraph` 保证拓扑顺序并在回调中完成延迟方法 | `asyncStartBeans` 任务图、`start()`/`awaitStartFinished()` 回调 | 中 | 捕获一次实际日志、验证 `nop.ioc.async-start-finished` 顺序并比对任务依赖。 |【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L485-L520】【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanContainerImpl.java†L564-L579】|
| `BeanScopeImpl` 关闭时会逐一销毁 Bean 并校验剩余实例 | `close()` 实现 | 高 | 审阅具有自定义作用域的模块，确认 destroy 钩子执行。 |【F:nop-ioc/src/main/java/io/nop/ioc/impl/BeanScopeImpl.java†L65-L108】|

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.4 | 2024-06-17 | 统计 `_dump/merged-app.beans.xml` 中的 `LOC` 注释仅覆盖默认模块，提示需另采样差量 Bean 快照并新增缺口用例。 |
| v0.3 | 2024-06-17 | 记录 `_dump/merged-app.beans.xml` 调试快照的 `LOC` 注释示例，并将其纳入用例与证据矩阵支撑差量验证。 |
| v0.2 | 2024-06-16 | 补充并发启动的配置入口、`TaskExecutionGraph` 执行与取消流程，并新增对应规则、用例与证据矩阵条目。 |
| v0.1 | 2024-06-14 | 首次整理 `nop-ioc` 容器装载流程、配置入口、作用域与启动模式，建立 Run-less 研究基线。 |

## 12. 检索标签（Tags）
`#nop-ioc` `#BeanContainer` `#AutoConfig` `#BeanScope` `#RunLess`

