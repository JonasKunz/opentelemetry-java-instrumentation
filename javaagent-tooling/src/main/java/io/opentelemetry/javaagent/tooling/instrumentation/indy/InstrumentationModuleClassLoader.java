package io.opentelemetry.javaagent.tooling.instrumentation.indy;

import net.bytebuddy.dynamic.ClassFileLocator;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class InstrumentationModuleClassLoader extends ClassLoader {

  //TODO: This CL should also support looking up the correct .class resources based on the same delegation model

  private static final Map<String, byte[]> ALWAYS_INJECTED_CLASSES;
  static {
    Map<String, byte[]> alwaysInjected = new HashMap<>();
    alwaysInjected.put(LookupExposer.class.getName(), ClassFileLocator.ForClassLoader.read(LookupExposer.class));
    ALWAYS_INJECTED_CLASSES = Collections.unmodifiableMap(alwaysInjected);
  }

  private final Map<String, Supplier<byte[]>> additionalInjectedClasses;

  private final ClassLoader agentCl;
  private final ClassLoader instrumentedCl;

  public InstrumentationModuleClassLoader(ClassLoader instrumentedCl, ClassLoader agentCl, Map<String, Supplier<byte[]>> injectedClasses) {
    additionalInjectedClasses = injectedClasses;
    this.agentCl = agentCl;
    this.instrumentedCl = instrumentedCl;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock( name)) {
      Class<?> result = findLoadedClass(name);

      // This CL is self-first: Injected class are loaded BEFORE a parent lookup
      if(result == null) {
        byte[] injected = getInjectedClassBytecode(name);
        if (injected != null) {
           result = defineClass(name, injected, 0, injected.length);
        }
      }

      //TODO: swap order for JUnit tests as the "agent-classloader" (=JUnit CL) may also contain the instrumented lib
      //At least for elastic apm agent this was required
      if(result == null) {
        result = tryLoad(agentCl, name);
      }
      if(result == null) {
        result = tryLoad(instrumentedCl, name);
      }


      if (result != null) {
        if(resolve) {
          resolveClass(result);
        }
        return result;
      } else {
        throw new ClassNotFoundException(name);
      }
    }
  }

  private static Class<?> tryLoad(ClassLoader cl, String name) {
    try {
      return cl.loadClass(name);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }


  private byte[] getInjectedClassBytecode(String name) throws ClassNotFoundException {
    if(ALWAYS_INJECTED_CLASSES.containsKey(name)) {
      return ALWAYS_INJECTED_CLASSES.get(name);
    }
    Supplier<byte[]> byteCodeSupplier = additionalInjectedClasses.get(name);
    if(byteCodeSupplier != null) {
      try {
        return byteCodeSupplier.get();
      } catch (RuntimeException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
    return null;
  }

  public MethodHandles.Lookup getLookup() {
    //Load the injected copy of LookupExposer and invoke it
    try {
      Class<?> lookupExposer = loadClass(LookupExposer.class.getName());
      return (MethodHandles.Lookup) lookupExposer.getMethod("getLookup").invoke(null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
