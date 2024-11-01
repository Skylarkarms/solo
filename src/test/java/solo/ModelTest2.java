package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;
import utils.TestUtils;

import java.time.Duration;

public class ModelTest2 {
    static class ModelZed extends Model {
        static Ref<Integer> anInteRef = new Ref.Lazy<>(
                ModelZed.class,
                modelZed -> modelZed.anInt
        );
        static Ref<String> aStringRef = new Ref.Lazy<>(
                ModelZed.class,
                modelZed -> modelZed.aString
        );
        In.Consume<Integer> anInt = new In.Consume<>(3);
        In.Consume<Integer> anInt2 = new In.Consume<>(4);
        In.Consume<String> aString = new In.Consume<>("Hi!! ");

        public void setAnInt(int anInt) {
            Print.blue.ln("|| >>>>> *** int setting: " + anInt);
            this.anInt.accept(anInt);
        }
        public void setAnInt2(int anInt) {
            Print.blue.ln("int setting: " + anInt);
            this.anInt2.accept(anInt);
        }
        public void setAString(String message) {
            Print.blue.ln("string setting: " + message);
            this.aString.accept(message);
        }
    }
    static class ModelA extends Model.Live {
        Path<String> res = ModelZed.anInteRef.switchMap(
                integer -> {
                    Print.white.ln("new integer = " + integer);
                    return ModelZed.aStringRef.map(
                            s -> s.concat("\n second = " + integer)
                    );
                }
        );

        {
            sync(
                    res,
                    s -> Print.cyan.ln(">>> PRINTING Result = " + s)
            );
        }

        @Override
        protected void onStateChange(boolean isActive) {
            Print.yellow.ln(" isActive? " + isActive);
        }
    }
    public static void main(String[] args) {
        Settings.load(
                ModelStore.Singleton.Entry.get(
                        ModelZed.class
                        , ModelZed::new
                )
                , ModelStore.Singleton.Entry.get(
                        Model.Type.core
                        , ModelA.class
                        , ModelA::new
                )
        );
        ModelZed mz = Model.get(ModelZed.class);

        TestUtils.POSTPONE(
                Duration.ofSeconds(3).toMillis(),
                () -> {
                    message("Activating...(Print)");
                    Settings.activateModelStore();
                }
                , () -> {
                    message("Next should print...");
                    mz.setAnInt(4);
                }
                , () -> {
                    message("Next should print...");
                    mz.setAString("LOL!!!");
                }
                , () -> {
                    message("Deactivating...");
                    Settings.deactivateModelStore();
                }
                , () -> {
                    mz.setAnInt(6);
                }
                , () -> {
                    mz.setAString("HELLO WORLD!!!");
                }
                , () -> {
                    message("Activating...");
                    Settings.activateModelStore();
                }
//                , Settings::activateModelStore
                , () -> {
                    message("Next should print...");
                    mz.setAnInt(24);
                }
                , () -> {
                    message("Next should print...");
                    mz.setAnInt(48);
                }
                , () -> {
                    message("Deactivating...");
                    Settings.deactivateModelStore();
                }
                , () -> {
                    Print.red.ln("Shutting down...");
                }
                , Settings::shutdownNow

        );


    }

    private static void message(String mess) {
        Print.green.ln(mess);
    }
}
