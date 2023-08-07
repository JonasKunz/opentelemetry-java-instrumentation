package io.opentelemetry.javaagent.tooling.instrumentation.indy;

import io.opentelemetry.instrumentation.api.internal.cache.Cache;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.tooling.Utils;
import io.opentelemetry.javaagent.tooling.muzzle.InstrumentationModuleMuzzle;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher;

public class IndyModuleRegistry {

  private final ConcurrentHashMap<String, InstrumentationModule> adviceNameToModule = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<InstrumentationModule, Cache<ClassLoader, WeakReference<InstrumentationModuleClassLoader>>> instrumentationClassloaders = new ConcurrentHashMap<>();

  public InstrumentationModuleClassLoader getInstrumentationClassloader(String adviceClassName, ClassLoader instrumentedClassloader) {
    InstrumentationModule instrumentationModule = adviceNameToModule.get(adviceClassName);
    if(instrumentationModule == null) {
      throw new IllegalArgumentException("AdviceClass "+adviceClassName+" does not belong to any registered module!");
    }
    return getInstrumentationClassloader(instrumentationModule, instrumentedClassloader);
  }

  private synchronized InstrumentationModuleClassLoader getInstrumentationClassloader(InstrumentationModule module, ClassLoader instrumentedClassloader) {

    Cache<ClassLoader, WeakReference<InstrumentationModuleClassLoader>> cacheForModule = instrumentationClassloaders.computeIfAbsent(module, (k) -> Cache.weak());

    WeakReference<InstrumentationModuleClassLoader> cached = cacheForModule.get(instrumentedClassloader);
    if (cached != null) {
      //cached.get() is guaranteed to be non-null because the instrumedClassloader strongly references the InstrumentaitonModuleClassloader through Indy-CallSites
      return cached.get();
    }
    //We can't directly use "compute-if-absent" here because then for a short time only the WeakReference will point to the InstrumentationModuleCL
    InstrumentationModuleClassLoader created = createInstrumentationModuleClassloader(module, instrumentedClassloader);
    cacheForModule.put(instrumentedClassloader, new WeakReference<>(created));
    return created;
  }

  private static InstrumentationModuleClassLoader createInstrumentationModuleClassloader(
      InstrumentationModule module, ClassLoader instrumentedClassloader) {

    Set<String> toInject = new HashSet<>(InstrumentationModuleMuzzle.getHelperClassNames(module));
    //advices are not listed (yet) as helper classes
    toInject.addAll(getModuleAdviceNames(module));

    ClassFileLocator classFileLocator = ClassFileLocator.ForClassLoader.of(module.getClass().getClassLoader());
    Map<String, Supplier<byte[]>> injectedClasses = toInject.stream()
        .collect(Collectors.toMap(
            name -> name,
            name -> () -> {
              try {
                return classFileLocator.locate(name).resolve();
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            }
        ));

    return new InstrumentationModuleClassLoader(instrumentedClassloader, Utils.getAgentClassLoader(), injectedClasses);
  }

  public void registerIndyModule(InstrumentationModule module) {
    if(!module.isIndyModule()) {
      throw new IllegalArgumentException("Provided module is not an indy module!");
    }
    Set<String> adviceClassNames = getModuleAdviceNames(module);
    Optional<String> conflict = adviceClassNames.stream()
        .filter(adviceNameToModule::containsKey)
        .findFirst();
    if(conflict.isPresent()) {
      throw new IllegalArgumentException("Advice "+conflict+" is already registered!");
    }
    for(String advice : adviceClassNames) {
      adviceNameToModule.put(advice, module);
    }
  }


  private static Set<String> getModuleAdviceNames(InstrumentationModule module) {
    Set<String> adviceNames = new HashSet<>();
    TypeTransformer nameCollector = new TypeTransformer() {
      @Override
      public void applyAdviceToMethod(ElementMatcher<? super MethodDescription> methodMatcher,
          String adviceClassName) {
        adviceNames.add(adviceClassName);
      }

      @Override
      public void applyTransformer(AgentBuilder.Transformer transformer) {}
    };
    for(TypeInstrumentation instr : module.typeInstrumentations()) {
      instr.transform(nameCollector);
    }
    return adviceNames;
  }
}
