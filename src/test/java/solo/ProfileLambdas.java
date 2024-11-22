package solo;

import com.skylarkarms.concur.Executors;
import com.skylarkarms.lambdaprofiler.LambdaProfiler;
import com.skylarkarms.print.Print;
import com.skylarkarms.solo.Settings;

import java.util.concurrent.TimeUnit;

public class ProfileLambdas {
    public static void purpleTimeout(String TAG, long millis) {
        profile(TAG, LambdaProfiler.Params.custom(
                builder -> builder.setPrinter(
                        new LambdaProfiler.Interpreter.INKS(
                                builder1 -> builder1.single(
                                        LambdaProfiler.Interpreter.DEBUG_TYPE.timeout, Print.purple::ln
                                )
                        )
                ).setTimeout(millis, TimeUnit.MILLISECONDS)
        ));
    }
    public static void profile(String TAG, LambdaProfiler.Params params) {
        LambdaProfiler.Interpreter interpreter = new LambdaProfiler.Interpreter(
                TAG, params
        );
        Settings.redefineDefault(
                Settings.ExecutorType.work,
                threadPoolExecutor -> interpreter.wrap(
                        Executors.cleanFactory(
                                new ThreadGroup(TAG + "-WORK"), Thread.MAX_PRIORITY, Executors.auto_exit_handler()
                        )
                )
        );
        Settings.redefineDefault(
                Settings.ExecutorType.exit,
                threadPoolExecutor -> interpreter.wrap(
                        Executors.cleanFactory(
                                new ThreadGroup(TAG + "-EXIT"), Thread.MAX_PRIORITY, Executors.auto_exit_handler()
                        )
                )
        );
    }
}
