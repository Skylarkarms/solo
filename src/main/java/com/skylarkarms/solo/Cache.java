package com.skylarkarms.solo;

import com.skylarkarms.concur.Executors;
import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.BinaryPredicate;
import com.skylarkarms.lambdas.Exceptionals;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.Predicates;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
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
            /**
             * May fail under contention, even if `prev` coincides.
             * */
            weakSwapper,
            strongSwapper,
            /**
             * May fail under contention, even if `prev` coincides.
             * */
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
        @SuppressWarnings("unused")
        boolean compareAndSwap(T expect, T set);
        /**
         * Attempts to set the value.
         * <p> May fail under contention.
         * <p> It applies a strongCAS which means that
         * this will never fail for something other than contention alone.
         * */
        @SuppressWarnings("unused")
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
                () -> {
                    Versioned<T> res = localCache;
                    return !res.isDefault() && !excludeOut.test(res.value());
                };
        this.isConsumableSupplier = excludeOutAlwaysFalse ?
                () -> {
                    Versioned<T> res = localCache;
                    return res.isDefault() ? null : res;
                }
                :
                () -> {
                    Versioned<T> res = localCache;
                    return res.isDefault() || excludeOut.test(res.value()) ? null : res;
                };
    }

    private static boolean isOverridden(InternalSwapped<?> onSwapped) {
        try {
            Field[] fs = onSwapped.getClass().getDeclaredFields();
            // This temporarily fixes the static default Cache creation, but if the user manages
            // to create its own Cache outside the library, it will fail to execute the interface.
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


    // - Sources.
    // - Interfaces created at runtime, that capture cache's scope.
    //This way the Parent's dispatcher has direct access to this memory scope.

    In.ForUpdater<T> getUpdater() { return getUpdater(weakSwapDispatcher); }

    // swap to explicit assignment for faster bytecode.
    private In.ForUpdater<T> getUpdater(
            BinaryPredicate<Versioned<T>> weakDispatcher // as of now... all dispatchers are "weak" (spurious failures)
    ) {
        return excludeIn.isAlwaysFalse() ?
                new In.ForUpdater<>() {
                    @Override
                    public T cas(UnaryOperator<T> u, BinaryOperator<T> r) {
                        Versioned<T> prev = localCache;
                        final T prevT = prev.value();
                        T nextT = u.apply(prevT);

                        if (
                                (prevT != nextT) && (prevT == null || !prevT.equals(nextT))
                        ) {
                           Versioned<T> next = prev.newValue(nextT);
                           while (!weakDispatcher.test(prev, next)) {
                               Versioned<T> wit = localCache;
                               if (wit != prev) {
                                   T w_v = wit.value();
                                   if (
                                           (w_v == nextT) || (w_v != null && w_v.equals(nextT))
                                   ) return null;
                                   prev = wit;
                                   nextT = u.apply(wit.value());
                                   next = prev.newValue(nextT);
                               }
                           }
                           return r.apply(prev.value(), nextT);
                        } else return null;

                    }

                    @Override
                    public void up(UnaryOperator<T> u) {
                        Versioned<T> prev = localCache;
                        final T prevT = prev.value();
                        T nextT = u.apply(prevT);

                        if (
                                (prevT != nextT) && (prevT == null || !prevT.equals(nextT))
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            while (!weakDispatcher.test(prev, next)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) {
                                    T w_v = wit.value();
                                    if (
                                            (w_v == nextT) || (w_v != null && w_v.equals(nextT))
                                    ) return;
                                    prev = wit;
                                    nextT = u.apply(wit.value());
                                    next = prev.newValue(nextT);
                                }
                            }
                        }
                    }
                }
                :
                new In.ForUpdater<>() {
                    @Override
                    public T cas(UnaryOperator<T> u, BinaryOperator<T> r) {

                        Versioned<T> prev = localCache;
                        final T prevT = prev.value();
                        T nextT = u.apply(prevT);

                        if (
                                ((prevT != nextT) && (prevT == null || !prevT.equals(nextT)))
                                        && !excludeIn.test(nextT, prevT)
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            while (!weakDispatcher.test(prev, next)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) {
                                    T w_v = wit.value();
                                    if (
                                            ((w_v == nextT) || (w_v != null && w_v.equals(nextT)))
                                            ||
                                                    excludeIn.test(nextT, w_v)
                                    ) return null;
                                    prev = wit;
                                    nextT = u.apply(wit.value());
                                    next = prev.newValue(nextT);
                                }
                            }
                            return r.apply(prev.value(), nextT);
                        } else return null;
                    }

                    @Override
                    public void up(UnaryOperator<T> u) {

                        Versioned<T> prev = localCache;
                        final T prevT = prev.value();
                        T nextT = u.apply(prevT);

                        if (
                                ((prevT != nextT) && (prevT == null || !prevT.equals(nextT)))
                                        && !excludeIn.test(nextT, prevT)

                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            while (!weakDispatcher.test(prev, next)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) {
                                    T w_v = wit.value();
                                    if (
                                            ((w_v == nextT) || (w_v != null && w_v.equals(nextT)))
                                            ||
                                                    excludeIn.test(nextT, w_v)
                                    ) return;
                                    prev = wit;
                                    nextT = u.apply(wit.value());
                                    next = prev.newValue(nextT);
                                }
                            }
                        }
                    }
                };
    }

    In.ForUpdater<T> getUpdater(
            Executors.BaseExecutor delayer
    ) {
        assert delayer != null;
        //We need to build a custom weakSwapDispatcher...
        final BinaryPredicate<Versioned<T>> weakSwapDispatcher = Lambdas.isEmpty(dispatch) ?
                weakSwapper
                :
                Boolean.FALSE.equals(Lambdas.Predicates.defaultType(excludeOut)) ?
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
     * <p> This scheduling behaviour of this background process will be affected by the according parameters.
     *
     * <p> The value will enter a background thread with an integer value called "current". <p>
     * This integer is the result of an incrementAndGet CAS process, defined before Thread creation, and will serve as a version id. <p>
     * Once this integer enters the thread, a while loop will infer via volatile check,  <p>
     * whether contention was met in the process of thread creation. <p>
     * If contention IS met, a "NOT lesser than" test will infer whether the call deserves to continue or not. <p>
     * Finally, a compareAndSet() within {@link Cache#weakSwapDispatcher} ({@link BinaryPredicate#test(Object, Object)}) will break the spin lock if true. <p>
     * else the while will re-check whether the spin should be retried... or NOT if the "not lesser than" test fails. <p>
     * As a result, overlap from losing consumptions will force earlier consumptions to be dropped.<p>
     * */
    In.InStrategy.ToBooleanConsumer<T> getSource(
            Executors.BaseExecutor wExecutor
    ) {
        final AtomicInteger ver = new AtomicInteger(0);
        return excludeIn.isAlwaysFalse() ?
                s -> {
                    final int current = ver.incrementAndGet();
                    Versioned<T> first = localCache;
                    if (first.isDefault()) {
                        if (!strongSwapDispatcher.test(first, Versioned.first(s))) {
                            return current == ver.get() && wExecutor.onExecute(
                                    () -> {
                                        // Even tho this alternative seems fine, the cost of having:
                                        // a) dup_2 +
                                        // b) volatile int indirection from AtomicInteger
                                        // completely defeats the short-circuiting after the `&&`
                                        // This option would be convenient ONLY IF current IS lesser MORE OFTEN THAN NOT...
                                        // This is rare... so this is sub-optimal
//                                    Versioned<T> prev;
//                                    while (
//                                            !(current < ver.get())
//                                                    && (prev = localCache).isDiff(s)
//                                    ) {
//                                        if (weakSwapDispatcher.test(prev, prev.newValue(s))) return;
//                                    }

                                        if (current == ver.get()) {
                                            Versioned<T> prev = localCache;
                                            T prev_v = prev.value();
                                            if (
                                                    (prev_v != s) && (prev_v == null || !prev_v.equals(s)) //embedded non-equals
                                            ) {
                                                Versioned<T> next = prev.newValue(s);
                                                while (!weakSwapDispatcher.test(prev, next)) {
                                                    Versioned<T> wit = localCache;
                                                    if (wit != prev) {
                                                        if (current == ver.get()) {
                                                            T w_v = wit.value();
                                                            if (((w_v == s) || (w_v != null && w_v.equals(s)))
                                                            ) return;
                                                            prev = wit;
                                                            next = wit.newValue(s);
                                                        } else return;
                                                    }
                                                }
                                            }
                                        }
                                    }
                            );
                        } else return true;
                    } else {
                        return current == ver.get() && wExecutor.onExecute(
                                () -> {
                                    if (current == ver.get()) {
                                        Versioned<T> prev = localCache;
                                        T prev_v = prev.value();
                                        if (
                                                (prev_v != s) && (prev_v == null || !prev_v.equals(s))
                                        ) {
                                            Versioned<T> next = prev.newValue(s);
                                            while (!weakSwapDispatcher.test(prev, next)) {
                                                Versioned<T> wit = localCache;
                                                if (wit != prev) {
                                                    if (current == ver.get()) {
                                                        T w_v = wit.value();
                                                        if (((w_v == s) || (w_v != null && w_v.equals(s)))
                                                        ) return;
                                                        prev = wit;
                                                        next = wit.newValue(s);
                                                    } else return;
                                                }
                                            }
                                        }
                                    }
                                }
                        );
                    }
                }
                :
                s -> {
                    final int current = ver.incrementAndGet();
                    Versioned<T> prePrev = localCache;
                    if (prePrev.isDefault()) {
                        if (!excludeIn.test(s, null)) {
                            if (!strongSwapDispatcher.test(prePrev, Versioned.first(s))) {
                                return current == ver.get() && wExecutor.onExecute(
                                        () -> {
                                            if (current == ver.get()) {
                                                Versioned<T> prev = localCache;
                                                T val = prev.value();
                                                if (
                                                        ((val != s) && (val == null || !val.equals(s)))
                                                                && !excludeIn.test(s, val)
                                                ) {
                                                    Versioned<T> newVal = prev.newValue(s);
                                                    while (!weakSwapDispatcher.test(prev, newVal)) {
                                                        Versioned<T> witness = localCache;
                                                        if (witness != prev) {
                                                            if (current == ver.get()) {
                                                                T w_val = witness.value();
                                                                if (((w_val == s) || (w_val != null && w_val.equals(s)))
                                                                        || excludeIn.test(s, w_val)
                                                                ) return;
                                                                prev = witness;
                                                                newVal = prev.newValue(s);
                                                            } else return;
                                                        }
                                                    }
                                                }

                                            }
                                        }
                                );
                            } else return true;
                        } else return false;
                    } else {
                        return current == ver.get() && wExecutor.onExecute(
                                () -> {
                                    if (current == ver.get()) {
                                        Versioned<T> prev = localCache;
                                        T val = prev.value();
                                        if (
                                                ((val != s) && (val == null || !val.equals(s)))
                                                        && !excludeIn.test(s, val)
                                        ) {
                                            Versioned<T> newVal = prev.newValue(s);
                                            while (!weakSwapDispatcher.test(prev, newVal)) {
                                                Versioned<T> witness = localCache;
                                                if (witness != prev) {
                                                    if (current == ver.get()) {
                                                        T w_val = witness.value();
                                                        if (
                                                                ((w_val == s) || (w_val != null && w_val.equals(s)))
                                                                        || excludeIn.test(s, w_val)
                                                        ) return;
                                                        prev = witness;
                                                        newVal = prev.newValue(s);
                                                    } else return;
                                                }
                                            }
                                        }

                                    }
                                }
                        );
                    }
                };
    }

    /**
     * Contentious type of entry point.
     * <p> All emissions will be computed on a background thread defined by the 'executor'.
     * */
    In.InStrategy.ToBooleanConsumer<T> getBackSource(
            Executors.BaseExecutor executor
    ) {
        assert executor != null;
        final AtomicInteger ver = new AtomicInteger();
        return excludeIn.isAlwaysFalse() ?
                s -> {
                    final int current = ver.incrementAndGet();
                    return current == ver.get() && executor.onExecute(
                            () -> {
                                if (current == ver.get()) {
                                    Versioned<T> prev = localCache;
                                    T p_v = prev.value();
                                    if ((p_v != s) && (p_v == null || !p_v.equals(s))) {
                                        Versioned<T> next = prev.newValue(s);
                                        while (!weakSwapDispatcher.test(prev, next))
                                        {
                                            Versioned<T> witness = localCache;
                                            if (witness != prev) {
                                                if (current == ver.get()) {
                                                    T w_v = witness.value();
                                                    if (
                                                            (w_v == s) || (w_v != null && w_v.equals(s))
                                                    ) return;
                                                    prev = witness;
                                                    next = prev.newValue(s);
                                                } else return;
                                            }
                                        }
                                    }
                                }
                            }
                    );
                }
                :
                s -> {
                    final int current = ver.incrementAndGet();
                    return current == ver.get() && executor.onExecute(
                            () -> {
                                if (current == ver.get()) {
                                    Versioned<T> prev = localCache;
                                    T p_v = prev.value();
                                    if (
                                            ((p_v != s) && (p_v == null || !p_v.equals(s)))
                                                    && !excludeIn.test(s, p_v)
                                    ) {
                                        Versioned<T> next = prev.newValue(s);
                                        while (!weakSwapDispatcher.test(prev, next))
                                        {
                                            Versioned<T> wit = localCache;
                                            if (wit != prev) {
                                                if (current == ver.get()) {
                                                    T w_v = wit.value();
                                                    if (
                                                            ((w_v == s) || (w_v != null && w_v.equals(s)))
                                                                    || excludeIn.test(s, w_v)
                                                    ) return;
                                                    prev = wit;
                                                    next = prev.newValue(s);
                                                } else return;
                                            }
                                        }

                                    }
                                }
                            }
                    );
                };
    }

    /**
     * {@link In.Compute} type of entry point.
     * @param executor
     *                <p> NON-NULL = All emissions will be computed on a background thread defined by the 'executor'.
     *                <p> NULL = Lock-free type of entry point.
     *                <p> concurrent emissions will be discarded.
     *                <p> computation speed {@link Callable} will be used as backpressure drop timeout.
     * @return {@link In.InStrategy.ToBooleanConsumer.Computable}
     *                * */
    In.InStrategy.ToBooleanConsumer.Computable<T> getComputable(
            Executors.BaseExecutor executor
    )
    {
        final AtomicInteger ver = new AtomicInteger();
        StackTraceElement[] es = Settings.DEBUG_MODE.ref ? Thread.currentThread().getStackTrace() : null;
        return excludeIn.isAlwaysFalse() ?
                executor != null ?
                        s -> {
                            final int current = ver.incrementAndGet();
                            return current == ver.get() && executor.onExecute(
                                    () -> {
                                        if (current == ver.get()) {
                                            T nextCall = getCall(es, s);
                                            Versioned<T> prev = localCache;
                                            T p_v = prev.value();
                                            if (
                                                    (p_v != nextCall) && (p_v == null || !p_v.equals(nextCall))
                                            ) {
                                                Versioned<T> next = prev.newValue(nextCall);
                                                while (!weakSwapDispatcher.test(prev, next)) {
                                                    Versioned<T> wit = localCache;
                                                    if (wit != prev) {
                                                        if (current == ver.get()) {
                                                            T w_t = wit.value();
                                                            if (
                                                                    (w_t == nextCall) || (w_t != null && w_t.equals(nextCall))
                                                            ) return;
                                                            prev = wit;
                                                            next = wit.newValue(nextCall);
                                                        } else return;
                                                    }
                                                }
                                            }
                                        }
                                    }
                            );
                        } :
                        s -> {
                            final int current = ver.incrementAndGet();

                            Versioned<T> prev = localCache;
                            T nextCall = getCall(es, s);
                            T p_v = prev.value();

                            // we should check for version inside while loop after the fist CAS-miss since
                            // there is no real reason to believe that we may be in a concurrent environment besides the first miss.

                            if (
                                    (p_v != nextCall) && (p_v == null || !p_v.equals(nextCall))
                            ) {
                                Versioned<T> next = prev.newValue(nextCall);
                                while (
                                        !weakSwapDispatcher.test(prev, next)
                                ) {
                                    Versioned<T> wit = localCache;
                                    if (wit != prev) {
                                        if (current == ver.get()) {
                                            T w_t = wit.value();
                                            if (
                                                    (w_t == nextCall) || (w_t != null && w_t.equals(nextCall))
                                            ) return false;
                                            prev = wit;
                                            next = wit.newValue(nextCall);
                                        } else return false;
                                    }
                                }
                                return true;
                            }
                            return false;
                        }
                :
                executor != null ?
                        s -> {
                            int current = ver.incrementAndGet();
                            return current == ver.get() && executor.onExecute(
                                    () -> {
                                        if (current == ver.get()) {
                                            T nextCall = getCall(es, s);
                                            Versioned<T> prev = localCache;
                                            T p_v = prev.value();
                                            if (
                                                    ((p_v != nextCall) && (p_v == null || !p_v.equals(nextCall)))
                                                            &&
                                                            !excludeIn.test(nextCall, p_v)
                                            ) {
                                                Versioned<T> next = prev.newValue(nextCall);
                                                while (!weakSwapDispatcher.test(prev, next)) {
                                                    Versioned<T> wit = localCache;
                                                    if (wit != prev) {
                                                        if (current == ver.get()) {
                                                            T w_t = wit.value();
                                                            if (
                                                                    ((w_t == nextCall) || (w_t != null && w_t.equals(nextCall)))
                                                                            || excludeIn.test(nextCall, w_t)

                                                            ) return;
                                                            prev = wit;
                                                            next = wit.newValue(nextCall);
                                                        } else return;
                                                    }
                                                }
                                            }
                                        }
                                    }
                            );
                        } :
                        s -> {
                            final int current = ver.incrementAndGet();

                            Versioned<T> prev = localCache;
                            T nextCall = getCall(es, s);
                            T p_v = prev.value();

                            // we should check for version inside while loop after the fist CAS-miss since
                            // there is no real reason to believe that we may be in a concurrent environment besides the first miss.

                            if (
                                    ((p_v != nextCall) && (p_v == null || !p_v.equals(nextCall)))
                                            && !excludeIn.test(nextCall, p_v)
                            ) {
                                Versioned<T> next = prev.newValue(nextCall);
                                while (
                                        !weakSwapDispatcher.test(prev, next)
                                ) {
                                    Versioned<T> wit = localCache;
                                    if (wit != prev) {
                                        if (current == ver.get()) {
                                            T w_t = wit.value();
                                            if (
                                                    ((w_t == nextCall) || (w_t != null && w_t.equals(nextCall)))
                                                            || excludeIn.test(nextCall, w_t)
                                            ) return false;
                                            prev = wit;
                                            next = wit.newValue(nextCall);
                                        } else return false;
                                    }
                                }
                                return true;
                            }
                            return false;
                        };
    }

    private static <T> T getCall(StackTraceElement[] es, Callable<T> s) {
        try {
            return s.call();
        } catch (Exception ex) {
            throw es != null ? new RuntimeException(
                    "provenance = ".concat(Exceptionals.formatStack(0, es)),
                    ex
            ) : new RuntimeException(ex);
        }
    }

    static final String
            concurrent_error_message = "Concurrent signals should use the the contentious options. Source(boolean contentious = true)."
            , nonTraced = "\n For more information about the source of the error please set Settings.debug_mode = true before any system instantiating begins"
            , traced = "\n Error source: \n"
            ;

    In.InStrategy.ToBooleanConsumer<T> getSource()
    {
        final StackTraceElement[] es = Settings.DEBUG_MODE.ref ? Thread.currentThread().getStackTrace() : null;
        return excludeIn.isAlwaysFalse() ?
                (s) -> {
                    Versioned<T> prev = localCache;
                    T p_v = prev.value();
                    if ((p_v != s) && (p_v == null || !p_v.equals(s))) {
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
                    T p_v = prev.value();
                    if (
                            ((p_v != s) && (p_v == null || !p_v.equals(s)))
                                    && !excludeIn.test(s, p_v)) {
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
        static <T> Receiver<T> getDefault() { return (Receiver<T>) def; }
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
        final boolean excludeIsDefault = excludeIn.isAlwaysFalse();
        if (excludeIsDefault && !Lambdas.Identities.isIdentity(map)) {
            return new CompoundReceiver<>() {
                @Override
                public void accept(Versioned<Parent> versioned) {
                    Versioned<T> prev = localCache;
                    final int v_ver = versioned.version();
                    if (v_ver > prev.version()) {
                        T nextT = versioned.applyToVal(map);
                        final T p_v = prev.value();
                        if (
                                (p_v != nextT) && (p_v == null || !p_v.equals(nextT))
                        ) {
                            Versioned<T> next = versioned.swapType(nextT);
                            while (!weakSwapDispatcher.test(prev, next)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) { //TRUE failure...
                                    if (v_ver > wit.version()) {
                                        // a true failure must re-assign
                                        T c_v = wit.value();
                                        if (
                                                (c_v == nextT) || (c_v != null && c_v.equals(nextT))
                                        ) return;
                                        prev = wit;
                                    } else return;
                                }
                            }
                        }
                    }
                }

                @Override
                public Versioned<T> apply(Versioned<Parent> versioned) {
                    final Versioned<T> prev = localCache, next;
                    int diff = versioned.version() - prev.version();
                    if (
                            diff > 0
                                    && strongSwapper.test(prev, next = versioned.swapType(map.apply(versioned.value())))
                    ) return next;
                    else return diff == 0 ? prev : null;
                }
            };
        } else if (excludeIsDefault) { // is identity
            return (CompoundReceiver<T, Parent>) new CompoundReceiver<T, T>() {

                @Override
                public Versioned<T> apply(Versioned<T> versioned) {
                    Versioned<T> prev = localCache;
                    int diff = versioned.version() - prev.version();
                    if (
                            diff > 0
                                    && strongSwapper.test(prev, versioned)
                    ) return versioned;
                    else return diff == 0 ? prev : null;
                }

                @Override
                public void accept(Versioned<T> versioned) {
                    Versioned<T> prev = localCache;
                    final int v_ver = versioned.version();
                    if (v_ver > prev.version()) {
                        final T p_v = prev.value();
                        final  T v_v = versioned.value();
                        if ((p_v != v_v) && (p_v == null || !p_v.equals(v_v))) {
                            while (!weakSwapDispatcher.test(prev, versioned)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) {
                                    if (v_ver > wit.version()) {
                                        T w_v = wit.value();
                                        if ((w_v == v_v) || (w_v != null && w_v.equals(v_v))) return;
                                        prev = wit;
                                    } else return;
                                }
                            }
                        }
                    }
                }
            };
        } else if (!Lambdas.Identities.isIdentity(map)) { // exclude never default AND identity
            return new CompoundReceiver<>() { //never default AND map.
                @Override
                public void accept(Versioned<Parent> versioned) {
                    Versioned<T> prev = localCache;
                    final int v_ver = versioned.version();
                    if (v_ver > prev.version()) {
                        final T prevT = prev.value(), nextT = versioned.applyToVal(map);
                        if (
                                ((prevT != nextT) && (prevT == null || !prevT.equals(nextT)))
                                && !excludeIn.test(nextT, prevT)
                        ) {
                            Versioned<T> next = versioned.swapType(nextT);
                            while (!weakSwapDispatcher.test(prev, next)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) {
                                    if (v_ver > wit.version()) {
                                        T w_t = wit.value();
                                        if (
                                                ((w_t == nextT) || (w_t != null && w_t.equals(nextT)))
                                                        || excludeIn.test(nextT, w_t)
                                        ) return;
                                        prev = wit;
                                    } else return;
                                }
                            }
                        }
                    }

                }

                @Override
                public Versioned<T> apply(Versioned<Parent> versioned) {
                    final Versioned<T> prev = localCache;
                    final T nextValue = map.apply(versioned.value());
                    int diff = versioned.version() - prev.version();
                    if (
                            diff > 0 && !excludeIn.test(nextValue, prev.value())
                    ) {
                        // prevent extra allocation if test NOT passed.
                        Versioned<T> next = versioned.swapType(nextValue);
                        if (strongSwapper.test(prev, next)) return next;
                    }
                    return diff == 0 ? prev : null;
                }
            };
        }
        else
            return (CompoundReceiver<T, Parent>) new CompoundReceiver<T, T>() {

                @Override
                public Versioned<T> apply(Versioned<T> versioned) {
                    final Versioned<T> prev = localCache;
                    int diff = versioned.version() - prev.version();
                    if (
                            diff > 0
                                    && !excludeIn.test(versioned.value(), prev.value())
                                    && strongSwapper.test(prev, versioned)
                    ) return versioned;
                    return diff == 0 ? prev : null;
                }

                @Override
                public void accept(Versioned<T> versioned) {
                    Versioned<T> prev = localCache;
                    final int v_ver = versioned.version();
                    if (v_ver > prev.version()) {
                        final T prev_val = prev.value(), ver_val = versioned.value();
                        if (
                                ((prev_val != ver_val) && (prev_val == null || !prev_val.equals(ver_val)))
                                        && !excludeIn.test(versioned.value(), prev.value())

                        ) {
                            while (!weakSwapDispatcher.test(prev, versioned)) {
                                Versioned<T> wit = localCache;
                                if (wit != prev) {
                                    if (v_ver > wit.version()) {
                                        T w_v = wit.value();
                                        if (
                                                ((w_v == ver_val) || (w_v != null && w_v.equals(ver_val)))
                                                        || excludeIn.test(versioned.value(), wit.value())
                                        ) return;

                                        prev = wit;
                                    } else return;
                                }
                            }
                        }
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
        volatile int parent = 0;

        static final VarHandle PARENT;

        static {
            try {
                PARENT = MethodHandles.lookup().findVarHandle(VersionedReceiver.class, "parent", int.class);
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
                    final int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T p_v = prev.value();
                    final T newVal = versioned.value();
                    if ((p_v != newVal) && (p_v == null || !p_v.equals(newVal))) {
                        Versioned<T> next = prev.newValue(newVal);
                        if (PARENT.compareAndSet(this, current, nextV)
                                && strongSwapDispatcher.test(prev, next)
                        ) return next;
                    }
                    return null;
                };
                this.core = versioned -> {
                    // When parent is set... weakDispatcher should reattempt until success... OR STOP if witness parent increases during re-attempt
                    // Higher initial throughput is allowed by not relying on atomicInteger, since it allows us to load
                    // `localCache` and map `next` before strongCASing on the int.
                    // this "breathing room" should allow for MORE context-switches, resulting in more backpressure drops
                    // benefiting the downstream, with the tradeoff of some extra computing during this step,
                    // for all the mappings of `next` between `parent` load and `parent` store.
//                    final int nextV = versioned.version();
//                    int prevV = parent;
//                    Versioned<T> prev, next;
//                    T nextT = versioned.value();
//                    while (nextV > prevV) {
//                        prev = localCache;
//                        if (prev.isDiff(nextT)) {
//                            next = prev.newValue(nextT);
//                            if (
//                                    PARENT.compareAndSet(this, prevV, nextV) //better than compareAndExchange + cast + dup_2
//                            ) {
//                                while (!weakSwapDispatcher.test(prev, next)) {
//                                    Versioned<T> wit = localCache;
//                                    if (prev != wit) {
//                                        if (!wit.isDiff(nextT) || nextV != parent) return;
//                                        prev = wit;
//                                        next = wit.newValue(nextT);
//                                    }
//                                }
//                                return;
//                            } else prevV = parent;
//                        } else return;
//                    }

                    // Another alternative which performs fewer loads
                    // from localCache... less re-mappings of next and allows super
                    // late assignments preventing wasted loads.
                    final int nextV = versioned.version();
                    int prevV = parent;
                    if (nextV > prevV) {
                        Versioned<T> prev = localCache;
                        final T p_v = prev.value();
                        final T nextT = versioned.value();
                        if ((p_v != nextT) && (p_v == null || !p_v.equals(nextT))) {
                            Versioned<T> next = prev.newValue(nextT);
                            do {
                                if (
                                        PARENT.compareAndSet(this, prevV, nextV) //better than compareAndExchange + cast + dup_2
                                ) {
                                    while (!weakSwapDispatcher.test(prev, next)) {
                                        Versioned<T> wit = localCache;
                                        if (prev != wit) {
                                            if (nextV == parent) {
                                                T w_v = wit.value();
                                                if ((w_v == nextT) || (w_v != null && w_v.equals(nextT))) return;
                                                prev = wit;
                                                next = wit.newValue(nextT);
                                            } else return;
                                        }
                                    }
                                    return;
                                }
                                prevV = parent;
                            } while (nextV > prevV);
                        }
                    }
                };
            } else {
                this.onActive = versioned -> {
                    final int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T p_v = prev.value();
                    final T nextValue = versioned.value();
                    if (
                            ((p_v != nextValue) && (p_v == null || !p_v.equals(nextValue)))
                                    && !excludeIn.test(nextValue, prev.value())
                    ) {
                        Versioned<T> next = prev.newValue(nextValue);
                        if (PARENT.compareAndSet(this, current, nextV)
                                && strongSwapDispatcher.test(prev, next)
                        ) return next;
                    }
                    return null;
                };
                this.core = versioned -> {
                    final int nextV = versioned.version();
                    int prevV = parent;
                    if (nextV > prevV) {
                        Versioned<T> prev = localCache;
                        final T p_v = prev.value();
                        final T nextT = versioned.value();
                        if (
                                ((p_v != nextT) && (p_v == null || !p_v.equals(nextT)))
                                && !excludeIn.test(nextT, p_v)
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            do {
                                if (
                                        PARENT.compareAndSet(this, prevV, nextV) //better than compareAndExchange + cast + dup_2
                                ) {
                                    while (!weakSwapDispatcher.test(prev, next)) {
                                        Versioned<T> wit = localCache;
                                        if (prev != wit) {
                                            if (nextV == parent) {
                                                T w_v = wit.value();
                                                if (
                                                        ((w_v == nextT) || (w_v != null && w_v.equals(nextT)))
                                                        || excludeIn.test(nextT, w_v)
                                                ) return;
                                                prev = wit;
                                                next = wit.newValue(nextT);
                                            } else return;
                                        }
                                    }
                                    return;
                                }
                                prevV = parent;
                            } while (nextV > prevV);
                        }
                    }
                };
            }
        }

        @Override
        public Versioned<T> apply(Versioned<T> versioned) { return onActive.apply(versioned); }

        @Override
        public void accept(Versioned<T> versioned) { core.accept(versioned); }
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
                    T p_v = prev.value();
                    T nextT = map.apply(versioned.value());
                    if ((p_v != nextT) && (p_v == null || !p_v.equals(nextT))) {
                        if (PARENT.compareAndSet(this, current, nextV)) {
                            Versioned<T> next = prev.newValue(nextT);
                            if (strongSwapDispatcher.test(prev, next)) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    final int nextV = versioned.version();
                    int prevV = parent;
                    if (nextV > prevV) {
                        Versioned<T> prev = localCache;
                        final T p_v = prev.value();
                        final T nextT = map.apply(versioned.value());
                        if (
                                (p_v != nextT) && (p_v == null || !p_v.equals(nextT))
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            do {
                                if (
                                        PARENT.compareAndSet(this, prevV, nextV) //better than compareAndExchange + cast + dup_2
                                ) {
                                    while (!weakSwapDispatcher.test(prev, next)) {
                                        Versioned<T> wit = localCache;
                                        if (prev != wit) {
                                            if (nextV == parent) {
                                                T w_v = wit.value();
                                                if ((w_v == nextT) || (w_v != null && w_v.equals(nextT))) return;
                                                prev = wit;
                                                next = wit.newValue(nextT);
                                            } else return;
                                        }
                                    }
                                    return;
                                }
                                prevV = parent;
                            } while (nextV > prevV);
                        }
                    }
                };
            } else {
                this.onActive = versioned -> {
                    int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T p_v = prev.value();
                    final T nextValue = map.apply(versioned.value());
                    if (
                            ((p_v != nextValue) && (p_v == null || !p_v.equals(nextValue)))
                                    && !excludeIn.test(nextValue, p_v)
                    ) {
                        Versioned<T> next = prev.newValue(nextValue);
                        if (PARENT.compareAndSet(this, current, nextV)) {
                            if (strongSwapDispatcher.test(prev, next)) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    final int nextV = versioned.version();
                    int prevV = parent;
                    if (nextV > prevV) {
                        final T nextT = map.apply(versioned.value());
                        Versioned<T> prev = localCache;
                        final T p_v = prev.value();
                        if (
                                ((p_v != nextT) && (p_v == null || !p_v.equals(nextT)))
                                        && !excludeIn.test(nextT, p_v)
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            do {
                                if (
                                        PARENT.compareAndSet(this, prevV, nextV)
                                ) {
                                    while (!weakSwapDispatcher.test(prev, next)) {
                                        Versioned<T> wit = localCache;
                                        if (prev != wit) {
                                            if (nextV == parent) {
                                                T w_v = wit.value();
                                                if (
                                                        ((w_v == nextT) || (w_v != null && w_v.equals(nextT)))
                                                        ||
                                                                excludeIn.test(nextT, w_v)
                                                ) return;
                                                prev = wit;
                                                next = wit.newValue(nextT);
                                            } else return;
                                        }
                                    }
                                    return;
                                }
                                prevV = parent;
                            } while (nextV > prevV);
                        }
                    }
                };
            }
        }

        @Override
        public Versioned<T> apply(Versioned<S> versioned) { return onActive.apply(versioned); }

        @Override
        public void accept(Versioned<S> versioned) { core.accept(versioned); }
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
                    final T p_v = prev.value();
                    T nextT = map.apply(p_v, versioned.value());
                    if (
                            (p_v != nextT) && (p_v == null || !p_v.equals(nextT))
                    ) {
                        if (PARENT.compareAndSet(this, current, nextV)) {
                            Versioned<T> next = prev.newValue(nextT);
                            if (strongSwapper.test(prev, next)) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    final int nextV = versioned.version();
                    int prevV = parent;
                    if (nextV > prevV) {
                        Versioned<T> prev = localCache;
                        final T p_v = prev.value();
                        final T nextT = map.apply(p_v, versioned.value());
                        if (
                                (p_v != nextT) && (p_v == null || !p_v.equals(nextT))
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            do {
                                if (
                                        PARENT.compareAndSet(this, prevV, nextV)
                                ) {
                                    while (!weakSwapDispatcher.test(prev, next)) {
                                        Versioned<T> wit = localCache;
                                        if (prev != wit) {
                                            if (nextV == parent) {
                                                T w_v = wit.value();
                                                T new_next = map.apply(w_v, versioned.value());
                                                if ((w_v == new_next) || (w_v != null && w_v.equals(new_next))) return;
                                                prev = wit;
                                                next = wit.newValue(new_next);
                                            } else return;
                                        }
                                    }
                                    return;
                                }
                                prevV = parent;
                            } while (nextV > prevV);
                        }
                    }
                };
            } else {
                this.silentCore = versioned -> {
                    int current = parent, nextV = versioned.version();
                    final Versioned<T> prev = localCache;
                    final T p_v = prev.value();
                    final T nextValue = map.apply(prev.value(), versioned.value());
                    if (
                            ((p_v != nextValue) && (p_v == null || !p_v.equals(nextValue)))
                                    && !excludeIn.test(nextValue, p_v)
                    ) {
                        Versioned<T> next = prev.newValue(nextValue);
                        if (PARENT.compareAndSet(this, current, nextV)) {
                            if (strongSwapper.test(prev, next)) {
                                return next;
                            }
                        }
                    }
                    return null;
                };
                this.core = versioned -> {
                    final int nextV = versioned.version();
                    int prevV = parent;
                    if (nextV > prevV) {
                        Versioned<T> prev = localCache;
                        final T p_v = prev.value();
                        final T nextT = map.apply(p_v, versioned.value());
                        if (
                                ((p_v != nextT) && (p_v == null || !p_v.equals(nextT)))
                                && !excludeIn.test(nextT, p_v)
                        ) {
                            Versioned<T> next = prev.newValue(nextT);
                            do {
                                if (PARENT.compareAndSet(this, prevV, nextV)) {
                                    while (!weakSwapDispatcher.test(prev, next)) {
                                        Versioned<T> wit = localCache;
                                        if (prev != wit) {
                                            if (nextV == parent) {
                                                T w_v = wit.value();
                                                T new_next = map.apply(w_v, versioned.value());
                                                if (
                                                        ((w_v == new_next) || (w_v != null && w_v.equals(new_next)))
                                                                || excludeIn.test(new_next, w_v)
                                                ) return;
                                                prev = wit;
                                                next = wit.newValue(new_next);
                                            } else return;
                                        }
                                    }
                                    return;
                                }
                                prevV = parent;
                            } while (nextV > prevV);
                        }
                    }
                };
            }
        }

        @Override
        public Versioned<T> apply(Versioned<S> versioned) { return silentCore.apply(versioned); }

        @Override
        public void accept(Versioned<S> versioned) { core.accept(versioned); }
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
    getJoinReceiver(BiFunction<T, Parent, T> map) { return new JoinReceiver<>(map); }

    class JoinReceiver<Parent> extends VersionedReceiver implements CompoundReceiver<T, Parent> {

        private final Receiver<Parent> core;
        private final FirstReceiver<Parent, T> onActive;

        JoinReceiver(BiFunction<T, Parent, T> map) {
            boolean neverExcludeIn = excludeIn.isAlwaysFalse();
            this.core = neverExcludeIn ?
                    versioned -> {
                        final int parentV = versioned.version();
                        int thisV = parent;
                        if (parentV > thisV) {
                            final Parent parent_val = versioned.value();
                            Versioned<T> prevV = localCache;
                            final T p_v = prevV.value();
                            T next = map.apply(p_v, parent_val);
                            if ((p_v != next) && (p_v == null || !p_v.equals(next))) {
                                Versioned<T> nextV = prevV.newValue(next); //breathing room...
                                do {
                                    if (PARENT.compareAndSet(this, thisV, parentV)) {
                                        while (!weakSwapDispatcher.test(prevV, nextV)) {
                                            Versioned<T> wit = localCache;
                                            if (wit != prevV) {
                                                if (parentV == parent) {
                                                    T w_v = wit.value();
                                                    T new_next = map.apply(w_v, parent_val);
                                                    if ((w_v == new_next) || (w_v != null && w_v.equals(new_next))) return;
                                                    prevV = wit;
                                                    nextV = wit.newValue(new_next);
                                                } else return;
                                            }
                                        }
                                    }
                                    thisV = parent;
                                } while (parentV > thisV);
                            }
                        }
                    }
                    :
                    versioned -> {
                        final int parentV = versioned.version();
                        int thisV = parent;
                        if (parentV > thisV) {
                            final Parent parent_val = versioned.value();
                            Versioned<T> prevV = localCache;
                            final T p_v = prevV.value();
                            T next = map.apply(p_v, parent_val);
                            if (
                                    ((p_v != next) && (p_v == null || !p_v.equals(next)))
                                            && !excludeIn.test(next, p_v)
                            ) {
                                Versioned<T> nextV = prevV.newValue(next); //breathing room...
                                do {
                                    if (PARENT.compareAndSet(this, thisV, parentV)) {
                                        while (!weakSwapDispatcher.test(prevV, nextV)) {
                                            Versioned<T> wit = localCache;
                                            if (wit != prevV) {
                                                if (parentV == parent) {
                                                    T w_v = wit.value();
                                                    T new_next = map.apply(w_v, parent_val);
                                                    if (
                                                            ((w_v == new_next) || (w_v != null && w_v.equals(new_next)))
                                                                    && excludeIn.test(new_next, w_v)
                                                    ) return;
                                                    prevV = wit;
                                                    nextV = wit.newValue(new_next);
                                                } else return;
                                            }
                                        }
                                    }
                                    thisV = parent;
                                } while (parentV > thisV);
                            }
                        }
                    };
            this.onActive = neverExcludeIn ?
                    versioned -> {
                        final int parentV = versioned.version(), thisV = parent;
                        Versioned<T> prevV = localCache;
                        T next;
                        if (
                                parentV > thisV
                                        && prevV.isDiff(next = map.apply(prevV.value(), versioned.value()))
                        ) {
                            Versioned<T> nextV;
                            if (strongSwapDispatcher.test(prevV, nextV = prevV.newValue(next))
                                    && PARENT.compareAndSet(this, thisV, parentV)
                            ) {
                                return nextV;
                            }
                        }
                        return null;
                    }
                    :
                    versioned -> {
                        final int parentV = versioned.version(), thisV = parent;
                        Versioned<T> prevV = localCache;
                        T next;
                        if (
                                parentV > thisV
                                        && prevV.isDiff(next = map.apply(prevV.value(), versioned.value()))
                                        && !excludeIn.test(next, prevV.value())
                        ) {
                            Versioned<T> nextV;
                            if (strongSwapDispatcher.test(prevV, nextV = prevV.newValue(next))
                                    && PARENT.compareAndSet(this, thisV, parentV)
                            ) return nextV;
                        }
                        return null;
                    };
        }

        @Override
        public Versioned<T> apply(Versioned<Parent> parentVersioned) { return onActive.apply(parentVersioned); }

        @Override
        public void accept(Versioned<Parent> versioned) { core.accept(versioned); }
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
// We need a new syntax.
// One that would trigger generalization.
// A new syntax that synchronizes the unaligned.
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