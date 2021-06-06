/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import org.redkale.convert.*;
import org.redkale.convert.json.JsonFactory;
import org.redkale.util.*;

/**
 * Redkale中缓存数据源的核心类。 主要供业务开发者使用， 技术开发者提供CacheSource的实现。<br>
 * CacheSource提供三种数据类型操作: String、Long和泛型指定的数据类型。<br>
 * String统一用setString、getString等系列方法。<br>
 * Long统一用setLong、getLong、incr等系列方法。<br>
 * 其他则供自定义数据类型使用。
 *
 * param V value的类型 移除 @2.4.0
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public interface CacheSource {

    public String getType();

    //ServiceLoader时判断配置是否符合当前实现类
    public boolean match(AnyValue config);

    default boolean isOpen() {
        return true;
    }

    public boolean exists(final String key);

    public <T> T get(final String key, final Type type);

    public <T> T getAndRefresh(final String key, final int expireSeconds, final Type type);

    //----------- hxxx --------------
    public int hremove(final String key, String... fields);

    public List<String> hkeys(final String key);

    public int hsize(final String key);

    public long hincr(final String key, String field);

    public long hincr(final String key, String field, long num);

    public long hdecr(final String key, String field);

    public long hdecr(final String key, String field, long num);

    public boolean hexists(final String key, String field);

    public <T> void hset(final String key, final String field, final Convert convert, final T value);

    public <T> void hset(final String key, final String field, final Type type, final T value);

    public <T> void hset(final String key, final String field, final Convert convert, final Type type, final T value);

    public void hsetString(final String key, final String field, final String value);

    public void hsetLong(final String key, final String field, final long value);

    public void hmset(final String key, final Serializable... values);

    public <T> List<T> hmget(final String key, final Type type, final String... fields);

    public <T> Map<String, T> hmap(final String key, final Type type, int offset, int limit);

    public <T> Map<String, T> hmap(final String key, final Type type, int offset, int limit, String pattern);

    public <T> T hget(final String key, final String field, final Type type);

    public String hgetString(final String key, final String field);

    public long hgetLong(final String key, final String field, long defValue);
    //----------- hxxx --------------

    public void refresh(final String key, final int expireSeconds);

    public <T> void set(final String key, final Convert convert, final T value);

    public <T> void set(final String key, final Type type, final T value);

    public <T> void set(final String key, final Convert convert, final Type type, final T value);

    public <T> void set(final int expireSeconds, final String key, final Convert convert, final T value);

    public <T> void set(final int expireSeconds, final String key, final Type type, final T value);

    public <T> void set(final int expireSeconds, final String key, final Convert convert, final Type type, final T value);

    public void setExpireSeconds(final String key, final int expireSeconds);

    public int remove(final String key);

    public long incr(final String key);

    public long incr(final String key, long num);

    public long decr(final String key);

    public long decr(final String key, long num);

    public <T> Map<String, T> getMap(final Type componentType, final String... keys);

    public <T> Collection<T> getCollection(final String key, final Type componentType);

    public <T> Map<String, Collection<T>> getCollectionMap(final boolean set, final Type componentType, final String... keys);

    public int getCollectionSize(final String key);

    public <T> Collection<T> getCollectionAndRefresh(final String key, final int expireSeconds, final Type componentType);

    public <T> void appendListItem(final String key, final Type componentType, final T value);

    public <T> int removeListItem(final String key, final Type componentType, final T value);

    public <T> boolean existsSetItem(final String key, final Type componentType, final T value);

    public <T> void appendSetItem(final String key, final Type componentType, final T value);

    public <T> int removeSetItem(final String key, final Type componentType, final T value);

    public <T> T spopSetItem(final String key, final Type componentType);

    public <T> List<T> spopSetItem(final String key, final int count, final Type componentType);

    public byte[] getBytes(final String key);

    public byte[] getBytesAndRefresh(final String key, final int expireSeconds);

    public void setBytes(final String key, final byte[] value);

    public void setBytes(final int expireSeconds, final String key, final byte[] value);

    public <T> void setBytes(final String key, final Convert convert, final Type type, final T value);

    public <T> void setBytes(final int expireSeconds, final String key, final Convert convert, final Type type, final T value);

    public List<String> queryKeys();

    public List<String> queryKeysStartsWith(String startsWith);

    public List<String> queryKeysEndsWith(String endsWith);

    public int getKeySize();

    public List<CacheEntry<Object>> queryList();

    public String getString(final String key);

    public String getStringAndRefresh(final String key, final int expireSeconds);

    public void setString(final String key, final String value);

    public void setString(final int expireSeconds, final String key, final String value);

    public Map<String, String> getStringMap(final String... keys);

    public String[] getStringArray(final String... keys);

    public Collection<String> getStringCollection(final String key);

    public Map<String, Collection<String>> getStringCollectionMap(final boolean set, final String... keys);

    public Collection<String> getStringCollectionAndRefresh(final String key, final int expireSeconds);

    public void appendStringListItem(final String key, final String value);

    public String spopStringSetItem(final String key);

    public List<String> spopStringSetItem(final String key, final int count);

    public int removeStringListItem(final String key, final String value);

    public boolean existsStringSetItem(final String key, final String value);

    public void appendStringSetItem(final String key, final String value);

    public int removeStringSetItem(final String key, final String value);

    public long getLong(final String key, long defValue);

    public long getLongAndRefresh(final String key, final int expireSeconds, long defValue);

    public void setLong(final String key, final long value);

    public void setLong(final int expireSeconds, final String key, final long value);

    public Map<String, Long> getLongMap(final String... keys);

    public Long[] getLongArray(final String... keys);

    public Collection<Long> getLongCollection(final String key);

    public Map<String, Collection<Long>> getLongCollectionMap(final boolean set, final String... keys);

    public Collection<Long> getLongCollectionAndRefresh(final String key, final int expireSeconds);

    public void appendLongListItem(final String key, final long value);

    public Long spopLongSetItem(final String key);

    public List<Long> spopLongSetItem(final String key, final int count);

    public int removeLongListItem(final String key, final long value);

    public boolean existsLongSetItem(final String key, final long value);

    public void appendLongSetItem(final String key, final long value);

    public int removeLongSetItem(final String key, final long value);

    //---------------------- CompletableFuture 异步版 ---------------------------------
    public CompletableFuture<Boolean> existsAsync(final String key);

    public <T> CompletableFuture<T> getAsync(final String key, final Type type);

    public <T> CompletableFuture<T> getAndRefreshAsync(final String key, final int expireSeconds, final Type type);

    public CompletableFuture<Void> refreshAsync(final String key, final int expireSeconds);

    public <T> CompletableFuture<Void> setAsync(final String key, final Convert convert, final T value);

    public <T> CompletableFuture<Void> setAsync(final String key, final Type type, final T value);

    public <T> CompletableFuture<Void> setAsync(final String key, final Convert convert, final Type type, final T value);

    public <T> CompletableFuture<Void> setAsync(final int expireSeconds, final String key, final Convert convert, final T value);

    public <T> CompletableFuture<Void> setAsync(final int expireSeconds, final String key, final Type type, final T value);

    public <T> CompletableFuture<Void> setAsync(final int expireSeconds, final String key, final Convert convert, final Type type, final T value);

    public CompletableFuture<Void> setExpireSecondsAsync(final String key, final int expireSeconds);

    public CompletableFuture<Integer> removeAsync(final String key);

    public CompletableFuture<Long> incrAsync(final String key);

    public CompletableFuture<Long> incrAsync(final String key, long num);

    public CompletableFuture<Long> decrAsync(final String key);

    public CompletableFuture<Long> decrAsync(final String key, long num);

    //----------- hxxx --------------
    public CompletableFuture<Integer> hremoveAsync(final String key, String... fields);

    public CompletableFuture<List<String>> hkeysAsync(final String key);

    public CompletableFuture<Integer> hsizeAsync(final String key);

    public CompletableFuture<Long> hincrAsync(final String key, String field);

    public CompletableFuture<Long> hincrAsync(final String key, String field, long num);

    public CompletableFuture<Long> hdecrAsync(final String key, String field);

    public CompletableFuture<Long> hdecrAsync(final String key, String field, long num);

    public CompletableFuture<Boolean> hexistsAsync(final String key, String field);

    public <T> CompletableFuture<Void> hsetAsync(final String key, final String field, final Convert convert, final T value);

    public <T> CompletableFuture<Void> hsetAsync(final String key, final String field, final Type type, final T value);

    public <T> CompletableFuture<Void> hsetAsync(final String key, final String field, final Convert convert, final Type type, final T value);

    public CompletableFuture<Void> hsetStringAsync(final String key, final String field, final String value);

    public CompletableFuture<Void> hsetLongAsync(final String key, final String field, final long value);

    public CompletableFuture<Void> hmsetAsync(final String key, final Serializable... values);

    public <T> CompletableFuture<List<T>> hmgetAsync(final String key, final Type type, final String... fields);

    public <T> CompletableFuture<Map<String, T>> hmapAsync(final String key, final Type type, int offset, int limit);

    public <T> CompletableFuture<Map<String, T>> hmapAsync(final String key, final Type type, int offset, int limit, String pattern);

    public <T> CompletableFuture<T> hgetAsync(final String key, final String field, final Type type);

    public CompletableFuture<String> hgetStringAsync(final String key, final String field);

    public CompletableFuture<Long> hgetLongAsync(final String key, final String field, long defValue);
    //----------- hxxx --------------

    public <T> CompletableFuture<Map<String, T>> getMapAsync(final Type componentType, final String... keys);

    public <T> CompletableFuture<Collection<T>> getCollectionAsync(final String key, final Type componentType);

    public <T> CompletableFuture<Map<String, Collection<T>>> getCollectionMapAsync(final boolean set, final Type componentType, final String... keys);

    public CompletableFuture<Integer> getCollectionSizeAsync(final String key);

    public <T> CompletableFuture<Collection<T>> getCollectionAndRefreshAsync(final String key, final int expireSeconds, final Type componentType);

    public <T> CompletableFuture<T> spopSetItemAsync(final String key, final Type componentType);

    public <T> CompletableFuture<List<T>> spopSetItemAsync(final String key, final int count, final Type componentType);

    public <T> CompletableFuture<Void> appendListItemAsync(final String key, final Type componentType, final T value);

    public <T> CompletableFuture<Integer> removeListItemAsync(final String key, final Type componentType, final T value);

    public <T> CompletableFuture<Boolean> existsSetItemAsync(final String key, final Type componentType, final T value);

    public <T> CompletableFuture<Void> appendSetItemAsync(final String key, final Type componentType, final T value);

    public <T> CompletableFuture<Integer> removeSetItemAsync(final String key, final Type componentType, final T value);

    public CompletableFuture<byte[]> getBytesAsync(final String key);

    public CompletableFuture<byte[]> getBytesAndRefreshAsync(final String key, final int expireSeconds);

    public CompletableFuture<Void> setBytesAsync(final String key, final byte[] value);

    public CompletableFuture<Void> setBytesAsync(final int expireSeconds, final String key, final byte[] value);

    public <T> CompletableFuture<Void> setBytesAsync(final String key, final Convert convert, final Type type, final T value);

    public <T> CompletableFuture<Void> setBytesAsync(final int expireSeconds, final String key, final Convert convert, final Type type, final T value);

    public CompletableFuture<List<String>> queryKeysAsync();

    public CompletableFuture<List<String>> queryKeysStartsWithAsync(String startsWith);

    public CompletableFuture<List<String>> queryKeysEndsWithAsync(String endsWith);

    public CompletableFuture<Integer> getKeySizeAsync();

    public CompletableFuture<List<CacheEntry< Object>>> queryListAsync();

    public CompletableFuture<String> getStringAsync(final String key);

    public CompletableFuture<String> getStringAndRefreshAsync(final String key, final int expireSeconds);

    public CompletableFuture<Void> setStringAsync(final String key, final String value);

    public CompletableFuture<Void> setStringAsync(final int expireSeconds, final String key, final String value);

    public CompletableFuture<Map<String, String>> getStringMapAsync(final String... keys);

    public CompletableFuture<String[]> getStringArrayAsync(final String... keys);

    public CompletableFuture<Collection<String>> getStringCollectionAsync(final String key);

    public CompletableFuture<Map<String, Collection<String>>> getStringCollectionMapAsync(final boolean set, final String... keys);

    public CompletableFuture<Collection<String>> getStringCollectionAndRefreshAsync(final String key, final int expireSeconds);

    public CompletableFuture<Void> appendStringListItemAsync(final String key, final String value);

    public CompletableFuture<String> spopStringSetItemAsync(final String key);

    public CompletableFuture<List<String>> spopStringSetItemAsync(final String key, final int count);

    public CompletableFuture<Integer> removeStringListItemAsync(final String key, final String value);

    public CompletableFuture<Boolean> existsStringSetItemAsync(final String key, final String value);

    public CompletableFuture<Void> appendStringSetItemAsync(final String key, final String value);

    public CompletableFuture<Integer> removeStringSetItemAsync(final String key, final String value);

    public CompletableFuture<Long> getLongAsync(final String key, long defValue);

    public CompletableFuture<Long> getLongAndRefreshAsync(final String key, final int expireSeconds, long defValue);

    public CompletableFuture<Void> setLongAsync(final String key, long value);

    public CompletableFuture<Void> setLongAsync(final int expireSeconds, final String key, final long value);

    public CompletableFuture<Map<String, Long>> getLongMapAsync(final String... keys);

    public CompletableFuture<Long[]> getLongArrayAsync(final String... keys);

    public CompletableFuture<Collection<Long>> getLongCollectionAsync(final String key);

    public CompletableFuture<Map<String, Collection<Long>>> getLongCollectionMapAsync(final boolean set, final String... keys);

    public CompletableFuture<Collection<Long>> getLongCollectionAndRefreshAsync(final String key, final int expireSeconds);

    public CompletableFuture<Void> appendLongListItemAsync(final String key, final long value);

    public CompletableFuture<Long> spopLongSetItemAsync(final String key);

    public CompletableFuture<List<Long>> spopLongSetItemAsync(final String key, final int count);

    public CompletableFuture<Integer> removeLongListItemAsync(final String key, final long value);

    public CompletableFuture<Boolean> existsLongSetItemAsync(final String key, final long value);

    public CompletableFuture<Void> appendLongSetItemAsync(final String key, final long value);

    public CompletableFuture<Integer> removeLongSetItemAsync(final String key, final long value);

    default CompletableFuture<Boolean> isOpenAsync() {
        return CompletableFuture.completedFuture(isOpen());
    }

    public static enum CacheEntryType {
        LONG, STRING, OBJECT, BYTES, ATOMIC, MAP,
        LONG_SET, STRING_SET, OBJECT_SET,
        LONG_LIST, STRING_LIST, OBJECT_LIST;
    }

    public static final class CacheEntry<T> {

        final CacheEntryType cacheType;

        final String key;

        //<=0表示永久保存
        int expireSeconds;

        volatile int lastAccessed; //最后刷新时间

        T objectValue;

        ConcurrentHashMap<String, Serializable> mapValue;

        CopyOnWriteArraySet<T> csetValue;

        ConcurrentLinkedQueue<T> listValue;

        public CacheEntry(CacheEntryType cacheType, String key, T objectValue, CopyOnWriteArraySet<T> csetValue, ConcurrentLinkedQueue<T> listValue, ConcurrentHashMap<String, Serializable> mapValue) {
            this(cacheType, 0, key, objectValue, csetValue, listValue, mapValue);
        }

        public CacheEntry(CacheEntryType cacheType, int expireSeconds, String key, T objectValue, CopyOnWriteArraySet<T> csetValue, ConcurrentLinkedQueue<T> listValue, ConcurrentHashMap<String, Serializable> mapValue) {
            this(cacheType, expireSeconds, (int) (System.currentTimeMillis() / 1000), key, objectValue, csetValue, listValue, mapValue);
        }

        @ConstructorParameters({"cacheType", "expireSeconds", "lastAccessed", "key", "objectValue", "csetValue", "listValue", "mapValue"})
        public CacheEntry(CacheEntryType cacheType, int expireSeconds, int lastAccessed, String key, T objectValue, CopyOnWriteArraySet<T> csetValue, ConcurrentLinkedQueue<T> listValue, ConcurrentHashMap<String, Serializable> mapValue) {
            this.cacheType = cacheType;
            this.expireSeconds = expireSeconds;
            this.lastAccessed = lastAccessed;
            this.key = key;
            this.objectValue = objectValue;
            this.csetValue = csetValue;
            this.listValue = listValue;
            this.mapValue = mapValue;
        }

        @Override
        public String toString() {
            return JsonFactory.root().getConvert().convertTo(this);
        }

        @ConvertColumn(ignore = true)
        public boolean isListCacheType() {
            return cacheType == CacheEntryType.LONG_LIST || cacheType == CacheEntryType.STRING_LIST || cacheType == CacheEntryType.OBJECT_LIST;
        }

        @ConvertColumn(ignore = true)
        public boolean isSetCacheType() {
            return cacheType == CacheEntryType.LONG_SET || cacheType == CacheEntryType.STRING_SET || cacheType == CacheEntryType.OBJECT_SET;
        }

        @ConvertColumn(ignore = true)
        public boolean isMapCacheType() {
            return cacheType == CacheEntryType.MAP;
        }

        @ConvertColumn(ignore = true)
        public boolean isExpired() {
            return (expireSeconds > 0 && lastAccessed + expireSeconds < (System.currentTimeMillis() / 1000));
        }

        public CacheEntryType getCacheType() {
            return cacheType;
        }

        public int getExpireSeconds() {
            return expireSeconds;
        }

        public int getLastAccessed() {
            return lastAccessed;
        }

        public String getKey() {
            return key;
        }

        public T getObjectValue() {
            return objectValue;
        }

        public CopyOnWriteArraySet<T> getCsetValue() {
            return csetValue;
        }

        public ConcurrentLinkedQueue<T> getListValue() {
            return listValue;
        }

        public ConcurrentHashMap<String, Serializable> getMapValue() {
            return mapValue;
        }
    }
}
