package net.javacrumbs.cloffle.benchmark.tuplepea;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

final class TuplePeaRootNode extends RootNode {

    private final String name;
    @Child private TuplePeaNodes.Expr body;

    TuplePeaRootNode(TuplePeaLanguage language, String name, TuplePeaNodes.Expr body) {
        super(language);
        this.name = name;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.execute(frame);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "TuplePeaRootNode[" + name + "]";
    }
}
