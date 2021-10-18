/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert;

import org.redkale.util.Creator;
import java.lang.reflect.*;
import java.util.*;
import org.redkale.convert.ext.StringSimpledCoder;
import org.redkale.util.*;

/**
 * 自定义对象的反序列化操作类
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <R> Reader输入的子类
 * @param <T> 反解析的数据类型
 */
@SuppressWarnings("unchecked")
public class ObjectDecoder<R extends Reader, T> implements Decodeable<R, T> {

    protected final Type type;

    protected final Class typeClass;

    protected Creator<T> creator;

    protected DeMember<R, T, ?>[] creatorConstructorMembers = null;

    protected DeMember[] members;

    protected ConvertFactory factory;

    protected volatile boolean inited = false;

    protected final Object lock = new Object();

    protected ObjectDecoder(Type type) {
        this.type = ((type instanceof Class) && ((Class) type).isInterface()) ? Object.class : type;
        if (type instanceof ParameterizedType) {
            final ParameterizedType pt = (ParameterizedType) type;
            this.typeClass = (Class) pt.getRawType();
        } else if (type instanceof TypeVariable) {
            TypeVariable tv = (TypeVariable) type;
            Type[] ts = tv.getBounds();
            if (ts.length == 1 && ts[0] instanceof Class) {
                this.typeClass = (Class) ts[0];
            } else {
                throw new ConvertException("[" + type + "] is no a class or ParameterizedType");
            }
        } else {
            this.typeClass = (Class) type;
        }
        this.members = new DeMember[0];
    }

