# 业务动作与 BizObject（nop-biz）

## 1. 摘要（TL;DR）
1. `BizObjectManager` 以 `GraphQLBizModels` 和可选的动态模型提供器组装 BizObject，并通过租户感知的 LoadingCache 复用结果，同时暴露 GraphQL 类型、操作与文档聚合能力。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L74-L390】
2. `BizObjectBuilder` 在虚拟文件系统中回溯 `.xbiz`、`.xmeta` 与 Java 反射模型，生成 GraphQL 对象、装配状态机、装饰服务动作，并保留 meta 控制字段暴露范围。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L60-L332】
3. `BizObjectImpl` 保存 Biz 模型、ObjMeta、GraphQL 操作与运行态动作，提供 method_missing 钩子把 EvalAction 调用映射到 `IServiceAction`，并校验上下文与 Selection 参数类型。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectImpl.java†L44-L258】
4. `BizActionService.callActionAsync` 解析 BizAction 元数据决定执行器，支持按 hash 分区顺序执行并将结果转换为 `ApiResponse`。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L31-L100】
5. `BizActionInvocation` 负责在 RPC 执行链中补齐 `ServiceContext`，统一把 BizObject 返回值包裹为成功响应或异步转换。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvocation.java†L23-L85】
6. `BizActionInvoker` 为工作流、批处理等场景提供同步/异步直调入口，并根据 GraphQL 操作类型自动决定是否包裹在事务模板中执行。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L20-L101】
7. `BizActionModel`/`BizActionArgModel` 在初始化阶段同步类型与 Schema 元信息，为 EvalAction 装配参数构建器和返回值约束提供数据。【F:nop-biz/src/main/java/io/nop/biz/model/BizActionModel.java†L18-L63】【F:nop-biz/src/main/java/io/nop/biz/model/BizActionArgModel.java†L17-L39】
8. `BizConfigs` 暴露查询 in 条目与左连接数量上限，支撑前端过滤参数的校验策略。【F:nop-biz/src/main/java/io/nop/biz/BizConfigs.java†L17-L27】

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| BizObject | Biz Object | 聚合 Biz 模型、ObjMeta、GraphQL 操作与服务动作的静态实体，不持有非静态状态，通过 `IBizObject` 接口暴露 GraphQL 与 Action 查找能力。【F:nop-biz/src/main/java/io/nop/biz/api/IBizObject.java†L22-L83】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectImpl.java†L44-L208】|
| BizObjectManager | Biz Object Manager | 负责构建、缓存、销毁 BizObject，同时实现 GraphQL Schema Loader 接口，提供类型、操作、文档聚合方法。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L74-L390】|
| BizActionModel | Biz Action Model | 描述单个业务动作的类型、参数、返回值以及 GraphQL operation 类型，用于生成 GraphQL 字段与 `IServiceAction` 包装。【F:nop-biz/src/main/java/io/nop/biz/model/BizActionModel.java†L18-L63】|
| BizLoaderModel | Biz Loader Model | XBiz 定义的关联加载器，`BizModelToGraphQLDefinition` 将其转换为 GraphQL 字段并绑定数据取数逻辑。【F:nop-biz/src/main/java/io/nop/biz/impl/BizModelToGraphQLDefinition.java†L57-L154】|
| IActionDecoratorCollector | Action Decorator Collector | 收集服务动作装饰器以叠加缓存、事务、审计等横切逻辑，`BizObjectBuildHelper` 将其排序并包装至动作执行链。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuildHelper.java†L9-L101】|
| BizActionService | Biz Action Service | RPC 层入口，解析 BizAction 配置并选择执行器，将返回值转换为 `CompletionStage<ApiResponse<?>>`。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L31-L100】|
| BizActionInvoker | Biz Action Invoker | 提供绕过 GraphQL 的同步/异步直调方法，并可通过 GraphQL 引擎执行 BizAction 以获得标准响应。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L20-L101】|
| IDynamicBizModelProvider | Dynamic Biz Model Provider | 可插拔的动态 Biz 模型源，支持监听变更并刷新 BizObject 缓存。【F:nop-biz/src/main/java/io/nop/biz/impl/IDynamicBizModelProvider.java†L1-L19】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L121】|

