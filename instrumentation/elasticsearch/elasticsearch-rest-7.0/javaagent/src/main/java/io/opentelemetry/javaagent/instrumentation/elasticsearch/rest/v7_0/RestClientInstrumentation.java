/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.elasticsearch.rest.v7_0;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.instrumentation.elasticsearch.rest.v7_0.ElasticsearchRest7Singletons.instrumenter;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.elasticsearch.rest.ElasticsearchEndpointDefinition;
import io.opentelemetry.javaagent.instrumentation.elasticsearch.rest.ElasticsearchRestRequest;
import io.opentelemetry.javaagent.instrumentation.elasticsearch.rest.RestResponseListener;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;

public class RestClientInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.elasticsearch.client.RestClient");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyIndyAdviceToMethod(
        isMethod()
            .and(named("performRequest"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.elasticsearch.client.Request"))),
        this.getClass().getName() + "$PerformRequestAdvice");
    transformer.applyIndyAdviceToMethod(
        isMethod()
            .and(named("performRequestAsync"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.elasticsearch.client.Request")))
            .and(takesArgument(1, named("org.elasticsearch.client.ResponseListener"))),
        this.getClass().getName() + "$PerformRequestAsyncAdvice");
  }

  public static class RequestEndpointField {

    // Virtual fields can and should now be declared as static constants
    // There is no need to have the lookup in the Advice itself and to then rewrite it
    // Muzzle most likely needs to be adapted to discover the VirtualFields declared this way.
    public static final VirtualField<Request, ElasticsearchEndpointDefinition> FIELD =
        VirtualField.find(Request.class, ElasticsearchEndpointDefinition.class);
  }

  @SuppressWarnings("unused")
  public static class PerformRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static Object[] onEnter(@Advice.Argument(0) Request request) {

      Context parentContext = currentContext();
      ElasticsearchRestRequest otelRequest =
          ElasticsearchRestRequest.create(
              request.getMethod(),
              request.getEndpoint(),
              RequestEndpointField.FIELD.get(request),
              request.getEntity());
      if (!instrumenter().shouldStart(parentContext, otelRequest)) {
        return null;
      }

      Context context = instrumenter().start(parentContext, otelRequest);
      Scope scope = context.makeCurrent();
      return new Object[]{otelRequest, context, scope};
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class, inline = false)
    public static void stopSpan(
        @Advice.Return(readOnly = false) Response response,
        @Advice.Thrown Throwable throwable,
        @Advice.Enter Object[] enter) {

      if (enter == null) {
        return;
      }

      ElasticsearchRestRequest otelRequest = (ElasticsearchRestRequest) enter[0];
      Context context = (Context) enter[1];
      Scope scope = (Scope) enter[2];

      scope.close();

      instrumenter().end(context, otelRequest, response, throwable);
    }
  }

  @SuppressWarnings("unused")
  public static class PerformRequestAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    @Advice.AssignReturned.ToArguments(@Advice.AssignReturned.ToArguments.ToArgument(value = 1, index = 3, typing = DYNAMIC))
    public static Object[] onEnter(
        @Advice.Argument(0) Request request,
        @Advice.Argument(value = 1, readOnly = false) ResponseListener responseListener
    ) {

      Context parentContext = currentContext();

      ElasticsearchRestRequest otelRequest =
          ElasticsearchRestRequest.create(
              request.getMethod(),
              request.getEndpoint(),
              RequestEndpointField.FIELD.get(request),
              request.getEntity());
      if (!instrumenter().shouldStart(parentContext, otelRequest)) {
        return new Object[]{null, null, null, responseListener};
      }

      Context context = instrumenter().start(parentContext, otelRequest);
      Scope scope = context.makeCurrent();

      ResponseListener wrappedResponseListener =
          new RestResponseListener(
              responseListener, parentContext, instrumenter(), context, otelRequest);

      return new Object[]{otelRequest, context, scope, wrappedResponseListener};
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class, inline = false)
    public static void stopSpan(
        @Advice.Thrown Throwable throwable,
        @Advice.Enter Object[] enter) {

      ElasticsearchRestRequest otelRequest = (ElasticsearchRestRequest) enter[0];
      Context context = (Context) enter[1];
      Scope scope = (Scope) enter[2];
      if (scope == null) {
        return;
      }
      scope.close();

      if (throwable != null) {
        instrumenter().end(context, otelRequest, null, throwable);
      }
      // span ended in RestResponseListener
    }
  }
}
