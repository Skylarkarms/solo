package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import com.skylarkarms.solo.Publisher;
import com.skylarkarms.solo.Settings;
import utils.TestUtils;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PathTest5 {
    private static final String TAG = "PathTest5";
    static {
        Print.setAutoFlush(true);
    }
    public static void main(String[] args) {
        Executor e = Settings.getWork_executor();
        Print.purple.ln(e.toString());
        Print.green.ln("work procs = " + Settings.avail_percent +
                ",\n exit procs = " + Settings.exit_cores);
        In.Consume<Integer> integerSource = new In.Consume<>(3, In.Config.Consume.CONT());

        Path<Integer> x2 = integerSource.map(integer -> integer * 2);
        Path<Integer> x3 = integerSource.map(integer -> integer * 3);
        Path<Integer> x4 = integerSource.map(integer -> integer * 4);
        Path<Integer> x5 = integerSource.map(integer -> integer * 5);
        Supplier<Integer> cache = x2.getCache();
        Supplier<Integer> cacheX3 = x3.getCache();
        Supplier<Integer> cacheX4 = x4.getCache();
        Supplier<Integer> cacheX5 = x5.getCache();
        Publisher<Integer> x2Publisher = x2.getPublisher();
        Publisher<Integer> x3Publisher = x3.getPublisher(true);
        Publisher<Integer> x4Publisher = x4.getPublisher(true);
        Publisher<Integer> x5Publisher = x5.getPublisher(true);

        Consumer<Integer> observer = System.err::println;                                   //Red 200 000
        Consumer<Integer> observer2 = System.out::println;                                  //White 300 000
        Consumer<Integer> observer3 = Print.green::ln; //Green 400 000
        Consumer<Integer> observer4 = Print.purple::ln; //Purple 400 000

        x2Publisher.add(
                observer
        );

        x3Publisher.add(
                observer2
        );

        x4Publisher.add(
                observer3
        );

        x5Publisher.add(
                observer4
        );

        System.err.println("100000...x i");
        TestUtils.FRONT_LOOP(
                100000,
                integerSource::consume
        );

        TestUtils.POSTPONE(
                5000,
                () -> {
                    System.err.println(" " +
                            "\n Postponed!!!... " +
                            "\n Cache x2 =  " + cache +
                            "\n Cache x3 =  " + cacheX3 +
                            "\n Cache x4 =  " + cacheX4 +
                            "\n Cache x5 =  " + cacheX5
                    );
                    Print.green.ln(" " +
                            "\n Postponed!!!... " +
                            "\n x2 =  " + x2 +
                            "\n x3 =  " + x3 +
                            "\n x4 =  " + x4 +
                            "\n x5 =  " + x5
                    );
                    x2Publisher.remove(observer);
                    TestUtils.FINISH();
                }
        );


    }
}
