/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.instrumentation.indy;

import io.opentelemetry.javaagent.bootstrap.CallDepth;
import io.opentelemetry.javaagent.bootstrap.IndyBootstrapDispatcher;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.method.ParameterList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaConstant;
import org.jetbrains.annotations.NotNull;

/**
 * We instruct Byte Buddy (via {@link Advice.WithCustomMapping#bootstrap(java.lang.reflect.Method)})
 * to dispatch {@linkplain Advice.OnMethodEnter#inline() non-inlined advices} via an invokedynamic
 * (indy) instruction. The target method is linked to a dynamically created instrumentation module
 * class loader that is specific to an instrumentation module and the class loader of the
 * instrumented method.
 *
 * <p>The first invocation of an {@code INVOKEDYNAMIC} causes the JVM to dynamically link a {@link
 * CallSite}. In this case, it will use the {@link #bootstrap} method to do that. This will also
 * create the {@link InstrumentationModuleClassLoader}.
 *
 * <pre>
 *
 *   Bootstrap CL ←──────────────────────────── Agent CL
 *       ↑ └───────── IndyBootstrapDispatcher ─ ↑ ──→ └────────────── {@link IndyBootstrap#bootstrap}
 *     Ext/Platform CL               ↑          │                        ╷
 *       ↑                           ╷          │                        ↓
 *     System CL                     ╷          │        {@link IndyModuleRegistry#getInstrumentationClassLoader(String, ClassLoader)}
 *       ↑                           ╷          │                        ╷
 *     Common               linking of CallSite │                        ╷
 *     ↑    ↑             (on first invocation) │                        ╷
 * WebApp1  WebApp2                  ╷          │                     creates
 *          ↑ - InstrumentedClass    ╷          │                        ╷
 *          │                ╷       ╷          │                        ╷
 *          │                INVOKEDYNAMIC      │                        ↓
 *          └────────────────┼──────────────────{@link InstrumentationModuleClassLoader}
 *                           └╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶→├ AdviceClass
 *                                                  ├ AdviceHelper
 *                                                  └ {@link LookupExposer}
 *
 * Legend:
 *  ╶╶→ method calls
 *  ──→ class loader parent/child relationships
 * </pre>
 */
public class IndyBootstrap {

  private static final Logger logger = Logger.getLogger(IndyBootstrap.class.getName());

  private static final Method indyBootstrapMethod;

  private static final String BOOTSTRAP_KIND_ADVICE = "advice";
  private static final String BOOTSTRAP_KIND_PROXY = "proxy";

  private static final String PROXY_KIND_STATIC = "static";
  private static final String PROXY_KIND_CONSTRUCTOR = "constructor";
  private static final String PROXY_KIND_VIRTUAL = "virtual";

