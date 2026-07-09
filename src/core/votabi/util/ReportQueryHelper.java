package votabi.util;

import ai.webPage.dto.OrderByItem;
import cmn.anotation.ClassDeclare;
import cmn.dto.PairDto;
import cmn.dto.SqlStatementDto;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import cmn.enums.sql.DBTypeEnum;
import cn.hutool.core.util.StrUtil;
import org.nutz.dao.entity.annotation.Comment;
import votabi.constant.DataSourceConst;
import votabi.constant.ReportDesignConst;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Comment("Report runtime query helper")
@ClassDeclare(
        label = "Report runtime query helper",
        what = "Build parameterized SQL statements and normalize query results",
        why = "Keep report JDBC query logic testable without BAP runtime",
        how = "Static helper methods",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-07-08"
)
public final class ReportQueryHelper {

    private static final Pattern SQL_PARAM_PATTERN = Pattern.compile("\\$(\\w+)");
    private static final Pattern SQL_FRAGMENT_PATTERN = Pattern.compile("@(\\w+)");
    private static final Pattern PLATFORM_MACRO_PATTERN = Pattern.compile("\\$(C|WT|WF):[^$]+\\$");

    private ReportQueryHelper() {
    }

    public static SqlStatementDto buildSqlStatement(String sqlTemplate, Map<String, Object> params,
                                                    Integer pageNo, Integer pageSize) {
        if (StrUtil.isBlank(sqlTemplate)) {
            throw new IllegalArgumentException("SQL template can not be empty");
        }
        Map<String, Object> paramMap = (params == null) ? new HashMap<>() : new HashMap<>(params);
        validateSqlParams(sqlTemplate, paramMap);

        String statement = stripTrailingSemicolon(sqlTemplate);
        if (pageNo != null && pageSize != null && pageNo >= 1 && pageSize >= 1) {
            int offset = (pageNo - 1) * pageSize;
            statement = statement + " LIMIT " + ReportDesignConst.SqlParamPrefix + "__limit"
                    + " OFFSET " + ReportDesignConst.SqlParamPrefix + "__offset";
            paramMap.put("__limit", pageSize);
            paramMap.put("__offset", offset);
        }
        return new SqlStatementDto(statement).setParamMap(paramMap);
    }

    public static SqlStatementDto buildCountSqlStatement(String sqlTemplate, Map<String, Object> params) {
        return buildCountSqlStatement(sqlTemplate, params, DBTypeEnum.MySQL);
    }

    public static SqlStatementDto buildCountSqlStatement(String sqlTemplate, Map<String, Object> params,
                                                         DBTypeEnum dbType) {
        if (StrUtil.isBlank(sqlTemplate)) {
            throw new IllegalArgumentException("SQL template can not be empty");
        }
        Map<String, Object> paramMap = (params == null) ? new HashMap<>() : new HashMap<>(params);
        validateSqlParams(sqlTemplate, paramMap);
        return new SqlStatementDto(dialect(dbType).countSql(stripTrailingSemicolon(sqlTemplate))).setParamMap(paramMap);
    }

    public static SqlStatementDto buildProbeSqlStatement(String sqlTemplate, Map<String, Object> params,
                                                         DBTypeEnum dbType) {
        if (StrUtil.isBlank(sqlTemplate)) {
            throw new IllegalArgumentException("SQL template can not be empty");
        }
        SqlDialect dialect = dialect(dbType);
        Map<String, Object> paramMap = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        String baseSql = "SELECT * FROM (" + stripTrailingSemicolon(sqlTemplate) + ") _ds";
        String statement = dbType == DBTypeEnum.SQLServer
                ? "SELECT TOP 1 * FROM (" + stripTrailingSemicolon(sqlTemplate) + ") _ds"
                : dialect.pageSql(baseSql, 1, 1, paramMap);
        validateSqlParams(statement, paramMap);
        return new SqlStatementDto(statement).setParamMap(paramMap);
    }

