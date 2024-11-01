package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Join;
import utils.TestUtils;

import java.util.Arrays;
import java.util.function.Consumer;

public class JoinTest3 {
    private static final String TAG = "JoinTest2";
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        iteration(
                TestUtils::FINISH

        );


    }

    private static void iteration(Runnable next) {
        Print.Timer chrono = new Print.Timer(Print.yellow);
        In.Consume<Integer>
                a = new In.Consume<>(),
                b = new In.Consume<>(In.Config.Consume.CONT()),
                c = new In.Consume<>(In.Config.Consume.CONT());

        Join.str str = new Join.str(
                a, b, c
        );

        //  a , b,  c
        // [17, 7, 12]
        Consumer<String[]> consumer = strings -> {
            Print.yellow.ln(TAG, "Strings are = " + Arrays.toString(strings));
        };

        Print.blue.ln(TAG, "Adding observer...");
        str.add(consumer);

        TestUtils.POSTPONE(
                1200,
                () -> {
                    chrono.start();
                    TestUtils.FRONT_LOOP(
                            7,
                            b::accept
                    );
                    TestUtils.FRONT_LOOP(
                            12,
                            c::accept
                    );
                    TestUtils.FRONT_LOOP(
                            17,
                            a::accept
                    );
                },
                () -> {
                    str.remove(consumer);
                    assert !str.isActive();
                    assert !a.isActive();
                    assert !b.isActive();
                    assert !c.isActive();
                },
                next
        );
    }
}
