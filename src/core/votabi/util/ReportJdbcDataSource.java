package votabi.util;

import cmn.dto.sql.IDBDataSource;
import cmn.dto.sql.IDBDataSourceParam;
import cmn.enums.sql.DBTypeEnum;
import cn.hutool.core.util.StrUtil;
import cmn.anotation.ClassDeclare;
import org.nutz.dao.entity.annotation.Comment;
import votabi.constant.ReportDesignConst;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表设计器自定义 JDBC 数据源。
 *
 * <p>直接实现 {@link IDBDataSource}（不继承平台 DaoDto），把报表定义“数据连接”子表里的一行
 * 映射成 {@code cmn.jdbc} 这套连接池/取数 API 所需的数据源描述。</p>
 *
 * <p><b>稳定唯一 key：</b>{@link #getName()} 返回 {@code panelCode + "." + connectionId}，
 * 作为连接池（{@code IDBPoolService}）里该连接的稳定唯一标识——同一报表面板下的每个连接标识
 * 对应一个独立连接池，重建/探活按此 key 定位。</p>
 *
 * @author Devin
 * @version 1.0
 */
@Comment("报表设计器自定义JDBC数据源")
@ClassDeclare(
        label = "报表设计器自定义JDBC数据源",
        what = "把报表定义数据连接子表的一行映射为 IDBDataSource",
        why = "为真参数化取数/连接探活提供连接池稳定唯一 key",
        how = "实现 IDBDataSource，getName()=panelCode.connectionId",
        developer = "Devin", version = "1.0",
        createTime = "2026-06-25", updateTime = "2026-06-25"
)
public class ReportJdbcDataSource implements IDBDataSource {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String url;
    private final String userName;
    private final String password;
    private final String driverClass;
    private final String dbTypeName;
    private final int minConnection;
    private final int maxConnection;

    /**
     * 全参构造。一般通过 {@link #of(String, String, String, String, String, String, String)} 创建。
     *
     * @param name          连接池稳定唯一 key（{@code panelCode.connectionId}）
     * @param url           JDBC 连接地址
     * @param userName      数据库用户名
     * @param password      数据库密码
     * @param driverClass   数据库驱动类
     * @param dbTypeName    数据库类型名（对应 {@link DBTypeEnum} 的 typeName，可空，空则按驱动推断）
     * @param minConnection 最小连接数
     * @param maxConnection 最大连接数
     */
    public ReportJdbcDataSource(String name, String url, String userName, String password,
                                String driverClass, String dbTypeName, int minConnection, int maxConnection) {
        this.name = name;
        this.url = url;
        this.userName = userName;
        this.password = password;
        this.driverClass = driverClass;
        this.dbTypeName = dbTypeName;
        this.minConnection = minConnection;
        this.maxConnection = maxConnection;
    }

    /**
     * 工厂方法：用默认连接池参数构建数据源。
     *
     * @param panelCode    面板编号（用于拼连接池稳定 key）
     * @param connectionId 连接标识（用于拼连接池稳定 key）
     * @param url          JDBC 连接地址
     * @param userName     数据库用户名
     * @param password     数据库密码
     * @param driverClass  数据库驱动类
     * @param dbTypeName   数据库类型名（可空，空则按驱动推断）
     * @return 数据源实例
     */
    public static ReportJdbcDataSource of(String panelCode, String connectionId, String url, String userName,
                                          String password, String driverClass, String dbTypeName) {
        String dsName = buildDataSourceName(panelCode, connectionId);
        return new ReportJdbcDataSource(dsName, url, userName, password, driverClass, dbTypeName,
                ReportDesignConst.DataSource_MinConnection, ReportDesignConst.DataSource_MaxConnection);
    }

    /**
     * 拼连接池稳定唯一 key：{@code panelCode + "." + connectionId}。
     *
     * @param panelCode    面板编号
     * @param connectionId 连接标识
     * @return 稳定唯一 key
     */
    public static String buildDataSourceName(String panelCode, String connectionId) {
        return StrUtil.blankToDefault(panelCode, "") + ReportDesignConst.DataSourceName_Delimiter
                + StrUtil.blankToDefault(connectionId, "");
    }

    @Override
    public IDBDataSource getDs() {
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Override
    public String getUserName() {
        return userName;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getDriverClass() {
        return driverClass;
    }

    @Override
    public int getMinConnection() {
        return minConnection;
    }

    @Override
    public int getMaxConnection() {
        return maxConnection;
    }

    /**
     * 数据库类型：优先按“数据库类型”字段名解析；为空时按驱动类名推断。
     *
     * @return 数据库类型枚举（解析不到返回 {@link DBTypeEnum#Other}）
     */
    @Override
    public DBTypeEnum getDBType() {
        if (StrUtil.isNotBlank(dbTypeName)) {
            return DBTypeEnum.getTypeByName(dbTypeName);
        }
        return DBTypeEnum.getDBTypeByDriver(driverClass);
    }

    @Override
    public List<? extends IDBDataSourceParam> getParams() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getParamsMap() {
        return new HashMap<>();
    }
}
