package votabi.util;

import cell.cdao.IDao;
import cell.gpf.adur.data.IFormMgr;
import cell.octo.cm.service.IPanelDesignService;
import cmn.anotation.ClassDeclare;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import gpf.adur.data.AssociationData;
import gpf.adur.data.Form;
import gpf.adur.data.ResultSet;
import gpf.adur.data.TableData;
import octo.cm.util.EasyOperation;
import octo.cm.util.PanelDesignCommonFormUtil;
import octocm.domain.observer.OctoDomainOpObserver;
import org.nutz.dao.Cnd;
import org.nutz.dao.entity.annotation.Comment;
import org.nutz.dao.util.cri.SqlExpression;

import java.util.*;

import static octo.cm.constant.WorkBenchConst.*;

/**
 * “某种设计定义”发布到面板设计的公共处理器基类（一次性实例化使用）。
 *
 * <p>VotaBI 自维护的报表面板发布基类，保留面板物化的通用步骤，但只服务
 * {@code ReportToPanelDesignPublisher}，避免报表发布实现继续混在 workbench 流程设计业务里。</p>
 *
 * <p><b>关键链接：</b>{@link #locateOrCreatePanel(String)} 新建分支会设置
 * {@code panel.setAttrValue(Form.Owner, defForm.getUuid())}，让目标面板归属于其定义（流程定义 / 报表定义），
 * 实现“删定义联动删面板”。</p>
 *
 * <p>数据覆盖语义：所有子表整表覆盖；关联列表整体替换；被取消引用的旧底层 Form 不做物理删除。</p>
 *
 * @author Devin
 * @version 1.0
 */
@Comment("发布到面板设计的公共处理器基类")
@ClassDeclare(
        label = "发布到面板设计的公共处理器基类",
        what = "流程/报表发布到面板共用的面板物化逻辑",
        why = "ReportToPanelDesignPublisher 的报表专用面板发布基类",
        how = "由 ReportToPanelDesignPublisher 继承",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-06-25"
)
public abstract class ReportPanelDesignPublisherBase {

    protected static final EasyOperation Op = EasyOperation.get();

    protected final IDao dao;
    protected final OctoDomainOpObserver observer;
    protected final String modelType;

    /**
     * 被发布的“定义” Form：流程发布时为流程定义，报表发布时为报表定义。
     * <p>新建面板时其 uuid 写入 {@code 目标面板.Form.Owner}（实现“删定义联动删面板”）；
     * 子类也会读它的其它字段（如报表发布取 FormModelId 作描述标记），故按“定义本身”而非“Owner”命名。</p>
     */
    protected final Form defForm;

    protected final JSONObject src;

    /** 处理过程中得到的目标面板 Form */
    protected Form panel;

    /** 本次发布是否走的是新建分支（包括前端给了关联面板但后台找不到的情况） */
    protected boolean isNewPanel = false;

    /**
     * 本次新建分支创建出来的默认“新增”按钮 Form。
     * <p>必须缓存——因为 {@link PanelDesignCommonFormUtil#getOrCreateDefaultCreateButton} 内部
     * 会先 deleteForm 同名旧按钮再重建，如果重复调用第二次，第一次挂到面板按钮子表里的关联就指向了被删除的编号，落库会报
     * APP_DAT_0013-关联模型不存在该编号。</p>
     */
    protected Form defaultCreateBtnForm;

    /** 本次处理过的角色实现：角色编号 -> Form */
    protected final Map<String, Form> roleImplByCode = new HashMap<>();
    /** 本次处理过的角色实现：角色名称 -> Form（备用） */
    protected final Map<String, Form> roleImplByName = new HashMap<>();

    /**
     * 构造发布器。
     *
     * @param dao       数据访问会话
     * @param observer  业务域观察者
     * @param modelType 模型类别（如“流程”“报表”）
     * @param defForm   被发布的定义 Form（流程定义 / 报表定义；新建面板时 uuid 写入面板 Owner）
     * @param src       发布源数据（前端映射过来的面板 JSON）
     */
    protected ReportPanelDesignPublisherBase(IDao dao, OctoDomainOpObserver observer, String modelType, Form defForm, JSONObject src) {
        this.dao = dao;
        this.observer = observer;
        this.modelType = modelType;
        this.defForm = defForm;
        this.src = src;
    }

