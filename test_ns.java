import clojure.lang.*;
public class test_ns {
    public static void main(String[] args) throws Exception {
        RT.init();
        System.out.println("CURRENT_NS: " + RT.CURRENT_NS.deref());
    }
}
