# Web 页面与动态资源机制（nop-web）

## 1. 摘要（TL;DR）
1. `PageProvider` 同时注册 XView/XPage 组件加载器，支持解析 `page.yaml/json/json5` 并在加载时清理空值、归一化导入与修复 amis 结构。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L100-L155】【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L283-L305】【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L70-L130】
2. 页面渲染流程可选择是否解析配置/i18n，占用 `PageRenderOptions` 控制线程数、后处理与权限转换，渲染结果写入 JSON 文件或返回内存结构。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L182-L242】
3. `DynamicJsLoader`/`DynamicCssLoader` 根据配置及 `.xjs`/`.xcss` 是否存在选择直接返回静态文件或动态生成内容，并在生成后回写 dump/物理文件供调试。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L73-L137】【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L58-L91】
4. `DynamicWebFileProvider` 在启用 `nop.web.auto-load-dynamic-file` 时扫描模块资源自动加载全部 `.xjs/.xcss`，并提供带历史回滚的读取/写入接口。【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L33-L73】【F:nop-web/src/main/java/io/nop/web/page/ResourceWithHistoryProvider.java†L35-L46】
5. `WebDynamicFileProcessor` 解析动态注释块与 mock 标记，借助 XLang 模板将 `<@generate/>` 片段展开为最终 JS/CSS 代码，缺少结束标记时抛出专用异常。【F:nop-web/src/main/java/io/nop/web/page/WebDynamicFileProcessor.java†L29-L95】
6. 页面/脚本编辑 BizModel 在配置禁用编辑时直接抛出 `ERR_WEB_PAGE_NOT_ALLOW_EDIT`，同时在保存前校验扩展名、去除随机 `id` 并通过历史锁保障并发。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L34-L73】【F:nop-web/src/main/java/io/nop/web/biz/SystemJsProviderBizModel.java†L33-L71】【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L49-L68】【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L203-L228】
7. 权限转换由 `PageProvider` 在渲染或读取阶段执行，将 `xui:permissions` 映射到角色集合并与原有 `xui:roles` 合并；若未注入 `IRolePermissionMapping` 则直接返回原数据并保留 `xui:permissions` 供前端自行处理。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L233-L268】
8. `WebConfigs` 暴露自动加载、动态 JS/CSS 开关与页面校验线程数，为部署环境调节资源加载策略提供入口。【F:nop-web/src/main/java/io/nop/web/WebConfigs.java†L16-L32】

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| XPage/XView | XPage/XView Model | 通过 `ComponentModelConfig` 注册的页面模型与视图模型，分别映射 `page.*` 与 `view.xml` 文件，解析时支持 XLang/JSON Delta。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L105-L155】|
| Dynamic JS/CSS | Dynamic JS/CSS | `.xjs`/`.xcss` 源文件经 `DynamicJsLoader`/`DynamicCssLoader` 生成最终资源，若关闭配置或缺少源文件则退回静态 `.js/.css`。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L73-L137】【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L58-L91】|
| Resource History | Resource History | `ResourceWithHistoryProvider` 基于资源锁与 `SimpleBakResourceHistory` 管理版本与回滚，仅作用于虚拟文件系统资源，不负责权限控制。【F:nop-web/src/main/java/io/nop/web/page/ResourceWithHistoryProvider.java†L23-L46】|
| Permission Transformer | Permission Transformer | `PageProvider` 在页面 JSON 上将 `xui:permissions` 转换为 `xui:roles` 合集，仅在存在 `IRolePermissionMapping` 时执行，不校验权限合法性。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L233-L268】|

