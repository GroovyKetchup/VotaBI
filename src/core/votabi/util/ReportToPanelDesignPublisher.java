package votabi.util;

import cell.cdao.IDao;
import cell.gpf.adur.data.IFormMgr;
import cmn.anotation.ClassDeclare;
import cn.hutool.json.JSONObject;
import gpf.adur.data.AssociationData;
import gpf.adur.data.Form;
import gpf.adur.data.ResultSet;
import gpf.adur.data.TableData;
import octo.cm.constant.PanelDesignConst;
import octo.cm.util.PanelCategoryUtil;
import octocm.domain.observer.OctoDomainOpObserver;
import votabi.constant.ReportDesignConst;

import static octo.cm.constant.WorkBenchConst.FormModelId_Axis_Event;
import static octo.cm.constant.WorkBenchConst.SlaveFormModelId_PanelDesign_Constraint_Event;
import static octo.cm.constant.WorkBenchConst.SlaveFormModelId_PanelDesign_View_Orchestration_WebPage;

import org.nutz.dao.entity.annotation.Comment;
import org.nutz.dao.util.cri.SqlExpression;

/**
 * 报表设计发布到面板设计的处理器（一次性实例化使用）。
 *
 * <p>复用公共基类
 * {@link ReportPanelDesignPublisherBase} 的面板物化逻辑，把报表定义一次性物化进目标面板。
 * 报表面板不涉及流程的面板状态/业务编排/面板权限，故只走通用顶层字段 + 面板角色 + 面板数据 +
 * 面板按钮 + 默认视图。</p>
 *
 * <p><b>双向关联：</b>发布后建立关联：
 * <ul>
 *   <li>{@code 面板.Form.Owner = 报表定义.uuid}（本类负责）</li>
 *   <li>{@code 报表定义.”关联面板” = 面板编号}（调用方 {@link cell.votabi.expr.ReportDesignerExpr#publishToPanelDesign} 负责）</li>
 * </ul>
 * 运行态通过报表定义面板配置里的业务面板引用定位数据集/连接。</p>
 *
 * @author Devin
 * @version 1.0
 */
@Comment("报表设计发布到面板设计的处理器")
@ClassDeclare(
        label = "报表设计发布到面板设计的处理器",
        what = "把报表定义一次性物化进目标面板",
        why = "镜像流程发布；建立报表定义和面板关联",
        how = "继承 ReportPanelDesignPublisherBase，复用公共面板物化逻辑",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-06-26"
)
public class ReportToPanelDesignPublisher extends ReportPanelDesignPublisherBase {

    /**
     * 构造报表发布器。
     *
     * @param dao       数据访问会话
     * @param observer  业务域观察者
     * @param modelType 模型类别（一般为”报表”）
     * @param reportDef 报表定义 Form（其 FormModelId 写入目标面板 Owner）
     * @param src       发布源数据（前端映射过来的面板 JSON）
     */
    public ReportToPanelDesignPublisher(IDao dao, OctoDomainOpObserver observer, String modelType, Form reportDef, JSONObject src) {
        super(dao, observer, modelType, reportDef, src);
    }

    /**
     * 报表面板分类：数据看板。
     *
     * @return 面板分类关联值
     * @throws Exception 查询分类失败
     */
    @Override
    protected AssociationData getPanelCategoryAc() throws Exception {
        return PanelCategoryUtil.getAssignCategoryAc(PanelCategoryUtil.CategoryType_DataBoard);
    }

    /**
     * 执行报表发布。
     *
     * @param existedPanelCode 已有的面板编号；为空或后台查无此面板时均走新建分支
     * @return 已落库的面板设计 Form
     * @throws Exception 发布失败
     */
    public Form publish(String existedPanelCode) throws Exception {
        locateOrCreatePanel(existedPanelCode);

        fillTopFields();
        fillPanelRoles();
        fillPanelData();
        fillPanelButtons();
        ensureDefaultCreateButton();
        ensureSystemDefaultButtons();
        // buildDefaultViews();
        ensureInitialWebPageOnNewPanel();
        // 报表面板固定挂一条「操作_执行数据查询」面板事件，供运行态取数
        ensureExecDataQueryPanelEvent();

        // 关联：Owner 存报表定义 uuid（用于关联和联动删除）
        if (defForm != null) {
            panel.setAttrValueByCode(Form.Owner, defForm.getUuid());
        }

        panel = IFormMgr.get().updateForm(null, dao, panel, observer);
        dao.commit();
        return panel;
    }

