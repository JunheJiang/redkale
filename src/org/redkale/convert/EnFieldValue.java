/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert;

import java.io.Serializable;
import java.lang.reflect.Type;
import org.redkale.convert.json.JsonConvert;

/**
 * newConvert参数中的Function返回结果的数据类
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public class EnFieldValue implements Serializable {

    protected String name;

    protected Type type;

    protected int position;

    protected Object value;

    public EnFieldValue() {
    }

    public EnFieldValue(String name, Object value) {
        this.name = name;
        this.value = value;
    }

    public EnFieldValue(String name, int position, Object value) {
        this.name = name;
        this.position = position;
        this.value = value;
    }

    public EnFieldValue(String name, Type type, Object value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }

    public EnFieldValue(String name, Type type, int position, Object value) {
        this.name = name;
        this.type = type;
        this.position = position;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return JsonConvert.root().convertTo(this);
    }
}
