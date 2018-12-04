/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.truffle.espresso.intrinsics;

import static com.oracle.truffle.espresso.meta.Meta.meta;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.impl.FieldInfo;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.runtime.StaticObjectArray;
import com.oracle.truffle.espresso.runtime.StaticObjectClass;
import com.oracle.truffle.espresso.runtime.StaticObjectImpl;

import sun.misc.Unsafe;

@EspressoIntrinsics
public class Target_sun_misc_Unsafe {

    private static final int SAFETY_FIELD_OFFSET = 123456789;

    private static Unsafe U;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @Intrinsic(hasReceiver = true)
    public static int arrayBaseOffset(@SuppressWarnings("unused") Object self, @Type(Class.class) StaticObjectClass clazz) {
        assert clazz.getMirror().isArray();
        if (clazz.getMirror().getComponentType().isPrimitive()) {
            Class<?> hostPrimitive = clazz.getMirror().getComponentType().getJavaKind().toJavaClass();
            return U.arrayBaseOffset(Array.newInstance(hostPrimitive, 0).getClass());
        } else {
            // Just a reference type.
            return U.arrayBaseOffset(Object[].class);
        }
    }

    @Intrinsic(hasReceiver = true)
    public static int arrayIndexScale(@SuppressWarnings("unused") Object self, @Type(Class.class) StaticObjectClass clazz) {
        assert clazz.getMirror().isArray();
        if (clazz.getMirror().getComponentType().isPrimitive()) {
            Class<?> hostPrimitive = clazz.getMirror().getComponentType().getJavaKind().toJavaClass();
            return U.arrayIndexScale(Array.newInstance(hostPrimitive, 0).getClass());
        } else {
            // Just a reference type.
            return U.arrayIndexScale(Object[].class);
        }
    }

    @Intrinsic(hasReceiver = true)
    public static int addressSize(@SuppressWarnings("unused") Object self) {
        return 4;
    }

    @Intrinsic(hasReceiver = true)
    public static long objectFieldOffset(@SuppressWarnings("unused") Object self, @Type(Field.class) StaticObjectImpl field) {
        StaticObjectImpl curField = field;
        FieldInfo target = null;
        while (target == null) {
            target = (FieldInfo) curField.getHiddenField(Target_java_lang_Class.HIDDEN_FIELD_KEY);
            if (target == null) {
                curField = (StaticObjectImpl) meta(curField).declaredField("root").get();
            }
        }

        return SAFETY_FIELD_OFFSET + meta(target).getUnsafeInstanceOffset();
    }