    public static QueryPlan buildConsumerQueryPlan(String sqlTemplate,
                                                   Map<String, Object> params,
                                                   List<Map<String, Object>> fieldConfigs,
                                                   String keyword,
                                                   Map<String, Object> condition,
                                                   Map<String, Object> advancedConditions,
                                                   List<?> orderBy,
                                                   DBTypeEnum dbType,
                                                   Integer pageNo,
                                                   Integer pageSize) {
        if (StrUtil.isBlank(sqlTemplate)) {
            throw new IllegalArgumentException("SQL template can not be empty");
        }
        SqlDialect dialect = dialect(dbType);
        Map<String, Object> paramMap = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        Map<String, FieldRef> fieldMap = buildFieldWhitelist(fieldConfigs);

        String filteredSql = buildFilteredSql(stripTrailingSemicolon(sqlTemplate), paramMap, fieldMap, dialect,
                keyword, condition, advancedConditions);
        Map<String, Object> countParamMap = new LinkedHashMap<>(paramMap);
        String orderedSql = appendOrderBy(filteredSql, fieldMap, dialect, orderBy, dbType == DBTypeEnum.SQLServer);
        String pageSql = dialect.pageSql(orderedSql, defaultPageNo(pageNo), defaultPageSize(pageSize), paramMap);
        String countSql = dialect.countSql(filteredSql);

        validateSqlParams(countSql, countParamMap);
        validateSqlParams(pageSql, paramMap);
        return new QueryPlan(new SqlStatementDto(countSql).setParamMap(countParamMap),
                new SqlStatementDto(pageSql).setParamMap(paramMap));
    }

