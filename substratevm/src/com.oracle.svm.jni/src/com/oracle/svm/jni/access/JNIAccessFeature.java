/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.svm.jni.access;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.word.WordTypes;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.impl.ConfigurationCondition;
import org.graalvm.nativeimage.impl.ReflectionRegistry;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.configure.ConditionalElement;
import com.oracle.svm.core.configure.ConfigurationFile;
import com.oracle.svm.core.configure.ConfigurationFiles;
import com.oracle.svm.core.configure.ReflectionConfigurationParser;
import com.oracle.svm.core.jni.JNIRuntimeAccess;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.ConditionalConfigurationRegistry;
import com.oracle.svm.hosted.FallbackFeature;
import com.oracle.svm.hosted.FeatureImpl.AfterRegistrationAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.CompilationAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.DuringAnalysisAccessImpl;
import com.oracle.svm.hosted.ProgressReporter;
import com.oracle.svm.hosted.code.CEntryPointData;
import com.oracle.svm.hosted.code.FactoryMethodSupport;
import com.oracle.svm.hosted.code.SimpleSignature;
import com.oracle.svm.hosted.config.ConfigurationParserUtils;
import com.oracle.svm.hosted.meta.KnownOffsetsFeature;
import com.oracle.svm.hosted.meta.MaterializedConstantFields;
import com.oracle.svm.hosted.substitute.SubstitutionReflectivityFilter;
import com.oracle.svm.jni.JNIJavaCallTrampolines;
import com.oracle.svm.jni.hosted.JNICallTrampolineMethod;
import com.oracle.svm.jni.hosted.JNIFieldAccessorMethod;
import com.oracle.svm.jni.hosted.JNIJavaCallVariantWrapperMethod;
import com.oracle.svm.jni.hosted.JNIJavaCallVariantWrapperMethod.CallVariant;
import com.oracle.svm.jni.hosted.JNIJavaCallWrapperMethod;
import com.oracle.svm.util.ReflectionUtil;

import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Prepares classes, methods and fields before and during the analysis so that they are accessible
 * via JNI at image runtime.
 */
public class JNIAccessFeature implements Feature {

    @Fold
    public static JNIAccessFeature singleton() {
        return ImageSingletons.lookup(JNIAccessFeature.class);
    }

    /** A group of wrappers for the same target signature, but different JNI call variants. */
    static final class JNIJavaCallVariantWrapperGroup {
        static final JNIJavaCallVariantWrapperGroup NONE = new JNIJavaCallVariantWrapperGroup(null, null, null);

        final JNIJavaCallVariantWrapperMethod varargs;
        final JNIJavaCallVariantWrapperMethod array;
        final JNIJavaCallVariantWrapperMethod valist;

        JNIJavaCallVariantWrapperGroup(JNIJavaCallVariantWrapperMethod varargs, JNIJavaCallVariantWrapperMethod array, JNIJavaCallVariantWrapperMethod valist) {
            this.varargs = varargs;
            this.array = array;
            this.valist = valist;
        }
    }

    private boolean sealed = false;
    private final Map<String, JNICallTrampolineMethod> trampolineMethods = new ConcurrentHashMap<>();
    private final Map<SimpleSignature, JNIJavaCallWrapperMethod> javaCallWrapperMethods = new ConcurrentHashMap<>();
    private final Map<SimpleSignature, JNIJavaCallVariantWrapperGroup> callVariantWrappers = new ConcurrentHashMap<>();
    private final Map<SimpleSignature, JNIJavaCallVariantWrapperGroup> nonvirtualCallVariantWrappers = new ConcurrentHashMap<>();

    private int loadedConfigurations;

    private final Set<Class<?>> newClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Executable> newMethods = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Field, Boolean> newFields = new ConcurrentHashMap<>();
    private final Map<JNINativeLinkage, JNINativeLinkage> newLinkages = new ConcurrentHashMap<>();

