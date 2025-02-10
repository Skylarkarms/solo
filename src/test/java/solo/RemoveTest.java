package solo;

import com.skylarkarms.concur.Locks;
import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Settings;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

public class RemoveTest {
    private static final String TAG = "RemoveTest";


    public static void main(String[] args) {
        class Ext extends In.Compute<String> {

            public Ext() {
                super(In.Config.Compute.BACK());
            }

            final AtomicInteger ver = new AtomicInteger();
            public void add(ObjIntConsumer<? super String> observer) {
                super.add(
                        new Consumer<>() {
                            @Override
                            public void accept(String s) {
                                observer.accept(s, ver.incrementAndGet());
                            }

                            @Override
                            public boolean equals(Object obj) {
                                boolean found = (obj == observer) || (obj != null && obj.equals(observer));
//                                if (!found) {
                                    Print.yellow.ln(
                                            "\n found = " + found
                                            + "\n THIS = " + observer
                                            + "\n THAT = " + obj
                                    );
//                                }
                                return found;
                            }
                        }
                );
            }
            public void remove(ObjIntConsumer<? super String> observer) {
                super.remove(
                        observer
                );
            }


        }
        Settings.concurrent = false;
        final Ext ex = new Ext();
        Locks.Valet valet = new Locks.Valet();
        AtomicInteger count = new AtomicInteger(2);
        ObjIntConsumer<String> obs = (s, value) -> {
            Print.purple.ln("" +
                            "\n s = " + s
                            + "\n value = " + value
            );
            if (count.decrementAndGet() == 0) {
                Print.yellow.ln("shutting down...objInt");
                valet.shutdown();
            }
        };
        Consumer<String> normalObserver = s -> {
            Print.purple.ln(
                    "\n s = " + s
            );
            if (count.decrementAndGet() == 0) {
                Print.yellow.ln("shutting down...cons");
                valet.shutdown();
            }
        };
        Print.purple.ln("size = " + ex.observerSize());
        ex.add(obs);
        assert ex.contains(obs) : "Error 1";
        ex.add(normalObserver);
        assert ex.contains(normalObserver) : "Error 2";
        ex.accept(() -> "LMAO");
        valet.parkUnpark(10, TimeUnit.SECONDS);
        Print.green.ln("un-parked...");
        ex.remove(obs);
        assert !ex.contains(obs) : "Error 3";
        Print.blue.ln("removed obs");
        ex.remove(normalObserver);
        assert !ex.contains(normalObserver) : "Error 4";
        Print.blue.ln("removed normalObserver");
        Print.yellow.ln("size = " + ex.observerSize());
        assert !ex.isActive() : "Failed...";
        Print.blue.ln("sleeping");
        Locks.robustPark(5, TimeUnit.SECONDS);
        Executor executor = Settings.getExit_executor();
        if (executor instanceof ThreadPoolExecutor tp) {
            assert tp.isShutdown() : "Hmm....";
        }
    }
}
