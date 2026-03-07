package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ReifyNode;
import net.javacrumbs.cloffle.nodes.ReifyNode.ReifyMethodDef;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReifyNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword REIFY = keyword("reify");
    private static final Keyword INTERFACES = keyword("interfaces");
    private static final Keyword METHODS = keyword("methods");
    private static final Keyword NAME = keyword("name");
    private static final Keyword PARAMS = keyword("params");
    private static final Keyword BODY = keyword("body");
    private static final Keyword THIS = keyword("this");

    protected ReifyNodeBuilder(AstBuilder astBuilder) {
        super(REIFY, astBuilder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Set<Class<?>> ifaceSet = (Set<Class<?>>) tree.get(INTERFACES);
        Class<?>[] interfaces = ifaceSet.toArray(new Class<?>[0]);

        List<Map<Keyword, Object>> methodMaps = (List<Map<Keyword, Object>>) tree.get(METHODS);
        ReifyMethodDef[] methodDefs = new ReifyMethodDef[methodMaps.size()];
        for (int i = 0; i < methodMaps.size(); i++) {
            methodDefs[i] = buildMethod(methodMaps.get(i));
        }

        return new ReifyNode(interfaces, methodDefs, getAstBuilder().getLanguage());
    }

    @SuppressWarnings("unchecked")
    private ReifyMethodDef buildMethod(Map<Keyword, Object> methodMap) {
        Symbol nameSym = (Symbol) methodMap.get(NAME);
        String name = nameSym.getName();

        Map<Keyword, Object> thisBinding = (Map<Keyword, Object>) methodMap.get(THIS);
        Object thisName = thisBinding.get(NAME);
        int thisSlot = getSlotIndex(thisName);

        List<Map<Keyword, Object>> paramMaps = (List<Map<Keyword, Object>>) methodMap.get(PARAMS);
        int[] paramSlots = new int[paramMaps.size()];
        for (int i = 0; i < paramMaps.size(); i++) {
            Object paramName = paramMaps.get(i).get(NAME);
            paramSlots[i] = getSlotIndex(paramName);
        }

        ClojureNode body = build(methodMap.get(BODY));

        return new ReifyMethodDef(name, thisSlot, paramSlots, body);
    }
}
