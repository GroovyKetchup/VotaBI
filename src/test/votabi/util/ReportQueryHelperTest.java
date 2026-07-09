package votabi.util;

import ai.webPage.dto.OrderByItem;
import cmn.enums.sql.DBTypeEnum;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import votabi.constant.DataSourceConst;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportQueryHelperTest {

    public static void main(String[] args) {
        filtersBeforeCountAndPage();
        keywordMatchesStringAndNumberFields();
        keywordCastsEditedStringFieldForPostgreSQL();
        supportsPrismaAdvancedOperators();
        acceptsMapOrderByFromOperationParam();
        sqlServerUsesStableDefaultOrder();
        sqlServerProbeUsesTopOne();
        booleanFieldDefaultsToAttributeRole();
        rejectsUnknownConsumerField();
        rejectsUnknownDatabaseType();
    }

    private static void filtersBeforeCountAndPage() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org_code", "001");
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("国家", "中国");
        Map<String, Object> amountFilter = new LinkedHashMap<>();
        amountFilter.put("gte", "10");
        Map<String, Object> advanced = new LinkedHashMap<>();
        advanced.put("订单总数", amountFilter);
        OrderByItem order = new OrderByItem();
        order.setFieldName("订单总数");
        order.setOrder("desc");
        List<OrderByItem> orderBy = new ArrayList<>();
        orderBy.add(order);

        ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                "SELECT customer_nationality AS country, COUNT(*) AS order_total FROM orders WHERE org_code = $org_code GROUP BY customer_nationality;",
                params,
                fieldConfigs(),
                null,
                condition,
                advanced,
                orderBy,
                DBTypeEnum.MySQL,
                2,
                20
        );

        String filtered = "SELECT * FROM (SELECT customer_nationality AS country, COUNT(*) AS order_total FROM orders WHERE org_code = $org_code GROUP BY customer_nationality) _ds WHERE CAST(`country` AS CHAR) LIKE $__filter_0 AND `order_total` >= $__filter_1";
        assertEquals("SELECT COUNT(*) FROM (" + filtered + ") _votabi_count", plan.getCountStatement().getStatement());
        assertEquals(filtered + " ORDER BY `order_total` DESC LIMIT $__limit OFFSET $__offset", plan.getPageStatement().getStatement());
        assertEquals("%中国%", plan.getPageStatement().getParamMap().get("__filter_0"));
        assertEquals(new BigDecimal("10"), plan.getPageStatement().getParamMap().get("__filter_1"));
        assertEquals(20, plan.getPageStatement().getParamMap().get("__limit"));
        assertEquals(20, plan.getPageStatement().getParamMap().get("__offset"));
        assertEquals(false, plan.getCountStatement().getParamMap().containsKey("__limit"));
    }

    private static void sqlServerUsesStableDefaultOrder() {
        ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                "SELECT id FROM orders",
                new LinkedHashMap<>(),
                fieldConfigs(),
                null,
                null,
                null,
                null,
                DBTypeEnum.SQLServer,
                1,
                10
        );
        assertEquals("SELECT * FROM (SELECT id FROM orders) _ds ORDER BY [country] ASC OFFSET $__offset ROWS FETCH NEXT $__limit ROWS ONLY",
                plan.getPageStatement().getStatement());
    }

    private static void keywordMatchesStringAndNumberFields() {
        ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                "SELECT name, country, order_total FROM orders",
                new LinkedHashMap<>(),
                fieldConfigs(),
                "10",
                null,
                null,
                null,
                DBTypeEnum.MySQL,
                1,
                10
        );
        assertEquals("SELECT * FROM (SELECT name, country, order_total FROM orders) _ds WHERE (CAST(`country` AS CHAR) LIKE $__filter_0 OR `order_total` = $__filter_1 OR CAST(`name` AS CHAR) LIKE $__filter_0) LIMIT $__limit OFFSET $__offset",
                plan.getPageStatement().getStatement());
        assertEquals("%10%", plan.getPageStatement().getParamMap().get("__filter_0"));
        assertEquals(new BigDecimal("10"), plan.getPageStatement().getParamMap().get("__filter_1"));
    }

    private static void keywordCastsEditedStringFieldForPostgreSQL() {
        List<Map<String, Object>> fields = fieldConfigs();
        Map<String, Object> amount = fields.get(1);
        amount.put(DataSourceConst.FieldConfigKey_DataType, DataSourceConst.DataType_String);
        amount.put(DataSourceConst.FieldConfigKey_Role, DataSourceConst.Role_Measure);

        ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                "SELECT name, country, order_total FROM orders",
                new LinkedHashMap<>(),
                fields,
                "公司",
                null,
                null,
                null,
                DBTypeEnum.PostgreSQL,
                1,
                10
        );

        assertEquals("SELECT * FROM (SELECT name, country, order_total FROM orders) _ds WHERE (CAST(\"country\" AS TEXT) LIKE $__filter_0 OR CAST(\"order_total\" AS TEXT) LIKE $__filter_0 OR CAST(\"name\" AS TEXT) LIKE $__filter_0) OFFSET $__offset ROWS FETCH NEXT $__limit ROWS ONLY",
                plan.getPageStatement().getStatement());
    }

    private static void supportsPrismaAdvancedOperators() {
        Map<String, Object> nameOps = new LinkedHashMap<>();
        nameOps.put("equals", "a");
        Map<String, Object> countryOps = new LinkedHashMap<>();
        countryOps.put("contains", "中");
        Map<String, Object> amountOps = new LinkedHashMap<>();
        amountOps.put("isNotNull", true);
        Map<String, Object> advanced = new LinkedHashMap<>();
        advanced.put("name", nameOps);
        advanced.put("国家", countryOps);
        advanced.put("订单总数", amountOps);

        ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                "SELECT name, country, order_total FROM orders",
                new LinkedHashMap<>(),
                fieldConfigs(),
                null,
                null,
                advanced,
                null,
                DBTypeEnum.PostgreSQL,
                1,
                10
        );
        assertEquals("SELECT * FROM (SELECT name, country, order_total FROM orders) _ds WHERE \"name\" = $__filter_0 AND CAST(\"country\" AS TEXT) LIKE $__filter_1 AND \"order_total\" IS NOT NULL OFFSET $__offset ROWS FETCH NEXT $__limit ROWS ONLY",
                plan.getPageStatement().getStatement());
        assertEquals("a", plan.getPageStatement().getParamMap().get("__filter_0"));
        assertEquals("%中%", plan.getPageStatement().getParamMap().get("__filter_1"));
    }

    private static void acceptsMapOrderByFromOperationParam() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("fieldName", "订单总数");
        order.put("order", "desc");
        List<Object> orderBy = new ArrayList<>();
        orderBy.add(order);

        ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                "SELECT order_total FROM orders",
                new LinkedHashMap<>(),
                fieldConfigs(),
                null,
                null,
                null,
                orderBy,
                DBTypeEnum.MySQL,
                1,
                10
        );
        assertEquals("SELECT * FROM (SELECT order_total FROM orders) _ds ORDER BY `order_total` DESC LIMIT $__limit OFFSET $__offset",
                plan.getPageStatement().getStatement());
    }

    private static void sqlServerProbeUsesTopOne() {
        assertEquals("SELECT TOP 1 * FROM (SELECT id FROM orders) _ds",
                ReportQueryHelper.buildProbeSqlStatement("SELECT id FROM orders", null, DBTypeEnum.SQLServer).getStatement());
    }

    private static void booleanFieldDefaultsToAttributeRole() {
        JdbcMetaInfoDto meta = new JdbcMetaInfoDto();
        meta.setName("enabled");
        meta.setLabel("enabled");
        meta.setType(Types.BOOLEAN);
        List<JdbcMetaInfoDto> metas = new ArrayList<>();
        metas.add(meta);

        List<Map<String, Object>> fields = DataSourceJdbcUtil.buildFieldList(metas);

        assertEquals(DataSourceConst.DataType_Boolean, fields.get(0).get(DataSourceConst.FieldConfigKey_DataType));
        assertEquals(DataSourceConst.Role_Attribute, fields.get(0).get(DataSourceConst.FieldConfigKey_Role));
    }

    private static void rejectsUnknownConsumerField() {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("bad_field", "x");
        try {
            ReportQueryHelper.buildConsumerQueryPlan(
                    "SELECT id FROM orders",
                    new LinkedHashMap<>(),
                    fieldConfigs(),
                    null,
                    condition,
                    null,
                    null,
                    DBTypeEnum.MySQL,
                    1,
                    10
            );
            throw new AssertionError("Expected unknown field to be rejected");
        } catch (RuntimeException expected) {
            if (!expected.getMessage().contains("bad_field")) {
                throw new AssertionError("Unexpected error: " + expected.getMessage());
            }
        }
    }

    private static void rejectsUnknownDatabaseType() {
        try {
            ReportQueryHelper.buildConsumerQueryPlan(
                    "SELECT id FROM orders",
                    new LinkedHashMap<>(),
                    fieldConfigs(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    10
            );
            throw new AssertionError("Expected unknown database type to be rejected");
        } catch (RuntimeException expected) {
            if (!expected.getMessage().contains("Unsupported database type")) {
                throw new AssertionError("Unexpected error: " + expected.getMessage());
            }
        }
    }

    private static List<Map<String, Object>> fieldConfigs() {
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> country = new LinkedHashMap<>();
        country.put(DataSourceConst.FieldConfigKey_DataName, "country");
        country.put(DataSourceConst.FieldConfigKey_Alias, "国家");
        country.put(DataSourceConst.FieldConfigKey_DataType, DataSourceConst.DataType_String);
        fields.add(country);
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put(DataSourceConst.FieldConfigKey_DataName, "order_total");
        amount.put(DataSourceConst.FieldConfigKey_Alias, "订单总数");
        amount.put(DataSourceConst.FieldConfigKey_DataType, DataSourceConst.DataType_Number);
        fields.add(amount);
        Map<String, Object> name = new LinkedHashMap<>();
        name.put(DataSourceConst.FieldConfigKey_DataName, "name");
        name.put(DataSourceConst.FieldConfigKey_Alias, "客户名称");
        name.put(DataSourceConst.FieldConfigKey_DataType, DataSourceConst.DataType_String);
        fields.add(name);
        return fields;
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }
}

