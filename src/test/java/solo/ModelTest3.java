package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Model;
import utils.TestUtils;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public class ModelTest3 {
    static class ModelZed extends Model.Live implements Consumer<Integer> {
        In.Consume<Integer> integerConsume = new In.Consume<>(24);
        {
            sync(integerConsume, this);
        }

        @Override
        public void accept(Integer integer) {
            message(Print.cyan, "Printing..." + integer);
        }
    }
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        ModelZed mz = new ModelZed();

        message("Activating...");
        mz.activate();
        assert mz.isActive() : "Huh??? mz = " + mz;
        TestUtils.FINISH();
    }

    private static void message(String mess) {
        message(Print.green, mess);
    }
    private static void message(Print color, String mess) {
        color.ln(mess);
        LockSupport.parkNanos(Duration.ofSeconds(3).toNanos());
    }
}
