package votabi.constant;

import cmn.anotation.ClassDeclare;
import org.nutz.dao.entity.annotation.Comment;

/**
 * 报表设计器常量集中地。
 *
 * <p>集中存放报表设计器后端用到的【中文字段名】、【事件/按钮名】与【SQL 占位符约定】，
 * 避免散落在各操作函数里出现魔法字符串。面板设计相关的模型 id（如
 * {@link WorkBenchConst#FormModelId_PanelDesign}）一律复用 {@link WorkBenchConst}，
 * 本类【不】新建报表专属面板模型常量，运行期通过报表定义面板配置里的业务面板引用定位数据集/连接。</p>
 *
 * <p>事件名与前端 {@code src/engine/datasource/ExternalDataSource.ts} 的
 * {@code EXTERNAL_QUERY_EVENT} 等保持逐字一致，便于 {@code callEvent} 契约对齐。</p>
 *
 * @author Devin
 * @version 1.0
 */
@Comment("报表设计器常量")
@ClassDeclare(
        label = "报表设计器常量",
        what = "集中报表设计器后端用到的中文字段名/事件名/占位符约定",
        why = "避免魔法字符串散落，统一与前端契约对齐",
        how = "全部 public static final 常量，按用途分组",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-06-25"
)
public final class ReportDesignConst {

    private ReportDesignConst() {
    }

    // ========================= 事件 / 按钮名（与前端 callEvent / callButton 契约一致） =========================

    public static final String Event_SaveForm = "表单保存";

    /**
     * 运行态取数事件名。
     * <p>与前端 {@code ExternalDataSource.ts} 的 {@code EXTERNAL_QUERY_EVENT} 逐字一致；
     * HTTP-proxy 与 JDBC 共用该事件，本轮仅落地 JDBC 真参数化取数。</p>
     */
    public static final String Event_ExecuteReportQuery = "操作_执行数据查询";

    /** 执行取数操作函数名。 */
    public static final String Function_ExecuteReportQuery = "执行报表取数";

    /** 测试数据连接事件名（与前端契约一致）。 */
    public static final String Event_TestConnection = "操作_测试数据连接";

    /** 获取报表列表事件名（与前端契约一致）。 */
    public static final String Event_QueryReportList = "操作_获取报表列表";

    /** 删除报表事件名（与前端契约一致）。 */
    public static final String Event_DeleteReport = "操作_删除报表";

    /** 保存/更新报表记录的按钮名（与前端 callButton 契约一致）。 */
    public static final String Button_SaveReport = "报表设计器_保存";

    // ========================= 报表定义↔面板绑定字段 =========================
    // 面板自身字段名（面板编号/名称/描述）已迁至 PanelDesignConst 统一维护，见 PanelDesignConst.FieldName_Panel*。

    /**
     * 报表定义里“关联面板”字段名（保存其发布到的目标面板编号）。
     * <p>镜像流程定义的同名字段；发布新建分支时把新分配的面板编号回写到此字段。</p>
     */
    public static final String FieldName_LinkedPanel = "关联面板";

    /** 报表定义自身的“报表名称”字段名（发布时映射为面板名称；前端展示/编辑同名）。 */
    public static final String FieldName_ReportName = "报表名称";

    /** 报表定义自身的“报表描述”字段名（发布时映射为面板描述）。 */
    public static final String FieldName_ReportDesc = "报表描述";

    /** 报表定义面板里指向数据连接业务面板的场景属性名。 */
    public static final String SceneAttr_DataConnection = "数据连接";

    /** 报表定义面板里指向数据集业务面板的场景属性名。 */
    public static final String SceneAttr_Dataset = "数据集";

    // ========================= “数据连接”子表字段（报表定义内） =========================

    /** 报表定义里的“数据连接”子表名。 */
    public static final String Table_DataConnection = "数据连接";

    /**
     * 连接标识字段名。
     * <p>前端 {@code IExternalSourceConfig.connectionRef}（即取数入参 {@code connectionId}）按值匹配本字段，
     * 定位唯一一行连接配置。</p>
     */
    public static final String FieldName_ConnectionId = "连接标识";

