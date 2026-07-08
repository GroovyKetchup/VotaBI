package votabi.util;

import ai.webPage.utils.FormModelUtil;
import cell.cdao.IDao;
import cell.gpf.adur.data.IFormMgr;
import cmn.dto.PairDto;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import cn.hutool.core.util.StrUtil;
import gpf.adur.data.Form;
import votabi.constant.DataSourceConst;
import votabi.constant.ReportDesignConst;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DataSourceJdbcUtil {

    private DataSourceJdbcUtil() {
    }

    public static Form queryBusinessFormByCode(IDao dao, String domain, String panelCode, String formCode) throws Exception {
        String formModelId = FormModelUtil.buildPanelFormModelIdByCmName(domain, panelCode);
        return IFormMgr.get().queryFormByCode(dao, formModelId, formCode);
    }

    public static ReportJdbcDataSource buildDataSource(Form connRow, String panelCode, String databaseId) throws Exception {
        String url = connRow.getString(ReportDesignConst.FieldName_ConnectionUrl);
        String user = connRow.getString(ReportDesignConst.FieldName_UserName);
        gpf.adur.data.Password passwordObj = connRow.getPassword(ReportDesignConst.FieldName_Password);
        String pwd = (passwordObj != null) ? passwordObj.getValue() : "";
        String driver = connRow.getString(ReportDesignConst.FieldName_DbDriver);
        String dbType = connRow.getString(ReportDesignConst.FieldName_DbType);
        return ReportJdbcDataSource.of(panelCode, databaseId, url, user, pwd, driver, dbType);
    }

    public static List<String> listSchemas(ReportJdbcDataSource ds) throws Exception {
        try (Connection conn = openConnection(ds)) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> schemas = new LinkedHashSet<>();
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (StrUtil.isNotBlank(name)) schemas.add(name);
                }
            }
            if (schemas.isEmpty()) {
                try (ResultSet rs = meta.getCatalogs()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if (StrUtil.isNotBlank(name)) schemas.add(name);
                    }
                }
            }
            return new ArrayList<>(schemas);
        }
    }

    public static Map<String, Object> listTables(ReportJdbcDataSource ds, String schemaName, String keyword,
                                                 Integer pageNo, Integer pageSize) throws Exception {
        pageNo = defaultPageNo(pageNo);
        pageSize = defaultPageSize(pageSize);
        List<Map<String, Object>> all = new ArrayList<>();
        String kw = StrUtil.blankToDefault(keyword, "").toLowerCase(Locale.ROOT);

        try (Connection conn = openConnection(ds)) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> schemas = readSchemas(meta);
            try (ResultSet rs = meta.getTables(null, schemaName, null, new String[]{"TABLE"})) {
                addTables(rs, kw, all);
            }
            if (all.isEmpty() && !schemas.contains(schemaName)) {
                try (ResultSet rs = meta.getTables(schemaName, null, null, new String[]{"TABLE"})) {
                    addTables(rs, kw, all);
                }
            }
        }

        int total = all.size();
        int from = Math.min((pageNo - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(DataSourceConst.ResultKey_List, all.subList(from, to));
        result.put(DataSourceConst.ResultKey_TotalSize, total);
        return result;
    }

    private static Set<String> readSchemas(DatabaseMetaData meta) throws Exception {
        Set<String> schemas = new LinkedHashSet<>();
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (StrUtil.isNotBlank(name)) schemas.add(name);
            }
        }
        return schemas;
    }

    private static void addTables(ResultSet rs, String keyword, List<Map<String, Object>> rows) throws Exception {
        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            if (StrUtil.isBlank(tableName)) continue;
            if (StrUtil.isNotBlank(keyword) && !tableName.toLowerCase(Locale.ROOT).contains(keyword)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(DataSourceConst.FieldName_SourceTableName, tableName);
            rows.add(row);
        }
    }

    public static String buildSourceTableSql(String schemaName, String sourceTableName) {
        assertIdentifier(schemaName, DataSourceConst.FieldName_SchemaName);
        assertIdentifier(sourceTableName, DataSourceConst.FieldName_SourceTableName);
        return "SELECT * FROM " + schemaName + "." + sourceTableName;
    }

    public static Map<String, Object> buildFieldResult(List<JdbcMetaInfoDto> metas) {
        List<Map<String, Object>> fields = buildFieldList(metas);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(DataSourceConst.ResultKey_List, fields);
        result.put(DataSourceConst.ResultKey_TotalSize, fields.size());
        return result;
    }

    public static List<Map<String, Object>> buildFieldList(List<JdbcMetaInfoDto> metas) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (metas == null) return fields;
        for (JdbcMetaInfoDto meta : metas) {
            if (meta == null) continue;
            String name = meta.getName();
            String typeName = meta.getTypeName();
            String dataType = mapFieldType(meta.getType(), typeName);
            Map<String, Object> field = new LinkedHashMap<>();
            field.put(DataSourceConst.FieldConfigKey_DataName, name);
            field.put(DataSourceConst.FieldConfigKey_Alias, meta.getLabel());
            field.put(DataSourceConst.FieldConfigKey_DataType, dataType);
            field.put(DataSourceConst.FieldConfigKey_Role,
                    DataSourceConst.DataType_Number.equals(dataType)
                            ? DataSourceConst.Role_Measure
                            : DataSourceConst.Role_Dimension);
            field.put(DataSourceConst.FieldConfigKey_DateFormat,
                    DataSourceConst.DataType_Date.equals(dataType) ? DataSourceConst.DefaultDateFormat : "");
            field.put(DataSourceConst.FieldConfigKey_Primary, false);
            field.put(DataSourceConst.FieldConfigKey_Sort, "");
            field.put(DataSourceConst.FieldConfigKey_Category, "");
            fields.add(field);
        }
        return fields;
    }

    public static Map<String, Object> buildQueryListResult(PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair) {
        return buildQueryListResult(pair, pair == null || pair.getRight() == null ? 0 : pair.getRight().size());
    }

    public static Map<String, Object> buildQueryListResult(PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair,
                                                           int totalSize) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<JdbcMetaInfoDto> metas = pair == null ? null : pair.getLeft();
        List<List<String>> data = pair == null ? null : pair.getRight();
        List<String> columnNames = new ArrayList<>();
        if (metas != null) {
            for (JdbcMetaInfoDto meta : metas) {
                if (meta != null) columnNames.add(meta.getName());
            }
        }
        if (data != null) {
            for (List<String> dataRow : data) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    row.put(columnNames.get(i), dataRow != null && i < dataRow.size() ? dataRow.get(i) : null);
                }
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(DataSourceConst.ResultKey_List, rows);
        result.put(DataSourceConst.ResultKey_TotalSize, totalSize);
        return result;
    }

    public static int readCount(PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair) {
        if (pair == null || pair.getRight() == null || pair.getRight().isEmpty()) return 0;
        List<String> firstRow = pair.getRight().get(0);
        if (firstRow == null || firstRow.isEmpty() || StrUtil.isBlank(firstRow.get(0))) return 0;
        return Integer.parseInt(firstRow.get(0));
    }

    public static String mapFieldType(int sqlType, String typeName) {
        switch (sqlType) {
            case Types.BIT:
            case Types.BOOLEAN:
                return DataSourceConst.DataType_Boolean;
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return DataSourceConst.DataType_Number;
            case Types.DATE:
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return DataSourceConst.DataType_Date;
            case Types.CHAR:
            case Types.NCHAR:
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return DataSourceConst.DataType_String;
            default:
                return mapFieldType(typeName);
        }
    }

    public static String mapFieldType(String typeName) {
        String type = StrUtil.blankToDefault(typeName, "").toUpperCase(Locale.ROOT);
        if (type.contains("BOOL")) return DataSourceConst.DataType_Boolean;
        if (type.contains("DATE") || type.contains("TIME")) return DataSourceConst.DataType_Date;
        if (type.contains("INT") || type.contains("DECIMAL") || type.contains("NUMERIC")
                || type.contains("NUMBER") || type.contains("DOUBLE") || type.contains("FLOAT")
                || type.contains("REAL") || type.contains("MONEY")) {
            return DataSourceConst.DataType_Number;
        }
        return DataSourceConst.DataType_String;
    }

    public static int defaultPageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? DataSourceConst.DefaultPageNo : pageNo;
    }

    public static int defaultPageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? DataSourceConst.DefaultPageSize : pageSize;
    }

    private static Connection openConnection(ReportJdbcDataSource ds) throws Exception {
        if (StrUtil.isNotBlank(ds.getDriverClass())) Class.forName(ds.getDriverClass());
        return DriverManager.getConnection(ds.getUrl(), ds.getUserName(), ds.getPassword());
    }

    private static void assertIdentifier(String value, String fieldName) {
        if (StrUtil.isBlank(value)) throw new RuntimeException(fieldName + "不能为空");
        if (!value.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new RuntimeException(fieldName + "不是合法数据库标识符：" + value);
        }
    }
}
