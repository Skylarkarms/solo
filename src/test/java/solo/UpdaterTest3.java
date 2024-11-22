package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import com.skylarkarms.solo.Publisher;
import utils.TestUtils;

import java.util.function.Consumer;

public class UpdaterTest3 {
    private static final String TAG = "UpdaterTest3";

    public static void main(String[] args) {
        TestUtils.setPrinterParams(false);
        ProfileLambdas.purpleTimeout(TAG,
                500
                );
        iteration(
                () -> iteration(
                        () -> iteration(
                                () -> iteration(
                                        () -> iteration(
                                                () -> iteration(
                                                        () -> iteration(
                                                                () -> iteration(
                                                                        () -> iteration(
                                                                                () ->
                                                                                        iteration(
                                                                                        () -> {
                                                                                            TestUtils.FINISH();

                                                                                        }
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static void iteration(Runnable next) {
        In.Update<Integer> integerUpdater = new In.Update<>(0);
        Path<Integer> mapped = integerUpdater.map(integer -> integer * 5);
        In.Consume<Integer> finalA = new In.Consume<>(7);

        Path<Integer> switched = finalA.switchMap(
                integer -> mapped.map(integer1 -> integer1 * integer
                )
        );

        Publisher<Integer> publisher = switched.getPublisher();

        Print.Timer chrono = new Print.Timer(Print.yellow);
        Consumer<Integer> obs = new Consumer<>() {
            @Override
            public void accept(Integer i) {
                if (i == 8330) {
                    chrono.elapsed();
                    publisher.remove(this);
                    assert !publisher.isActive();
                    assert !switched.isActive();
                    assert !finalA.isActive();
                    assert !mapped.isActive();
                    assert switched.getCache().get() == 8330;
                    next.run();
                }
            }
        };
        publisher.add(obs);

        assert publisher.isActive();

        TestUtils.POSTPONE(
                900,
                () -> {
                    chrono.start();
                    TestUtils.FRONT_LOOP( //res = res + 21 (98)
                            6,
                            i -> {
                                integerUpdater.update(
                                            integer -> integer + i
                                    );
                            }
                    );
                    TestUtils.BACK_LOOP( //res = res + 21 (77)
                            6,
                            i -> {
                                    integerUpdater.update(
                                            integer -> integer + i
                                    );
                            }
                    );
                    TestUtils.BACK_LOOP( //res = res + 21 (56)
                            6,
                            i -> {
                                    integerUpdater.update(
                                            integer -> integer + i
                                    );
                            }
                    );
                    TestUtils.BACK_LOOP(
                            17,
                            finalA::accept
                    );
                    TestUtils.BACK_LOOP( //res = res + 7 (35)
                            6,
                            i -> {
                                integerUpdater.update(
                                        integer -> integer + 1
                                );
                            }
                    );
                    TestUtils.BACK_LOOP( //res = res + 7 (28)
                            6,
                            i -> {
                                integerUpdater.update(
                                        integer -> integer + 1
                                );
                            }
                    );
                    TestUtils.FRONT_LOOP( //res == 21
                            6,
                            i -> {
                                integerUpdater.update(
                                        integer -> integer + i
                                );
                            }
                    );

                    //final res == 21
                },
                () -> {
                    Print.blue.ln(TAG, " " +
                            "\n Switched = " + switched +
                            "\n Publisher = " + publisher
                    );
                }
        );
    }
}
