package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;
import utils.TestUtils;

import java.util.Arrays;
import java.util.function.Predicate;

public class SimpleJoinTest {
    static class TEST extends Model.Live {
        In.Consume<Integer>
                int_a = new In.Consume<>(),
                int_b = new In.Consume<>();
        Join<String[]> a_b = new Join<>(
                new String[2]
                , (Predicate<String[]>) strings -> strings.length == 0
                , new Join.Entry<>(
                int_a,
                (s, integer) -> CAS(0,s,integer)
        ), new Join.Entry<>(
                int_b
                , (s, integer) -> CAS(1, s, integer)
        )
        );

        private String[] CAS(int index, String[] s, Integer integer) {
            String converted = integer.toString();
            Print.cyan.print("CAS:"
                    + "\n index = " + index
                    + "\n prev = " + Arrays.toString(s)
                    + "\n integer = " + converted
            );
            if (!converted.equals(s[index])) {
                String[] cloned = s.clone();
                cloned[index] = converted;
                return cloned;
            }
            return s;
        }

        {
            sync(
                    a_b,
                    strings -> {
                        Print.yellow.print("Printing..." + Arrays.toString(strings));
                    }
            );
        }

        @Override
        public String toString() {
            return "TEST{" +
                    "\n >> int_a =" + int_a.toStringDetailed() +
                    ",\n >> int_b =" + int_b.toStringDetailed() +
                    ",\n >> a_b =" + a_b.toStringDetailed() +
                    "\n }@".concat(Integer.toString(hashCode()));
        }
    }

    public static void main(String[] args) {
        Settings.load(ModelStore.Singleton.Entry.get(
                Model.Type.guest,
                TEST.class, TEST::new));
        TEST model = Model.get(TEST.class);
        TestUtils.POSTPONE(
                3000,
                () -> {
                    acceptor(model.int_a, 3);
                }
                ,() -> {
                    acceptor(model.int_b, 5);
                }
                ,() -> {
                    print(">>> Activating...");
                    model.activate();
                    assert model.isActive() : model.toString();
                }
                ,() -> {
                    acceptor(model.int_a, 10);
                }
                ,() -> {
                    print("<<< Deactivating...");
                    model.deactivate();
                    assert !model.isActive() : model.toString();
                }
                ,() -> {
                    print("<<< Fake activation...");
                    Settings.activateModelStore();
                    assert !model.isActive() : model.toString();
                }
                ,() -> {
                    print("<<< Fake deactivation...");
                    Settings.deactivateModelStore();
                }
                ,() -> {
                    acceptor(model.int_b, 20);
                }
                ,() -> {
                    print(">>> Activating...");
                    model.activate();
                    assert model.isActive() : model.toString();
                }
                ,() -> {
                    acceptor(model.int_a, 24);
                }
                ,() -> {
                    acceptor(model.int_b, 48);
                }
                ,() -> {
                    print("<<< Deactivating...");
                    model.deactivate();
                    assert !model.isActive() : model.toString();
                }
                ,() -> {
                    Settings.shutDowNow();
                    TestUtils.FINISH();
                }
        );
    }

    private static void acceptor(In.Consume<Integer> path, int toAccept) {
        print("Accepting..." + toAccept);
        path.accept(toAccept);
    }

    static void print(String action) {
        Print.green.print("Action = " + action);
    }
}