    /** 本次是否走的是新建分支（含“关联面板找不到”的回退） */
    public boolean isNewPanel() {
        return isNewPanel;
    }

    /**
     * 子类提供目标面板的“面板分类”关联值（流程=流程处理；报表=数据看板）。
     *
     * @return 面板分类关联值
     * @throws Exception 查询分类失败
     */
    protected abstract AssociationData getPanelCategoryAc() throws Exception;

    /**
     * 新建面板 shell 时写入的“面板分类”（仅用于 {@link #locateOrCreatePanel(String)} 新建分支，
     * 会被 {@link #fillTopFields()} 覆盖为最终分类）。默认与 {@link #getPanelCategoryAc()} 一致；
     * 子类可覆盖以保持历史行为（如流程始终用“流程处理”）。
     *
     * @return 新建 shell 的面板分类关联值
     * @throws Exception 查询分类失败
     */
    protected AssociationData getNewPanelShellCategoryAc() throws Exception {
        return getPanelCategoryAc();
    }

    // ========================= 公共面板物化逻辑 =========================

    /**
     * 定位或新建面板设计 Form：关联面板查无此记录时按用户要求回退到新建。
     *
     * @param existedPanelCode 已有的面板编号；为空或后台查无此面板时均走新建分支
     * @throws Exception 查询/新建面板失败
     */
    protected void locateOrCreatePanel(String existedPanelCode) throws Exception {
        if (StrUtil.isNotBlank(existedPanelCode)) {
            Cnd cnd = Op.getBusDomainFilterCondition(observer, FormModelId_PanelDesign);
            cnd.where().andEquals(Op.getFieldCode("面板编号"), existedPanelCode);

            ResultSet<Form> pdRs = IFormMgr.get().queryFormPage(dao, FormModelId_PanelDesign, cnd, 1, 1, true, true);
            if (!pdRs.isEmpty()) {
                this.panel = pdRs.getDataList().get(0);
                this.isNewPanel = false;
                return;
            }
            // 定义声明了关联面板，但后台不存在 → 回退到新建路径
            Op.logf("定义声明的关联面板[{}]不存在，回退到新建路径", existedPanelCode);
        }

        // 新建：复用 IPanelDesignService 的标准流程，会自动分配 IML_xxxxx、挂默认按钮、初始化属性实现
        Form shell = new Form(FormModelId_PanelDesign);

        // 设置依赖，到时候定义删除底下面板也一起联动删掉
        if (defForm != null) {
            shell.setAttrValue(Form.Owner, defForm.getUuid());
        }

        // 提前塞一下面板名称，便于服务端创建默认按钮（XX_新增）时使用真实名字
        String panelName = src.getStr("面板名称");
        if (StrUtil.isNotBlank(panelName)) shell.setAttrValue("面板名称", panelName);
        shell.setAttrValue("面板分类", getNewPanelShellCategoryAc());

        this.panel = IPanelDesignService.get().createPanelDesign(observer, shell);
        this.isNewPanel = true;
    }

    /** 顶层字段：面板名称/面板描述/面板分类（分类由子类经 {@link #getPanelCategoryAc()} 决定） */
    protected void fillTopFields() throws Exception {
        panel.setAttrValue("面板名称", src.getStr("面板名称"));
        panel.setAttrValue("面板描述", StrUtil.blankToDefault(src.getStr("面板描述"), ""));
        panel.setAttrValue("面板分类", getPanelCategoryAc());
    }

