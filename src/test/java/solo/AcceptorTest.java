package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Publisher;
import utils.TestUtils;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class AcceptorTest {
    private static final String TAG = "AcceptorTest";
    static {
        TestUtils.setPrinterParams();
    }
    public static void main(String[] args) {
        In.Consume<Integer> integerSource = new In.Consume<>(In.Config.Consume.BACK());
        Supplier<Integer> cache = integerSource.getCache();
        Publisher<Integer> publisher = integerSource.getPublisher(true);

        integerSource.consume(3);

        Print.Timer chrono = new Print.Timer(Print.cyan);
        Consumer<Integer> observer = x -> {
            System.err.println(x);
            if (x == 10000) {
                chrono.elapsed();
            }
        };

        publisher.add(
                observer
        );

        chrono.start();
        TestUtils.FRONT_LOOP(
                10000,
                integerSource::consume
        );

        TestUtils.POSTPONE(
                3000,
                () -> {
                    publisher.remove(observer);
                    assert !publisher.isActive();
                    assert !integerSource.isActive();
                    System.err.println(" " +
                            "\n Postponed!!!... " + cache +
                            ",\n publisher = " + publisher
                    );
                    TestUtils.FINISH();
                }
        );
    }
}