## 3. 事实（Facts）
- `BizObjectManager.init()` 若未注入 TypeRegistry 会创建默认实例，注册全局缓存，并按 Schema 初始化器预热类型后利用 `GraphQLBizModels.build` 收集所有 Biz 模型。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L147-L169】
- BizObject 缓存通过 `ResourceTenantManager.makeLoadingCache("biz-object-cache", this::buildBizObject, null)` 构建，支持多租户隔离并在 `@PreDestroy` 释放缓存与动态监听器。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L196】
- `BizObjectBuilder` 先尝试当前对象的 `.xbiz/.xmeta`，若缺失会回退至基础对象名，必要时要求补充派生对象的 `.xmeta`，否则抛出缺失异常。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L201-L240】
- XBiz 定义的 Loader 会转化为 GraphQL 字段并绑定 EvalAction fetcher；若 Loader 声明 `ContextSource` 集合参数则自动装配 BatchFetcher。【F:nop-biz/src/main/java/io/nop/biz/impl/BizModelToGraphQLDefinition.java†L105-L173】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L278-L321】
- BizObject 的 GraphQL 操作若缺少服务实现会记录 `nop.biz.operation-no-impl-action` 日志，但不会直接抛异常，以便后续由继承或装饰器补齐。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L178-L188】
- `BizObjectImpl.method_invoke` 接受 `ApiRequest` 或拆分参数，严格校验 `FieldSelectionBean` 与 `IServiceContext` 类型，并允许从 `EvalScope` 获取上下文变量。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectImpl.java†L211-L257】
- `BizActionService` 根据 `IBizActionModel.isBizSequential()` 决定是使用 `IPartitionedExecutor.executeForPartition` 顺序执行，还是直接在 Executor 上异步提交任务。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L79】
- `BizActionInvocation.proceedAsync()` 优先复用上下文中已有的 `ServiceContext`，否则新建并注入请求数据，然后调用 BizObject.invoke 并将返回值统一转换为 `ApiResponse`。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvocation.java†L57-L80】
- 直调入口 `BizActionInvoker.invokeActionSync` 在 ORM Session 中执行查询动作，对于非 Query 默认套入事务模板，确保写操作具备事务语义；异步版本对应调用 `runInSessionAsync` 与异步事务。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L34-L75】
- `BizConfigs` 暴露的查询参数上限通过 `AppConfig.varRef` 构造，可在配置中心覆盖，默认 in 操作最多 100 个值、左连接条件最多 3 个。【F:nop-biz/src/main/java/io/nop/biz/BizConfigs.java†L17-L27】
- `CacheActionDecoratorCollector` 根据 `@Cache`、`@CacheEvict(s)` 注解和 BizAction 的缓存模型构造缓存/驱逐装饰器，先调用 `XLang.newCompileTool()` 编译缓存键表达式，再把装饰器追加到执行链，确保 `IServiceAction` 执行前后自动处理缓存。【F:nop-biz/src/main/java/io/nop/biz/decorator/CacheActionDecoratorCollector.java†L23-L55】
- 对于 Java 方法暴露的 BizAction，收集器同样读取 `IFunctionModel` 上的注解：若存在 `@Cache`/`@CacheEvict(s)` 则分别编译表达式并追加对应装饰器，使纯 Java BizObject 也能复用缓存策略；若 `.xbiz` 中提供 `BizCacheModel` 则在 `collectDecorator(BizActionModel, …)` 支路直接读取模型属性构造装饰器。【F:nop-biz/src/main/java/io/nop/biz/decorator/CacheActionDecoratorCollector.java†L27-L55】
- `TransactionActionDecoratorCollector` 结合 `@Transactional` 注解或 `.xbiz` 中的事务模型，利用 `ITransactionTemplate` 为动作组装事务装饰器并遵循 `BizConstants` 默认的 Mutation=事务化策略。【F:nop-biz/src/main/java/io/nop/biz/decorator/TransactionActionDecoratorCollector.java†L21-L53】【F:nop-biz/src/main/java/io/nop/biz/BizConstants.java†L39-L52】
- `nop-xdefs` 中的 `xbiz.xdef` 明确 `<txn transactional="boolean" txnGroup="string" propagation="enum"/>`、`<cache cacheName="string" cacheKeyExpr="expr"/>` 与 `<cache-evict cacheName="string" cacheKeyExpr="expr"/>` 的声明方式，指导 `.xbiz` 文件为动作或 Loader 提供事务与缓存元数据。【F:nop-xdefs/src/main/resources/_vfs/nop/schema/biz/xbiz.xdef†L20-L93】
- `biz-defaults.beans.xml` 通过 `<ioc:collect-beans by-type="io.nop.biz.decorator.IActionDecoratorCollector"/>` 收集装饰器收集器，避免循环依赖并在 `BizObjectManager` 构建阶段统一注入缓存、事务等装饰器链。【F:nop-biz/src/main/resources/_vfs/nop/biz/beans/biz-defaults.beans.xml†L53-L78】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L83-L197】
- 当前仓库生成的 `.xbiz` 模板（例如 `NopAuthRole.xbiz`）仅保留 `<actions/>` 节点，并未自动写入 `<cache>`/`<txn>`，因此要启用缓存或覆写事务需额外编辑差量文件或 Java 注解补齐配置。【F:nop-auth/nop-auth-service/src/main/resources/_vfs/nop/auth/model/NopAuthRole/NopAuthRole.xbiz†L1-L5】【F:nop-auth/nop-auth-service/src/main/resources/_vfs/nop/auth/model/NopAuthRole/_NopAuthRole.xbiz†L1-L15】
- `nop-dyn` 模块的 `DynCodeGen` 实现 `IDynamicBizModelProvider`，在初始化时判断租户模式、批量生成动态模块，并把 `addOnChangeListener` 委派给 `InMemoryCodeCache`，同时注册 `ResourceTenantManager` 以暴露租户资源与缓存刷新入口。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L50-L254】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L1-L220】
- `InMemoryCodeCache.addOnChangeListener` 仅将监听器加入列表，原计划调用监听器的 `genModuleBizModels` 被整段注释，导致动态生成/删除 Biz 模型不会触发 `onBizObjChanged` 或 `onBizObjRemoved`，`BizObjectManager` 也就无法自动清除缓存，必须通过 `DynCodeGen.reloadModel()` 或直接调用 `BizObjectManager.removeCache` 手工刷新。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L108-L176】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L300-L335】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L200-L244】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L121】
- `DynCodeGen.reloadModel()` 委派 `InMemoryCodeCache.reloadModel()` 合并核心/前端资源并刷新 `mergedStore`，在非租户模式下更新 `VirtualFileSystem` 与 `ModuleManager` 后调用 `ormSessionFactory.reloadModel()`，但不会自动触达 `BizObjectManager` 缓存，因此动态模型发布后仍需手工移除对应 BizObject 缓存以触发重建。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L242-L244】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L295-L324】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L185】

