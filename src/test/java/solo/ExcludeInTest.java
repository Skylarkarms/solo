package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import utils.TestUtils;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class ExcludeInTest {
    private static final String TAG = "ExcludeInTest";
    static final String
            AA = "AA",
            BB = "BB",
            CC = "CC",
            FF = "FF";
    static final String SOLVED_1 = AA.concat(AA);
    static final String SOLVED_2 = CC.concat(AA);
    static final String SOLVED_3 = FF.concat(AA);
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        In.Consume<String> A = new In.Consume<>(
                (Predicate<String>)  s -> s.equals(BB)
        );
        Supplier<String> aCache = A.getCache();

        Path<String> B = A.map(s -> s.concat(AA), (Predicate<String>) s -> s.equals(SOLVED_2));
        Supplier<String> cache = B.getCache();

        Consumer<String> obs = Print.purple::ln;

        TestUtils.POSTPONE(
                1000,
                () -> {
                    Print.green.ln(TAG, "setting: " + AA);
                    B.add(obs);
                    A.accept(AA);
                    check(aCache, cache, SOLVED_1);
                },
                () -> {
                    Print.green.ln(TAG, "setting: " + BB);
                    A.accept(BB);
                    check(aCache, cache, SOLVED_1);
                },
                () -> {
                    Print.green.ln(TAG, "setting: " + CC);
                    A.accept(CC);
                    check(aCache, cache, SOLVED_2);
                },
                () -> {
                    Print.green.ln(TAG, "removing: ");
                    B.remove(obs);
                    Print.green.ln(TAG, "setting: " + BB);
                    A.accept(FF);
                    check(aCache, cache, SOLVED_2);
                },
                () -> {
                    Print.green.ln(TAG, "adding: " + obs);
                    B.add(obs);
                    check(aCache, cache, SOLVED_3);
                },
                TestUtils::FINISH
        );
    }

    private static void check(
            Supplier<String> aCache,
            Supplier<String> bCache,
            String is) {
        String aRes = aCache.get();
        String res = bCache.get();
        Print.cyan.ln(TAG, "A cache = " + aRes);
        Print.purple.ln(TAG, "B cache = " + res);
        assert res.equals(is) : "res is = " + res;
    }
}
