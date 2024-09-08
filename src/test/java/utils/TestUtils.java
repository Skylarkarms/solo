package utils;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.Settings;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

public class TestUtils {
    private static final int
            loops = 30;

    /**{@link #loops}*/
    public static void FRONT_LOOP(IntConsumer iter) {
        FRONT_LOOP(loops, iter);
    }

    /**{@link #loops}*/
    public static void FRONT_LOOP(int forInt, IntConsumer iter) {
        for (int i = 0; i < forInt + 1; i++) {
            iter.accept(i);
        }
    }

    /**{@link #loops}*/
    public static void FRONT_LOOP(int forInt, IntConsumer iter, Runnable onFinish) {
        for (int i = 0; i < forInt + 1; i++) {
            iter.accept(i);
        }
        onFinish.run();
    }

    /**{@link #loops}*/
    public static void BACK_LOOP(int forInt, IntConsumer iter) {
        START(
                () -> {
                    try {
                        FRONT_LOOP(forInt, iter);
                    } catch (Exception | Error e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    /**{@link #loops}*/
    public static void BACK_LOOP(int forInt, IntConsumer iter, Runnable onFinish) {
        START(
                () -> FRONT_LOOP(forInt, iter, onFinish)
        );
    }

    public static void setPrinterParams() {
        Print.setAutoFlush(true);
//        Print.printStack(true);
    }

    public static void START(Runnable runnable) {
        new Thread(
                runnable
        ).start();
    }

    /**{@link #loops}*/
    public static void POSTPONE(long millis, Runnable later) {
        START(
                () -> {
                    try {
                        System.err.println("Sleeping... " + millis + " millis.");
                        Thread.sleep(millis);
                        later.run();
                    } catch (RuntimeException | InterruptedException | Error e) {
                        Settings.shutDowNow();
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    /**{@link #loops}*/
    public static void POSTPONE(long millis, Runnable... later) {
        Runnable resolved = () -> POSTPONE(millis, later[later.length - 1]);
        UnaryOperator<Runnable> postponed = runnable -> () -> POSTPONE(
                millis,
                runnable
        );
        for (int i = later.length - 2; i >= 0; i--) {
            Runnable runnable = later[i];
            Runnable finalResolved = resolved;
            resolved = postponed.apply(() -> {
                runnable.run();
                finalResolved.run();
            });
        }
        resolved.run();
    }

    public static void threadWait(long millis) {
        try {
            System.err.println("sleeping..." + millis + " millis.");
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void FINISH() {
        POSTPONE(
                500,
                Settings::shutDowNow
        );
    }

    static<T> T[] map(IntFunction<T[]> collector, int size, IntFunction<T> supplier) {
        T[] res = collector.apply(size);
        for (int i = 0; i < size; i++) {
            res[i] = supplier.apply(i);
        }
        return res;
    }

}
