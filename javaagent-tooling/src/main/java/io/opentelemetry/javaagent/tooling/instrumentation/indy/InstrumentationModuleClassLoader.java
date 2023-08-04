package io.opentelemetry.javaagent.tooling.instrumentation.indy;

import java.lang.invoke.MethodHandles;

public class InstrumentationModuleClassLoader extends ClassLoader {

  /**
   * @return a Lookup constructed within this classloader.
   */
  public MethodHandles.Lookup getLookup() {

  }
}
