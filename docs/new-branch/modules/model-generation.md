# 模型生成链路（Excel → ORM → GraphQL）

## 1. 摘要（TL;DR）
1. Excel 数据模型通过 `imp.xml` 规范解析字段、实体与标签，是模型驱动生成的入口。【F:docs/dev-guide/model/excel-model.md†L1-L25】
2. `CodeGenTask` 结合 `exec-maven-plugin` 在编译前后执行 `_vfs` 模板，实现预编译/后编译阶段的代码生成。【F:docs/dev-guide/codegen.md†L3-L24】
3. `XCodeGenerator` 以模板路径为微 DSL，数据驱动地展开循环与条件，生成 ORM、元数据与前端资源。【F:docs/dev-guide/codegen.md†L118-L195】
4. `nop-cli gen` 命令封装 `CodeGenTask`，可直接在命令行读取 Excel 模型并输出代码。【F:docs/dev-guide/codegen.md†L73-L91】
5. 生成的 ORM 实体、XMeta、BizModel 同步构成 GraphQL Schema 与加载器骨架，无需手写绑定代码。【F:docs/theory/lowcode-orm-2.md†L816-L905】
6. 差量目录 `_delta` 支持在不修改基线文件的前提下覆写 Mapper、Bean 配置，配合模型再生保持可逆定制。【F:docs/dev-guide/spring/spring-delta.md†L17-L83】
7. GraphQL 关联属性通过 `ref-connection`、`graphql:findMethod` 标签在生成期注入 Connection、分页能力。【F:docs/dev-guide/graphql/connection.md†L1-L135】

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| Imp 导入规范 | Import Specification | 由 `*.imp.xml` 定义 Excel→模型的字段解析规则，仅描述结构，不涵盖业务语义。【F:docs/dev-guide/model/excel-model.md†L1-L11】|
| XCodeGenerator | XCodeGenerator | `nop-codegen` 中的数据驱动生成器，通过模板路径表达循环与条件控制。【F:docs/dev-guide/codegen.md†L118-L176】|
| `_vfs` 虚拟文件系统 | Virtual File System | 统一挂载模板、生成物与差量文件的资源层，生成阶段按模块路径解析。【F:docs/dev-guide/codegen.md†L83-L107】【F:docs/dev-guide/model/excel-model.md†L15-L24】|
| `_delta` 差量目录 | Delta Directory | 放置定制文件的目录，加载顺序优先于基线，实现继承式覆写。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】|
| BizModel | BizModel | GraphQL/REST 共用的业务模型类，通过注解声明查询、装载逻辑。【F:docs/theory/lowcode-orm-2.md†L853-L907】|