    public void init(final ConvertFactory factory) {
        this.factory = factory;
        try {
            if (type == Object.class) {
                this.creatorConstructorMembers = null;
                return;
            }

            Class clazz = null;
            if (type instanceof ParameterizedType) {
                final ParameterizedType pts = (ParameterizedType) type;
                clazz = (Class) (pts).getRawType();
            } else if (type instanceof TypeVariable) {
                TypeVariable tv = (TypeVariable) type;
                Type[] ts = tv.getBounds();
                if (ts.length == 1 && ts[0] instanceof Class) {
                    clazz = (Class) ts[0];
                } else {
                    throw new ConvertException("[" + type + "] is no a class or TypeVariable");
                }
            } else if (!(type instanceof Class)) {
                throw new ConvertException("[" + type + "] is no a class");
            } else {
                clazz = (Class) type;
            }
            if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
                this.creator = factory.loadCreator(clazz);
                if (this.creator == null) throw new ConvertException("Cannot create a creator for " + clazz);
            }
            final Set<DeMember> list = new LinkedHashSet();
            final String[] cps = ObjectEncoder.findConstructorProperties(this.creator);
            try {
                ConvertColumnEntry ref;
                RedkaleClassLoader.putReflectionPublicFields(clazz.getName());
                for (final Field field : clazz.getFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (factory.isConvertDisabled(field)) continue;
                    ref = factory.findRef(clazz, field);
                    if (ref != null && ref.ignore()) continue;
                    ConvertSmallString small = field.getAnnotation(ConvertSmallString.class);
                    Decodeable<R, ?> fieldCoder;
                    if (small != null && field.getType() == String.class) {
                        fieldCoder = StringSimpledCoder.SmallStringSimpledCoder.instance;
                    } else {
                        fieldCoder = factory.findFieldCoder(clazz, field.getName());
                    }
                    if (fieldCoder == null) {
                        Type t = TypeToken.createClassType(TypeToken.getGenericType(field.getGenericType(), this.type), this.type);
                        fieldCoder = factory.loadDecoder(t);
                    }
                    DeMember member = new DeMember(ObjectEncoder.createAttribute(factory, type, clazz, field, null, null), fieldCoder, field, null);
                    if (ref != null) member.index = ref.getIndex();
                    list.add(member);
                }
                final boolean reversible = factory.isReversible();
                RedkaleClassLoader.putReflectionPublicMethods(clazz.getName());
                for (final Method method : clazz.getMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) continue;
                    if (Modifier.isAbstract(method.getModifiers())) continue;
                    if (method.isSynthetic()) continue;
                    if (method.getReturnType() != void.class) continue;
                    if (method.getParameterCount() != 1) continue;
                    if (method.getName().length() < 4) continue;
                    if (!method.getName().startsWith("set")) continue;
                    if (factory.isConvertDisabled(method)) continue;
                    if (reversible && (cps == null || !ObjectEncoder.contains(cps, ConvertFactory.readGetSetFieldName(method)))) {
                        boolean is = method.getParameterTypes()[0] == boolean.class || method.getParameterTypes()[0] == Boolean.class;
                        try {
                            Method getter = clazz.getMethod(method.getName().replaceFirst("set", is ? "is" : "get"));
                            if (getter.getReturnType() != method.getParameterTypes()[0]) continue;
                        } catch (Exception e) {
                            continue;
                        }
                    } else {
                        String fieldname = ConvertFactory.readGetSetFieldName(method);
                        Field f = null;
                        try {
                            f = clazz.getDeclaredField(fieldname);
                            if (f.getType() != method.getParameterTypes()[0]) continue;
                        } catch (Exception e) {
                        }
                        if (f == null) {
                            boolean is = method.getParameterTypes()[0] == boolean.class || method.getParameterTypes()[0] == Boolean.class;
                            try {
                                Method getter = clazz.getMethod(method.getName().replaceFirst("set", is ? "is" : "get"));
                                if (getter.getReturnType() != method.getParameterTypes()[0]) continue;
                            } catch (Exception e) {
                            }
                        }
                    }
                    ref = factory.findRef(clazz, method);
                    if (ref != null && ref.ignore()) continue;

                    ConvertSmallString small = method.getAnnotation(ConvertSmallString.class);
                    Decodeable<R, ?> fieldCoder;
                    if (small != null && method.getReturnType() == String.class) {
                        fieldCoder = StringSimpledCoder.SmallStringSimpledCoder.instance;
                    } else {
                        fieldCoder = factory.findFieldCoder(clazz, ConvertFactory.readGetSetFieldName(method));
                    }
                    if (fieldCoder == null) {
                        Type t = TypeToken.createClassType(TypeToken.getGenericType(method.getGenericParameterTypes()[0], this.type), this.type);
                        fieldCoder = factory.loadDecoder(t);
                    }
                    DeMember member = new DeMember(ObjectEncoder.createAttribute(factory, type, clazz, null, null, method), fieldCoder, ConvertFactory.readGetSetField(method), method);
                    if (ref != null) member.index = ref.getIndex();
                    list.add(member);
                }
                if (cps != null) { //可能存在某些构造函数中的字段名不存在setter方法
                    for (final String constructorField : cps) {
                        boolean flag = false;
                        for (DeMember m : list) {
                            if (m.attribute.field().equals(constructorField)) {
                                flag = true;
                                break;
                            }
                        }
                        if (flag) continue;
                        //不存在setter方法
                        try {
                            Field f = clazz.getDeclaredField(constructorField);
                            Type t = TypeToken.createClassType(f.getGenericType(), this.type);
                            list.add(new DeMember(ObjectEncoder.createAttribute(factory, type, clazz, f, null, null), factory.loadDecoder(t), f, null));
                        } catch (NoSuchFieldException nsfe) { //不存在field， 可能存在getter方法
                            char[] fs = constructorField.toCharArray();
                            fs[0] = Character.toUpperCase(fs[0]);
                            String mn = new String(fs);
                            Method getter;
                            try {
                                getter = clazz.getMethod("get" + mn);
                            } catch (NoSuchMethodException ex) {
                                getter = clazz.getMethod("is" + mn);
                            }
                            Type t = TypeToken.createClassType(TypeToken.getGenericType(getter.getGenericParameterTypes()[0], this.type), this.type);
                            list.add(new DeMember(ObjectEncoder.createAttribute(factory, type, clazz, null, getter, null), factory.loadDecoder(t), ConvertFactory.readGetSetField(getter), getter));
                        }
                    }
                }

                List<DeMember> sorts = new ArrayList<>(list);
                Collections.sort(sorts, (a, b) -> a.compareTo(factory.isFieldSort(), b));
                Set<Integer> pos = new HashSet<>();
                for (DeMember member : sorts) {
                    if (member.index > 0) pos.add(member.index);
                }
                int pidx = 0;
                for (DeMember member : sorts) {
                    if (member.index > 0) {
                        member.position = member.index;
                    } else {
                        while (pos.contains(++pidx));
                        member.position = pidx;
                    }
                    initForEachDeMember(factory, member);
                }

                this.members = list.toArray(new DeMember[list.size()]);
                Arrays.sort(this.members, (a, b) -> a.compareTo(factory.isFieldSort(), b));

                if (cps != null) {
                    final String[] fields = cps;
                    final DeMember<R, T, ?>[] ms = new DeMember[fields.length];
                    for (int i = 0; i < fields.length; i++) {
                        for (DeMember m : this.members) {
                            if (m.attribute.field().equals(fields[i])) {
                                ms[i] = m;
                                break;
                            }
                        }
                    }
                    this.creatorConstructorMembers = ms;
                }
            } catch (Exception ex) {
                throw new ConvertException(ex);
            }
        } finally {
            inited = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    protected void initForEachDeMember(ConvertFactory factory, DeMember member) {
    }

    protected void setTag(DeMember member, int tag) {
        member.tag = tag;
    }

    /**
     * 对象格式: [0x1][short字段个数][字段名][字段值]...[0x2]
     *
     * @param in 输入流
     *
     * @return 反解析后的对象结果
     */
    @Override
    public T convertFrom(final R in) {
        R objin = objectReader(in);
        final String clazz = objin.readObjectB(typeClass);
        if (clazz == null) return null;
        if (!clazz.isEmpty()) return (T) factory.loadDecoder(factory.getEntityAlias(clazz)).convertFrom(objin);
        if (!this.inited) {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        if (this.creator == null) {
            if (typeClass.isInterface() || Modifier.isAbstract(typeClass.getModifiers())) {
                throw new ConvertException("[" + typeClass + "] is a interface or abstract class, cannot create it's Creator.");
            }
        }
        if (this.creatorConstructorMembers == null) {  //空构造函数
            final T result = this.creator == null ? null : this.creator.create();
            boolean first = true;
            while (hasNext(objin, first)) {
                DeMember member = objin.readFieldName(members);
                objin.readBlank();
                if (member == null) {
                    objin.skipValue(); //跳过不存在的属性的值
                } else {
                    readMemberValue(objin, member, result, first);
                }
                first = false;
            }
            objin.readObjectE(typeClass);
            return result;
        } else {  //带参数的构造函数
            final DeMember<R, T, ?>[] fields = this.creatorConstructorMembers;
            final Object[] constructorParams = new Object[fields.length];
            final Object[][] otherParams = new Object[this.members.length][2];
            int oc = 0;
            boolean first = true;
            while (hasNext(objin, first)) {
                DeMember member = objin.readFieldName(members);
                objin.readBlank();
                if (member == null) {
                    objin.skipValue(); //跳过不存在的属性的值
                } else {
                    Object val = readMemberValue(objin, member, first);
                    boolean flag = true;
                    for (int i = 0; i < fields.length; i++) {
                        if (member == fields[i]) {
                            constructorParams[i] = val;
                            flag = false;
                            break;
                        }
                    }
                    if (flag) otherParams[oc++] = new Object[]{member.attribute, val};

                }
                first = false;
            }
            objin.readObjectE(typeClass);
            if (this.creator == null) return null;
            final T result = this.creator.create(constructorParams);
            for (int i = 0; i < oc; i++) {
                ((Attribute) otherParams[i][0]).set(result, otherParams[i][1]);
            }
            return result;
        }
    }

    protected R objectReader(R in) {
        return in;
    }

    protected boolean hasNext(R in, boolean first) {
        return in.hasNext();
    }

    protected Object readMemberValue(R in, DeMember member, boolean first) {
        return member.read(in);
    }

    protected void readMemberValue(R in, DeMember member, T result, boolean first) {
        member.read(in, result);
    }

    @Override
    public Type getType() {
        return this.type;
    }

    public DeMember[] getMembers() {
        return Arrays.copyOf(members, members.length);
    }

    @Override
    public String toString() {
        return "ObjectDecoder{" + "type=" + type + ", members=" + Arrays.toString(members) + '}';
    }
}
