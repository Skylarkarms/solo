package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.time.Duration;
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
public class JoinTestComapredToUpdate2 {
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
        Print.purple.ln(TAG, " val = " + val + " res = " + intRes);
        Print.Timer chrono = new Print.Timer(Print.yellow);
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
                    Print.red.ln(TAG, "Removing this!!!!!");
                    res.remove(this);
                    TestUtils.POSTPONE(
                            2000, //100 OK
//                            900, //100 OK
//                            1100, //100 OK
//                            1800, //100 OK
//                            1500, //100 OK
//                            500, //100 OK
                            () -> {
                                int asserts = 0;
                                try {
                                    int iRes = resSupplier.get();
                                    assert iRes == intRes : "Real res = " + iRes + " expected was = " + res;
                                    asserts++;
                                    assert !res.isActive();
                                    asserts++;
                                    assert !a.isActive() : "A = " + a.toStringDetailed();
                                    asserts++;
                                    assert !b.isActive() : "B = " + b.toStringDetailed();
                                    asserts++;
                                    assert !c.isActive() : "C = " + c.toStringDetailed();
                                    asserts++;
                                    assert !d.isActive() : "d = " + d.toStringDetailed();
                                    asserts++;
                                    assert !e.isActive() : "e = " + e.toStringDetailed();
                                    next.run();
                                } catch (Exception | Error ex) {
                                    int finalAsserts = asserts;
                                    TestUtils.POSTPONE(
                                            Duration.ofSeconds(5).toMillis()
                                            , () -> {
                                                throw new RuntimeException(
                                                        readMessage(
                                                                finalAsserts
                                                                , res, a, b, c, d, e
                                                        )
                                                        , ex);
                                            }
                                    );
//                                    throw new RuntimeException(ex);
                                }
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

    @SafeVarargs
    static String readMessage(
            int asserts,
            final Path<Integer>... res
    ) {
        String prev = switch (asserts) {
            case 0, 1 -> "Res";
            case 2 -> "A";
            case 3 -> "B";
            case 4 -> "C";
            case 5 -> "D";
            case 6 -> "E";
            default -> throw new IllegalStateException("Unexpected value: " + asserts);
        };
        return prev.concat(" = ").concat(
                res[
                        asserts == 0 ? 0 : asserts - 1
                        ].toStringDetailed()
                        .concat("\n >>>ENDS<<< \n")
        );
    }
}
