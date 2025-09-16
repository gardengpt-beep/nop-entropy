# ERP Spring Project

该示例在离线环境中复现 Nop“模型驱动”的核心思路：业务语义依然保存在
`src/main/resources/_vfs/com/company/erp` 目录下的 ORM 与 Biz 元数据文件，
运行时代码只负责按模型解释并执行 CRUD 逻辑。

## 关键实现

- `runtime/metadata` 下的加载器读取 `app.orm.xml` 与 `ErpProduct.xbiz`，提取字段约束、
  默认值以及 `active_findPage` 查询的过滤条件；
- `ModelDrivenErpEngine` 使用这些元数据在内存中校验、生成主键并维护产品数据；
- `run-tests.sh` 提供了无需 Maven 的测试入口，直接编译并执行模型驱动的 CRUD 流程，
  在完全离线的环境中也能验证逻辑正确性。

## 运行测试

```bash
cd erp-spring-project
./run-tests.sh
```

脚本会执行与此前 REST 集成测试相同的流程：保存商品、按 ID 查询以及执行
`active_findPage` 查询，确保模型驱动逻辑生效。

## 手动演示

构建完成后可直接运行入口类了解加载情况：

```bash
./run-tests.sh   # 先编译生成 target/classes
java -cp target/classes com.company.erp.ErpSpringProjectApplication
```

控制台会输出当前根据模型加载的实体字段列表，方便在没有外部依赖的环境中快速验证。
