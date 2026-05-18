# MySQL / SQL 规范

1. 禁止使用 `SELECT *`，必须明确字段。
2. 涉及用户输入的 SQL 必须使用参数绑定，禁止字符串拼接。
3. `UPDATE` 和 `DELETE` 必须带 `WHERE` 条件。
4. 分页查询必须限制 `pageSize` 上限。
5. 高频查询字段要考虑索引和查询模式。