    /** 面板角色：关联列表（直接挂在面板上，不是子表） */
    protected void fillPanelRoles() throws Exception {
        roleImplByCode.clear();
        roleImplByName.clear();

        List<AssociationData> acs = new ArrayList<>();
        JSONArray arr = src.getJSONArray("面板角色");
        if (arr == null) {
            panel.setAttrValue("面板角色", acs);
            return;
        }

        for (Object o : arr) {
            JSONObject row = (JSONObject) o;
            String roleCode = row.getStr("角色编号");
            String roleName = row.getStr("角色名称");
            String orgMatch = row.getStr("组织匹配");

            if (StrUtil.isBlank(roleCode)) {
                // 前端约定一定会传，缺失视为脏数据
                throw new RuntimeException(StrUtil.format("面板角色[{}]缺少角色编号", roleName));
            }

            Form roleImpl = PanelDesignCommonFormUtil.queryFormByAssignField(
                    observer, FormModelId_Axis_Role, "角色编号", roleCode);

            if (roleImpl == null) {
                roleImpl = Op.newForm(FormModelId_Axis_Role);
                roleImpl.setAttrValue("角色编号", roleCode);
                roleImpl.setAttrValue("角色名称", roleName);
                roleImpl.setAttrValue("组织匹配", orgMatch);
                roleImpl = IFormMgr.get().createForm(null, dao, roleImpl, observer);
            } else {
                roleImpl.setAttrValue("角色名称", roleName);
                roleImpl.setAttrValue("组织匹配", orgMatch);
                roleImpl = IFormMgr.get().updateForm(null, dao, roleImpl, observer);
            }

            roleImplByCode.put(roleCode, roleImpl);
            if (StrUtil.isNotBlank(roleName)) roleImplByName.put(roleName, roleImpl);

            acs.add(Op.toAssociationData(roleImpl));
        }

        panel.setAttrValue("面板角色", acs);
    }

    /** 面板数据：子表 + 属性实现（按属性编号查/建） */
    protected void fillPanelData() throws Exception {
        TableData td = new TableData(SlaveFormModelId_PanelDesign_Data);

        JSONArray arr = src.getJSONArray("面板数据");
        if (arr == null) {
            panel.setAttrValue("面板数据", td);
            return;
        }

        for (Object o : arr) {
            JSONObject row = (JSONObject) o;
            JSONObject impl = row.getJSONObject("属性实现");

            Form attrImpl = resolveOrCreateAttrImpl(impl);

            Form line = Op.newForm(td.getFormModelId());
            line.setAttrValue("场景属性名称", row.getStr("场景属性名称"));
            line.setAttrValue("场景属性样式", row.getStr("场景属性样式"));
            line.setAttrValue("属性别名", row.getStr("属性别名"));
            line.setAttrValue("是否必填", row.getBool("是否必填", false));
            line.setAttrValue("属性样式", row.getStr("属性样式"));
            line.setAttrValue("默认值", row.getStr("默认值"));
            line.setAttrValue("提示文字", row.getStr("提示文字"));
            if (attrImpl != null) {
                line.setAttrValue("属性实现", Op.toAssociationData(attrImpl));
            }
            td.add(line);
        }

        panel.setAttrValue("面板数据", td);
    }

