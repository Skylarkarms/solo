package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
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
public class JoinTestComapredToUpdate {
    private static final String TAG = "JoinTestComapredToUpdate";
    private static final int
            MAX = 20,
            MIN = 15;
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        Random r = new Random();
        int abs = MAX - MIN + 1;
        IntSupplier supplier = () -> r.nextInt(abs) + MIN;
        retryFor(100, supplier);
    }

    static void retryFor(int tries, IntSupplier generated) {
        assert tries != 0;
        UnaryOperator<Runnable> res = runnable -> () -> iteration(runnable, generated.getAsInt());
        Runnable relay = res.apply(() -> iteration(TestUtils::FINISH, generated.getAsInt()));
        for (int i = 0; i < tries - 2; i++) {
            relay = res.apply(relay);
        }
        relay.run();
    }

    private static void iteration(Runnable next, int val) {
        int intRes = (int) Math.pow(val, 5);
        Print.purple.print(TAG, " val = " + val + " res = " + intRes);
        Print.Chrono chrono = new Print.Chrono(Print.yellow);
        In.Consume<Integer>
                a = new In.Consume<>(),
                b = new In.Consume<>(),
                c = new In.Consume<>(),
                d = new In.Consume<>(),
                e = new In.Consume<>();

        Path<Integer> res =
                a.switchMap(iA ->
                        b.switchMap(iB ->
                                c.switchMap(iC ->
                                        d.switchMap(iD ->
                                                e.map(iE ->
                                                        iA * iB * iC * iD * iE
                                                )))));

        Supplier<Integer> resSupplier = res.getCache();

        Consumer<Integer> intsConsumer = new Consumer<>() {
            @Override
            public void accept(Integer integer) {
                if (integer == intRes) {
//                if (integer == 3200000) {
                    chrono.elapsed();
                    Print.red.print(TAG, "Removing this!!!!!");
                    res.remove(this);
                    TestUtils.POSTPONE(
                            1600, //100 OK
//                            900, //100 OK
//                            1100, //100 OK
//                            1800, //100 OK
//                            1500, //100 OK
//                            500, //100 OK
                            () -> {
                                try {
                                    int iRes = resSupplier.get();
                                    assert iRes == intRes : "Real res = " + iRes + " expected was = " + res;
//                                    assert iRes == 3200000 : "Real res = " + iRes;

                                    assert !res.isActive();
                                    assert !a.isActive() : "A = " + a.toStringDetailed();
                                    assert !b.isActive() : "B = " + b.toStringDetailed();
                                    assert !c.isActive() : "C = " + c.toStringDetailed();
                                    assert !d.isActive() : "d = " + d.toStringDetailed();
                                    assert !e.isActive() : "e = " + e.toStringDetailed();
                                    next.run();
                                } catch (Exception | Error ex) {
                                    throw new RuntimeException(ex);
                                }
                            }
                    );
                }
            }
        };

        Print.blue.print(TAG, "Adding observer...");
        res.add(intsConsumer);

        TestUtils.POSTPONE(
                800,
                () -> {
                    chrono.start();
                    TestUtils.BACK_LOOP(
                            val,
//                            20,
                            d::accept
                    );
                    TestUtils.BACK_LOOP(
                            val,
                            e::accept
                    );
                    TestUtils.BACK_LOOP(
                            val,
                            b::accept
                    );
                    TestUtils.BACK_LOOP(
                            val,
                            c::accept
                    );
                    TestUtils.FRONT_LOOP(
                            val,
                            a::accept
                    );
                }
        );
    }
}
