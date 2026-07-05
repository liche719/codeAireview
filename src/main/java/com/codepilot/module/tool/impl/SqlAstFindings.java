package com.codepilot.module.tool.impl;

record SqlAstFindings(boolean parsed, boolean selectAll, boolean updateWithoutWhere, boolean deleteWithoutWhere) {

    static SqlAstFindings parsed(boolean selectAll, boolean updateWithoutWhere, boolean deleteWithoutWhere) {
        return new SqlAstFindings(true, selectAll, updateWithoutWhere, deleteWithoutWhere);
    }

    static SqlAstFindings notParsed() {
        return new SqlAstFindings(false, false, false, false);
    }

    SqlAstFindings merge(SqlAstFindings other) {
        return new SqlAstFindings(
                parsed || other.parsed,
                selectAll || other.selectAll,
                updateWithoutWhere || other.updateWithoutWhere,
                deleteWithoutWhere || other.deleteWithoutWhere
        );
    }
}
