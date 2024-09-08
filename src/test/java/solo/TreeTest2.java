package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.Tree;
import utils.TestUtils;

import java.util.function.BinaryOperator;
import java.util.function.Consumer;

public class TreeTest2 {
    private static final String TAG = "TreeTest";

    private static final String
            A = "A",
                B = "B",
                    C = "C",
                D = "D",
                    E = "E",
                        F = "F";

    static class MyNode extends Tree.TypedStr.TypedNode<MyNode> {

        protected MyNode(
                String thisKey,
                Tree.Node<String, MyNode> parent, String nodeValue, Tree.SysForker<String, MyNode> forker, String[] template, BinaryOperator<String> operator) {
            super(
                    thisKey,
                    parent, nodeValue, forker, template, operator);
        }

        void print() { Print.green.print(TAG, "FROM NODE: " + result()); }
    }

    private static final Tree.TypedStr<MyNode> str = new Tree.TypedStr<>(A, "/", MyNode::new) {

        @Override
        protected void onCreate(MyNode root) {
            Print.yellow.print(TAG, "creation begins...");
            MyNode bNode = root.fork(B);
            bNode.append(C);
            MyNode dNode = root.fork(D);
            MyNode eNode = dNode.fork(E);
            eNode.append(F);
            Print.yellow.print(TAG, "creation finish...");
        }
    };

    public static void main(String[] args) {
        Consumer<String> printer = Print.purple::print;
        MyNode aNode = str.get(A);
        MyNode fNode = str.get(F);


        TestUtils.POSTPONE(
                1200,
                () -> {
                    Print.purple.print(TAG, "adding printer (A)...");
                    aNode.add(
                            printer
                    );
                    assert str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "Typed Node access...");
                    aNode.print();
                    assert str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "removing printer (A)...");
                    aNode.remove(
                            printer
                    );
                    assert !str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "adding printer (F)...");
                    fNode.add(
                            printer
                    );
                    assert str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "accepting value to D");
                    str.get(D).accept("D CHANGED!!!");
                },
                () -> {
                    Print.purple.print(TAG, "Typed Node E access...");
                    str.get(E).print();
                },
                () -> {
                    Print.purple.print(TAG, "accepting value to B");
                    str.get(B).accept("B CHANGED!!!");
                },
                () -> {
                    Print.purple.print(TAG, "accepting value to A");
                    str.get(A).accept("A CHANGED!!!");
                },
                () -> {
                    Print.purple.print(TAG, "removing printer (F)");
                    fNode.remove(printer);
                    assert !str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "Accepting B.2...");
                    str.get(B).accept("B CHANGED AGAIN!!!");
                    assert !str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "Accessing F");
                    str.get(F).print();
                    assert !str.isActive();
                },
                () -> {
                    Print.purple.print(TAG, "connecting printer (C)");
                    str.get(F).add(printer);
                    assert str.isActive();
                },
                () -> {
                  Print.purple.print(TAG, "Transaction...");
                  MyNode eNode = str.transaction(
                          new Tree.Entry<>(A, "LOL... A"),
                          new Tree.Entry<>(F, "LOL... F"),
                          new Tree.Entry<>(D, "LOL... D"),
                          new Tree.Entry<>(E, "LOL... E")
                  );

                  eNode.print();

                },
                () -> {
                    Print.purple.print(TAG, "removing printer (C)");
                    str.get(F).remove(printer);
                    assert !str.isActive();
                },
                TestUtils::FINISH
        );
    }
}