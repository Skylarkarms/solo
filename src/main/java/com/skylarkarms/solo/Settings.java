package com.skylarkarms.solo;

import com.skylarkarms.concur.Executors;
import com.skylarkarms.concur.LazyHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class Settings {
    private static volatile boolean debug_mode = false;
    static volatile boolean debug_mode_grabbed;

    record DEBUG_MODE() { static {debug_mode_grabbed = true;}
        static final boolean ref = debug_mode;
    }

    public static synchronized void setDebug_mode(boolean debug_mode) {
        if (debug_mode_grabbed) throw new IllegalStateException("A solo instance has already being called." +
                "\n This value must be set before any one instance is initialized (class loading may be included).");

        Settings.debug_mode = debug_mode;
        LazyHolder.setDebug(debug_mode);
    }

    public static boolean concurrent = true;
    static int all_processors = Runtime.getRuntime().availableProcessors();
    public static int avail_percent = (int) (all_processors - (all_processors *0.3));

    //The high number of work_cores allow more context-switching,
    // triggering more backpressure drops earlier.
    public static int work_cores = avail_percent + all_processors;
    public static int exit_cores = all_processors - avail_percent;

    static {
        System.out.println("Settings cores:" +
                "\n work cores = " + avail_percent +
                "\n work max size = " + work_cores +
                "\n exit cores = " + exit_cores +
                "\n exit max size = " + all_processors +
                "\n Note: These values will change on custom executor services set."
        );
    }

    public enum ExecutorType {
        work, exit
    }

    private static volatile boolean execs_called;
    private record Execs() {
        static {
            execs_called = true;
        }
        private static final ThreadFactory
                workFactory = Executors.cleanFactory(Thread.MAX_PRIORITY)
                , exitFactory = Executors.cleanFactory(Thread.MIN_PRIORITY);

        /**Default System executors.
         * {@systemProperty def_work_executor} 70% of all available processors.
         * {@systemProperty def_exit_executor} 30% of all available processors.
         * */
        private static final Executors.ThrowableExecutor
                def_work_executor = new Executors.ThrowableExecutor(
                avail_percent,
                true,
                work_cores,
                10L, TimeUnit.SECONDS
                , workFactory
        ),
                def_exit_executor = new Executors.ThrowableExecutor(
                        exit_cores,
                        true,
                        all_processors,
                        10L, TimeUnit.SECONDS,
                        exitFactory
                );

        private static Executor
                work_executor = def_work_executor,
                exit_executor = def_exit_executor;
    }

    public static void redefineDefault(ExecutorType type, Consumer<ThreadPoolExecutor> action) {
        try {
            action.accept(type == ExecutorType.work ?
                    (ThreadPoolExecutor) Execs.work_executor :
                    (ThreadPoolExecutor) Execs.exit_executor
            );
        } catch (Exception e) {
            throw new IllegalStateException("The executor was set to a one that's not of the type 'ThreadPoolExecutor.class, " +
                    "\n use 'redefine(Executor)' instead");
        }
    }

    public static Executor getWork_executor() {
        return execs_called ? Execs.work_executor : null;
    }

    public static Executor getExit_executor() {
        return execs_called ? Execs.exit_executor : null;
    }

    public static void setWork_executor(Executor work_executor) {
        if (!Execs.def_work_executor.isShutdown()) Execs.def_work_executor.shutdownNow();
        Settings.Execs.work_executor = work_executor;
    }

    public static void setExit_executor(Executor exit_executor) {
        if (!Execs.def_exit_executor.isShutdown()) Execs.def_exit_executor.shutdownNow();
        Settings.Execs.exit_executor = exit_executor;
    }

    public static final Ref.Storage storage = new Ref.Storage();

    /**
     * {@link LazyHolder.SingletonCollection} that will store a single class of any
     * given {@link Model} implementation delivered via {@link #load(ModelStore.Singleton.Entry[])}
     * */
    static ModelStore.Singleton modelStore;

    public static boolean hasModelStore() { return modelStore != null; }

    public static void clearRefStorage() { storage.clearAll(); }

    /**
     * Will call {@link Model#onDestroy()}
     * */
    public static void shutdownModelStore() {
        ModelStore loc = modelStore;
        if (loc != null) {
            modelStore = null;
            loc.shutdown();
        }
    }

    private static final String model_err = "No Model or LiveModel has been loaded with Settings.load()";

    public static boolean activateModelStore() { return Objects.requireNonNull(modelStore, model_err).activate(); }

    public static void deactivateModelStore() { Objects.requireNonNull(modelStore, model_err).deactivate(); }

    /**
     * Loads {@link Model.Live} implementations to a {@link LazyHolder.SingletonCollection}
     * */
    @SafeVarargs
    public static void load(
            ModelStore.Singleton.Entry<? extends Model>... models
    ) {
        if (hasModelStore()) throw new IllegalStateException("Model Store already loaded, only one loading per application.");
        modelStore = ModelStore.Singleton.populate(
                "[Settings Model Store].",
                models);
    }

    /**index 0 = working runnable <p>
     * index 1 = exit runnable
     * */
    @SuppressWarnings("unchecked")
    public static List<Runnable>[] shutdownNow() {
        shutdownModelStore();
        clearRefStorage();
        List<Runnable>[] runnables = new List[2];
        runnables[0] = new ArrayList<>();
        runnables[1] = new ArrayList<>();
        if (execs_called) {
            if (Execs.work_executor instanceof ExecutorService es) {
                List<Runnable> working = es.shutdownNow();
                runnables[0].addAll(working);
            }
            if (Execs.exit_executor instanceof ExecutorService es) {
                List<Runnable> working = es.shutdownNow();
                runnables[1].addAll(working);
            }
        }
        return runnables;
    }
}