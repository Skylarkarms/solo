package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.*;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

public class ModelTest {
    static class ModelZed extends Model.Live {
        static Ref<Integer> anInteRef = new Ref.Lazy<>(
                ModelZed.class,
                modelZed -> modelZed.anInt
        );
        static Ref<String> aStringRef = new Ref.Lazy<>(
                ModelZed.class,
                modelZed -> modelZed.aString
        );
        In.Consume<Integer> anInt = new In.Consume<>(3);
        In.Consume<String> aString = new In.Consume<>("Hi!! ");

        public void setAnInt(int anInt) {
            Print.blue.ln("int setting: " + anInt);
            this.anInt.accept(anInt);
        }
        public void setAString(String message) {
            Print.blue.ln("string setting: " + message);
            this.aString.accept(message);
        }
    }
    static class ModelA extends Model.Live {
        Getter<Integer> integerGetter = asGetter(ModelZed.anInteRef);

        {
            sync(
                    ModelZed.anInteRef,
                    ModelZed.aStringRef,
                    (integer, s) -> Print.cyan.ln(">>> sync Result = " + (s + integer))
            );
        }

        void check() {
            Print.yellow.ln("checking...");
            integerGetter.passiveNext(Print.purple::ln);
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
        ModelA ma = Model.get(ModelA.class);
        try {
            ma.check();
        } catch (Exception e) {
            Print.green.ln(">>>>>>>>>>> CAUGHT!!!" +
                    "\n message = " + e.getMessage());
        }
        message("Activating...");
        Settings.activateModelStore();
        ma.check();
        ModelZed mz = Model.get(ModelZed.class);
        mz.setAnInt(4);
        mz.setAString("LOL!!!");
        ma.check();
        message("Deactivating...");
        Settings.deactivateModelStore();
        mz.setAnInt(6);

        try {
            ma.check();
        } catch (Exception e) {
            Print.green.ln(">>>>>>>>>>> 2ND!!! CAUGHT!!!" +
                    "\n message = " + e.getMessage());
        }
        mz.setAString("HELLO WORLD!!!");
        message("Activating...");
        Settings.activateModelStore();
        message("Deactivating...");
        Settings.deactivateModelStore();

        Print.red.ln("Shutting down...");
        Settings.shutdownNow();
    }

    private static void message(String mess) {
        Print.green.ln(mess);
        LockSupport.parkNanos(Duration.ofSeconds(3).toNanos());
    }
}
