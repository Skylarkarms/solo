package solo;

import com.skylarkarms.concur.Locks;
import com.skylarkarms.lambdas.Consumers;
import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;
import utils.TestUtils;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

public class AsGetterTest {

    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        Settings.setDebug_mode(true);
        Consumers.OfString obs = System.out::println;
        Settings.load(
                ModelStore.Singleton.Entry.get(
                        LiveModelA.class, LiveModelA::new
                )
                , ModelStore.Singleton.Entry.get(
                        LiveModelB.class, LiveModelB::new
                )
        );
        Settings.activateModelStore();
        LiveModelB b = Model.Live.get(LiveModelB.class);
//        Print.blue.print(LiveModelA.lazyRef.toStringDetailed());
                b.activate();
        Print.blue.ln(LiveModelA.lazyRef.toStringDetailed());
        Print.yellow.ln(LiveModelA.lazyRef.toString());

        Model.Live.get(LiveModelB.class).readFirst(
                obs
        );
        Model.Live.get(LiveModelA.class).source.accept(4);
        Model.Live.get(LiveModelB.class).readFirst(
                obs
        );

        LockSupport.parkNanos(Duration.ofMillis(3000).toNanos());
        Settings.shutdownNow();
    }

    static class LiveModelA extends Model.Live {
        static Ref.Lazy<String> lazyRef = new Ref.Lazy<>(
                Locks.ExceptionConfig.runtime_10(),
                LiveModelA.class, modelA -> modelA.toString);


        private static final String TAG = "ModelA";
        static {
            System.out.println("Building " + LiveModelA.TAG
                    + "\n >> id = " + lazyRef.getId()
            );
        }
        final In.Consume<Integer> source = new In.Consume<>(13);
        final Path<Integer> sourceMapped = source.map(integer -> integer * 5);
        final Path<String> toString = sourceMapped.map(String::valueOf);


    }

    static class LiveModelB extends Model.Live {
        private final Getter<String> fromA = asGetter(LiveModelA.lazyRef);

        public void readFirst(Consumers.OfString stringConsumer) {
            fromA.first(
                    stringConsumer
            );
        }
    }
}
