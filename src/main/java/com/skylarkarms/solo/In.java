package com.skylarkarms.solo;

import com.skylarkarms.concur.Executors;
import com.skylarkarms.lambdas.BinaryPredicate;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.Predicates;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/**
 * This class defines the entry point of the Singular reactive system.
 * It categorizes the type of Input into 2 different variants:
 * <ul>
 *     <li>
 *         {@link Update}
 *         This class is able to update the current value,
 *         the back-pressure is managed by a simple spin-lock buffer meaning that the order of execution may be lost.
 *         The rules of engagement with th method are:
 *         <ul>
 *             <li>
 *                 No side effects: The 'update' {@link UnaryOperator} should not perform side effect operations
 *                 while computing since the operation may retry under contention.
 *             </li>
 *             <li>
 *                 Unsuccessful updates will not perform a 'swap' update when <b>unsuccessful</b>
 *                 <p> An update is considered unsuccessful If:
 *          <ul>
 *              <li> The same instance is returned by the UnaryOperator function.</li>
 *              <li>The "excludeIn" test has determined it as unfit to change the cache's state.</li>
 *          </ul>
 *             </li>
 *         </ul>
 *     </li>
 *     <li>
 *         {@link Consume}
 *         This class 'sets' the current state of the {@link Cache}.
 *         The backpressure is managed by creating an atomic 'int' version, which
 *         is then used to infer whether newer concurrent emissions are attempting cache exclusivity.
 *         If so, then old versions are discarded.
 *         This means that not all emissions will reach the end of the Reactive graph under heavy pressure.
 *
 *         <p> Different types of configuration defined under the {@link Config.Consume} class offer concurrency variants for Threading
 *         <p> The default type is {@link Config.Consume#NON_CONT()} which
 *         is a non contentious version used for when source emissions are handled by an 'event loop' source.
 *     </li>
 * </ul>
 * */
