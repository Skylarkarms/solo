package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * In this Test 4 parallel Thread sources plus main (5 sources total), are delivering sequential changes(loops) EACH:
 * Thread 1 & Thread 2 => sequential loop... Main => sequential loop.
 * , that converge in a multiplication called:
 *
 * Path<Integer> res =
 *                 a.switchMap(iA ->
 *                         b.switchMap(iB ->
 *                                 c.switchMap(iC ->
 *                                         d.switchMap(iD ->
 *                                                 e.map(iE ->
 *                                                         iA * iB * iC * iD * iE
 *                                                 )))));
 *
 * */
public class JoinTestComapredToUpdate4 {
    private static final String TAG = "JoinTestComapredToUpdate";
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        Print.red.ln(TAG, "Commencing!!!...");

        retryFor(9);
    }

    static void retryFor(int tries) {
        assert tries != 0;
        UnaryOperator<Runnable> res = runnable -> () -> iteration(runnable);
        Runnable relay = res.apply(() -> iteration(TestUtils::FINISH));
        for (int i = 0; i < tries - 2; i++) {
            relay = res.apply(relay);
        }
        relay.run();
    }

    private static void iteration(Runnable next) {
        Print.Timer chrono = new Print.Timer(Print.yellow);
        In.Consume<Integer>
                a = new In.Consume<>(),
                b = new In.Consume<>(),
                c = new In.Consume<>(),
                d = new In.Consume<>();

        Path<Integer> res =
                a.switchMap(iA ->
                        b.switchMap(iB ->
                                c.switchMap(iC ->
                                        d.map(iD ->
                                                        iA * iB * iC * iD
                                                ))));

        Supplier<Integer> resSupplier = res.getCache();

        Consumer<Integer> intsConsumer = new Consumer<>() {
            @Override
            public void accept(Integer integer) {
                if (integer == 160000) {
                    chrono.elapsed();
                    Print.red.ln(TAG, "Removing this!!!!!");
                    res.remove(this);

                    TestUtils.POSTPONE(
                            300,
                            () -> {
                                assert !res.isActive();
                                assert !a.isActive() : "A = " + a.toStringDetailed();
                                assert !b.isActive() : "B = " + b.toStringDetailed();
                                assert !c.isActive() : "C = " + c.toStringDetailed();
                                assert !d.isActive() : "d = " + d.toStringDetailed();
                                assert resSupplier.get() == 160000;
                                next.run();
                            }
                    );
                }
            }
        };

        Print.blue.ln(TAG, "Adding observer...");
        res.add(intsConsumer);

        TestUtils.POSTPONE(
                800,
                () -> {
                    chrono.start();
                    TestUtils.BACK_LOOP(
                            20,
                            d::accept
                    );
                    TestUtils.BACK_LOOP(
                            20,
                            b::accept
                    );
                    TestUtils.BACK_LOOP(
                            20,
                            c::accept
                    );
                    TestUtils.FRONT_LOOP(
                            20,
                            a::accept
                    );
                }
        );
    }
}