## 4. 推测（Hypotheses）
- ⚠️ 尚需评估动态模型批量发布或运维接口是否会统一调用 `DynCodeGen.reloadModel()` 或 `BizObjectManager.clearCache()`，以便在 Run-less 手册中补充推荐的刷新入口。计划：搜索 `nop-dyn`、`nop-admin`、`nop-cli` 中的命令或任务入口，列出可触发全量刷新的操作清单。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L200-L244】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L205】

## 5. 规则（IF-THEN，可执行）
- IF BizAction 声明 `bizSequential=true` THEN 必须提供 `IBizHashFunction` 以选择分区执行器，确保同一业务键串行处理，否则顺序无法保证。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L79】
- IF `.xbiz` 文件缺失且需继承基础对象 THEN 必须提供派生对象的 `.xmeta`，否则 `BizObjectBuilder` 会抛出 `ERR_BIZ_MISSING_META_FILE_FOR_OBJ`。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L223-L239】
- IF Biz 模型禁用某个继承动作 THEN `BizObjectImpl.isAllowInheritAction` 会拒绝合并 Java 默认动作，需要在 `.xbiz` 中重新声明或解除禁用。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectImpl.java†L67-L81】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuildHelper.java†L25-L53】
- IF `.xbiz` 中未显式配置 `<txn transactional="false">` 且动作类型为 Mutation THEN `TransactionActionDecoratorCollector` 会以 `BizConstants.BIZ_ACTION_TYPE_MUTATION` 为默认值判定事务化，并注入 `TransactionActionDecorator`，从而保持写操作默认开启事务。【F:nop-biz/src/main/java/io/nop/biz/decorator/TransactionActionDecoratorCollector.java†L40-L52】【F:nop-biz/src/main/java/io/nop/biz/BizConstants.java†L39-L52】
- IF GraphQL 操作绑定的 `IServiceAction` 为空 THEN `BizObjectBuilder.checkOperations` 仅记录日志，因此上线前需通过测试校验操作是否具备实现，以免产生空指针或无响应。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L178-L188】
- IF 通过 `DynCodeGen.generateBizModel` 或直接覆盖动态模块文件 THEN 必须先执行 `DynCodeGen.reloadModel()` 重建动态模块的 `mergedStore` 与 ORM 模型，再针对受影响的 BizObject 调用 `BizObjectManager.removeCache`，否则由于监听器未触发，缓存仍返回旧模型。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L238-L244】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L295-L324】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L185】

