/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import java.io.Serializable;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.Stream;
import javax.annotation.Resource;
import org.redkale.net.AsyncGroup;
import org.redkale.service.*;
import static org.redkale.source.DataSources.*;
import org.redkale.util.*;
import static org.redkale.boot.Application.RESNAME_APP_GROUP;
import org.redkale.net.*;

/**
 * DataSource的SQL抽象实现类 <br>
 * 注意: 所有的操作只能作用在一张表上，不能同时变更多张表
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
@Local
@AutoLoad(false)
@SuppressWarnings("unchecked")
@ResourceType(DataSource.class)
public abstract class DataSqlSource extends AbstractService implements DataSource, Function<Class, EntityInfo>, AutoCloseable, Resourcable {

    protected static final Flipper FLIPPER_ONE = new Flipper(1);

    protected final Logger logger = Logger.getLogger(this.getClass().getSimpleName());

    protected String name;

    protected URL persistxml;

    protected Properties readprop;

    protected Properties writeprop;

    protected boolean cacheForbidden;

    protected PoolSource readPool;

    protected PoolSource writePool;

    @Resource(name = RESNAME_APP_GROUP)
    protected AsyncGroup asyncGroup;

    protected final BiFunction<EntityInfo, Object, CharSequence> sqlFormatter;

    protected final BiConsumer futureCompleteConsumer = (r, t) -> {
        if (t != null) logger.log(Level.INFO, "CompletableFuture complete error", (Throwable) t);
    };

    protected final BiFunction<DataSource, EntityInfo, CompletableFuture<List>> fullloader = (s, i)
        -> ((CompletableFuture<Sheet>) querySheetDB(i, false, false, false, null, null, (FilterNode) null)).thenApply(e -> e == null ? new ArrayList() : e.list(true));

    @SuppressWarnings({"OverridableMethodCallInConstructor", "LeakingThisInConstructor"})
    public DataSqlSource(String unitName, URL persistxml, Properties readprop, Properties writeprop) {
        if (readprop == null) readprop = new Properties();
        if (writeprop == null) writeprop = readprop;
        this.name = unitName;
        this.persistxml = persistxml;
        this.readprop = readprop;
        this.writeprop = writeprop;
        this.sqlFormatter = (info, val) -> formatValueToString(info, val);
    }

    @Override
    public void init(AnyValue conf) {
        int maxconns = Math.max(8, Integer.decode(readprop.getProperty(JDBC_CONNECTIONS_LIMIT, "" + Runtime.getRuntime().availableProcessors() * 32)));
        if (readprop != writeprop) maxconns = 0;
        this.cacheForbidden = "NONE".equalsIgnoreCase(readprop.getProperty(JDBC_CACHE_MODE));
        ArrayBlockingQueue queue = maxconns > 0 ? new ArrayBlockingQueue(maxconns) : null;
        Semaphore semaphore = maxconns > 0 ? new Semaphore(maxconns) : null;
        this.readPool = createPoolSource(this, this.asyncGroup, "read", queue, semaphore, readprop);
        this.writePool = createPoolSource(this, this.asyncGroup, "write", queue, semaphore, writeprop);
    }

    public void updateMaxconns(int maxconns) {
        if (readprop == writeprop) {
            Semaphore semaphore = new Semaphore(maxconns);
            this.readPool.setSemaphore(semaphore);
            this.writePool.setSemaphore(semaphore);
            if (readPool instanceof PoolTcpSource) {
                ArrayBlockingQueue connQueue = new ArrayBlockingQueue(maxconns);
                ((PoolTcpSource) readPool).updateConnQueue(connQueue);
                ((PoolTcpSource) writePool).updateConnQueue(connQueue);
            }
        } else {
            int onemax = maxconns / 2;
            this.readPool.setSemaphore(new Semaphore(onemax));
            this.writePool.setSemaphore(new Semaphore(onemax));
            if (readPool instanceof PoolTcpSource) {
                ((PoolTcpSource) readPool).updateConnQueue(new ArrayBlockingQueue(onemax));
                ((PoolTcpSource) writePool).updateConnQueue(new ArrayBlockingQueue(onemax));
            }
        }
    }

    @Override
    public void destroy(AnyValue config) {
        if (readPool != null) readPool.close();
        if (writePool != null) writePool.close();
    }

    @Local
    public abstract int directExecute(String sql);

    @Local
    public abstract int[] directExecute(String... sqls);

    @Local
    public abstract <V> V directQuery(String sql, Function<ResultSet, V> handler);

    //是否异步， 为true则只能调用pollAsync方法，为false则只能调用poll方法
    protected abstract boolean isAsync();

    //index从1开始
    protected abstract String prepareParamSign(int index);

    //创建连接池
    protected abstract PoolSource createPoolSource(DataSource source, AsyncGroup asyncGroup, String rwtype, ArrayBlockingQueue queue, Semaphore semaphore, Properties prop);

    //插入纪录
    protected abstract <T> CompletableFuture<Integer> insertDB(final EntityInfo<T> info, T... entitys);

    //删除记录
    protected abstract <T> CompletableFuture<Integer> deleteDB(final EntityInfo<T> info, Flipper flipper, final String sql);

    //清空表
    protected abstract <T> CompletableFuture<Integer> clearTableDB(final EntityInfo<T> info, final String table, final String sql);

    //删除表
    protected abstract <T> CompletableFuture<Integer> dropTableDB(final EntityInfo<T> info, final String table, final String sql);

    //更新纪录
    protected abstract <T> CompletableFuture<Integer> updateDB(final EntityInfo<T> info, final ChannelContext context, T... entitys);

    //更新纪录
    protected abstract <T> CompletableFuture<Integer> updateDB(final EntityInfo<T> info, Flipper flipper, final String sql, final boolean prepared, Object... params);

    //查询Number Map数据
    protected abstract <T, N extends Number> CompletableFuture<Map<String, N>> getNumberMapDB(final EntityInfo<T> info, final String sql, final FilterFuncColumn... columns);

    //查询Number数据
    protected abstract <T> CompletableFuture<Number> getNumberResultDB(final EntityInfo<T> info, final String sql, final Number defVal, final String column);

    //查询Map数据
    protected abstract <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapDB(final EntityInfo<T> info, final String sql, final String keyColumn);

    //查询Map数据
    protected abstract <T, K extends Serializable, N extends Number> CompletableFuture<Map<K[], N[]>> queryColumnMapDB(final EntityInfo<T> info, final String sql, final ColumnNode[] funcNodes, final String[] groupByColumns);

    //查询单条记录
    protected abstract <T> CompletableFuture<T> findDB(final EntityInfo<T> info, final ChannelContext context, final String sql, final boolean onlypk, final SelectColumn selects);

    //查询单条记录的单个字段
    protected abstract <T> CompletableFuture<Serializable> findColumnDB(final EntityInfo<T> info, final String sql, final boolean onlypk, final String column, final Serializable defValue);

    //判断记录是否存在
    protected abstract <T> CompletableFuture<Boolean> existsDB(final EntityInfo<T> info, final String sql, final boolean onlypk);

    //查询一页数据
    protected abstract <T> CompletableFuture<Sheet<T>> querySheetDB(final EntityInfo<T> info, final boolean readcache, final boolean needtotal, final boolean distinct, final SelectColumn selects, final Flipper flipper, final FilterNode node);

    protected <T> T getEntityValue(EntityInfo<T> info, final SelectColumn sels, final ResultSet set) throws SQLException {
        return info.getEntityValue(sels, set);
    }

    protected <T> Serializable getFieldValue(EntityInfo<T> info, Attribute<T, Serializable> attr, final ResultSet set, int index) throws SQLException {
        return info.getFieldValue(attr, set, index);
    }

    protected <T> Serializable getFieldValue(EntityInfo<T> info, Attribute<T, Serializable> attr, final ResultSet set) throws SQLException {
        return info.getFieldValue(attr, set);
    }

    protected <T> String createSQLOrderby(EntityInfo<T> info, Flipper flipper) {
        return info.createSQLOrderby(flipper);
    }

    protected Map<Class, String> getJoinTabalis(FilterNode node) {
        return node == null ? null : node.getJoinTabalis();
    }

    protected <T> CharSequence createSQLJoin(FilterNode node, final Function<Class, EntityInfo> func, final boolean update, final Map<Class, String> joinTabalis, final Set<String> haset, final EntityInfo<T> info) {
        return node == null ? null : node.createSQLJoin(func, update, joinTabalis, haset, info);
    }

    protected <T> CharSequence createSQLExpress(FilterNode node, final EntityInfo<T> info, final Map<Class, String> joinTabalis) {
        return node == null ? null : node.createSQLExpress(info, joinTabalis);
    }

    @Local
    @Override
    public String getType() {
        return "sql";
    }

    @Override
    public final String resourceName() {
        return name;
    }

    @Local
    @Override
    public void close() throws Exception {
        if (readPool != null) readPool.close();
        if (writePool != null) writePool.close();
    }

    @Local
    public PoolSource getReadPoolSource() {
        return readPool;
    }

    @Local
    public PoolSource getWritePoolSource() {
        return writePool;
    }

    @Local
    @Override
    public EntityInfo apply(Class t) {
        return loadEntityInfo(t);
    }

    protected <T> EntityInfo<T> loadEntityInfo(Class<T> clazz) {
        return EntityInfo.load(clazz, this.cacheForbidden, this.readPool == null ? null : this.readPool.props, this, fullloader);
    }

    protected boolean isOnlyCache(EntityInfo info) {
        return info.isVirtualEntity();
    }

    public <T> EntityCache<T> loadCache(Class<T> clazz) {
        EntityInfo<T> info = loadEntityInfo(clazz);
        return info.getCache();
    }

    /**
     * 将entity的对象全部加载到Cache中去，如果clazz没有被@javax.persistence.Cacheable注解则不做任何事
     *
     * @param <T>   Entity类泛型
     * @param clazz Entity类
     */
    public <T> void refreshCache(Class<T> clazz) {
        EntityInfo<T> info = loadEntityInfo(clazz);
        EntityCache<T> cache = info.getCache();
        if (cache == null) return;
        cache.fullLoadAsync();
    }
    ////检查对象是否都是同一个Entity类

    protected <T> CompletableFuture checkEntity(String action, boolean async, T... entitys) {
        if (entitys.length < 1) return null;
        Class clazz = null;
        for (T val : entitys) {
            if (clazz == null) {
                clazz = val.getClass();
                continue;
            }
            if (clazz != val.getClass()) {
                if (async) {
                    CompletableFuture future = new CompletableFuture<>();
                    future.completeExceptionally(new SQLException("DataSource." + action + " must the same Class Entity, but diff is " + clazz + " and " + val.getClass()));
                    return future;
                }
                throw new RuntimeException("DataSource." + action + " must the same Class Entity, but diff is " + clazz + " and " + val.getClass());
            }
        }
        return null;
    }

    protected <T> CharSequence formatValueToString(final EntityInfo<T> info, Object value) {
        final String dbtype = this.readPool.getDbtype();
        if ("mysql".equals(dbtype)) {
            if (value == null) return null;
            if (value instanceof CharSequence) {
                return new StringBuilder().append('\'').append(value.toString().replace("\\", "\\\\").replace("'", "\\'")).append('\'').toString();
            } else if (!(value instanceof Number) && !(value instanceof java.util.Date)
                && !value.getClass().getName().startsWith("java.sql.") && !value.getClass().getName().startsWith("java.time.")) {
                return new StringBuilder().append('\'').append(info.getJsonConvert().convertTo(value).replace("\\", "\\\\").replace("'", "\\'")).append('\'').toString();
            }
            return String.valueOf(value);
        }
        return info.formatSQLValue(value, null);
    }

    //----------------------------- insert -----------------------------
    /**
     * 新增对象， 必须是Entity对象
     *
     * @param <T>     Entity类泛型
     * @param entitys Entity对象
     *
     * @return 影响的记录条数
     */
    @Override
    public <T> int insert(T... entitys) {
        if (entitys.length == 0) return 0;
        checkEntity("insert", false, entitys);
        final EntityInfo<T> info = loadEntityInfo((Class<T>) entitys[0].getClass());
        if (isOnlyCache(info)) return insertCache(info, entitys);
        return insertDB(info, entitys).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                insertCache(info, entitys);
            }
        }).join();
    }

    @Override
    public final <T> int insert(final Collection<T> entitys) {
        if (entitys == null || entitys.isEmpty()) return 0;
        return insert(entitys.toArray());
    }

    @Override
    public final <T> int insert(final Stream<T> entitys) {
        if (entitys == null) return 0;
        return insert(entitys.toArray());
    }

    @Override
    public <T> CompletableFuture<Integer> insertAsync(T... entitys) {
        if (entitys.length == 0) return CompletableFuture.completedFuture(0);
        CompletableFuture future = checkEntity("insert", true, entitys);
        if (future != null) return future;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) entitys[0].getClass());
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(insertCache(info, entitys));
        }
        if (isAsync()) return insertDB(info, entitys).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    insertCache(info, entitys);
                }
            });
        return CompletableFuture.supplyAsync(() -> insertDB(info, entitys).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                insertCache(info, entitys);
            }
        });
    }

    @Override
    public final <T> CompletableFuture<Integer> insertAsync(final Collection<T> entitys) {
        if (entitys == null || entitys.isEmpty()) return CompletableFuture.completedFuture(0);
        return insertAsync(entitys.toArray());
    }

    @Override
    public final <T> CompletableFuture<Integer> insertAsync(final Stream<T> entitys) {
        if (entitys == null) return CompletableFuture.completedFuture(0);
        return insertAsync(entitys.toArray());
    }

    protected <T> int insertCache(final EntityInfo<T> info, T... entitys) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return 0;
        int c = 0;
        for (final T value : entitys) {
            c += cache.insert(value);
        }
        return c;
    }

    //----------------------------- deleteCompose -----------------------------
    /**
     * 删除对象， 必须是Entity对象
     *
     * @param <T>     Entity类泛型
     * @param entitys Entity对象
     *
     * @return 删除的数据条数
     */
    @Override
    public <T> int delete(T... entitys) {
        if (entitys.length == 0) return -1;
        checkEntity("delete", false, entitys);
        final Class<T> clazz = (Class<T>) entitys[0].getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute primary = info.getPrimary();
        Serializable[] ids = new Serializable[entitys.length];
        int i = 0;
        for (final T value : entitys) {
            ids[i++] = (Serializable) primary.get(value);
        }
        return delete(clazz, ids);
    }

    @Override
    public <T> CompletableFuture<Integer> deleteAsync(final T... entitys) {
        if (entitys.length == 0) return CompletableFuture.completedFuture(-1);
        CompletableFuture future = checkEntity("delete", true, entitys);
        if (future != null) return future;
        final Class<T> clazz = (Class<T>) entitys[0].getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute primary = info.getPrimary();
        Serializable[] ids = new Serializable[entitys.length];
        int i = 0;
        for (final T value : entitys) {
            ids[i++] = (Serializable) primary.get(value);
        }
        return deleteAsync(clazz, ids);
    }

    @Override
    public <T> int delete(Class<T> clazz, Serializable... pks) {
        if (pks.length == 0) return -1;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return deleteCache(info, -1, pks);
        return deleteCompose(info, pks).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                deleteCache(info, rs, pks);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> deleteAsync(final Class<T> clazz, final Serializable... pks) {
        if (pks.length == 0) return CompletableFuture.completedFuture(-1);
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(deleteCache(info, -1, pks));
        }
        if (isAsync()) return deleteCompose(info, pks).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    deleteCache(info, rs, pks);
                }
            });
        return CompletableFuture.supplyAsync(() -> deleteCompose(info, pks).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                deleteCache(info, rs, pks);
            }
        });
    }

    @Override
    public <T> int delete(Class<T> clazz, FilterNode node) {
        return delete(clazz, (Flipper) null, node);
    }

    @Override
    public <T> CompletableFuture<Integer> deleteAsync(final Class<T> clazz, final FilterNode node) {
        return deleteAsync(clazz, (Flipper) null, node);
    }

    @Override
    public <T> int delete(Class<T> clazz, final Flipper flipper, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return deleteCache(info, -1, flipper, node);
        return this.deleteCompose(info, flipper, node).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                deleteCache(info, rs, flipper, node);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> deleteAsync(final Class<T> clazz, final Flipper flipper, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(deleteCache(info, -1, flipper, node));
        }
        if (isAsync()) return this.deleteCompose(info, flipper, node).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    deleteCache(info, rs, flipper, node);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.deleteCompose(info, flipper, node).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                deleteCache(info, rs, flipper, node);
            }
        });
    }

    protected <T> CompletableFuture<Integer> deleteCompose(final EntityInfo<T> info, final Serializable... pks) {
        if (pks.length == 1) {
            String sql = "DELETE FROM " + info.getTable(pks[0]) + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(info.getPrimarySQLColumn(), pks[0], sqlFormatter);
            return deleteDB(info, null, sql);
        }
        String sql = "DELETE FROM " + info.getTable(pks[0]) + " WHERE " + info.getPrimarySQLColumn() + " IN (";
        for (int i = 0; i < pks.length; i++) {
            if (i > 0) sql += ',';
            sql += info.formatSQLValue(info.getPrimarySQLColumn(), pks[i], sqlFormatter);
        }
        sql += ")";
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " delete sql=" + sql);
        return deleteDB(info, null, sql);
    }

    protected <T> CompletableFuture<Integer> deleteCompose(final EntityInfo<T> info, final Flipper flipper, final FilterNode node) {
        Map<Class, String> joinTabalis = node.getJoinTabalis();
        CharSequence join = node.createSQLJoin(this, true, joinTabalis, new HashSet<>(), info);
        CharSequence where = node.createSQLExpress(info, joinTabalis);

        StringBuilder join1 = null;
        StringBuilder join2 = null;
        if (join != null) {
            String joinstr = join.toString();
            join1 = multisplit('[', ']', ",", new StringBuilder(), joinstr, 0);
            join2 = multisplit('{', '}', " AND ", new StringBuilder(), joinstr, 0);
        }
        String sql = "DELETE " + ("mysql".equals(this.readPool.getDbtype()) ? "a" : "") + " FROM " + info.getTable(node) + " a" + (join1 == null ? "" : (", " + join1))
            + ((where == null || where.length() == 0) ? (join2 == null ? "" : (" WHERE " + join2))
            : (" WHERE " + where + (join2 == null ? "" : (" AND " + join2)))) + info.createSQLOrderby(flipper);
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " delete sql="
                + (sql + ((flipper == null || flipper.getLimit() < 1) ? "" : (" LIMIT " + flipper.getLimit()))));
        return deleteDB(info, flipper, sql);
    }

    //----------------------------- clearTableCompose -----------------------------
    @Override
    public <T> int clearTable(Class<T> clazz) {
        return clearTable(clazz, (FilterNode) null);
    }

    @Override
    public <T> int clearTable(Class<T> clazz, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return clearTableCache(info, node);
        return this.clearTableCompose(info, node).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                clearTableCache(info, node);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> clearTableAsync(final Class<T> clazz) {
        return clearTableAsync(clazz, (FilterNode) null);
    }

    @Override
    public <T> CompletableFuture<Integer> clearTableAsync(final Class<T> clazz, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(clearTableCache(info, node));
        }
        if (isAsync()) return this.clearTableCompose(info, node).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    clearTableCache(info, node);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.clearTableCompose(info, node).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                clearTableCache(info, node);
            }
        });
    }

    protected <T> CompletableFuture<Integer> clearTableCompose(final EntityInfo<T> info, final FilterNode node) {
        final String table = info.getTable(node);
        String sql = "TRUNCATE TABLE " + table;
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " clearTable sql=" + sql);
        return clearTableDB(info, table, sql);
    }

    //----------------------------- dropTableCompose -----------------------------
    @Override
    public <T> int dropTable(Class<T> clazz) {
        return dropTable(clazz, (FilterNode) null);
    }

    @Override
    public <T> int dropTable(Class<T> clazz, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return dropTableCache(info, node);
        return this.dropTableCompose(info, node).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                dropTableCache(info, node);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> dropTableAsync(final Class<T> clazz) {
        return dropTableAsync(clazz, (FilterNode) null);
    }

    @Override
    public <T> CompletableFuture<Integer> dropTableAsync(final Class<T> clazz, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(dropTableCache(info, node));
        }
        if (isAsync()) return this.dropTableCompose(info, node).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    dropTableCache(info, node);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.dropTableCompose(info, node).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                dropTableCache(info, node);
            }
        });
    }

    protected <T> CompletableFuture<Integer> dropTableCompose(final EntityInfo<T> info, final FilterNode node) {
        final String table = info.getTable(node);
        String sql = "DROP TABLE " + table;
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " dropTable sql=" + sql);
        return dropTableDB(info, table, sql);
    }

    protected <T> int clearTableCache(final EntityInfo<T> info, FilterNode node) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return -1;
        return cache.clear();
    }

    protected <T> int dropTableCache(final EntityInfo<T> info, FilterNode node) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return -1;
        return cache.drop();
    }

    protected <T> int deleteCache(final EntityInfo<T> info, int count, Flipper flipper, FilterNode node) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return -1;
        Serializable[] ids = cache.delete(flipper, node);
        return count >= 0 ? count : (ids == null ? 0 : ids.length);
    }

    protected <T> int deleteCache(final EntityInfo<T> info, int count, Serializable... pks) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return -1;
        int c = 0;
        for (Serializable key : pks) {
            c += cache.delete(key);
        }
        return count >= 0 ? count : c;
    }

    protected static StringBuilder multisplit(char ch1, char ch2, String split, StringBuilder sb, String str, int from) {
        if (str == null) return sb;
        int pos1 = str.indexOf(ch1, from);
        if (pos1 < 0) return sb;
        int pos2 = str.indexOf(ch2, from);
        if (pos2 < 0) return sb;
        if (sb.length() > 0) sb.append(split);
        sb.append(str.substring(pos1 + 1, pos2));
        return multisplit(ch1, ch2, split, sb, str, pos2 + 1);
    }

    //---------------------------- update ----------------------------
    /**
     * 更新对象， 必须是Entity对象
     *
     * @param <T>     Entity类泛型
     * @param entitys Entity对象
     *
     * @return 更新的数据条数
     */
    @Override
    public <T> int update(T... entitys) {
        if (entitys.length == 0) return -1;
        checkEntity("update", false, entitys);
        final Class<T> clazz = (Class<T>) entitys[0].getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, entitys);
        return updateDB(info, null, entitys).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, entitys);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateAsync(final T... entitys) {
        return updateAsync((ChannelContext) null, entitys);
    }

    @Override
    public <T> CompletableFuture<Integer> updateAsync(final ChannelContext context, final T... entitys) {
        if (entitys.length == 0) return CompletableFuture.completedFuture(-1);
        CompletableFuture future = checkEntity("update", true, entitys);
        if (future != null) return future;
        final Class<T> clazz = (Class<T>) entitys[0].getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, entitys));
        }
        if (isAsync()) return updateDB(info, context, entitys).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, entitys);
                }
            });
        return CompletableFuture.supplyAsync(() -> updateDB(info, context, entitys).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, entitys);
            }
        });
    }

    /**
     * 根据主键值更新对象的column对应的值， 必须是Entity Class
     *
     * @param <T>    Entity类的泛型
     * @param clazz  Entity类
     * @param pk     主键值
     * @param column 过滤字段名
     * @param colval 过滤字段值
     *
     * @return 更新的数据条数
     */
    @Override
    public <T> int updateColumn(Class<T> clazz, Serializable pk, String column, Serializable colval) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, pk, column, colval);
        return updateColumnCompose(info, pk, column, colval).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, pk, column, colval);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final Class<T> clazz, final Serializable pk, final String column, final Serializable colval) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, pk, column, colval));
        }
        if (isAsync()) return updateColumnCompose(info, pk, column, colval).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, pk, column, colval);
                }
            });
        return CompletableFuture.supplyAsync(() -> updateColumnCompose(info, pk, column, colval).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, pk, column, colval);
            }
        });
    }

    protected <T> CompletableFuture<Integer> updateColumnCompose(final EntityInfo<T> info, Serializable pk, String column, final Serializable colval) {
        if (colval instanceof byte[]) {
            String sql = "UPDATE " + info.getTable(pk) + " SET " + info.getSQLColumn(null, column) + "=" + prepareParamSign(1) + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(info.getPrimarySQLColumn(), pk, sqlFormatter);
            return updateDB(info, null, sql, true, colval);
        } else {
            String sql = "UPDATE " + info.getTable(pk) + " SET " + info.getSQLColumn(null, column) + "="
                + info.formatSQLValue(column, colval, sqlFormatter) + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(info.getPrimarySQLColumn(), pk, sqlFormatter);
            return updateDB(info, null, sql, false);
        }
    }

    /**
     * 根据主键值更新对象的column对应的值， 必须是Entity Class
     *
     * @param <T>    Entity类的泛型
     * @param clazz  Entity类
     * @param column 过滤字段名
     * @param colval 过滤字段值
     * @param node   过滤node 不能为null
     *
     * @return 更新的数据条数
     */
    @Override
    public <T> int updateColumn(Class<T> clazz, String column, Serializable colval, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, column, colval, node);
        return this.updateColumnCompose(info, column, colval, node).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, column, colval, node);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final Class<T> clazz, final String column, final Serializable colval, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, column, colval, node));
        }
        if (isAsync()) return this.updateColumnCompose(info, column, colval, node).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, column, colval, node);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.updateColumnCompose(info, column, colval, node).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, column, colval, node);
            }
        });
    }

    protected <T> CompletableFuture<Integer> updateColumnCompose(final EntityInfo<T> info, final String column, final Serializable colval, final FilterNode node) {
        Map<Class, String> joinTabalis = node.getJoinTabalis();
        CharSequence join = node.createSQLJoin(this, true, joinTabalis, new HashSet<>(), info);
        CharSequence where = node.createSQLExpress(info, joinTabalis);

        StringBuilder join1 = null;
        StringBuilder join2 = null;
        if (join != null) {
            String joinstr = join.toString();
            join1 = multisplit('[', ']', ",", new StringBuilder(), joinstr, 0);
            join2 = multisplit('{', '}', " AND ", new StringBuilder(), joinstr, 0);
        }
        String alias = "postgresql".equals(writePool.dbtype) ? null : "a"; //postgresql的BUG， UPDATE的SET中不能含别名
        if (colval instanceof byte[]) {
            String sql = "UPDATE " + info.getTable(node) + " a " + (join1 == null ? "" : (", " + join1))
                + " SET " + info.getSQLColumn(alias, column) + "=" + prepareParamSign(1)
                + ((where == null || where.length() == 0) ? (join2 == null ? "" : (" WHERE " + join2))
                : (" WHERE " + where + (join2 == null ? "" : (" AND " + join2))));
            return updateDB(info, null, sql, true, colval);
        } else {
            String sql = "UPDATE " + info.getTable(node) + " a " + (join1 == null ? "" : (", " + join1))
                + " SET " + info.getSQLColumn(alias, column) + "=" + info.formatSQLValue(colval, sqlFormatter)
                + ((where == null || where.length() == 0) ? (join2 == null ? "" : (" WHERE " + join2))
                : (" WHERE " + where + (join2 == null ? "" : (" AND " + join2))));
            return updateDB(info, null, sql, false);
        }
    }

    /**
     * 根据主键值更新对象的多个column对应的值， 必须是Entity Class
     *
     * @param <T>    Entity类的泛型
     * @param clazz  Entity类
     * @param pk     主键值
     * @param values 字段值
     *
     * @return 更新的数据条数
     */
    @Override
    public <T> int updateColumn(final Class<T> clazz, final Serializable pk, final ColumnValue... values) {
        if (values == null || values.length < 1) return -1;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, pk, values);
        return this.updateColumnCompose(info, pk, values).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, pk, values);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final Class<T> clazz, final Serializable pk, final ColumnValue... values) {
        if (values == null || values.length < 1) return CompletableFuture.completedFuture(-1);
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, pk, values));
        }
        if (isAsync()) return this.updateColumnCompose(info, pk, values).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, pk, values);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.updateColumnCompose(info, pk, values).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, pk, values);
            }
        });
    }

    protected <T> CompletableFuture<Integer> updateColumnCompose(final EntityInfo<T> info, final Serializable pk, final ColumnValue... values) {
        StringBuilder setsql = new StringBuilder();
        List<byte[]> blobs = null;
        int index = 0;
        for (ColumnValue col : values) {
            if (col == null) continue;
            Attribute<T, Serializable> attr = info.getUpdateAttribute(col.getColumn());
            if (attr == null) throw new RuntimeException(info.getType() + " cannot found column " + col.getColumn());
            if (setsql.length() > 0) setsql.append(", ");
            String sqlColumn = info.getSQLColumn(null, col.getColumn());
            if (col.getValue() instanceof byte[]) {
                if (blobs == null) blobs = new ArrayList<>();
                blobs.add((byte[]) col.getValue());
                setsql.append(sqlColumn).append("=").append(prepareParamSign(++index));
            } else {
                setsql.append(sqlColumn).append("=").append(info.formatSQLValue(sqlColumn, attr, col, sqlFormatter));
            }
        }
        if (setsql.length() < 1) return CompletableFuture.completedFuture(0);
        String sql = "UPDATE " + info.getTable(pk) + " SET " + setsql + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(info.getPrimarySQLColumn(), pk, sqlFormatter);
        if (blobs == null) return updateDB(info, null, sql, false);
        return updateDB(info, null, sql, true, blobs.toArray());
    }

    /**
     * 根据主键值更新对象的多个column对应的值， 必须是Entity Class
     *
     * @param <T>    Entity类的泛型
     * @param clazz  Entity类
     * @param node   过滤条件
     * @param values 字段值
     *
     * @return 更新的数据条数
     */
    @Override
    public <T> int updateColumn(final Class<T> clazz, final FilterNode node, final ColumnValue... values) {
        return updateColumn(clazz, node, null, values);
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final Class<T> clazz, final FilterNode node, final ColumnValue... values) {
        return updateColumnAsync(clazz, node, null, values);
    }

    @Override
    public <T> int updateColumn(final Class<T> clazz, final FilterNode node, final Flipper flipper, final ColumnValue... values) {
        if (values == null || values.length < 1) return -1;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, node, flipper, values);
        return this.updateColumnCompose(info, node, flipper, values).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, node, flipper, values);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final Class<T> clazz, final FilterNode node, final Flipper flipper, final ColumnValue... values) {
        if (values == null || values.length < 1) return CompletableFuture.completedFuture(-1);
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, node, flipper, values));
        }
        if (isAsync()) return this.updateColumnCompose(info, node, flipper, values).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, node, flipper, values);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.updateColumnCompose(info, node, flipper, values).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, node, flipper, values);
            }
        });
    }

    protected <T> CompletableFuture<Integer> updateColumnCompose(final EntityInfo<T> info, final FilterNode node, final Flipper flipper, final ColumnValue... values) {
        StringBuilder setsql = new StringBuilder();
        List<byte[]> blobs = null;
        int index = 0;
        String alias = "postgresql".equals(writePool.dbtype) ? null : "a"; //postgresql的BUG， UPDATE的SET中不能含别名
        for (ColumnValue col : values) {
            if (col == null) continue;
            Attribute<T, Serializable> attr = info.getUpdateAttribute(col.getColumn());
            if (attr == null) continue;
            if (setsql.length() > 0) setsql.append(", ");
            String sqlColumn = info.getSQLColumn(alias, col.getColumn());
            if (col.getValue() instanceof byte[]) {
                if (blobs == null) blobs = new ArrayList<>();
                blobs.add((byte[]) col.getValue());
                setsql.append(sqlColumn).append("=").append(prepareParamSign(++index));
            } else {
                setsql.append(sqlColumn).append("=").append(info.formatSQLValue(sqlColumn, attr, col, sqlFormatter));
            }
        }
        if (setsql.length() < 1) return CompletableFuture.completedFuture(0);
        Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        CharSequence join = node == null ? null : node.createSQLJoin(this, true, joinTabalis, new HashSet<>(), info);
        CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        StringBuilder join1 = null;
        StringBuilder join2 = null;
        if (join != null) {
            String joinstr = join.toString();
            join1 = multisplit('[', ']', ",", new StringBuilder(), joinstr, 0);
            join2 = multisplit('{', '}', " AND ", new StringBuilder(), joinstr, 0);
        }
        String sql = "UPDATE " + info.getTable(node) + " a " + (join1 == null ? "" : (", " + join1)) + " SET " + setsql
            + ((where == null || where.length() == 0) ? (join2 == null ? "" : (" WHERE " + join2))
            : (" WHERE " + where + (join2 == null ? "" : (" AND " + join2))))
            + info.createSQLOrderby(flipper);
        if (blobs == null) return updateDB(info, null, sql, false);
        return updateDB(info, flipper, sql, true, blobs.toArray());
    }

    @Override
    public <T> int updateColumn(final T entity, final String... columns) {
        return updateColumn(entity, SelectColumn.includes(columns));
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final T entity, final String... columns) {
        return updateColumnAsync(entity, SelectColumn.includes(columns));
    }

    @Override
    public <T> int updateColumn(final T entity, final FilterNode node, final String... columns) {
        return updateColumn(entity, node, SelectColumn.includes(columns));
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final T entity, final FilterNode node, final String... columns) {
        return updateColumnAsync(entity, node, SelectColumn.includes(columns));
    }

    @Override
    public <T> int updateColumn(final T entity, final SelectColumn selects) {
        if (entity == null || selects == null) return -1;
        Class<T> clazz = (Class) entity.getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, false, entity, null, selects);
        return this.updateColumnCompose(info, false, entity, null, selects).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, false, entity, null, selects);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final T entity, final SelectColumn selects) {
        if (entity == null || selects == null) return CompletableFuture.completedFuture(-1);
        Class<T> clazz = (Class) entity.getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, false, entity, null, selects));
        }
        if (isAsync()) return this.updateColumnCompose(info, false, entity, null, selects).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, false, entity, null, selects);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.updateColumnCompose(info, false, entity, null, selects).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, false, entity, null, selects);
            }
        });
    }

    @Override
    public <T> int updateColumn(final T entity, final FilterNode node, final SelectColumn selects) {
        if (entity == null || node == null || selects == null) return -1;
        Class<T> clazz = (Class) entity.getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) return updateCache(info, -1, true, entity, node, selects);
        return this.updateColumnCompose(info, true, entity, node, selects).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, true, entity, node, selects);
            }
        }).join();
    }

    @Override
    public <T> CompletableFuture<Integer> updateColumnAsync(final T entity, final FilterNode node, final SelectColumn selects) {
        if (entity == null || node == null || selects == null) return CompletableFuture.completedFuture(-1);
        Class<T> clazz = (Class) entity.getClass();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (isOnlyCache(info)) {
            return CompletableFuture.completedFuture(updateCache(info, -1, true, entity, node, selects));
        }
        if (isAsync()) return this.updateColumnCompose(info, true, entity, node, selects).whenComplete((rs, t) -> {
                if (t != null) {
                    futureCompleteConsumer.accept(rs, t);
                } else {
                    updateCache(info, rs, true, entity, node, selects);
                }
            });
        return CompletableFuture.supplyAsync(() -> this.updateColumnCompose(info, true, entity, node, selects).join(), getExecutor()).whenComplete((rs, t) -> {
            if (t != null) {
                futureCompleteConsumer.accept(rs, t);
            } else {
                updateCache(info, rs, true, entity, node, selects);
            }
        });
    }

    protected <T> CompletableFuture<Integer> updateColumnCompose(final EntityInfo<T> info, final boolean neednode, final T entity, final FilterNode node, final SelectColumn selects) {
        StringBuilder setsql = new StringBuilder();
        List<byte[]> blobs = null;
        int index = 0;
        String alias = "postgresql".equals(writePool.dbtype) ? null : "a"; //postgresql的BUG， UPDATE的SET中不能含别名
        for (Attribute<T, Serializable> attr : info.updateAttributes) {
            if (!selects.test(attr.field())) continue;
            if (setsql.length() > 0) setsql.append(", ");
            setsql.append(info.getSQLColumn(alias, attr.field()));
            Serializable val = info.getFieldValue(attr, entity);
            if (val instanceof byte[]) {
                if (blobs == null) blobs = new ArrayList<>();
                blobs.add((byte[]) val);
                setsql.append("=").append(prepareParamSign(++index));
            } else {
                CharSequence sqlval = info.formatSQLValue(val, sqlFormatter);
                if (sqlval == null && info.isNotNullJson(attr)) sqlval = "''";
                setsql.append("=").append(sqlval);
            }
        }
        if (neednode) {
            Map<Class, String> joinTabalis = node.getJoinTabalis();
            CharSequence join = node.createSQLJoin(this, true, joinTabalis, new HashSet<>(), info);
            CharSequence where = node.createSQLExpress(info, joinTabalis);
            StringBuilder join1 = null;
            StringBuilder join2 = null;
            if (join != null) {
                String joinstr = join.toString();
                join1 = multisplit('[', ']', ",", new StringBuilder(), joinstr, 0);
                join2 = multisplit('{', '}', " AND ", new StringBuilder(), joinstr, 0);
            }
            String sql = "UPDATE " + info.getTable(node) + " a " + (join1 == null ? "" : (", " + join1)) + " SET " + setsql
                + ((where == null || where.length() == 0) ? (join2 == null ? "" : (" WHERE " + join2))
                : (" WHERE " + where + (join2 == null ? "" : (" AND " + join2))));
            if (blobs == null) return updateDB(info, null, sql, false);
            return updateDB(info, null, sql, true, blobs.toArray());
        } else {
            final Serializable id = (Serializable) info.getSQLValue(info.getPrimary(), entity);
            String sql = "UPDATE " + info.getTable(id) + " a SET " + setsql + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(id, sqlFormatter);
            if (blobs == null) return updateDB(info, null, sql, false);
            return updateDB(info, null, sql, true, blobs.toArray());
        }
    }

    protected <T> int updateCache(final EntityInfo<T> info, int count, final boolean neednode, final T entity, final FilterNode node, final SelectColumn selects) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return count;
        final List<Attribute<T, Serializable>> attrs = new ArrayList<>();
        for (Attribute<T, Serializable> attr : info.updateAttributes) {
            if (!selects.test(attr.field())) continue;
            attrs.add(attr);
        }
        if (neednode) {
            T[] rs = cache.update(entity, attrs, node);
            return count >= 0 ? count : (rs == null ? 0 : rs.length);
        } else {
            T rs = cache.update(entity, attrs);
            return count >= 0 ? count : (rs == null ? 0 : 1);
        }
    }

    protected <T> int updateCache(final EntityInfo<T> info, int count, final FilterNode node, final Flipper flipper, final ColumnValue... values) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return count;
        final List<Attribute<T, Serializable>> attrs = new ArrayList<>();
        final List<ColumnValue> cols = new ArrayList<>();
        for (ColumnValue col : values) {
            if (col == null) continue;
            Attribute<T, Serializable> attr = info.getUpdateAttribute(col.getColumn());
            if (attr == null) continue;
            attrs.add(attr);
            cols.add(col);
        }
        T[] rs = cache.updateColumn(node, flipper, attrs, cols);
        return count >= 0 ? count : (rs == null ? 0 : 1);
    }

    protected <T> int updateCache(final EntityInfo<T> info, int count, final Serializable pk, final ColumnValue... values) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return count;
        final List<Attribute<T, Serializable>> attrs = new ArrayList<>();
        final List<ColumnValue> cols = new ArrayList<>();
        for (ColumnValue col : values) {
            if (col == null) continue;
            Attribute<T, Serializable> attr = info.getUpdateAttribute(col.getColumn());
            if (attr == null) continue;
            attrs.add(attr);
            cols.add(col);
        }
        T rs = cache.updateColumn(pk, attrs, cols);
        return count >= 0 ? count : (rs == null ? 0 : 1);
    }

    protected <T> int updateCache(final EntityInfo<T> info, int count, String column, final Serializable colval, FilterNode node) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return count;
        T[] rs = cache.update(info.getAttribute(column), colval, node);
        return count >= 0 ? count : (rs == null ? 0 : 1);
    }

    protected <T> int updateCache(final EntityInfo<T> info, int count, final Serializable pk, final String column, final Serializable colval) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return count;
        T rs = cache.update(pk, info.getAttribute(column), colval);
        return count >= 0 ? count : (rs == null ? 0 : 1);
    }

    protected <T> int updateCache(final EntityInfo<T> info, int count, T... entitys) {
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return -1;
        int c2 = 0;
        for (final T value : entitys) {
            c2 += cache.update(value);
        }
        return count >= 0 ? count : c2;
    }

    public <T> int reloadCache(Class<T> clazz, Serializable... pks) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return -1;
        String column = info.getPrimary().field();
        int c = 0;
        for (Serializable id : pks) {
            Sheet<T> sheet = querySheetCompose(false, true, false, clazz, null, FLIPPER_ONE, FilterNode.create(column, id)).join();
            T value = sheet.isEmpty() ? null : sheet.list().get(0);
            if (value != null) c += cache.update(value);
        }
        return c;
    }

    //------------------------- getNumberMapCompose -------------------------
    @Override
    public <N extends Number> Map<String, N> getNumberMap(final Class entityClass, final FilterFuncColumn... columns) {
        return getNumberMap(entityClass, (FilterNode) null, columns);
    }

    @Override
    public <N extends Number> CompletableFuture<Map<String, N>> getNumberMapAsync(final Class entityClass, final FilterFuncColumn... columns) {
        return getNumberMapAsync(entityClass, (FilterNode) null, columns);
    }

    @Override
    public <N extends Number> Map<String, N> getNumberMap(final Class entityClass, final FilterBean bean, final FilterFuncColumn... columns) {
        return getNumberMap(entityClass, FilterNodeBean.createFilterNode(bean), columns);
    }

    @Override
    public <N extends Number> CompletableFuture<Map<String, N>> getNumberMapAsync(final Class entityClass, final FilterBean bean, final FilterFuncColumn... columns) {
        return getNumberMapAsync(entityClass, FilterNodeBean.createFilterNode(bean), columns);
    }

    @Override
    public <N extends Number> Map<String, N> getNumberMap(final Class entityClass, final FilterNode node, final FilterFuncColumn... columns) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            final Map map = new HashMap<>();
            if (node == null || node.isCacheUseable(this)) {
                for (FilterFuncColumn ffc : columns) {
                    for (String col : ffc.cols()) {
                        map.put(ffc.col(col), cache.getNumberResult(ffc.func, ffc.defvalue, col, node));
                    }
                }
                return map;
            }
        }
        return (Map) getNumberMapCompose(info, node, columns).join();
    }

    @Override
    public <N extends Number> CompletableFuture<Map<String, N>> getNumberMapAsync(final Class entityClass, final FilterNode node, final FilterFuncColumn... columns) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            final Map map = new HashMap<>();
            if (node == null || node.isCacheUseable(this)) {
                for (FilterFuncColumn ffc : columns) {
                    for (String col : ffc.cols()) {
                        map.put(ffc.col(col), cache.getNumberResult(ffc.func, ffc.defvalue, col, node));
                    }
                }
                return CompletableFuture.completedFuture(map);
            }
        }
        if (isAsync()) return getNumberMapCompose(info, node, columns);
        return CompletableFuture.supplyAsync(() -> (Map) getNumberMapCompose(info, node, columns).join(), getExecutor());
    }

    protected <N extends Number> CompletableFuture<Map<String, N>> getNumberMapCompose(final EntityInfo info, final FilterNode node, final FilterFuncColumn... columns) {
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final Set<String> haset = new HashSet<>();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, haset, info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        StringBuilder sb = new StringBuilder();
        for (FilterFuncColumn ffc : columns) {
            for (String col : ffc.cols()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(ffc.func.getColumn((col == null || col.isEmpty() ? "*" : info.getSQLColumn("a", col))));
            }
        }
        final String sql = "SELECT " + sb + " FROM " + info.getTable(node) + " a"
            + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " getnumbermap sql=" + sql);
        return getNumberMapDB(info, sql, columns);
    }

    //------------------------ getNumberResultCompose -----------------------
    @Override
    public Number getNumberResult(final Class entityClass, final FilterFunc func, final String column) {
        return getNumberResult(entityClass, func, null, column, (FilterNode) null);
    }

    @Override
    public CompletableFuture<Number> getNumberResultAsync(final Class entityClass, final FilterFunc func, final String column) {
        return getNumberResultAsync(entityClass, func, null, column, (FilterNode) null);
    }

    @Override
    public Number getNumberResult(final Class entityClass, final FilterFunc func, final String column, FilterBean bean) {
        return getNumberResult(entityClass, func, null, column, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public CompletableFuture<Number> getNumberResultAsync(final Class entityClass, final FilterFunc func, final String column, final FilterBean bean) {
        return getNumberResultAsync(entityClass, func, null, column, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public Number getNumberResult(final Class entityClass, final FilterFunc func, final String column, final FilterNode node) {
        return getNumberResult(entityClass, func, null, column, node);
    }

    @Override
    public CompletableFuture<Number> getNumberResultAsync(final Class entityClass, final FilterFunc func, final String column, final FilterNode node) {
        return getNumberResultAsync(entityClass, func, null, column, node);
    }

    @Override
    public Number getNumberResult(final Class entityClass, final FilterFunc func, final Number defVal, final String column) {
        return getNumberResult(entityClass, func, defVal, column, (FilterNode) null);
    }

    @Override
    public CompletableFuture<Number> getNumberResultAsync(final Class entityClass, final FilterFunc func, final Number defVal, final String column) {
        return getNumberResultAsync(entityClass, func, defVal, column, (FilterNode) null);
    }

    @Override
    public Number getNumberResult(final Class entityClass, final FilterFunc func, final Number defVal, final String column, FilterBean bean) {
        return getNumberResult(entityClass, func, defVal, column, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public CompletableFuture<Number> getNumberResultAsync(final Class entityClass, final FilterFunc func, final Number defVal, final String column, FilterBean bean) {
        return getNumberResultAsync(entityClass, func, defVal, column, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public Number getNumberResult(final Class entityClass, final FilterFunc func, final Number defVal, final String column, final FilterNode node) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            if (node == null || node.isCacheUseable(this)) {
                return cache.getNumberResult(func, defVal, column, node);
            }
        }
        return getNumberResultCompose(info, entityClass, func, defVal, column, node).join();
    }

    @Override
    public CompletableFuture<Number> getNumberResultAsync(final Class entityClass, final FilterFunc func, final Number defVal, final String column, final FilterNode node) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            if (node == null || node.isCacheUseable(this)) {
                return CompletableFuture.completedFuture(cache.getNumberResult(func, defVal, column, node));
            }
        }
        if (isAsync()) return getNumberResultCompose(info, entityClass, func, defVal, column, node);
        return CompletableFuture.supplyAsync(() -> getNumberResultCompose(info, entityClass, func, defVal, column, node).join(), getExecutor());
    }

    protected CompletableFuture<Number> getNumberResultCompose(final EntityInfo info, final Class entityClass, final FilterFunc func, final Number defVal, final String column, final FilterNode node) {
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final Set<String> haset = new HashSet<>();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, haset, info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        final String sql = "SELECT " + func.getColumn((column == null || column.isEmpty() ? "*" : info.getSQLColumn("a", column))) + " FROM " + info.getTable(node) + " a"
            + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(entityClass.getSimpleName() + " getnumberresult sql=" + sql);
        return getNumberResultDB(info, sql, defVal, column);
    }

    //------------------------ queryColumnMapCompose ------------------------
    @Override
    public <T, K extends Serializable, N extends Number> Map<K, N> queryColumnMap(final Class<T> entityClass, final String keyColumn, final FilterFunc func, final String funcColumn) {
        return queryColumnMap(entityClass, keyColumn, func, funcColumn, (FilterNode) null);
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapAsync(final Class<T> entityClass, final String keyColumn, final FilterFunc func, final String funcColumn) {
        return queryColumnMapAsync(entityClass, keyColumn, func, funcColumn, (FilterNode) null);
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K, N> queryColumnMap(final Class<T> entityClass, final String keyColumn, final FilterFunc func, final String funcColumn, final FilterBean bean) {
        return queryColumnMap(entityClass, keyColumn, func, funcColumn, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapAsync(final Class<T> entityClass, final String keyColumn, final FilterFunc func, final String funcColumn, final FilterBean bean) {
        return queryColumnMapAsync(entityClass, keyColumn, func, funcColumn, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K, N> queryColumnMap(final Class<T> entityClass, final String keyColumn, final FilterFunc func, final String funcColumn, FilterNode node) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            if (node == null || node.isCacheUseable(this)) {
                return cache.queryColumnMap(keyColumn, func, funcColumn, node);
            }
        }
        return (Map) queryColumnMapCompose(info, keyColumn, func, funcColumn, node).join();
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapAsync(final Class<T> entityClass, final String keyColumn, final FilterFunc func, final String funcColumn, FilterNode node) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            if (node == null || node.isCacheUseable(this)) {
                return CompletableFuture.completedFuture(cache.queryColumnMap(keyColumn, func, funcColumn, node));
            }
        }
        if (isAsync()) return queryColumnMapCompose(info, keyColumn, func, funcColumn, node);
        return CompletableFuture.supplyAsync(() -> (Map) queryColumnMapCompose(info, keyColumn, func, funcColumn, node).join(), getExecutor());
    }

    protected <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapCompose(final EntityInfo<T> info, final String keyColumn, final FilterFunc func, final String funcColumn, FilterNode node) {
        final String keySqlColumn = info.getSQLColumn(null, keyColumn);
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final Set<String> haset = new HashSet<>();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, haset, info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        final String funcSqlColumn = func == null ? info.getSQLColumn("a", funcColumn) : func.getColumn((funcColumn == null || funcColumn.isEmpty() ? "*" : info.getSQLColumn("a", funcColumn)));
        final String sql = "SELECT a." + keySqlColumn + ", " + funcSqlColumn
            + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where)) + " GROUP BY a." + keySqlColumn;
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " querycolumnmap sql=" + sql);
        return queryColumnMapDB(info, sql, keyColumn);
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K, N[]> queryColumnMap(final Class<T> entityClass, final ColumnNode[] funcNodes, final String groupByColumn) {
        return queryColumnMap(entityClass, funcNodes, groupByColumn, (FilterNode) null);
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N[]>> queryColumnMapAsync(final Class<T> entityClass, final ColumnNode[] funcNodes, final String groupByColumn) {
        return queryColumnMapAsync(entityClass, funcNodes, groupByColumn, (FilterNode) null);
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K, N[]> queryColumnMap(final Class<T> entityClass, final ColumnNode[] funcNodes, final String groupByColumn, final FilterBean bean) {
        return queryColumnMap(entityClass, funcNodes, groupByColumn, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N[]>> queryColumnMapAsync(final Class<T> entityClass, final ColumnNode[] funcNodes, final String groupByColumn, final FilterBean bean) {
        return queryColumnMapAsync(entityClass, funcNodes, groupByColumn, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K, N[]> queryColumnMap(final Class<T> entityClass, final ColumnNode[] funcNodes, final String groupByColumn, final FilterNode node) {
        Map<K[], N[]> map = queryColumnMap(entityClass, funcNodes, Utility.ofArray(groupByColumn), node);
        final Map<K, N[]> rs = new LinkedHashMap<>();
        map.forEach((keys, values) -> rs.put(keys[0], values));
        return rs;
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N[]>> queryColumnMapAsync(final Class<T> entityClass, final ColumnNode[] funcNodes, final String groupByColumn, final FilterNode node) {
        CompletableFuture<Map<K[], N[]>> future = queryColumnMapAsync(entityClass, funcNodes, Utility.ofArray(groupByColumn), node);
        return future.thenApply(map -> {
            final Map<K, N[]> rs = new LinkedHashMap<>();
            map.forEach((keys, values) -> rs.put(keys[0], values));
            return rs;
        });
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K[], N[]> queryColumnMap(final Class<T> entityClass, final ColumnNode[] funcNodes, final String[] groupByColumns) {
        return queryColumnMap(entityClass, funcNodes, groupByColumns, (FilterNode) null);
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K[], N[]>> queryColumnMapAsync(final Class<T> entityClass, final ColumnNode[] funcNodes, final String[] groupByColumns) {
        return queryColumnMapAsync(entityClass, funcNodes, groupByColumns, (FilterNode) null);
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K[], N[]> queryColumnMap(final Class<T> entityClass, final ColumnNode[] funcNodes, final String[] groupByColumns, final FilterBean bean) {
        return queryColumnMap(entityClass, funcNodes, groupByColumns, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K[], N[]>> queryColumnMapAsync(final Class<T> entityClass, final ColumnNode[] funcNodes, final String[] groupByColumns, final FilterBean bean) {
        return queryColumnMapAsync(entityClass, funcNodes, groupByColumns, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, K extends Serializable, N extends Number> Map<K[], N[]> queryColumnMap(final Class<T> entityClass, final ColumnNode[] funcNodes, final String[] groupByColumns, final FilterNode node) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            if (node == null || node.isCacheUseable(this)) {
                return cache.queryColumnMap(funcNodes, groupByColumns, node);
            }
        }
        return (Map) queryColumnMapCompose(info, funcNodes, groupByColumns, node).join();
    }

    @Override
    public <T, K extends Serializable, N extends Number> CompletableFuture<Map<K[], N[]>> queryColumnMapAsync(final Class<T> entityClass, final ColumnNode[] funcNodes, final String[] groupByColumns, final FilterNode node) {
        final EntityInfo info = loadEntityInfo(entityClass);
        final EntityCache cache = info.getCache();
        if (cache != null && (isOnlyCache(info) || cache.isFullLoaded())) {
            if (node == null || node.isCacheUseable(this)) {
                return CompletableFuture.completedFuture(cache.queryColumnMap(funcNodes, groupByColumns, node));
            }
        }
        if (isAsync()) return queryColumnMapCompose(info, funcNodes, groupByColumns, node);
        return CompletableFuture.supplyAsync(() -> (Map) queryColumnMapCompose(info, funcNodes, groupByColumns, node).join(), getExecutor());
    }

    protected <T, K extends Serializable, N extends Number> CompletableFuture<Map<K[], N[]>> queryColumnMapCompose(final EntityInfo<T> info, final ColumnNode[] funcNodes, final String[] groupByColumns, final FilterNode node) {
        final StringBuilder groupBySqlColumns = new StringBuilder();
        if (groupByColumns != null && groupByColumns.length > 0) {
            for (int i = 0; i < groupByColumns.length; i++) {
                if (groupBySqlColumns.length() > 0) groupBySqlColumns.append(", ");
                groupBySqlColumns.append(info.getSQLColumn("a", groupByColumns[i]));
            }
        }
        final StringBuilder funcSqlColumns = new StringBuilder();
        for (int i = 0; i < funcNodes.length; i++) {
            if (funcSqlColumns.length() > 0) funcSqlColumns.append(", ");
            if (funcNodes[i] instanceof ColumnFuncNode) {
                funcSqlColumns.append(info.formatSQLValue((Attribute) null, "a", (ColumnFuncNode) funcNodes[i], sqlFormatter));
            } else {
                funcSqlColumns.append(info.formatSQLValue((Attribute) null, "a", (ColumnNodeValue) funcNodes[i], sqlFormatter));
            }
        }
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final Set<String> haset = new HashSet<>();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, haset, info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        String sql = "SELECT ";
        if (groupBySqlColumns.length() > 0) sql += groupBySqlColumns + ", ";
        sql += funcSqlColumns + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        if (groupBySqlColumns.length() > 0) sql += " GROUP BY " + groupBySqlColumns;
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " querycolumnmap sql=" + sql);
        return queryColumnMapDB(info, sql, funcNodes, groupByColumns);
    }

    //----------------------------- findCompose -----------------------------
    /**
     * 根据主键获取对象
     *
     * @param <T>   Entity类的泛型
     * @param clazz Entity类
     * @param pk    主键值
     *
     * @return Entity对象
     */
    @Override
    public <T> T find(Class<T> clazz, Serializable pk) {
        return find(clazz, (SelectColumn) null, pk);
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final Serializable pk) {
        return findAsync(clazz, (SelectColumn) null, pk);
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, ChannelContext context, final Serializable pk) {
        return findAsync(clazz, context, (SelectColumn) null, pk);
    }

    @Override
    public <T> T find(Class<T> clazz, final SelectColumn selects, Serializable pk) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            T rs = selects == null ? cache.find(pk) : cache.find(selects, pk);
            if (cache.isFullLoaded() || rs != null) return rs;
        }
        return findCompose(info, null, selects, pk).join();
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final SelectColumn selects, final Serializable pk) {
        return findAsync(clazz, null, selects, pk);
    }

    protected <T> CompletableFuture<T> findAsync(final Class<T> clazz, final ChannelContext context, final SelectColumn selects, final Serializable pk) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            T rs = selects == null ? cache.find(pk) : cache.find(selects, pk);
            if (cache.isFullLoaded() || rs != null) return CompletableFuture.completedFuture(rs);
        }
        if (isAsync()) return findCompose(info, context, selects, pk);
        return CompletableFuture.supplyAsync(() -> findCompose(info, context, selects, pk).join(), getExecutor());
    }

    protected <T> CompletableFuture<T> findCompose(final EntityInfo<T> info, final ChannelContext context, final SelectColumn selects, Serializable pk) {
        String column = info.getPrimarySQLColumn();
        final String sql = "SELECT " + info.getQueryColumns(null, selects) + " FROM " + info.getTable(pk) + " WHERE " + column + "=" + info.formatSQLValue(column, pk, sqlFormatter);
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " find sql=" + sql);
        return findDB(info, context, sql, true, selects);
    }

    @Override
    public <T> T find(final Class<T> clazz, final String column, final Serializable colval) {
        return find(clazz, null, FilterNode.create(column, colval));
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final String column, final Serializable colval) {
        return findAsync(clazz, null, FilterNode.create(column, colval));
    }

    @Override
    public <T> T find(final Class<T> clazz, final FilterBean bean) {
        return find(clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final FilterBean bean) {
        return findAsync(clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> T find(final Class<T> clazz, final FilterNode node) {
        return find(clazz, null, node);
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final FilterNode node) {
        return findAsync(clazz, null, node);
    }

    @Override
    public <T> T find(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return find(clazz, selects, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return findAsync(clazz, selects, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> T find(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null && cache.isFullLoaded() && (node == null || node.isCacheUseable(this))) return cache.find(selects, node);
        return this.findCompose(info, selects, node).join();
    }

    @Override
    public <T> CompletableFuture<T> findAsync(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null && cache.isFullLoaded() && (node == null || node.isCacheUseable(this))) {
            return CompletableFuture.completedFuture(cache.find(selects, node));
        }
        if (isAsync()) return this.findCompose(info, selects, node);
        return CompletableFuture.supplyAsync(() -> this.findCompose(info, selects, node).join(), getExecutor());
    }

    protected <T> CompletableFuture<T> findCompose(final EntityInfo<T> info, final SelectColumn selects, final FilterNode node) {
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, new HashSet<>(), info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        final String sql = "SELECT " + info.getQueryColumns("a", selects) + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " find sql=" + sql);
        return findDB(info, null, sql, false, selects);
    }

    @Override
    public <T> Serializable findColumn(final Class<T> clazz, final String column, final Serializable pk) {
        return findColumn(clazz, column, null, pk);
    }

    @Override
    public <T> CompletableFuture<Serializable> findColumnAsync(final Class<T> clazz, final String column, final Serializable pk) {
        return findColumnAsync(clazz, column, null, pk);
    }

    @Override
    public <T> Serializable findColumn(final Class<T> clazz, final String column, final FilterBean bean) {
        return findColumn(clazz, column, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Serializable> findColumnAsync(final Class<T> clazz, final String column, final FilterBean bean) {
        return findColumnAsync(clazz, column, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Serializable findColumn(final Class<T> clazz, final String column, final FilterNode node) {
        return findColumn(clazz, column, null, node);
    }

    @Override
    public <T> CompletableFuture<Serializable> findColumnAsync(final Class<T> clazz, final String column, final FilterNode node) {
        return findColumnAsync(clazz, column, null, node);
    }

    @Override
    public <T> Serializable findColumn(final Class<T> clazz, final String column, final Serializable defValue, final FilterBean bean) {
        return findColumn(clazz, column, defValue, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Serializable> findColumnAsync(final Class<T> clazz, final String column, final Serializable defValue, final FilterBean bean) {
        return findColumnAsync(clazz, column, defValue, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Serializable findColumn(final Class<T> clazz, final String column, final Serializable defValue, final Serializable pk) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            Serializable val = cache.findColumn(column, defValue, pk);
            if (cache.isFullLoaded() || val != null) return val;
        }
        return findColumnCompose(info, column, defValue, pk).join();
    }

    @Override
    public <T> CompletableFuture<Serializable> findColumnAsync(final Class<T> clazz, final String column, final Serializable defValue, final Serializable pk) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            Serializable val = cache.findColumn(column, defValue, pk);
            if (cache.isFullLoaded() || val != null) return CompletableFuture.completedFuture(val);
        }
        if (isAsync()) return findColumnCompose(info, column, defValue, pk);
        return CompletableFuture.supplyAsync(() -> findColumnCompose(info, column, defValue, pk).join(), getExecutor());
    }

    protected <T> CompletableFuture<Serializable> findColumnCompose(final EntityInfo<T> info, String column, final Serializable defValue, final Serializable pk) {
        final String sql = "SELECT " + info.getSQLColumn(null, column) + " FROM " + info.getTable(pk) + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(info.getPrimarySQLColumn(), pk, sqlFormatter);
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " find sql=" + sql);
        return findColumnDB(info, sql, true, column, defValue);
    }

    @Override
    public <T> Serializable findColumn(final Class<T> clazz, final String column, final Serializable defValue, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            Serializable val = cache.findColumn(column, defValue, node);
            if (cache.isFullLoaded() || val != null) return val;
        }
        return this.findColumnCompose(info, column, defValue, node).join();
    }

    @Override
    public <T> CompletableFuture<Serializable> findColumnAsync(final Class<T> clazz, final String column, final Serializable defValue, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            Serializable val = cache.findColumn(column, defValue, node);
            if (cache.isFullLoaded() || val != null) return CompletableFuture.completedFuture(val);
        }
        if (isAsync()) return this.findColumnCompose(info, column, defValue, node);
        return CompletableFuture.supplyAsync(() -> this.findColumnCompose(info, column, defValue, node).join(), getExecutor());
    }

    protected <T> CompletableFuture<Serializable> findColumnCompose(final EntityInfo<T> info, String column, final Serializable defValue, final FilterNode node) {
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, new HashSet<>(), info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        final String sql = "SELECT " + info.getSQLColumn("a", column) + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " find sql=" + sql);
        return findColumnDB(info, sql, false, column, defValue);
    }

    //---------------------------- existsCompose ----------------------------
    @Override
    public <T> boolean exists(Class<T> clazz, Serializable pk) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            boolean rs = cache.exists(pk);
            if (rs || cache.isFullLoaded()) return rs;
        }
        return existsCompose(info, pk).join();
    }

    @Override
    public <T> CompletableFuture<Boolean> existsAsync(final Class<T> clazz, final Serializable pk) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            boolean rs = cache.exists(pk);
            if (rs || cache.isFullLoaded()) return CompletableFuture.completedFuture(rs);
        }
        if (isAsync()) return existsCompose(info, pk);
        return CompletableFuture.supplyAsync(() -> existsCompose(info, pk).join(), getExecutor());
    }

    protected <T> CompletableFuture<Boolean> existsCompose(final EntityInfo<T> info, Serializable pk) {
        final String sql = "SELECT COUNT(*) FROM " + info.getTable(pk) + " WHERE " + info.getPrimarySQLColumn() + "=" + info.formatSQLValue(info.getPrimarySQLColumn(), pk, sqlFormatter);
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " exists sql=" + sql);
        return existsDB(info, sql, true);
    }

    @Override
    public <T> boolean exists(final Class<T> clazz, final FilterBean bean) {
        return exists(clazz, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Boolean> existsAsync(final Class<T> clazz, final FilterBean bean) {
        return existsAsync(clazz, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> boolean exists(final Class<T> clazz, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            boolean rs = cache.exists(node);
            if (rs || cache.isFullLoaded()) return rs;
        }
        return this.existsCompose(info, node).join();
    }

    @Override
    public <T> CompletableFuture<Boolean> existsAsync(final Class<T> clazz, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache != null) {
            boolean rs = cache.exists(node);
            if (rs || cache.isFullLoaded()) return CompletableFuture.completedFuture(rs);
        }
        if (isAsync()) return this.existsCompose(info, node);
        return CompletableFuture.supplyAsync(() -> this.existsCompose(info, node).join(), getExecutor());
    }

    protected <T> CompletableFuture<Boolean> existsCompose(final EntityInfo<T> info, FilterNode node) {
        final Map<Class, String> joinTabalis = node == null ? null : node.getJoinTabalis();
        final CharSequence join = node == null ? null : node.createSQLJoin(this, false, joinTabalis, new HashSet<>(), info);
        final CharSequence where = node == null ? null : node.createSQLExpress(info, joinTabalis);
        final String sql = "SELECT COUNT(" + info.getPrimarySQLColumn("a") + ") FROM " + info.getTable(node) + " a" + (join == null ? "" : join) + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " exists sql=" + sql);
        return existsDB(info, sql, false);
    }

    //-----------------------list set----------------------------
    @Override
    public <T, V extends Serializable> Set<V> queryColumnSet(final String selectedColumn, final Class<T> clazz, final String column, final Serializable colval) {
        return queryColumnSet(selectedColumn, clazz, null, FilterNode.create(column, colval));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Set<V>> queryColumnSetAsync(final String selectedColumn, final Class<T> clazz, final String column, final Serializable colval) {
        return queryColumnSetAsync(selectedColumn, clazz, null, FilterNode.create(column, colval));
    }

    @Override
    public <T, V extends Serializable> Set<V> queryColumnSet(final String selectedColumn, final Class<T> clazz, final FilterBean bean) {
        return queryColumnSet(selectedColumn, clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Set<V>> queryColumnSetAsync(final String selectedColumn, final Class<T> clazz, final FilterBean bean) {
        return queryColumnSetAsync(selectedColumn, clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> Set<V> queryColumnSet(final String selectedColumn, final Class<T> clazz, final FilterNode node) {
        return queryColumnSet(selectedColumn, clazz, null, node);
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Set<V>> queryColumnSetAsync(final String selectedColumn, final Class<T> clazz, final FilterNode node) {
        return queryColumnSetAsync(selectedColumn, clazz, null, node);
    }

    @Override
    public <T, V extends Serializable> Set<V> queryColumnSet(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnSet(selectedColumn, clazz, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Set<V>> queryColumnSetAsync(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnSetAsync(selectedColumn, clazz, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> Set<V> queryColumnSet(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        final Set<T> list = querySet(clazz, SelectColumn.includes(selectedColumn), flipper, node);
        final Set<V> rs = new LinkedHashSet<>();
        if (list.isEmpty()) return rs;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
        for (T t : list) {
            rs.add(selected.get(t));
        }
        return rs;
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Set<V>> queryColumnSetAsync(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySetAsync(clazz, SelectColumn.includes(selectedColumn), flipper, node).thenApply((Set<T> list) -> {
            final Set<V> rs = new LinkedHashSet<>();
            if (list.isEmpty()) return rs;
            final EntityInfo<T> info = loadEntityInfo(clazz);
            final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
            for (T t : list) {
                rs.add(selected.get(t));
            }
            return rs;
        });
    }

    @Override
    public <T, V extends Serializable> List<V> queryColumnList(final String selectedColumn, final Class<T> clazz, final String column, final Serializable colval) {
        return queryColumnList(selectedColumn, clazz, null, FilterNode.create(column, colval));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<List<V>> queryColumnListAsync(final String selectedColumn, final Class<T> clazz, final String column, final Serializable colval) {
        return queryColumnListAsync(selectedColumn, clazz, null, FilterNode.create(column, colval));
    }

    @Override
    public <T, V extends Serializable> List<V> queryColumnList(final String selectedColumn, final Class<T> clazz, final FilterBean bean) {
        return queryColumnList(selectedColumn, clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<List<V>> queryColumnListAsync(final String selectedColumn, final Class<T> clazz, final FilterBean bean) {
        return queryColumnListAsync(selectedColumn, clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> List<V> queryColumnList(final String selectedColumn, final Class<T> clazz, final FilterNode node) {
        return queryColumnList(selectedColumn, clazz, null, node);
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<List<V>> queryColumnListAsync(final String selectedColumn, final Class<T> clazz, final FilterNode node) {
        return queryColumnListAsync(selectedColumn, clazz, null, node);
    }

    @Override
    public <T, V extends Serializable> List<V> queryColumnList(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnList(selectedColumn, clazz, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<List<V>> queryColumnListAsync(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnListAsync(selectedColumn, clazz, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> List<V> queryColumnList(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        final List<T> list = queryList(clazz, SelectColumn.includes(selectedColumn), flipper, node);
        final List<V> rs = new ArrayList<>();
        if (list.isEmpty()) return rs;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
        for (T t : list) {
            rs.add(selected.get(t));
        }
        return rs;
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<List<V>> queryColumnListAsync(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return queryListAsync(clazz, SelectColumn.includes(selectedColumn), flipper, node).thenApply((List<T> list) -> {
            final List<V> rs = new ArrayList<>();
            if (list.isEmpty()) return rs;
            final EntityInfo<T> info = loadEntityInfo(clazz);
            final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
            for (T t : list) {
                rs.add(selected.get(t));
            }
            return rs;
        });
    }

    /**
     * 根据指定参数查询对象某个字段的集合
     * <p>
     * @param <T>            Entity类的泛型
     * @param <V>            字段值的类型
     * @param selectedColumn 字段名
     * @param clazz          Entity类
     * @param flipper        翻页对象
     * @param bean           过滤Bean
     *
     * @return 字段集合
     */
    @Override
    public <T, V extends Serializable> Sheet<V> queryColumnSheet(final String selectedColumn, Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnSheet(selectedColumn, clazz, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Sheet<V>> queryColumnSheetAsync(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnSheetAsync(selectedColumn, clazz, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T, V extends Serializable> Sheet<V> queryColumnSheet(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        Sheet<T> sheet = querySheet(clazz, SelectColumn.includes(selectedColumn), flipper, node);
        final Sheet<V> rs = new Sheet<>();
        if (sheet.isEmpty()) return rs;
        rs.setTotal(sheet.getTotal());
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
        final List<V> list = new ArrayList<>();
        for (T t : sheet.getRows()) {
            list.add(selected.get(t));
        }
        rs.setRows(list);
        return rs;
    }

    @Override
    public <T, V extends Serializable> CompletableFuture<Sheet<V>> queryColumnSheetAsync(final String selectedColumn, final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySheetAsync(clazz, SelectColumn.includes(selectedColumn), flipper, node).thenApply((Sheet<T> sheet) -> {
            final Sheet<V> rs = new Sheet<>();
            if (sheet.isEmpty()) return rs;
            rs.setTotal(sheet.getTotal());
            final EntityInfo<T> info = loadEntityInfo(clazz);
            final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
            final List<V> list = new ArrayList<>();
            for (T t : sheet.getRows()) {
                list.add(selected.get(t));
            }
            rs.setRows(list);
            return rs;
        });
    }

    /**
     * 查询符合过滤条件记录的Map集合, 主键值为key   <br>
     * 等价SQL: SELECT * FROM {table} WHERE {column} = {key} ORDER BY {flipper.sort} LIMIT {flipper.limit}  <br>
     *
     * @param <K>       主键泛型
     * @param <T>       Entity泛型
     * @param clazz     Entity类
     * @param keyStream 主键Stream
     *
     * @return Entity的集合
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final Stream<K> keyStream) {
        return queryMap(clazz, null, keyStream);
    }

    @Override
    public <K extends Serializable, T> CompletableFuture<Map<K, T>> queryMapAsync(final Class<T> clazz, final Stream<K> keyStream) {
        return queryMapAsync(clazz, null, keyStream);
    }

    /**
     * 查询符合过滤条件记录的Map集合, 主键值为key   <br>
     * 等价SQL: SELECT * FROM {table} WHERE {column} = {key} ORDER BY {flipper.sort} LIMIT {flipper.limit}  <br>
     *
     * @param <K>   主键泛型
     * @param <T>   Entity泛型
     * @param clazz Entity类
     * @param bean  FilterBean
     *
     * @return Entity的集合
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final FilterBean bean) {
        return queryMap(clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <K extends Serializable, T> CompletableFuture<Map<K, T>> queryMapAsync(final Class<T> clazz, final FilterBean bean) {
        return queryMapAsync(clazz, null, FilterNodeBean.createFilterNode(bean));
    }

    /**
     * 查询符合过滤条件记录的Map集合, 主键值为key   <br>
     * 等价SQL: SELECT * FROM {table} WHERE {column} = {key} ORDER BY {flipper.sort} LIMIT {flipper.limit}  <br>
     *
     * @param <K>   主键泛型
     * @param <T>   Entity泛型
     * @param clazz Entity类
     * @param node  FilterNode
     *
     * @return Entity的集合
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final FilterNode node) {
        return queryMap(clazz, null, node);
    }

    @Override
    public <K extends Serializable, T> CompletableFuture<Map<K, T>> queryMapAsync(final Class<T> clazz, final FilterNode node) {
        return queryMapAsync(clazz, null, node);
    }

    /**
     * 查询符合过滤条件记录的Map集合, 主键值为key   <br>
     * 等价SQL: SELECT * FROM {table} WHERE {column} = {key} ORDER BY {flipper.sort} LIMIT {flipper.limit}  <br>
     *
     * @param <K>       主键泛型
     * @param <T>       Entity泛型
     * @param clazz     Entity类
     * @param selects   指定字段
     * @param keyStream 主键Stream
     *
     * @return Entity的集合
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final SelectColumn selects, final Stream<K> keyStream) {
        if (keyStream == null) return new LinkedHashMap<>();
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final ArrayList<K> ids = new ArrayList<>();
        keyStream.forEach(k -> ids.add(k));
        final Attribute<T, Serializable> primary = info.primary;
        List<T> rs = queryList(clazz, FilterNode.create(primary.field(), ids));
        Map<K, T> map = new LinkedHashMap<>();
        if (rs.isEmpty()) return new LinkedHashMap<>();
        for (T item : rs) {
            map.put((K) primary.get(item), item);
        }
        return map;
    }

    @Override
    public <K extends Serializable, T> CompletableFuture<Map<K, T>> queryMapAsync(final Class<T> clazz, final SelectColumn selects, final Stream<K> keyStream) {
        if (keyStream == null) return CompletableFuture.completedFuture(new LinkedHashMap<>());
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final ArrayList<K> pks = new ArrayList<>();
        keyStream.forEach(k -> pks.add(k));
        final Attribute<T, Serializable> primary = info.primary;
        return queryListAsync(clazz, FilterNode.create(primary.field(), pks)).thenApply((List<T> rs) -> {
            Map<K, T> map = new LinkedHashMap<>();
            if (rs.isEmpty()) return new LinkedHashMap<>();
            for (T item : rs) {
                map.put((K) primary.get(item), item);
            }
            return map;
        });
    }

    /**
     * 查询符合过滤条件记录的Map集合, 主键值为key   <br>
     * 等价SQL: SELECT * FROM {table} WHERE {column} = {key} ORDER BY {flipper.sort} LIMIT {flipper.limit}  <br>
     *
     * @param <K>     主键泛型
     * @param <T>     Entity泛型
     * @param clazz   Entity类
     * @param selects 指定字段
     * @param bean    FilterBean
     *
     * @return Entity的集合
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return queryMap(clazz, selects, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <K extends Serializable, T> CompletableFuture<Map<K, T>> queryMapAsync(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return queryMapAsync(clazz, selects, FilterNodeBean.createFilterNode(bean));
    }

    /**
     * 查询符合过滤条件记录的Map集合, 主键值为key   <br>
     * 等价SQL: SELECT * FROM {table} WHERE {column} = {key} ORDER BY {flipper.sort} LIMIT {flipper.limit}  <br>
     *
     * @param <K>     主键泛型
     * @param <T>     Entity泛型
     * @param clazz   Entity类
     * @param selects 指定字段
     * @param node    FilterNode
     *
     * @return Entity的集合
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        List<T> rs = queryList(clazz, selects, node);
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute<T, Serializable> primary = info.primary;
        Map<K, T> map = new LinkedHashMap<>();
        if (rs.isEmpty()) return new LinkedHashMap<>();
        for (T item : rs) {
            map.put((K) primary.get(item), item);
        }
        return map;
    }

    @Override
    public <K extends Serializable, T> CompletableFuture<Map<K, T>> queryMapAsync(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        return queryListAsync(clazz, selects, node).thenApply((List<T> rs) -> {
            final EntityInfo<T> info = loadEntityInfo(clazz);
            final Attribute<T, Serializable> primary = info.primary;
            Map<K, T> map = new LinkedHashMap<>();
            if (rs.isEmpty()) return new LinkedHashMap<>();
            for (T item : rs) {
                map.put((K) primary.get(item), item);
            }
            return map;
        });
    }

    /**
     * 根据指定字段值查询对象集合
     *
     * @param <T>    Entity类的泛型
     * @param clazz  Entity类
     * @param column 过滤字段名
     * @param colval 过滤字段值
     *
     * @return Entity对象的集合
     */
    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final String column, final Serializable colval) {
        return querySet(clazz, (SelectColumn) null, null, FilterNode.create(column, colval));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final String column, final Serializable colval) {
        return querySetAsync(clazz, (SelectColumn) null, null, FilterNode.create(column, colval));
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz) {
        return querySet(clazz, (SelectColumn) null, null, (FilterNode) null);
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz) {
        return querySetAsync(clazz, (SelectColumn) null, null, (FilterNode) null);
    }

    /**
     * 根据过滤对象FilterBean查询对象集合
     *
     * @param <T>   Entity类的泛型
     * @param clazz Entity类
     * @param bean  过滤Bean
     *
     * @return Entity对象集合
     */
    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final FilterBean bean) {
        return querySet(clazz, (SelectColumn) null, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final FilterBean bean) {
        return querySetAsync(clazz, (SelectColumn) null, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final FilterNode node) {
        return querySet(clazz, (SelectColumn) null, null, node);
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final FilterNode node) {
        return querySetAsync(clazz, (SelectColumn) null, null, node);
    }

    /**
     * 根据过滤对象FilterBean查询对象集合， 对象只填充或排除SelectField指定的字段
     *
     * @param <T>     Entity类的泛型
     * @param clazz   Entity类
     * @param selects 收集的字段
     * @param bean    过滤Bean
     *
     * @return Entity对象的集合
     */
    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return querySet(clazz, selects, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, SelectColumn selects, final FilterBean bean) {
        return querySetAsync(clazz, selects, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        return querySet(clazz, selects, null, node);
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, SelectColumn selects, final FilterNode node) {
        return querySetAsync(clazz, selects, null, node);
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final Flipper flipper, final String column, final Serializable colval) {
        return querySet(clazz, null, flipper, FilterNode.create(column, colval));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final Flipper flipper, final String column, final Serializable colval) {
        return querySetAsync(clazz, null, flipper, FilterNode.create(column, colval));
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return querySet(clazz, null, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return querySetAsync(clazz, null, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySet(clazz, null, flipper, node);
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySetAsync(clazz, null, flipper, node);
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return querySet(clazz, selects, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return querySetAsync(clazz, selects, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Set<T> querySet(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        return new LinkedHashSet<>(querySheetCompose(true, false, true, clazz, selects, flipper, node).join().list(true));
    }

    @Override
    public <T> CompletableFuture<Set<T>> querySetAsync(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        return querySheetCompose(true, false, true, clazz, selects, flipper, node).thenApply((rs) -> new LinkedHashSet<>(rs.list(true)));
    }

    /**
     * 根据指定字段值查询对象集合
     *
     * @param <T>    Entity类的泛型
     * @param clazz  Entity类
     * @param column 过滤字段名
     * @param colval 过滤字段值
     *
     * @return Entity对象的集合
     */
    @Override
    public <T> List<T> queryList(final Class<T> clazz, final String column, final Serializable colval) {
        return queryList(clazz, (SelectColumn) null, null, FilterNode.create(column, colval));
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final String column, final Serializable colval) {
        return queryListAsync(clazz, (SelectColumn) null, null, FilterNode.create(column, colval));
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz) {
        return queryList(clazz, (SelectColumn) null, null, (FilterNode) null);
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz) {
        return queryListAsync(clazz, (SelectColumn) null, null, (FilterNode) null);
    }

    /**
     * 根据过滤对象FilterBean查询对象集合
     *
     * @param <T>   Entity类的泛型
     * @param clazz Entity类
     * @param bean  过滤Bean
     *
     * @return Entity对象集合
     */
    @Override
    public <T> List<T> queryList(final Class<T> clazz, final FilterBean bean) {
        return queryList(clazz, (SelectColumn) null, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final FilterBean bean) {
        return queryListAsync(clazz, (SelectColumn) null, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final FilterNode node) {
        return queryList(clazz, (SelectColumn) null, null, node);
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final FilterNode node) {
        return queryListAsync(clazz, (SelectColumn) null, null, node);
    }

    /**
     * 根据过滤对象FilterBean查询对象集合， 对象只填充或排除SelectField指定的字段
     *
     * @param <T>     Entity类的泛型
     * @param clazz   Entity类
     * @param selects 收集的字段
     * @param bean    过滤Bean
     *
     * @return Entity对象的集合
     */
    @Override
    public <T> List<T> queryList(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return queryList(clazz, selects, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, SelectColumn selects, final FilterBean bean) {
        return queryListAsync(clazz, selects, null, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        return queryList(clazz, selects, null, node);
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, SelectColumn selects, final FilterNode node) {
        return queryListAsync(clazz, selects, null, node);
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final Flipper flipper, final String column, final Serializable colval) {
        return queryList(clazz, null, flipper, FilterNode.create(column, colval));
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final Flipper flipper, final String column, final Serializable colval) {
        return queryListAsync(clazz, null, flipper, FilterNode.create(column, colval));
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryList(clazz, null, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryListAsync(clazz, null, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return queryList(clazz, null, flipper, node);
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return queryListAsync(clazz, null, flipper, node);
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return queryList(clazz, selects, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return queryListAsync(clazz, selects, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        return querySheetCompose(true, false, false, clazz, selects, flipper, node).join().list(true);
    }

    @Override
    public <T> CompletableFuture<List<T>> queryListAsync(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        return querySheetCompose(true, false, false, clazz, selects, flipper, node).thenApply((rs) -> rs.list(true));
    }

    //-----------------------sheet----------------------------
    /**
     * 根据过滤对象FilterBean和翻页对象Flipper查询一页的数据
     *
     * @param <T>     Entity类的泛型
     * @param clazz   Entity类
     * @param flipper 翻页对象
     * @param bean    过滤Bean
     *
     * @return Entity对象的集合
     */
    @Override
    public <T> Sheet<T> querySheet(final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return querySheet(clazz, null, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Sheet<T>> querySheetAsync(final Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return querySheetAsync(clazz, null, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Sheet<T> querySheet(final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySheet(clazz, null, flipper, node);
    }

    @Override
    public <T> CompletableFuture<Sheet<T>> querySheetAsync(final Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySheetAsync(clazz, null, flipper, node);
    }

    /**
     * 根据过滤对象FilterBean和翻页对象Flipper查询一页的数据， 对象只填充或排除SelectField指定的字段
     *
     * @param <T>     Entity类的泛型
     * @param clazz   Entity类
     * @param selects 收集的字段集合
     * @param flipper 翻页对象
     * @param bean    过滤Bean
     *
     * @return Entity对象的集合
     */
    @Override
    public <T> Sheet<T> querySheet(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return querySheet(clazz, selects, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> CompletableFuture<Sheet<T>> querySheetAsync(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return querySheetAsync(clazz, selects, flipper, FilterNodeBean.createFilterNode(bean));
    }

    @Override
    public <T> Sheet<T> querySheet(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        return querySheetCompose(true, true, false, clazz, selects, flipper, node).join();
    }

    @Override
    public <T> CompletableFuture<Sheet<T>> querySheetAsync(final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        if (isAsync()) return querySheetCompose(true, true, false, clazz, selects, flipper, node);
        return CompletableFuture.supplyAsync(() -> querySheetCompose(true, true, false, clazz, selects, flipper, node).join(), getExecutor());
    }

    protected <T> CompletableFuture<Sheet<T>> querySheetCompose(final boolean readcache, final boolean needtotal, final boolean distinct, final Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (readcache && cache != null && cache.isFullLoaded()) {
            if (node == null || node.isCacheUseable(this)) {
                if (info.isLoggable(logger, Level.FINEST, " cache query predicate = ")) logger.finest(clazz.getSimpleName() + " cache query predicate = " + (node == null ? null : node.createPredicate(cache)));
                return CompletableFuture.completedFuture(cache.querySheet(needtotal, distinct, selects, flipper, node));
            }
        }
        return querySheetDB(info, readcache, needtotal, distinct, selects, flipper, node);
    }

    protected static enum UpdateMode {
        INSERT, DELETE, UPDATE, CLEAR, DROP, ALTER, OTHER;
    }
}
