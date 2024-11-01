package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Link;
import utils.TestUtils;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class LinkTest {
    private static final String TAG = "LinkTest";
    static {
        TestUtils.setPrinterParams();
    }
    public static void main(String[] args) {
        In.Consume<Integer> consumer = new In.Consume<>();
        Link<Integer> integerJoin = new Link<>(4);
        Supplier<Integer> integerSupplier = integerJoin.getCache();

        Consumer<Integer> printer = Print.purple::ln;

        TestUtils.POSTPONE(
                3000,
                () -> {
                    Print.green.ln(TAG, "Connecting listener...");
                    integerJoin.add(printer);
                }
                ,() -> {
                    Print.green.ln(TAG, "Binding...");
                    integerJoin.bind(consumer);
                    assertThat(integerSupplier, 4);
                }
                ,() -> {
                    int to = 5;
                    Print.green.ln(TAG, "changing...to to " + to);
                    consumer.accept(to);
                    assertThat(integerSupplier, to);
                }
                ,() -> {
                    int to = 7;
                    Print.green.ln(TAG, "changing...to to " + to);
                    consumer.accept(to);
                    assertThat(integerSupplier, to);
                }
                ,() -> {
                    Print.green.ln(TAG, "unbinding...");
                    integerJoin.unbind();
                    assertThat(integerSupplier, 7);
                }
                ,() -> {
                    int to = 9;
                    Print.green.ln(TAG, "changing...to to " + to);
                    consumer.accept(to);
                    assertThat(integerSupplier, 7);
                }
                ,() -> {
                    Print.green.ln(TAG, "Binding...");
                    integerJoin.bind(consumer);
                    assertThat(integerSupplier, 9);
                }
                ,() -> {
                    Print.green.ln(TAG, "Unbinding...");
                    integerJoin.unbind(consumer);
                    assert !integerJoin.isBound() : "It should not be bounded";
                }
                ,() -> {
                    Print.green.ln(TAG, "Detaching listener...");
                    integerJoin.remove(printer);
                    assert !consumer.isActive() : "Consumer was active!";
                    assert !integerJoin.isActive() : "Join was active!";
                }
                ,TestUtils::FINISH
        );
    }

    static void assertThat(Supplier<Integer> value, int is) {
        Integer res;
        assert (res = value.get()) == is : "Value was not [" + is + "], Real value was [" + res + "].";
    }
}
