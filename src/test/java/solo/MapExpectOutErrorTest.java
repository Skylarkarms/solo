package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import com.skylarkarms.solo.Settings;

import java.util.Objects;
import java.util.function.Predicate;

public class MapExpectOutErrorTest {
    public static void main(String[] args) {
        SomePath sp = new SomePath();

        Path<Integer> path = sp.map(Integer::parseInt, (Predicate<Integer>) Objects::isNull);

        path.add(i -> {
            Print.yellow.print("LOL...");
            Print.red.print(i);
            Settings.shutDowNow();
        });
    }

    static class SomePath extends Path.Impl<String> {
        In.InStrategy.ToBooleanConsumer<String> in = In.Consume.Config.Consume.NON_CONT().apply(this);
        @Override
        protected void onStateChange(boolean isActive) {
            Print.purple.print("isActive? " + isActive);
            if (isActive) in.accept("45");
        }
    }
}