## 6. 流程（Text-Sequence：编号步骤）
1. **模型定位**：`BizObjectManager` 根据 Biz 名称从 `GraphQLBizModels`/动态提供器获取模型基路径与回退策略。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L194-L240】
2. **资源解析**：在虚拟文件系统中解析 `.xbiz`、`.xmeta`，并回退至基础对象或引用 Java 反射模型补齐 GraphQL 字段定义。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L88-L133】
3. **GraphQL 合成**：合并 Loader、继承动作与 meta 字段，按需移除未公开字段，装配 MakerChecker 元数据与状态机。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L116-L170】
4. **装饰器注入**：`BizObjectBuildHelper` 根据收集到的装饰器构建服务动作和 Fetcher，形成最终的 `IServiceAction` 链。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuildHelper.java†L23-L101】
5. **缓存注册**：构建完成的 BizObject 写入租户感知缓存，可按需清除或通过动态模型监听刷新。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L205】
6. **动态刷新**：调用 `DynCodeGen.reloadModel()` 时，`InMemoryCodeCache.reloadModel()` 会合并模块核心/前端资源，刷新 `VirtualFileSystem`、`ModuleManager` 并触发 `ormSessionFactory.reloadModel()`；完成后需针对受影响的 BizObject 调用 `BizObjectManager.removeCache` 以便下一次访问重新构建模型。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L242-L244】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L295-L324】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L185】
7. **调用适配**：`BizActionService` 接收 RPC 请求，解析 BizAction 元信息，封装 `BizActionInvocation` 并在指定执行器中运行。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L92】
8. **响应构建**：`BizObjectManager.buildResponse` 根据执行结果或异常组装 `ApiResponse`，填充响应头并返回给调用方。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L214-L232】

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| Uninitialized | 调用 `BizObjectManager.init()` | TypeRegistry 未提供 | RegistryReady | 创建默认 TypeRegistry、注册缓存、执行 Schema 初始化器。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L147-L169】|
| RegistryReady | `getBizObject(name)` | 缓存未命中 | Building | 触发 `BizObjectBuilder` 解析 `.xbiz/.xmeta` 并装配 GraphQL、状态机与动作。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L88-L170】|
| Building | 构建完成 | 无 | Cached | BizObject 写入租户缓存并返回调用方。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L211】|
| Cached | 动态模型变更 | 监听器回调 | Invalidated | 调用 `removeCache` 清除指定 BizObject，等待下一次访问重新构建。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L121】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L185】|
| ServicePending | `BizActionService` 提交任务 | `isBizSequential=true` | PartitionQueued | 根据 BizHash 将任务投递到顺序执行队列，等待执行。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L79】|
| ServicePending | `BizActionService` 提交任务 | `isBizSequential=false` | ExecutorRunning | 直接提交到指定 Executor 异步执行。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L69-L78】|
| ExecutorRunning/PartitionQueued | `BizActionInvocation` 完成 | FutureHelper 完成 | Responded | 统一封装为 `ApiResponse` 返回给调用方。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvocation.java†L57-L80】|

