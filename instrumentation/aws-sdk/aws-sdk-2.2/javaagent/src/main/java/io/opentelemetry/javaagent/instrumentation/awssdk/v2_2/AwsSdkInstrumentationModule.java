/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.awssdk.v2_2;

import com.google.auto.service.AutoService;
import io.opentelemetry.instrumentation.awssdk.v2_2.autoconfigure.TracingExecutionInterceptor;
import io.opentelemetry.javaagent.extension.instrumentation.HelperResourceBuilder;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.extension.instrumentation.injection.ClassInjector;
import io.opentelemetry.javaagent.extension.instrumentation.injection.InjectionMode;

@AutoService(InstrumentationModule.class)
public class AwsSdkInstrumentationModule extends AbstractAwsSdkInstrumentationModule {
  public AwsSdkInstrumentationModule() {
    super("aws-sdk-2.2-core");
  }

  @Override
  public boolean isIndyModule() {
    return true;
  }

  /**
   * Injects resource file with reference to our {@link TracingExecutionInterceptor} to allow SDK's
   * service loading mechanism to pick it up.
   */
  @Override
  public void registerHelperResources(HelperResourceBuilder helperResourceBuilder) {
    helperResourceBuilder.register("software/amazon/awssdk/global/handlers/execution.interceptors");
  }

  @Override
  public void injectClasses(ClassInjector injector) {
    //TODO: get rid of shading for indy modules to make this less confusing
    injector.proxyBuilder("io.opentelemetry.javaagent.shaded.instrumentation.awssdk.v2_2.autoconfigure.TracingExecutionInterceptor", "io.opentelemetry.javaagent.shaded.instrumentation.awssdk.v2_2.autoconfigure.TracingExecutionInterceptor")
        .inject(InjectionMode.CLASS_ONLY);
  }

  @Override
  void doTransform(TypeTransformer transformer) {
    // Nothing to transform, this type instrumentation is only used for injecting resources.
  }
}
