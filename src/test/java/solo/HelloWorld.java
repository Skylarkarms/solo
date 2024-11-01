package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import com.skylarkarms.solo.Settings;
import utils.TestUtils;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class HelloWorld {
    private static final String
            HELLO = "Hello",
            MAD = "Mad",
            WORLD = " World!";
    private static final UnaryOperator<String> map = s -> s.concat(WORLD);
    private static final String RES = map.apply(HELLO);
    private static final String RES2 = map.apply(MAD);
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        In.Consume<String> hello = new In.Consume<>(HELLO);
        Path<String> world = hello.map(map);
        Supplier<String> resSupplier = world.getCache();
        Consumer<String> observer = Print.purple::ln;
        world.add(observer);
        assert hello.isActive();
        assert resSupplier.get().equals(RES);
        new Thread(
                () -> {
                    try {
                        Thread.sleep(1300);
                        hello.accept(MAD);
                        assert resSupplier.get().equals(RES2);

                        Thread.sleep(1300);
                        world.remove(observer);
                        assert !hello.isActive();
                        Settings.shutdownNow();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
        ).start();

    }
}