## 3. 事实（Facts）
- `PageProvider` 初始化时注册 XView/XPage 加载器并使用 `DslModelParser`/`XJsonLoader` 解析资源，注册过程记录依赖方便调试。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L100-L155】【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L288-L305】
- 页面校验与渲染可按配置并行执行，线程数由 `CFG_WEB_PAGE_VALIDATION_THREAD_COUNT` 控制，遍历所有启用模块下 `pages/*/*.page.yaml` 文件。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L163-L210】
- 渲染选项允许关闭值解析或 i18n 解析，若关闭 `resolveI18n` 则复制编译器并移除 `i18n` 解析器，避免在导出静态资源时替换翻译键。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L220-L239】
- 权限转换通过 `rolePermissionMapping.getRolesWithPermission` 计算角色集合，并用 `CollectionHelper.mergeSet` 与原有角色合并，最终写回 `xui:roles`；当 `rolePermissionMapping` 为空时不会执行转换逻辑，`xui:permissions` 字段保持原状。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L233-L268】
- `DynamicJsLoader` 根据文件扩展名区分 `mjs`/`js`，对 `.xjs` 源调用 `WebDynamicFileProcessor` 生成文本并可选交由 `systemJsTransformer` 转换为 System.register 格式。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L73-L170】
- `DynamicCssLoader` 若 `CFG_WEB_USE_DYNAMIC_CSS` 关闭或缺少 `.xcss` 源，将回退到原 `.css` 文件，否则读取 `.xcss` 并生成内容后落盘至 dump 及物理文件。【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L58-L90】
- `DynamicWebFileProvider` 会在自动加载时扫描模块 `/pages` 与 `/js`（或 `/css`）目录，将 `.xjs/.xcss` 转换结果预热到组件模型缓存。【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L33-L55】
- `ResourceWithHistoryProvider` 对保存与回滚操作包裹资源锁，并使用租户 ID 作为锁 key 的一部分，保证多租户隔离下的编辑安全。【F:nop-web/src/main/java/io/nop/web/page/ResourceWithHistoryProvider.java†L35-L46】
- `WebPageHelper` 提供文件类型校验、空值清理、GraphQL URL 转义、`xui:import` 归一化以及自动删除编辑器生成的随机 `id`。【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L49-L266】
- `PageProviderBizModel`/`SystemJsProviderBizModel` 使用 `@cfg:*` 覆盖控制编辑功能，禁用时抛出 `ERR_WEB_PAGE_NOT_ALLOW_EDIT` 并拒绝保存/回滚请求。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L34-L73】【F:nop-web/src/main/java/io/nop/web/biz/SystemJsProviderBizModel.java†L33-L71】

## 4. 推测（Hypotheses，⚠️ + 验证计划）
- ⚠️ `DynamicWebFileProvider.loadAllXjs()`/`loadAllXcss()` 是否会在大量模块场景触发重复生成？计划：统计 `ResourceComponentManager` 的缓存命中日志，确认重复调用时是否重用组件模型，而非重复写文件。【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L41-L55】

## 5. 规则（IF-THEN，可执行）
- IF `CFG_WEB_AUTO_LOAD_DYNAMIC_FILE` 为 true THEN 服务启动时遍历模块 `/pages`、`/js`、`/css` 目录并加载所有 `.xjs/.xcss`，确保动态资源预热；否则仅在首次访问时按需生成。【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L33-L55】
- IF `.xjs` 文件存在且 `systemJsTransformer` 可用 THEN `DynamicJsLoader` 会先生成源代码再调用 transformer 输出 SystemJS 模块，否则保留 `.js` 原文或抛 `ERR_RESOURCE_NOT_EXISTS`。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L100-L156】
- IF 页面编辑入口 `nop.web.page-provider.edit-enabled` 关闭 THEN 所有保存、回滚、源码读取操作抛出 `ERR_WEB_PAGE_NOT_ALLOW_EDIT`，仅允许 `getPage` 公共查询。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L34-L73】
- IF 保存页面时检测到元素 `id` 以 `u:` 开头且包含 `v:id`/`x:id`/`name` 等关键字段 THEN `removeGeneratedId` 会删除该随机 `id`，防止差量合并失败。【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L203-L228】
- IF 请求的页面/脚本扩展名不在白名单内 THEN `WebPageHelper.checkPageFile/checkJsFile/checkXjsFile` 会抛出对应错误码，阻止非法文件操作。【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L49-L68】

