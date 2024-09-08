package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;
import utils.TestUtils;

import java.util.function.Consumer;

public class ModelTest4 {
    static final class Instances {
        public static Ref.Lazy<String> M1_REF = new Ref.Lazy<>(
                M1.class,
                m -> m.stringPath
        );
        public static Ref.Lazy<String> M2_REF = new Ref.Lazy<>(
                M2.class,
                m -> m.path
        );
        public static Ref.Lazy<String> M3_REF = new Ref.Lazy<>(
                M3.class,
                m -> m.path
        );
    }
    static class M1 extends Model {
        public static String first = "FIRST";
        private final In.Consume<String> stringPath = new In.Consume<>(first);

        @Override
        protected void onDestroy() {
            print(Print.cyan, "onDestroy from " + this);
        }
    }
    static abstract class Ext extends Model.Live implements Consumer<String> {
        final String TAG;
        final Path<String> path;
        Ext(
                Path<String> path,
                String TAG
        ){
            this.path = path;
            this.TAG = TAG;
            sync(path, this);
        }

        @Override
        protected void onStateChange(boolean isActive) {
            Print.yellow.print(TAG + " is active? " + isActive);
        }

        @Override
        public final void accept(String s) {
            Print.green.print(TAG + print.concat(" := ".concat(s)));
        }

        @Override
        protected void onDestroy() {
            print(Print.cyan, "onDestroy from " + this);
        }
    }
    static class M2 extends Ext {
        private static final String TAG = " => M2";

        M2() {
            super(
                    Instances.M1_REF.map(
                            s -> s + TAG
                    )
                    , TAG);
        }
    }
    static class M3 extends Ext {
        private static final String TAG = " => M3";

        M3() {
            super(
                    Instances.M2_REF.map(
                            s -> s + TAG
                    )
                    , TAG);
        }
    }
    static class GUEST extends Ext {
        private static final String TAG = " => GUEST";
        private final String instance_TAG = TAG.concat("@".concat(Integer.toString(hashCode())));


        GUEST() {
            super(
                    Instances.M2_REF.map(
                            s -> s + TAG
                    )
                    , TAG);
        }

        @Override
        public String toString() {
            return "GUEST{" +
                    "\n >>> instance_TAG='" + instance_TAG +
                    "\n >>> super='" + super.toString() +
                    "\n }";
        }
    }
    static void print(Print color, String message) {
        color.print(message);
    }
    static String
            set = "[Setting]",
            grab = "[Grab]",
            exception = "[EXCEPTION!]",
            act = "[Activating]",
            deact = "[De-Activating]",
            print = "[Printing]",
            store = "[store]",
            core = "[core = M2]",
            lazy_core = "[lazy_core = M3]",
                    // to - Set
            sec = "SECOND",
            thrd = "THIRD",
            fourth = "FOURTH"
                    ;
    static Expect expect = strings -> "\n >>> expect[" + getJoin(strings) + "] \n";
    interface Expect {
        String apply(String... strings);
    }

    static void print(String... strings) {
        print(Print.white, getJoin(strings));
    }

    private static String getJoin(String... strings) {
        return String.join(",", strings);
    }

    public static void main(String[] args) {
        TestUtils.setPrinterParams();
        Settings.load(
                ModelStore.Singleton.Entry.get(
                        M1.class, M1::new
                )
                , ModelStore.Singleton.Entry.get(
                        Model.Type.core
                        , M2.class, M2::new
                )
                , ModelStore.Singleton.Entry.get(
                        Model.Type.lazy_core
                        , M3.class, M3::new
                )
                , ModelStore.Singleton.Entry.get(
                        Model.Type.guest
                        , GUEST.class, GUEST::new
                )
        );

        TestUtils.POSTPONE(
                3000,
                () -> {
                    print(act, store,
                            expect.apply(act, core, print, M1.first)
                    );
                    Settings.activateModelStore();
                }
                , () -> {
                    print(set, fourth, expect.apply(core, print, fourth));
                    Model.get(M1.class).stringPath.accept(fourth);
                }
                , () -> {
                    print(grab, lazy_core, expect.apply(lazy_core, print, fourth));
                    M3 m3 = Model.get(M3.class);
                    m3.accept(grab);
                }
                , () -> {
                    print(deact, store,
                            expect.apply(core, lazy_core, deact)
                    );
                    Settings.deactivateModelStore();
                }
                , () -> {
                    print(set, thrd);
                    Model.get(M1.class).stringPath.accept(thrd);
                }
                , () -> {
                    print(act, store,
                            expect.apply(
                                    act, core, lazy_core, print, thrd
                            )
                    );
                    Settings.activateModelStore();
                }
                , () -> {
                    print(deact, core,
                            expect.apply(
                                    core, exception
                            )
                    );
                    try {
                        M2 m2;
                        (m2 = Model.get(M2.class)).deactivate();
                        assert m2.isActive() : "Cannot be inactive = " + m2;

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                , () -> {
                    print(deact, lazy_core,
                            expect.apply(
                                    lazy_core, exception
                            )
                    );
                    try {
                        M3 m;
                        (m = Model.get(M3.class)).deactivate();
                        assert m.isActive() : "Cannot be inactive = " + m;

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                , () -> {
                    print(grab, GUEST.TAG
                    );
                    Model.get(GUEST.class).accept(grab);
                }
                , () -> {
                    print(act, GUEST.TAG,
                            expect.apply(
                                    act, GUEST.TAG, print, thrd
                            )
                    );
                    Model.get(GUEST.class).activate();
                }
                , () -> {
                    GUEST g2 = new GUEST();
                    print(act, g2.instance_TAG,
                            expect.apply(
                                    act, g2.instance_TAG, print, thrd
                            )
                    );
                    g2.activate();
                    assert g2.isActive() : "Instance=[" + g2 + "] has not been activated = ";
                }
                , TestUtils::FINISH
        );
    }

}
