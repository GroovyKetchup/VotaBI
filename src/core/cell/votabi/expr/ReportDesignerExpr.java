package cell.votabi.expr;

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
import cmn.dto.PairDto;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.leavay.nio.crpc.RpcMap;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ClassDeclare(
        label = "报表设计器-操作函数",
        what = "报表设计器后端操作函数：发布/生效/执行取数",
        why = "为报表设计器提供运行态 JDBC 取数能力",
        how = "CellIntf default 方法 + @MethodDeclare/@InputDeclare",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-07-08"
)
public interface ReportDesignerExpr extends CellIntf {

    EasyOperation Op = EasyOperation.get();

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

        Object formObj = output == null ? null : output.get(ReportDesignConst.Event_SaveForm);
        if (!(formObj instanceof Form)) throw new RuntimeException("未拿到上一步操作函数的返回值（表单保存）");
        Form reportDef = (Form) formObj;

        OctoDomainOpObserver observer = Op.getOctoDomainOpObserver(domain);
        if (observer == null) throw DomainException.Builder.notFoundWithCode(domain);

        JSONObject src = new JSONObject();
        src.set(PanelDesignConst.FieldName_PanelName, reportDef.getString(ReportDesignConst.FieldName_ReportName));
        src.set(PanelDesignConst.FieldName_PanelDesc,
                StrUtil.blankToDefault(reportDef.getString(ReportDesignConst.FieldName_ReportDesc), ""));

