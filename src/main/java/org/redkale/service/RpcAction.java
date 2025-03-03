/*
 *
 */
package org.redkale.service;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import java.lang.annotation.*;

/**
 * 用于自定义SncpActionid，默认会根据Method.toString来计算actionid
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @since 2.8.0
 */
@Inherited
@Documented
@Target({METHOD})
@Retention(RUNTIME)
public @interface RpcAction {

    String name();
}