## 3. 事实（Facts）
- Excel 数据模型的 `appName` 必须与文件名一致，确保生成模块目录正确映射到 `_vfs/nop/{app}` 结构。【F:docs/dev-guide/model/excel-model.md†L15-L24】
- 模板执行分为 `precompile` 与 `postcompile`，分别在 Java 编译前后运行，访问的类路径范围不同。【F:docs/dev-guide/codegen.md†L3-L24】
- `nop-cli gen` 可直接指定模型与模板路径，将生成结果输出到当前工程目录，无需启动 IoC 容器。【F:docs/dev-guide/codegen.md†L73-L91】
- 生成的 `_MyObj.java`、`MyObj.java`、`MyObj.xmeta`、`MyObjBizModel.java` 构成 ORM 与 GraphQL 的同步骨架。【F:docs/theory/lowcode-orm-2.md†L816-L888】
- `ref-connection` 标签会在元编程阶段生成 `GraphQLConnection` 属性，为关联集合提供分页接口。【F:docs/dev-guide/graphql/connection.md†L1-L55】
- `_delta/default` 下的 Mapper 覆盖文件使用 `x:extends="super"` 合并基线 SQL，运行期由 `DslNodeLoader` 解析后注入 MyBatis。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】
- `nop-auth.orm.xlsx` 的 `nop_auth_user` 工作表把 `USER_ID/USER_NAME` 标记为主键与展示字段，对应生成的 `_NopAuthUser` 常量与 XMeta 属性，实现 Excel→ORM→元数据的对齐。【5658c7†L1-L10】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthUser.java†L23-L37】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthUser/_NopAuthUser.xmeta†L24-L58】
- `nop-auth.orm.xlsx` 的 `nop_auth_role` 表中 `ROLE_ID/ROLE_NAME` 以及差量说明列与 `_NopAuthRole`、`_NopAuthRole.xmeta` 保持字段、约束一致，验证角色模型链路同步。【c57623†L1-L6】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthRole.java†L23-L45】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthRole/_NopAuthRole.xmeta†L24-L48】
- `nop_auth_dept` 表定义的主键、展示字段与树型关系在 `_NopAuthDept` 常量、`_NopAuthDept.xmeta` 中完整重现，额外的负责人、联络信息等属性也在生成物中补全扩展字段，证明 Excel→ORM→XMeta 的同步生成能力覆盖树结构场景。【538435†L32-L55】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L18-L126】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L1-L125】
- `MANAGER_ID` 字段在 ORM 中声明外键并生成 `managerId` 常量，XMeta 通过 `ext:relation="manager"` 与 `NopAuthUser` 关联，同时 `_gen` 视图默认 GraphQL 查询中暴露 `managerId` 列，验证 Excel→ORM→GraphQL 的连续映射。【F:nop-auth/nop-auth-dao/src/main/resources/_vfs/nop/auth/orm/_app.orm.xml†L769-L826】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L43-L126】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L46-L102】【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthDept/_gen/_NopAuthDept.view.xml†L21-L152】
- `groupMappings` 多对多关系在 ORM `to-many` 关联中定义，并在 XMeta 中生成带 `graphql:labelProp` 的 `relatedGroupList_ids` 访问器，辅助页面与 GraphQL 请求返回关联标签集合，证明生成物覆盖引用集合与标签展示需求。【F:nop-auth/nop-auth-dao/src/main/resources/_vfs/nop/auth/orm/_app.orm.xml†L839-L845】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L87-L102】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L118-L140】
- `_NopAuthGroup` 的 `_gen` 视图提供树列表所需的 `parentId/ownerId` 列，并自动补齐 `parent { name }`、`owner { userName }` 等关联展示字段，但默认表单尚未纳入 `relatedDeptList_ids/relatedUserList_ids`，显示多对多标签需后续定制视图触发。【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthGroup/_gen/_NopAuthGroup.view.xml†L18-L136】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthGroup/_NopAuthGroup.xmeta†L24-L183】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L63-L111】
- `NopDynApp` 通过自定义视图在编辑表单引入 `relatedModuleList_ids` 控件，依赖 `graphql:labelProp` 自动补充 `relatedModuleList_label` 字段，证明 `_ids` 属性一旦出现在布局中，`@formSelection` 会自动拉取标签集合供 UI 使用。【F:nop-dyn/nop-dyn-web/src/main/resources/_vfs/nop/dyn/pages/NopDynApp/NopDynApp.view.xml†L11-L20】【F:nop-dyn/nop-dyn-meta/src/main/resources/_vfs/nop/dyn/model/NopDynApp/_NopDynApp.xmeta†L52-L89】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L200-L210】
- `_NopAuthDept.xbiz` 通过 `DefaultBizGenExtends` 直接绑定 ORM 实体 `io.nop.auth.dao.entity.NopAuthDept`，让默认 GraphQL/BizModel 动作继承实体字段与关联配置，无需额外手写逻辑即可对接生成的视图与 API。【F:nop-auth/nop-auth-service/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xbiz†L1-L13】
- `XuiHelper.getListSelection` 与 `getFormSelection` 借助 `XuiViewAnalyzer` 自动补齐 `id`、主键列，并根据表格/表单的列、布局追加关联展示字段和标签，使 `{@listSelection}`、`{@formSelection}` 可以直接复用 GraphQL 查询所需字段集合。【F:nop-ui/src/main/java/io/nop/xui/utils/XuiHelper.java†L190-L205】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L63-L176】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L179-L235】

