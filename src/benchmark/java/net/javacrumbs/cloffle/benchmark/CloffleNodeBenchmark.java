package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import clojure.lang.RT;
import clojure.lang.Var;
import clojure.lang.Symbol;
import clojure.lang.Namespace;
import clojure.lang.AFn;

import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.FnMethodNode;
import net.javacrumbs.cloffle.nodes.invoke.InvokeNode;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;
import net.javacrumbs.cloffle.nodes.value.LongNode;
import net.javacrumbs.cloffle.nodes.RecurNode;
import net.javacrumbs.cloffle.nodes.vars.VarNode;
import net.javacrumbs.cloffle.nodes.vars.LocalNode;
import net.javacrumbs.cloffle.nodes.IfNode;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class CloffleNodeBenchmark {

    private VirtualFrame frame;
    private VirtualFrame recurFrame;

    private InvokeNode invokeStableNode;
    private InvokeNode invokeDynamicNode;
    private FnMethodNode fnMethodNode;
    private VarNode stableVarNode;
    private VarNode dynamicVarNode;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();

        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Object, Symbol.intern("dummy"), null);
        frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());

        setupInvokeNodes();
        setupFnMethodNode();
        setupVarNodes();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        Var.popThreadBindings();
    }

    private void setupInvokeNodes() {
        Namespace ns = Namespace.findOrCreate(Symbol.intern("benchmark"));

        Var vStable = Var.intern(ns, Symbol.intern("stable"), new AFn() {
            public Object invoke(Object arg) { return arg; }
        });

        VarNode varNodeStable = new VarNode(0, vStable);
        ClojureNode[] args = new ClojureNode[] { new LongNode(42) };
        invokeStableNode = new InvokeNode(varNodeStable, (FrameDescriptor)null, null, null, args);

        Var vDynamic = Var.intern(ns, Symbol.intern("dynamic"), new AFn() {
            public Object invoke(Object arg) { return "root"; }
        });
        vDynamic.setDynamic();

        Var.pushThreadBindings(RT.map(vDynamic, new AFn() {
            public Object invoke(Object arg) { return "dynamic"; }
        }));

        VarNode varNodeDynamic = new VarNode(0, vDynamic);
        invokeDynamicNode = new InvokeNode(varNodeDynamic, (FrameDescriptor)null, null, null, args);
    }

    private void setupFnMethodNode() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotI = builder.addSlot(FrameSlotKind.Object, Symbol.intern("i"), null);
        FrameDescriptor fd = builder.build();

        recurFrame = Truffle.getRuntime().createVirtualFrame(new Object[] { 1000L }, fd);

        BindingNode[] params = new BindingNode[] { new TestBindingNode(slotI) };

        ClojureNode iNode = new LocalNode(slotI);
        ClojureNode condition = new PosCheckNode(iNode);
        ClojureNode decI = new DecNode(new LocalNode(slotI));
        ClojureNode thenNode = new RecurNode(new ClojureNode[] { decI });
        ClojureNode elseNode = new LocalNode(slotI);
        ClojureNode body = new IfNode(condition, thenNode, elseNode);

        fnMethodNode = new FnMethodNode(params, body, 1, false);
    }

    static class TestBindingNode extends BindingNode {
        private final int slot;
        public TestBindingNode(int slot) { super(Symbol.intern("i")); this.slot = slot; }
        @Override protected int getSlot() { return slot; }

        @Override public Object executeGeneric(VirtualFrame frame) {
             Object val = frame.getArguments().length > 0 ? frame.getArguments()[0] : 1000L;
             frame.setObject(slot, val);
             return val;
        }

        @Override
        public void rebindValue(Object value, VirtualFrame frame) {
            frame.setObject(slot, value);
        }
    }

    static class PosCheckNode extends ClojureNode {
        @Child ClojureNode arg;
        public PosCheckNode(ClojureNode arg) { this.arg = arg; }
        @Override public Object executeGeneric(VirtualFrame frame) {
            long val = (long) arg.executeGeneric(frame);
            return val > 0;
        }
        @Override public boolean executeBoolean(VirtualFrame frame) {
            return (boolean) executeGeneric(frame);
        }
    }

    static class DecNode extends ClojureNode {
        @Child ClojureNode arg;
        public DecNode(ClojureNode arg) { this.arg = arg; }
        @Override public Object executeGeneric(VirtualFrame frame) {
            long val = (long) arg.executeGeneric(frame);
            return val - 1;
        }
    }

    @Benchmark
    public Object testInvokeStable() {
        return invokeStableNode.executeGeneric(frame);
    }

    @Benchmark
    public Object testInvokeDynamic() {
        return invokeDynamicNode.executeGeneric(frame);
    }

    @Benchmark
    public Object testRecur() {
        return fnMethodNode.executeGeneric(recurFrame);
    }

    @Benchmark
    public Object testVarReadStable() {
        return stableVarNode.executeGeneric(frame);
    }

    @Benchmark
    public Object testVarReadDynamic() {
        return dynamicVarNode.executeGeneric(frame);
    }

    private void setupVarNodes() {
         Namespace ns = Namespace.findOrCreate(Symbol.intern("benchmark-vars"));

         Var vStable = Var.intern(ns, Symbol.intern("stable-val"), "stable-value");
         stableVarNode = new VarNode(0, vStable);

         Var vDynamic = Var.intern(ns, Symbol.intern("dynamic-val"), "root-value");
         vDynamic.setDynamic();
         Var.pushThreadBindings(RT.map(vDynamic, "thread-value"));
         dynamicVarNode = new VarNode(0, vDynamic);
    }
}
