package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Example1 {
    private static final String TAG = "Example1";
    record User(int id, String name, int value) {
        public User newID(int newID) {
            return new User(newID, name, value);
        }

//        public static BinaryPredicate.Unary<User> invalidTest = User::notValid;
        boolean notValid() {
            return this.id == 0;
        }

        boolean shouldNOTDispatch() {
            return this.id ==2;
        }
    }
    static User
            uA = new User(0, "A", 24),
            uB = new User(1, "B", 48),
            uC = new User(2, "C", 100);
    static In.Consume<User>
            A = new In.Consume<>(uA),
            B = new In.Consume<>(uB),
            C = new In.Consume<>(uC);

    static Map<Integer, Path<User>> userMap = new HashMap<>();
    static {
        userMap.put(uA.id, A);
        userMap.put(uB.id, B);
        userMap.put(uC.id, C);
    }

    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        In.Consume<Integer> id = new In.Consume<>();

        Path<User> res = id.switchMap(
                Path.Builder.inExcluded(User::notValid).excludeOut(User::shouldNOTDispatch),
                integer -> userMap.get(integer)
//                User::notValid,
//                User::shouldNOTDispatch
        );
        Supplier<User> cache = res.getCache();

        Consumer<User> obs = user -> Print.green.ln(TAG, user);
        res.add(obs);

        TestUtils.POSTPONE(
                1200,
                () -> {
                    id.accept(uB.id);
                    assert B.isActive();
                    User cached = cache.get();
                    assert cached == uB : "Cached was: " + cached;
                },
                () -> {
                    id.accept(uA.id);
                    assert A.isActive();
                    User cached = cache.get();
                    assert cached == uB : "Cached was: " + cached;
                },
                () -> {
                    User newUser = B.get().newID(30);
                    A.accept(newUser);
                    User u = cache.get();
                    assert u.id == 30: "User was = " + u +
                            ",\n expected = " + newUser;
                },
                () -> {
                    id.accept(uC.id);
                    assert cache.get() == uC;
                },
                () -> {
                    res.remove(obs);
                    assert !res.isActive();
                },
                TestUtils::FINISH
        );

    }
}
