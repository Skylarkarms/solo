package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;

import java.util.function.Consumer;

import static utils.TestUtils.FINISH;
import static utils.TestUtils.POSTPONE;

public class SwitchTest4 {
    private static final String TAG = "SwitchTest";
    public static void main(String[] args) {
        Print.setAutoFlush(true);
        Print.printStack(true);

        In.Consume<Integer> integerSource = new In.Consume<>();
        In.Consume<Integer> base = new In.Consume<>();
        base.accept(4);

        Print.yellow.ln(TAG, Print.divisor + " base = " + base);

        Path<Integer>
                firstSwitch =
                integerSource.switchMap(integer ->
                {

                    Path<Integer> res = base.map(integer1 ->
                            integer * integer1
                    );
                    Print.cyan.ln(TAG, " " +
                            "\n integer = " + integer + Print.divisor + " map path = " + res + Print.divisor + "base Path = " + base);
                    return res;
                }
        );


        Consumer<Integer> firstSwitchObs = integer ->
        {
            Print.purple.ln(">>>>>>>>>>>>>>>>>>> NUMBER >>>>>>>> || " + integer);
        };
        firstSwitch.add(firstSwitchObs);

        Print.cyan.ln(TAG, " >>>>>  FIRST acceptor = " + integerSource);

        POSTPONE(
                200,
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [3]");
                    integerSource.accept(3); // R = 12
                },
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [6]");
                    integerSource.accept(6); // R = 24
                },
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(9); // R = 36
                },
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(11); // R = 44
                },
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(13); // R = 52
                },
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(15); // R = 60
                },
                () -> {
                    Print.blue.ln(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(17); // R = 68
                },
                () -> {
                    Print.yellow.ln(
                            " " +
                                    "\n acceptor = " + integerSource +
                                    "\n firstSwitch = " + firstSwitch +
                                    "\n base = " + base
                    );
                    firstSwitch.remove(firstSwitchObs);

                    POSTPONE(
                            2000,
                            () -> {
                                Print.red.ln(
                                        "AFTER removed!!: firstSwitch = " + firstSwitch +
                                                ",\n firstSwitch is Active?? " + firstSwitch.isActive() +
                                                Print.divisor +
                                                ",\n acceptor = " + integerSource +
                                                ",\n acceptor is active? " + integerSource.isActive() +
                                                Print.divisor +
                                                ",\n base = " + base +
                                                ",\n base is active? " + base.isActive()
                                );
                                FINISH();
                            }
                    );
                }
        );
    }
}
