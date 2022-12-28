/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import java.io.Serializable;
import static org.redkale.source.ColumnExpress.*;

/**
 * 作为ColumnValue的value字段值，用于复杂的字段表达式 。  <br>
 * String 视为 字段名  <br>
 * Number 视为 数值   <br>
 * 例如： UPDATE Reord SET updateTime = createTime + 10 WHERE id = 1   <br>
 * source.updateColumn(Record.class, 1, ColumnValue.mov("updateTime", ColumnNodeValue.inc("createTime", 10)));  <br>
 * 例如： UPDATE Reord SET updateTime = createTime * 10 / createCount WHERE id = 1   <br>
 * source.updateColumn(Record.class, 1, ColumnValue.mov("updateTime", ColumnNodeValue.div(ColumnNodeValue.mul("createTime", 10), "createCount")));  <br>
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @since 2.0.0
 */
public class ColumnNodeValue implements ColumnNode {

    protected Serializable left;//类型只能是String、Number、ColumnNodeValue

    protected ColumnExpress express; //MOV时，left必须是String, right必须是null

    protected Serializable right;//类型只能是String、Number、ColumnNodeValue

    public ColumnNodeValue() {
    }

    public ColumnNodeValue(Serializable left, ColumnExpress express, Serializable right) {
        if (express == null) {
            throw new IllegalArgumentException("express cannot be null");
        }
        if (express == MOV) {
            if (!(left instanceof String) || right != null) {
                throw new IllegalArgumentException("left value must be String, right value must be null on ColumnExpress.MOV");
            }
        } else {
            if (!(left instanceof String) && !(left instanceof Number) && !(left instanceof ColumnNodeValue) && !(left instanceof ColumnFuncNode)) {
                throw new IllegalArgumentException("left value must be String, Number, ColumnFuncNode or ColumnNodeValue");
            }
            if (!(right instanceof String) && !(right instanceof Number) && !(right instanceof ColumnNodeValue) && !(right instanceof ColumnFuncNode)) {
                throw new IllegalArgumentException("right value must be String, Number, ColumnFuncNode or ColumnNodeValue");
            }
        }
        this.left = left;
        this.express = express;
        this.right = right;
    }

    public static ColumnNodeValue create(Serializable left, ColumnExpress express, Serializable right) {
        return new ColumnNodeValue(left, express, right);
    }

    public static ColumnNodeValue mov(String left) {
        return new ColumnNodeValue(left, MOV, null);
    }

    public static ColumnNodeValue inc(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, INC, right);
    }

    public static ColumnNodeValue dec(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, DEC, right);
    }

    public static ColumnNodeValue mul(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, MUL, right);
    }

    public static ColumnNodeValue div(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, DIV, right);
    }

    public static ColumnNodeValue mod(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, MOD, right);
    }

    public static ColumnNodeValue and(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, AND, right);
    }

    public static ColumnNodeValue orr(Serializable left, Serializable right) {
        return new ColumnNodeValue(left, ORR, right);
    }

    public ColumnNodeValue inc(Serializable right) {
        return any(INC, right);
    }

    public ColumnNodeValue dec(Serializable right) {
        return any(DEC, right);
    }

    public ColumnNodeValue mul(Serializable right) {
        return any(MUL, right);
    }

    public ColumnNodeValue div(Serializable right) {
        return any(DIV, right);
    }

    public ColumnNodeValue mod(Serializable right) {
        return any(MOD, right);
    }

    public ColumnNodeValue and(Serializable right) {
        return any(AND, right);
    }

    public ColumnNodeValue orr(Serializable right) {
        return any(ORR, right);
    }

    protected ColumnNodeValue any(ColumnExpress express, Serializable right) {
        ColumnNodeValue one = new ColumnNodeValue(this.left, this.express, this.right);
        this.left = one;
        this.express = express;
        this.right = right;
        return this;
    }

    public Serializable getLeft() {
        return left;
    }

    public void setLeft(Serializable left) {
        this.left = left;
    }

    public ColumnExpress getExpress() {
        return express;
    }

    public void setExpress(ColumnExpress express) {
        this.express = express;
    }

    public Serializable getRight() {
        return right;
    }

    public void setRight(Serializable right) {
        this.right = right;
    }

    @Override
    public String toString() {
        return "{\"column\":" + ((left instanceof CharSequence) ? ("\"" + left + "\"") : left) + ", \"express\":" + express + ", \"value\":" + ((right instanceof CharSequence) ? ("\"" + right + "\"") : right) + "}";
    }
}
