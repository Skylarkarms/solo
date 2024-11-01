package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;
import utils.TestUtils;

import java.util.function.IntConsumer;

public class GetterTest {
    private static final String TAG = "GetterTest";
    static {
        Print.setAutoFlush(true);
        Print.printStack(true);
    }
    private static final IntConsumer printer = i -> Print.red.ln(TAG, i);

    static final class VM extends Model.Live {
        public static final Ref.Lazy<Integer> combine = new Ref.Lazy<>(
                VM.class,
                model -> model.m_combine
        );
        In.Consume<Integer>
                aSource = new In.Consume<>(4),
                bSource = new In.Consume<>(6),
                cSource = new In.Consume<>(7);

        Path<Integer>
                mapA = aSource.map(integer -> integer * 13),
                mapB = bSource.map(integer -> integer * 6),
                mapC = cSource.map(integer -> integer * 5);

        Path<Integer> m_combine = mapA.switchMap(intA ->
                mapB.switchMap(intB ->
                        mapC.map(intC ->
                                intA * intB * intC

                        )
                ));

        void aAccept(int i) { aSource.accept(i); }
        void bAccept(int i) { bSource.accept(i); }
        void cAccept(int i) { cSource.accept(i); }

        public void finalAssert() {
            assert !mapA.isActive();
            assert !mapB.isActive();
            assert !mapC.isActive();
            assert !aSource.isActive();
            assert !bSource.isActive();
            assert !cSource.isActive();
        }
    }

    public static void main(String[] args) {
        Thread.currentThread().getId();
        Settings.load(
                ModelStore.Singleton.Entry.get(VM.class, VM::new)
        );
        Path<Integer> combine = VM.combine;
        Getter<Integer> integerGetter = new Getter<>(combine) {
            @Override
            protected void onStateChange(boolean isActive) {
                Print.yellow.ln(
                        TAG,
                        "This getter [" + this + "]..." +
                                "\n is active? " + isActive
                );
            }
        };
        VM vm = Model.get(VM.class);

        /*A * B * C*/

        TestUtils.POSTPONE(
                1000,
                () -> {
                    integerGetter.activate();
                    assert integerGetter.isActive();
                    int res = integerGetter.get();
                    assert res == 65520;
                    print(res); // 65 520
                    integerGetter.deactivate();
                    assert !integerGetter.isActive();
                    assert !combine.isActive() : "Should be inactive!!";
                },
                integerGetter::activate,
                () -> {
                    assert integerGetter.isActive();
                    vm.aAccept(7); // 114 660
                    int res = integerGetter.get();
                    assert res == 114660: "Real is = " + res + ", NOT 114660";
                    print(res);
                },
                () -> {
                    vm.bAccept(9);
                    vm.cAccept(17);
                    int res = integerGetter.get();
                    assert res == 417690 : "Real is = " + res + ", NOT 417690";
                    print(res); // 417 690
                },
                () -> {
                    integerGetter.deactivate();
                    assert !combine.isActive() : "Should be inactive!!";
                    try {
                        int res = integerGetter.get();
                        print(res);
                    } catch (Exception e) {
                        Print.blue.ln(TAG, "This should throw: " + e.getMessage() +
                                Print.divisor + "getter is Active? " + integerGetter.isActive() +
                                Print.divisor + "combine is Active? " + combine.isActive()
                        );
                        vm.aAccept(20);
                        Print.yellow.ln(TAG,
                                "Using passiveGet... "
                                        + integerGetter.passiveGet() // 417 690
                        );
                        assert !integerGetter.isActive() : " Should be inactive!!!";
                        integerGetter.activate();
                        int res = integerGetter.get();
                        assert res == 1193400: "Real res = " + res + ", NOT 1193400";
                        print(res); // 1193 400
                    }
                },
                () -> {
                    assert integerGetter.isActive() : "Should be active!!!";
                    integerGetter.deactivate();
                    assert !integerGetter.isActive();
                    TestUtils.FRONT_LOOP(
                            18,
                            vm::aAccept
                    );
                    int res = integerGetter.passiveGet();
                    Print.cyan.ln(TAG,
                            "passive..."
                                    + res // 1193 400
                    );
                    assert res == 1193400 : "Real res = " + res + ", NOT 1193400";
                    integerGetter.activate();
                    assert integerGetter.isActive();
                    int res2 = integerGetter.get();
                    Print.yellow.ln("Res = " + res2);
                    print(res2); // 1074 060
                },
                () -> {
                    Print.green.ln(TAG, "is active? " + integerGetter.isActive());
                    integerGetter.deactivate();
                    assert !integerGetter.isActive();
                    assert !combine.isActive();
                    vm.finalAssert();
                    Print.green.ln(TAG, "is active? " + integerGetter.isActive());
                },
                TestUtils::FINISH

        );

    }

    private static void print(int res) { GetterTest.printer.accept(res); }
}