        try (IDao dao = IDaoService.newIDao()) {
            String existedPanelCode = reportDef.getString(ReportDesignConst.FieldName_LinkedPanel);
            ReportToPanelDesignPublisher publisher =
                    new ReportToPanelDesignPublisher(dao, observer, ReportDesignConst.ModelType_Report, reportDef, src);
            Form panelDesign = publisher.publish(existedPanelCode);
            boolean changed = updateExecuteReportQueryEvent(dao, panelDesign,
                    extractPanelCodeFromFormModelId(reportDef.getFormModelId()));

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
            PanelDesignPublishUtil.publishBatch(null, null, observer, CollUtil.newArrayList(panelDesignForm));
        }
    }

    @MethodDeclare(
            label = "执行报表取数", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
                    @InputDeclare(name = "reportDefPanelCode", label = "报表定义面板编号", desc = ""),
                    @InputDeclare(name = "datasetId", label = "数据集编号", desc = ""),
                    @InputDeclare(name = "params", label = "SQL动态参数", desc = "", nullable = true),
                    @InputDeclare(name = "keyword", label = "关键词", desc = "", nullable = true),
                    @InputDeclare(name = "condition", label = "简单查询条件", desc = "", nullable = true),
                    @InputDeclare(name = "advancedConditions", label = "高级查询条件", desc = "", nullable = true),
                    @InputDeclare(name = "orderBy", label = "排序", desc = "", nullable = true),
                    @InputDeclare(name = "pageNo", label = "页码", desc = "", nullable = true),
                    @InputDeclare(name = "pageSize", label = "页大小", desc = "", nullable = true),
            }
    )
    default Map<String, Object> executeReportQuery(String domain, String reportDefPanelCode, String datasetId,
                                                   Map<String, Object> params,
                                                   String keyword,
                                                   Map<String, Object> condition,
                                                   Map<String, Object> advancedConditions,
                                                   List<?> orderBy,
                                                   Integer pageNo, Integer pageSize) throws Exception {
        if (StrUtil.isBlank(domain)) throw DomainException.Builder.busDomainCodeEmpty();
        if (StrUtil.isBlank(reportDefPanelCode)) throw new RuntimeException("报表定义面板编号不能为空");
        if (StrUtil.isBlank(datasetId)) throw new RuntimeException("数据集编号不能为空");

        try (IDao dao = IDaoService.newIDao()) {
            String datasetPanelCode = resolveScenePanelCode(domain, dao, reportDefPanelCode, ReportDesignConst.SceneAttr_Dataset);
            Form dataset = queryBusinessFormByCode(dao, domain, datasetPanelCode, datasetId);
            if (dataset == null) throw new RuntimeException("未找到数据集[" + datasetId + "]");

            String databaseId = dataset.getString(DataSourceConst.FieldName_DatabaseCode);
            if (StrUtil.isBlank(databaseId)) throw new RuntimeException("数据集[" + datasetId + "]未配置数据连接编号");
            String databasePanelCode = resolveScenePanelCode(domain, dao, reportDefPanelCode, ReportDesignConst.SceneAttr_DataConnection);
            Form connRow = queryBusinessFormByCode(dao, domain, databasePanelCode, databaseId);
            if (connRow == null) throw new RuntimeException("未找到数据连接[" + databaseId + "]");
            ReportJdbcDataSource ds = buildDataSource(connRow, databasePanelCode, databaseId);

            String datasetType = StrUtil.blankToDefault(dataset.getString(DataSourceConst.FieldName_DatasetType),
                    DataSourceConst.DatasetType_Sql);
            String querySql;
            Map<String, Object> sqlParams = params;
            if (DataSourceConst.DatasetType_Table.equals(datasetType)) {
                querySql = ReportQueryHelper.buildSourceTableSql(
                        dataset.getString(DataSourceConst.FieldName_SchemaName),
                        dataset.getString(DataSourceConst.FieldName_SourceTableName),
                        ds.getDBType());
            } else if (DataSourceConst.DatasetType_Sql.equals(datasetType)) {
                querySql = dataset.getString(DataSourceConst.FieldName_QuerySql);
                if (StrUtil.isBlank(querySql)) throw new RuntimeException("数据集[" + datasetId + "]未配置查询SQL");
                sqlParams = mergeQueryParams(dataset.getString(DataSourceConst.FieldName_QueryParams), params);
            } else {
                throw new RuntimeException("数据集[" + datasetId + "]数据集类型不支持：" + datasetType);
            }

            ReportQueryHelper.QueryPlan plan = ReportQueryHelper.buildConsumerQueryPlan(
                    querySql,
                    sqlParams,
                    parseFieldConfigs(dataset.getString(DataSourceConst.FieldName_FieldConfig)),
                    keyword,
                    condition,
                    advancedConditions,
                    orderBy,
                    ds.getDBType(),
                    defaultPageNo(pageNo),
                    defaultPageSize(pageSize));

            PairDto<List<JdbcMetaInfoDto>, List<List<String>>> countPair =
                    IJDBCService.get().queryDataWithStatement(ds, plan.getCountStatement());
            int totalSize = DataSourceJdbcUtil.readCount(countPair);
            PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair =
                    IJDBCService.get().queryDataWithStatement(ds, plan.getPageStatement());
            return DataSourceJdbcUtil.buildQueryListResult(pair, totalSize);
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
        if (runtimeParams != null) merged.putAll(runtimeParams);
        return merged;
    }

    default List<Map<String, Object>> parseFieldConfigs(String fieldConfigJson) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (StrUtil.isBlank(fieldConfigJson)) return fields;
        JSONArray arr;
        try {
            arr = JSONUtil.parseArray(fieldConfigJson);
        } catch (Exception e) {
            throw new RuntimeException("字段配置必须是JSON数组字符串", e);
        }
        for (Object item : arr) {
            JSONObject obj = JSONUtil.parseObj(item);
            Map<String, Object> field = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                field.put(entry.getKey(), entry.getValue());
            }
            fields.add(field);
        }
        return fields;
    }

    default int defaultPageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? ReportDesignConst.DefaultPageNo : pageNo;
    }

    default int defaultPageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? ReportDesignConst.DefaultPageSize : pageSize;
    }

    default String resolveScenePanelCode(String domain, IDao dao, String reportDefPanelCode, String sceneAttrName) throws Exception {
        Form reportDefPanel = loadReportDefPanelDesign(domain, dao, reportDefPanelCode);
        TableData td = reportDefPanel.getTable(PanelDesignConst.FieldName_PanelData);
        if (td == null || Op.isEmpty(td)) {
            throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]不含“"
                    + PanelDesignConst.FieldName_PanelData + "”子表");
        }
        for (Form row : td.getRows()) {
            if (!sceneAttrName.equals(row.getString(PanelDesignConst.FieldName_SceneAttrName))) continue;
            String style = row.getString(PanelDesignConst.FieldName_SceneAttrStyle);
            if (!isPanelRefStyle(style)) {
                throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]的场景属性["
                        + sceneAttrName + "]样式不是下拉框/表格/表单：" + style);
            }
            String panelCode = extractStylePanelCode(style);
            if (StrUtil.isBlank(panelCode)) {
                throw new RuntimeException("报表定义面板[" + reportDefPanelCode + "]的场景属性["
                        + sceneAttrName + "]样式未配置业务面板编号：" + style);
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

    @Deprecated
    default Form loadReportDefByPanelCode(IDao dao, String panelCode) throws Exception {
        Form panel = Op.queryFormByValueMatchAnyField(dao, WorkBenchConst.FormModelId_PanelDesign,
                CollUtil.newHashSet(PanelDesignConst.FieldName_PanelCode), panelCode, null);
        if (panel == null) throw PanelDesignException.Builder.notFoundWithCode(panelCode);

        String reportDefFormModelId = extractReportDefModelId(panel.getString(PanelDesignConst.FieldName_PanelDesc));
        if (StrUtil.isBlank(reportDefFormModelId)) {
            throw new RuntimeException("面板[" + panelCode + "]描述中未找到报表定义模型标记");
        }
        String reportDefUuid = panel.getStringByCode(Form.Owner);
        if (StrUtil.isBlank(reportDefUuid)) {
            throw new RuntimeException("面板[" + panelCode + "]未绑定报表定义");
        }
        Form reportDef = IFormMgr.get().queryForm(dao, reportDefFormModelId, reportDefUuid);
        if (reportDef == null) throw new RuntimeException("未找到面板[" + panelCode + "]关联的报表定义");
        return reportDef;
    }

    default String extractReportDefModelId(String panelDesc) {
        if (StrUtil.isBlank(panelDesc)) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<!--REPORT_DEF_MODEL_ID:(.*?)-->");
        java.util.regex.Matcher matcher = pattern.matcher(panelDesc);
        return matcher.find() ? matcher.group(1) : null;
    }

    default Form findConnectionRow(Form reportDef, String connectionId) throws Exception {
        TableData td = reportDef.getTable(ReportDesignConst.Table_DataConnection);
        if (td == null || Op.isEmpty(td)) throw new RuntimeException("报表定义不含数据连接配置");
        for (Form r : td.getRows()) {
            if (connectionId.equals(r.getString(ReportDesignConst.FieldName_ConnectionId))) return r;
        }
        throw new RuntimeException("未找到连接标识[" + connectionId + "]对应的数据连接");
    }

    default ReportJdbcDataSource buildDataSource(Form connRow, String panelCode, String connectionId) throws Exception {
        return DataSourceJdbcUtil.buildDataSource(connRow, panelCode, connectionId);
    }
}
