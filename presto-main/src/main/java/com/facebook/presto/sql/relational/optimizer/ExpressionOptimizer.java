/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.relational.optimizer;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.FunctionInfo;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.sql.relational.CallExpression;
import com.facebook.presto.sql.relational.ConstantExpression;
import com.facebook.presto.sql.relational.InputReferenceExpression;
import com.facebook.presto.sql.relational.RowExpression;
import com.facebook.presto.sql.relational.RowExpressionVisitor;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.type.UnknownType;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.sql.relational.Expressions.constant;
import static com.facebook.presto.sql.relational.Expressions.constantNull;
import static com.facebook.presto.sql.relational.Signatures.CAST;
import static com.facebook.presto.sql.relational.Signatures.COALESCE;
import static com.facebook.presto.sql.relational.Signatures.IF;
import static com.facebook.presto.sql.relational.Signatures.IN;
import static com.facebook.presto.sql.relational.Signatures.IS_NULL;
import static com.facebook.presto.sql.relational.Signatures.NULL_IF;
import static com.facebook.presto.sql.relational.Signatures.SWITCH;
import static com.facebook.presto.sql.relational.Signatures.TRY_CAST;
import static com.google.common.base.Predicates.instanceOf;

public class ExpressionOptimizer
{
    private final FunctionRegistry registry;
    private final TypeManager typeManager;
    private final ConnectorSession session;

    public ExpressionOptimizer(FunctionRegistry registry, TypeManager typeManager, Session session)
    {
        this.registry = registry;
        this.typeManager = typeManager;
        this.session = session.toConnectorSession();
    }

    public RowExpression optimize(RowExpression expression)
    {
        return expression.accept(new Visitor(), null);
    }

    private class Visitor
            implements RowExpressionVisitor<Void, RowExpression>
    {
        @Override
        public RowExpression visitInputReference(InputReferenceExpression reference, Void context)
        {
            return reference;
        }

        @Override
        public RowExpression visitConstant(ConstantExpression literal, Void context)
        {
            return literal;
        }

        @Override
        public RowExpression visitCall(CallExpression call, final Void context)
        {
            FunctionInfo function;
            Signature signature = call.getSignature();

            if (signature.getName().equals(CAST)) {
                if (call.getArguments().get(0).getType().equals(UnknownType.UNKNOWN)) {
                    return constantNull(call.getType());
                }
                function = registry.getCoercion(call.getArguments().get(0).getType(), call.getType());
            }
            else {
                switch (signature.getName()) {
                    // TODO: optimize these special forms
                    case IF:
                    case NULL_IF:
                    case SWITCH:
                    case TRY_CAST:
                    case IS_NULL:
                    case "IS_DISTINCT_FROM":
                    case COALESCE:
                    case "AND":
                    case "OR":
                    case IN:
                        return call;
                    default:
                        function = registry.getExactFunction(signature);
                        if (function == null) {
                            // TODO: temporary hack to deal with magic timestamp literal functions which don't have an "exact" form and need to be "resolved"
                            function = registry.resolveFunction(QualifiedName.of(signature.getName()), signature.getArgumentTypes(), false);
                        }
                }
            }

            List<RowExpression> arguments = Lists.transform(call.getArguments(), new Function<RowExpression, RowExpression>()
            {
                @Override
                public RowExpression apply(RowExpression input)
                {
                    return input.accept(Visitor.this, context);
                }
            });

            if (Iterables.all(arguments, instanceOf(ConstantExpression.class)) && function.isDeterministic()) {
                MethodHandle method = function.getMethodHandle();

                if (method.type().parameterCount() > 0 && method.type().parameterType(0) == ConnectorSession.class) {
                    method = method.bindTo(session);
                }

                List<Object> constantArguments = new ArrayList<>();
                for (RowExpression argument : arguments) {
                    Object value = ((ConstantExpression) argument).getValue();
                    // if any argument is null, return null
                    if (value == null) {
                        return constantNull(call.getType());
                    }
                    constantArguments.add(value);
                }

                try {
                    return constant(method.invokeWithArguments(constantArguments), call.getType());
                }
                catch (Throwable e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw Throwables.propagate(e);
                }
            }

            return call(signature, typeManager.getType(signature.getReturnType()), arguments);
        }
    }
}