    /**
     * 按属性编号/名称查或建属性实现 Form。
     *
     * @param impl 前端传来的属性实现 JSON
     * @return 属性实现 Form（impl 为空返回 null）
     * @throws Exception 查/建失败
     */
    protected Form resolveOrCreateAttrImpl(JSONObject impl) throws Exception {
        if (impl == null) return null;
        String attrCode = impl.getStr("属性编号");
        String attrName = impl.getStr("属性名称");

        Form attrImpl = null;
        if (StrUtil.isNotBlank(attrCode)) {
            attrImpl = PanelDesignCommonFormUtil.queryFormByAssignField(
                    observer, FormModelId_Axis_Data, "属性编号", attrCode);
        }
        if (attrImpl == null && StrUtil.isNotBlank(attrName)) {
            // 前端在过渡期可能没传属性编号，按名称兜底一次（不创建副本）
            attrImpl = PanelDesignCommonFormUtil.queryFormByAssignField(
                    observer, FormModelId_Axis_Data, "属性名称", attrName);
        }

        if (attrImpl == null) {
            attrImpl = Op.newForm(FormModelId_Axis_Data);
            attrImpl.setAttrValue(Form.Owner, panel.getUuid());
            attrImpl.setAttrValue("属性编号", StrUtil.blankToDefault(attrCode, ""));
            attrImpl.setAttrValue("属性名称", attrName);
            attrImpl.setAttrValue("属性别名", impl.getStr("属性别名"));
            attrImpl.setAttrValue("是否必填", impl.getBool("是否必填", false));
            attrImpl.setAttrValue("属性样式", impl.getStr("属性样式"));
            attrImpl.setAttrValue("默认值", impl.getStr("默认值"));
            attrImpl.setAttrValue("提示文字", impl.getStr("提示文字"));
            attrImpl = IFormMgr.get().createForm(null, dao, attrImpl, observer);
        } else {
            attrImpl.setAttrValue("属性名称", attrName);
            attrImpl.setAttrValue("属性别名", impl.getStr("属性别名"));
            attrImpl.setAttrValue("是否必填", impl.getBool("是否必填", false));
            attrImpl.setAttrValue("属性样式", impl.getStr("属性样式"));
            attrImpl.setAttrValue("默认值", impl.getStr("默认值"));
            attrImpl.setAttrValue("提示文字", impl.getStr("提示文字"));
            attrImpl = IFormMgr.get().updateForm(null, dao, attrImpl, observer);
        }
        return attrImpl;
    }

    /** 面板按钮：子表 + 按钮实现（按按钮编号查/建，含按钮动作子表，允许动作为空） */
    protected void fillPanelButtons() throws Exception {
        TableData td = new TableData(SlaveFormModelId_PanelDesign_Behavior_Button);

        JSONArray arr = src.getJSONArray("面板按钮");
        if (arr == null) {
            panel.setAttrValue("面板按钮", td);
            return;
        }

        for (Object o : arr) {
            JSONObject row = (JSONObject) o;
            JSONObject impl = row.getJSONObject("面板按钮");
            Form btnImpl = resolveOrCreateButtonImpl(impl);

            Form line = Op.newForm(td.getFormModelId());
            line.setAttrValue("按钮别名", row.getStr("按钮别名"));
            line.setAttrValue("按钮说明", row.getStr("按钮说明"));
            if (btnImpl != null) {
                line.setAttrValue("面板按钮", Op.toAssociationData(btnImpl));
            }
            td.add(line);
        }

        panel.setAttrValue("面板按钮", td);
    }

    /**
     * 按按钮编号查或建按钮实现 Form（含按钮动作子表）。
     *
     * @param impl 前端传来的按钮实现 JSON
     * @return 按钮实现 Form（impl 为空返回 null）
     * @throws Exception 查/建失败
     */
    protected Form resolveOrCreateButtonImpl(JSONObject impl) throws Exception {
        if (impl == null) return null;
        String btnCode = impl.getStr("按钮编号");
        String btnName = impl.getStr("按钮名称");

        Form btnImpl = null;
        if (StrUtil.isNotBlank(btnCode)) {
            btnImpl = PanelDesignCommonFormUtil.queryFormByAssignField(
                    observer, FormModelId_Axis_Button, "按钮编号", btnCode);
        }

        TableData actionTd = buildBtnActionTd(impl.getJSONArray("按钮动作"));

        if (btnImpl == null) {
            btnImpl = Op.newForm(FormModelId_Axis_Button);
            btnImpl.setAttrValue(Form.Owner, panel.getUuid());
            btnImpl.setAttrValue("按钮编号", StrUtil.blankToDefault(btnCode, ""));
            btnImpl.setAttrValue("按钮名称", btnName);
            btnImpl.setAttrValue("别名", impl.getStr("别名"));
            btnImpl.setAttrValue("按钮说明", impl.getStr("按钮说明"));
            btnImpl.setAttrValue("分类标签", impl.getStr("分类标签"));
            btnImpl.setAttrValue("按钮动作", actionTd);
            btnImpl = IFormMgr.get().createForm(null, dao, btnImpl, observer);
        } else {
            btnImpl.setAttrValue("按钮名称", btnName);
            btnImpl.setAttrValue("别名", impl.getStr("别名"));
            btnImpl.setAttrValue("按钮说明", impl.getStr("按钮说明"));
            btnImpl.setAttrValue("分类标签", impl.getStr("分类标签"));
            btnImpl.setAttrValue("按钮动作", actionTd);
            btnImpl = IFormMgr.get().updateForm(null, dao, btnImpl, observer);
        }
        return btnImpl;
    }