## 8. 接口契约（I/O 表：端点、字段、约束、错误码）
| 接口/方法 | 输入 | 输出 | 约束 | 错误/异常 |
| --- | --- | --- | --- | --- |
| `IBizObjectManager.getBizObject(String)` | Biz 对象名 | `IBizObject` | 名称必须注册在静态或动态模型中 | 未找到对象抛 `ERR_BIZ_UNKNOWN_BIZ_OBJ_NAME`。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L209-L248】|
| `BizObjectManager.buildResponse` | Locale、结果对象、`IServiceContext` | `ApiResponse<?>` | 自动读取上下文错误与响应头 | 错误 Bean 排序后返回首条严重级别信息。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L214-L232】|
| `BizActionService.callActionAsync` | Biz 对象名、动作名、`ApiRequest<?>` | `CompletionStage<ApiResponse<?>>` | 需要在 Bean 容器中配置执行器与 BizObjectManager | 执行器查找或业务异常通过 Future 异常返回。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L83】|
| `BizActionInvocation.proceedAsync` | 无 | `CompletionStage<ApiResponse<?>>` | 若缺少 `ServiceContext` 将新建并注入请求 | 异常通过 `FutureHelper.reject` 返回。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvocation.java†L57-L80】|
| `BizActionInvoker.invokeActionSync` | Biz 对象名、动作、请求、Selection、上下文 | 动作返回值 | 非 Query 动作默认包裹事务执行 | 事务/执行异常会冒泡给调用方；需手动转 `ApiResponse`。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L34-L53】|
| `BizActionInvoker.invokeGraphQLAsync` | Biz 对象名、动作、`ApiRequest<?>`、上下文 | `CompletionStage<ApiResponse<?>>` | 通过 GraphQL 引擎创建 RPC 上下文 | GraphQL 校验失败返回标准错误响应。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L86-L101】|

