package solo;

import com.skylarkarms.solo.In;

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
                                return (obj == observer) || (obj != null && obj.equals(observer));
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
        final Ext ex = new Ext();

        ex.add(
                new ObjIntConsumer<String>() {
                    @Override
                    public void accept(String s, int value) {

                    }
                }
        );
    }
}
