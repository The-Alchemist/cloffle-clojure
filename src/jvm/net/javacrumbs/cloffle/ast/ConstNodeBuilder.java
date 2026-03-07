/**
 * Copyright 2009-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.RT;
import net.javacrumbs.cloffle.nodes.value.BooleanNode;
import net.javacrumbs.cloffle.nodes.value.DoubleNode;
import net.javacrumbs.cloffle.nodes.value.LongNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.value.ObjectNode;

import java.util.Map;

public class ConstNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword CONST = keyword("const");
    private static final Keyword KEYWORD = keyword("keyword");
    private static final Keyword VECTOR = keyword("vector");
    private static final Keyword MAP = keyword("map");
    private static final Keyword SET = keyword("set");
    private static final Keyword CHAR = keyword("char");
    private static final Keyword STRING = keyword("string");
    private static final Keyword NUMBER = keyword("number");
    private static final Keyword BOOL = keyword("bool");
    private static final Keyword REGEX = keyword("regex");
    private static final Keyword CLASS = keyword("class");

    protected ConstNodeBuilder(AstBuilder astBuilder) {
        super(CONST, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Object type = tree.get(TYPE);

        if (NIL.equals(type)) {
            return new NilNode();
        }

        Object val = tree.get(VAL);

        if (NUMBER.equals(type)) {
            if (val instanceof Long l) {
                return new LongNode(l);
            }
            if (val instanceof Double d) {
                return new DoubleNode(d);
            }
            return new ObjectNode(val);
        }
        if (BOOL.equals(type)) {
            return new BooleanNode((Boolean) val);
        }
        if (STRING.equals(type)) {
            return new ObjectNode(val);
        }
        if (CHAR.equals(type)) {
            return new ObjectNode(val);
        }

        if (KEYWORD.equals(type) || VECTOR.equals(type) || MAP.equals(type)
                || SET.equals(type) || REGEX.equals(type) || CLASS.equals(type)) {
            return new ObjectNode(val);
        }

        Class<?> tag = (Class<?>) tree.get(TAG);
        if (long.class.equals(tag) || Long.class.equals(tag)) {
            return new LongNode(RT.longCast(val));
        }
        if (double.class.equals(tag)) {
            return new DoubleNode(RT.doubleCast(val));
        }
        if (Boolean.class.equals(tag)) {
            return new BooleanNode((Boolean) val);
        }
        if (String.class.equals(tag)) {
            return new ObjectNode(val);
        }

        return new ObjectNode(val);
    }
}