## 9. 用例（Few-shot：Given-When-Then）
- 正向：Given `BizActionModel` 设置 `bizSequential=true` 且 `BizActionService` 注入 `IPartitionedExecutor`，When 多个请求携带相同 BizKey 并发调用，Then 服务根据 hash 选定同一分区顺序执行，避免并发写冲突。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L79】
- 反向：Given 派生对象未提供 `.xmeta` 文件，When `BizObjectBuilder` 回退基础对象 `.xbiz` 时，Then 抛出 `ERR_BIZ_MISSING_META_FILE_FOR_OBJ` 提示必须补齐 meta 才能继承。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L223-L239】
- 边界：Given Biz 模型标记 `not-pub`，When `BizObjectBuilder` 合成 GraphQL 对象时，Then 清空字段并返回 `null` 以避免对外暴露，实现内部专用 BizObject。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L139-L173】
- 正向（缓存模型）：Given `.xbiz` 在动作下声明 `<cache cacheName="biz-demo" cacheKeyExpr="ctx.userId">`，When `BizObjectBuildHelper` 收敛模型并交给 `CacheActionDecoratorCollector`，Then 收集器会编译 `cacheKeyExpr` 并追加 `CacheActionDecorator`，使相同 key 的请求命中缓存。【F:nop-xdefs/src/main/resources/_vfs/nop/schema/biz/xbiz.xdef†L20-L52】【F:nop-biz/src/main/java/io/nop/biz/decorator/CacheActionDecoratorCollector.java†L30-L55】
- 反向（取消事务）：Given `.xbiz` 的 `<txn transactional="false">` 明确禁用事务，When `TransactionActionDecoratorCollector` 解析 BizActionModel，Then 因 `txnModel.getTransactional()` 返回 false 而跳过事务装饰器，使读取型 Mutation 可以在无事务的线程池中执行。【F:nop-xdefs/src/main/resources/_vfs/nop/schema/biz/xbiz.xdef†L35-L48】【F:nop-biz/src/main/java/io/nop/biz/decorator/TransactionActionDecoratorCollector.java†L40-L52】
- 故障：Given BizAction 被调用但未在 `.xbiz` 或 Java 模型中提供实现，When `BizObjectBuilder.checkOperations` 检测到 `serviceAction=null`，Then 仅记录日志，后续调用会触发 `ERR_BIZ_OBJECT_NOT_SUPPORT_ACTION`，提示需要补齐实现。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L178-L188】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectImpl.java†L211-L217】
- 集成：Given 工作流节点需跳过 GraphQL 层，When 调用 `BizActionInvoker.invokeActionSync` 执行 Mutation，Then 内部自动开启事务模板并返回原始结果，供工作流处理后续逻辑。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L34-L53】
- 反向（模板未配缓存）：Given 代码生成产物的 `.xbiz` 只有 `<actions/>` 占位且缺少 `<cache>` 配置，When BizObject 直接使用该模型运行，Then `CacheActionDecoratorCollector` 无法从模型中收集缓存信息，除非后续在差量或 Java 注解中补齐配置。【F:nop-auth/nop-auth-service/src/main/resources/_vfs/nop/auth/model/NopAuthRole/NopAuthRole.xbiz†L1-L5】【F:nop-biz/src/main/java/io/nop/biz/decorator/CacheActionDecoratorCollector.java†L45-L55】
- 反向（监听器未触发）：Given 动态 Biz 模型通过 CLI 或管理界面执行 `generateBizModel` 重建 `.xbiz`，When 未额外调用 `DynCodeGen.reloadModel()`，Then 由于 `changeListeners` 未被触发，`BizObjectManager` 缓存仍返回旧模型，需要手工清除缓存或执行 `reloadModel()` 才能生效。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L108-L176】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L200-L244】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L185】
- 正向（手工刷新动态模型）：Given 通过 `DynCodeGen.generateBizModel` 重建 `.xbiz`，When 随后执行 `DynCodeGen.reloadModel()` 并对受影响的 BizObject 调用 `BizObjectManager.removeCache`，Then `InMemoryCodeCache` 会刷新虚拟文件系统与动态模块映射，而缓存清除确保下一次访问重新构建最新模型。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L238-L244】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L295-L324】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L185】