    @Intrinsic(hasReceiver = true)
    public static final boolean compareAndSwapObject(@SuppressWarnings("unused") Object self, @Type(Object.class) StaticObject holder, long offset, Object before, Object after) {
        if (holder instanceof StaticObjectArray) {
            return U.compareAndSwapObject(((StaticObjectArray) holder).getWrapped(), offset, before, after);
        }
        // TODO(peterssen): Current workaround assumes it's a field access, offset <-> field index.
        Meta.Field f = getInstanceFieldFromOffset(holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        Object inTheField = f.get(holder);
        if (inTheField == before) {
            f.set(holder, after);
            return true;
        } else {
            return false;
        }
    }

    // FIXME(peterssen): This abomination must go, once the object model land.
    private static Meta.Field getInstanceFieldFromOffset(StaticObject holder, int offset) {
        if (!(0 <= offset && offset < 1 << 16)) {
            throw EspressoError.shouldNotReachHere("the field offset is not normalized");
        }
        Meta.Klass klass = meta(holder.getKlass());
        List<Meta.Klass> superKlasses = new ArrayList<>();
        // Includes own.
        for (Meta.Klass superKlass = klass; superKlass != null; superKlass = superKlass.getSuperclass()) {
            superKlasses.add(superKlass);
        }
        Collections.reverse(superKlasses);

        int curOffset = 0;
        for (Meta.Klass superKlass : superKlasses) {
            FieldInfo declFields[] = superKlass.rawKlass().getDeclaredFields();
            if (curOffset + declFields.length < offset) {
                curOffset += declFields.length;
                continue;
            }

            if (offset != meta(declFields[offset - curOffset]).getUnsafeInstanceOffset()) {
                throw EspressoError.shouldNotReachHere("Field offset for Unsafe do NOT match.");
            }

            return meta(declFields[offset - curOffset]);
        }

        throw EspressoError.shouldNotReachHere();
    }

    @Intrinsic(hasReceiver = true)
    public static int getIntVolatile(@SuppressWarnings("unused") Object unsafe, @Type(Object.class) StaticObject holder, long offset) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset(holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        return (int) f.get(holder);
    }

    @Intrinsic(hasReceiver = true)
    public static long getLongVolatile(@SuppressWarnings("unused") Object unsafe, @Type(Object.class) StaticObject holder, long offset) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset(holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        return (long) f.get(holder);
    }

    @Intrinsic(hasReceiver = true)
    public static @Type(Class.class) StaticObject defineClass(@SuppressWarnings("unused") Object self, @Type(String.class) StaticObject name,
                    byte[] buf, int offset, int len, @Type(ClassLoader.class) StaticObject loader,
                    @SuppressWarnings("unused") @Type(ProtectionDomain.class) StaticObject pd) {
        // TODO(peterssen): Protection domain is ignored.
        byte[] bytes = Arrays.copyOfRange(buf, offset, len);
        return EspressoLanguage.getCurrentContext().getRegistries().defineKlass(Meta.toHost(name), bytes, loader).mirror();

    }

    @Intrinsic(hasReceiver = true)
    public static boolean compareAndSwapInt(@SuppressWarnings("unused") Object self, @Type(Object.class) StaticObject holder, long offset, int before, int after) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset(holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        int inTheField = (int) f.get(holder);
        if (inTheField == before) {
            f.set(holder, after);
            return true;
        } else {
            return false;
        }
    }

    @Intrinsic(hasReceiver = true)
    public static boolean compareAndSwapLong(@SuppressWarnings("unused") Object self, @Type(Object.class) StaticObject holder, long offset, long before, long after) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset(holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        long inTheField = (long) f.get(holder);
        if (inTheField == before) {
            f.set(holder, after);
            return true;
        } else {
            return false;
        }
    }

    @Intrinsic
    public static void registerNatives() {
        /* nop */
    }

    @Intrinsic(hasReceiver = true)
    public static long allocateMemory(@SuppressWarnings("unused") Object self, long length) {
        return U.allocateMemory(length);
    }

    @Intrinsic(hasReceiver = true)
    public static void freeMemory(@SuppressWarnings("unused") Object self, long address) {
        U.freeMemory(address);
    }

    @Intrinsic(hasReceiver = true)
    public static void putLong(@SuppressWarnings("unused") Object self, Object holder, long offset, long x) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset((StaticObject) holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        f.set((StaticObject) holder, x);
    }

    @Intrinsic(hasReceiver = true)
    public static void putLong(@SuppressWarnings("unused") Object self, long offset, long x) {
        U.putLong(offset, x);
    }

    @Intrinsic(hasReceiver = true)
    public static byte getByte(@SuppressWarnings("unused") Object self, Object holder, long offset) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset((StaticObject) holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        return (byte) f.get((StaticObject) holder);
    }

    @Intrinsic(hasReceiver = true)
    public static int getInt(@SuppressWarnings("unused") Object self, Object holder, long offset) {
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset((StaticObject) holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        return (int) f.get((StaticObject) holder);
    }

    @Intrinsic(hasReceiver = true)
    public static byte getByte(@SuppressWarnings("unused") Object self, long offset) {
        return U.getByte(offset);
    }

    @Intrinsic(hasReceiver = true)
    public static int getInt(@SuppressWarnings("unused") Object self, long offset) {
        return U.getInt(offset);
    }

    @Intrinsic(hasReceiver = true)
    public static Object getObjectVolatile(@SuppressWarnings("unused") Object self, Object holder, long offset) {
        if (holder instanceof StaticObjectArray) {
            return U.getObjectVolatile(((StaticObjectArray) holder).getWrapped(), offset);
        }
        // TODO(peterssen): Current workaround assumes it's a field access, encoding is offset <->
        // field index.
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset((StaticObject) holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        return f.get((StaticObject) holder);
    }

    @Intrinsic(hasReceiver = true)
    public static void putObjectVolatile(@SuppressWarnings("unused") Object self, Object holder, long offset, Object value) {
        if (holder instanceof StaticObjectArray) {
            U.putObjectVolatile(((StaticObjectArray) holder).getWrapped(), offset, value);
            return;
        }
        // TODO(peterssen): Current workaround assumes it's a field access, encoding is offset <->
        // field index.
        // TODO(peterssen): Use holder.getKlass().findInstanceFieldWithOffset
        Meta.Field f = getInstanceFieldFromOffset((StaticObject) holder, Math.toIntExact(offset) - SAFETY_FIELD_OFFSET);
        f.set((StaticObject) holder, value);
    }

    @Intrinsic(hasReceiver = true)
    public static void ensureClassInitialized(@SuppressWarnings("unused") Object self, @Type(Class.class) StaticObject clazz) {
        meta(((StaticObjectClass) clazz).getMirror()).safeInitialize();
    }

    @Intrinsic(hasReceiver = true)
    public static void copyMemory(@SuppressWarnings("unused") Object self, Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
        if (bytes == 0) {
            return;
        }
        U.copyMemory(MetaUtil.unwrap(srcBase), srcOffset, MetaUtil.unwrap(destBase), destOffset, bytes);
    }

    @Intrinsic(hasReceiver = true)
    public static void putByte(@SuppressWarnings("unused") Object self, long offset, byte value) {
        U.putByte(offset, value);
    }

    @Intrinsic(hasReceiver = true)
    public static void putByte(@SuppressWarnings("unused") Object self, Object object, long offset, byte value) {
        U.putByte(MetaUtil.unwrap(object), offset, value);
    }

    @Intrinsic(hasReceiver = true)
    public static Object allocateInstance(@SuppressWarnings("unused") Object self, @Type(Class.class) StaticObject clazz) { // throws
        // InstantiationException;
        assert !((StaticObjectClass) clazz).getMirror().isAbstract();
        return EspressoLanguage.getCurrentContext().getInterpreterToVM().newObject(((StaticObjectClass) clazz).getMirror());
    }

    @Intrinsic(hasReceiver = true)
    public static int pageSize(@SuppressWarnings("unused") Object self) {
        return U.pageSize();
    }

    @Intrinsic(hasReceiver = true)
    public static void setMemory(@SuppressWarnings("unused") Object self, Object o, long offset, long bytes, byte value) {
        U.setMemory(StaticObject.isNull(o) ? null : o, offset, bytes, value);
    }
}
