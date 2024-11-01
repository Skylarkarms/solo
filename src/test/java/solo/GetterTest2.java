package solo;

import com.skylarkarms.concur.Versioned;
import com.skylarkarms.print.Print;
import com.skylarkarms.solo.Getter;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Settings;
import utils.TestUtils;

import java.util.Objects;
import java.util.function.Predicate;

public class GetterTest2 {

    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        Print.yellow.ln("Getter main:");
        In.Consume<Integer> integerConsume = new In.Consume<>(
                (Predicate<Integer>) Objects::isNull
        );
        Getter<Integer> integerGetter = new Getter<>(integerConsume) {
            @Override
            protected void CASAttempt(boolean success, Versioned<Integer> prev, Versioned<Integer> next) {
                Print.yellow.ln("Getter attempt:"
                        + "\n >>> success = " + success
                        + "\n >>> prev = " + prev
                        + "\n >>> next = " + next
                );
            }

            @Override
            protected void onStateChange(boolean isActive) {
                Print.yellow.ln("Getter is active = " + isActive);
            }
        };

        integerGetter.activate();
        assert integerGetter.isActive();
        integerConsume.consume(5);

        int res = integerGetter.get();
        assert res == 5 : "Huh? getter = " + integerGetter;
        Settings.shutdownNow();
    }
}
