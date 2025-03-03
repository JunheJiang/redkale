/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.mq;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import org.redkale.annotation.Comment;
import org.redkale.convert.*;
import org.redkale.convert.bson.BsonConvert;
import org.redkale.convert.json.JsonConvert;
import org.redkale.net.http.HttpSimpleRequest;
import org.redkale.net.sncp.SncpHeader;

/**
 * 存在MQ里面的数据结构<p>
 * groupid + userid 来确定partition， 优先使用 groupid
 *
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 *
 * @since 2.1.0
 */
public class MessageRecord implements Serializable {

    static final byte[] EMPTY_BYTES = new byte[0];

    protected static final byte CTYPE_STRING = 1;

    protected static final byte CTYPE_HTTP_REQUEST = 2;

    protected static final byte CTYPE_HTTP_RESULT = 3;

    protected static final byte CTYPE_BSON_RESULT = 4;

    @ConvertColumn(index = 1)
    @Comment("消息序列号")
    protected long seqid;

    @ConvertColumn(index = 2)
    @Comment("版本")
    protected int version;

    @ConvertColumn(index = 3)
    @Comment("标记位, 自定义时使用")
    protected int flag;

    @ConvertColumn(index = 4)
    @Comment("创建时间")
    protected long createTime;

    @ConvertColumn(index = 5)
    @Comment("用户ID，无用户信息视为null或0, 具体数据类型只能是int、long、String")  //@since 2.5.0 由int改成Serializable
    protected Serializable userid;

    @ConvertColumn(index = 6)
    @Comment("组ID")
    protected String groupid;

    @ConvertColumn(index = 7)
    @Comment("当前topic")
    protected String topic;

    @ConvertColumn(index = 8)
    @Comment("目标topic, 为空表示无目标topic")
    protected String respTopic;

    @ConvertColumn(index = 9)
    @Comment("链路ID")
    protected String traceid;

    @ConvertColumn(index = 10)
    @Comment("消息内容")
    protected byte[] content;

    @ConvertColumn(index = 11)
    @Comment("消息内容的类型")
    protected byte ctype;

    @Comment("本地附加对象，不会被序列化")
    protected Object localAttach;

    public MessageRecord() {
    }

    protected MessageRecord(long seqid, byte ctype, String topic, String respTopic, String traceid, byte[] content) {
        this(seqid, ctype, 1, 0, System.currentTimeMillis(), 0, null, topic, respTopic, traceid, content);
    }

    protected MessageRecord(long seqid, byte ctype, int flag, Serializable userid, String groupid, String topic, String respTopic, String traceid, byte[] content) {
        this(seqid, ctype, 1, flag, System.currentTimeMillis(), userid, groupid, topic, respTopic, traceid, content);
    }

    protected MessageRecord(long seqid, byte ctype, int version, int flag, long createTime, Serializable userid, String groupid, String topic, String respTopic, String traceid, byte[] content) {
        this.seqid = seqid;
        this.ctype = ctype;
        this.version = version;
        this.flag = flag;
        this.createTime = createTime;
        this.userid = userid;
        this.groupid = groupid;
        this.topic = topic;
        this.respTopic = respTopic;
        this.traceid = traceid;
        this.content = content;
    }

