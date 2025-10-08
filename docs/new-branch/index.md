# Nop Platform 2.0 研究索引

## TL;DR（概览摘要）
- Nop Platform 2.0 是基于可逆计算原理构建的低代码/无代码后端平台，强调模型驱动与领域语言工作台能力。
- 后端项目 `nop-entropy` 由多个独立模块组成，涵盖 ORM、GraphQL、批处理、规则引擎等核心能力，可与 Quarkus、Spring 集成。
- 研究策略采用只读分析与静态推理，所有结论需在证据矩阵标注来源，并准备可延期的验证计划。
- 文档集合使用“主文档 + 模块文档”结构，以中文撰写并提供内链，支持逐步深化学习。
- 代码路线图、调试清单与学习者行动清单会持续更新，确保在无法运行代码的前提下也能掌握系统脉络。
- `_delta` 差量目录可通过 `ModuleManager` 聚合并由 `DslNodeLoader` 合并，支持 MyBatis/Spring 等运行期定制，无需修改基线文件。【F:docs/dev-guide/spring/spring-delta.md†L25-L140】【F:nop-core/src/main/java/io/nop/core/module/ModuleManager.java†L61-L184】【F:nop-xlang/src/main/java/io/nop/xlang/xdsl/DslNodeLoader.java†L29-L85】

## 文档目录
- [平台总体认知（modules/platform-overview.md）](modules/platform-overview.md)
- [模型生成链路（modules/model-generation.md）](modules/model-generation.md)
- [差量管理（modules/delta-management.md）](modules/delta-management.md)
- [ORM 引擎工作原理（modules/nop-orm.md）](modules/nop-orm.md)
- [动态配置总览（modules/nop-config.md）](modules/nop-config.md)
- [业务动作与 BizObject（modules/nop-biz.md）](modules/nop-biz.md)
- [IoC 容器机制（modules/nop-ioc.md）](modules/nop-ioc.md)
- [系统基础 DAO 组件（modules/nop-sys-dao.md）](modules/nop-sys-dao.md)
- [Web 页面与动态资源机制（modules/nop-web.md）](modules/nop-web.md)
- （待补充）GraphQL、批处理、规则等专题文档。

