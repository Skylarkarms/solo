package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UpdatableConcurrentTest {
    private static final String TAG = "UpdatableConcurrentTest";

    record IntegerObject(int i) {
        IntegerObject multiplyBy5() { return new IntegerObject(i * 5); }
        IntegerObject addToPrev(int toAdd) { return new IntegerObject(i + toAdd); }
    }

    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        In.Update<IntegerObject> integerUpdatable = new In.Update<>(new IntegerObject(0));
        Path<IntegerObject> mapped = integerUpdatable.map(
                IntegerObject::multiplyBy5
        );
        //final res = 1050
        Supplier<IntegerObject> mappedSupplier = mapped.getCache();
        Consumer<IntegerObject> obs = new Consumer<>() {
            @Override
            public void accept(IntegerObject anInt) {
                Print.purple.ln(anInt.i);
                if (anInt.i == 1050) {
                    Print.white.ln(TAG, "DONE!!!");
                    mapped.remove(this);
                    TestUtils.POSTPONE(
                            100,
                            TestUtils::FINISH
                    );
                }
            }
        };
        TestUtils.POSTPONE(
                1200,
                () -> {
                    Print.green.ln(TAG, " adding ... (print)");
                    mapped.add(obs);
                },
                () -> {
                    Print.green.ln(TAG, " concurrent update ... start");
                    AtomicInteger arrived = new AtomicInteger();
                    for (int i = 1; i < 21; i++) {
                        int finalI = i;
                        TestUtils.START(
                                () -> {
                                    Print.yellow.ln(TAG, " to compute = " + finalI);
                                    integerUpdatable.update(
                                            integerO -> integerO.addToPrev(finalI)
                                    );
                                    int arr = arrived.incrementAndGet();
                                    if (arr == 20) {
                                        Print.purple.ln(TAG,
                                                "FINISHED, final res = " +
                                                        ",\n final res = " + mappedSupplier.get()
                                        );
                                    }
                                }
                        );
                    }
                }
        );
    }
}
