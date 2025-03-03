/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.util;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import java.lang.annotation.*;

/**
 * 被标记的日志级别以上的才会被记录
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
@Deprecated(since = "2.8.0")
@Documented
@Target({TYPE})
@Retention(RUNTIME)
public @interface LogLevel {

    String value();
}
