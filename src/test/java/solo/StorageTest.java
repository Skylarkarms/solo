package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;

import java.util.UUID;
import java.util.function.Consumer;

public class StorageTest {

    public static void main(String[] args) {
        Consumer<String> obs = System.out::println;
        Settings.load(
                ModelStore.Singleton.Entry.get(
                        LiveModelA.class, LiveModelA::new
                )
                , ModelStore.Singleton.Entry.get(
                        LiveModelB.class, LiveModelB::new
                )
        );
        UUID id = LiveModelB.lazyRef_0.getId();
        Print.green.print(id);
        Ref.Lazy<String>
                storedLazyRef = Settings.storage.getLazyRef(id, String.class);
        storedLazyRef.add(obs);
        Model.get(LiveModelA.class).source.accept(4);
        Model.get(LiveModelB.class).res.remove(obs);
        assert !LiveModelB.lazyRef_0.isActive();

        Settings.shutDowNow();
    }

    static class LiveModelA extends Model.Live {
        static Ref.Lazy<String> lazyRef = new Ref.Lazy<>(LiveModelA.class, modelA -> modelA.toString);

        private static final String TAG = "ModelA";
        static {
            System.out.println("Building " + LiveModelA.TAG);
        }
        final In.Consume<Integer> source = new In.Consume<>(13);
        final Path<Integer> sourceMapped = source.map(integer -> integer * 5);
        final Path<String> toString = sourceMapped.map(String::valueOf);


    }

    static class LiveModelB extends Model.Live {
        private static final String TAG = "ModelB";
        static Ref.Lazy<String> lazyRef_0 = new Ref.Lazy<>(LiveModelB.class, LiveModelB::getRes);

        static {
            System.out.println("Building " + LiveModelB.TAG);
        }
        final Path<String> refMapped = LiveModelA.lazyRef.map(s -> s.concat(" MIXED!!!"));
        final Path<String> res = refMapped.map(s -> s.concat("===> FINISHED!!!"));

        Path<String> getRes() {
            return res;
        }
    }
}
