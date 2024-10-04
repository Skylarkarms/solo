package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class SwitchMapTest {
    private static final String TAG = "SwitchMapTest";

    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        In.Consume<Integer>
                A = new In.Consume<>(4),
                B = new In.Consume<>(3),
                C = new In.Consume<>(5);

        Path<Integer> res = A.switchMap(ia ->
                B.switchMap(ib ->
                        C.map(ic ->
                                {
                                    Print.green.print(TAG, "solving...");
                                    return ia * ib * ic;
                                }
                        )));
        Supplier<Integer> resSupplier = res.getCache();

        Consumer<Integer> obs = Print.purple::print;
        Print.yellow.print(TAG, "adding..., Res = 60 (print)");
        res.add(obs);

        TestUtils.POSTPONE(
                1300,
                () -> {
                    Print.yellow.print(TAG, "A = 10, Res = 150 (print)");
                    A.accept(10);
                    test(resSupplier, 150);
                },
                () -> {
                    Print.yellow.print(TAG, "removing...");
                    res.remove(obs);
                }
                ,() -> {
                    assert !res.isActive();
                    assert !A.isActive();
                    assert !B.isActive() : "B = " + B.toStringDetailed();
                    assert !C.isActive();
                }
                ,() -> {
                    Print.yellow.print(TAG, "A = 12");
                    A.accept(12);
                    test(resSupplier, 150);
                },
                () -> {
                    Print.yellow.print(TAG, "adding... (print)");
                    res.add(obs);
                    assert res.isActive();
                    test(resSupplier, 180);
                },
                () -> {
                    Print.yellow.print(TAG, "A = 14, R = 210 (print)");
                    A.accept(14);
                    test(resSupplier, 210);
                },
                () -> {
                    Print.yellow.print(TAG, "B = 7, R = 490 (print)");
                    B.accept(7);
                    test(resSupplier, 490);
                },
                () -> {
                    Print.yellow.print(TAG, "C = 9, R = 882 (print)");
                    C.accept(9);
                    test(resSupplier, 882);
                },
                () -> {
                    Print.yellow.print(TAG, "removing...");
                    res.remove(obs);
                    assert !res.isActive();
                    assert !A.isActive();
                    assert !B.isActive();
                    assert !C.isActive();
                },
                () -> {
                    Print.yellow.print(TAG, "B = 2, R = 882");
                    B.accept(2);
                    test(resSupplier, 882);
                },
                () -> {
                    Print.yellow.print(TAG, "C = 7, R = 882");
                    C.accept(7);
                    test(resSupplier, 882);
                },
                () -> {
                    Print.yellow.print(TAG, "adding... R = 196 (print)");
                    res.add(obs);
                    assert res.isActive();
                    assert A.isActive();
                    assert B.isActive();
                    assert C.isActive();
                    test(resSupplier, 196);
                },
                () -> {
                    Print.yellow.print(TAG, "C = 4, R = 112 (print)");
                    C.accept(4);
                    test(resSupplier, 112);
                },
                () -> {
                    Print.yellow.print(TAG, "removing...");
                    res.remove(obs);
                    assert !res.isActive();
                    assert !A.isActive();
                    assert !B.isActive();
                    assert !C.isActive();
                },
                () -> {
                    Print.yellow.print(TAG, "adding... (print)");
                    res.add(obs);
                    assert res.isActive();
                    assert A.isActive();
                    assert B.isActive();
                    assert C.isActive();
                },
                () -> {
                    Print.yellow.print(TAG, "removing...");
                    res.remove(obs);
                    assert !res.isActive();
                    assert !A.isActive();
                    assert !B.isActive();
                    assert !C.isActive();
                },
                TestUtils::FINISH
        );
    }

    private static void test(Supplier<Integer> resSupplier, int expect) {
        Integer aRes = resSupplier.get();
        assert aRes == expect : "Res is NOT = " + expect +
                "\n... BUT = " + aRes;
    }
}