    private final Map<JNINativeLinkage, JNINativeLinkage> nativeLinkages = new ConcurrentHashMap<>();

    public static class Options {
        @Option(help = "Print JNI methods added to generated image")//
        public static final HostedOptionKey<Boolean> PrintJNIMethods = new HostedOptionKey<>(false);
    }

    private void abortIfSealed() {
        UserError.guarantee(!sealed, "Classes, methods and fields must be registered for JNI access before the analysis has completed.");
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        // Ensure that KnownOffsets is fully initialized before we access it
        return List.of(KnownOffsetsFeature.class);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess arg) {
        AfterRegistrationAccessImpl access = (AfterRegistrationAccessImpl) arg;

        JNIReflectionDictionary.initialize();

        JNIRuntimeAccessibilitySupportImpl registry = new JNIRuntimeAccessibilitySupportImpl();
        ImageSingletons.add(JNIRuntimeAccess.JNIRuntimeAccessibilitySupport.class, registry);

        ReflectionConfigurationParser<ConditionalElement<Class<?>>> parser = ConfigurationParserUtils.create(registry, access.getImageClassLoader());
        loadedConfigurations = ConfigurationParserUtils.parseAndRegisterConfigurations(parser, access.getImageClassLoader(), "JNI",
                        ConfigurationFiles.Options.JNIConfigurationFiles, ConfigurationFiles.Options.JNIConfigurationResources, ConfigurationFile.JNI.getFileName());
    }

    private class JNIRuntimeAccessibilitySupportImpl extends ConditionalConfigurationRegistry implements JNIRuntimeAccess.JNIRuntimeAccessibilitySupport, ReflectionRegistry {

        @Override
        public void register(ConfigurationCondition condition, boolean unsafeAllocated, Class<?> clazz) {
            assert !unsafeAllocated : "unsafeAllocated can be only set via Unsafe.allocateInstance, not via JNI.";
            abortIfSealed();
            registerConditionalConfiguration(condition, () -> newClasses.add(clazz));
        }

        @Override
        public void register(ConfigurationCondition condition, boolean queriedOnly, Executable... methods) {
            abortIfSealed();
            registerConditionalConfiguration(condition, () -> newMethods.addAll(Arrays.asList(methods)));
        }

        @Override
        public void register(ConfigurationCondition condition, boolean finalIsWritable, Field... fields) {
            abortIfSealed();
            registerConditionalConfiguration(condition, () -> registerFields(finalIsWritable, fields));
        }

        private void registerFields(boolean finalIsWritable, Field[] fields) {
            for (Field field : fields) {
                boolean writable = finalIsWritable || !Modifier.isFinal(field.getModifiers());
                newFields.put(field, writable);
            }
        }

    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess arg) {
        if (!ImageSingletons.contains(JNIFieldAccessorMethod.Factory.class)) {
            ImageSingletons.add(JNIFieldAccessorMethod.Factory.class, new JNIFieldAccessorMethod.Factory());
        }
        if (!ImageSingletons.contains(JNIJavaCallWrapperMethod.Factory.class)) {
            ImageSingletons.add(JNIJavaCallWrapperMethod.Factory.class, new JNIJavaCallWrapperMethod.Factory());
        }

        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) arg;

        for (CallVariant variant : CallVariant.values()) {
            registerJavaCallTrampoline(access, variant, false);
            registerJavaCallTrampoline(access, variant, true);
        }