## 6. 流程（Text-Sequence：编号步骤）
1. **组件注册**：`PageProvider.init()` 注册 XView/XPage loader；`DynamicJsLoader`/`DynamicCssLoader` 注册动态文本加载器。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L100-L155】【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L51-L59】【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L37-L45】
2. **资源发现**：若启用自动加载，`DynamicWebFileProvider.init()` 扫描模块资源并调用 loader 生成初始 `.js/.css`。【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L33-L55】
3. **页面解析**：`PageProvider.loadPage` 切换上下文 locale，加载 JSON Delta，调用 `WebPageHelper` 清理空值、规范导入与修复 amis 结构后封装为 `PageModel`。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L283-L305】【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L70-L266】
4. **权限处理**：若存在 `IRolePermissionMapping`，`transformPermissions` 将 `xui:permissions` 转换成角色集合并写回页面数据。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L233-L268】
5. **渲染输出**：根据 `PageRenderOptions` 决定是否执行 resolver/i18n、是否二次处理 JSON，并在需要时写入目标目录或返回内存结构。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L182-L242】
6. **编辑保存**：编辑入口在保存前调用 `checkPageFile`、`removeGeneratedId`，随后经 `withHistorySupport` 写入资源并生成 Delta 差量，同时保留历史版本供回滚。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L56-L73】【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L203-L228】【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L320-L338】【F:nop-web/src/main/java/io/nop/web/page/ResourceWithHistoryProvider.java†L35-L46】
7. **脚本生成**：`DynamicJsLoader.loadDynamicJs` 判断扩展名，必要时运行 `WebDynamicFileProcessor` 处理 `@generate` 片段并调用 transformer 产出最终文本；生成结果写入 dump/物理文件供调试与静态部署。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L73-L170】【F:nop-web/src/main/java/io/nop/web/page/WebDynamicFileProcessor.java†L29-L95】

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| RAW_TEMPLATE | 访问 `.xjs/.xcss` | 源文件存在且配置开启 | GENERATED | `WebDynamicFileProcessor` 生成代码并写入 dump/物理文件。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L112-L126】【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L73-L91】|
| RAW_TEMPLATE | 访问 `.xjs/.xcss` | 配置关闭或源缺失 | FALLBACK | 直接返回原 `.js/.css` 或抛缺失异常。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L98-L132】【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L65-L71】|
| EDITABLE_PAGE | 保存请求 | `editEnabled=true` 且扩展名合法 | SAVED_WITH_HISTORY | `withHistorySupport` 写入文件并记录历史快照。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L56-L73】【F:nop-web/src/main/java/io/nop/web/page/ResourceWithHistoryProvider.java†L35-L46】|
| EDITABLE_PAGE | 保存/回滚请求 | `editEnabled=false` | REJECTED | 抛出 `ERR_WEB_PAGE_NOT_ALLOW_EDIT`，不修改资源。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L47-L73】|

## 8. 接口契约（I/O 表：端点、字段、约束、错误码）
| 组件/入口 | 输入 | 输出 | 约束 | 错误/异常 |
| --- | --- | --- | --- | --- |
| `PageProvider.getPage(path, locale)` | 页面路径、语言代码 | 页面 JSON | 需要合法页面扩展名；在 `IRolePermissionMapping` 可用时追加 `xui:roles`。| 文件缺失或解析错误抛 `NopException`；非法文件类型由调用方先行校验。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L244-L268】|
| `PageProvider.savePageSource(path, page)` | 页面路径、修改后的 JSON | - | 仅允许真实文件；保存前执行 `unfixPage`、i18n 绑定转换与历史管理。| 非文件资源抛 `ERR_WEB_PAGE_RESOURCE_NOT_FILE`；写入失败向上传播异常。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L320-L338】|
| `DynamicJsLoader.loadDynamicJs(path)` | `.js/.mjs` 虚拟路径 | `TextFile` 内容 | 根据扩展决定回退或生成；需要 transformer 才能输出 SystemJS。| 源文件缺失抛 `ERR_RESOURCE_NOT_EXISTS`；未知扩展抛 `ERR_COMPONENT_UNKNOWN_FILE_TYPE_FOR_MODEL_TYPE`。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L73-L136】|
| `DynamicWebFileProvider.saveJsSource(path, source)` | `.xjs` 路径、源文本 | - | 使用资源锁与历史记录保护写入；未做扩展校验，需调用前检查。| 写入失败会抛 `NopException` 或 I/O 异常，历史管理负责回滚旧版本。【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L67-L73】|
| `SystemJsProviderBizModel.getJs(path)` | URL 路径 | `WebContentBean` | 自动剥离 query，校验扩展名仅允许 `.js`。| 扩展不合法抛 `ERR_WEB_UNSUPPORTED_FILE_TYPE`；编辑禁用时部分接口抛 `ERR_WEB_PAGE_NOT_ALLOW_EDIT`。【F:nop-web/src/main/java/io/nop/web/biz/SystemJsProviderBizModel.java†L36-L71】【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L56-L68】|