## 最近更新
| 版本 | 日期 | 变更摘要 |
| --- | --- | --- |
| v0.94 | 2024-06-17 | 更新 `nop-sys-dao` 文档，强调 `runLocal` 在独立 Session/REQUIRES_NEW 事务中刷新序列并新增清缓存规则、缓存占位边界用例，提示修改数据库序列后需失效 JVM 缓存且关注前移造成的独立事务效应；路线图同步纳入序列独立事务与缓存清理的取证任务。 |
| v0.93 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `defaultCache` 60 秒 TTL 与 `removeCache`/`clearCache` 缓存失效策略，新增“缓存占位导致配置延迟生效”用例与证据矩阵条目，并在路线图加入序列缓存清理调试提醒。 |
| v0.92 | 2024-06-17 | 更新 `nop-sys-dao` 文档，梳理 `SysSequenceGenerator` 的缓存批量更新、`syncFromDb` 事务流程与 `useDefault` 回退策略，提醒缺失序列会返回默认或随机 UUID 并需补充运行证据；路线图同步新增序列取证动作。 |
| v0.91 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `SysEventHelper` 序列化 `ApiRequest` 元数据与普通对象随机分区的事实/规则/用例，提示需要收集事件行对比日志，并在路线图强调验证 `event_headers` 与 `partition_index`。 |
| v0.90 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `ResourceLock` 默认等待/租期的覆盖路径与 `getLock` 默认窗口说明，并在路线图提示统一调整 Bean 属性以支撑长事务互斥。 |
| v0.89 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充锁记录写入 `appId`/`holderAdder` 的来源与 `IServerAddrFinder` 注入策略，并在路线图新增多网卡/容器场景的锁定位提示。 |
| v0.88 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `forceUnlock` 无版本校验的风险及兜底使用规则，新增相关规则/用例/证据矩阵并在路线图提醒记录 `forceUnlock` 操作日志。 |
| v0.87 | 2024-06-17 | 更新 `nop-sys-dao` 文档，记录 `ResourceLock` 默认等待/租期与 `tryLockWithLease` 超时返回 `null` 的行为，新增相关规则/用例/证据并在路线图提示关注等待耗尽日志。 |
| v0.86 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `isHoldingLock` 校验与 `releaseLock` 删除流程，新增规则/用例/证据矩阵并提醒释放前刷新锁状态；路线图同步强化锁持有调试提示。 |
| v0.85 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充锁续约需调用 `tryResetLease` 并刷新版本的事实/规则/用例，提醒续约失败需重新竞争锁且依赖数据库估算时钟；路线图同步加入续约调试提示。 |
| v0.84 | 2024-06-17 | 更新 `nop-sys-dao` 文档，明确 `cleanup()` 仍使用本地系统时钟推导删除阈值、补充漂移风险规则/用例，并在路线图强化命名服务清理窗口与时钟对齐的调试提示。 |
| v0.83 | 2024-06-17 | 更新 `nop-sys-dao` 文档，提示服务实例 metadata/标签列长度限制与默认 `ephemeral=true` 风险，新增相关规则、反向用例与证据矩阵条目，路线图同步加入 metadata 体积调试动作。 |
| v0.82 | 2024-06-17 | 更新 `nop-sys-dao` 文档，记录 `getInstances` 仍依赖本地系统时钟过滤服务实例、提示校准节点时间并新增漂移反向用例/证据矩阵。 |
| v0.81 | 2024-06-17 | 更新 `nop-sys-dao` 文档，说明命名服务使用数据库估算时钟刷新 `updateTime` 并提醒校验时间漂移对心跳窗口的影响，路线图同步加入时间源自检提示。 |
| v0.80 | 2024-06-17 | 更新 `nop-sys-dao` 文档，注明 `autoUpdateInterval` 会额外增加 1 秒安全裕度，新增心跳窗口配置规则/用例/证据矩阵，并提醒同步调整续约与清理周期。 |
| v0.79 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充服务实例字段/metadata 序列化、命名服务接口契约与 `cleanupInterval` 配置注意事项，并在路线图新增命名服务调试行动。 |
| v0.78 | 2024-06-17 | 更新 `nop-sys-dao` 文档，记录 MakerChecker 默认关闭、`ApiResponse.buildSuccess` 返回审批编号与同步/异步 try 一致性，路线图同步强化审批风险提示。 |
| v0.77 | 2024-06-17 | 更新 `nop-sys-dao` 文档，梳理 MakerChecker 审批链条与 GraphQL 开关、`@BizMakerChecker` 注解的协作，新增审批入库用例与路线图调试提醒。 |
| v0.76 | 2024-06-17 | 更新 `nop-sys-dao` 文档，强调 `nop.orm.audit.enabled` 开关、`no-audit` 列排除策略与审计失败用例，并在路线图补充开关校验的调试提示。 |
| v0.75 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充审计日志开关、`audit`/`audit-save` 标签差异与 `NopSysChangeLog` 字段说明，并在路线图加入审计调试提示。 |
| v0.74 | 2024-06-17 | 更新 `nop-sys-dao` 文档，记录 `OrmEntityChangeLogInterceptor` 的审计日志流程并在路线图补充变更追踪调试点。 |
| v0.73 | 2024-06-17 | 更新 `nop-sys-dao` 文档，细化 `SysDaoNamingService` 心跳上限与临时实例清理规则，并在路线图补充服务注册的调试提醒。 |
| v0.72 | 2024-06-17 | 更新 `nop-sys-dao` 文档，标注 `restartElection` 通过新会话读取最新 leader 行并新增保留 `runInNewSession` 的规则，提醒强制重选需注意缓存污染。 |
| v0.71 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `SysDaoLeaderElector` 的循环上限、租约续期与 `restartElection` 重选机制，并在路线图加入选主日志取证建议。 |
| v0.70 | 2024-06-17 | 更新 `nop-sys-dao` 文档，强调异步 Ack 失败仅留下 `nop.message.consumer-error` 日志且不会写入 `ack-` 主题，并在路线图新增失败应答的 Run-less 计划。 |
| v0.69 | 2024-06-17 | 更新 `nop-sys-dao` 文档，说明 `CompletionStage` 异步应答如何写入 `ack-` 主题且仅在本地分发，并在路线图提示验证异步 Ack 日志。 |
| v0.68 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充 `LocalMessageService` 的广播分类与 `ack-` 主题桥接机制，并在路线图强调系统事件响应需要订阅 `ack-` 主题。 |
| v0.67 | 2024-06-17 | 更新 `nop-sys-dao` 文档，说明广播/非广播查询窗口与 `lastBroadcastEvent` 指针、乐观锁回流流程，并在路线图加入相关调试提醒。 |
| v0.66 | 2024-06-17 | 更新 `nop-sys-dao` 文档，补充消息轮询默认执行器/窗口参数与广播补偿窗口，用以指导线程池和 `startGap` 的配置覆盖，并同步刷新路线图热点。 |
| v0.65 | 2024-06-17 | 更新 `nop-sys-dao` 文档以记录默认 `RetryPolicy` 的 2 次重试上限、规则与反向用例，提醒需自定义策略方可突破限制，并同步刷新路线图热点。 |
| v0.64 | 2024-06-17 | 补充 `nop-sys-dao` 文档的 Run-less 消息延迟校准流程与对照用例，指导在未覆盖 `minProcessDelay` 时如何手算调度时间，并同步更新路线图调试提示。 |
| v0.63 | 2024-06-17 | 更新 `nop-sys-dao` 文档，确认 `minProcessDelay` 默认 10 秒及差量覆盖方式，新增规则、用例与证据矩阵条目，并在路线图强化系统消息热点。 |
| v0.62 | 2024-06-17 | 更新 `nop-orm` 文档与路线图，补充 `OrmSessionImpl.attach/detach` 的跨 session 守护逻辑及级联附加用例，强调重新绑定前需显式分离实体。 |
| v0.61 | 2024-06-17 | 更新 `nop-orm` 文档，梳理自定义持久化驱动的 Bean 前缀与 prototype 要求，提醒未按原型注册会抛出 `ERR_ORM_BEAN_NOT_PROTOTYPE_SCOPE`，并同步刷新路线图热点与用例。 |
| v0.60 | 2024-06-17 | 更新 `nop-orm` 文档，说明 SessionFactory `name` 如何影响缓存前缀与全局清理命名，并在路线图新增相关调试提醒。 |
| v0.59 | 2024-06-17 | 更新 `nop-orm` 文档，梳理 `IOrmModelHolder` 的默认/租户缓存行为，提醒默认实现无法按租户刷新并补充路线图调试指引。 |
| v0.58 | 2024-06-17 | 更新 `nop-orm` 文档与路线图，指出 `OrmSessionFactoryBean.init()` 未转发 `queryExecutors`，提醒自定义查询空间默认回退 JDBC 并跟踪官方修复。 |
| v0.57 | 2024-06-17 | 更新 `nop-orm` 文档，解析 `OrmSessionImpl.flush` 与 `CascadeFlusher` 的脏实体扫描、级联删除与防死循环机制，并同步调整路线图热点与证据矩阵。 |
| v0.56 | 2024-06-17 | 更新 `nop-orm` 文档，梳理查询空间到 `IQueryExecutor` 的路由路径、默认 `JdbcQueryExecutor` 的编译/监听/行映射流程，并同步刷新路线图与证据矩阵。 |
| v0.55 | 2024-06-17 | 统计 `_dump/merged-app.beans.xml` 的 `LOC` 注释仅覆盖默认模块，提示需额外收集含差量 Bean 的调试快照并同步更新路线图。 |
| v0.54 | 2024-06-16 | 更新 `nop-ioc` 文档，补充 `concurrent-start` 的任务图执行与取消流程，并在路线图提示并发启动的日志与等待策略。 |
| v0.53 | 2024-06-16 | 更新 `nop-orm` 文档，记录 SQL 库代理的上下文初始化、分页参数处理与返回值转换细节，并同步扩展规则/用例/证据矩阵。 |
| v0.52 | 2024-06-16 | 更新 `nop-orm` 文档，细化 `SqlItemModel` 缓存键、行映射、代理与字典加载机制，并同步刷新路线图与证据矩阵。 |
| v0.51 | 2024-06-16 | 更新 `nop-orm` 文档，梳理 `SqlLibManager` 的组件注册、权限校验与代理校验流程，并补充 SQL 库用例与证据矩阵。 |
| v0.50 | 2024-06-16 | 补充 `nop-orm` 的全局缓存清理流程，记录 `GlobalCacheRegistry.clear` 的输入/效果与证据矩阵更新，强调按租户清理空实现。 |
| v0.49 | 2024-06-16 | 更新 `nop-orm` 文档，说明 `GlobalCacheRegistry.clearForTenant` 对 ORM 缓存无效并给出统一清理替代路径。 |
| v0.48 | 2024-06-16 | 更新 `nop-orm` 文档，记录 `registerGlobalCache` 注册与全局缓存统一清理路径，并同步新增规则/用例/证据矩阵。 |
| v0.47 | 2024-06-16 | 更新 `nop-biz` 文档，梳理 `reloadModel` 合并顺序与手工清缓存流程，并刷新路线图行动项。 |
| v0.46 | 2024-06-16 | 更新 `nop-orm` 文档以记录 `TenantCachedQueryPlan` 的租户查询计划缓存逻辑，并补充相关规则、用例与证据矩阵。 |
| v0.45 | 2024-06-16 | 更新 `nop-orm` 文档的 `SessionFactoryConfig` 依赖映射表，补充默认 Bean 来源、可替换策略与新规则/证据矩阵。 |
| v0.44 | 2024-06-16 | 新增 `nop-sys-dao` 锁过期缺陷的 Run-less 复现时间轴，补充规避建议与证据矩阵。 |
| v0.43 | 2024-06-16 | 记录 `.xbiz` 模板默认不含缓存/事务节点及装饰器收集器的注解通路，提示需额外配置。 |
| v0.42 | 2024-06-16 | 明确 `nop-web` 在缺少权限映射时保留 `xui:permissions`，补充动态资源生成的证据矩阵更新。 |
| v0.41 | 2024-06-16 | 新增 `nop-web` 模块文档，梳理页面加载、动态 JS/CSS 生成与编辑 BizModel 的配置、流程和规则。 |
| v0.40 | 2024-06-16 | 记录 `useMetrics` 默认启用及 Micrometer 指标注入路径，新增禁用指标的规则与调试用例。 |
| v0.39 | 2024-06-15 | 标注 `RouterConfigSource` 在仅启用应用路由时会抛出空指针，路线图与配置文档新增风险提示与复现计划。 |
| v0.38 | 2024-06-15 | 记录 `nop-sys-dao` 数据库锁判定缺陷与 `minProcessDelay` 覆盖需求，补充反向用例与证据矩阵条目。 |
| v0.37 | 2024-06-14 | 汇总 `nop-sys-dao` 的系统序列、字典、锁、选主与消息队列实现，新增模块文档与风险假设。 |
| v0.36 | 2024-06-14 | 记录 `OrmTimestampHelper` 自动审计字段流程与 `nop.orm.sys-user-name` 回退策略，刷新 `nop-orm` 模块的规则/用例/证据矩阵。 |
| v0.35 | 2024-06-14 | 补充 `nop-orm` 组合主键封装与标签检索行为，扩充规则/用例/证据矩阵并提示标签唯一性风险。 |
| v0.34 | 2024-06-14 | 新增 `nop-ioc` 容器机制专题，梳理装载流程、配置入口与并发启动规则并更新路线图。 |
| v0.34 | 2024-06-16 | 深入 `EntityPersisterImpl` 的默认值、租户校验与全局缓存驱逐流程，更新 `nop-orm` 文档的事实、规则、用例与证据矩阵。 |
| v0.33 | 2024-06-14 | 给出 `_delta` Bean 覆写 `dynamicEntityNames` 的模板与用例，文档同步记录差量注入步骤及证据矩阵条目。 |
| v0.32 | 2024-06-14 | 盘点核心、Spring、Quarkus 默认装配均未注入 `dynamicEntityNames`，并将差量注入模板纳入下一步行动。 |
| v0.32 | 2024-06-16 | 证实动态 Biz 模型不会自动触发缓存刷新，新增手工 `reloadModel()` 流程与路线图调试/行动项。 |
| v0.31 | 2024-06-14 | 明确 ORM stateless/tenant Session 的缓存差异及缺失租户号的异常路径，路线图新增租户上下文调试动作。 |
| v0.30 | 2024-06-13 | 补充 `.xbiz` `cache`/`txn` 节点定义与装饰器行为，说明缓存键编译与 Mutation 默认事务策略的证据。 |
| v0.29 | 2024-06-13 | 说明 `nop.orm.session-check-context` 的配置入口与关闭后风险，补充 `ContextProvider.thenOnContext` 运行机制及文档交叉引用。 |
| v0.28 | 2024-06-13 | 梳理 `OrmSessionRegistry` 与 `runInSession(Async)` 的上下文复用、日志提示与异步回收流程，新增调试/用例指引。 |
| v0.27 | 2024-06-12 | 强化 `nop-orm` Session 上下文守护说明，记录 `nop.orm.session-check-context` 默认值与跨线程异常边界。 |
| v0.26 | 2024-06-12 | 增补配置刷新日志链路速查，串联 `nop.config.vars`/`nop.config.update-var-value-fail` 到缓存刷新动作的证据。 |
| v0.25 | 2024-06-11 | 梳理配置热更新链路与 `nop.config.vars` 调试日志，补强 `ConfigChangeApplier` 串行机制与异常排查指引。 |
| v0.24 | 2024-06-11 | 记录 `OrmSessionFactoryBean.refreshConfig` 的缓存刷新机制与命名策略，补充配置热更新影响范围。 |
| v0.24 | 2024-06-17 | 收集 `_dump/merged-app.beans.xml` 调试快照，标注 `LOC` 注释映射并更新 `nop-ioc` 文档与路线图的差量验证指引。 |
| v0.23 | 2024-06-11 | 补充 `nop-biz` 缓存/事务装饰器与 `DynCodeGen` 动态模型提供器证据，标注监听触发待验证。 |
| v0.22 | 2024-06-10 | 梳理 `nop-biz` BizObject 构建流程、BizAction 执行链与装饰器机制，补齐业务服务入口研究。 |
| v0.21 | 2024-06-10 | 补充 Router JSON 字段结构、首匹配策略与 `enabled` 标志行为，完善灰度停用边界用例。 |
| v0.20 | 2024-06-09 | 解析 RouterConfigSource 覆盖顺序并确认仓库缺少 `dynamicEntityNames` 注入样例。 |
| v0.19 | 2024-06-09 | 明确 `nop.config.additional-location` 覆盖顺序与 `DefaultConfigProvider` 热更新引用机制。 |
| v0.18 | 2024-06-08 | 分析 `ConfigRouterProcessor` JSON 路由机制，补充灰度覆盖规则与前缀命名说明。 |
| v0.17 | 2024-06-07 | 补充配置来源→覆盖级别速查表与附加文件/Router 叠加示例，明确 `CompositeConfigSource` 优先级规则。 |
| v0.16 | 2024-06-07 | 新增 `nop-config` 配置总览，整理 ConfigStarter 优先级链与热更新机制支撑动态实体注入研究。 |
| v0.15 | 2024-06-06 | 梳理 `SessionFactoryConfig` IoC 依赖表并补充动态实体反证，用于定位运行期注入缺口。 |
| v0.14 | 2024-06-05 | 汇总 Spring 合并 Bean 的 ORM 依赖表，确认默认未注入 `dynamicEntityNames` 并更新路线图行动项。 |
| v0.13 | 2024-06-04 | 记录 `nop-orm` 动态实体配置样例，确认 `dynamicEntityNames` 默认值并收集模板触发证据。 |
| v0.12 | 2024-06-03 | 补充 `nop-orm` IoC 装配链路与动态实体模板证据，明确 SessionFactory 依赖来源。 |
| v0.11 | 2024-06-02 | 新增“ORM 引擎工作原理”模块，梳理 `SessionFactoryImpl`/`OrmTemplateImpl` 等核心组件与实体状态机。 |
| v0.10 | 2024-06-01 | 记录 `NopAuthGroup` `_gen` selections 与 `_ids` 标签示例，扩展 GraphQL 字段映射样本库。 |
| v0.9 | 2024-05-31 | 归档 `_NopAuthDept` 视图的 `@listSelection/@formSelection` 字段清单，补齐页面→GraphQL 字段映射说明。 |
| v0.8 | 2024-05-30 | 抽查 `NopAuthDept` 的 `managerId/groupMappings` 字段，串联 ORM、XMeta 与 `_gen` 视图的 GraphQL 映射证据。 |
| v0.7 | 2024-05-29 | 增补差量层优先级速查表与租户 `_tenant` 命名证据，完善 Run-less 差量研究基线。 |
| v0.6 | 2024-05-28 | 扩展差量管理文档，记录租户覆盖顺序与 `delta-layer-ids` 配置要点。 |
| v0.5 | 2024-05-27 | 新增“差量管理”模块，梳理 `_delta` 目录合并流程与 MyBatis/Spring 差量用例。 |
| v0.4 | 2024-05-26 | 扩展 `NopAuthDept` 树模型证据链，整理 `exec-maven-plugin` 四阶段执行与调试线索。 |
| v0.3 | 2024-05-25 | 抽查 `nop-auth` 生成物，补齐 Excel→ORM→GraphQL 映射证据与 `_delta` 差量用例。 |
| v0.2 | 2024-05-24 | 新增“模型生成链路”模块，补齐生成流程事实与证据矩阵。 |
| v0.1 | 2024-05-23 | 建立索引框架与平台总体认知模块骨架，标注首批问题清单。 |

## 术语表入口
- 术语初稿见《平台总体认知》文档的“术语与边界”章节，后续将提炼为专门术语表。

## 参考资料优先级
1. 仓库根目录 `README.md`、`README.en.md`
2. `docs/index.md`、`docs/nop-intro.md`
3. 各模块子仓库中的 `README` 与配置示例
4. Issue/PR/提交日志中暴露的演进线索（待整理）

## 研究方法提醒
- 固定输出节奏：每轮交付包含研究更新、当前状态、下一步任务、学习摘要、代码路线图、证据矩阵。
- 若资料不足，优先补齐证据来源与提问清单，避免空泛结论。

## 链接规范
- 统一使用相对路径指向仓库内文档，便于离线浏览。
- 表格内容若引用外部资源需明确写明获取方式与必要上下文。
