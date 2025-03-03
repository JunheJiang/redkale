/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import java.io.Serializable;
import java.util.Objects;
import static org.redkale.source.ColumnExpress.*;

/**
 * ColumnValue主要用于多个字段更新的表达式。
 * value值一般为: ColumnNodeValue、ColumnFuncNode、Number、String等 <br>
 * 用于 DataSource.updateColumn 方法  <br>
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public class ColumnValue {

    private String column;

    private ColumnExpress express;

    private Serializable value;

    public ColumnValue() {
    }

    public ColumnValue(String column, Serializable value) {
        this(column, ColumnExpress.MOV, value);
    }

    public ColumnValue(String column, ColumnExpress express, Serializable value) {
        Objects.requireNonNull(column);
        this.column = column;
        this.express = express == null ? ColumnExpress.MOV : express;
        this.value = value;
    }

    /**
     * 同 mov 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue create(String column, Serializable value) {
        return new ColumnValue(column, value);
    }

    /**
     * 返回 {column} = {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue mov(String column, Serializable value) {
        return new ColumnValue(column, MOV, value);
    }

    /**
     * 返回 {column} = {column} + {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue inc(String column, Serializable value) {
        return new ColumnValue(column, INC, value);
    }

    /**
     * 返回 {column} = {column} + 1 操作
     *
     * @param column 字段名
     *
     * @return ColumnValue
     *
     * @since 2.4.0
     */
    public static ColumnValue inc(String column) {
        return new ColumnValue(column, INC, 1);
    }

    /**
     * 返回 {column} = {column} - {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue dec(String column, Serializable value) {
        return new ColumnValue(column, DEC, value);
    }

    /**
     * 返回 {column} = {column} - 1 操作
     *
     * @param column 字段名
     *
     * @return ColumnValue
     *
     * @since 2.4.0
     */
    public static ColumnValue dec(String column) {
        return new ColumnValue(column, DEC, 1);
    }

    /**
     * 返回 {column} = {column} * {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue mul(String column, Serializable value) {
        return new ColumnValue(column, MUL, value);
    }

    /**
     * 返回 {column} = {column} / {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue div(String column, Serializable value) {
        return new ColumnValue(column, DIV, value);
    }

    /**
     * 返回 {column} = {column} % {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    //不常用，防止开发者容易在mov时误输入mod
//    public static ColumnValue mod(String column, Serializable value) {
//        return new ColumnValue(column, MOD, value);
//    }
    /**
     * 返回 {column} = {column} &#38; {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue and(String column, Serializable value) {
        return new ColumnValue(column, AND, value);
    }

    /**
     * 返回 {column} = {column} | {value} 操作
     *
     * @param column 字段名
     * @param value  字段值
     *
     * @return ColumnValue
     */
    public static ColumnValue orr(String column, Serializable value) {
        return new ColumnValue(column, ORR, value);
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public ColumnExpress getExpress() {
        return express;
    }

    public void setExpress(ColumnExpress express) {
        this.express = express;
    }

    public Serializable getValue() {
        return value;
    }

    public void setValue(Serializable value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "{\"column\":\"" + column + "\", \"express\":" + express + ", \"value\":" + ((value instanceof CharSequence) ? ("\"" + value + "\"") : value) + "}";
    }
}