public class In<T>
        extends Path.Impl<T>
        implements Supplier<T>, Cache.CAS<T> {

    @Override
    public final boolean compareAndSwap(T expect, T set) { return super.compareAndSwap(expect, set); }

    @Override
    public final boolean weakSet(T set) { return super.weakSet(set); }

    public interface InStrategy {
        @FunctionalInterface
        interface ToBooleanConsumer<T>
                extends Consumer<T>, InStrategy {
            boolean consume(T t);

            @Override
            default void accept(T t) { consume(t); }

            @FunctionalInterface
            interface Computable<T> extends ToBooleanConsumer<Callable<T>> {}
        }

        /**
         * Updater is an entry point class of the Singular reactive system.
         * <p> As opposed to the {@link Consume} class, this class will NOT disregard concurrent signals, instead a buffer will build up on it's spin-lock mechanism,
         * eventually resolving all signals.
         * <p> The {@link UnaryOperator} `update` function should be side-effect-free, since it may be re-applied
         * when attempted updates fail due to contention among threads.
         * <p> This process will ignore the order of execution under heavy contention, but operation resolution will eventually perform.
         * <p> Any successful update will trigger a dispatch event to all observers.
         * <p> An update is considered unsuccessful If:
         *  <ul>
         *      <li> The same instance is returned by the UnaryOperator function.</li>
         *      <li>The "excludeIn" test has determined it as unfit to change the cache's state.</li>
         *  </ul>
         **/
        interface Updater<T>
                extends InStrategy
        {
            private static<T> Updater<T> fromStrat(ForUpdater<T> strat) {
                return new Updater<>() {
                    @Override
                    public T updateAndGet(UnaryOperator<T> update) { return strat.cas(update, Actions.getNext()); }

                    @Override
                    public T getAndUpdate(UnaryOperator<T> update) { return strat.cas(update, Actions.getPrev()); }

                    @Override
                    public void update(UnaryOperator<T> update) { strat.up(update); }
                };
            }

            /**
             * Updates the inner state value of this {@link Update} using the provided unary operator.
             * The provided update function should not have side effects, as it may be retried
             * if update attempts fail due to thread contention.
             *
             * @param update The unary operator that defines the update logic.
             * @return T the updated value. OR {@code null} if the update was unsuccessful
             * */
            T updateAndGet(UnaryOperator<T> update);

            /**
             * Updates the inner state value of this {@link Update} using the provided unary operator.
             * The provided update function should not have side effects, as it may be retried
             * if update attempts fail due to thread contention.
             *
             * @param update The unary operator that defines the update logic.
             * @return The previous value before the update OR {@code null} if the update was unsuccessful.
             */
            T getAndUpdate(UnaryOperator<T> update);

            /**
             * Updates the inner state value of this {@link Update} using the provided unary operator.
             * The provided update function should not have side effects, as it may be retried
             * if update attempts fail due to thread contention.
             *
             * @param update The unary operator that defines the update logic.
             * */
            void update(UnaryOperator<T> update);
        }
    }

    private record Actions() {
        static final BinaryOperator<?> prev = (prev, next) -> prev;
        static final BinaryOperator<?> next = (prev, next) -> next;
        @SuppressWarnings("unchecked")
        static<T> BinaryOperator<T> getPrev() { return (BinaryOperator<T>) prev; }
        @SuppressWarnings("unchecked")
        static<T> BinaryOperator<T> getNext() { return (BinaryOperator<T>) next; }
    }

    interface ForUpdater<T> {
        T cas(UnaryOperator<T> u, BinaryOperator<T> r);
        void up(UnaryOperator<T> u);
    }

    private In(T initialValue, Consumer<Builder<T>> builder) {
        this(
                Builder.resolve(initialValue, builder)
        );
    }

    private In(Builder<T> builder) {
        super(builder);
    }

    @Override
    public final T get() {
        return this.cache.liveGet();
    }

    public static abstract class Config<S extends InStrategy> {

        @FunctionalInterface
        interface Strategy<S extends InStrategy> {
            <T> S apply(Cache<T> cache, Executors.BaseExecutor delayer);
        }
        final Strategy<S> strategy;
        final Executors.BaseExecutor delayer;

        <T> S apply(Cache<T> cache) { return strategy.apply(cache, delayer); }

        <T> S apply(Impl<T> impl) { return apply(impl.cache); }

        private Config(
                Strategy<S> strategy,
                Executors.BaseExecutor delayer
        ) {
            this.strategy = strategy;
            this.delayer = delayer;
        }

        /**
         * Specifies the type of Source strategy to define the backpressure mechanism
         * */
        public static final class Consume
                extends Config<InStrategy.ToBooleanConsumer<?>> {

            private Consume(Type t,
                            Executors.BaseExecutor delayer
            ) {
                super(t, delayer);
            }

            /**
             * <p> This is the default non-contentious type of the Source class.
             * <p> It is designed as a non-contentious consumption entry point.
             * <p> It handles background emissions, but if any contention is detected within this entry point, an error will be thrown.
             * */
            public static final Consume NON_CONT() { return NON_CONT.ref; }
            private record NON_CONT() { static final Consume ref = new Consume(Type.non_cont, null); }
            /**
             * <p>  Contentious type of entry point.
             * <p> This option will dispatch the first emission on the current Thread and subsequent emissions on a background thread defined by 'executor'.
             * <p> By default the constructor will use the executor specified at {@link Executors#UNBRIDLED()}.
             * <p> For custom executor the {@link #CONT(Executor)} factory method should be used.
             * */
            public static final Consume CONT() { return CONT.ref; }
            private record CONT() { static final Consume ref = new Consume(Type.cont,
                        Executors.getContentious(
                                Executors.UNBRIDLED()
                        )
                ); }
            /**
             * <p> Contentious type of entry point.
             * <p> <b>All</b> emissions will be computed on a background thread defined by the 'executor'.
             * <p> By default the constructor will use the executor specified at {@link Executors#UNBRIDLED()}.
             * <p> For custom executor the {@link #BACK(Executor)} factory method should be used.
             * */
            public static final Consume BACK() { return BACK.ref; }
            private record BACK() { static final Consume ref = new Consume(Type.back,
                        Executors.getContentious(
                                Executors.UNBRIDLED()
                        )
                ); }

            /**
             * <p> Synchronized type of entry point.
             * <p> By default the constructor will use the executor specified at {@link Executors#UNBRIDLED()}.
             * <p> For custom executor the {@link #SYNC(Executor)} factory method should be used.
             * <ul>
             *     <li> When no executor is provided (executor is null), the 'sync' strategy employs synchronized blocks to ensure
             *     the thread-safe consumption of data. Users are responsible for managing Thread creation manually.
             *     </li>
             *     <li> When an executor is provided (executor is not null), the 'sync' strategy delivers background commands for each
             *     individual consumption. This automates the thread synchronization process, eliminating the need for manual
             *     management.
             *     </li>
             * </ul>
             * */
            public static final Consume SYNC() {return SYNC.ref; }
            private record SYNC() {static final Consume ref = new Consume(Type.sync,
                    Executors.getContentious(
                            Executors.UNBRIDLED()
                    )
            );}


            /**
             * To use for custom executor
             * */
            public static Consume CONT(Executor executor) {
                return new Consume(Type.cont,
                        Executors.getContentious(executor)
                );
            }

            public static Consume CONT_delayed(Executor executor, long duration, TimeUnit unit) {
                return new Consume(Type.cont,
                        Executors.getDelayer(executor, duration, unit)
                );
            }

            public static Consume CONT_delayed(long duration, TimeUnit unit) {
                return CONT_delayed(
                        Executors.UNBRIDLED(),
                        duration, unit);
            }

            /**
             * To use for custom executor
             * */
            public static Consume BACK(Executor executor) {
                return new Consume(Type.back,
                        Executors.getContentious(executor)
                );
            }

            public static Consume BACK_delayed(Executor executor, long duration, TimeUnit unit) {
                return new Consume(Type.back,
                        Executors.getDelayer(executor, duration, unit)
                );
            }

            public static Consume BACK_delayed(long duration, TimeUnit unit) {
                return BACK_delayed(
                        Executors.UNBRIDLED(),
                        duration, unit
                );
            }

            /**
             * Pass null for NO automatic threading.
             * */
            public static Consume SYNC(Executor executor) {
                return new Consume(Type.sync,
                        executor != null ? Executors.getContentious(executor) : null
                );
            }

            public static Consume SYNC_delayed(Executor executor, long duration, TimeUnit unit) {
                return new Consume(Type.sync,
                        Executors.getDelayer(executor, duration, unit)
                );
            }

            public static Consume SYNC_delayed(long duration, TimeUnit unit) {
                return SYNC_delayed(
                        Executors.UNBRIDLED(),
                        duration, unit
                );
            }

            @SuppressWarnings("unchecked")
            @Override
            <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> cache) {
                return (InStrategy.ToBooleanConsumer<T>) super.apply(cache);
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> InStrategy.ToBooleanConsumer<T> apply(Impl<T> impl) {
                return (InStrategy.ToBooleanConsumer<T>) super.apply(impl);
            }

            @FunctionalInterface
            interface Strategy {
                <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> tCache, Executors.BaseExecutor delayer);
            }

            /**
             * Singleton Instance of the backpressure strategies.
             * <p> Allows the manual creation of {@link Impl} scoped consumers (@link Consumer).
             * */
            enum Type
                    implements Config.Strategy<InStrategy.ToBooleanConsumer<?>> {
                /**
                 * Non-contentious type of entry point.
                 * <p> This strategy can handle background processes, but if contention is met, an error will throw.
                 * <p> See {@link Cache#concurrent_error_message} .
                 * */
                non_cont(
                        new Strategy() {
                            @Override
                            public <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> tCache,
                                                                             Executors.BaseExecutor delayer
                            ) {
                                //We'll just ignore executor
                                return tCache.getSource();
                            }
                        }
                ),
                /**
                 * Contentious type of entry point.
                 * <p>This option will dispatch the first emission on the current Thread
                 * and subsequent emissions on a background thread defined by 'executor'.
                 * */
                cont(
                        new Strategy() {
                            @Override
                            public <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> tCache,
                                                                             Executors.BaseExecutor delayer
                            ) {
                                assert delayer != null : "Must provide an executor";
                                return tCache.getSource(delayer);
                            }
                        }
                ),
                /**
                 * Contentious type of entry point.
                 * <p> All emissions will be computed on a background thread defined by the 'executor'.
                 * */
                back(
                        new Strategy() {
                            @Override
                            public <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> tCache,
                                                                             Executors.BaseExecutor delayer
                            ) {
                                assert delayer != null : "Must provide an executor";
                                return tCache.getBackSource(delayer);
                            }
                        }
                ),
                /**
                 * Synchronized type of entry point.
                 * <p> Ensures proper synchronization on the concurrent consumption of data.
                 * when multiple threads are involved. Depending on whether an executor is provided, the synchronization mechanism
                 * varies as follows:
                 *
                 * <ul>
                 *     <li> When no executor is provided (executor is null), the 'sync' strategy employs synchronized blocks to ensure
                 *     the thread-safe consumption of data. Users are responsible for managing Thread creation manually.
                 *     </li>
                 *     <li> When an executor is provided (executor is not null), the 'sync' strategy delivers background commands for each
                 *     individual consumption. This automates the thread synchronization process, eliminating the need for manual
                 *     management.
                 *     </li>
                 * </ul>
                 *
                 * It's recommended to use the executor-based approach whenever possible to simplify thread management and enhance
                 * performance.
                 */
                sync(

                        new Strategy() {
                            @Override
                            public <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> tCache,
                                                                             Executors.BaseExecutor delayer
                            ) {
                                final Object lock = new Object();
                                final InStrategy.ToBooleanConsumer<T> core = tCache.getSource();
                                final InStrategy.ToBooleanConsumer<T> synchronize = t -> {
                                    synchronized (lock) {
                                        return core.consume(t);
                                    }
                                };
                                return delayer == null ? synchronize :
                                        t -> {
                                            delayer.onExecute( //Do not use the onExecute boolean result.
                                                    () -> synchronize.consume(t)
                                            );
                                            return true;
                                        };
                            }
                        }
                );

                final Strategy fun;

                Type(Strategy fun) {
                    this.fun = fun;
                }

                @Override
                public <T> InStrategy.ToBooleanConsumer<T> apply(Cache<T> cache, Executors.BaseExecutor delayer) {
                    return fun.apply(cache, delayer);
                }
            }
        }

        /**
         * Specifies the type of {@link In.Compute} strategy to define the backpressure mechanism
         * */
        public static final class Compute
                extends Config<InStrategy.ToBooleanConsumer.Computable<?>> {

            private Compute(Strategy<InStrategy.ToBooleanConsumer.Computable<?>> t,
                            Executors.BaseExecutor delayer
            ) {
                super(t, delayer);
            }

            /**
             * Contentious type of entry point.
             * <p> All emissions will be computed on a background thread defined by the 'executor'.
             * */
            record base_compute() {static final Strategy<InStrategy.ToBooleanConsumer.Computable<?>> ref = new Strategy<>() {
                @Override
                public <T> InStrategy.ToBooleanConsumer.Computable<T> apply(
                        Cache<T> cache, Executors.BaseExecutor delayer) {
                    return cache.getComputable(delayer);
                }
            };}


            /**
             * <p> Loc-Free type of entry point.
             * <p> Calls {@link Cache#getComputable(Executors.BaseExecutor)} with null as {@link Executors.BaseExecutor}.
             * <p> concurrent emissions will be discarded.
             * <p> computation speed {@link Callable} will be used as backpressure drop timeout.
             * */
            public static final Compute FORTH() {return FORTH.ref;}
            record FORTH() {static final Compute ref = new Compute(
                    base_compute.ref,
                    null
            );}


            /**
             * To use for custom executor
             * */
            public static Compute BACK(Executor executor) {
                return new Compute(base_compute.ref,
                        Executors.getContentious(executor)
                );
            }

            public static Compute BACK_delayed(Executor executor, long duration, TimeUnit unit) {
                return new Compute(base_compute.ref,
                        Executors.getDelayer(executor, duration, unit)
                );
            }

            /**
             * <p> Contentious type of entry point.
             * <p> <b>All</b> emissions will be computed on a background thread.
             * <p> By default the constructor will use a single
             * Threaded executor with a Thread set to {@link Thread#MAX_PRIORITY} as priority.
             * <p> For custom executor the {@link #BACK(Executor)} factory method should be used.
             * */
            public static Compute BACK() {
                return new Compute(base_compute.ref,
                        Executors.getContentious(
                                Executors.UNBRIDLED()
                        )
                );
            }

            public static Compute BACK_delayed(long duration, TimeUnit unit) {
                return BACK_delayed(
                        Executors.UNBRIDLED()
                        , duration, unit
                );
            }

            @SuppressWarnings("unchecked")
            @Override
            <T> InStrategy.ToBooleanConsumer.Computable<T> apply(Cache<T> cache) {
                return (InStrategy.ToBooleanConsumer.Computable<T>) super.apply(cache);
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> InStrategy.ToBooleanConsumer.Computable<T> apply(Impl<T> impl) {
                return (InStrategy.ToBooleanConsumer.Computable<T>) super.apply(impl);
            }
        }

        /**
         * The {@link Type} configuration that will define this {@link In.Update}
         * <p> The different Types are accessible via the static components or constructors:
         * <ul>
         *     <li>
         *      {@link #FORTH()}
         *     </li>
         *     <li>
         *      {@link #BACK()}
         *     </li>
         * </ul>
         * */
        public static final class Update
                extends Config<InStrategy.Updater<?>> {

            Update(Type type, Executors.BaseExecutor delayer) { super(type, delayer); }

            /**
             * A Configuration of type {@link Type#back} that makes use of {@link Executors#UNBRIDLED()}
             * <p> Other configurations involve:
             * <ul>
             *     <li>
             *         {@link #BACK(Executor)}
             *     </li>
             *     <li>
             *         {@link #BACK(long, TimeUnit)}
             *     </li>
             *     <li>
             *         {@link #BACK(Executor, long, TimeUnit)}
             *     </li>
             * </ul>
             * */
            public static final Update BACK() {return BACK.ref;}
            private record BACK() {static final Update ref = new Update(Type.back, Executors.getContentious(
                    Executors.UNBRIDLED()
            ));}


            public static Update BACK(Executor executor){
                return new Update(Type.back, Executors.getContentious(executor));
            }

            public static Update BACK(Executor executor, long duration, TimeUnit unit){
                return new Update(Type.back, Executors.getDelayer(executor, duration, unit));
            }

            /**
             * Will use {@link Executors#UNBRIDLED()} as default {@link Executors.Delayer}
             * */
            public static Update BACK(long duration, TimeUnit unit){
                return new Update(Type.back, Executors.getDelayer(
                        Executors.UNBRIDLED(),
                        duration, unit));
            }

            /**
             * A Configuration of type {@link Type#forth}.
             * */
            public static Update FORTH() {return FORTH.ref;}
            record FORTH() {static final Update ref = new Update(Type.forth, null);}

            @SuppressWarnings("unchecked")
            @Override
            <T> InStrategy.Updater<T> apply(Cache<T> cache) {
                assert !cache.localCache.isDefault() : "Path must have at least an initial value";
                return (InStrategy.Updater<T>) super.apply(cache);
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> InStrategy.Updater<T> apply(Impl<T> impl) {
                assert !impl.cache.localCache.isDefault() : "Path must have at least an initial value";
                return (InStrategy.Updater<T>) super.apply(impl);
            }

            @FunctionalInterface
            interface Strategy {
                <T> InStrategy.Updater<T> apply(Cache<T> tCache, Executors.BaseExecutor delayer);
            }

            /**
             * This typology defines 2 different strategies that manage the way in which emissions are triggered from this Updatable:
             * <ul>
             *     <li>
             *         {@link #back}
             *     </li>
             *     <li>
             *         {@link #forth}
             *     </li>
             * </ul>
             * */
            enum Type
                    implements Config.Strategy<InStrategy.Updater<?>> {
                /**
                 * Update propagations, will be performed on a background Thread.
                 * <p> Contention will subside into a single emission comprising the
                 * <p> full computational update up until the moment of dispatch.
                 * <p> If more updates are performed during this dispatch, a new emission
                 * <p> will be prepared, until contention subsides, and the dispatcher sends new emission
                 * <p> with the most recent update up until that point.
                 * */
                back(
                        new Strategy() {
                            @Override
                            public <T> InStrategy.Updater<T> apply(Cache<T> cache, Executors.BaseExecutor delayer) {
                                return InStrategy.Updater.fromStrat(
                                        cache.getUpdater(delayer)
                                );
                            }
                        }
                ),

                /**
                 * Emissions will make use of the Singular default backPressure dropping mechanism.
                 * <p> If contention is met, ALL emissions will most likely travel down-stream, and
                 * <p> even tho the System is well capable of handling this pressure properly, exit points
                 * <p> may be subjected to this pressure.
                 * */
                forth(
                        new Strategy() {
                            @Override
                            public <T> InStrategy.Updater<T> apply(Cache<T> tCache, Executors.BaseExecutor delayer) {
                                assert delayer == null;
                                return InStrategy.Updater.fromStrat(tCache.getUpdater());
                            }
                        }
                );

                final Strategy strategy;

                Type(Strategy strategy) { this.strategy = strategy; }

                @Override
                public <T> InStrategy.Updater<T> apply(Cache<T> cache, Executors.BaseExecutor delayer) {
                    return strategy.apply(cache, delayer);
                }
            }
        }
    }

    /**
     * The `In.Consume` class serves as the entry point to the Singular reactive system.
     * <p> Entry points such as {@link Consume} handles their own backpressure relief mechanism.
     * <p> Once emissions leave these entry points, the reactive system will handle contentiousness on their own.
     * <p> So even if these entry points are specified as being non-contentious (sequential) see {@link Config.Consume#NON_CONT()},
     * the system will still be capable of supporting any type of emission from other entry point sources.
     * <p> If concurrency control is necessary, the `Source` class offers options through its different types:
     * <ul>
     *     <li>
     *         {@link Config.Consume#NON_CONT()} :
     *         <p> This is the default non-contentious type of the Source class.
     *         <p> It is designed as a non-contentious consumption entry point.
     *         <p> It handles background emissions, but if any contention is detected within this entry point, an error will be thrown.
     *     </li>
     *     <li>
     *         {@link Config.Consume#CONT(Executor)} OR {@link Config.Consume#CONT(Executor)} :
     *         <p> Contentious type of entry point.
     *         <p> This option will dispatch the first emission on the current Thread and subsequent emissions on a background thread defined by 'executor'.
     *         <p> By default the constructor will use the executor specified at {@link Executors#UNBRIDLED()}.
     *         <p> The efficiency of this option is evident, even in scenarios where sequential but consecutive emissions occur.
     *         <p> For custom executor the {@link Config.Consume#CONT(Executor)} factory method should be used.
     *     </li>
     *     <li>
     *         {@link Config.Consume#BACK()} OR {@link Config.Consume#BACK(Executor)}
     *         <p> Contentious type of entry point.
     *         <p> <b>All</b> emissions will be computed on a background thread defined by the 'executor'.
     *         <p> By default the constructor will use the executor specified at {@link Executors#UNBRIDLED()}.
     *         <p> For custom executor the {@link Config.Consume#BACK(Executor)} factory method should be used.
     *     </li>
     *     <li>
     *         {@link Config.Consume#SYNC()} OR {@link Config.Consume#SYNC(Executor)}
     *          <p> Synchronized type of entry point.
     *          <p> By default the constructor will use the executor specified at {@link Executors#UNBRIDLED()}.
     *          <p> For custom executor the {@link Config.Consume#SYNC(Executor)} factory method should be used.
     *          <ul>
     *              <li> When no executor is provided (executor is null), the 'sync' strategy will deliver consumption calls on the
     *              same thread and background deliverance should be handled manually by the user.
     *              </li>
     *              <li> When an executor is provided (executor is not null), the 'sync' strategy will deliver background commands for each
     *              individual consumption. This automates thread creation management.
     *              </li>
     *          </ul>
     *     </li>
     * </ul>
     *
     */
    public static class Consume<T>
            extends In<T>
            implements
            InStrategy.ToBooleanConsumer<T>,
            Cache.CAS<T>
    {
        private final ToBooleanConsumer<T> consumer;
        /**
         * Defaults to {@link #Consume(Object, Config.Consume)}
         * <p> Where {@link Config.Consume} is set to {@link Config.Consume#NON_CONT()}
         * <p> This Source cannot sustain contentious emissions.
         * */
        public Consume(
                T initialValue
        ) {
            this(Builder.withValue(initialValue));
        }

        /**Defaults to {@link #Consume(Builder)}
         * <p> Where params are set to:
         * <ul>
         *     <li>
         *         'builder' = {@link Builder#getDefault()}
         *         except for the param defined in this constructor
         *     </li>
         *     <li>
         *         'Type' = {@link Config.Consume#NON_CONT()}
         *     </li>
         * </ul>
         * */
        public Consume(
                BinaryPredicate<T> excludeIn
        ) {
            this(Builder.inExcluded(excludeIn));
        }

        public Consume(
                Predicate<T> excludeOut
        ) {
            this(Builder.outExcluded(excludeOut));
        }

        /**Defaults to {@link #Consume(BinaryPredicate)}
         * @param excludeIn  A test that prevents undesired state changes in the {@link Cache}.
         *                   If the test returns true, the swapping operation is skipped.
         *
         * <pre>
         * <b>Default values:</b>
         * <ul>
         *     <li>
         *          T 'initialValue' set to null.
         *      </li>
         *      <li>
         *          {@link Config.Consume} 'type' set to {@link Config.Consume#NON_CONT()}.
         *      </li>
         *  </ul>
         * </pre>
         * */
        public Consume(
                BinaryPredicate.Unary<T> excludeIn
        ) {
            this(
                    (BinaryPredicate<T>) excludeIn
            );
        }

        /**Defaults to {@link #Consume(Object, Config.Consume)}
         *
         * <pre>
         * <b>Default values:</b>
         * <ul>
         *     <li>
         *          T 'initialValue' set to null.
         *      </li>
         *      <li>
         *          {@link Config.Consume} 'type' set to {@link Config.Consume#NON_CONT()}.
         *      </li>
         *  </ul>
         * </pre>
         * */
        public Consume() {
            this(Builder.getDefault(),
                    Config.Consume.NON_CONT.ref
            );
        }

        /**Defaults to {@link #Consume(Builder, Config.Consume)}
         * @param initialValue The first value to be set to this Source.
         *                     If this value is null the Cache will be considered in a default initial state and observers will get emissions once a first value is finally given.
         * @param config Specifies the type of Source strategy to define the backpressure mechanism.
         * <pre>
         * <b>Default values:</b>
         * <ul>
         *      <li>
         *          {@link BinaryPredicate} 'excludeIn' set to default state {@link Lambdas.BinaryPredicates#defaultFalse()}.
         *      </li>
         *  </ul>
         * </pre>
         * */

        public Consume(
                T initialValue,
                Config.Consume config
        ) {
            this(Builder.withValue(initialValue), config);
        }

        public Consume(
                Config.Consume config
        ) {
            this(Builder.getDefault(), config);
        }

        /**
         * Main constructor for the 'Consume' class.
         * @param builder The {@link Builder} parameter Object for constructor overloading.
         * @param config Specifies the {@link Config.Consume} of strategy to define the backpressure mechanism of this specific entry point,
         *             offering multiple degrees and types of concurrency management.
         **/

        public Consume(
                Builder<T> builder,
                Config.Consume config
        ) {
            super(builder);
            this.consumer = config.apply(cache);
        }

        /**
         * Defaults to {@link #Consume(Builder, Config.Consume)}
         * @param builder The {@link Builder} parameter Object for constructor overloading.
         * <p><b>Default values:</b>
         * <ul>
         *      <li>
         *          {@link Config.Consume} set to {@link Config.Consume#NON_CONT()}.
         *      </li>
         *  </ul>
         * */
        public Consume(
                Builder<T> builder
        ) {
            this(builder, Config.Consume.NON_CONT.ref);
        }

        @Override
        public boolean consume(T t) { return consumer.consume(t); }

        public static class Arr<T> extends Impl.Arr<T>
                implements
                ToBooleanConsumer<T[]>,
                Supplier<T[]>
        {
            private final ToBooleanConsumer<T[]> consumer;

            public Arr() {
                this(null, Predicates.defaultFalse(),
                        Config.Consume.NON_CONT.ref
                );
            }
            public Arr(T[] initialValue) {
                this(initialValue, Predicates.defaultFalse(),
                        Config.Consume.NON_CONT.ref
                );
            }
            public Arr(
                    T[] initialValue,
                    Predicate<T[]> excludeOut,
                    Config.Consume config
            ) {
                this(
                        initialValue,
                        null,
                        excludeOut,
                        config
                );
            }

            public Arr(
                    T[] initialValue,
                    BinaryPredicate<T[]> excludeIn,
                    Predicate<T[]> excludeOut,
                    Config.Consume config
            ) {
                super(excludeIn == null ? getEquals() :
                                (next, prev) -> getEquals().test(next, prev) && !excludeIn.test(next, prev),
                        initialValue,
                        excludeOut
                );
                this.consumer = config.apply(cache);
            }

            @Override
            public T[] get() { return cache.liveGet(); }

            @Override
            public boolean consume(T[] t) { return consumer.consume(t); }
        }

        @Override
        public Consume<T> assign(Ref.Eager<T> referent) { return (Consume<T>) super.assign(referent); }
    }

    /**
     * This class will compute a {@link Callable} on a Thread defined in its {@link Config.Compute} configuration.
     * Contention will be automatically resolved.
     * */
    public static class Compute<T>
            extends In<T> implements InStrategy.ToBooleanConsumer.Computable<T> {

        final Computable<T> computable;

        public Compute(
                Config.Compute config) {
            this(
                    config,
                    Builder.getDefault()
            );
        }

        public Compute(
                Config.Compute config,
                T initialValue, Consumer<Builder<T>> builder) {
            this(
                    config,
                    Builder.resolve(
                            initialValue, builder
                    )
            );
        }

        public Compute(
                Config.Compute config,
                T initialValue
        ) {
            this(config, Builder.withValue(initialValue));
        }

        public Compute(
                Config.Compute config,
                Builder<T> builder) {
            super(builder);
            computable = config.apply(cache);
        }

        @Override
        public boolean consume(Callable<T> tCallable) { return computable.consume(tCallable); }
    }

    /**
     * Implementation of {@link Updater} that extends {@link Impl}
     **/
    public static class Update<T>
            extends In<T>
            implements InStrategy.Updater<T>
    {
        private final Updater<T> strat;

        public Update(T initialValue) { this(initialValue, Lambdas.Consumers.getDefaultEmpty()); }
        public Update(
                T initialValue,
                Consumer<Builder<T>> builder) {
            this(Config.Update.FORTH.ref, initialValue, builder);
        }

        public Update(
                Config.Update config,
                T initialValue,
                Consumer<Builder<T>> builder
        ) {
            super(initialValue, builder);
            strat = config.apply(cache);
        }

        /**
         * Updates the inner state value of this 'Update' using the provided unary operator.
         * The provided update function should not have side effects, as it may be retried
         * if update attempts fail due to thread contention.
         *
         * @param update The unary operator that defines the update logic.
         * @return T the updated value. OR {@code null} if the update was unsuccessful
         * */
        @Override
        public T updateAndGet(UnaryOperator<T> update) { return strat.updateAndGet(update); }

        /**
         * Updates the inner state value of this 'Update' using the provided unary operator.
         * The provided update function should not have side effects, as it may be retried
         * if update attempts fail due to thread contention.
         *
         * @param update The unary operator that defines the update logic.
         * @return The previous value before the update OR {@code null} if the update was unsuccessful.
         */
        @Override
        public T getAndUpdate(UnaryOperator<T> update) { return strat.getAndUpdate(update); }

        /**
         * Updates the inner state value of this 'Update' using the provided unary operator.
         * The provided update function should not have side effects, as it may be retried
         * if update attempts fail due to thread contention.
         *
         * @param update The unary operator that defines the update logic.
         * */
        @Override
        public void update(UnaryOperator<T> update) { strat.update(update); }

        @Override
        public Update<T> assign(Ref.Eager<T> referent) { return (Update<T>) super.assign(referent); }
    }
}