| Excel 字段 | ORM 常量 | XMeta 属性 | 备注 |
| --- | --- | --- | --- |
| `DEPT_ID`（seq 主键） | `PROP_NAME_deptId` / `PROP_ID_deptId=1` | `<prop name="deptId" tagSet="seq" mandatory="true"/>` | 同步标记主键、展示限制。【538435†L38-L41】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L24-L41】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L15-L28】 |
| `DEPT_NAME`（disp 展示） | `PROP_NAME_deptName` / `PROP_ID_deptName=2` | `<prop name="deptName" tagSet="disp" mandatory="true"/>` | 展示字段与国际化名称保持一致。【538435†L38-L44】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L29-L45】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L29-L38】 |
| `PARENT_ID`（parent 标签） | `PROP_NAME_parentId` / `PROP_ID_parentId=3` | `<tree parentProp="parentId" childrenProp="children"/>` + `<prop name="parentId" tagSet="parent"/>` | 树形层级在 XMeta 中转化为 `tree` 配置与引用关系。【538435†L44-L47】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L32-L63】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L31-L55】 |
| `MANAGER_ID`（负责人外键） | `PROP_NAME_managerId` / `PROP_ID_managerId=6` | `<prop name="managerId" ext:relation="manager"/>` + 视图列 `<col id="managerId"/>` | 验证 Excel→ORM→GraphQL 的负责人字段同步。【F:nop-auth/nop-auth-dao/src/main/resources/_vfs/nop/auth/orm/_app.orm.xml†L769-L826】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L43-L125】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L46-L102】【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthDept/_gen/_NopAuthDept.view.xml†L21-L104】 |
| `groupMappings`（部门-分组映射） | `PROP_NAME_groupMappings` | `<prop name="groupMappings" tagSet="pub,cascade-delete"/>` + `relatedGroupList_ids` `graphql:labelProp` | 多对多映射与 GraphQL 标签同步生成。【F:nop-auth/nop-auth-dao/src/main/resources/_vfs/nop/auth/orm/_app.orm.xml†L839-L845】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L87-L102】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L118-L140】 |

| `_delta` 目录 | 定制类型 | 作用 | 证据 |
| --- | --- | --- | --- |
| `nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/...` | XMeta/BizModel | 扩展日志排序与字段域定义，覆盖 GraphQL 元数据。 |【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】|
| `nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml` | ORM | 为实体开启分片配置，复用基线并最小化差量。 |【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】|
| `nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml` | MyBatis Mapper | 通过 `x:extends="super"` 改写查询 SQL，展示差量注入流程。 |【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】【F:docs/dev-guide/spring/spring-delta.md†L64-L83】|

> `_gen` 视图 GraphQL 选择映射（`NopAuthDept` 树型场景）

| 选择名 | GraphQL 字段（按输出顺序） | 字段来源 | 说明 |
| --- | --- | --- | --- |
| `@listSelection` | `id`, `deptId`, `deptName`, `parentId`, `parent { deptName }`, `orderNum`, `deptType`, `managerId`, `manager { userName }`, `email`, `phone`, `createdBy`, `createTime`, `updatedBy`, `updateTime`, `remark`, `children @TreeChildren(max:5)` | 列配置取自 `grid id="list"` 与 `tree-list` 的扩展选择；主键字段、关联展示属性由 `XuiViewAnalyzer` 自动附加。【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthDept/_gen/_NopAuthDept.view.xml†L18-L103】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L63-L111】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L381-L395】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L22-L117】 | 树列表调用 `NopAuthDept__findList` 时直接嵌入该字段集合，并通过 `@TreeChildren(max:5)` 指令懒加载子节点。 |
| `@formSelection` | `id`, `deptId`, `deptName`, `parentId`, `parent { deptName }`, `orderNum`, `deptType`, `managerId`, `manager { userName }`, `email`, `phone`, `createdBy`, `createTime`, `updatedBy`, `updateTime`, `remark` | 字段来自 `view`/`edit` 表单布局，`XuiViewAnalyzer.collectSelection` 会合并布局单元、主键与关联展示字段。【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthDept/_gen/_NopAuthDept.view.xml†L64-L102】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L163-L235】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L22-L117】 | `view`、`update` 页面通过 `@formSelection` 发起 `NopAuthDept__get` 查询，默认覆盖负责人、审计字段。 |

> `_gen` 视图 GraphQL 选择映射（`NopAuthGroup` 树结构 + 多对多预置）

