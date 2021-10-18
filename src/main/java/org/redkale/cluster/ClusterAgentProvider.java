/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.cluster;

import org.redkale.util.AnyValue;

/**
 * 自定义的ClusterAgent加载器
 *
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @since 2.5.0
 */
public interface ClusterAgentProvider {

    public boolean acceptsConf(AnyValue config);

    public Class<? extends ClusterAgent> agentClass();
}
