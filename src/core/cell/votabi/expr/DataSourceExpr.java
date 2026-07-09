package cell.votabi.expr;

import cell.CellIntf;
import cell.cdao.IDao;
import cell.cdao.IDaoService;
import cell.cmn.jdbc.IJDBCService;
import cmn.anotation.ClassDeclare;
import cmn.anotation.InputDeclare;
import cmn.anotation.MethodDeclare;
import cmn.dto.PairDto;
import cmn.dto.SqlStatementDto;
import cmn.dto.sql.dql.JdbcMetaInfoDto;
import cn.hutool.core.util.StrUtil;
import gpf.adur.data.Form;
import octo.cm.exception.business.DomainException;
import votabi.util.DataSourceJdbcUtil;
import votabi.util.ReportJdbcDataSource;
import votabi.util.ReportQueryHelper;

import java.util.List;
import java.util.Map;

@ClassDeclare(
        label = "数据源操作函数",
        what = "数据连接元数据查询与数据集字段探测",
        why = "支撑报表设计器 table/sql 两类数据集配置",
        how = "CellIntf 接口 + JDBC metadata + 轻量查询探测",
        developer = "Devin", version = "1.0",
        createTime = "2026-07-05", updateTime = "2026-07-05"
)
public interface DataSourceExpr extends CellIntf {

    @MethodDeclare(
            label = "获取数据库模式列表", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
                    @InputDeclare(name = "databasePanelCode", label = "数据连接业务面板编号", desc = ""),
                    @InputDeclare(name = "databaseId", label = "数据连接编号", desc = ""),
            }
    )
    default List<String> listDatabaseSchemas(String domain, String databasePanelCode, String databaseId) throws Exception {
        ReportJdbcDataSource ds = loadDataSource(domain, databasePanelCode, databaseId);
        return DataSourceJdbcUtil.listSchemas(ds);
    }

    @MethodDeclare(
            label = "获取数据库表列表", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
                    @InputDeclare(name = "databasePanelCode", label = "数据连接业务面板编号", desc = ""),
                    @InputDeclare(name = "databaseId", label = "数据连接编号", desc = ""),
                    @InputDeclare(name = "schemaName", label = "模式名", desc = ""),
                    @InputDeclare(name = "keyword", label = "关键词", desc = "", nullable = true),
                    @InputDeclare(name = "pageNo", label = "页码", desc = "", nullable = true),
                    @InputDeclare(name = "pageSize", label = "页大小", desc = "", nullable = true),
            }
    )
    default Map<String, Object> listDatabaseTables(String domain, String databasePanelCode, String databaseId,
                                                   String schemaName, String keyword, Integer pageNo, Integer pageSize) throws Exception {
        if (StrUtil.isBlank(schemaName)) throw new RuntimeException("模式名不能为空");
        ReportJdbcDataSource ds = loadDataSource(domain, databasePanelCode, databaseId);
        return DataSourceJdbcUtil.listTables(ds, schemaName, keyword, pageNo, pageSize);
    }

    @MethodDeclare(
            label = "获取源表字段", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
                    @InputDeclare(name = "databasePanelCode", label = "数据连接业务面板编号", desc = ""),
                    @InputDeclare(name = "databaseId", label = "数据连接编号", desc = ""),
                    @InputDeclare(name = "schemaName", label = "模式名", desc = ""),
                    @InputDeclare(name = "sourceTableName", label = "源表名", desc = ""),
            }
    )
    default Map<String, Object> getSourceTableFields(String domain, String databasePanelCode, String databaseId,
                                                     String schemaName, String sourceTableName) throws Exception {
        ReportJdbcDataSource ds = loadDataSource(domain, databasePanelCode, databaseId);
        String querySql = ReportQueryHelper.buildSourceTableSql(schemaName, sourceTableName, ds.getDBType());
        return queryFields(ds, querySql, null);
    }

    @MethodDeclare(
            label = "获取查询SQL字段", how = "", what = "", why = "",
            inputs = {
                    @InputDeclare(name = "domain", label = "业务域编号", desc = "", exampleValue = "$domain$"),
                    @InputDeclare(name = "databasePanelCode", label = "数据连接业务面板编号", desc = ""),
                    @InputDeclare(name = "databaseId", label = "数据连接编号", desc = ""),
                    @InputDeclare(name = "querySql", label = "查询SQL", desc = ""),
                    @InputDeclare(name = "params", label = "设计态测试参数", desc = "", nullable = true),
            }
    )
    default Map<String, Object> getQuerySqlFields(String domain, String databasePanelCode, String databaseId,
                                                  String querySql, Map<String, Object> params) throws Exception {
        if (StrUtil.isBlank(querySql)) throw new RuntimeException("查询SQL不能为空");
        ReportJdbcDataSource ds = loadDataSource(domain, databasePanelCode, databaseId);
        return queryFields(ds, querySql, params);
    }

    default ReportJdbcDataSource loadDataSource(String domain, String databasePanelCode, String databaseId) throws Exception {
        if (StrUtil.isBlank(domain)) throw DomainException.Builder.busDomainCodeEmpty();
        if (StrUtil.isBlank(databasePanelCode)) throw new RuntimeException("数据连接业务面板编号不能为空");
        if (StrUtil.isBlank(databaseId)) throw new RuntimeException("数据连接编号不能为空");

        try (IDao dao = IDaoService.newIDao()) {
            Form connRow = DataSourceJdbcUtil.queryBusinessFormByCode(dao, domain, databasePanelCode, databaseId);
            if (connRow == null) throw new RuntimeException("未找到数据连接[" + databaseId + "]");
            return DataSourceJdbcUtil.buildDataSource(connRow, databasePanelCode, databaseId);
        }
    }

    default Map<String, Object> queryFields(ReportJdbcDataSource ds, String querySql, Map<String, Object> params) throws Exception {
        SqlStatementDto stmt = ReportQueryHelper.buildProbeSqlStatement(querySql, params, ds.getDBType());
        PairDto<List<JdbcMetaInfoDto>, List<List<String>>> pair =
                IJDBCService.get().queryDataWithStatement(ds, stmt);
        return DataSourceJdbcUtil.buildFieldResult(pair == null ? null : pair.getLeft());
    }
}
