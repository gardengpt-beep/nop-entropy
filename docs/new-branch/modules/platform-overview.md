# 平台总体认知（Nop Platform 2.0）

## 1. 摘要（TL;DR）
1. Nop Platform 2.0 以可逆计算理论为核心，定位为低代码/无代码的领域语言工作台。
2. 平台通过 Excel 元数据自动生成 GraphQL/REST/gRPC 服务，并保持可扩展的模块化架构。
3. 后端项目 `nop-entropy` 自研 IoC、ORM、GraphQL、规则、批处理等引擎，不依赖 Spring 等外部框架。
4. 目标运行环境可与 Quarkus、Spring、Solon 等框架组合，支持 GraalVM 原生编译。
5. 研发策略强调模型驱动、声明式差量定制，并计划支持在线业务模块调整。
6. 平台现有模块覆盖配置中心、任务调度、报表、规则、批处理等，部分子系统仍在演进。
7. 研究工作采用 Run-less 方法，依托文档与配置追踪特性、依赖关系和演进状态。
8. 需建立跨模块术语、接口契约、状态管理的一致性，以支撑后续专题文档。

## 2. 术语与边界（中+英）
| 术语 | 英文 | 边界说明 |
| --- | --- | --- |
| 可逆计算 | Reversible Computation | 平台的理论基础，指导架构与语言设计，不展开具体数学推导。|
| 领域语言工作台 | Domain Language Workbench | 支持定义 DSL、生成解析器/验证器/IDE 插件的综合能力。|
| 模型驱动 | Model-Driven | 通过元数据（Excel/DSL）生成代码与文档，覆盖运行期和定制。|
| GraalVM 原生编译 | GraalVM Native Image | 借助 Quarkus/SpringNative 将应用打包为可执行文件，降低启动时延。|
| 差量定制 | Delta Customization | 以声明式方式对生成产物进行局部覆盖或扩展，避免直接修改源代码。|

## 3. 事实（Facts）
- README 明确列出平台模块列表及各自进度，显示生态覆盖面广。 
- 官方说明强调不依赖 Spring，自研 IoC/ORM/GraphQL 等核心引擎。 
- 支持与 Quarkus、Spring、Solon 集成，并可编译为原生镜像。 
- 平台定位为低代码/无代码，强调模型驱动自动生成服务与文档。 
- 研发目标包含云原生设计、分布式事务、多租户支持。 

## 4. 推测（Hypotheses，⚠️）
- ⚠️ 推测1：Excel 元数据转换流程可能由 `nop-codegen` + `nop-orm` 协作，需要进一步查阅生成器配置。验证计划：检索 `nop-codegen` 模块 README 与示例。 
- ⚠️ 推测2：差量定制机制或依赖声明式配置文件（可能在 `nop-config`/`nop-dyn`）。验证计划：查找配置目录与示例说明。 
- ⚠️ 推测3：在线业务模块调整功能由 `nop-dyn` 和前端 `nop-chaos` 协同实现。验证计划：阅读 `nop-dyn` 文档及 `nop-web` 相关描述。 

## 5. 规则（IF-THEN，可执行）
- IF 需要扩展 DSL 功能 THEN 查看 `nop-xlang` 模块的语法扩展示例并遵循模型驱动流程。 
- IF 计划生成后端服务 THEN 先准备 Excel 数据模型，再通过 `nop-codegen` 触发自动生成。 
- IF 需要部署原生镜像 THEN 选择 Quarkus 或 SpringNative 构建链并确认 JDK17+。 
- IF 想集成第三方框架 THEN 需评估平台内置 IoC/配置机制与外部框架的兼容性。 

## 6. 流程（Text-Sequence）
1. 定义领域模型（Excel/DSL）。
2. 使用代码生成器生成服务接口、配置与文档。
3. 配置差量定制文件以覆盖/扩展默认行为。
4. 选择运行框架（Quarkus/Spring/Solon）并整合所需模块。
5. 根据部署目标执行打包（Uber-Jar 或 Native Image）。
6. 通过配置中心与多租户支持部署到目标环境。

## 7. 状态机（Transition Table）
| Current State | Event | Guard | Next State | Side Effects |
| --- | --- | --- | --- | --- |
| 模型草稿 | 审核完成 | DSL/Excel 校验通过 | 模型冻结 | 生成器可读取模型版本 |
| 模型冻结 | 触发生成 | 生成器配置完整 | 工程输出 | 产出代码/配置/接口文档 |
| 工程输出 | 申请差量 | 存在定制需求 | 差量待审 | 创建差量配置草案 |
| 差量待审 | 审核通过 | 覆盖规则无冲突 | 差量生效 | 生成成果合并差量 |
| 差量生效 | 发布请求 | 运行环境准备就绪 | 发布完成 | 部署包或原生镜像可交付 |

## 8. 接口契约（I/O 表）
| 端点/工具 | 输入 | 输出 | 约束 | 错误码/异常 |
| --- | --- | --- | --- | --- |
| 代码生成器 (nop-codegen CLI) | Excel 模型、配置文件 | Java/DSL/配置产物 | 需符合模型规范、命名约定 | 配置缺失、模型验证失败 |
| GraphQL 引擎 | DSL 模型、查询请求 | GraphQL 响应 | 依赖 `nop-graphql` 模型 | Schema 校验错误、授权失败 |
| 配置中心 (nop-config) | 多租户配置、环境变量 | 运行时配置值 | 支持分布式存储 | 连接失败、配置缺失 |

## 9. 用例（Few-shot）
- 正向：Given 已完成 Excel 模型，When 运行 `nop-codegen`，Then 自动生成 GraphQL/REST 服务骨架。
- 反向：Given 模型缺少主键定义，When 触发生成，Then 生成器提示模型校验失败并终止。
- 边界：Given 需要差量定制，When 添加声明式覆盖文件，Then 差量在审核通过后替换默认实现。

## 10. 证据矩阵
| 结论 | 证据 | 置信度 | Run-less 验证计划 |
| --- | --- | --- | --- |
| 平台以可逆计算和低代码为核心定位 | README.md 介绍章节 | 高 | 交叉阅读 `docs/nop-intro.md` 比对术语描述 |
| 模块自研覆盖 IoC/ORM/GraphQL 等 | README.md 模块列表 | 中 | 抽样检查 `nop-ioc`、`nop-orm` README | 
| 支持 GraalVM 原生编译 | README.md 安装教程段落 | 中 | 查看 `nop-quarkus` 相关配置与构建脚本 |
| 差量定制能力存在 | README.md 中的“声明式差量定制”表述 | 低 | 搜索 `delta` / `customization` 关键词定位实例 |

## 11. 更新记录
| 版本 | 日期 | 内容 |
| --- | --- | --- |
| v0.1 | 2024-05-23 | 创建文档骨架，整理事实、初步推测与验证计划。 |

## 12. 检索标签（Tags）
`#nop-platform` `#低代码` `#可逆计算` `#模型驱动` `#GraalVM` `#Quarkus` `#差量定制`