    public String contentString() {
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    public MessageRecord attach(Object attach) {
        this.localAttach = attach;
        return this;
    }

    @ConvertDisabled
    public boolean isEmptyTopic() {
        return this.topic == null || this.topic.isEmpty();
    }

    @ConvertDisabled
    public boolean isEmptyRespTopic() {
        return this.respTopic == null || this.respTopic.isEmpty();
    }

    @ConvertDisabled
    public boolean isEmptyTraceid() {
        return this.traceid == null || this.traceid.isEmpty();
    }

    public <T> T convertFromContent(Convert convert, java.lang.reflect.Type type) {
        if (this.content == null || this.content.length == 0) {
            return null;
        }
        return (T) convert.convertFrom(type, this.content);
    }

    public <T> T decodeContent(MessageCoder<T> coder) {
        if (this.content == null || this.content.length == 0) {
            return null;
        }
        return (T) coder.decode(this.content);
    }

    public <T> MessageRecord encodeContent(MessageCoder<T> coder, T data) {
        this.content = coder.encode(data);
        return this;
    }

    public int hash() {
        if (groupid != null && !groupid.isEmpty()) {
            return groupid.hashCode();
        } else if (userid != null) {
            return userid.hashCode();
        } else {
            return 0;
        }
    }

    public MessageRecord version(int version) {
        this.version = version;
        return this;
    }

    public MessageRecord flag(int flag) {
        this.flag = flag;
        return this;
    }

    public MessageRecord createTime(long createtime) {
        this.createTime = createtime;
        return this;
    }

    public MessageRecord userid(Serializable userid) {
        this.userid = userid;
        return this;
    }

    public MessageRecord groupid(String groupid) {
        this.groupid = groupid;
        return this;
    }

    public MessageRecord topic(String topic) {
        this.topic = topic;
        return this;
    }

    public MessageRecord respTopic(String resptopic) {
        this.respTopic = resptopic;
        return this;
    }

    public MessageRecord content(byte[] content) {
        this.content = content;
        return this;
    }

    public MessageRecord traceid(String traceid) {
        this.traceid = traceid;
        return this;
    }

    public MessageRecord contentString(String content) {
        this.content = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public long getSeqid() {
        return seqid;
    }

    public void setSeqid(long seqid) {
        this.seqid = seqid;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public Serializable getUserid() {
        return userid;
    }

    public void setUserid(Serializable userid) {
        this.userid = userid;
    }

    public String getTraceid() {
        return traceid;
    }

    public void setTraceid(String traceid) {
        this.traceid = traceid;
    }

    public String getGroupid() {
        return groupid;
    }

    public void setGroupid(String groupid) {
        this.groupid = groupid;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getRespTopic() {
        return respTopic;
    }

    public void setRespTopic(String respTopic) {
        this.respTopic = respTopic;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    @Override
    public String toString() {
        //return JsonConvert.root().convertTo(this);
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"seqid\":").append(this.seqid);
        sb.append(",\"version\":").append(this.version);
        if (this.flag != 0) {
            sb.append(",\"flag\":").append(this.flag);
        }
        if (this.createTime != 0) {
            sb.append(",\"createTime\":").append(this.createTime);
        }
        if (this.userid != null) {
            sb.append(",\"userid\":").append(this.userid);
        }
        if (this.groupid != null) {
            sb.append(",\"groupid\":\"").append(this.groupid).append("\"");
        }
        if (this.topic != null) {
            sb.append(",\"topic\":\"").append(this.topic).append("\"");
        }
        if (this.respTopic != null) {
            sb.append(",\"respTopic\":\"").append(this.respTopic).append("\"");
        }
        if (this.content != null) {
            if (this.ctype == CTYPE_BSON_RESULT && this.content.length > SncpHeader.HEADER_SIZE) {
                int offset = SncpHeader.HEADER_SIZE + 1; //循环占位符
                Object rs = BsonConvert.root().convertFrom(Object.class, this.content, offset, this.content.length - offset);
                sb.append(",\"content\":").append(rs);
            } else if (this.ctype == CTYPE_HTTP_REQUEST) {
                HttpSimpleRequest req = HttpSimpleRequestCoder.getInstance().decode(this.content);
                if (req != null) {
                    if (req.getCurrentUserid() == null) {
                        req.setCurrentUserid(this.userid);
                    }
                    if (req.getHashid() == 0) {
                        req.setHashid(this.hash());
                    }
                }
                sb.append(",\"content\":").append(req);
            } else if (this.ctype == CTYPE_HTTP_RESULT) {
                sb.append(",\"content\":").append(HttpResultCoder.getInstance().decode(this.content));
            } else if (localAttach != null) {
                sb.append(",\"attach\":").append(JsonConvert.root().convertTo(localAttach));
            } else {
                sb.append(",\"content\":\"").append(new String(this.content, StandardCharsets.UTF_8)).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

//    public static void main(String[] args) throws Throwable {
//        System.out.println(new MessageRecord(333, 2, 3, null, "tt", null, "xxx".getBytes()));
//    }
}
