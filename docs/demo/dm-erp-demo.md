# DM8 ERP 演示库

已在本地 `localhost:25236` 的 DM8 实例创建 `ERP_DEMO` 模式，使用现有的 `localhost@25236` 连接即可访问。该模式包含 32 张业务表、60 条物理外键、313 行虚构数据及中文表/字段注释。

## 查看 ER 图

1. 在连接树刷新 `localhost@25236`，展开 `ERP_DEMO`。
2. 右键该模式下的“表”文件夹，选择“ER 图”。如果已有 ER 页签，关闭后重新打开以读取新结构。
3. 点击“显示全部连线”，再使用“适应窗口”查看全图。也可悬停或点击字段查看相关关系。
4. 在表名筛选框输入 `ORG_,SYS_,CRM_,MD_,PUR_,INV_,SAL_,FIN_,MFG_`，保留全部 32 张业务表。

DM 会为部门、员工、分类这三条自关联外键生成 9 张 `SREF_CON_TAB...` 内部辅助表。当前 JDBC 表清单也会返回这些对象，未筛选的 ER 图可能显示 41 张表；上述筛选可隐藏辅助表。无需删除这些内部表。

按当前项目的 `ErDiagramGraph` 实际组装结果，业务图共有 **64 条关系**：2 条一对一、58 条一对多、4 条多对多。多对多由关联表的外键和复合主键推导，因此图关系数比物理外键数多 4。勾选“折叠关联表”可进一步查看多对多效果。

## 业务范围

| 模块 | 表 | 关系示例 |
| --- | --- | --- |
| 组织与权限 | `ORG_*`、`SYS_*` | 公司→部门→员工；员工↔档案一对一；员工↔角色多对多；部门与主管层级 |
| 客户 | `CRM_*` | 客户经理→客户→联系人 |
| 产品 | `MD_*` | 分类与子分类、计量单位→产品 |
| 采购 | `PUR_*` | 供应商↔物料多对多；采购订单→明细→收货 |
| 库存 | `INV_*` | 公司→仓库→库位→库存；库存使用库位与产品复合主键 |
| 销售 | `SAL_*` | 客户→销售订单→明细→发货 |
| 财务 | `FIN_*` | 销售订单↔发票一对一；发票→收款；采购订单→账单→付款 |
| 生产 | `MFG_*` | 成品↔组件多对多；生产工单→领料；领料使用复合外键引用库存 |

建议先筛选 `SAL_,CRM_,FIN_` 查看销售收款链路，或筛选 `ORG_EMPLOYEE,SYS_` 查看员工角色多对多。

## SQL 脚本与验证

完整建表、外键、注释和数据位于 [dm-erp-demo.sql](dm-erp-demo.sql)。已通过 JDBC 实际执行；当前实例无需再次导入。若在另一个 DM 实例导入，使用具备建模式权限的账号，在 SQL 编辑器依次执行 UTF-8 脚本。脚本以 `SYSDBA` 作为模式所有者，只适用于尚不存在 `ERP_DEMO` 的实例，不包含删库或删表语句；DM DDL 会隐式提交。

已验证 32 张业务表共 313 行，60 条外键全部启用，JDBC 可读取 61 对外键列映射（其中一条为双列复合外键），采购和销售订单金额与明细汇总一致，中文注释可正常读取。ER 关系已使用项目当前编译的图组装逻辑核验；尚未进行浏览器视觉验收。

```sql
-- 销售订单、客户与开票金额
SELECT O.ORDER_NO, C.NAME AS CUSTOMER_NAME, O.TOTAL_AMOUNT,
       I.INVOICE_NO, I.AMOUNT AS INVOICE_AMOUNT
FROM ERP_DEMO.SAL_ORDER O
JOIN ERP_DEMO.CRM_CUSTOMER C ON C.ID = O.CUSTOMER_ID
JOIN ERP_DEMO.FIN_INVOICE I ON I.ORDER_ID = O.ID
ORDER BY O.ID;

-- 员工与角色
SELECT E.NAME AS EMPLOYEE_NAME, R.NAME AS ROLE_NAME
FROM ERP_DEMO.ORG_EMPLOYEE E
JOIN ERP_DEMO.SYS_EMPLOYEE_ROLE ER ON ER.EMPLOYEE_ID = E.ID
JOIN ERP_DEMO.SYS_ROLE R ON R.ID = ER.ROLE_ID
ORDER BY E.ID, R.ID;
```
