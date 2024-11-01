package com.skylarkarms.solo;

import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.BinaryPredicate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

public class Link<T>
        extends Path.Impl.SwappableActivator<T, Link.Updatable.Resettable<T>> {
    private final AtomicInteger counter = new AtomicInteger();
    private final IntSupplier liveCount = counter::get;

    public Link() { this(Builder.getDefault()); }

    public Link(BinaryPredicate<T> excludeIn) {
        this(Builder.inExcluded(excludeIn));
    }

    private Link(Builder<T> builder) { super(builder); }

    public Link(UnaryOperator<Builder<T>> builder) { super(builder); }

    public Link(T initialValue) { this(Builder.withValue(initialValue)); }

    BooleanSupplier getResetter(Supplier<T> nextValSupplier) {
        return () -> {
            final Versioned<T> current = cache.get();
            if (current.isDefault()) return false;
            final T nextVal = nextValSupplier.get();
            if (nextVal != null) {
                return current.isDiff(nextVal)
                        && cache.strongSwapDispatcher.test(
                        current, current.newValue(nextVal)
                );
            } else return false;
        };
    }

    public Path<?> bind(Path<T> path) {
        if (sysRegister.getActivator().state.equals(path)) {
            return path;
        }
        return sysBind(
                new Updatable.Resettable<>(
                        path,
                        cache.hierarchicalIdentity()
                        , getResetter(
                        () -> {
                            final Versioned<T> nextV;
                            return (nextV = path.isConsumable()) != null ? nextV.value() : null;
                        }
                )
                ));
    }

    public<S> Path<?> bind(Path<S> path, Function<S, T> map){
        assert map != null : "map cannot be null, use the bind(Path<T>) method instead.";
        if (sysRegister.getActivator().state.equals(path)) return path;
        return sysBind(
                new Updatable.Resettable<>(
                        path,
                        cache.hierarchicalMap(map)
                        ,getResetter(
                        () -> {
                            final Versioned<S> nextV;
                            return (nextV = path.isConsumable()) != null ? map.apply(nextV.value()) : null;
                        }
                    )
                )
        );
    }

    private <S> Path<?> sysBind(
            Updatable.Resettable<T> nextA
    ) {
        Activators.GenericShuttableActivator<T, Updatable.Resettable<T>>
                prev = sysRegister.register(
                counter.incrementAndGet(),
                liveCount,
                new Activators.GenericShuttableActivator<>(nextA)

        );
        return prev != null ? prev.state.parent : null;
    }

    public Path<?> unbind() {
        Activators.BinaryState<?> unreg = sysRegister.unregister().state;
        if (unreg != null && !unreg.isDefault()) {
            assert unreg instanceof Activators.PathedBinaryState<?,?>;
            return ((Activators.PathedBinaryState<?, ?>)unreg).parent;
        } else return null;
    }

    public boolean unbind(Path<?> aPath) {
        return sysRegister.unregister(
                binaryState -> {
                    if (binaryState instanceof Activators.PathedBinaryState<?, ?> pa) {
                        return pa.parent == aPath;
                    } else return false;
                }
        );
    }

    @Override
    boolean sysActivate() { return sysRegister.activate(); }

    @Override
    void sysDeactivate() { sysRegister.deactivate(); }

    public static class Updatable<T> extends SwappableActionablePath<T> implements In.InStrategy.Updater<T>, Cache.CAS<T>
    {

        private final AtomicInteger counter = new AtomicInteger();
        private final IntSupplier liveCount = counter::get;
        private final Updater<T> updater;

        public Updatable(T initialValue) {
            this(
                    initialValue, Builder.getDefault()
            );
        }

        public Path<?> unbind() {
            Activators.BinaryState<?> unreg = sysRegister.unregister().state;
            if (unreg instanceof Activators.PathedBinaryState<?,?> pa) {
                return pa.parent;
            } else return null;
        }

        public boolean unbind(Path<?> aPath) {
            return sysRegister.unregister(
                    binaryState -> {
                        if (binaryState instanceof Activators.PathedBinaryState<?,?> pa) {
                            return pa.parent == aPath;
                        } else return false;
                    }
            );
        }

        /**
         * Resets the contents of this Link to be that of it's bounded Path.
         * @return {@code false} if there is no Path bounded,
         *          <p> OR if its source was the same as the one from this Path.
         * */
        public boolean reset() {
            Activators.BinaryState<?> bst = sysRegister.getActivator().state;
            if (bst instanceof Resettable<?> rs) {
                return rs.resetter.getAsBoolean();
            } else return false;
        }

        static class Resettable<T> extends Activators.PathedBinaryState<Object, T> {
            final BooleanSupplier resetter;

            @SuppressWarnings("unchecked")
            <S>Resettable(
                    Path<S> path,
                    Cache.CompoundReceiver<T, S> receiver,
                    BooleanSupplier resetter
            ) {
                super(
                        Activators.GenericShuttableActivator.INIT,
                        (Path<Object>) path, (Cache.CompoundReceiver<T, Object>) receiver
                );
                this.resetter = resetter;
            }
        }

        /**
         * @param update :
         *            1st = the previous value of this Link.
         *            2nd = the incoming value of the source path.
         *            return = the value to update the current state of this Link.
         * */
        public<S> Path<?> bind(Path<S> path, BiFunction<T, S, T> update){
            assert update != null : "map cannot be null.";
            if (sysRegister.getActivator().state.equals(path)) {
                return path;
            }
            Activators.GenericShuttableActivator<T, Activators.PathedBinaryState<?, T>>
                    prev = sysRegister.register(
                    counter.incrementAndGet()
                    , liveCount,
                    new Activators.GenericShuttableActivator<>(
                            Activators.PathedBinaryState.get(Activators.GenericShuttableActivator.INIT,
                                    path,
                                    cache.hierarchicalUpdater(update)
                            )
                    )
            );
            return prev != null ? prev.state.parent : null;
        }

        public Updatable(T initialValue, Builder<T> builder) { this(In.Config.Update.FORTH.ref, initialValue, builder); }

        public Updatable(
                In.Config.Update config,
                T initialValue, Builder<T> builder) {
            super(initialValue, builder);
            this.updater = config.apply(cache);
        }

        public Updatable(T initialValue, Predicate<T> excludeOut) {
            this(initialValue, Builder.outExcluded(excludeOut));
        }

        @Override
        public boolean compareAndSwap(T expect, T set) { return cache.compareAndSwap(expect, set); }

        @Override
        public boolean weakSet(T set) { return cache.weakSet(set); }

        @Override
        public T updateAndGet(UnaryOperator<T> update) { return updater.updateAndGet(update); }

        @Override
        public T getAndUpdate(UnaryOperator<T> update) { return updater.getAndUpdate(update); }

        @Override
        public void update(UnaryOperator<T> update) { updater.update(update); }
    }
}
