package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;

import java.util.function.Consumer;

import static utils.TestUtils.FINISH;
import static utils.TestUtils.POSTPONE;

public class OpenSwitchTest {
    private static final String TAG = "SwitchTest";
    public static void main(String[] args) {
        Print.setAutoFlush(true);
        Print.printStack(true);

        In.Consume<Integer> integerSource = new In.Consume<>();
        In.Consume<Integer> base = new In.Consume<>();
        base.accept(4);

        Print.yellow.print(TAG, Print.divisor + " base = " + base);

        Path<Integer>
                firstSwitch =
                integerSource.openSwitchMap(integer ->
                        {

                            Path<Integer> res = base.openMap(integer1 ->
                                            integer * integer1,
                                    (success, prev, next) -> {

                                    }
                            );
                            Print.cyan.print(TAG, " " +
                                    "\n integer = " + integer + Print.divisor + " map path = " + res + Print.divisor + "base Path = " + base);
                            return res;
                        },
                        (success, prev, next) -> {

                        }
                );


        Consumer<Integer> firstSwitchObs = integer ->
        {
            Print.purple.print(">>>>>>>>>>>>>>>>>>> NUMBER >>>>>>>> || " + integer);
        };
        firstSwitch.add(firstSwitchObs);

        Print.green.print(TAG, " >>>>>  FIRST acceptor = " + integerSource);

        POSTPONE(
                200,
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [3]");
                    integerSource.accept(3); // R = 12
                },
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [6]");
                    integerSource.accept(6); // R = 24
                },
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(9); // R = 36
                },
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(11); // R = 44
                },
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(13); // R = 52
                },
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(15); // R = 60
                },
                () -> {
                    Print.blue.print(TAG, " >>>>>  acceptor from [9]");
                    integerSource.accept(17); // R = 68
                },
                () -> {
                    Print.yellow.print(
                            " " +
                                    "\n acceptor = " + integerSource +
                                    "\n firstSwitch = " + firstSwitch +
                                    "\n base = " + base
                    );
                    firstSwitch.remove(firstSwitchObs);

                    POSTPONE(
                            2000,
                            () -> {
                                Print.red.print(
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