    /**
     * 报表发布不创建默认表格/表单，但首次创建目标面板时仍需要一个页面入口供菜单发布。
     */
    protected void ensureInitialWebPageOnNewPanel() throws Exception {
        if (!isNewPanel()) {
            return;
        }
        String panelName = panel.getString(PanelDesignConst.FieldName_PanelName);
        if (panelName == null || panelName.trim().isEmpty()) {
            return;
        }

        TableData webPageTd = new TableData(SlaveFormModelId_PanelDesign_View_Orchestration_WebPage);
        Form webPageForm = Op.newForm(webPageTd.getFormModelId());
        webPageForm.setAttrValue(PanelDesignConst.FieldName_PageName, panelName);
        webPageForm.setAttrValue(PanelDesignConst.FieldName_PageCode, buildEmptyPageCode(panelName));
        webPageTd.add(webPageForm);

        panel.setAttrValue(PanelDesignConst.FieldName_PanelWebPage, webPageTd);
        panel.setAttrValue(PanelDesignConst.FieldName_PageEntry, panelName);
    }

    protected String buildEmptyPageCode(String pageName) {
        JSONObject component = new JSONObject();
        component.set("type", "Section");
        component.set("id", "root");
        component.set("props", new JSONObject());
        component.set("children", new cn.hutool.json.JSONArray());

        JSONObject pageSelfData = new JSONObject();
        pageSelfData.set("dataSource", new cn.hutool.json.JSONArray());

        JSONObject page = new JSONObject();
        page.set("pageId", pageName);
        page.set("pageName", pageName);
        page.set("component", component);
        page.set("pageSelfData", pageSelfData);
        return page.toString();
    }

    /**
     * 取事件轴模型（{@link octo.cm.constant.WorkBenchConst#FormModelId_Axis_Event}）里
     * “事件名称”为 {@link ReportDesignConst#Event_ExecuteReportQuery} 的那条事件定义。
     *
     * <p>按字段名分页查询的写法参考 {@link #ensureSystemDefaultButtons()}。</p>
     *
     * @return 该事件定义 Form；不存在时返回 {@code null}
     * @throws Exception 查询失败
     */
    protected Form findExecDataQueryEvent() throws Exception {
        SqlExpression domainFilterExpr = Op.getBusDomainFilterExpr(observer, FormModelId_Axis_Event);
        ResultSet<Form> eventRs = Op.queryFormPageByCondition(dao, FormModelId_Axis_Event,
                PanelDesignConst.FieldName_EventName, ReportDesignConst.Event_ExecuteReportQuery, cnd -> {
                    if (domainFilterExpr != null) {
                        cnd.where().and(domainFilterExpr);
                    }
                    return cnd;
                });
        if (eventRs.isEmpty()) {
            return null;
        }
        return eventRs.getDataList().get(0);
    }

    /**
     * 确保面板的[面板事件]子表里挂有一条“事件实现 = {@link ReportDesignConst#Event_ExecuteReportQuery}”的记录。
     *
     * <p>子表结构与挂载方式参考 {@link #buildDefaultViews()} 里对[面板事件]（行点击）的处理；
     * 去重方式参考 {@link #ensureDefaultCreateButton()}——按事件实现关联值（uuid/编号）比对，已存在则不重复挂载。
     * 追加语义：不覆盖 {@link #buildDefaultViews()} 已写入的行点击事件。</p>
     *
     * @throws Exception 查询/构建失败
     */
    protected void ensureExecDataQueryPanelEvent() throws Exception {
        Form event = findExecDataQueryEvent();
        if (event == null) throw new RuntimeException("未找到" + ReportDesignConst.Event_ExecuteReportQuery + "面板事件，请检查报表设计器面板及其面板事件定义。");

        TableData td = panel.getTable(PanelDesignConst.FieldName_PanelEvent);
        if (td == null) td = new TableData(SlaveFormModelId_PanelDesign_Constraint_Event);

        // 已存在则不重复挂载
        String eventUuid = event.getUuid();
        String eventCode = event.getString(Form.Code);
        for (Form row : td.getRows()) {
            AssociationData ac = row.getAssociation(PanelDesignConst.FieldName_EventImpl);
            if (ac == null) continue;
            String acVal = ac.getValue();
            if (acVal != null && (acVal.equals(eventUuid) || acVal.equals(eventCode))) return;
        }

        Form line = Op.newForm(td.getFormModelId());
        line.setAttrValue(PanelDesignConst.FieldName_EventImpl, Op.toAssociationData(event));
        td.add(line);

        panel.setAttrValue(PanelDesignConst.FieldName_PanelEvent, td);
    }
}