    public static Set<String> extractSqlParamNames(String querySql) {
        if (StrUtil.isBlank(querySql)) return new LinkedHashSet<>();
        rejectUnsupportedSqlPlaceholders(querySql);
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SQL_PARAM_PATTERN.matcher(querySql);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!"__limit".equals(name) && !"__offset".equals(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public static void validateSqlParams(String querySql, Map<String, Object> params) {
        Set<String> names = extractSqlParamNames(querySql);
        Map<String, Object> safeParams = params == null ? new HashMap<>() : params;
        for (String name : names) {
            if (!safeParams.containsKey(name)) {
                throw new RuntimeException("SQL parameter [" + name + "] is missing");
            }
            Object value = safeParams.get(name);
            if (value == null) {
                throw new RuntimeException("SQL parameter [" + name + "] can not be null");
            }
            if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
                throw new RuntimeException("SQL parameter [" + name + "] collection can not be empty");
            }
            if (value.getClass().isArray() && Array.getLength(value) == 0) {
                throw new RuntimeException("SQL parameter [" + name + "] array can not be empty");
            }
        }
    }

    public static String buildSourceTableSql(String schemaName, String sourceTableName, DBTypeEnum dbType) {
        return "SELECT * FROM " + dialect(dbType).quoteTable(schemaName, sourceTableName);
    }

    private static void rejectUnsupportedSqlPlaceholders(String querySql) {
        Matcher macroMatcher = PLATFORM_MACRO_PATTERN.matcher(querySql);
        if (macroMatcher.find()) {
            throw new RuntimeException("Unsupported platform SQL macro: " + macroMatcher.group());
        }
        Matcher fragmentMatcher = SQL_FRAGMENT_PATTERN.matcher(querySql);
        if (fragmentMatcher.find()) {
            throw new RuntimeException("Unsupported SQL fragment variable: @" + fragmentMatcher.group(1));
        }
    }

    private static String stripTrailingSemicolon(String querySql) {
        return querySql.replaceAll(";+\\s*$", "").trim();
    }

    private static String buildFilteredSql(String baseSql, Map<String, Object> paramMap,
                                           Map<String, FieldRef> fieldMap, SqlDialect dialect,
                                           String keyword,
                                           Map<String, Object> condition,
                                           Map<String, Object> advancedConditions) {
        List<String> clauses = new ArrayList<>();
        appendKeywordClause(clauses, paramMap, fieldMap, dialect, keyword);
        appendConditionClauses(clauses, paramMap, fieldMap, dialect, condition);
        appendAdvancedClauses(clauses, paramMap, fieldMap, dialect, advancedConditions);
        if (clauses.isEmpty()) return "SELECT * FROM (" + baseSql + ") _ds";
        return "SELECT * FROM (" + baseSql + ") _ds WHERE " + String.join(" AND ", clauses);
    }

    private static void appendKeywordClause(List<String> clauses, Map<String, Object> paramMap,
                                            Map<String, FieldRef> fieldMap, SqlDialect dialect, String keyword) {
        if (StrUtil.isBlank(keyword) || fieldMap.isEmpty()) return;
        List<String> items = new ArrayList<>();
        String textParam = nextFilterParam(paramMap);
        paramMap.put(textParam, "%" + keyword.trim() + "%");
        Object numberValue = parseKeywordNumber(keyword);
        String numberParam = null;
        for (FieldRef field : new LinkedHashSet<>(fieldMap.values())) {
            String column = dialect.quoteIdentifier(field.name);
            if (DataSourceConst.DataType_String.equals(field.dataType)) {
                items.add(dialect.textExpression(column) + " LIKE $" + textParam);
            } else if (DataSourceConst.DataType_Number.equals(field.dataType) && numberValue != null) {
                if (numberParam == null) {
                    numberParam = nextFilterParam(paramMap);
                    paramMap.put(numberParam, numberValue);
                }
                items.add(column + " = $" + numberParam);
            }
        }
        if (!items.isEmpty()) clauses.add("(" + String.join(" OR ", items) + ")");
    }

    private static void appendConditionClauses(List<String> clauses, Map<String, Object> paramMap,
                                               Map<String, FieldRef> fieldMap, SqlDialect dialect,
                                               Map<String, Object> condition) {
        if (condition == null || condition.isEmpty()) return;
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            FieldRef field = resolveField(fieldMap, entry.getKey());
            String column = dialect.quoteIdentifier(field.name);
            Object value = entry.getValue();
            if (value == null) continue;
            String param = nextFilterParam(paramMap);
            if (DataSourceConst.DataType_String.equals(field.dataType) && value instanceof String) {
                String text = ((String) value).trim();
                if (StrUtil.isBlank(text)) {
                    paramMap.put(param, "");
                    clauses.add("(" + dialect.textExpression(column) + " = $" + param + " OR " + column + " IS NULL)");
                } else {
                    paramMap.put(param, "%" + text + "%");
                    clauses.add(dialect.textExpression(column) + " LIKE $" + param);
                }
            } else {
                paramMap.put(param, convertFilterValue(field, value));
                clauses.add(column + " = $" + param);
            }
        }
    }

    private static void appendAdvancedClauses(List<String> clauses, Map<String, Object> paramMap,
                                              Map<String, FieldRef> fieldMap, SqlDialect dialect,
                                              Map<String, Object> advancedConditions) {
        if (advancedConditions == null || advancedConditions.isEmpty()) return;
        for (Map.Entry<String, Object> entry : advancedConditions.entrySet()) {
            FieldRef field = resolveField(fieldMap, entry.getKey());
            String column = dialect.quoteIdentifier(field.name);
            Object rawOps = entry.getValue();
            if (!(rawOps instanceof Map)) continue;
            Map<?, ?> ops = (Map<?, ?>) rawOps;
            for (Map.Entry<?, ?> opEntry : ops.entrySet()) {
                String op = String.valueOf(opEntry.getKey());
                Object value = opEntry.getValue();
                if ("isNull".equals(op)) {
                    clauses.add(Boolean.TRUE.equals(value) ? column + " IS NULL" : column + " IS NOT NULL");
                    continue;
                }
                if ("isNotNull".equals(op)) {
                    clauses.add(column + " IS NOT NULL");
                    continue;
                }
                if (value == null) {
                    appendNullAdvancedClause(clauses, column, op);
                    continue;
                }
                String param = nextFilterParam(paramMap);
                if ("in".equals(op) || "notIn".equals(op)) {
                    assertNotEmptyArrayLike(entry.getKey(), value);
                }
                Object bindValue = convertAdvancedValue(field, op, value);
                paramMap.put(param, bindValue);
                String expression = isLikeOperator(op) ? dialect.textExpression(column) : column;
                clauses.add(expression + " " + sqlOperator(op) + " $" + param);
            }
        }
    }