| 选择名 | GraphQL 字段（按输出顺序） | 字段来源 | 说明 |
| --- | --- | --- | --- |
| `@listSelection` | `id`, `groupId`, `name`, `parentId`, `parent { name }`, `ownerId`, `owner { userName }`, `createdBy`, `createTime`, `updatedBy`, `updateTime`, `remark`, `children @TreeChildren(max:5)` | 树表格列配置提供 `name/parentId/ownerId/...`，`XuiViewAnalyzer` 追加主键与关联展示字段；`tree-list` 复用列表列并加上 `children @TreeChildren(max:5)`。【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthGroup/_gen/_NopAuthGroup.view.xml†L18-L118】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L63-L111】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthGroup/_NopAuthGroup.xmeta†L24-L107】 | 默认 `NopAuthGroup__findList` 查询即可返回父节点名称与所有者用户名，树节点子集按 5 层懒加载。 |
| `@formSelection` | `id`, `groupId`, `name`, `parentId`, `parent { name }`, `ownerId`, `owner { userName }`, `createdBy`, `createTime`, `updatedBy`, `updateTime`, `remark` | 表单布局目前仅包含基础字段，生成逻辑补齐主键与关联展示值，尚未引用 `relatedDeptList_ids/relatedUserList_ids` 等多对多控件。【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthGroup/_gen/_NopAuthGroup.view.xml†L52-L136】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L200-L235】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthGroup/_NopAuthGroup.xmeta†L24-L183】 | 若后续在表单中放入 `_ids` 控件，可依托 `graphql:labelProp` 自动补全标签字段（见下方示例）。 |

> 表单引用 `_ids` 字段时的标签扩展示例（`NopDynApp` 定制视图）

| 页面/选择 | GraphQL 字段（按输出顺序） | 字段来源 | 说明 |
| --- | --- | --- | --- |
| `@formSelection`（`update`） | `id`, `appId`, `appName`, `displayName`, `appVersion`, `sortOrder`, `status`, `relatedModuleList_ids`, `relatedModuleList_label` | 自定义视图在 `edit` 表单加入 `relatedModuleList_ids` 控件，`XuiViewAnalyzer.addLabelProp` 因 `graphql:labelProp="relatedModuleList_label"` 自动补充标签字段；主键字段同样由生成器附加。【F:nop-dyn/nop-dyn-web/src/main/resources/_vfs/nop/dyn/pages/NopDynApp/NopDynApp.view.xml†L11-L20】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L200-L210】【F:nop-dyn/nop-dyn-meta/src/main/resources/_vfs/nop/dyn/model/NopDynApp/_NopDynApp.xmeta†L52-L89】 | `_NopDynApp.xmeta` 对 `relatedModuleList_ids` 声明 `graphql:labelProp` 与 picker URL，扩展层 `NopDynApp.xmeta` 将其改为可写字段，从而让 `@formSelection` 同时获得 ID 与标签集合用于多选展示。【F:nop-dyn/nop-dyn-meta/src/main/resources/_vfs/nop/dyn/model/NopDynApp/NopDynApp.xmeta†L1-L6】【F:nop-dyn/nop-dyn-meta/src/main/resources/_vfs/nop/dyn/model/NopDynApp/_NopDynApp.xmeta†L70-L107】 |

## 4. 推测（Hypotheses，⚠️）
- ⚠️ 推测1：`precompile` 目录下还可生成 gRPC/OpenAPI 描述文件。验证计划：检索 `_vfs` 模板中是否存在 `grpc`、`openapi` 关键词。
- ⚠️ 推测2：GraphQL 生成链路可输出 REST `@BizQuery` 自动暴露到 `/r/` 路径。验证计划：查找 `GraphQLWebService` 与 REST 控制器实现。
- ⚠️ 推测3：`_delta` 支持多租户特定目录（如 `_delta/tenant-x`）实现租户级差量。验证计划：搜索 `_delta` 目录命名规范或相关配置项。

