package votabi.util;

import cmn.dto.PairDto;
import cmn.dto.SqlStatementDto;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import cn.hutool.core.util.StrUtil;
import cmn.anotation.ClassDeclare;
import org.nutz.dao.entity.annotation.Comment;
import votabi.constant.ReportDesignConst;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表运行态取数的纯函数辅助类（不依赖 BAP 运行时，便于单元测试）。
 *
 * <p>负责两件事：</p>
 * <ul>
 *   <li>{@link #buildSqlStatement(String, Map, Integer, Integer)}：把 SQL 模板 + 运行态参数 + 分页
 *   组装成 {@link SqlStatementDto}；运行态筛选值一律走 {@code setParamMap} 形成 {@code ?} 绑定，
 *   <b>绝不</b>拼进 SQL 文本，集合/数组自动展开为 IN 列表。</li>
 *   <li>{@link #buildQueryResult(PairDto)}：把 {@code IJDBCService} 返回的“表头 + 二维表数据”
 *   规整为 {@code {columns, rows, total}}，供前端 {@code normalize} 消费。</li>
 * </ul>
 *
 * @author Devin
 * @version 1.0
 */
@Comment("报表运行态取数纯函数辅助")
@ClassDeclare(
        label = "报表运行态取数纯函数辅助",
        what = "组装 SqlStatementDto 与规整查询结果",
        why = "真参数化（不拼 SQL）且可脱离 BAP 运行时单测",
        how = "全部静态纯函数",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-06-25"
)
public final class ReportQueryHelper {

    private static final Pattern SQL_PARAM_PATTERN = Pattern.compile("\\$(\\w+)");
    private static final Pattern SQL_FRAGMENT_PATTERN = Pattern.compile("@(\\w+)");
    private static final Pattern PLATFORM_MACRO_PATTERN = Pattern.compile("\\$(C|WT|WF):[^$]+\\$");

    private ReportQueryHelper() {
    }

    /**
     * 组装真参数化 SQL 语句对象。
     *
     * <p>占位符约定 {@code $name}（见 {@link ReportDesignConst#SqlParamPrefix}）。基础分页：当
     * {@code pageNo>=1 && pageSize>=1} 时追加 {@code LIMIT $__limit OFFSET $__offset}，分页值同样以
     * 绑定参数下发（不拼字面量）。本轮仅基础 LIMIT/OFFSET（MySQL/PostgreSQL 语义），SQL 方言模板下一轮再做。</p>
     *
     * @param sqlTemplate SQL 模板（保留 {@code $name} 占位）
     * @param params      运行态绑定值（可空）；集合/数组将展开为 IN 列表
     * @param pageNo      页码（从 1 开始，可空）
     * @param pageSize    页大小（可空）
     * @return 已设置 paramMap 的 {@link SqlStatementDto}
     */
    public static SqlStatementDto buildSqlStatement(String sqlTemplate, Map<String, Object> params,
                                                    Integer pageNo, Integer pageSize) {
        if (StrUtil.isBlank(sqlTemplate)) {
            throw new IllegalArgumentException("SQL 模板不能为空");
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
        if (StrUtil.isBlank(sqlTemplate)) {
            throw new IllegalArgumentException("SQL 模板不能为空");
        }
        Map<String, Object> paramMap = (params == null) ? new HashMap<>() : new HashMap<>(params);
        validateSqlParams(sqlTemplate, paramMap);
        String statement = "SELECT COUNT(*) FROM (" + stripTrailingSemicolon(sqlTemplate) + ") _votabi_count";
        return new SqlStatementDto(statement).setParamMap(paramMap);
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
                throw new RuntimeException("SQL参数[" + name + "]缺少绑定值");
            }
            Object value = safeParams.get(name);
            if (value == null) {
                throw new RuntimeException("SQL参数[" + name + "]不能为null");
            }
            if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
                throw new RuntimeException("SQL参数[" + name + "]集合不能为空");
            }
            if (value.getClass().isArray() && Array.getLength(value) == 0) {
                throw new RuntimeException("SQL参数[" + name + "]数组不能为空");
            }
        }
    }

    private static void rejectUnsupportedSqlPlaceholders(String querySql) {
        Matcher macroMatcher = PLATFORM_MACRO_PATTERN.matcher(querySql);
        if (macroMatcher.find()) {
            throw new RuntimeException("暂不支持平台宏参数：" + macroMatcher.group());
        }
        Matcher fragmentMatcher = SQL_FRAGMENT_PATTERN.matcher(querySql);
        if (fragmentMatcher.find()) {
            throw new RuntimeException("暂不支持SQL片段变量@" + fragmentMatcher.group(1));
        }
    }

    private static String stripTrailingSemicolon(String querySql) {
        return querySql.replaceAll(";+\\s*$", "").trim();
    }

    /**
     * 把 JDBC 查询结果（表头 + 二维表数据）规整为 {@code {columns, rows, total}}。
     *
     * @param pair {@code IJDBCService.queryDataWithStatement} 的返回值（left=表头, right=二维数据）
     * @return 结果 Map：columns=列元信息(name/label/typeName)，rows=List&lt;Map&lt;列名,值&gt;&gt;，total=行数
     */
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