        /* duplicated to reduce the number of analysis iterations */
        getConditionalConfigurationRegistry().flushConditionalConfiguration(access);
    }

    private static ConditionalConfigurationRegistry getConditionalConfigurationRegistry() {
        return (ConditionalConfigurationRegistry) ImageSingletons.lookup(JNIRuntimeAccess.JNIRuntimeAccessibilitySupport.class);
    }

    private static void registerJavaCallTrampoline(BeforeAnalysisAccessImpl access, CallVariant variant, boolean nonVirtual) {
        MetaAccessProvider originalMetaAccess = access.getMetaAccess().getWrapped();
        ResolvedJavaField field = JNIAccessibleMethod.getCallVariantWrapperField(originalMetaAccess, variant, nonVirtual);
        access.getUniverse().lookup(field.getDeclaringClass()).registerAsReachable();
        access.registerAsAccessed(access.getUniverse().lookup(field));
        String name = JNIJavaCallTrampolines.getTrampolineName(variant, nonVirtual);
        Method method = ReflectionUtil.lookupMethod(JNIJavaCallTrampolines.class, name);
        access.registerAsRoot(method, true);
    }

    public JNICallTrampolineMethod getCallTrampolineMethod(CallVariant variant, boolean nonVirtual) {
        String name = JNIJavaCallTrampolines.getTrampolineName(variant, nonVirtual);
        return getCallTrampolineMethod(name);
    }

    public JNICallTrampolineMethod getCallTrampolineMethod(String trampolineName) {
        JNICallTrampolineMethod trampoline = trampolineMethods.get(trampolineName);
        assert trampoline != null;
        return trampoline;
    }

    public JNICallTrampolineMethod getOrCreateCallTrampolineMethod(MetaAccessProvider metaAccess, String trampolineName) {
        return trampolineMethods.computeIfAbsent(trampolineName, name -> {
            Method reflectionMethod = ReflectionUtil.lookupMethod(JNIJavaCallTrampolines.class, name);
            boolean nonVirtual = JNIJavaCallTrampolines.isNonVirtual(name);
            ResolvedJavaField field = JNIAccessibleMethod.getCallVariantWrapperField(metaAccess, JNIJavaCallTrampolines.getVariant(name), nonVirtual);
            ResolvedJavaMethod method = metaAccess.lookupJavaMethod(reflectionMethod);
            return new JNICallTrampolineMethod(method, field, nonVirtual);
        });
    }

    public JNINativeLinkage makeLinkage(String declaringClass, String name, String descriptor) {
        UserError.guarantee(!sealed,
                        "All linkages for JNI calls must be created before the analysis has completed.%nOffending class: %s name: %s descriptor: %s",
                        declaringClass, name, descriptor);

        assert declaringClass.startsWith("L") && declaringClass.endsWith(";") : declaringClass;
        JNINativeLinkage key = new JNINativeLinkage(declaringClass, name, descriptor);

        if (JNIAccessFeature.Options.PrintJNIMethods.getValue()) {
            System.out.println("Creating a new JNINativeLinkage: " + key.toString());
        }

        return nativeLinkages.computeIfAbsent(key, linkage -> {
            newLinkages.put(linkage, linkage);
            return linkage;
        });
    }

    private boolean wereElementsAdded() {
        return !(newClasses.isEmpty() && newMethods.isEmpty() && newFields.isEmpty() && newLinkages.isEmpty());
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess a) {
        getConditionalConfigurationRegistry().flushConditionalConfiguration(a);
        DuringAnalysisAccessImpl access = (DuringAnalysisAccessImpl) a;
        if (!wereElementsAdded()) {
            return;
        }

        for (Class<?> clazz : newClasses) {
            addClass(clazz, access);
        }
        newClasses.clear();

        for (Executable method : newMethods) {
            addMethod(method, access);
        }
        newMethods.clear();

        newFields.forEach((field, writable) -> {
            addField(field, writable, access);
        });
        newFields.clear();

        JNIReflectionDictionary.singleton().addLinkages(newLinkages);
        newLinkages.clear();

        access.requireAnalysisIteration();
    }

    private static JNIAccessibleClass addClass(Class<?> classObj, DuringAnalysisAccessImpl access) {
        if (SubstitutionReflectivityFilter.shouldExclude(classObj, access.getMetaAccess(), access.getUniverse())) {
            return null;
        }
        return JNIReflectionDictionary.singleton().addClassIfAbsent(classObj, c -> {
            AnalysisType analysisClass = access.getMetaAccess().lookupJavaType(classObj);
            if (analysisClass.isInterface() || (analysisClass.isInstanceClass() && analysisClass.isAbstract())) {
                analysisClass.registerAsReachable();
            } else {
                access.getBigBang().markTypeInstantiated(analysisClass);
            }
            return new JNIAccessibleClass(classObj);
        });
    }

    private void addMethod(Executable method, DuringAnalysisAccessImpl access) {
        if (SubstitutionReflectivityFilter.shouldExclude(method, access.getMetaAccess(), access.getUniverse())) {
            return;
        }
        JNIAccessibleClass jniClass = addClass(method.getDeclaringClass(), access);
        JNIAccessibleMethodDescriptor descriptor = JNIAccessibleMethodDescriptor.of(method);
        jniClass.addMethodIfAbsent(descriptor, d -> {
            AnalysisUniverse universe = access.getUniverse();
            MetaAccessProvider originalMetaAccess = universe.getOriginalMetaAccess();
            ResolvedJavaMethod targetMethod = originalMetaAccess.lookupJavaMethod(method);

            JNIJavaCallWrapperMethod.Factory factory = ImageSingletons.lookup(JNIJavaCallWrapperMethod.Factory.class);
            AnalysisMethod aTargetMethod = universe.lookup(targetMethod);
            if (!targetMethod.isConstructor() || factory.canInvokeConstructorOnObject(targetMethod, originalMetaAccess)) {
                access.registerAsRoot(aTargetMethod, false);
            } // else: function pointers will be an error stub

            ResolvedJavaMethod newObjectMethod = null;
            if (targetMethod.isConstructor() && !targetMethod.getDeclaringClass().isAbstract()) {
                var aFactoryMethod = (AnalysisMethod) FactoryMethodSupport.singleton().lookup(access.getMetaAccess(), aTargetMethod, false);
                access.registerAsRoot(aFactoryMethod, true);
                newObjectMethod = aFactoryMethod.getWrapped();
            }

            SimpleSignature compatibleSignature = JNIJavaCallWrapperMethod.getGeneralizedSignatureForTarget(targetMethod, originalMetaAccess);
            JNIJavaCallWrapperMethod callWrapperMethod = javaCallWrapperMethods.computeIfAbsent(compatibleSignature,
                            signature -> factory.create(signature, originalMetaAccess, access.getBigBang().getProviders().getWordTypes()));
            access.registerAsRoot(universe.lookup(callWrapperMethod), true);

            JNIJavaCallVariantWrapperGroup variantWrappers = createJavaCallVariantWrappers(access, callWrapperMethod.getSignature(), false);
            JNIJavaCallVariantWrapperGroup nonvirtualVariantWrappers = JNIJavaCallVariantWrapperGroup.NONE;
            if (!Modifier.isStatic(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers())) {
                nonvirtualVariantWrappers = createJavaCallVariantWrappers(access, callWrapperMethod.getSignature(), true);
            }
            return new JNIAccessibleMethod(d, jniClass, targetMethod, newObjectMethod, callWrapperMethod,
                            variantWrappers.varargs, variantWrappers.array, variantWrappers.valist,
                            nonvirtualVariantWrappers.varargs, nonvirtualVariantWrappers.array, nonvirtualVariantWrappers.valist);
        });
    }

    private JNIJavaCallVariantWrapperGroup createJavaCallVariantWrappers(DuringAnalysisAccessImpl access, SimpleSignature wrapperSignature, boolean nonVirtual) {
        var map = nonVirtual ? nonvirtualCallVariantWrappers : callVariantWrappers;
        return map.computeIfAbsent(wrapperSignature, signature -> {
            MetaAccessProvider originalMetaAccess = access.getUniverse().getOriginalMetaAccess();
            WordTypes wordTypes = access.getBigBang().getProviders().getWordTypes();
            var varargs = new JNIJavaCallVariantWrapperMethod(signature, CallVariant.VARARGS, nonVirtual, originalMetaAccess, wordTypes);
            var array = new JNIJavaCallVariantWrapperMethod(signature, CallVariant.ARRAY, nonVirtual, originalMetaAccess, wordTypes);
            var valist = new JNIJavaCallVariantWrapperMethod(signature, CallVariant.VA_LIST, nonVirtual, originalMetaAccess, wordTypes);
            Stream<JNIJavaCallVariantWrapperMethod> wrappers = Stream.of(varargs, array, valist);
            CEntryPointData unpublished = CEntryPointData.createCustomUnpublished();
            wrappers.forEach(wrapper -> {
                AnalysisMethod analysisWrapper = access.getUniverse().lookup(wrapper);
                access.getBigBang().addRootMethod(analysisWrapper, true);
                analysisWrapper.registerAsEntryPoint(unpublished); // ensures C calling convention
            });
            return new JNIJavaCallVariantWrapperGroup(varargs, array, valist);
        });
    }

    private static void addField(Field reflField, boolean writable, DuringAnalysisAccessImpl access) {
        access.getMetaAccess().lookupJavaType(reflField.getDeclaringClass()).registerAsReachable();
        if (SubstitutionReflectivityFilter.shouldExclude(reflField, access.getMetaAccess(), access.getUniverse())) {
            return;
        }
        JNIAccessibleClass jniClass = addClass(reflField.getDeclaringClass(), access);
        AnalysisField field = access.getMetaAccess().lookupJavaField(reflField);
        jniClass.addFieldIfAbsent(field.getName(), name -> new JNIAccessibleField(jniClass, name, field.getJavaKind(), field.getModifiers()));
        field.registerAsJNIAccessed();
        field.registerAsRead(null);
        if (writable) {
            field.registerAsWritten(null);
            AnalysisType fieldType = field.getType();
            if (fieldType.isArray() && !access.isReachable(fieldType)) {
                // For convenience, make the array type reachable if its elemental type becomes
                // such, allowing the array creation via JNI without an explicit reflection config.
                access.registerReachabilityHandler(a -> access.getBigBang().markTypeInstantiated(fieldType),
                                ((AnalysisType) fieldType.getElementalType()).getJavaClass());
            }
        } else if (field.isStatic() && field.isFinal()) {
            MaterializedConstantFields.singleton().register(field);
        }

        BigBang bb = access.getBigBang();
        bb.registerAsJNIAccessed(field, writable);
    }

    @Override
    @SuppressWarnings("unused")
    public void afterAnalysis(AfterAnalysisAccess access) {
        sealed = true;
        if (wereElementsAdded()) {
            abortIfSealed();
        }

        int numClasses = 0;
        int numFields = 0;
        int numMethods = 0;
        for (JNIAccessibleClass clazz : JNIReflectionDictionary.singleton().getClasses()) {
            numClasses++;
            for (JNIAccessibleField f : clazz.getFields()) {
                numFields++;
            }
            for (JNIAccessibleMethod m : clazz.getMethods()) {
                numMethods++;
            }
        }
        ProgressReporter.singleton().setJNIInfo(numClasses, numFields, numMethods);
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess a) {
        if (ImageSingletons.contains(FallbackFeature.class)) {
            FallbackFeature.FallbackImageRequest jniFallback = ImageSingletons.lookup(FallbackFeature.class).jniFallback;
            if (jniFallback != null && loadedConfigurations == 0) {
                throw jniFallback;
            }
        }

        CompilationAccessImpl access = (CompilationAccessImpl) a;
        for (JNIAccessibleClass clazz : JNIReflectionDictionary.singleton().getClasses()) {
            for (JNIAccessibleField field : clazz.getFields()) {
                field.finishBeforeCompilation(access);
            }
            for (JNIAccessibleMethod method : clazz.getMethods()) {
                method.finishBeforeCompilation(access);
                access.registerAsImmutable(method); // for constant address to use as identifier
            }
        }
    }
}