    /**
     * 构建按钮动作子表；用户未配置动作时默认给一个「表单保存()」。
     *
     * @param actions 前端传来的按钮动作数组
     * @return 按钮动作子表
     * @throws Exception 构建失败
     */
    protected TableData buildBtnActionTd(JSONArray actions) throws Exception {
        TableData td = new TableData(FormModelId_PanelDesign_Action_Orchestration);
        if (actions != null) {
            for (Object o : actions) {
                JSONObject a = (JSONObject) o;
                Form action = Op.newForm(td.getFormModelId());
                action.setAttrValue("操作函数", a.getStr("操作函数"));
                action.setAttrValue("操作说明", a.getStr("操作说明"));
                td.add(action);
            }
        }
        // 用户未配置动作时，默认给一个「表单保存()」
        if (Op.isEmpty(td)) {
            Form action = new Form(td.getFormModelId());
            action.setAttrValue("操作函数", "表单保存()");
            td.add(action);
        }
        return td;
    }

    /** 新建分支专用：把默认“新增”按钮挂到面板按钮子表，复用面板按钮工具 */
    protected void ensureDefaultCreateButton() throws Exception {
        Form createBtn = obtainDefaultCreateBtn();
        if (createBtn == null) return;

        TableData td = panel.getTable("面板按钮");
        if (td == null) td = new TableData(SlaveFormModelId_PanelDesign_Behavior_Button);

        // 已存在则不重复挂载
        String createBtnUuid = createBtn.getUuid();
        String createBtnCode = createBtn.getString(Form.Code);
        for (Form row : td.getRows()) {
            AssociationData ac = row.getAssociation("面板按钮");
            if (ac == null) continue;
            String acVal = ac.getValue();
            if (acVal != null && (acVal.equals(createBtnUuid) || acVal.equals(createBtnCode))) return;
        }

        Form line = Op.newForm(td.getFormModelId());
        line.setAttrValue("按钮别名", createBtn.getString("别名"));
        line.setAttrValue("按钮说明", createBtn.getString("按钮说明"));
        line.setAttrValue("面板按钮", Op.toAssociationData(createBtn));
        td.add(line);

        panel.setAttrValue("面板按钮", td);
    }

    /**
     * 补充系统默认按钮（刷新/删除/保存/取消等）：查询系统中分类标签为“系统按钮”的预制按钮，
     * 对于面板按钮子表中尚未挂载的，按别名去重后补充挂上去。
     */
    protected void ensureSystemDefaultButtons() throws Exception {
        SqlExpression domainFilterExpr = Op.getBusDomainFilterExpr(observer, FormModelId_Axis_Button);
        ResultSet<Form> systemBtnRs = Op.queryFormPageByCondition(dao, FormModelId_Axis_Button,
                FieldName_CategoryLabel, Text_SystemDefaultButton, cnd -> {
                    if (domainFilterExpr != null) {
                        cnd.where().and(domainFilterExpr);
                    }
                    return cnd;
                });
        if (systemBtnRs.isEmpty()) return;

        TableData td = panel.getTable("面板按钮");
        if (td == null) td = new TableData(SlaveFormModelId_PanelDesign_Behavior_Button);

        // 收集已有别名，避免重复挂载
        Set<String> existingAliases = new HashSet<>();
        for (Form row : td.getRows()) {
            String alias = row.getString("按钮别名");
            if (StrUtil.isNotBlank(alias)) existingAliases.add(alias);
        }

        for (Form systemBtn : systemBtnRs.getDataList()) {
            String btnAlias = systemBtn.getString("别名");
            if (StrUtil.isBlank(btnAlias)) continue;
            if (existingAliases.contains(btnAlias)) continue;

            Form line = Op.newForm(td.getFormModelId());
            line.setAttrValue("按钮别名", btnAlias);
            line.setAttrValue("按钮说明", systemBtn.getString("按钮说明"));
            line.setAttrValue("面板按钮", Op.toAssociationData(systemBtn));
            td.add(line);
        }

        panel.setAttrValue("面板按钮", td);
    }

