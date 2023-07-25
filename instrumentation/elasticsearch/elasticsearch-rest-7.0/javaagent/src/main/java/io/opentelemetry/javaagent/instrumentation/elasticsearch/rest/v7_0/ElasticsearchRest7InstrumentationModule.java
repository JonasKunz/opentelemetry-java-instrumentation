/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.elasticsearch.rest.v7_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class ElasticsearchRest7InstrumentationModule extends InstrumentationModule {
  public ElasticsearchRest7InstrumentationModule() {
    super("elasticsearch-rest", "elasticsearch-rest-7.0", "elasticsearch");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // class introduced in 7.0.0
    return hasClassesNamed("org.elasticsearch.client.RestClient$InternalRequest");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return singletonList(new RestClientInstrumentation());
  }

  @Override
  public boolean isGlobalStateClass(String className) {
    // We mark ElasticsearchEndpointMap as a global state class
    // this means that this class will not be copied by each instrumentation module classloader instance,
    // but will be placed in a shared parent classloader.
    // This is just done as an example, it would bee fine to copy ElasticsearchEndpointMap everytime, except for the unnecessary memory consumption.
    // Muzzle should take care that all classes references by GlobalState-classes are also marked as global state
    // (e.g. ElasticsearchEndpointDefinition in this case).
    return "io.opentelemetry.javaagent.instrumentation.elasticsearch.apiclient.ElasticsearchEndpointMap".equals(className);
  }
}