    /** 连接地址（JDBC url）字段名。 */
    public static final String FieldName_ConnectionUrl = "连接地址";

    /** 数据库用户名字段名。 */
    public static final String FieldName_UserName = "用户名";

    /** 数据库密码字段名（敏感字段：禁止明文回传/记录日志）。 */
    public static final String FieldName_Password = "密码";

    /**
     * 密码“未修改”回显哨兵。
     * <p>{@link cell.votabi.expr.ReportDesignerExpr#listDataConnections} 用它占位代替真实密码回传；
     * {@link cell.votabi.expr.ReportDesignerExpr#saveDataConnection} 收到该值即视为“未修改密码”、保留旧值不覆盖。
     * 故意取一个几乎不可能与真实密码碰撞的怪串（区别于普通掩码 {@code "******"}——掩码可能撞真实密码且无保存端识别）；
     * 与 {@code server_zbyth} 的 {@code IReportCommonAction.immutablePassword} 同款，便于跨系统对齐。</p>
     */
    public static final String Password_UnchangedSentinel = "!@#$%^&*_IMMUTABLE_!@#$%^&*";

    /** 数据库驱动类字段名。 */
    public static final String FieldName_DbDriver = "数据库驱动";

    /** 数据库类型字段名（对应 {@code cmn.enums.sql.DBTypeEnum} 的 typeName）。 */
    public static final String FieldName_DbType = "数据库类型";

    /** 数据集业务表里保存数据连接编号的字段名，按 Form.Code 查询数据连接。 */
    public static final String FieldName_DatabaseCode = "数据连接编号";

    /** 数据集业务表里的查询 SQL 字段名。 */
    public static final String FieldName_QuerySql = "查询SQL";

    // ========================= SQL 占位符 / 取数约定 =========================

    /**
     * 运行态参数占位符前缀。
     * <p>{@code $name}（无尾随 {@code $}），与 {@code cmn.dto.SqlStatementDto} 的
     * 正则 {@code ([@$])(\w+)} 一致；运行期筛选值一律走 {@code setParamMap}，
     * 由 {@code SqlStatementDto} 展开为 PreparedStatement 的 {@code ?} 绑定，
     * 集合/数组自动展开为 {@code ?,?,...} 的 IN 列表，绝不拼进 SQL 文本。</p>
     */
    public static final String SqlParamPrefix = "$";

    /** 连接探活用的轻量查询语句（testConnection 使用）。 */
    public static final String ProbeSql = "SELECT 1";

    public static final int DefaultPageNo = 1;

    public static final int DefaultPageSize = 20;

    /** 取数结果中“列信息”的键名。 */
    public static final String ResultKey_Columns = "columns";

    /** 取数结果中“数据行”的键名。 */
    public static final String ResultKey_Rows = "rows";

    /** 取数结果中“总行数”的键名。 */
    public static final String ResultKey_Total = "total";

    public static final String SqlStructKey_FieldName = "fieldName";

    public static final String SqlStructKey_FieldLabel = "fieldLabel";

    public static final String SqlStructKey_TypeName = "typeName";

    public static final String SqlStructKey_FieldType = "fieldType";

    public static final String FieldType_Text = "文本";

    public static final String FieldType_Number = "数值";

    public static final String FieldType_Date = "日期";

    // ========================= 数据连接默认连接池参数 =========================

    /** 自定义数据源默认最小连接数。 */
    public static final int DataSource_MinConnection = 1;

    /** 自定义数据源默认最大连接数。 */
    public static final int DataSource_MaxConnection = 20;

    /** 连接池稳定唯一 key 的分隔符：{@code panelCode + "." + connectionId}。 */
    public static final String DataSourceName_Delimiter = ".";

    /** 模型类别：报表（发布时写入面板的语义标识，用于区别”流程”）。 */
    public static final String ModelType_Report = "报表";
}