    /**
     * 新建分支专用：获取/创建默认“新增”按钮，本次发布内只会真正建一次。
     * <p>必须走这个方法访问，不要直接调 {@link PanelDesignCommonFormUtil#getOrCreateDefaultCreateButton}，
     * 因为后者每次都会 delete 同名旧按钮再重建，重复调会使之前挂出去的关联变成“找不到的编号”。</p>
     *
     * @return 默认“新增”按钮 Form
     * @throws Exception 获取/创建失败
     */
    protected Form obtainDefaultCreateBtn() throws Exception {
        if (defaultCreateBtnForm == null) {
            defaultCreateBtnForm = PanelDesignCommonFormUtil.getOrCreateDefaultCreateButton(dao, observer, panel);
        }
        return defaultCreateBtnForm;
    }

    /**
     * 每次发布都完整重建[面板表格] + [面板表单] + [页面入口] + [面板事件](行点击)，Publisher 是唯一数据源。
     *
     * <p>命名/字段填充策略参考 {@code CPanelDesignService.initPanelDesignViewOrchestrationTable/Form}：
     * <ul>
     *   <li>表格名称 = 面板名称（去除特殊字符）</li>
     *   <li>表单名称 = {@code PanelDesignCommonFormUtil.buildPanelFormName(panelName)}</li>
     *   <li>列名/属性 = 面板数据子表里的全部“场景属性名称”</li>
     *   <li>表格菜单 = 面板按钮里名称包含 "刷新" / "&lt;面板名&gt;_新增" / "删除" 的</li>
     *   <li>表格操作列 = 面板按钮里名称包含 "删除" 的</li>
     *   <li>表单按钮 = 面板按钮里名称包含 "保存" / "取消" 的</li>
     *   <li>行点击 = 复用 {@code PanelDesignCommonFormUtil.createDefaultTableRowClickEvent}</li>
     *   <li>页面入口 = 表格名称</li>
     * </ul>
     */
    protected void buildDefaultViews() throws Exception {
        String panelName = panel.getString("面板名称");
        if (StrUtil.isBlank(panelName)) return;

        // 列字段名串：从面板数据子表抽取场景属性名称
        String columnNamesStr = joinSceneAttrNames(panel.getTable("面板数据"));

        // 默认行点击事件（弹出编辑表单）
        Form rowClickEvent = PanelDesignCommonFormUtil.createDefaultTableRowClickEvent(dao, observer, panel);
        AssociationData rowClickEventAc = rowClickEvent != null ? Op.toAssociationData(rowClickEvent) : null;

        // ----- 默认表格 -----
        String tableName = removeSpecialChar(panelName);
        TableData tableTd = new TableData(SlaveFormModelId_PanelDesign_View_Orchestration_Table);
        Form tableRow = Op.newForm(tableTd.getFormModelId());
        tableRow.setAttrValue("表格名称", tableName);
        tableRow.setAttrValue("列名", columnNamesStr);

        TableData panelBtnTd = panel.getTable("面板按钮");
        Set<String> menuKeywords = new HashSet<>();
        menuKeywords.add("刷新");
        menuKeywords.add(StrUtil.format("{}_新增", panelName));
        menuKeywords.add("删除");
        List<String> menuBtnNames = pickButtonNamesByKeyword(panelBtnTd, menuKeywords);

        Set<String> rowOpKeywords = new HashSet<>();
        rowOpKeywords.add("删除");
        List<String> rowBtnNames = pickButtonNamesByKeyword(panelBtnTd, rowOpKeywords);

        if (!menuBtnNames.isEmpty()) {
            tableRow.setAttrValue("菜单", CollUtil.join(menuBtnNames, ","));
        }
        tableRow.setAttrValue("操作列", CollUtil.join(rowBtnNames, ","));

        if (rowClickEventAc != null) {
            tableRow.setAttrValue("事件集合", CollUtil.newArrayList(rowClickEventAc));

            TableData eventTd = new TableData(SlaveFormModelId_PanelDesign_Constraint_Event);
            Form eventRow = Op.newForm(eventTd.getFormModelId());
            eventRow.setAttrValue("事件实现", rowClickEventAc);
            eventTd.add(eventRow);
            panel.setAttrValue("面板事件", eventTd);
        }
        tableTd.add(tableRow);
        panel.setAttrValue("面板表格", tableTd);

        // ----- 默认表单 -----
        TableData formTd = new TableData(SlaveFormModelId_PanelDesign_View_Orchestration_Form);
        Form formRow = Op.newForm(formTd.getFormModelId());
        formRow.setAttrValue("表单名称", PanelDesignCommonFormUtil.buildPanelFormName(panelName));
        formRow.setAttrValue("属性", columnNamesStr);
        formRow.setAttrValue("重置布局", true);

        Set<String> formBtnKeywords = new HashSet<>();
        formBtnKeywords.add("*");
        List<String> formBtnNames = pickButtonNamesByKeyword(panelBtnTd, formBtnKeywords);
        formRow.setAttrValue("按钮", CollUtil.join(formBtnNames, ","));
        formTd.add(formRow);
        panel.setAttrValue("面板表单", formTd);

        // ----- 页面入口 -----
        panel.setAttrValue("页面入口", tableName);
    }

