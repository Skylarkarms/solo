package solo;

import com.skylarkarms.lambdas.BinaryPredicate;
import com.skylarkarms.solo.Path;

public class ConstructorTest {
    private static final String TAG = "ConstructorTest";
    public static void main(String[] args) {
        LOL<Integer> lol = new LOL<>();
        lol.add(
                integer -> {

                }
        );
    }

    static class LOL<T> extends Path.Impl.ObservableState<T> {
        protected LOL() {
            super(BinaryPredicate.equalFun());
        }
    }
}