## 5. 规则（IF-THEN，可执行）
- IF Excel `appName` 与文件名不一致 THEN 调整文件名为 `${appName}.orm.xlsx` 以避免生成路径错误。【F:docs/dev-guide/model/excel-model.md†L15-L24】
- IF 需要最小初始化开销执行生成 THEN 设定 `CoreConfigs.CFG_CORE_MAX_INITIALIZE_LEVEL=INITIALIZER_PRIORITY_ANALYZE` 再启动 `CodeGenTask`。【F:docs/dev-guide/codegen.md†L62-L71】
- IF 需要扩展模板 THEN 将自定义模板打包到 `src/resources/_vfs` 并在命令中通过 `-t=/xxx/yyy` 指定路径。【F:docs/dev-guide/codegen.md†L83-L107】
- IF MyBatis SQL 需局部覆写 THEN 在 `_delta/default/.../_gen` 平级创建差量文件并使用 `x:extends="super"` 合并基线定义。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】
- IF GraphQL 关联需分页 THEN 在 Excel 模型关联属性上标注 `ref-connection` 或 `ref-query` 以自动生成 Connection/FindList 能力。【F:docs/dev-guide/graphql/connection.md†L1-L135】
- IF 需要沿用平台的生成管线 THEN 在模块 `pom.xml` 引入继承自根 `pluginManagement` 的 `exec-maven-plugin`，即可复用 `precompile/precompile2/aop/postcompile` 四阶段执行 `CodeGenTask`，无需重复配置主类与依赖。【F:pom.xml†L205-L284】【F:nop-auth/nop-auth-codegen/pom.xml†L12-L19】

## 6. 流程（Text-Sequence：编号步骤）
1. 准备 Excel 模型并校验字段、标签、`appName`、包名配置。【F:docs/dev-guide/model/excel-model.md†L15-L88】
2. （可选）在 `_vfs/_delta` 预置差量模板或定制 SQL/Bean。【F:docs/dev-guide/spring/spring-delta.md†L70-L139】
3. 执行 `mvn package` 或 `nop-cli gen` 触发 `CodeGenTask`，加载 `precompile` 模板。【F:docs/dev-guide/codegen.md†L3-L90】
4. `exec-maven-plugin` 先后在 `generate-sources`（`precompile`、`precompile2`）、`compile`（`aop`）、`generate-test-resources`（`postcompile`）阶段调用 `CodeGenTask`，覆盖生成、增强与补丁流程。【F:pom.xml†L205-L284】
5. `XCodeGenerator` 解析模板路径表达式，生成 ORM 实体、元数据、前端资源。【F:docs/dev-guide/codegen.md†L118-L195】
6. 生成器将 GraphQL Schema、BizModel 骨架写入 `_gen` 目录，供二次定制。【F:docs/theory/lowcode-orm-2.md†L816-L888】
7. `postcompile` 阶段应用补充模板，确保生成文件与手工扩展协同。【F:docs/dev-guide/codegen.md†L3-L24】
8. `DslNodeLoader` 在运行期装载 `_delta` 定制，与基线合并后暴露最终服务。【F:docs/dev-guide/spring/spring-delta.md†L27-L83】

- 冲突诊断步骤：
  1. 通过 `ModuleManager.findModuleResources` / `DslNodeLoader` 的日志确认是否命中 `_delta` 资源，若未触发需检查目录层级与文件名是否一致。【F:docs/dev-guide/spring/spring-delta.md†L64-L83】
  2. 对比基线与差量文件中的 `x:extends="super"` 配置及命名空间，确保 schema/namespace 完整匹配（常见于 Mapper、XMeta 差量）。【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】
  3. 若仍冲突，临时移除 `_delta` 文件并重启生成流程，利用 `super` 基线输出比对差异，再逐项恢复差量定位冲突行。【F:docs/dev-guide/spring/spring-delta.md†L83-L140】

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| Excel 草稿 | 配置校验 | `imp` 规则通过 | 模型冻结 | 记录 `appName` 与包名映射。【F:docs/dev-guide/model/excel-model.md†L15-L24】|
| 模型冻结 | 触发生成 | 模板、依赖存在 | 模板执行中 | 初始化虚拟文件系统，装载 `_vfs`。【F:docs/dev-guide/codegen.md†L83-L107】|
| 模板执行中 | 模板遍历完成 | 循环展开无错误 | 生成产物 | 输出 `_gen` 文件夹内实体/元数据。【F:docs/dev-guide/codegen.md†L118-L195】|
| 生成产物 | 差量扫描 | `_delta` 命中 | 差量合并中 | `DslNodeLoader` 合并差量节点。【F:docs/dev-guide/spring/spring-delta.md†L70-L83】|
| 差量合并中 | 合并完成 | 无冲突 | 定制生效 | Mapper/Bean/GraphQL 属性更新。【F:docs/dev-guide/graphql/connection.md†L20-L35】|

