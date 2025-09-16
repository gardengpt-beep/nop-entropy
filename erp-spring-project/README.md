# ERP Spring Project

本模块按照提供的 Maven POM 搭建 Spring Boot + Nop 的运行骨架，完整保留了 Nop 平台倡导的“模型驱动”思想。
所有业务语义都通过 `_vfs/com/company/erp` 目录下的模型文件声明完成，而不是手写控制器与服务类：

- `orm/app.orm.xml` 定义了 `ErpProduct` 实体结构、字段约束以及产品状态字典；
- `model/ErpProduct/_ErpProduct.xmeta` 描述实体的元数据、校验与展示属性，自动生成 GraphQL 类型；
- `model/ErpProduct/_ErpProduct.xbiz` 通过 `graphql:base="crud"` 暴露标准增删改查操作，`ErpProduct.xbiz` 额外提供了按状态过滤的 `active_findPage` 查询。

借助这些模型，Nop 会在运行期自动装配 GraphQL/REST 服务、DAO 以及数据校验逻辑，无需再手写样板代码。

## 运行方式

```bash
cd erp-spring-project
mvn spring-boot:run
```

应用默认使用 H2 内存库，并开启 `nop.orm.init-database-schema=true` 自动建表，启动后即可通过 `/actuator/health` 检查健康状态。

## 示例调用

`/r/{BizObjName}__{Action}` 接口是 Nop GraphQL 的 REST 语法糖，下列示例展示了如何创建并查询商品：

```bash
# 新建商品（返回字段在 @selection 中声明）
curl -X POST 'http://localhost:8080/r/ErpProduct__save?@selection=productId,sku,name,status' \
  -H 'Content-Type: application/json' \
  -d '{"data":{"sku":"SKU-1001","name":"演示商品","category":"办公","unitPrice":199.00,"stockQuantity":50}}'

# 查询在售商品列表（使用模型里定义的 active_findPage 查询）
curl -X POST 'http://localhost:8080/r/ErpProduct__active_findPage?@selection=total,items{productId,sku,name,status}' \
  -H 'Content-Type: application/json' \
  -d '{"query":{"limit":20}}'
```

所有字段约束、字典与默认值均来自模型定义，可继续通过 Excel/ORM 模型扩展更多 ERP 场景。