## 10. 证据矩阵（结论↔证据↔置信度↔Run-less验证）
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| BizObjectManager 以租户缓存聚合 BizObject 并提供 GraphQL Schema Loader 能力 | `BizObjectManager` 构造缓存、实现 `IGraphQLSchemaLoader`、聚合 GraphQL 文档。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L74-L390】 | 高 | 抽查 Demo 启动日志确认 `biz-object-cache` 注册与 GraphQL 文档导出。 |
| BizObjectBuilder 支持 `.xbiz`/`.xmeta` 回退并装配状态机与装饰器 | 构建流程、回退逻辑、状态机初始化、装饰器注入实现。【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectBuilder.java†L88-L332】 | 高 | 记录一组 `_gen` Biz 模型及 `.xmeta` 文件路径，验证回退规则。 |
| BizActionService 基于元数据选择执行器并封装 `BizActionInvocation` | 异步执行逻辑与顺序控制实现。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionService.java†L51-L95】 | 高 | 枚举典型 BizAction 的 `executor` 配置，确认顺序执行与默认线程池名称。 |
| BizActionInvoker 为非 GraphQL 场景提供事务化直调能力 | 同步/异步直调与 GraphQL 包装实现。【F:nop-biz/src/main/java/io/nop/biz/service/BizActionInvoker.java†L34-L101】 | 中 | 查阅工作流/任务模块调用示例，验证直调接口在运行期的使用方式。 |
| 缓存/事务装饰器通过 `IActionDecoratorCollector` 自动接入执行链 | `CacheActionDecoratorCollector`、`TransactionActionDecoratorCollector` 与 IoC 收集配置。【F:nop-biz/src/main/java/io/nop/biz/decorator/CacheActionDecoratorCollector.java†L23-L55】【F:nop-biz/src/main/java/io/nop/biz/decorator/TransactionActionDecoratorCollector.java†L21-L53】【F:nop-biz/src/main/resources/_vfs/nop/biz/beans/biz-defaults.beans.xml†L50-L70】 | 高 | 继续枚举是否存在审计/监控类收集器并整理优先级列表。 |
| `.xbiz` Schema 为动作暴露事务与缓存节点 | `xbiz.xdef` 中 `<txn>`、`<cache>`、`<cache-evict>` 的定义以及 Loader 重用缓存模型的说明。【F:nop-xdefs/src/main/resources/_vfs/nop/schema/biz/xbiz.xdef†L20-L93】 | 高 | 在 Demo 或 `_gen` 输出中定位实际 `.xbiz` 样例，验证字段命名与表达式写法。 |
| `DynCodeGen` 提供动态 Biz 模型并驱动租户代码缓存 | `DynCodeGen` 初始化、租户注册与 `InMemoryCodeCache` 委派实现。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L50-L209】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L1-L120】 | 中 | 追踪变更通知触发点或记录需要手动调用 `reloadModel()` 的场景。 |
| 生成产物默认不写入缓存/事务节点 | `NopAuthRole.xbiz` 与 `_NopAuthRole.xbiz` 仅包含 `<actions/>` 与模板扩展占位，没有 `<cache>`/`<txn>` 配置，表明缓存与事务需要后续手工补齐。【F:nop-auth/nop-auth-service/src/main/resources/_vfs/nop/auth/model/NopAuthRole/NopAuthRole.xbiz†L1-L5】【F:nop-auth/nop-auth-service/src/main/resources/_vfs/nop/auth/model/NopAuthRole/_NopAuthRole.xbiz†L1-L15】 | 中 | 搜索外部示例或差量定制文件，确认在真实项目中如何追加 `<cache>`/`<txn>` 节点。 |
| 动态 Biz 模型更新不会自动触发监听器刷新 | `InMemoryCodeCache` 仅保存监听器且通知逻辑被注释，`reloadModel()` 重建 `dynBizModels` 并更新模块，`BizObjectManager` 监听器依赖 `onBizObjChanged/Removed` 才会清除缓存。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L108-L176】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L300-L335】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L200-L244】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L90-L121】 | 中 | 梳理 CLI/管理接口或配置任务中可触发 `reloadModel()` 的入口，并补充运行期操作指南。 |
| 动态刷新需串联 `reloadModel` 与缓存清除 | `DynCodeGen.reloadModel()` 更新虚拟文件系统、模块映射并调用 `ormSessionFactory.reloadModel()`，但 `BizObjectManager` 缓存需额外 `removeCache` 才会失效。【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/DynCodeGen.java†L242-L244】【F:nop-dyn/nop-dyn-service/src/main/java/io/nop/dyn/service/codegen/InMemoryCodeCache.java†L295-L324】【F:nop-biz/src/main/java/io/nop/biz/impl/BizObjectManager.java†L179-L185】 | 中 | 搜索 CLI/管理接口是否封装了 `reloadModel + removeCache` 的组合操作，并记录推荐执行步骤。 |

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.6 | 2024-06-16 | 补充动态 Biz 模型手工刷新流程，说明 `reloadModel` 合并顺序与缓存清理步骤，更新规则、流程、用例与证据矩阵。 |
| v0.5 | 2024-06-16 | 证实动态 Biz 模型监听器未被触发、补充手工刷新步骤，并更新用例与证据矩阵。 |
| v0.4 | 2024-06-16 | 明确 Java 注解与 `.xbiz` 双通路的装饰器注入机制，并确认代码生成产物默认不包含缓存/事务节点。 |
| v0.3 | 2024-06-13 | 记录 `.xbiz` 缓存/事务节点与装饰器联动细节，新增默认事务规则与缓存用例说明。 |
| v0.2 | 2024-06-11 | 补充缓存/事务装饰器收集器与 `DynCodeGen` 动态模型提供器证据，标记动态监听触发待确认点。 |
| v0.1 | 2024-06-10 | 首次梳理 `nop-biz` BizObject 管理、BizAction 调用链、装饰器机制与配置项。 |

## 12. 检索标签（Tags）
`#nop-biz` `#BizObject` `#GraphQL` `#BizAction` `#RunLess`
