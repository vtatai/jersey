/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.model;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Type;
import java.util.List;

import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import javax.inject.Inject;

import org.glassfish.jersey.internal.ProcessingException;
import org.glassfish.jersey.server.spi.internal.ParameterValueHelper;
import org.glassfish.jersey.server.spi.internal.ResourceMethodDispatcher;

import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.ServiceLocator;

/**
 * An abstract implementation of {@link ResourceMethodDispatcher.Provider} that
 * creates instances of {@link ResourceMethodDispatcher}.
 * <p>
 * Implementing classes are required to override the {@link #createValueProviders(Invocable)}
 * method to return {@link Factory injection providers} associated with the parameters
 * of the abstract resource method.
 *
 * @author Paul Sandoz
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
abstract class AbstractJavaResourceMethodDispatcherProvider implements ResourceMethodDispatcher.Provider {

    @Inject
    private ServiceLocator serviceLocator;

    @Override
    public ResourceMethodDispatcher create(Invocable resourceMethod, InvocationHandler invocationHandler) {

        final List<Factory<?>> valueProviders = createValueProviders(resourceMethod);
        if (valueProviders == null) {
            return null;
        }

        if (valueProviders.contains(null)) {
            // Missing dependency
//
//            TODO: missing dependency error reporting
//
//            for (int i = 0; i < pp.getInjectables().size(); i++) {
//                if (pp.getInjectables().get(i) == null) {
//                    Errors.missingDependency(abstractResourceMethod.getMethod(), i);
//                }
//            }
            return null;
        }

        final Class<?> returnType = resourceMethod.getHandlingMethod().getReturnType();
        if (Response.class.isAssignableFrom(returnType)) {
            return new ResponseOutInvoker(resourceMethod, invocationHandler, valueProviders);
// TODO should we support JResponse?
//        } else if (JResponse.class.isAssignableFrom(returnType)) {
//            return new JResponseOutInvoker(resourceMethod, pp, invocationHandler);
        } else if (returnType != void.class) {
            if (returnType == Object.class || GenericEntity.class.isAssignableFrom(returnType)) {
                return new ObjectOutInvoker(resourceMethod, invocationHandler, valueProviders);
            } else {
                return new TypeOutInvoker(resourceMethod, invocationHandler, valueProviders);
            }
        } else {
            return new VoidOutInvoker(resourceMethod, invocationHandler, valueProviders);
        }
    }

    /**
     * Get the application-configured HK2 service locator.
     *
     * @return application-configured HK2 service locator.
     */
    final ServiceLocator getServiceLocator() {
        return serviceLocator;
    }

    /**
     * Get the injectable values provider for an abstract resource method.
     *
     * @param invocableResourceMethod the invocable resource method.
     * @return the injectable values provider, or null if no injectable values
     *         can be created for the parameters of the abstract
     *         resource method.
     */
    protected abstract List<Factory<?>> createValueProviders(Invocable invocableResourceMethod);

    private static abstract class AbstractMethodParamInvoker extends AbstractJavaResourceMethodDispatcher {

        private final List<Factory<?>> valueProviders;

        public AbstractMethodParamInvoker(
                Invocable resourceMethod,
                InvocationHandler handler,
                List<Factory<?>> valueProviders) {
            super(resourceMethod, handler);
            this.valueProviders = valueProviders;
        }

        final Object[] getParamValues() {
                return ParameterValueHelper.getParameterValues(valueProviders);
        }
    }

    private static final class VoidOutInvoker extends AbstractMethodParamInvoker {

        public VoidOutInvoker(
                Invocable resourceMethod,
                InvocationHandler handler,
                List<Factory<?>> valueProviders) {
            super(resourceMethod, handler, valueProviders);
        }

        @Override
        protected Response doDispatch(Object resource, Request request) throws ProcessingException {
            invoke(resource, getParamValues());
            return Response.noContent().build();
        }
    }

    private static final class ResponseOutInvoker extends AbstractMethodParamInvoker {

        public ResponseOutInvoker(
                Invocable resourceMethod,
                InvocationHandler handler,
                List<Factory<?>> valueProviders) {
            super(resourceMethod, handler, valueProviders);
        }

        @Override
        protected Response doDispatch(Object resource, Request request) throws ProcessingException {
            return Response.class.cast(invoke(resource, getParamValues()));
        }
    }

    private static final class ObjectOutInvoker extends AbstractMethodParamInvoker {

        public ObjectOutInvoker(
                Invocable resourceMethod,
                InvocationHandler handler,
                List<Factory<?>> valueProviders) {
            super(resourceMethod, handler, valueProviders);
        }

        @Override
        protected Response doDispatch(Object resource, Request request) throws ProcessingException {
            final Object o = invoke(resource, getParamValues());

            if (o instanceof Response) {
                return Response.class.cast(o);
//            } else if (o instanceof JResponse) {
//                context.getResponseContext().setResponse(((JResponse)o).toResponse());
            } else if (o != null) {
                return Response.ok().entity(o).build();
            } else {
                return Response.noContent().build();
            }
        }
    }

    private static final class TypeOutInvoker extends AbstractMethodParamInvoker {

        private final Type t;

        public TypeOutInvoker(
                Invocable resourceMethod,
                InvocationHandler handler,
                List<Factory<?>> valueProviders) {
            super(resourceMethod, handler, valueProviders);
            this.t = resourceMethod.getHandlingMethod().getGenericReturnType();
        }

        @Override
        protected Response doDispatch(Object resource, Request request) throws ProcessingException {
            final Object o = invoke(resource, getParamValues());
            if (o != null) {

                Response response = Response.ok().entity(o).build();
                // TODO set the method return Java type to the proper context.
//                Response r = new ResponseBuilderImpl().
//                        entityWithType(o, t).status(200).build();
                return response;
            } else {
                return Response.noContent().build();
            }
        }
    }
}