## 8. 接口契约（I/O 表：端点、字段、约束、错误码）
| 工具/端点 | 输入 | 输出 | 约束 | 错误/异常 |
| --- | --- | --- | --- | --- |
| `mvn package` + `CodeGenTask` | 项目 `pom.xml`、`precompile`/`postcompile` 模板 | `_gen` Java/配置文件 | 需配置 `exec-maven-plugin`；模板路径必须合法 | 模板缺失、循环变量未定义导致异常。【F:docs/dev-guide/codegen.md†L3-L176】|
| `nop-cli gen <model> -t=<template>` | Excel 模型、模板路径 | 目标目录生成文件 | 默认输出到当前工程，需确保模型语法正确 | 模型解析失败、模板初始化脚本抛错。【F:docs/dev-guide/codegen.md†L73-L195】|
| GraphQL Connection 属性 | GraphQL 查询参数（`first/last`, `filter`, `_subArgs`） | `GraphQLConnection` 结果 | 需要 `ref-connection`、`graphql:connectionProp` 配置 | 未标注标签或超出 `maxFetchSize` 触发限制。【F:docs/dev-guide/graphql/connection.md†L1-L135】|
| MyBatis Mapper 差量 | `_delta` 覆写 SQL | 合并后的 SQL 注册到 `SqlSessionFactory` | 差量文件须 `x:extends="super"` 并符合 schema | 差量冲突、缺少 namespace 触发加载失败。【F:docs/dev-guide/spring/spring-delta.md†L27-L83】|

## 9. 用例（Few-shot：Given-When-Then）
- 正向：Given `nop_sys.orm.xlsx` 已配置 `mapper` 标签，When 运行 `nop-cli gen`，Then 在 DAO 模块生成 `_SysUser.mapper.xml` 与对应实体。【F:docs/dev-guide/model/excel-model.md†L82-L101】【F:docs/dev-guide/codegen.md†L73-L91】
- 反向：Given Excel 中 `appName` 与文件名不一致，When 执行 `mvn package`，Then 生成器定位错误目录导致配置失配并报错提示路径不匹配。【F:docs/dev-guide/model/excel-model.md†L15-L24】
- 边界：Given 子表需要分页，When 在关联上标注 `ref-connection`，Then 生成 `resourcesConnection` 属性并支持 `first/after` 参数分页。【F:docs/dev-guide/graphql/connection.md†L1-L55】

