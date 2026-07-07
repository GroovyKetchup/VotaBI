package cell.votabi.expr;

import ai.webPage.utils.FormModelUtil;
import cell.CellIntf;
import cell.cdao.IDao;
import cell.cdao.IDaoService;
import cell.cmn.jdbc.IJDBCService;
import cell.gpf.adur.data.IFormMgr;
import cell.octo.cm.IContext;
import cell.octo.cm.service.IPanelDesignService;
import cmn.anotation.ClassDeclare;
import cmn.anotation.InputDeclare;
import cmn.anotation.MethodDeclare;
import com.leavay.nio.crpc.RpcMap;
import cmn.dto.PairDto;
import cmn.dto.SqlStatementDto;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import gpf.adur.data.AssociationData;
import gpf.adur.data.Form;
import gpf.adur.data.TableData;
import octo.cm.constant.PanelDesignConst;
import octo.cm.constant.WorkBenchConst;
import octo.cm.exception.business.DomainException;
import octo.cm.exception.business.PanelDesignException;
import octo.cm.util.EasyOperation;
import octo.cm.util.PanelDesignPublishUtil;
import octocm.domain.observer.OctoDomainOpObserver;
import votabi.constant.DataSourceConst;
import votabi.constant.ReportDesignConst;
import votabi.util.DataSourceJdbcUtil;
import votabi.util.ReportJdbcDataSource;
import votabi.util.ReportQueryHelper;
import votabi.util.ReportToPanelDesignPublisher;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报表设计器-操作函数集合（镜像 {@code FlowDesignerExpr} 的接口/注解风格）。
 *
 * <p>本接口为报表设计器 Phase 3 提供后端操作函数：</p>
 * <ul>
 *   <li>{@link #publishToPanelDesign}：把报表定义一次性物化/发布到目标面板，并建立双向绑定：
 *   {@code 面板.Owner = 报表定义.uuid}，{@code 报表定义.”关联面板” = 面板编号}。</li>
 *   <li>{@link #takeEffectPanelDesign}：报表面板生效（镜像流程）。</li>
 *   <li>数据连接 CRUD：{@link #saveDataConnection} / {@link #deleteDataConnection} / {@link #listDataConnections}。</li>
 *   <li>{@link #testConnection}：连接探活（rebuildDbPool + 轻量探活查询）。</li>
 *   <li>{@link #executeReportQuery}：运行态真参数化取数（通过报表定义面板配置定位数据集/连接）。</li>
 * </ul>
 *
 * <p><b>运行态取数：</b>按报表定义面板编号查面板设计 Form → 从“面板数据”子表的“数据集/数据连接”场景属性样式解析业务面板编号 →
 * 通过 {@link FormModelUtil#buildPanelFormModelIdByCmName(String, String)} 构建模型 ID → 按 {@code Form.Code} 查询数据集和数据连接 →
 * 构建自定义 {@link ReportJdbcDataSource} → {@link SqlStatementDto} 真参数化（{@code $name} → {@code ?} 绑定，
 * 集合自动展开 IN 列表，绝不拼 SQL 文本）。</p>
 *
 * @author Devin
 * @version 1.0
 */
@ClassDeclare(
        label = "报表设计器-操作函数",
        what = "报表设计器后端操作函数：发布/生效/数据连接CRUD/测试连接/真参数化取数",
        why = "镜像流程设计器，提供报表 Phase3 后端能力",
        how = "CellIntf 接口 + default 方法 + @MethodDeclare/@InputDeclare",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-06-25"
)
public interface ReportDesignerExpr extends CellIntf {

    /** 公共操作辅助单例。 */
    EasyOperation Op = EasyOperation.get();

    // ========================= 发布 / 生效 =========================

    /**
     * 报表设计发布到面板。
     *
     * <p>报表定义这条 Form 取自上一步「表单保存」操作函数的返回（镜像流程，经 {@code output.get(“表单保存”)}），
     * 不再从 {@link IContext#getCmInstance()} 取。面板名称/面板描述由本函数内部从报表定义映射，前端不再传 mappedData。
     * 处理器 {@link ReportToPanelDesignPublisher} 把数据一次性物化进目标面板并落库，建立双向绑定：
     * {@code 面板.Owner = 报表定义.uuid}，{@code 报表定义.”关联面板” = 面板编号}（新建分支时本函数回写）。</p>
     *
     * @param context 操作上下文
     * @param output  运行输出（取上一步「表单保存」返回的报表定义 Form）
     * @param domain  业务域编号
     * @return 处理后的报表定义 Form
     * @throws Exception 业务域/数据非法或发布失败
     */
    @MethodDeclare(
            label = "报表设计发布到面板", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "context", label = "", desc = "", exampleValue = "$context$"),
                    @InputDeclare(name = "output", label = "运行输出", desc = "运行输出", exampleValue = "$output$", nullable = true),
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
            }
    )
    default Form publishToPanelDesign(IContext context, RpcMap<Object> output, String domain) throws Exception {
        if (StrUtil.isBlank(domain)) throw DomainException.Builder.busDomainCodeEmpty();

        // 报表定义取上一步「表单保存」的返回（镜像流程），不再从 context 取
        Object formObj = output == null ? null : output.get(ReportDesignConst.Event_SaveForm);
        if (!(formObj instanceof Form)) throw new RuntimeException("未拿到上一个操作函数的返回值（表单保存）");
        Form reportDef = (Form) formObj;

        OctoDomainOpObserver observer = Op.getOctoDomainOpObserver(domain);
        if (observer == null) throw DomainException.Builder.notFoundWithCode(domain);

        // 面板名称/面板描述映射收进函数内部：从报表定义取，前端不再承担该职责
        JSONObject src = new JSONObject();
        src.set(PanelDesignConst.FieldName_PanelName, reportDef.getString(ReportDesignConst.FieldName_ReportName));
        src.set(PanelDesignConst.FieldName_PanelDesc,
                StrUtil.blankToDefault(reportDef.getString(ReportDesignConst.FieldName_ReportDesc), ""));

        try (IDao dao = IDaoService.newIDao()) {
            String existedPanelCode = reportDef.getString(ReportDesignConst.FieldName_LinkedPanel);

            ReportToPanelDesignPublisher publisher =
                    new ReportToPanelDesignPublisher(dao, observer, ReportDesignConst.ModelType_Report, reportDef, src);
            Form panelDesign = publisher.publish(existedPanelCode);
            boolean changed = updateExecuteReportQueryEvent(dao, panelDesign, extractPanelCodeFromFormModelId(reportDef.getFormModelId()));

            // 新建分支（含“关联面板找不到”的回退）：把新分配的面板编号回写到报表定义
            if (publisher.isNewPanel()) {
                reportDef.setAttrValue(ReportDesignConst.FieldName_LinkedPanel,
                        panelDesign.getString(PanelDesignConst.FieldName_PanelCode));
                reportDef = IFormMgr.get().updateForm(null, dao, reportDef, observer);
                dao.commit();
            } else if (changed) {
                dao.commit();
            }
        }

        return reportDef;
    }

    /**
     * 报表设计面板生效（镜像流程）。
     *
     * @param domain          业务域编号
     * @param targetPanelCode 目标面板编号
     * @throws Exception 业务域为空或面板不存在
     */
    @MethodDeclare(
            label = "报表设计面板生效", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$", nullable = true),
                    @InputDeclare(name = "targetPanelCode", label = "目标面板编号", desc = ""),
            }
    )
    default void takeEffectPanelDesign(String domain, String targetPanelCode) throws Exception {
        if (StrUtil.isBlank(domain)) throw DomainException.Builder.busDomainCodeEmpty();

        try (IDao dao = IDaoService.newIDao()) {
            OctoDomainOpObserver observer = Op.getOctoDomainOpObserver(domain);
            Form panelDesignForm = IPanelDesignService.get().getPanelDesign(dao, observer, targetPanelCode, true);
            if (panelDesignForm == null) throw PanelDesignException.Builder.notFoundWithCode(targetPanelCode);

            PanelDesignPublishUtil.publishBatch(null, null,
                    observer, CollUtil.newArrayList(panelDesignForm));
        }
    }

    // ========================= 数据连接 CRUD =========================

    /**
     * 运行态真参数化取数（通过报表定义面板配置定位数据集/连接）。
     *
     * <p>流程：按面板编号通过 Owner + 描述字段隐藏标记查询报表定义 → 选”数据连接”子表中”连接标识”匹配行构建数据源 → 用 {@link ReportQueryHelper#buildSqlStatement}
     * 组装真参数化语句（{@code $name} → {@code ?} 绑定，集合自动展开 IN 列表，绝不拼 SQL 文本，
     * 含基础 LIMIT/OFFSET 分页）→ {@link IJDBCService#queryDataWithStatement} 执行 →
     * 规整为 {@code {columns, rows, total}}。</p>
     *
     * @param targetPanelCode 面板编号（运行态/设计态统一，零硬编码）
     * @param datasetId    数据集编号
     * @param params       运行态绑定值（可空）；集合/数组展开为 IN 列表
     * @param pageNo       页码（从 1 开始，可空）
     * @param pageSize     页大小（可空）
     * @return {@code {columns, rows, total}}
     * @throws Exception 解析报表定义/数据连接或查询失败
     */
    @MethodDeclare(
            label = "执行报表取数", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
                    @InputDeclare(name = "reportDefPanelCode", label = "报表定义面板编号", desc = ""),
                    @InputDeclare(name = "datasetId", label = "数据集编号", desc = ""),
                    @InputDeclare(name = "params", label = "绑定参数", desc = "", nullable = true),
                    @InputDeclare(name = "pageNo", label = "页码", desc = "", nullable = true),
                    @InputDeclare(name = "pageSize", label = "页大小", desc = "", nullable = true),
            }
    )
    default Map<String, Object> executeReportQuery(String domain, String reportDefPanelCode, String datasetId,
                                                   Map<String, Object> params, Integer pageNo, Integer pageSize) throws Exception {
        if (StrUtil.isBlank(domain)) throw DomainException.Builder.busDomainCodeEmpty();
        if (StrUtil.isBlank(reportDefPanelCode)) throw new RuntimeException("报表定义面板编号不能为空");
        if (StrUtil.isBlank(datasetId)) throw new RuntimeException("数据集编号不能为空");

        try (IDao dao = IDaoService.newIDao()) {
            String datasetPanelCode = resolveScenePanelCode(domain, dao, reportDefPanelCode, ReportDesignConst.SceneAttr_Dataset);
            Form dataset = queryBusinessFormByCode(dao, domain, datasetPanelCode, datasetId);
            if (dataset == null) throw new RuntimeException("未找到数据集[" + datasetId + "]");

            String connectionId = dataset.getString(DataSourceConst.FieldName_DatabaseCode);
            String datasetType = StrUtil.blankToDefault(dataset.getString(DataSourceConst.FieldName_DatasetType),
                    DataSourceConst.DatasetType_Sql);
            if (StrUtil.isBlank(connectionId)) throw new RuntimeException("数据集[" + datasetId + "]未配置数据连接编号");

            String querySql;
            Map<String, Object> sqlParams = params;
            if (DataSourceConst.DatasetType_Table.equals(datasetType)) {
                String schemaName = dataset.getString(DataSourceConst.FieldName_SchemaName);
                String sourceTableName = dataset.getString(DataSourceConst.FieldName_SourceTableName);
                querySql = DataSourceJdbcUtil.buildSourceTableSql(schemaName, sourceTableName);
            } else if (DataSourceConst.DatasetType_Sql.equals(datasetType)) {
                querySql = dataset.getString(DataSourceConst.FieldName_QuerySql);
                if (StrUtil.isBlank(querySql)) throw new RuntimeException("数据集[" + datasetId + "]未配置查询SQL");
                sqlParams = mergeQueryParams(dataset.getString(DataSourceConst.FieldName_QueryParams), params);
            } else {
                throw new RuntimeException("数据集[" + datasetId + "]数据集类型不支持：" + datasetType);
            }

            String databasePanelCode = resolveScenePanelCode(domain, dao, reportDefPanelCode, ReportDesignConst.SceneAttr_DataConnection);
            Form connRow = queryBusinessFormByCode(dao, domain, databasePanelCode, connectionId);
            if (connRow == null) throw new RuntimeException("未找到连接标识[" + connectionId + "]对应的数据连接");
            ReportJdbcDataSource ds = buildDataSource(connRow, databasePanelCode, connectionId);

            pageNo = defaultPageNo(pageNo);
            pageSize = defaultPageSize(pageSize);
            SqlStatementDto stmt = ReportQueryHelper.buildSqlStatement(querySql, sqlParams, pageNo, pageSize);
            PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair =
                    IJDBCService.get().queryDataWithStatement(ds, stmt);
            return DataSourceJdbcUtil.buildQueryListResult(pair);
        }
    }

    default Map<String, Object> mergeQueryParams(String queryParamsJson, Map<String, Object> runtimeParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(queryParamsJson)) {
            if (!queryParamsJson.trim().startsWith("{")) {
                throw new RuntimeException("查询参数必须是JSON对象字符串");
            }
            JSONObject defaults;
            try {
                defaults = JSONUtil.parseObj(queryParamsJson);
            } catch (Exception e) {
                throw new RuntimeException("查询参数必须是JSON对象字符串", e);
            }
            for (Map.Entry<String, Object> entry : defaults.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        return merged;
    }

    default int defaultPageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? ReportDesignConst.DefaultPageNo : pageNo;
    }

    default int defaultPageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? ReportDesignConst.DefaultPageSize : pageSize;
    }

    // ========================= 支撑方法 =========================

    default String resolveScenePanelCode(String domain, IDao dao, String reportDefPanelCode, String sceneAttrName) throws Exception {
        Form reportDefPanel = loadReportDefPanelDesign(domain, dao, reportDefPanelCode);
        TableData td = reportDefPanel.getTable(PanelDesignConst.FieldName_PanelData);
        if (td == null || Op.isEmpty(td)) {
            throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]不含“" + PanelDesignConst.FieldName_PanelData + "”子表");
        }
        for (Form row : td.getRows()) {
            if (!sceneAttrName.equals(row.getString(PanelDesignConst.FieldName_SceneAttrName))) continue;
            String style = row.getString(PanelDesignConst.FieldName_SceneAttrStyle);
            if (!isPanelRefStyle(style)) {
                throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]的场景属性[" + sceneAttrName + "]样式不是下拉框/表格/表单：" + style);
            }
            String panelCode = extractStylePanelCode(style);
            if (StrUtil.isBlank(panelCode)) {
                throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]的场景属性[" + sceneAttrName + "]样式未配置业务面板编号：" + style);
            }
            return panelCode;
        }
        throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]未找到场景属性[" + sceneAttrName + "]");
    }

    default Form loadReportDefPanelDesign(String domain, IDao dao, String reportDefPanelCode) throws Exception {
        OctoDomainOpObserver observer = Op.getOctoDomainOpObserver(domain);
        if (observer == null) throw DomainException.Builder.notFoundWithCode(domain);
        Form panel = IPanelDesignService.get().getPanelDesign(dao, observer, reportDefPanelCode, true);
        if (panel == null) throw PanelDesignException.Builder.notFoundWithCode(reportDefPanelCode);
        return panel;
    }

    default boolean isPanelRefStyle(String style) {
        if (StrUtil.isBlank(style)) return false;
        String styleName = styleName(style);
        return styleName.contains("下拉框") || styleName.endsWith("表格") || styleName.endsWith("表单");
    }

    default String extractStylePanelCode(String style) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("'([^']*)'").matcher(style);
        if (!matcher.find()) return null;
        String panelCode = matcher.group(1);
        return StrUtil.isBlank(panelCode) ? null : StrUtil.removeSuffix(panelCode, "_CM");
    }

    default String styleName(String style) {
        int idx = style.indexOf('(');
        return (idx < 0 ? style : style.substring(0, idx)).trim();
    }

    default Form queryBusinessFormByCode(IDao dao, String domain, String panelCode, String formCode) throws Exception {
        return DataSourceJdbcUtil.queryBusinessFormByCode(dao, domain, panelCode, formCode);
    }

    default boolean updateExecuteReportQueryEvent(IDao dao, Form panelDesign, String reportDefPanelCode) throws Exception {
        if (panelDesign == null || StrUtil.isBlank(reportDefPanelCode)) return false;
        TableData td = panelDesign.getTable(PanelDesignConst.FieldName_PanelEvent);
        if (td == null || Op.isEmpty(td)) return false;
        for (Form row : td.getRows()) {
            AssociationData ac = row.getAssociation(PanelDesignConst.FieldName_EventImpl);
            Form event = Op.queryFormByAc(dao, ac);
            if (event == null || !ReportDesignConst.Event_ExecuteReportQuery.equals(event.getString(PanelDesignConst.FieldName_EventName))) {
                continue;
            }
            if (updateEventActionFirstArg(event, reportDefPanelCode)) {
                IFormMgr.get().updateForm(dao, event);
                return true;
            }
        }
        return false;
    }

    default boolean updateEventActionFirstArg(Form event, String reportDefPanelCode) throws Exception {
        TableData actionTd = event.getTable(PanelDesignConst.FieldName_EventAction);
        if (actionTd == null || Op.isEmpty(actionTd)) return false;
        boolean changed = false;
        for (Form action : actionTd.getRows()) {
            String expr = action.getString(PanelDesignConst.FieldName_OperateFunction);
            String updated = replaceExecuteReportQueryFirstArg(expr, reportDefPanelCode);
            if (!StrUtil.equals(expr, updated)) {
                action.setAttrValue(PanelDesignConst.FieldName_OperateFunction, updated);
                changed = true;
            }
        }
        if (changed) event.setAttrValue(PanelDesignConst.FieldName_EventAction, actionTd);
        return changed;
    }

    default String replaceExecuteReportQueryFirstArg(String expr, String reportDefPanelCode) {
        if (StrUtil.isBlank(expr)) return expr;
        String prefix = ReportDesignConst.Function_ExecuteReportQuery + "(";
        int start = expr.indexOf(prefix);
        if (start < 0) return expr;
        int argStart = start + prefix.length();
        int argEnd = findFirstArgEnd(expr, argStart);
        if (argEnd < argStart) return expr;
        String expected = "\"" + reportDefPanelCode + "\"";
        String firstArg = expr.substring(argStart, argEnd).trim();
        if (expected.equals(firstArg)) return expr;
        return expr.substring(0, argStart) + expected + expr.substring(argEnd);
    }

    default int findFirstArgEnd(String expr, int from) {
        char quote = 0;
        for (int i = from; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if ((c == '\'' || c == '"') && (i == from || expr.charAt(i - 1) != '\\')) {
                quote = quote == 0 ? c : (quote == c ? 0 : quote);
            } else if (quote == 0 && (c == ',' || c == ')')) {
                return i;
            }
        }
        return -1;
    }

    default String extractPanelCodeFromFormModelId(String formModelId) {
        if (StrUtil.isBlank(formModelId)) return null;
        String panelCode = formModelId.substring(formModelId.lastIndexOf('.') + 1);
        panelCode = panelCode.replaceFirst("^iML", "IML");
        return StrUtil.removeSuffix(panelCode, "_CM");
    }

    /**
     * 旧兼容：按面板编号通过 Owner + 描述字段隐藏标记查询报表定义 Form。
     *
     * <p>查询步骤：
     * <ol>
     *   <li>按面板编号查面板设计 Form</li>
     *   <li>从"面板描述"字段提取隐藏标记 {@code <!--REPORT_DEF_MODEL_ID:xxx-->} 获取 FormModelId</li>
     *   <li>读取 {@code Form.Owner} 得报表定义的 uuid</li>
     *   <li>查询报表定义 Form</li>
     * </ol>
     * </p>
     *
     * @param dao       数据访问会话
     * @param panelCode 面板编号
     * @return 报表定义 Form
     * @throws Exception 面板不存在 / 未绑定报表定义 / 报表定义加载失败
     */
    @Deprecated
    default Form loadReportDefByPanelCode(IDao dao, String panelCode) throws Exception {
        Form panel = Op.queryFormByValueMatchAnyField(dao, WorkBenchConst.FormModelId_PanelDesign,
                CollUtil.newHashSet(PanelDesignConst.FieldName_PanelCode), panelCode, null);
        if (panel == null) throw PanelDesignException.Builder.notFoundWithCode(panelCode);

        // 从面板描述提取 FormModelId
        String panelDesc = panel.getString(PanelDesignConst.FieldName_PanelDesc);
        String reportDefFormModelId = extractReportDefModelId(panelDesc);
        if (StrUtil.isBlank(reportDefFormModelId)) {
            throw new RuntimeException("面板[" + panelCode + "]描述中未找到报表定义模型标记");
        }

        // 从 Owner 获取 uuid
        String reportDefUuid = panel.getStringByCode(Form.Owner);
        if (StrUtil.isBlank(reportDefUuid)) {
            throw new RuntimeException("面板[" + panelCode + "]未绑定报表定义（Form.Owner 为空）");
        }

        Form reportDef = IFormMgr.get().queryForm(dao, reportDefFormModelId, reportDefUuid);
        if (reportDef == null) {
            throw new RuntimeException("未找到面板[" + panelCode + "]关联的报表定义");
        }
        return reportDef;
    }

    /**
     * 从面板描述中提取报表定义模型 ID。
     *
     * @param panelDesc 面板描述
     * @return FormModelId，未找到返回 null
     */
    default String extractReportDefModelId(String panelDesc) {
        if (StrUtil.isBlank(panelDesc)) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<!--REPORT_DEF_MODEL_ID:(.*?)-->");
        java.util.regex.Matcher matcher = pattern.matcher(panelDesc);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 从报表定义“数据连接”子表中找“连接标识”匹配的行。
     *
     * @param reportDef    报表定义 Form
     * @param connectionId 连接标识
     * @return 匹配的连接行 Form
     * @throws Exception 子表为空或无匹配行
     */
    default Form findConnectionRow(Form reportDef, String connectionId) throws Exception {
        TableData td = reportDef.getTable(ReportDesignConst.Table_DataConnection);
        if (td == null || Op.isEmpty(td)) {
            throw new RuntimeException("报表定义不含数据连接配置");
        }
        for (Form r : td.getRows()) {
            if (connectionId.equals(r.getString(ReportDesignConst.FieldName_ConnectionId))) {
                return r;
            }
        }
        throw new RuntimeException("未找到连接标识[" + connectionId + "]对应的数据连接");
    }

    /**
     * 用一行”数据连接”配置构建自定义数据源（连接池稳定唯一 key = panelCode.connectionId）。
     *
     * @param connRow      数据连接行 Form
     * @param panelCode    面板编号
     * @param connectionId 连接标识
     * @return 自定义数据源
     * @throws Exception 读取字段失败
     */
    default ReportJdbcDataSource buildDataSource(Form connRow, String panelCode, String connectionId) throws Exception {
        return DataSourceJdbcUtil.buildDataSource(connRow, panelCode, connectionId);
    }
}