## 9. 用例（Few-shot：Given-When-Then）
- 正向（页面渲染）：Given `moduleId="nop-auth"` 且启用 i18n 解析，When 调用 `renderPagesTo` 导出页面，Then Loader 会读取所有匹配文件、解析 JSON Delta、转换权限并输出格式化 JSON。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L182-L242】
- 反向（禁用编辑）：Given `nop.web.page-provider.edit-enabled=false`，When 通过 BizMutation `savePageSource` 提交内容，Then 立即抛出 `ERR_WEB_PAGE_NOT_ALLOW_EDIT`，资源保持不变。【F:nop-web/src/main/java/io/nop/web/biz/PageProviderBizModel.java†L47-L64】
- 边界（缺少 `.xjs`）：Given 请求 `foo.js` 且不存在 `foo.xjs`，When `DynamicJsLoader` 被调用，Then 若静态 `.js` 存在直接返回文本，否则抛出 `ERR_RESOURCE_NOT_EXISTS` 提示缺失文件。【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L97-L132】
- 正向（权限转换）：Given 页面元素含 `xui:permissions="perm.a"` 且映射服务返回 `roleA`，When `getPage` 在有映射环境执行，Then `xui:roles` 包含 `roleA` 与原始角色集合的并集。【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L233-L268】
- 边界（非法页面扩展）：Given 调用 `getPageSource` 传入 `foo.txt`，When `checkPageFile` 检测到后缀不在允许列表，Then 抛出 `ERR_WEB_INVALID_PAGE_FILE_TYPE` 阻止读取。【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L49-L54】

## 10. 证据矩阵（结论↔证据↔置信度↔Run-less验证）
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| 动态 JS/CSS 生成后会写入 dump 与物理文件，便于调试与静态部署 | `DynamicJsLoader`/`DynamicCssLoader` 在生成后调用 `ResourceHelper.dumpResource`、`FileHelper.writeTextIfNotMatch` | 高 | 检查示例模块的 `_dump` 目录或生成文件，确认多次加载不会重复写入。 |【F:nop-web/src/main/java/io/nop/web/page/DynamicJsLoader.java†L118-L127】【F:nop-web/src/main/java/io/nop/web/page/DynamicCssLoader.java†L80-L88】|
| 页面保存前会清理随机 `id` 并通过历史锁序列化写入 | `removeGeneratedId` 逻辑 + `withHistorySupport` 包裹写入 | 中 | 在示例页面 JSON 中构造 `id="u:*"` 并静态推演保存结果，确认差量不包含随机 ID。 |【F:nop-web/src/main/java/io/nop/web/page/WebPageHelper.java†L203-L228】【F:nop-web/src/main/java/io/nop/web/page/ResourceWithHistoryProvider.java†L35-L46】|
| 权限映射会在 `rolePermissionMapping` 可用时将 `xui:permissions` 自动展开为角色 CSV；未提供映射时返回原始权限字段 | `transformPermissions` 使用 `rolePermissionMapping.getRolesWithPermission` 合并角色，`getPage`/`renderPage` 在映射缺失时跳过转换 | 高 | 记录一次页面 JSON，在有/无映射两种环境对比 `xui:roles` 与 `xui:permissions` 差异，补充角色格式实证。 |【F:nop-web/src/main/java/io/nop/web/page/PageProvider.java†L233-L268】|
| 自动加载依赖配置开关，关闭时不会触发批量生成 | `DynamicWebFileProvider.init()` 仅在配置开启时调用 `loadAllXjs/Xcss` | 高 | 比较配置开关前后的资源加载日志，确认未启用时不会扫描模块目录。 |【F:nop-web/src/main/java/io/nop/web/page/DynamicWebFileProvider.java†L33-L55】|

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.2 | 2024-06-16 | 明确缺少 `IRolePermissionMapping` 时保留 `xui:permissions`，并提升权限映射证据置信度。 |
| v0.1 | 2024-06-16 | 首次整理 `nop-web` 页面加载、动态 JS/CSS 生成与编辑 BizModel 的规则、流程和证据矩阵。 |

## 12. 检索标签（Tags）
`#nop-web` `#XPage` `#DynamicResource` `#PageProvider` `#RunLess`