## 10. 证据矩阵
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| Excel→模型解析受 `imp.xml` 约束 | 《Excel数据模型》说明、`nop-auth.orm.xlsx` 字段清单 | 高 | 继续抽查其他实体工作表与 `orm.imp.xml` 对应关系。【5658c7†L1-L10】【c57623†L1-L6】【F:docs/dev-guide/model/excel-model.md†L1-L11】|
| 模板执行由 `CodeGenTask` 与 `exec-maven-plugin` 协同触发 | 《Maven集成代码生成器》章节、根 `pom` 插件管理配置 | 高 | 抽查 `nop-auth-codegen` `pom.xml` 继承插件与生命周期绑定情况。【F:docs/dev-guide/codegen.md†L3-L24】【F:pom.xml†L205-L284】【F:nop-auth/nop-auth-codegen/pom.xml†L12-L19】|
| `XCodeGenerator` 采用模板路径控制循环 | 《数据驱动的代码生成器》章节 | 高 | 打开 `nop-wf/.../gen-orm.xgen` 验证路径语法使用。【F:docs/dev-guide/codegen.md†L118-L176】|
| GraphQL Schema 与 ORM 同步生成 | `_NopAuthUser.java` 与 `_NopAuthUser.xmeta` 对齐字段 | 高 | 对比更多实体（如 `NopAuthRole`、`NopAuthDept`）的 `_gen` 文件保持一致性。【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthUser.java†L23-L157】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthUser/_NopAuthUser.xmeta†L24-L166】|
| `NopAuthDept` 树模型三方同步 | `nop_auth_dept` 工作表、`_NopAuthDept` 常量、`_NopAuthDept.xmeta` | 高 | 扩展检查 `relatedGroupList` GraphQL 标签与 `_gen` 视图选择项的对应关系。【538435†L32-L55】【F:nop-auth/nop-auth-dao/src/main/java/io/nop/auth/dao/entity/_gen/_NopAuthDept.java†L18-L125】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L1-L140】|
| `managerId` 与 `groupMappings` 字段链路打通 | ORM 列/关联、`_NopAuthDept.xmeta` 关系、`_gen` 视图 GraphQL 查询 | 高 | 已整理 `@listSelection/@formSelection` 字段表，下一步抽查 `NopAuthGroup` 等视图验证多对多标签字段的 GraphQL 暴露方式。【F:nop-auth/nop-auth-dao/src/main/resources/_vfs/nop/auth/orm/_app.orm.xml†L769-L845】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthDept/_NopAuthDept.xmeta†L46-L140】【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthDept/_gen/_NopAuthDept.view.xml†L18-L152】|
| `_NopAuthGroup` 默认 selections 覆盖树节点与关联展示字段 | `_gen` 视图列定义、`XuiViewAnalyzer` 选择逻辑、XMeta 关联描述 | 中 | 继续观察是否在差量或自定义视图中加入 `_ids` 字段以触发标签集合；必要时记录新增字段后的 selection 变化。【F:nop-auth/nop-auth-web/src/main/resources/_vfs/nop/auth/pages/NopAuthGroup/_gen/_NopAuthGroup.view.xml†L18-L136】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L63-L235】【F:nop-auth/nop-auth-meta/src/main/resources/_vfs/nop/auth/model/NopAuthGroup/_NopAuthGroup.xmeta†L24-L183】|
| 引入 `_ids` 控件可自动补充标签字段 | `NopDynApp` 自定义视图、`graphql:labelProp` 属性、XuiViewAnalyzer 的 `addLabelProp` | 中 | 计划梳理更多模块（如 `NopAuthRole`）的 `_ids` 控件使用方式，并列出实际 selection 结果截图或摘录。【F:nop-dyn/nop-dyn-web/src/main/resources/_vfs/nop/dyn/pages/NopDynApp/NopDynApp.view.xml†L11-L20】【F:nop-dyn/nop-dyn-meta/src/main/resources/_vfs/nop/dyn/model/NopDynApp/_NopDynApp.xmeta†L52-L107】【F:nop-ui/src/main/java/io/nop/xui/utils/XuiViewAnalyzer.java†L200-L210】|
| `_delta` 目录用于 Mapper/ORM/Meta 差量 | Spring Delta 指南、示例差量文件 | 高 | 归档各模块差量清单并汇总合并日志样本。【F:docs/dev-guide/spring/spring-delta.md†L64-L140】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/orm/app.orm.xml†L1-L9】【F:nop-auth/nop-auth-service/src/test/resources/_vfs/_delta/default/nop/auth/model/NopAuthOpLog/NopAuthOpLog.xmeta†L1-L13】【F:nop-demo/nop-spring-demo/src/test/resources/_vfs/_delta/default/nop/spring/mapper/SysUser.mapper.xml†L1-L15】|

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.4 | 2024-06-01 | 记录 `NopAuthGroup` `_gen` 视图 selection 列表，并给出 `_ids` 控件触发标签字段的 `NopDynApp` 案例。|
| v0.3 | 2024-05-26 | 增补 `NopAuthDept` 树模型映射对照表，并梳理 `exec-maven-plugin` 四阶段执行要点。|
| v0.2 | 2024-05-25 | 补充 `nop-auth` Excel→ORM→XMeta 映射证据，整理 `_delta` 目录用例与冲突诊断步骤。|

## 12. 检索标签（Tags）
`#nop-codegen` `#Excel模型` `#XCodeGenerator` `#GraphQL` `#Delta` `#RunLess`
