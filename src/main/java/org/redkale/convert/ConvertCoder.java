/*
 */
package org.redkale.convert;

import java.lang.annotation.*;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;

/**
 * 依附在setter、getter方法、字段进行简单的配置 <br>
 * 优先使用coder字段
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @since 2.7.0
 */
@Inherited
@Documented
@Target({METHOD, FIELD})
@Retention(RUNTIME)
@Repeatable(ConvertCoder.ConvertCoders.class)
public @interface ConvertCoder {

    /**
     * 需要指定的字段类型，指定了coder字段值则可以不设置此字段
     *
     * @return 字段类名
     */
    Class column() default Object.class;

    /**
     * 序列化定制化的 Encodeable
     *
     * @return Encodeable 类
     */
    Class<? extends Encodeable> encoder() default Encodeable.class;

    /**
     * 反序列化定制化的 Decodeable
     *
     * @return Decodeable 类
     */
    Class<? extends Decodeable> decoder() default Decodeable.class;

    /**
     * 解析/序列化定制化的TYPE
     *
     * @return JSON or BSON or ALL
     */
    ConvertType type() default ConvertType.ALL;

    /**
     * ConvertCoder 的多用类
     *
     * <p>
     * 详情见: https://redkale.org
     *
     * @author zhangjx
     */
    @Inherited
    @Documented
    @Target({METHOD, FIELD})
    @Retention(RUNTIME)
    public static @interface ConvertCoders {

        ConvertCoder[] value();
    }
}
