package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;
import utils.TestUtils;

import java.util.function.Consumer;

public class UpdateBindToJoin {
    private static final String TAG = "UpdateBindToJoin";
    enum number {
        zero, one, two, three, four ,five, six, seven, eight, nine, ten, eleven, twelve, thirteen
    }
    static class TEST_MODEL extends Model.Live implements Consumer<String> {
        final Link<Integer> integerLink = new Link<>();

        public void bind(Path<number> numbersPath) {
            integerLink.bind(numbersPath, Enum::ordinal);
        }

        private static final String nan = "NOT_SET";

        final Join.Updatable<String> finalResult = new Join.Updatable<>(
                nan,
                integerLink, (prev, anInt) -> Integer.toString(anInt)
        );

        public boolean isBound() {
            return integerLink.isBound();
        }

        public void set(number number) {
            integerLink.unbind();
            finalResult.update(
                    prev -> {
                        String next;
                        if (!prev.equals((next = number.name()))) {
                            prev = next;
                        }
                        return prev;
                    }
            );
        }

        @Override
        public void accept(String s) { Print.green.ln("PRINTING result = " + s); }

        {
            sync(finalResult, this);
        }
    }

    @SuppressWarnings("unchecked")
    static final In.Consume<number>[] numbers = new In.Consume[] {
            new In.Consume<>(number.zero)
            , new In.Consume<>(number.one)
            , new In.Consume<>(number.two)
            , new In.Consume<>(number.three)
            , new In.Consume<>(number.four)
            , new In.Consume<>(number.five)
    };

    private static final String
            act = "ACTIVATING..."
            , deact = "DE-ACTIVATING..."
            , to_pirnt = "[printing]"
            , bind = "[bind]"
            , set = "[set]"
            ;
    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        Settings.load(
                ModelStore.Singleton.Entry.get(
                        Model.Type.core,
                        TEST_MODEL.class, TEST_MODEL::new
                )
        );

        TEST_MODEL tm = Model.get(TEST_MODEL.class);

        TestUtils.POSTPONE(
                3000,
                () -> {
                    ln(act.concat(to_pirnt) + TEST_MODEL.nan);
                    Settings.activateModelStore();
                }
                , () -> {
                    ln(bind.concat(to_pirnt) + number.zero);
                    tm.bind(numbers[0]);
                }
                , () -> {
                    ln(bind.concat("[repeated]"));
                    tm.bind(numbers[0]);
                }
                , () -> {
                    ln(set.concat(to_pirnt) + number.ten);
                    tm.set(number.ten);
                    assert !tm.isBound() : "Should not be bound.";
                    assert tm.integerLink.isActive() : "Should still be active... connected to Join";
                }
                , () -> {
                    ln("Setting to source..." + number.thirteen);
                    numbers[0].accept(number.thirteen);
                }
                , () -> {
                    ln(bind.concat(to_pirnt) + numbers[0].get());
                    tm.bind(numbers[0]);
                }
                , () -> {
                    ln(deact);
                    Settings.deactivateModelStore();
                }
                , () -> {
                    ln(set + number.five);
                    tm.set(number.five);
                }
                , () -> {
                    ln(act.concat(to_pirnt) + number.five);
                    Settings.activateModelStore();
                }
                , () -> {
                    ln(bind.concat(to_pirnt) + number.three);
                    tm.bind(numbers[3]);
                }
                , () -> {
                    ln(bind.concat(to_pirnt) + number.five);
                    tm.bind(numbers[5]);
                }
                , () -> {
                    ln(set.concat("[to source]").concat(to_pirnt) + number.twelve);
                    numbers[5].accept(number.twelve);
                }
                , TestUtils::FINISH
        );
    }

    static void ln(String s) {
        Print.white.ln(TAG, s);
    }
}