  static {
    try {
      indyBootstrapMethod =
          IndyBootstrapDispatcher.class.getMethod(
              "bootstrap",
              MethodHandles.Lookup.class,
              String.class,
              MethodType.class,
              Object[].class);

      MethodType bootstrapMethodType =
          MethodType.methodType(
              ConstantCallSite.class,
              MethodHandles.Lookup.class,
              String.class,
              MethodType.class,
              Object[].class);

      IndyBootstrapDispatcher.init(
          MethodHandles.lookup().findStatic(IndyBootstrap.class, "bootstrap", bootstrapMethodType));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private IndyBootstrap() {}

  public static Method getIndyBootstrapMethod() {
    return indyBootstrapMethod;
  }

  @Nullable
  @SuppressWarnings({"unused", "removal"}) // SecurityManager and AccessController are deprecated
  private static ConstantCallSite bootstrap(
      MethodHandles.Lookup lookup,
      String adviceMethodName,
      MethodType adviceMethodType,
      Object[] args) {

    if (System.getSecurityManager() == null) {
      return internalBootstrap(lookup, adviceMethodName, adviceMethodType, args);
    }

    // callsite resolution needs privileged access to call Class#getClassLoader() and
    // MethodHandles$Lookup#findStatic
    return java.security.AccessController.doPrivileged(
        (PrivilegedAction<ConstantCallSite>)
            () -> internalBootstrap(lookup, adviceMethodName, adviceMethodType, args));
  }

  private static ConstantCallSite internalBootstrap(
      MethodHandles.Lookup lookup,
      String adviceMethodName,
      MethodType adviceMethodType,
      Object[] args) {
    try {
      String kind = (String) args[0];
      switch (kind) {
        case BOOTSTRAP_KIND_ADVICE:
          // See the getAdviceBootstrapArguments method for the argument definitions
          return bootstrapAdvice(
              lookup,
              adviceMethodName,
              adviceMethodType,
              (String) args[1],
              (String) args[2],
              (String) args[3]);
        case BOOTSTRAP_KIND_PROXY:
          // See getProxyFactory for the argument definitions
          return bootstrapProxyMethod(
              lookup,
              adviceMethodName,
              adviceMethodType,
              (String) args[1],
              (String) args[2],
              (String) args[3]);
        default:
          throw new IllegalArgumentException("Unknown bootstrapping kind: " + kind);
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, e.getMessage(), e);
      return null;
    }
  }

  private static ConstantCallSite bootstrapAdvice(
      MethodHandles.Lookup lookup,
      String adviceMethodName,
      MethodType invokedynamicMethodType,
      String moduleClassName,
      String adviceMethodDescriptor,
      String adviceClassName)
      throws NoSuchMethodException, IllegalAccessException, ClassNotFoundException {
    CallDepth callDepth = CallDepth.forClass(IndyBootstrap.class);
    try {
      if (callDepth.getAndIncrement() > 0) {
        // avoid re-entrancy and stack overflow errors, which may happen when bootstrapping an
        // instrumentation that also gets triggered during the bootstrap
        // for example, adding correlation ids to the thread context when executing logger.debug.
        logger.log(
            Level.WARNING,
            "Nested instrumented invokedynamic instruction linkage detected",
            new Throwable());
        return null;
      }

      InstrumentationModuleClassLoader instrumentationClassloader =
          IndyModuleRegistry.getInstrumentationClassLoader(
              moduleClassName, lookup.lookupClass().getClassLoader());

      // Advices are not inlined. They are loaded as normal classes by the
      // InstrumentationModuleClassloader and invoked via a method call from the instrumented method
      Class<?> adviceClass = instrumentationClassloader.loadClass(adviceClassName);
      MethodType actualAdviceMethodType =
          MethodType.fromMethodDescriptorString(adviceMethodDescriptor, instrumentationClassloader);

      MethodHandle methodHandle =
          instrumentationClassloader
              .getLookup()
              .findStatic(adviceClass, adviceMethodName, actualAdviceMethodType)
              .asType(invokedynamicMethodType);
      return new ConstantCallSite(methodHandle);
    } finally {
      callDepth.decrementAndGet();
    }
  }

  static Advice.BootstrapArgumentResolver.Factory getAdviceBootstrapArguments(
      InstrumentationModule instrumentationModule) {
    String moduleName = instrumentationModule.getClass().getName();
    return new Advice.BootstrapArgumentResolver.Factory() {
      @Override
      public Advice.BootstrapArgumentResolver resolve(MethodDescription.InDefinedShape adviceMethod, boolean isExit) {
        return (instrumentedType, instrumentedMethod) ->
            Arrays.asList(
                JavaConstant.Simple.ofLoaded(BOOTSTRAP_KIND_ADVICE),
                JavaConstant.Simple.ofLoaded(moduleName),
                JavaConstant.Simple.ofLoaded(adviceMethod.getDescriptor()),
                JavaConstant.Simple.ofLoaded(adviceMethod.getDeclaringType().getName()));
      }

      @Override
      public MethodDescription.InDefinedShape override(MethodDescription.InDefinedShape original) {
        return eraseTypes(original);
      }
    };
  }

  @NotNull
  static MethodDescription.Latent eraseTypes(MethodDescription.InDefinedShape original) {
    ParameterList<ParameterDescription.InDefinedShape> parameters = original.getParameters();
    return new MethodDescription.Latent(
        original.getDeclaringType(),
        original.getInternalName(),
        original.getModifiers(),
        original.getTypeVariables().asTokenList(ElementMatchers.any()),
        eraseType(original.getReturnType()),
        eraseTypes(parameters),
        original.getExceptionTypes(),
        original.getDeclaredAnnotations(),
        original.getDefaultValue(),
        original.getReceiverType());
  }

  private static List<? extends ParameterDescription.Token> eraseTypes(List<? extends ParameterDescription> tokenList) {
    return tokenList.stream()
        .map(param -> new ParameterDescription.Token(eraseType(param.getType()), param.getDeclaredAnnotations(), param.getName(), param.getModifiers()))
        .collect(Collectors.toList());
  }

  private static TypeDescription.Generic eraseType(TypeDescription.Generic type) {
    if (type.asErasure().isPrimitive()) {
      return type;
    }
    if (type.isArray()) {
      //TODO: Erase array component types
      return type;
    }
    String name = type.asErasure().getName();
    if (name.startsWith("java.")) {
      return type;
    }
    return TypeDescription.ForLoadedType.of(Object.class).asGenericType();
  }

  private static ConstantCallSite bootstrapProxyMethod(
      MethodHandles.Lookup lookup,
      String proxyMethodName,
      MethodType expectedMethodType,
      String moduleClassName,
      String proxyClassName,
      String methodKind)
      throws NoSuchMethodException, IllegalAccessException, ClassNotFoundException {
    InstrumentationModuleClassLoader instrumentationClassloader =
        IndyModuleRegistry.getInstrumentationClassLoader(
            moduleClassName, lookup.lookupClass().getClassLoader());

    Class<?> proxiedClass = instrumentationClassloader.loadClass(proxyClassName);

    MethodHandle target;
    switch (methodKind) {
      case PROXY_KIND_STATIC:
        target =
            MethodHandles.publicLookup()
                .findStatic(proxiedClass, proxyMethodName, expectedMethodType);
        break;
      case PROXY_KIND_CONSTRUCTOR:
        target =
            MethodHandles.publicLookup()
                .findConstructor(proxiedClass, expectedMethodType.changeReturnType(void.class))
                .asType(expectedMethodType); // return type is the proxied class, but proxies expect
        // Object
        break;
      case PROXY_KIND_VIRTUAL:
        target =
            MethodHandles.publicLookup()
                .findVirtual(
                    proxiedClass, proxyMethodName, expectedMethodType.dropParameterTypes(0, 1))
                .asType(
                    expectedMethodType); // first argument type is the proxied class, but proxies
        // expect Object
        break;
      default:
        throw new IllegalStateException("unknown proxy method kind: " + methodKind);
    }
    return new ConstantCallSite(target);
  }

  /**
   * Creates a proxy factory for generating proxies for classes which are loaded by an {@link
   * InstrumentationModuleClassLoader} for the provided {@link InstrumentationModule}.
   *
   * @param instrumentationModule the instrumentation module used to load the proxied target classes
   * @return a factory for generating proxy classes
   */
  public static IndyProxyFactory getProxyFactory(InstrumentationModule instrumentationModule) {
    String moduleName = instrumentationModule.getClass().getName();
    return new IndyProxyFactory(
        getIndyBootstrapMethod(),
        (proxiedType, proxiedMethod) -> {
          String methodKind;
          if (proxiedMethod.isConstructor()) {
            methodKind = PROXY_KIND_CONSTRUCTOR;
          } else if (proxiedMethod.isMethod()) {
            if (proxiedMethod.isStatic()) {
              methodKind = PROXY_KIND_STATIC;
            } else {
              methodKind = PROXY_KIND_VIRTUAL;
            }
          } else {
            throw new IllegalArgumentException(
                "Unknown type of method: " + proxiedMethod.getName());
          }

          return Arrays.asList(
              JavaConstant.Simple.ofLoaded(BOOTSTRAP_KIND_PROXY),
              JavaConstant.Simple.ofLoaded(moduleName),
              JavaConstant.Simple.ofLoaded(proxiedType.getName()),
              JavaConstant.Simple.ofLoaded(methodKind));
        });
  }
}
