package votabi.constant;

public final class DataSourceConst {

    private DataSourceConst() {
    }

    public static final String FieldName_DatabaseCode = "数据连接编号";
    public static final String FieldName_DatasetName = "数据集名称";
    public static final String FieldName_DatasetType = "数据集类型";
    public static final String DatasetType_Table = "table";
    public static final String DatasetType_Sql = "sql";
    public static final String FieldName_SchemaName = "模式名";
    public static final String FieldName_SourceTableName = "源表名";
    public static final String FieldName_QuerySql = "查询SQL";
    public static final String FieldName_QueryParams = "查询参数";
    public static final String FieldName_Description = "描述";
    public static final String FieldName_FieldConfig = "字段配置";

    public static final int DefaultPageNo = 1;
    public static final int DefaultPageSize = 20;

    public static final String ResultKey_List = "list";
    public static final String ResultKey_TotalSize = "totalSize";

    public static final String FieldConfigKey_DataName = "dataName";
    public static final String FieldConfigKey_Alias = "alias";
    public static final String FieldConfigKey_SourceType = "sourceType";
    public static final String FieldConfigKey_DataType = "dataType";
    public static final String FieldConfigKey_Length = "length";
    public static final String FieldConfigKey_Precision = "precision";
    public static final String FieldConfigKey_Role = "role";
    public static final String FieldConfigKey_DateFormat = "dateFormat";
    public static final String FieldConfigKey_Primary = "primary";
    public static final String FieldConfigKey_Sort = "sort";
    public static final String FieldConfigKey_Category = "category";

    public static final String DataType_String = "string";
    public static final String DataType_Number = "number";
    public static final String DataType_Date = "date";
    public static final String DataType_Boolean = "boolean";

    public static final String Role_Dimension = "dimension";
    public static final String Role_Measure = "measure";
    public static final String Role_Attribute = "attribute";
    public static final String DefaultDateFormat = "yyyyMMdd";
}