    private static void appendNullAdvancedClause(List<String> clauses, String column, String op) {
        if ("equals".equals(op) || "eq".equals(op)) {
            clauses.add(column + " IS NULL");
            return;
        }
        if ("not".equals(op) || "ne".equals(op)) {
            clauses.add(column + " IS NOT NULL");
            return;
        }
        throw new RuntimeException("Advanced condition operator [" + op + "] requires value");
    }

    private static String appendOrderBy(String sql, Map<String, FieldRef> fieldMap, SqlDialect dialect,
                                        List<?> orderBy, boolean requireOrder) {
        List<String> items = new ArrayList<>();
        if (orderBy != null) {
            for (Object item : orderBy) {
                String fieldName = orderFieldName(item);
                if (StrUtil.isBlank(fieldName)) continue;
                String order = StrUtil.blankToDefault(orderDirection(item), "asc").toLowerCase();
                if (!"asc".equals(order) && !"desc".equals(order)) {
                    throw new RuntimeException("Invalid order: " + orderDirection(item));
                }
                items.add(dialect.quoteIdentifier(resolveField(fieldMap, fieldName).name) + " " + order.toUpperCase());
            }
        }
        if (items.isEmpty() && requireOrder) {
            if (fieldMap.isEmpty()) throw new RuntimeException("SQLServer page query requires orderBy or field config");
            items.add(dialect.quoteIdentifier(fieldMap.values().iterator().next().name) + " ASC");
        }
        return items.isEmpty() ? sql : sql + " ORDER BY " + String.join(", ", items);
    }

    private static String orderFieldName(Object item) {
        if (item instanceof OrderByItem) return ((OrderByItem) item).getFieldName();
        if (item instanceof Map) return stringValue(((Map<?, ?>) item).get("fieldName"));
        return null;
    }

    private static String orderDirection(Object item) {
        if (item instanceof OrderByItem) return ((OrderByItem) item).getOrder();
        if (item instanceof Map) return stringValue(((Map<?, ?>) item).get("order"));
        return null;
    }

    private static Map<String, FieldRef> buildFieldWhitelist(List<Map<String, Object>> fieldConfigs) {
        Map<String, FieldRef> fields = new LinkedHashMap<>();
        if (fieldConfigs == null) return fields;
        for (Map<String, Object> field : fieldConfigs) {
            if (field == null) continue;
            String dataName = stringValue(field.get(DataSourceConst.FieldConfigKey_DataName));
            String alias = stringValue(field.get(DataSourceConst.FieldConfigKey_Alias));
            String dataType = stringValue(field.get(DataSourceConst.FieldConfigKey_DataType));
            String dateFormat = stringValue(field.get(DataSourceConst.FieldConfigKey_DateFormat));
            if (StrUtil.isNotBlank(dataName)) {
                FieldRef ref = new FieldRef(dataName, dataType, dateFormat);
                fields.put(dataName, ref);
                if (StrUtil.isNotBlank(alias)) fields.put(alias, ref);
            }
        }
        return fields;
    }

    private static FieldRef resolveField(Map<String, FieldRef> fieldMap, Object fieldName) {
        String key = stringValue(fieldName);
        FieldRef field = fieldMap.get(key);
        if (field == null || StrUtil.isBlank(field.name)) throw new RuntimeException("Unknown query field: " + key);
        return field;
    }

