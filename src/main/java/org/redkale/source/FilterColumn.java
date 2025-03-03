/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import java.lang.annotation.*;

/**
 * 过滤字段标记
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
@Inherited
@Documented
@Target({FIELD})
@Retention(RUNTIME)
public @interface FilterColumn {

    /**
     * 对应Entity Class中字段的名称， 而不是SQL字段名称
     *
     * @return 字段名
     */
    String name() default "";

    /**
     * 当字段类型是Number时， 如果值&gt;=least() 则需要过滤， 否则跳过该字段 <br>
     * 当字段类型是String时， 如果字符串.length == 0 且 least() == 0 则需要过滤， 否则跳过该字段
     *
     * @return 最小可过滤值
     */
    long least() default 1;

    /**
     * 生成过滤条件时是否屏蔽该字段
     *
     * @return 是否屏蔽该字段
     * @since 2.4.0
     */
    boolean ignore() default false;

    /**
     * express的默认值根据字段类型的不同而不同:  <br>
     * 数组 --&gt; IN  <br>
     * Range --&gt; Between  <br>
     * 其他 --&gt; =  <br>
     *
     * @return 字段表达式
     */
    FilterExpress express() default FilterExpress.EQUAL;

    /**
     * 当标记的字段类型是数组/Collection类型且express不是IN/NOTIN时，则构建过滤条件时会遍历字段值的元素来循环构建表达式，元素之间的关系是AND或OR由该值来确定
     *
     * @return 数组元素间的表达式是否AND关系
     */
    boolean itemand() default true;

    /**
     * 判断字段是否必需，for OpenAPI Specification 3.1.0
     *
     * @return 是否必需
     */
    boolean required() default false;

    /**
     * for OpenAPI Specification 3.1.0
     *
     * @return String
     */
    String example() default "";

    /**
     * 备注描述
     *
     * @return 备注描述
     */
    String comment() default "";

}
