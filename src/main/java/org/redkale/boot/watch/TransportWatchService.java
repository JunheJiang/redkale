/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.boot.watch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.redkale.annotation.*;
import org.redkale.boot.Application;
import org.redkale.net.*;
import org.redkale.net.http.*;
import org.redkale.net.sncp.*;
import org.redkale.service.*;
import org.redkale.util.AnyValue;
import org.redkale.util.AnyValue.DefaultAnyValue;

/**
 *
 * @author zhangjx
 */
@RestService(name = "transport", catalog = "watch", repair = false)
public class TransportWatchService extends AbstractWatchService {

    @Comment("不存在的Group节点")
    public static final int RET_TRANSPORT_GROUP_NOT_EXISTS = 1606_0001;

    @Comment("非法的Node节点IP地址")
    public static final int RET_TRANSPORT_ADDR_ILLEGAL = 1606_0002;

    @Comment("Node节点IP地址已存在")
    public static final int RET_TRANSPORT_ADDR_EXISTS = 1606_0003;

    protected final ReentrantLock lock = new ReentrantLock();

    @Resource
    protected Application application;

    @Resource
    protected TransportFactory transportFactory;

    @RestMapping(name = "listnodes", auth = false, comment = "获取所有Node节点")
    public List<TransportGroupInfo> listNodes() {
        return transportFactory.getGroupInfos();
    }

    @RestMapping(name = "addnode", auth = false, comment = "动态增加指定Group的Node节点")
    public RetResult addNode(@RestParam(name = "group", comment = "Group节点名") final String group,
        @RestParam(name = "addr", comment = "节点IP") final String addr,
        @RestParam(name = "port", comment = "节点端口") final int port) throws IOException {
        InetSocketAddress address;
        try {
            address = new InetSocketAddress(addr, port);
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
            channel.connect(address).get(2, TimeUnit.SECONDS);  //连接超时2秒
            channel.close();
        } catch (Exception e) {
            return new RetResult(RET_TRANSPORT_ADDR_ILLEGAL, "InetSocketAddress(addr=" + addr + ", port=" + port + ") is illegal or cannot connect");
        }
        if (transportFactory.findGroupName(address) != null) {
            return new RetResult(RET_TRANSPORT_ADDR_ILLEGAL, "InetSocketAddress(addr=" + addr + ", port=" + port + ") is exists");
        }
        lock.lock();
        try {
            if (transportFactory.findGroupInfo(group) == null) {
                return new RetResult(RET_TRANSPORT_GROUP_NOT_EXISTS, "not found group (" + group + ")");
            }
            transportFactory.addGroupInfo(group, address);
            for (Service service : transportFactory.getServices()) {
                if (!Sncp.isSncpDyn(service)) {
                    continue;
                }
                OldSncpClient client = Sncp.getSncpOldClient(service);
                if (Sncp.isRemote(service)) {
                    if (client.getRemoteGroups() != null && client.getRemoteGroups().contains(group)) {
                        client.getRemoteGroupTransport().addRemoteAddresses(address);
                    }
                }
            }
            DefaultAnyValue node = DefaultAnyValue.create("addr", addr).addValue("port", port);
            for (AnyValue groupconf : application.getAppConfig().getAnyValues("group")) {
                if (group.equals(groupconf.getValue("name"))) {
                    ((DefaultAnyValue) groupconf).addValue("node", node);
                    break;
                }
            }
            //application.restoreConfig();
        } finally {
            lock.unlock();
        }
        return RetResult.success();
    }

    @RestMapping(name = "removenode", auth = false, comment = "动态删除指定Group的Node节点")
    public RetResult removeNode(@RestParam(name = "group", comment = "Group节点名") final String group,
        @RestParam(name = "addr", comment = "节点IP") final String addr,
        @RestParam(name = "port", comment = "节点端口") final int port) throws IOException {
        if (group == null) {
            return new RetResult(RET_TRANSPORT_GROUP_NOT_EXISTS, "not found group (" + group + ")");
        }
        final InetSocketAddress address = new InetSocketAddress(addr, port);
        if (!group.equals(transportFactory.findGroupName(address))) {
            return new RetResult(RET_TRANSPORT_ADDR_ILLEGAL, "InetSocketAddress(addr=" + addr + ", port=" + port + ") not belong to group(" + group + ")");
        }
        lock.lock();
        try {
            if (transportFactory.findGroupInfo(group) == null) {
                return new RetResult(RET_TRANSPORT_GROUP_NOT_EXISTS, "not found group (" + group + ")");
            }
            transportFactory.removeGroupInfo(group, address);
            for (Service service : transportFactory.getServices()) {
                if (!Sncp.isSncpDyn(service)) {
                    continue;
                }
                OldSncpClient client = Sncp.getSncpOldClient(service);
                if (Sncp.isRemote(service)) {
                    if (client.getRemoteGroups() != null && client.getRemoteGroups().contains(group)) {
                        client.getRemoteGroupTransport().removeRemoteAddresses(address);
                    }
                }
            }
            for (AnyValue groupconf : application.getAppConfig().getAnyValues("group")) {
                if (group.equals(groupconf.getValue("name"))) {
                    ((DefaultAnyValue) groupconf).removeValue("node", DefaultAnyValue.create("addr", addr).addValue("port", port));
                    break;
                }
            }
            //application.restoreConfig();
        } finally {
            lock.unlock();
        }
        return RetResult.success();
    }

    @RestMapping(name = "test1", auth = false, comment = "预留")
    public RetResult test1() {
        return RetResult.success();
    }

    @RestMapping(name = "test2", auth = false, comment = "预留")
    public RetResult test2() {
        return RetResult.success();
    }

    @RestMapping(name = "test3", auth = false, comment = "预留")
    public RetResult test3() {
        return RetResult.success();
    }

    @RestMapping(name = "test4", auth = false, comment = "预留")
    public RetResult test4() {
        return RetResult.success();
    }
}