    /**
     * 从[面板数据]子表抽取“场景属性名称”列，逗号拼接。
     *
     * @param dataTd 面板数据子表
     * @return 逗号拼接的场景属性名称串
     * @throws Exception 读取失败
     */
    protected String joinSceneAttrNames(TableData dataTd) throws Exception {
        StringJoiner sj = new StringJoiner(",");
        if (Op.isEmpty(dataTd)) return sj.toString();
        for (Form row : dataTd.getRows()) {
            String name = row.getString("场景属性名称");
            if (StrUtil.isNotBlank(name)) sj.add(name);
        }
        return sj.toString();
    }

    /**
     * 按关键字（“按钮名称”包含即命中）从面板按钮子表挑出按钮名。
     *
     * @param btnTd    面板按钮子表
     * @param keywords 关键字集合（含 "*" 时全部命中）
     * @return 命中的按钮名列表
     * @throws Exception 读取失败
     */
    protected List<String> pickButtonNamesByKeyword(TableData btnTd, Set<String> keywords) throws Exception {
        List<String> result = new ArrayList<>();
        if (Op.isEmpty(btnTd) || keywords == null || keywords.isEmpty()) return result;
        for (Form row : btnTd.getRows()) {
            AssociationData ac = row.getAssociation("面板按钮");
            if (ac == null) continue;
            Form btnImpl = IFormMgr.get().queryFormByCode(dao, ac.getFormModelId(), ac.getValue());
            if (btnImpl == null) continue;
            String btnName = btnImpl.getString("按钮名称");
            if (StrUtil.isBlank(btnName)) continue;
            for (String kw : keywords) {
                if ("*".equals(kw) || (StrUtil.isNotBlank(kw) && btnName.contains(kw))) {
                    result.add(btnName);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 表格名称去除特殊字符（与现有面板设计规则保持一致）。
     *
     * @param s 原始名称
     * @return 去除特殊字符后的名称
     */
    protected String removeSpecialChar(String s) {
        if (StrUtil.isBlank(s)) return s;
        return StrUtil.removeAll(s, '/', '\\', '|', ':', '*', '?', '"', '<', '>', ' ', ';', '&');
    }
}
