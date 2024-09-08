package com.skylarkarms.solo;

import com.skylarkarms.concur.Executors;
import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

final class Cache<T>
        implements Supplier<Versioned<T>>, IntSupplier
{
    /**
     * The cache is always coherent ... It doesn't matter if its LOCK-prefixed or LL/SC synchronized.
     * volatile is ONLY needed on double-checking cases, where the compiler may hoist the load and simplify repeating checks.
     * Volatile may still be required on older implementations of the JIT/compiler... where the double re-ordering
     * may alter the spin-lock enough to move the load before the loop. (Even tho according to some sources including the linux kernel manual:
     * <a href="https://www.kernel.org/doc/Documentation/memory-barriers.txt">https://www.kernel.org/doc/Documentation/memory-barriers.txt</a>)
     * The optimal thing to do would be to do this as a `memory_order_relaxed`, sadly Java's getOpaque version has too much overhead,
     * in this sense volatile alone is faster than opaqueness.
     * <p>
     * By eliminating volatile keyword, the processor is allowed to better execute "speculative execution" dropping older
     * version even earlier.
     * Let's see how this performs...
     * If there is a way to naively build our own `getOpaque` with VarHandle.fences... that would be the proper way.
     * */
    volatile Versioned<T> localCache;
    /**
     * Each and every situation in which an atomic CAS is performed, must immediately assume that the expected value should be discarded IF
     * Some other Thread did take hold of cache exclusivity.
     * Since the CAS involves complex memory alterations at a higher level, it is convenient that a weakerCAS
     * is used if possible, so LL/SC type architectures are benefited from this.
     * */
    private static final VarHandle VALUE;

    @SuppressWarnings("unchecked")
    Versioned<T> getOpaque() { return (Versioned<T>)VALUE.getOpaque(this); }

    /**T relaxedGet() {
     VarHandle.loadLoadFence();
     T res = localCache.value();
     VarHandle.loadLoadFence();
     return res;
     }*/

    T liveGet() { return getOpaque().value(); }

    final BinaryPredicate<T> excludeIn;
    final Predicate<T> excludeOut;

    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(Cache.class, "localCache", Versioned.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    final Runnable dispatch;
    final InternalSwapped<T> onSwapped;

    /**
     * This method will be used for double-checking, and it is required to be opaque
     * so that the result is not hoisted at the beginning
     * */
    @Override
    public int getAsInt() { return getOpaque().version(); }

    /**
     * This callback broadcasts whether CAS processes where a {@code success} or not. <p>
     * No matter the result, the response will bring the witness value defined as {@code prev} <p>
     * and the attempted value to set, defined as {@code next}. <p>
     * Whenever the boolean {@code success} appears true, the {@code next} value should be assumed as the current real state of {@link Cache}. <p>
     * If false, the CAS failed, and {@code next} would not have been set. <p>
     * If {@code prev} appears null, it means this is the FIRST swap to occur on this {@link Cache} instance. <p>
     * */
    @FunctionalInterface
    interface InternalSwapped<T> {
        void attempt(boolean success, Versioned<T> prev, Versioned<T> next);
    }

    final BinaryPredicate<Versioned<T>>
            weakSwapper,
            strongSwapper,
            weakSwapDispatcher,
            strongSwapDispatcher
                    ;
    final BooleanSupplier isConsumable;

    /** returns null if false*/
    final Supplier<Versioned<T>> isConsumableSupplier;

    interface CAS<T> {
        /**
         * Will compare the current value against the `expect` and attempt a set.
         * <p> The set operation is non-contentious which means that the swapping operation may fail under contention.
         * <p> It applies a strongCAS which means that
         * this will never fail for something other than contention alone.
         * */
        boolean compareAndSwap(T expect, T set);
        /**
         * Attempts to set the value.
         * <p> May fail under contention.
         * <p> It applies a strongCAS which means that
         * this will never fail for something other than contention alone.
         * */
        boolean weakSet(T set);
    }

    static<S> Cache<S> getDefault(InternalSwapped<S> onSwapped) {
        return new Cache<>(
                Lambdas.BinaryPredicates.defaultFalse(),
                Versioned.getDefault(),
                Predicates.defaultFalse(),
                Lambdas.emptyRunnable(), onSwapped
        );
    }

    Cache(
            BinaryPredicate<T> excludeIn,
            Versioned<T> initialValue,
            Predicate<T> excludeOut,
            Runnable dispatch,
            InternalSwapped<T> onSwapped
    ) {
        this.excludeIn = excludeIn;
        this.excludeOut = excludeOut;
        if (isOverridden(onSwapped)) {
            this.weakSwapper = (prev, next) -> {
                boolean swapped = VALUE.weakCompareAndSet(this, prev, next);
                onSwapped.attempt(swapped, prev, next);
                return swapped;
            };
            this.strongSwapper = (prev, next) -> {
                boolean swapped = VALUE.compareAndSet(this, prev, next);
                onSwapped.attempt(swapped, prev, next);
                return swapped;
            };
        } else {
            this.weakSwapper = (prev, next) -> VALUE.weakCompareAndSet(this, prev, next);
            this.strongSwapper = (prev, next) -> VALUE.compareAndSet(this, prev, next);
        }
        boolean dispatchIsEmpty = Lambdas.isEmpty(dispatch);
        boolean excludeOutAlwaysFalse = Boolean.FALSE.equals(Lambdas.Predicates.defaultType(excludeOut));
        this.weakSwapDispatcher = dispatchIsEmpty ?
                weakSwapper
                :
                excludeOutAlwaysFalse ?
                        (prev, next) -> {
                            boolean swapped = weakSwapper.test(prev, next);
                            if (swapped) dispatch.run();
                            return swapped;
                        }
                        :
                        (BinaryPredicate<Versioned<T>>) (prev, next) -> {
                            boolean swapped = weakSwapper.test(prev, next);
                            if (swapped && !excludeOut.test(next.value())) dispatch.run();
                            return swapped;
                        };
        this.strongSwapDispatcher = dispatchIsEmpty ?
                strongSwapper
                :
                excludeOutAlwaysFalse ?
                        (prev, next) -> {
                            boolean swapped = strongSwapper.test(prev, next);
                            if (swapped) dispatch.run();
                            return swapped;
                        }
                        :
                        (BinaryPredicate<Versioned<T>>) (prev, next) -> {
                            boolean swapped = strongSwapper.test(prev, next);
                            if (swapped && !excludeOut.test(next.value())) dispatch.run();
                            return swapped;
                        };
        if (initialValue.isDefault()) {
            this.localCache = Versioned.getDefault();
        } else {
            assert !excludeIn.test(initialValue.value(), null) : "Initial value does NOT meet pre-established requirements defined at 'excludeIn'";
            this.localCache = initialValue;
        }
        this.dispatch = dispatch;
        this.onSwapped = onSwapped;
        this.isConsumable = excludeOutAlwaysFalse ?
                () -> !localCache.isDefault()
                :
                () -> !localCache.isDefault()
                        && !excludeOut.test(localCache.value());
        this.isConsumableSupplier = excludeOutAlwaysFalse ?
                () -> {
                    Versioned<T> res;
                    return (res = localCache).isDefault() ? null : res;
                }
                :
                () -> {
                    Versioned<T> res;
                    return (res = localCache).isDefault() || excludeOut.test(res.value()) ? null : res;
                };
    }

    private static boolean isOverridden(InternalSwapped<?> onSwapped) {
        try {
            Field[] fs = onSwapped.getClass().getDeclaredFields();
            // This temporarily fixes the static default Cache creation, but if the user manages
            // to create it's own Cache outside the library, it will fail to execute the interface.
            if (fs.length == 0) return false;
            Field f = fs[0];
            f.setAccessible(true);
            Class<?> aClass = f.get(onSwapped).getClass();
            return !(aClass == Path.class
                    || aClass == In.Consume.class
                    || aClass == In.Update.class
            );
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Versioned<T> get() {
        return localCache;
    }

    boolean compareAndSwap(T expect, T set) {
        Versioned<T> prev = localCache;
        if (!prev.isDiff(expect)) {
            return strongSwapDispatcher.test(prev, prev.newValue(set));
        }
        return false;
    }

    boolean weakSet(T set) {
        Versioned<T> prev;
        return strongSwapDispatcher.test(prev = localCache, prev.newValue(set));
    }

    boolean notDefault() { return !localCache.isDefault(); }

    /**
     * @return null if not consumable
     * */
    Versioned<T> isConsumable() { return isConsumableSupplier.get(); }

    @Override
    public String toString() {
        return "Cache{" +
                "\n >>> localCache=" + localCache +
                "}@" + hashCode();
    }


    //Sources
    //Interfaces created at runtime, that capture cache's scope.
    //This way the Parent's dispatcher has direct access to this memory scope.

    In.ForUpdater<T> getUpdater() { return getUpdater(weakSwapDispatcher); }

    private In.ForUpdater<T> getUpdater(
            BinaryPredicate<Versioned<T>> dispatcher
    ) {
        return excludeIn.isAlwaysFalse() ?
                new In.ForUpdater<>() {
                    @Override
                    public T cas(UnaryOperator<T> u, BinaryOperator<T> r) {
                        Versioned<T> prev;
                        T prevT, nextT;
                        while(
                                ((prev = localCache).isDiff((nextT = u.apply((prevT = prev.value())))))
                        ) {
                            if (dispatcher.test(prev, prev.newValue(nextT))) return r.apply(prevT, nextT);
                        }
                        return null;
                    }

                    @SuppressWarnings("StatementWithEmptyBody")
                    @Override
                    public void up(UnaryOperator<T> u) {
                        Versioned<T> prev;
                        T nextT;
                        while (
                                ((prev = localCache).isDiff((nextT = u.apply(prev.value()))))
                                        && !dispatcher.test(prev, prev.newValue(nextT))
                        ) {
                        }
                    }
                }
                :
                new In.ForUpdater<>() {
                    @Override
                    public T cas(UnaryOperator<T> u, BinaryOperator<T> r) {
                        Versioned<T> prev;
                        T prevT, nextT;
                        while(
                                ((prev = localCache).isDiff((nextT = u.apply((prevT = prev.value())))))
                                        && !excludeIn.test(nextT, prevT)
                        ) {
                            if (dispatcher.test(prev, prev.newValue(nextT))) return r.apply(prevT, nextT);
                        }
                        return null;
                    }

                    @SuppressWarnings("StatementWithEmptyBody")
                    @Override
                    public void up(UnaryOperator<T> u) {
                        Versioned<T> prev;
                        T prevT, nextT;
                        while (
                                ((prev = localCache).isDiff((nextT = u.apply((prevT = prev.value())))))
                                        && !excludeIn.test(nextT, prevT)
                                        && !dispatcher.test(prev, prev.newValue(nextT))
                        ) {
                        }
                    }
                };
    }

    In.ForUpdater<T> getUpdater(
            Executors.ExecutorDelayer delayer
    ) {
        assert delayer != null;
        //We need to build a custom weakSwapDispatcher...
        final BinaryPredicate<Versioned<T>> weakSwapDispatcher = Lambdas.isEmpty(dispatch) ?
                weakSwapper
                :
                Boolean.FALSE.equals(Lambdas.Predicates.defaultType(excludeOut)) ?
//                Functions.isAlwaysFalse(excludeOut) ?
                        (prev, next) -> {
                            boolean swapped = weakSwapper.test(prev, next);
                            if (swapped) {
                                delayer.onExecute(dispatch);
                            }
                            return swapped;
                        }
                        :
                        (BinaryPredicate<Versioned<T>>) (prev, next) -> {
                            boolean swapped = weakSwapper.test(prev, next);
                            if (swapped && !excludeOut.test(next.value())) {
                                delayer.onExecute(dispatch);
                            }
                            return swapped;
                        };
        return getUpdater(weakSwapDispatcher);
    }

    /**
     * Version-based back-pressure dropping mechanism. <p>
     *      <p>
     * Recommended for rapid firing(spam) consumptions. <p>
     * Helps subside contentiousness as a result of rapid firing(spam). <p>
     * The second value to be consumed will trigger a background process. <p>
     * <p> This scheduling behaviour of this background process will be affected by the according paramters.
     *
     * <p> The value will enter a background thread with an integer value called "current". <p>
     * This integer is the result of an incrementAndGet CAS process, defined before Thread creation, and will serve as a version id. <p>
     * Once this integer enters the thread, a while loop will infer via volatile check,  <p>
     * whether contention was met in the process of thread creation. <p>
     * If contention IS met, a "NOT lesser than" (!<) test will infer whether the call deserves to continue or not. <p>
     * Finally, a compareAndSet() within {@link Cache#weakSwapDispatcher#asBoolean(Versioned, Versioned)} will break the spin lock if true. <p>
     * else the while will re-check whether the spin should be retried... or NOT if the "not lesser than"(!<) test fails. <p>
     * As a result, overlap from losing consumptions will force earlier consumptions to be dropped.<p>
     * */
    In.InStrategy.ToBooleanConsumer<T> getSource(
            Executors.ExecutorDelayer wExecutor
    ) {
        final AtomicInteger ver = new AtomicInteger(0);
        return excludeIn.isAlwaysFalse() ?
                s -> {
                    int current = ver.incrementAndGet();
                    if (!weakSwapDispatcher.test(Versioned.getDefault(), Versioned.first(s))) {
                        return wExecutor.onExecute(
                                () -> {
                                    Versioned<T> prev;
                                    while (
                                            !(current < ver.get())
                                                    && (prev = localCache).isDiff(s)
                                    ) {
                                        if (weakSwapDispatcher.test(prev, prev.newValue(s))) return;
                                    }
                                }
                        );
                    }
                    return false;
                }
                :
                s -> {
                    int current = ver.incrementAndGet();
                    Versioned<T> prePrev = localCache;
                    if (prePrev.isDefault() && !excludeIn.test(s, null)
                            && !weakSwapDispatcher.test(prePrev, Versioned.first(s))
                    ) {
                        return wExecutor.onExecute(
                                () -> {
                                    Versioned<T> prev;
                                    while (
                                            !(current < ver.get())
                                                    && (prev = localCache).isDiff(s)
                                                    && !excludeIn.test(s, prev.value())
                                    ) {
                                        if (weakSwapDispatcher.test(prev, prev.newValue(s))) return;
                                    }
                                }
                        );
                    }
                    return false;
                };
    }

    /**
     * Contentious type of entry point.
     * <p> All emissions will be computed on a background thread defined by the 'executor'.
     * */
    In.InStrategy.ToBooleanConsumer<T> getBackSource(
            Executors.ExecutorDelayer executor
    ) {
        assert executor != null;
        final AtomicInteger ver = new AtomicInteger(0);
        return excludeIn.isAlwaysFalse() ?
                s -> {
                    int current = ver.incrementAndGet();
                    return executor.onExecute(
                            () -> {
                                Versioned<T> prev;
                                while (
                                        !(current < ver.get())
                                                && (prev = localCache).isDiff(s)
                                ) {
                                    if (weakSwapDispatcher.test(prev, prev.newValue(s))) return;
                                }
                            }
                    );
                }
                :
                s -> {
                    int current = ver.incrementAndGet();
                    return executor.onExecute(
                            () -> {
                                Versioned<T> prev;
                                while (
                                        !(current < ver.get())
                                                && (prev = localCache).isDiff(s)
                                                && !excludeIn.test(s, prev.value())
                                ) {
                                    if (weakSwapDispatcher.test(prev, prev.newValue(s))) return;
                                }
                            }
                    );
                };
    }

    /**
     * {@link In.Compute} type of entry point.
     * @param executor:
     *                <p> NON NULL = All emissions will be computed on a background thread defined by the 'executor'.
     *                <p> NULL = Lock-free type of entry point.
     *                <p> concurrent emissions will be discarded.
     *                <p> computation speed {@link Callable} will be used as backpressure drop timeout.
     * @return {@link In.InStrategy.ToBooleanConsumer.Computable}
     *                * */
    In.InStrategy.ToBooleanConsumer.Computable<T> getComputable(
            Executors.ExecutorDelayer executor
    )
    {
        final AtomicInteger ver = new AtomicInteger();
        RuntimeException exception = new RuntimeException();
        return excludeIn.isAlwaysFalse() ?
                executor != null ?
                        s -> {
                            int current = ver.incrementAndGet();
                            return executor.onExecute(
                                    () -> {
                                        Versioned<T> prev;
                                        T nextCall;
                                        while (
                                                current == ver.getOpaque()
                                                        && (prev = localCache).isDiff((nextCall = getCall(exception, s)))
                                        ) {
                                            if (
                                                    current == ver.getOpaque()
                                                            && weakSwapDispatcher.test(prev, prev.newValue(nextCall))
                                            ) {
                                                return;
                                            }
                                        }
                                    }
                            );
                        } :
                        s -> {
                            int current = ver.incrementAndGet();
                            Versioned<T> prev;
                            T nextCall;
                            while (
                                    current == ver.getOpaque()
                                            && (prev = localCache).isDiff((nextCall = getCall(exception, s)))
                            ) {
                                if (
                                        current == ver.getOpaque()
                                                && weakSwapDispatcher.test(prev, prev.newValue(nextCall))
                                ) {
                                    return true;
                                }
                            }
                            return false;
                        }
                :
                executor != null ?
                        s -> {
                            int current = ver.incrementAndGet();
                            return executor.onExecute(
                                    () -> {
                                        Versioned<T> prev;
                                        T nextCall;
                                        while (
                                                current == ver.getOpaque()
                                                        && (prev = localCache).isDiff(
                                                        (nextCall = getCall(exception, s))
                                                )
                                                        && current == ver.getOpaque()
                                                        && !excludeIn.test(nextCall, prev.value())
                                        ) {
                                            if (
                                                    weakSwapDispatcher.test(prev, prev.newValue(nextCall))) {
                                                return;
                                            }
                                        }
                                    }
                            );
                        } :
                        s -> {
                            int current = ver.incrementAndGet();
                            Versioned<T> prev;
                            T nextCall;
                            while (
                                    current == ver.getOpaque()
                                            && (prev = localCache).isDiff(
                                            (nextCall = getCall(exception, s))
                                    )
                                            && current == ver.getOpaque()
                                            && !excludeIn.test(nextCall, prev.value())
                            ) {
                                if (
                                        weakSwapDispatcher.test(prev, prev.newValue(nextCall))) {
                                    return true;
                                }
                            }
                            return false;
                        };
    }

    private static <T> T getCall(Exception e, Callable<T> s) {
        try {
            return s.call();
        } catch (Exception ex) {
            e.initCause(ex);
            try {
                throw e;
            } catch (Exception exc) {
                throw new RuntimeException(exc);
            }
        }
    }

    static final String
            concurrent_error_message = "Concurrent signals should use the the contentious options. Source(boolean contentious = true)."
            , nonTraced = "\n For more information about the source of the error please set Settings.debug_mode = true before any system instantiating begins"
            , traced = "\n Error source: \n"
            ;

    In.InStrategy.ToBooleanConsumer<T> getSource()
    {
        final StackTraceElement[] es = Settings.debug_mode ? Thread.currentThread().getStackTrace() : null;
        return excludeIn.isAlwaysFalse() ?
                (s) -> {
                    Versioned<T> prev = localCache;
                    if (prev.isDiff(s)) {
                        Versioned<T> nextV = prev.newValue(s);
                        boolean swapped = strongSwapDispatcher.test(prev, nextV);
                        assert nextV == localCache :
                                es == null ?
                                        concurrent_error_message.concat(nonTraced) :
                                        concurrent_error_message.concat(traced.concat(
                                                Exceptionals.formatStack(0, es)
                                        ));
                        return swapped;
                    } return false;
                }
                :
                (s) -> {
                    Versioned<T> prev = localCache;
                    if (prev.isDiff(s) && !excludeIn.test(s, prev.value())) {
                        Versioned<T> nextV = prev.newValue(s);
                        boolean swapped = strongSwapDispatcher.test(prev, nextV);
                        assert nextV == localCache :
                                es == null ?
                                        concurrent_error_message.concat(nonTraced) :
                                        concurrent_error_message.concat(traced.concat(
                                                Exceptionals.formatStack(0, es)
                                        ));
                        return swapped;
                    } return false;
                };
    }

    @FunctionalInterface
    interface Receiver<P>
            extends Consumer<Versioned<P>> {
        Receiver<?> def = (Receiver<Object>) objectVersioned -> {};

        @SuppressWarnings("unchecked")
        static <T> Receiver<T> getDefault() {
            return (Receiver<T>) def;
        }
    }

    /**
     * {@link Function#apply(Object)} will return the value that was successfully set,
     * or null if it was unsuccessful.
     * {@link Activators.Propagator#backProp()}}
     * */
    @FunctionalInterface
    interface FirstReceiver<T, S> extends Function<Versioned<T>, Versioned<S>> {
    }

    @SuppressWarnings("unchecked")
    <Parent> CompoundReceiver<T, Parent> forMapped(Function<Parent, T> map) {
        boolean excludeIsDefault;
        if ((excludeIsDefault = excludeIn.isAlwaysFalse()) && !Lambdas.Identities.isIdentity(map)) {
            return new CompoundReceiver<>() {
                @Override
                public void accept(Versioned<Parent> versioned) {
                    Versioned<T> prev;
                    T prevT, nextT;
                    while (versioned.isNewerThanVersion((prev = localCache))) {
                        prevT = prev.value();
                        nextT = versioned.applyToVal(map);
                        if (!Objects.equals(prevT, nextT)) {
                            if (weakSwapDispatcher.test(prev, versioned.swapType(nextT))) return;
                        } else return;
                    }
                }

                @Override
                public Versioned<T> apply(Versioned<Parent> versioned) {
                    final Versioned<T> prev, next;
                    int diff;
                    if (
                            (diff = versioned.version() - (prev = localCache).version()) > 0
                                    && strongSwapper.test(prev, next = versioned.swapType(map.apply(versioned.value())))
                    ) return next;
                    else return diff == 0 ? prev : null;
                }
            };
        } else if (excludeIsDefault) { // is identity
            return (CompoundReceiver<T, Parent>) new CompoundReceiver<T, T>() {

                @Override
                public Versioned<T> apply(Versioned<T> versioned) {
                    Versioned<T> prev;
                    int diff;
                    if (
                            (diff = versioned.version() - (prev = localCache).version()) > 0
                                    && strongSwapper.test(prev, versioned)
                    ) return versioned;
                    else return diff == 0 ? prev : null;
                }

                @SuppressWarnings("StatementWithEmptyBody")
                @Override
                public void accept(Versioned<T> versioned) {
                    Versioned<T> prev;
                    while (
                            versioned.isNewerThan(prev = localCache)
                                    && prev.isDiff(versioned)
                                    && !weakSwapDispatcher.test(prev, versioned)
                    ) {
                    }
                }
            };
        } else if (!Lambdas.Identities.isIdentity(map)) { // exclude never default AND identity
            return new CompoundReceiver<>() { //never default AND map.
                @Override
                public void accept(Versioned<Parent> versioned) {
                    Versioned<T> prev;
                    T prevT, nextT;
                    while (versioned.isNewerThanVersion((prev = localCache))) {
                        prevT = prev.value();
                        nextT = versioned.applyToVal(map);
                        if (!Objects.equals(prevT, nextT) && !excludeIn.test(nextT, prevT)) {
                            if (weakSwapDispatcher.test(prev, versioned.swapType(nextT))) return;
                        } else return;
                    }
                }

                @Override
                public Versioned<T> apply(Versioned<Parent> versioned) {
                    final Versioned<T> prev;
                    final T nextValue;
                    int diff;
                    if (
                            (diff = versioned.version() - (prev = localCache).version()) > 0
                                    && !excludeIn.test(
                                    nextValue = map.apply(versioned.value()),
                                    prev.value()
                            )
                    ) {
                        // prevent extra allocation if test NOT passed.
                        Versioned<T> next;
                        if (strongSwapper.test(prev, next = versioned.swapType(nextValue))) {
                            return next;
                        }
                    }
                    return diff == 0 ? prev : null;
                }
            };
        }
        else
            return (CompoundReceiver<T, Parent>) new CompoundReceiver<T, T>() {

                @Override
                public Versioned<T> apply(Versioned<T> versioned) {
                    Versioned<T> prev;
                    int diff;
                    if (
                            (diff = versioned.version() - (prev = localCache).version()) > 0
                                    && !excludeIn.test(versioned.value(), prev.value())
                                    && strongSwapper.test(prev, versioned)
                    ) return versioned;
                    return diff == 0 ? prev : null;
                }

                @SuppressWarnings("StatementWithEmptyBody")
                @Override
                public void accept(Versioned<T> versioned) {
                    Versioned<T> prev;
                    while (
                            versioned.isNewerThan(prev = localCache)
                                    && prev.isDiff(versioned)
                                    && !excludeIn.test(versioned.value(), prev.value())
                                    && !weakSwapDispatcher.test(prev, versioned)
                    ) {
                    }
                }
            };
    }

    CompoundReceiver<T, T> hierarchicalIdentity(
    ) {
        return new HierarchicalReceiver();
    }

    <S> CompoundReceiver<T, S> hierarchicalMap(
            Function<S, T> map
    ) {
        return new HierarchicalReceiver2<>(map);
    }

    <S> CompoundReceiver<T, S> hierarchicalUpdater(
            BiFunction<T, S, T> updater
    ) {
        return new HierarchicalUpdater<>(updater);
    }

    abstract static class VersionedReceiver {

        /**WARNING, If not volatile... de-virtualization may result in bugs from JIT cache hoisting (NEVER cache promotion from processor)*/
        @SuppressWarnings("fieldMayBeFinal")
        volatile/*private*/ int parent = 0;

        static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(VersionedReceiver.class, "parent", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    interface CompoundReceiver<S, T> extends Receiver<T>, FirstReceiver<T, S> {
        CompoundReceiver<? , ?> defaultReceiver = new CompoundReceiver<>() {
            @Override
            public void accept(Versioned<Object> objectVersioned) {}

            @Override
            public Versioned<Object> apply(Versioned<Object> objectVersioned) {
                return null;
            }
        };

        @SuppressWarnings("unchecked")
        static<T, S> CompoundReceiver<T , S> getDefault() {
            return (CompoundReceiver<T, S>) defaultReceiver;
        }
    }

    class HierarchicalReceiver extends VersionedReceiver implements CompoundReceiver<T, T> {
        private final Receiver<T> core;
        private final FirstReceiver<T, T> onActive;

        public HierarchicalReceiver(
        ) {
            if (excludeIn.isAlwaysFalse()) {
                this.onActive = versioned -> {
                    int current = parent, nextV = versioned.version();
                    Versioned<T> prev = localCache;
                    T newVal = versioned.value();
                    if (prev.isDiff(newVal)) {
                        Versioned<T> next = prev.newValue(newVal);
                        if (VALUE.compareAndSet(this, current, nextV)
                                && strongSwapDispatcher.test(prev, next)
                        ) return next;
                    }
                    return null;
                };
                this.core = versioned -> {
                    int prevV, nextV = versioned.version();
                    Versioned<T> prev, next;
                    T nextT = versioned.value();
                    while (nextV > (prevV = parent)) {
                        prev = localCache;
                        if (prev.isDiff(nextT)) {
                            next = prev.newValue(nextT);
                            if (
                                    (VALUE.compareAndSet(this, prevV, nextV)
                                            && weakSwapDispatcher.test(prev, next))
                            ) return;
                        } else return;
                    }
                };
            } else {
                this.onActive = versioned -> {
                    int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T nextValue = versioned.value();
                    if (prev.isDiff(nextValue) && !excludeIn.test(nextValue, prev.value())) {
                        Versioned<T> next = prev.newValue(nextValue);
                        if (VALUE.compareAndSet(this, current, nextV)
                                && strongSwapDispatcher.test(prev, next)
                        ) return next;
                    }
                    return null;
                };
                this.core = versioned -> {
                    int prevV, nextV = versioned.version();
                    Versioned<T> prev, next;
                    T nextT = versioned.value();
                    while (nextV > (prevV = parent)) {
                        prev = localCache;
                        if (prev.isDiff(nextT) && !excludeIn.test(nextT, prev.value())) {
                            next = prev.newValue(nextT);
                            if (
                                    (VALUE.compareAndSet(this, prevV, nextV)
                                            && weakSwapDispatcher.test(prev, next))
                            ) return;
                        } else return;
                    }
                };
            }
        }

        @Override
        public Versioned<T> apply(Versioned<T> versioned) {
            return onActive.apply(versioned);
        }

        @Override
        public void accept(Versioned<T> versioned) {
            core.accept(versioned);
        }
    }

    class HierarchicalReceiver2<S> extends VersionedReceiver implements CompoundReceiver<T, S> {
        private final Receiver<S> core;
        private final FirstReceiver<S, T> onActive;

        public HierarchicalReceiver2(
                Function<S, T> map
        ) {
            if (excludeIn.isAlwaysFalse()) {
                this.onActive = versioned -> {
                    int current = parent, nextV = versioned.version();
                    Versioned<T> prev = localCache;
                    T nextT = map.apply(versioned.value());
                    if (prev.isDiff(nextT)) {
                        if (VALUE.compareAndSet(this, current, nextV)) {
                            Versioned<T> next;
                            if (strongSwapDispatcher.test(prev, next = prev.newValue(nextT))) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    int prevV, nextV = versioned.version();
                    Versioned<T> prev, next;
                    T nextT = map.apply(versioned.value());
                    while (nextV > (prevV = parent)) {
                        prev = localCache;
                        if (prev.isDiff(nextT)) {
                            next = prev.newValue(nextT);
                            if (
                                    (VALUE.compareAndSet(this, prevV, nextV)
                                            && weakSwapDispatcher.test(prev, next))
                            ) return;
                        } else return;
                    }
                };
            } else {
                this.onActive = versioned -> {
                    int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T nextValue = map.apply(versioned.value());
                    if (prev.isDiff(nextValue) && !excludeIn.test(nextValue, prev.value())) {
                        Versioned<T> next = prev.newValue(nextValue);
                        if (VALUE.compareAndSet(this, current, nextV)) {
                            if (strongSwapDispatcher.test(prev, next)) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    int prevV, nextV = versioned.version();
                    Versioned<T> prev, next;
                    T nextT = map.apply(versioned.value());
                    while (nextV > (prevV = parent)) {
                        prev = localCache;
                        if (prev.isDiff(nextT) && !excludeIn.test(nextT, prev.value())) {
                            next = prev.newValue(nextT);
                            if (
                                    (VALUE.compareAndSet(this, prevV, nextV)
                                            && weakSwapDispatcher.test(prev, next))
                            ) return;
                        } else return;
                    }
                };
            }
        }

        @Override
        public Versioned<T> apply(Versioned<S> versioned) {
            return onActive.apply(versioned);
        }

        @Override
        public void accept(Versioned<S> versioned) {
            core.accept(versioned);
        }
    }

    class HierarchicalUpdater<S> extends VersionedReceiver implements CompoundReceiver<T, S> {
        private final Receiver<S> core;
        private final FirstReceiver<S, T> silentCore;

        public HierarchicalUpdater(
                BiFunction<T, S, T> map
        ) {
            if (excludeIn.isAlwaysFalse()) {
                this.silentCore = versioned -> {
                    int current = parent, nextV = versioned.version();
                    Versioned<T> prev = localCache;
                    T nextT = map.apply(prev.value(), versioned.value());
                    if (prev.isDiff(nextT)) {
                        if (VALUE.compareAndSet(this, current, nextV)) {
                            Versioned<T> next;
                            if (strongSwapper.test(prev, next = prev.newValue(nextT))) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    int prevV, nextV = versioned.version();
                    Versioned<T> prev, next;
                    while (nextV > (prevV = parent)) {
                        prev = localCache;
                        T nextT = map.apply(prev.value(), versioned.value());
                        if (prev.isDiff(nextT)) {
                            next = prev.newValue(nextT);
                            if (
                                    (VALUE.compareAndSet(this, prevV, nextV)
                                            && weakSwapDispatcher.test(prev, next))
                            ) return;
                        } else return;
                    }
                };
            } else {
                this.silentCore = versioned -> {
                    int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T nextValue = map.apply(prev.value(), versioned.value());
                    if (prev.isDiff(nextValue) && !excludeIn.test(nextValue, prev.value())) {
                        Versioned<T> next = prev.newValue(nextValue);
                        if (VALUE.compareAndSet(this, current, nextV)) {
                            if (strongSwapper.test(prev, next)) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    int prevV, nextV = versioned.version();
                    Versioned<T> prev, next;
                    while (nextV > (prevV = parent)) {
                        prev = localCache;
                        T prevVal = prev.value();
                        T nextT = map.apply(prevVal, versioned.value());
                        if (prev.isDiff(nextT) && !excludeIn.test(nextT, prevVal)) {
                            next = prev.newValue(nextT);
                            if (
                                    (VALUE.compareAndSet(this, prevV, nextV)
                                            && weakSwapDispatcher.test(prev, next))
                            ) return;
                        } else return;
                    }
                };
            }
        }

        @Override
        public Versioned<T> apply(Versioned<S> versioned) {
            return silentCore.apply(versioned);
        }

        @Override
        public void accept(Versioned<S> versioned) {
            core.accept(versioned);
        }
    }

    /**The {@link BiFunction} is constituted as:
     * <ul>
     *     <li>
     *         first param: the current value in this cache
     *     <li>
     *         second, the parent's emission to be received.
     *     </li>
     * </ul>
     * */
    <Parent> CompoundReceiver<T, Parent>
    getJoinReceiver(BiFunction<T, Parent, T> map) {
        return new JoinReceiver<>(map);
    }

    class JoinReceiver<Parent> extends VersionedReceiver implements CompoundReceiver<T, Parent> {

        private final Receiver<Parent> core;
        private final FirstReceiver<Parent, T> onActive;

        JoinReceiver(BiFunction<T, Parent, T> map) {
            boolean neverExcludeIn = excludeIn.isAlwaysFalse();
            this.core = neverExcludeIn ?
                    versioned -> {
                        int parentV = versioned.version(), thisV;
                        Versioned<T> prevV;
                        T next;
                        while (
                                parentV > (thisV = parent)
                                        && (prevV = localCache).isDiff(next = map.apply(prevV.value(), versioned.value()))
                        ) {
                            if (weakSwapDispatcher.test(prevV, prevV.newValue(next))
                                    && VALUE.compareAndSet(this, thisV, parentV)) {
                                return;
                            }
                        }
                    }
                    :
                    versioned -> {
                        int parentV = versioned.version(), thisV;
                        Versioned<T> prevV;
                        T next;
                        while (
                                parentV > (thisV = parent)
                                        && (prevV = localCache).isDiff(next = map.apply(prevV.value(), versioned.value()))
                                        && !excludeIn.test(next, prevV.value())
                        ) {
                            if (weakSwapDispatcher.test(prevV, prevV.newValue(next))
                                    && VALUE.compareAndSet(this, thisV, parentV))
                                return;
                        }
                    };

            this.onActive = neverExcludeIn ?
                    versioned -> {
                        int parentV = versioned.version(), thisV;
                        Versioned<T> prevV;
                        T next;
                        if (
                                parentV > (thisV = parent)
                                        && (prevV = localCache).isDiff(next = map.apply(prevV.value(), versioned.value()))
                        ) {
                            Versioned<T> nextV;
                            if (strongSwapDispatcher.test(prevV, nextV = prevV.newValue(next))
                                    && VALUE.compareAndSet(this, thisV, parentV)
                            ) {
                                return nextV;
                            }
                        }
                        return null;
                    }
                    :
                    versioned -> {
                        int parentV = versioned.version(), thisV;
                        Versioned<T> prevV;
                        T next;
                        if (
                                parentV > (thisV = parent)
                                        && (prevV = localCache).isDiff(next = map.apply(prevV.value(), versioned.value()))
                                        && !excludeIn.test(next, prevV.value())
                        ) {
                            Versioned<T> nextV;
                            if (strongSwapDispatcher.test(prevV, nextV = prevV.newValue(next))
                                    && VALUE.compareAndSet(this, thisV, parentV)
                            ) return nextV;
                        }
                        return null;
                    };
        }

        @Override
        public Versioned<T> apply(Versioned<Parent> parentVersioned) {
            return onActive.apply(parentVersioned);
        }

        @Override
        public void accept(Versioned<Parent> versioned) {
            core.accept(versioned);
        }
    }

    public String toStringDetailed() {
        return "<Cache@" + hashCode() + "{" +
                "\n localCache=" + localCache.toStringDetailed() +
                ",\n versionTest=" + (excludeIn.isAlwaysFalse() ? "DEFAULT EVALUATION TEST" : excludeIn) +
                "\n}@" + hashCode() + "/>";
    }
}




// The system is not perfect.
// And even if we declare that its functional contradictions are a central tenet.
// The longer the system keeps running, the more these contradictions exacerbate it.
// One could argue that the lower nodes not receiving the entirety of the emissions consumed at the top is the issue,
// But the imperfect trickle down is not the culprit.
// The top is metastasizing at all levels of distribution management.
// The contradiction that was supposed to be instrumental, is now becoming the goal.
// Soon the damage will be irreparable.
// The longer it keeps running, the greater the technical debt.
// We need a new semantic.
// One that would trigger generalization.
// A new semantic that synchronizes the unaligned.
// That resolves contradictions.
// A Human and humane generalized model.
// Only by solving the human technical debt, can we move forward... doing so at break neck speed.
// Their freedom is our freedom.
// Their struggle, our struggle.
// One cause.

// "The camp is like a tall eminent tree.
// The tree has leaves, and each leaf of the tree bears the name of a martyr
// Even if they break a few branches,
// Others shall grow in their place.
// They were not able to reach the top of the tree.
// My greatest wish?
// My greatest wish is to go back home."
//                                                                    -N.J.

// We will get there.