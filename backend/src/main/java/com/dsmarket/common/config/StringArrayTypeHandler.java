package com.dsmarket.common.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL 一维字符串数组（varchar[]）TypeHandler —— 代码库首个数组映射（C2 keywords 列）。
 * 写入：{@code conn.createArrayOf("varchar", ...)} 显式声明数组类型；读取：按 {@code rs.getArray()} 还原。
 * null 数组：置 SQL NULL（Types.ARRAY），保证 MP insert/update 全列安全。
 */
@MappedTypes(String[].class)
public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        Connection conn = ps.getConnection();
        ps.setArray(i, conn.createArrayOf("varchar", parameter));
    }

    @Override
    public void setParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType) throws SQLException {
        if (parameter == null) {
            ps.setNull(i, Types.ARRAY);
        } else {
            setNonNullParameter(ps, i, parameter, jdbcType);
        }
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toArray(rs.getArray(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toArray(rs.getArray(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toArray(cs.getArray(columnIndex));
    }

    private String[] toArray(java.sql.Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object raw = array.getArray();
        if (raw == null) {
            return null;
        }
        if (raw instanceof String[] strings) {
            return strings;
        }
        // 极端兼容：PG 可能返回其它对象数组
        Object[] objs = (Object[]) raw;
        String[] out = new String[objs.length];
        for (int i = 0; i < objs.length; i++) {
            out[i] = objs[i] == null ? null : objs[i].toString();
        }
        return out;
    }
}