    private static Object convertFilterValue(FieldRef field, Object value) {
        if (value == null) return null;
        if (value instanceof Collection) {
            List<Object> values = new ArrayList<>();
            for (Object item : (Collection<?>) value) values.add(convertSingleFilterValue(field, item));
            return values;
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) values.add(convertSingleFilterValue(field, Array.get(value, i)));
            return values;
        }
        return convertSingleFilterValue(field, value);
    }

    private static Object convertAdvancedValue(FieldRef field, String op, Object value) {
        if ("contains".equals(op)) return "%" + stringValue(value) + "%";
        if ("startsWith".equals(op)) return stringValue(value) + "%";
        if ("endsWith".equals(op)) return "%" + stringValue(value);
        return convertFilterValue(field, value);
    }

    private static Object convertSingleFilterValue(FieldRef field, Object value) {
        if (value == null) return null;
        if (DataSourceConst.DataType_Number.equals(field.dataType)) {
            if (value instanceof Number) return value;
            String text = stringValue(value).trim();
            if (StrUtil.isBlank(text)) throw new RuntimeException("Query field [" + field.name + "] requires number");
            try {
                return new BigDecimal(text);
            } catch (Exception e) {
                throw new RuntimeException("Query field [" + field.name + "] requires number: " + value);
            }
        }
        if (DataSourceConst.DataType_Boolean.equals(field.dataType)) {
            if (value instanceof Boolean) return value;
            String text = stringValue(value).trim().toLowerCase();
            if ("true".equals(text) || "1".equals(text)) return true;
            if ("false".equals(text) || "0".equals(text)) return false;
            throw new RuntimeException("Query field [" + field.name + "] requires boolean: " + value);
        }
        if (DataSourceConst.DataType_Date.equals(field.dataType) && value instanceof String) {
            String text = ((String) value).trim();
            if (StrUtil.isBlank(text)) throw new RuntimeException("Query field [" + field.name + "] requires date");
            try {
                String pattern = StrUtil.blankToDefault(field.dateFormat, DataSourceConst.DefaultDateFormat);
                return new Timestamp(new SimpleDateFormat(pattern).parse(text).getTime());
            } catch (Exception e) {
                throw new RuntimeException("Query field [" + field.name + "] requires date: " + value);
            }
        }
        return value;
    }

    private static BigDecimal parseKeywordNumber(String keyword) {
        try {
            return new BigDecimal(keyword.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String sqlOperator(String op) {
        if ("equals".equals(op) || "eq".equals(op)) return "=";
        if ("not".equals(op) || "ne".equals(op)) return "<>";
        if ("contains".equals(op) || "startsWith".equals(op) || "endsWith".equals(op)) return "LIKE";
        if ("gte".equals(op)) return ">=";
        if ("lte".equals(op)) return "<=";
        if ("gt".equals(op)) return ">";
        if ("lt".equals(op)) return "<";
        if ("in".equals(op)) return "IN";
        if ("notIn".equals(op)) return "NOT IN";
        throw new RuntimeException("Unsupported advanced condition operator: " + op);
    }

    private static boolean isLikeOperator(String op) {
        return "contains".equals(op) || "startsWith".equals(op) || "endsWith".equals(op);
    }

    private static void assertNotEmptyArrayLike(Object fieldName, Object value) {
        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
            throw new RuntimeException("Query field [" + fieldName + "] collection can not be empty");
        }
        if (value.getClass().isArray() && Array.getLength(value) == 0) {
            throw new RuntimeException("Query field [" + fieldName + "] array can not be empty");
        }
    }

    private static String nextFilterParam(Map<String, Object> paramMap) {
        int i = 0;
        String key;
        do {
            key = "__filter_" + i++;
        } while (paramMap.containsKey(key));
        return key;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int defaultPageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    private static int defaultPageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : pageSize;
    }

    private static SqlDialect dialect(DBTypeEnum dbType) {
        if (dbType == DBTypeEnum.MySQL || dbType == DBTypeEnum.MySQL8) {
            return new SqlDialect("`", "`", "mysql", "CAST(%s AS CHAR)");
        }
        if (dbType == DBTypeEnum.PostgreSQL) {
            return new SqlDialect("\"", "\"", "ansi", "CAST(%s AS TEXT)");
        }
        if (dbType == DBTypeEnum.Oracle) {
            return new SqlDialect("\"", "\"", "ansi", "TO_CHAR(%s)");
        }
        if (dbType == DBTypeEnum.SQLServer) {
            return new SqlDialect("[", "]", "sqlserver", "CAST(%s AS NVARCHAR(MAX))");
        }
        throw new RuntimeException("Unsupported database type: " + dbType);
    }

    public static final class QueryPlan {
        private final SqlStatementDto countStatement;
        private final SqlStatementDto pageStatement;

        private QueryPlan(SqlStatementDto countStatement, SqlStatementDto pageStatement) {
            this.countStatement = countStatement;
            this.pageStatement = pageStatement;
        }

        public SqlStatementDto getCountStatement() {
            return countStatement;
        }

        public SqlStatementDto getPageStatement() {
            return pageStatement;
        }
    }

    private static final class FieldRef {
        private final String name;
        private final String dataType;
        private final String dateFormat;

        private FieldRef(String name, String dataType, String dateFormat) {
            this.name = name;
            this.dataType = dataType;
            this.dateFormat = dateFormat;
        }
    }

    private static final class SqlDialect {
        private final String quoteStart;
        private final String quoteEnd;
        private final String paging;
        private final String textExpressionPattern;

        private SqlDialect(String quoteStart, String quoteEnd, String paging, String textExpressionPattern) {
            this.quoteStart = quoteStart;
            this.quoteEnd = quoteEnd;
            this.paging = paging;
            this.textExpressionPattern = textExpressionPattern;
        }

        private String quoteIdentifier(String name) {
            if (StrUtil.isBlank(name)) throw new RuntimeException("Identifier can not be empty");
            return quoteStart + name.replace(quoteEnd, quoteEnd + quoteEnd) + quoteEnd;
        }

        private String quoteTable(String schemaName, String tableName) {
            if (StrUtil.isBlank(schemaName)) return quoteIdentifier(tableName);
            return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
        }

        private String textExpression(String column) {
            return String.format(textExpressionPattern, column);
        }

        private String countSql(String sql) {
            return "SELECT COUNT(*) FROM (" + sql + ") _votabi_count";
        }

        private String pageSql(String sql, int pageNo, int pageSize, Map<String, Object> paramMap) {
            int offset = (pageNo - 1) * pageSize;
            paramMap.put("__limit", pageSize);
            paramMap.put("__offset", offset);
            if ("sqlserver".equals(paging) || "ansi".equals(paging)) {
                return sql + " OFFSET $__offset ROWS FETCH NEXT $__limit ROWS ONLY";
            }
            return sql + " LIMIT $__limit OFFSET $__offset";
        }
    }

    public static Map<String, Object> buildQueryResult(PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair) {
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        List<JdbcMetaInfoDto> metas = (pair == null) ? null : pair.getLeft();
        List<List<String>> data = (pair == null) ? null : pair.getRight();

        List<String> columnNames = new ArrayList<>();
        if (metas != null) {
            for (JdbcMetaInfoDto meta : metas) {
                if (meta == null) continue;
                columnNames.add(meta.getName());
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", meta.getName());
                col.put("label", meta.getLabel());
                col.put("typeName", meta.getTypeName());
                columns.add(col);
            }
        }

        if (data != null) {
            for (List<String> dataRow : data) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    String value = (dataRow != null && i < dataRow.size()) ? dataRow.get(i) : null;
                    row.put(columnNames.get(i), value);
                }
                rows.add(row);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ReportDesignConst.ResultKey_Columns, columns);
        result.put(ReportDesignConst.ResultKey_Rows, rows);
        result.put(ReportDesignConst.ResultKey_Total, rows.size());
        return result;
    }
}
