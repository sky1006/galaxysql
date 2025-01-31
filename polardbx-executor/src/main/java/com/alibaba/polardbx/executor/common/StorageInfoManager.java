/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.common;

import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.common.jdbc.IDataSource;
import com.alibaba.polardbx.common.jdbc.MasterSlave;
import com.alibaba.polardbx.common.model.Group;
import com.alibaba.polardbx.common.model.Group.GroupType;
import com.alibaba.polardbx.common.model.lifecycle.AbstractLifecycle;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;
import com.alibaba.polardbx.config.ConfigDataMode;
import com.alibaba.polardbx.executor.spi.IGroupExecutor;
import com.alibaba.polardbx.gms.metadb.MetaDbDataSource;
import com.alibaba.polardbx.rpc.XConfig;
import com.alibaba.polardbx.rpc.compatible.XDataSource;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author chenmo.cm
 */
public class StorageInfoManager extends AbstractLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(StorageInfoManager.class);

    private final Map<String, StorageInfo> storageInfos;
    private final TopologyHandler topologyHandler;
    private volatile boolean supportXA;
    private volatile boolean supportTso;
    private volatile boolean supportTsoHeartbeat;
    private volatile boolean supportCtsTransaction;
    private volatile boolean supportDeadlockDetection;
    private volatile boolean supportMdlDeadlockDetection;
    private volatile boolean supportsBloomFilter;
    private volatile boolean supportOpenSSL;
    private volatile boolean supportSharedReadView;
    private volatile boolean supportsReturning;
    private boolean readOnly;
    private boolean lowerCaseTableNames;
    private volatile boolean supportHyperLogLog;
    private volatile boolean lessMy56Version;

    /**
     * FastChecker: generate checksum on xdb node
     * Since: 5.4.12 fix
     * Requirement: XDB supports HASHCHECK function
     */
    private volatile boolean supportFastChecker = false;

    public StorageInfoManager(TopologyHandler topologyHandler) {
        storageInfos = new ConcurrentHashMap<>();
        supportXA = false;
        supportsBloomFilter = false;
        supportsReturning = false;

        Preconditions.checkNotNull(topologyHandler);
        this.topologyHandler = topologyHandler;
    }

    public static String getMySqlVersion(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection(MasterSlave.MASTER_ONLY);
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            boolean hasNext = rs.next();
            assert hasNext;
            return rs.getString(1);
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_OTHER, ex,
                "Failed to get MySQL version: " + ex.getMessage());
        }
    }

    public static boolean checkSupportTso(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'innodb_commit_seq'")) {
            boolean hasNext = rs.next();
            return hasNext;
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_TRANS, ex,
                "Failed to check TSO support: " + ex.getMessage());
        }
    }

    public static boolean checkSupportTsoHeartbeat(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'innodb_heartbeat_seq'")) {
            boolean hasNext = rs.next();
            return hasNext;
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_TRANS, ex,
                "Failed to check TSO support: " + ex.getMessage());
        }
    }

    public static boolean checkSupportCtsTransaction(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'innodb_cts_transaction'")) {
            boolean hasNext = rs.next();
            return hasNext;
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_TRANS, ex,
                "Failed to check innodb_cts_transaction support: " + ex.getMessage());
        }
    }

    public static boolean checkIsXEngine(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'xengine_datadir'")) {
            boolean hasNext = rs.next();
            return hasNext;
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_OTHER, ex, "Failed to check xengine: " + ex.getMessage());
        }
    }

    public static boolean checkSupportPerformanceSchema(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'performance_schema'")) {
            boolean hasNext = rs.next();
            return hasNext && StringUtils.equalsIgnoreCase(rs.getString(2), "ON");
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_OTHER, ex,
                "Failed to check performance_schema support: " + ex.getMessage());
        }
    }

    public static boolean checkSupportSharedReadView(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'innodb_transaction_group'")) {
            boolean hasNext = rs.next();
            // 该变量只需要存在就支持，默认为OFF
            return hasNext;
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_TRANS_LOG, ex,
                "Failed to check shared read view support: " + ex.getMessage());
        }
    }

    public static boolean checkSupportReturning(DataSource dataSource) {
        if (XConfig.GALAXY_X_PROTOCOL) {
            return false;
        }
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("call dbms_admin.show_native_procedure()")) {
            boolean supportReturning = false;
            while (rs.next()) {
                final String schemaName = rs.getString(1);
                final String procName = rs.getString(2);
                supportReturning |= "dbms_trans".equalsIgnoreCase(schemaName) && "returning".equalsIgnoreCase(procName);
                if (supportReturning) {
                    break;
                }
            }
            return supportReturning;
        } catch (SQLException ex) {
            final boolean ER_SP_DOES_NOT_EXIST =
                "42000".equalsIgnoreCase(ex.getSQLState()) && 1305 == ex.getErrorCode() && ex.getMessage()
                    .contains("does not exist");
            if (ER_SP_DOES_NOT_EXIST) {
                logger.warn("PROCEDURE dbms_admin.show_native_procedure does not exist");
                return false;
            }

            final boolean ER_PLUGGABLE_PROTOCOL_COMMAND_NOT_SUPPORTED =
                "HY000".equalsIgnoreCase(ex.getSQLState()) && 3130 == ex.getErrorCode() && ex.getMessage()
                    .contains("Command not supported by pluggable protocols");
            if (ER_PLUGGABLE_PROTOCOL_COMMAND_NOT_SUPPORTED) {
                logger.warn("Do not support call dbms_amdin procedures within XPotocol");
                return false;
            }

            throw new TddlRuntimeException(ErrorCode.ERR_OTHER, ex,
                "Failed to check returning support: " + ex.getMessage());
        }
    }

    public static boolean checkMetaDataLocksSelectPrivilege(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT\n"
                + "  `pt`.`PROCESSLIST_ID` AS `waiting`,\n"
                + "  `gt`.`PROCESSLIST_ID` AS `blocking` \n"
                + "FROM\n"
                + "  (((((\n"
                + "            `performance_schema`.`metadata_locks` `g`\n"
                + "            JOIN `performance_schema`.`metadata_locks` `p` ON (((\n"
                + "                  `g`.`OBJECT_TYPE` = `p`.`OBJECT_TYPE` \n"
                + "                  ) \n"
                + "                AND ( `g`.`OBJECT_SCHEMA` = `p`.`OBJECT_SCHEMA` ) \n"
                + "                AND ( `g`.`OBJECT_NAME` = `p`.`OBJECT_NAME` ) \n"
                + "                AND ( `g`.`LOCK_STATUS` = 'GRANTED' ) \n"
                + "              AND ( `p`.`LOCK_STATUS` = 'PENDING' ))))\n"
                + "          JOIN `performance_schema`.`threads` `gt` ON ((\n"
                + "              `g`.`OWNER_THREAD_ID` = `gt`.`THREAD_ID` \n"
                + "            )))\n"
                + "        JOIN `performance_schema`.`threads` `pt` ON ((\n"
                + "            `p`.`OWNER_THREAD_ID` = `pt`.`THREAD_ID` \n"
                + "          )))\n"
                + "      LEFT JOIN `performance_schema`.`events_statements_current` `gs` ON ((\n"
                + "          `g`.`OWNER_THREAD_ID` = `gs`.`THREAD_ID` \n"
                + "        )))\n"
                + "    LEFT JOIN `performance_schema`.`events_statements_current` `ps` ON ((\n"
                + "        `p`.`OWNER_THREAD_ID` = `ps`.`THREAD_ID` \n"
                + "      ))) \n"
                + "WHERE\n"
                + "  ( `g`.`OBJECT_TYPE` = 'TABLE' ) \n"
                + "  AND `pt`.`PROCESSLIST_ID` != `gt`.`PROCESSLIST_ID`"
                + "  AND FALSE"
            );
            return true;
        } catch (SQLException ex) {
            logger.error("Failed to check performance_schema select privilege: " + ex.getMessage());
            return false;
        }
    }

    public static boolean checkMetaDataLocksEnable(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                "select enabled,timed from performance_schema.setup_instruments "
                    + "WHERE NAME = 'wait/lock/metadata/sql/mdl' ")) {
            boolean hasNext = rs.next();
            return hasNext && StringUtils.equalsIgnoreCase(rs.getString(1), "YES");
        } catch (SQLException ex) {
            logger.error("Failed to check performance_schema.metadata_locks: " + ex.getMessage());
            return false;
        }
    }

    /**
     * @see <a href="https://dev.mysql.com/doc/refman/5.7/en/server-status-variables.html#statvar_Rsa_public_key">Rsa_public_key</a>
     */
    public static boolean checkSupportOpenSSL(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW STATUS LIKE 'Rsa_public_key'")) {
            return rs.next();
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_TRANS_LOG, ex,
                "Failed to check openssl support: " + ex.getMessage());
        }
    }

    public static int getLowerCaseTableNames(IDataSource dataSource) {
        try (Connection conn = dataSource.getConnection(MasterSlave.MASTER_ONLY);
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT @@global.lower_case_table_names;")) {
            boolean hasNext = rs.next();
            assert hasNext;
            return rs.getInt(1);
        } catch (SQLException ex) {
            throw new TddlRuntimeException(ErrorCode.ERR_OTHER, ex,
                "Failed to get variable lower_case_table_names: " + ex.getMessage());
        }
    }

    @Override
    protected void doInit() {
        boolean tmpSupportXA = true;
        boolean tmpSupportTso = true;
        boolean tmpSupportTsoHeartbeat = true;
        boolean tmpSupportDeadlockDetection = true;
        boolean tmpSupportMdlDeadlockDetection = true;
        boolean tmpSupportsBloomFilter = true;
        boolean tmpSupportsReturning = true;
        boolean tmpLowerCaseTableNames = true;
        boolean tmpSupportOpenSSL = true;
        boolean tmpSupportSharedReadView = true;
        boolean tmpSupportCtsTransaction = true;
        boolean tmpSupportHyperLogLog = true;
        boolean lessMysql56 = false;
        boolean tmpSupportFastChecker = true;
        boolean tmpRDS80 = true;
        for (Group group : topologyHandler.getMatrix().getGroups()) {
            if (group.getType() == GroupType.MYSQL_JDBC) {
                IGroupExecutor groupExecutor = topologyHandler.get(group.getName());

                final StorageInfo storageInfo = initStorageInfo(group, groupExecutor.getDataSource());
                if (storageInfo != null) {
                    tmpSupportXA &= supportXA(storageInfo);
                    lessMysql56 = lessMysql56 || lessMysql56Version(storageInfo);
                    tmpSupportTso &= storageInfo.supportTso;
                    tmpSupportTsoHeartbeat &= storageInfo.supportTsoHeartbeat;
                    tmpSupportCtsTransaction &= storageInfo.supportCtsTransaction;
                    tmpSupportDeadlockDetection &= supportDeadlockDetection(storageInfo);
                    tmpSupportMdlDeadlockDetection &= supportMdlDeadlockDetection(storageInfo);
                    tmpSupportsBloomFilter &= storageInfo.supportsBloomFilter;
                    tmpSupportOpenSSL &= storageInfo.supportOpenSSL;
                    tmpSupportHyperLogLog &= storageInfo.supportHyperLogLog;
                    tmpSupportsReturning &= storageInfo.supportsReturning;
                    tmpLowerCaseTableNames &= enableLowerCaseTableNames(storageInfo);
                    tmpSupportSharedReadView &= storageInfo.supportSharedReadView;
                    tmpSupportFastChecker &= storageInfo.supportFastChecker;
                    tmpRDS80 &= isRDS80(storageInfo);
                }
            }
        }

        this.readOnly = !ConfigDataMode.isMasterMode();

        // Do not enable XA transaction in read-only instance
        this.supportXA = tmpSupportXA && !readOnly;
        this.supportsBloomFilter = tmpSupportsBloomFilter;
        this.supportsReturning = tmpSupportsReturning;
        this.supportTso = tmpSupportTso && (metaDbUsesXProtocol() || tmpRDS80);
        this.supportTsoHeartbeat = tmpSupportTsoHeartbeat && metaDbUsesXProtocol();
        this.supportCtsTransaction = tmpSupportCtsTransaction;
        this.supportSharedReadView = tmpSupportSharedReadView;
        this.supportDeadlockDetection = tmpSupportDeadlockDetection;
        this.supportMdlDeadlockDetection = tmpSupportMdlDeadlockDetection;
        this.supportOpenSSL = tmpSupportOpenSSL;
        this.lowerCaseTableNames = tmpLowerCaseTableNames;
        this.supportHyperLogLog = tmpSupportHyperLogLog;
        this.lessMy56Version = lessMysql56;
        this.supportFastChecker = tmpSupportFastChecker;
    }

    private boolean metaDbUsesXProtocol() {
        try {
            return MetaDbDataSource.getInstance().getDataSource().isWrapperFor(XDataSource.class);
        } catch (SQLException ex) {
            return false;
        }
    }

    private boolean isRDS80(StorageInfo storageInfo) {
        return storageInfo.version.startsWith("8.0");
    }

    private boolean supportXA(StorageInfo storageInfo) {
        return null == storageInfo
            || (!storageInfo.version.startsWith("5.6") && !storageInfo.version.startsWith("5.5")
            && !storageInfo.isXEngine);
    }

    private boolean lessMysql56Version(StorageInfo storageInfo) {
        return null != storageInfo
            && (storageInfo.version.startsWith("5.6") || storageInfo.version.startsWith("5.5"));
    }

    private boolean supportDeadlockDetection(StorageInfo storageInfo) {
        return null == storageInfo || storageInfo.version.startsWith("5.");
    }

    private boolean supportMdlDeadlockDetection(StorageInfo storageInfo) {
        return null == storageInfo ||
            storageInfo.supportPerformanceSchema
                && storageInfo.hasMetaDataLocksSelectPrivilege
                && storageInfo.isMetaDataLocksEnable;
    }

    private boolean enableLowerCaseTableNames(StorageInfo storageInfo) {
        return null == storageInfo || storageInfo.lowerCaseTableNames != 0;
    }

    @Override
    protected void doDestroy() {
        storageInfos.clear();
        supportXA = false;
        supportsBloomFilter = false;
        supportsReturning = false;
    }

    private StorageInfo initStorageInfo(Group group, IDataSource dataSource) {
        if (group.getType() != GroupType.MYSQL_JDBC) {
            return null;
        }
        StorageInfo storageInfo = StorageInfo.create(dataSource);
        storageInfos.put(group.getName(), storageInfo);

        return storageInfo;
    }

    public boolean supportXA() {
        if (!isInited()) {
            init();
        }

        return supportXA;
    }

    public boolean supportTso() {
        if (!isInited()) {
            init();
        }

        return supportTso;
    }

    public boolean isLessMy56Version() {
        if (!isInited()) {
            init();
        }

        return lessMy56Version;
    }

    public boolean supportTsoHeartbeat() {
        if (!isInited()) {
            init();
        }

        return supportTsoHeartbeat;
    }

    public boolean supportCtsTransaction() {
        if (!isInited()) {
            init();
        }

        return supportCtsTransaction;
    }

    public boolean supportDeadlockDetection() {
        if (!isInited()) {
            init();
        }

        return supportDeadlockDetection;
    }

    public boolean supportMdlDeadlockDetection() {
        if (!isInited()) {
            init();
        }
        return supportMdlDeadlockDetection;
    }

    public boolean supportsBloomFilter() {
        if (!isInited()) {
            init();
        }

        return supportsBloomFilter;
    }

    public boolean supportSharedReadView() {
        if (!isInited()) {
            init();
        }

        return supportSharedReadView;
    }

    public boolean supportOpenSSL() {
        if (!isInited()) {
            init();
        }

        return supportOpenSSL;
    }

    public boolean supportsHyperLogLog() {
        if (!isInited()) {
            init();
        }

        return supportHyperLogLog;
    }

    public boolean supportsReturning() {
        if (!isInited()) {
            init();
        }

        return supportsReturning;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isLowerCaseTableNames() {
        return lowerCaseTableNames;
    }

    public boolean supportFastChecker() {
        if (!isInited()) {
            init();
        }
        return supportFastChecker;
    }

    public static class StorageInfo {

        public final String version;
        public final boolean supportTso;
        private volatile boolean supportTsoHeartbeat;
        public final boolean supportCtsTransaction;
        public final boolean supportsBloomFilter;
        public final boolean supportsReturning;
        public final int lowerCaseTableNames;
        public final boolean supportPerformanceSchema;
        public final boolean isXEngine;
        public final boolean supportSharedReadView;
        boolean hasMetaDataLocksSelectPrivilege;
        boolean isMetaDataLocksEnable;
        public final boolean supportOpenSSL;
        boolean supportHyperLogLog;
        boolean supportFastChecker;

        public StorageInfo(
            String version,
            boolean supportTso,
            boolean supportTsoHeartbeat,
            boolean supportCtsTransaction,
            boolean supportsBloomFilter,
            boolean supportsReturning,
            int lowerCaseTableNames,
            boolean supportPerformanceSchema,
            boolean isXEngine,
            boolean supportSharedReadView,
            boolean hasMetaDataLocksSelectPrivilege,
            boolean isMetaDataLocksEnable,
            boolean supportHyperLogLog,
            boolean supportOpenSSL,
            boolean supportFastChecker
        ) {
            this.version = version;
            this.supportTso = supportTso;
            this.supportTsoHeartbeat = supportTsoHeartbeat;
            this.supportCtsTransaction = supportCtsTransaction;
            this.supportsBloomFilter = supportsBloomFilter;
            this.supportsReturning = supportsReturning;
            this.lowerCaseTableNames = lowerCaseTableNames;
            this.supportPerformanceSchema = supportPerformanceSchema;
            this.isXEngine = isXEngine;
            this.supportSharedReadView = supportSharedReadView;
            this.hasMetaDataLocksSelectPrivilege = hasMetaDataLocksSelectPrivilege;
            this.isMetaDataLocksEnable = isMetaDataLocksEnable;
            this.supportOpenSSL = supportOpenSSL;
            this.supportHyperLogLog = supportHyperLogLog;
            this.supportFastChecker = supportFastChecker;
        }

        public static StorageInfo create(IDataSource dataSource) {
            // mock storage version 5.7
            if (ConfigDataMode.isFastMock()) {
                return new StorageInfo(
                    "5.7",
                    false, false,
                    false, false, false, 1,
                    false, false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false
                );
            }

            String version = getMySqlVersion(dataSource);
            boolean supportTso = checkSupportTso(dataSource);
            boolean supportTsoHeartbeat = checkSupportTsoHeartbeat(dataSource);
            boolean supportPerformanceSchema = checkSupportPerformanceSchema(dataSource);
            boolean isXEngine = checkIsXEngine(dataSource);

            Optional<PolarxUDFInfo> polarxUDFInfo = PolarxUDFInfo.build(dataSource);
            boolean supportsBloomFilter = polarxUDFInfo.map(PolarxUDFInfo::supportsBloomFilter).orElse(false);
            boolean supportsReturning = checkSupportReturning(dataSource);
            boolean supportCtsTransaction = checkSupportCtsTransaction(dataSource);
            final int lowerCaseTableNames = getLowerCaseTableNames(dataSource);
            final boolean supportSharedReadView = checkSupportSharedReadView(dataSource);

            boolean hasMetaDataLocksSelectPrivilege = checkMetaDataLocksSelectPrivilege(dataSource);
            boolean isMetaDataLocksEnable = checkMetaDataLocksEnable(dataSource);
            boolean supportHyperLogLog = polarxUDFInfo.map(PolarxUDFInfo::supportsHyperLogLog).orElse(false);
            boolean supportOpenSSL = checkSupportOpenSSL(dataSource);
            boolean supportFastChecker = polarxUDFInfo.map(PolarxUDFInfo::supportFastChecker).orElse(false);

            return new StorageInfo(version, supportTso, supportTsoHeartbeat, supportCtsTransaction, supportsBloomFilter,
                supportsReturning, lowerCaseTableNames, supportPerformanceSchema, isXEngine, supportSharedReadView,
                hasMetaDataLocksSelectPrivilege, isMetaDataLocksEnable, supportHyperLogLog, supportOpenSSL,
                supportFastChecker);
        }

    }

    public static class PolarxUDFInfo {
        private static final String STATUS_ACTIVE = "ACTIVE";

        private static final String PLUGIN_NAME = "polarx_udf";
        private static final String VAR_FUNCTION_LIST = "polarx_udf_function_list";
        private static final String UDF_BLOOM_FILTER = "bloomfilter";
        private static final String UDF_HYPERLOGLOG = "hyperloglog";
        private static final String UDF_HASHCHECK = "hashcheck";

        private final int majorVersion;
        private final int minorVersion;
        private final String status;
        private final Set<String> functions;

        private PolarxUDFInfo(int majorVersion, int minorVersion, String status,
                              Set<String> functions) {
            this.majorVersion = majorVersion;
            this.minorVersion = minorVersion;
            this.status = status;
            this.functions = functions;
        }

        public static Optional<PolarxUDFInfo> build(DataSource dataSource) {
            try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
                String pluginSql = "select `PLUGIN_VERSION`, `PLUGIN_STATUS` "
                    + " from information_schema.plugins "
                    + " where `PLUGIN_NAME` = '" + PLUGIN_NAME + "';";

                int[] versionParts = new int[2];
                String status;
                Set<String> udfFunctions;
                try (ResultSet rs = stmt.executeQuery(pluginSql)) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }

                    if (!parseVersion(rs.getString(1), versionParts)) {
                        return Optional.empty();
                    }
                    status = rs.getString(2);
                }

                String functionListSql = "SHOW VARIABLES LIKE '" + VAR_FUNCTION_LIST + "';";
                try (ResultSet rs = stmt.executeQuery(functionListSql)) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }

                    String functionListString = rs.getString(2);
                    udfFunctions = Arrays.stream(functionListString.split(",")).collect(Collectors.toSet());
                }

                return Optional.of(new PolarxUDFInfo(versionParts[0], versionParts[1], status, udfFunctions));
            } catch (Exception ex) {
                logger.warn("Failed to check polar udf info", ex);
                return Optional.empty();
            }
        }

        private static boolean parseVersion(String versionString, int[] version) {
            String[] parts = versionString.split("\\.");
            if (parts.length < 2) {
                return false;
            }

            try {
                version[0] = Integer.parseInt(parts[0]);
                version[1] = Integer.parseInt(parts[1]);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to parse polarx udf version string: " + versionString);
                return false;
            }
        }

        public boolean supportsBloomFilter() {
            return majorVersion >= 1
                && minorVersion >= 1
                && STATUS_ACTIVE.equals(status)
                && functions.contains(UDF_BLOOM_FILTER);
        }

        public boolean supportsHyperLogLog() {
            return majorVersion >= 1
                && minorVersion >= 1
                && STATUS_ACTIVE.equals(status)
                && functions.contains(UDF_HYPERLOGLOG);
        }

        public boolean supportFastChecker() {
            return majorVersion >= 1
                && minorVersion >= 1
                && STATUS_ACTIVE.equals(status)
                && functions.contains(UDF_HASHCHECK);
        }
    }
}
