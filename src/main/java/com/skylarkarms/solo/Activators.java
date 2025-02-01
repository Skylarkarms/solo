package com.skylarkarms.solo;

import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.Consumers;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.Predicates;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;

public final class Activators {

    /**
     * Delivers an {@link Activator}.
     * This Activator will not support concurrent calls to
     * {@link Activator#activate()} and/or {@link Activator#deactivate()}
     * */
    public static<T, S> Activator getBaseActivator(
            Executor executor
            , Path<T> path
            , Function<T, S> map
            , Consumer<? super S> sConsumer
    ) {
        return getBaseActivator(
                path.getPublisher(executor)
                , map
                , sConsumer
        );
    }

    /**
     * Default implementation of {@link #getBaseActivator(Executor, Path, Function, Consumer)}
     * <p> Where:
     * <p> '{@link Function} map' = {@link Lambdas.Identities#identity()}
     * */
    public static<T> Activator getBaseActivator(
            Executor executor
            , Path<T> path
            , Consumer<? super T> sConsumer
    ) {
        return getBaseActivator(
                path.getPublisher(executor)
                , Lambdas.Identities.identity()
                , sConsumer
        );
    }

    record BaseActivator<T>(Publisher<T> publisher
            , Consumer<T> mapped
    ) implements Activator {
        @Override
        public boolean activate() {
            if (publisher.contains(mapped)) return false;
            publisher.add(mapped);
            return publisher.contains(mapped);
        }

        @Override
        public void deactivate() { publisher.remove(mapped); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BaseActivator<?> that = (BaseActivator<?>) o;
            return Objects.equals(publisher, that.publisher) && Objects.equals(mapped, that.mapped);
        }

        @Override
        public int hashCode() {
            return Objects.hash(publisher, mapped);
        }
    }

    /**
     * Delivers an {@link Activator} that will not support concurrent calls to
     * {@link Activator#activate()} and/or {@link Activator#deactivate()}
     * */
    @SuppressWarnings("unchecked")
    public static<T, S> Activator getBaseActivator(
            Publisher<T> publisher
            , Function<T, S> map
            , Consumer<? super S> sConsumer
    ) {
        Consumer<T> mapped = Lambdas.Identities.isIdentity(map) ?
                (Consumer<T>) sConsumer : Consumers.map(map, sConsumer);
        return new BaseActivator<>(publisher, mapped);
    }

    /**
     * Delivers a non-concurrent {@link Activator}
     * @param activator the {@link Predicates.OfBoolean} that will receive a
     *                  <p> '{@code true}' when {@link Activator#activate()} or
     *                  <p> '{@code false}' when {@link Activator#deactivate()}
     * */
    public static Activator getBaseActivator(
            Predicates.OfBoolean activator
    ) {
        return new Activator() {
            @Override
            public boolean activate() { return activator.test(true); }

            @Override
            public void deactivate() { activator.test(false); }
        };
    }

    public record SourceEntry(
            Function<Executor, Activator> fun
    )
    {
        public<T> SourceEntry(Path<T> source, Consumer<? super T> consumer) {
            this( exec ->
                    Activators.getBaseActivator(
                            exec, source, consumer
                    )
            );
        }
        static Activator[] map(Executor executor, SourceEntry[] from) {
            int length;
            if (from == null || (length = from.length) == 0) throw new IllegalStateException("Entries cannot be null or empty.");
            Activator[] res = new Activator[length];
            for (int i = 0; i < length; i++) {
                res[i] = from[i].setupSwitch(executor);
            }
            return res;
        }
        Activator setupSwitch(Executor executor) { return fun.apply(executor); }
    }

    record ActivatorArray(Activator[] listeners) implements Activator{

        @Override
        public boolean activate() {
            for (int i = 0; i < listeners.length; i++) {
                listeners[i].activate();
            }
            return true;
        }

        @Override
        public void deactivate() {
            for (int i = listeners.length - 1; i > -1; i--) {
                listeners[i].deactivate();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActivatorArray that = (ActivatorArray) o;
            return Arrays.equals(listeners, that.listeners);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(listeners);
        }
    }

    /**
     * Synchronizes multiple {@link SourceEntry}
     * */
    public static Activator from(Executor executor, SourceEntry... entries) {
        return new ActivatorArray(SourceEntry.map(executor, entries));
    }

    interface State {
        boolean isActive();
    }

    /**
     * package private Activator
     * list of backPrep returns:
     * {@link Path.Impl#openSwitchMap(Path.Builder, Function, OnSwapped, Predicates.OfBoolean.Consumer)}
     * {@link Path.Impl#switchMap(Path.Builder, Function)}
     * {@link Getter}
     * */
    static class SysActivator {
        boolean sysActivate() {return false;}
        void sysDeactivate() {}
    }
    record Acts() { static final BiConsumer<Object, Activator>
            active = (o, act) -> act.activate(),
            inactive = (o, act) -> act.deactivate();
    }
    record defaultActivator() {static final Activator ref = new Activator() {
        @Override
        public boolean activate() { return false; }

        @Override
        public void deactivate() { }
    };}

    public interface Activator extends Deactivator {
        boolean activate();

        static Activator getDefault() {return defaultActivator.ref;}
    }
    public interface StateActivator extends State, Activator {}

    static class SimpleStateActivator extends SysActivator implements StateActivator {

        private final AtomicBoolean state = new AtomicBoolean();

        @Override
        protected boolean sysActivate() { return false; }

        @Override
        protected void sysDeactivate() {
        }

        @Override
        public boolean isActive() { return state.get(); }

        @Override
        public boolean activate() {
            if (state.compareAndSet(false, true)) {
                return sysActivate();
            } else return false;
        }

        @Override
        public void deactivate() {
            if (state.compareAndSet(true, false))
                sysDeactivate();
        }
    }
    static class OnStateChange extends SysActivator {
        protected void onStateChange(boolean isActive) {}
    }

    @FunctionalInterface
    interface Deactivator {
        void deactivate();
    }

    static abstract class Propagator<T> implements Deactivator {

        boolean isMapped() { return false; }

        boolean isDefault() { return false; }

        abstract Versioned<T> backProp();

        static <S, T> Propagator<T> getLinked(
                Cache<T> childCache,
                Function<S, T> map,
                Path<S> parent
        ) {
            return new PathedBinaryState<>(BinaryState.INACTIVE,
                    parent,
                    childCache.forMapped(map)
            ) {
                @Override
                public boolean isMapped() { return true; }

                @Override
                public String toString() { return super.toString().concat("[Linked]"); }
            };
        }
    }
    interface PathState {
        Path<?> path();
        boolean isDiff(Path<?> that);
    }

    interface Shuttable extends State {
        /**
         * Sets the inner state from {@link GenericShuttableActivator#INIT} to {@link BinaryState#INACTIVE}
         * */
        void init();
        void shutOff();
        boolean isOff();
    }

    interface ListenerState {
        Predicates.OfBoolean getListener();
        boolean equalTo(Predicates.OfBoolean that);
    }

    /**
     * Extended by:
     * <ul>
     *     <li>
     *      @see PathedBinaryState
     *     </li>
     * </ul>
     * */
    static abstract class BinaryState<T> extends Propagator<T> implements State {

        static final int
                INACTIVE = 1,
                QUEUEING = 2,
                ACTIVE = 3;

        static String valueOf(
                int i) {
            return switch (i) {
                case GenericShuttableActivator.INIT -> "INIT";
                case INACTIVE -> "INACTIVE";
                case QUEUEING -> "QUEUEING";
                case ACTIVE -> "ACTIVE";
                case GenericShuttableActivator.OFF -> "OFF";
                default -> throw new IllegalStateException("Unknown value for " + i);
            };
        }

        public BinaryState(int initialState) { this.state = initialState; }

        volatile int state;
        static final VarHandle AA_s;
        final BooleanSupplier isQueueing = () -> QUEUEING == state;
        final BooleanSupplier setActive = () -> AA_s.compareAndSet(this, QUEUEING, ACTIVE);

        static {
            try {
                AA_s = MethodHandles.lookup().findVarHandle(BinaryState.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @Override
        final Versioned<T> backProp() {
            if (AA_s.compareAndSet(this, INACTIVE, QUEUEING)) {
                return sysActivate();
            } else return null;
        }

        final Versioned<T> sysActivate() { return activate(isQueueing, setActive); }

        /**
         * @see Path#activate(Cache.Receiver, BooleanSupplier, BooleanSupplier)
         * @param onSet must be executed to be able to continue to a definite {@link #ACTIVE} state
         * */
        abstract Versioned<T> activate(
                BooleanSupplier allow,
                BooleanSupplier onSet
        );

        @Override
        public final void deactivate() {
            int prev;
            if (ACTIVE == (prev = swapToInactive())) {
                softDeactivate();
            } else {
                if (isMapped() && prev == INACTIVE) {
                    int tries = 0, toThrow = 0;
                    while (state != ACTIVE) {
                        if (tries++ > 3200) {
                            if (toThrow++ == 8) {
                                throw new RuntimeException("TimeOutException: ACTIVE state did not arrived.... " +
                                        "\n waiting time = " + (8 * 250) + " nanos");
                            } else {
                                LockSupport.parkNanos(250);
                                tries = 0;
                            }
                        }
                    }
                    softDeactivate();
                }
            }
        }

        abstract void softDeactivate();

        /**Contentious deactivation*/
        private int swapToInactive() {
            int prev = state;
            while (prev > INACTIVE //QUEUEING or ACTIVE... or INIT
                    && !AA_s.weakCompareAndSet(this, prev, INACTIVE))
            {
                prev = state;
            }
            return prev;
        }

        @Override
        public final boolean isActive() { return ACTIVE == (int)AA_s.getOpaque(this); }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{" +
                    ",\n >>> state=" + valueOf(state) +
                    "\n }@" + hashCode();
        }

        static class Listenable<T> extends BinaryState<T> implements ListenerState {
            final Predicates.OfBoolean listener;

            public Listenable(Predicates.OfBoolean listener) { this(INACTIVE, listener); }

            Listenable(int initialState, Predicates.OfBoolean listener) {
                super(initialState);
                this.listener = listener;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null) return false;
                if (o instanceof Predicates.OfBoolean bo) {
                    return Objects.equals(listener, bo);
                }
                else if (o instanceof Listenable that) {
                    Predicates.OfBoolean t_l = that.listener;
                    return listener == t_l || (listener != null && listener.equals(t_l));
                }
                else return false;
            }

            @Override
            public int hashCode() { return Objects.hash(listener); }

            @Override
            Versioned<T> activate(BooleanSupplier allow, BooleanSupplier onSet) {
                if (allow.getAsBoolean()
                        && onSet.getAsBoolean()
                ) {
                    listener.apply(true);
                }
                return null;
            }

            @Override
            void softDeactivate() { listener.apply(false); }

            @Override
            public Predicates.OfBoolean getListener() { return listener; }

            @Override
            public boolean equalTo(Predicates.OfBoolean that) { return listener == that; }

            @Override
            public String toString() {
                return "Listenable{"
                        + "\n listener=" + listener
                        + "\n super=" + super.toString()
                        + "\n}";
            }
        }

        State removeOwner() {
            deactivate();
            return null;
        }
    }

    public static Activator forListener(Predicates.OfBoolean listener) {
        return new Activator() {
            final BinaryState.Listenable<Object> bl = new BinaryState.Listenable<>(listener);
            @Override
            public boolean activate() {
                bl.backProp();
                return bl.isActive();
            }

            @Override
            public void deactivate() { bl.deactivate(); }
        };
    }

    public static class StatefulActivator extends BinaryState<Object> implements Activator {

        protected StatefulActivator() { super(INACTIVE); }

        StatefulActivator(int initialState) { super(initialState); }

        @Override
        public final boolean activate() {
            if (AA_s.compareAndSet(this, INACTIVE, QUEUEING)) {
                sysActivate();
            }
            return ACTIVE == (int) AA_s.getOpaque(this);
        }

        @Override
        final Versioned<Object> activate(BooleanSupplier allow, BooleanSupplier onSet) {
            if (
                    allow.getAsBoolean()
                            && onSet.getAsBoolean()
            ) {
                sysOnActive();
            }
            return null;
        }

        void sysOnActive() {}
        void sysOnDeactive() {}

        @Override
        final void softDeactivate() { sysOnDeactive(); }
    }

    static class PathedBinaryState<S, T> extends BinaryState<T> implements PathState {
        @SuppressWarnings("unchecked")
        public static<S, T> PathedBinaryState<Object, Object> getObjectReif(
                int initialState,
                Path<S> path,
                Cache.CompoundReceiver<T, S> receiver
        ) {
            return (PathedBinaryState<Object, Object>) new PathedBinaryState<>(
                    initialState,
                    path,
                    receiver
            );
        }

        public static<S, T> PathedBinaryState<S, T> get(
                int initialState,
                Path<S> path,
                Cache.CompoundReceiver<T, S> receiver
        ) {
            return new PathedBinaryState<>(
                    initialState,
                    path,
                    receiver
            );
        }

        record defaultActivator() {
            public static final PathedBinaryState<Object, Object> ref
                    = new PathedBinaryState<>(GenericShuttableActivator.INIT,
                    Path.getDummy(),
                    Cache.CompoundReceiver.getDefault()
            ) {
                @Override
                boolean isDefault() { return true; }

                @Override
                public boolean isDiff(Path<?> that) { return !that.isDummy(); }
            };
        }

        final Path<S> parent;
        final Cache.CompoundReceiver<T, S> receiver;

        public PathedBinaryState(
                int initialState,
                Path<S> path,
                Cache.CompoundReceiver<T, S> receiver
        ) {
            super(initialState);
            this.parent = path;
            this.receiver = receiver;
        }
        @Override
        final Versioned<T> activate(BooleanSupplier allow, BooleanSupplier onSet) {
            Versioned<S> fromParent = parent.activate(receiver, allow, onSet);
            if (fromParent != null) {
                //During switchMaps, the value should be applied, YET, the value will never
                // propagate since the inner value will be acknowledged as being the exact same(???)
                return receiver.apply(fromParent);
            } else return null;
        }

        @Override
        final void softDeactivate() { parent.softDeactivate(receiver); }

        @Override
        public final Path<?> path() { return parent; }

        @Override
        public boolean isDiff(Path<?> that) {
            if (that instanceof Ref<?> tathRef) {
                return parent.isDiff(tathRef.getPath());
            } else return parent.isDiff(that);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof Path) return o.equals(parent);
            if (o == null || getClass() != o.getClass()) return false;
            PathedBinaryState<?, ?> that = (PathedBinaryState<?, ?>) o;
            return parent.equals(that.parent);
        }

        @Override
        public int hashCode() { return Objects.hash(parent); }

        @Override
        public String toString() {
            return "PathedBinaryState{"
                    + "\n >>> parent=" + parent
                    + "\n >>> receiver=" + receiver
                    + "\n >>> BinaryState = {"
                    + "\n " + super.toString()
                    + "\n }"
                    + "\n }@" + hashCode();
        }
    }

    public static final Consumer<BinaryState<?>>
            activate = BinaryState::backProp,
            deactivate = BinaryState::deactivate;

    /**
     * 'Lapsed listener' issues will arrive if we maintain the only 2 states provided by {@link BinaryState}.
     * The additional states prevent 2 potential issues that may arrive from contentious fast CAS's:
     * <ul>
     *     <li>
     *         Double activation (solved by {@link GenericShuttableActivator#INIT}).
     *         <p> An activator may be triggered twice by an interleaving problem between memory load
     *         (either {@link SysRegister#register(int, IntSupplier, GenericShuttableActivator)} or {@link AbstractMap#put(Object, Object)})
     *         inference and the actual activation once the memory has been load.
     *     </li>
     *     <li>
     *         Lapsed Listener (solved by {@link GenericShuttableActivator#OFF}).
     *         <p> An activator may be stay activated once the activator has been removed from
     *         the reference because of an interleaving previous activation arriving late.
     *     </li>
     * </ul>
     * */
    static class
    GenericShuttableActivator<T, B extends BinaryState<T>> extends Propagator<T> implements Shuttable {
        record defaultActivator() {
            private static final GenericShuttableActivator<Object, ?> ref = new GenericShuttableActivator<>(
                    PathedBinaryState.defaultActivator.ref
            ) {

                @Override
                public void init() {}

                @Override
                public Versioned<Object> backProp() { return null; }

                @Override
                public void deactivate() {}

                @Override
                public void shutOff() {}

                @Override
                public boolean isActive() { return false; }

                @Override
                public boolean isOff() { return true; }

                @Override
                public boolean isDefault() { return true; }

                @Override
                GenericShuttableActivator<Object, PathedBinaryState<Object, Object>> setOwner(State newOwner) {
                    return null;
                }

                @Override
                boolean removeOwner(State toRemove) { return false; }
            };
        }

        /**
         * {@link Activator} MUST return true, to continue activation.
         * */
        static <T> GenericShuttableActivator<T,BinaryState<T>> build(
                Activator activator
        ) {
            return
                    new GenericShuttableActivator<>(
                            new BinaryState<>(
                                    INIT
                            ) {
                                @Override
                                Versioned<T> activate(BooleanSupplier allow, BooleanSupplier onSet) {
                                    if (allow.getAsBoolean()
                                            && onSet.getAsBoolean()
                                    )
                                        activator.activate();
                                    return null;
                                }

                                @Override
                                void softDeactivate() { activator.deactivate(); }
                            }
                    );
        }

        /**
         * {@link Activator} MUST return true, to continue activation.
         * */
        static GenericShuttableActivator<Object,BinaryState<Object>> build(
                Predicates.OfBoolean listener
        ) {
            return new GenericShuttableActivator<>(
                    new BinaryState.Listenable<>(INIT, listener)
            );
        }

        static<S extends BinaryState<Object>> GenericShuttableActivator<Object,BinaryState<Object>> build(
                S activator
        ) {
            return
                    new GenericShuttableActivator<>(
                            activator
                    );
        }

        @SuppressWarnings("unchecked")
        public static<T, B extends BinaryState<T>> GenericShuttableActivator<T, B> getDefault() {
            return (GenericShuttableActivator<T, B>) defaultActivator.ref;
        }

        /**
         * @see BinaryState
         * */
        final B state;

        private volatile State owner = null;

        private static final VarHandle OWNER;

        static {
            try {
                OWNER = MethodHandles.lookup().findVarHandle(
                        GenericShuttableActivator.class, "owner",
                        State.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        boolean hasOwner() { return null != owner; }

        @Override
        public void init() { BinaryState.AA_s.compareAndSet(state, INIT, BinaryState.INACTIVE); }

        GenericShuttableActivator<T, B> setOwner(State newOwner) {
            Object prev = OWNER.getAndSet(this, newOwner);
            boolean itsOwn = false;
            assert newOwner != null && !(itsOwn = (newOwner == this)) :
                    itsOwn ? "Synchronizer must not be its own." : "Synchronizer must not be null.";
            if (
                    prev == null
            ) {
                if (newOwner.isActive()) {
                    if (BinaryState.AA_s.compareAndSet(state, INIT, BinaryState.QUEUEING)) {
                        state.activate(
                                () -> BinaryState.QUEUEING == state.state && newOwner == this.owner
                                , state.setActive);
                    }
                } else {
                    BinaryState.AA_s.compareAndSet(state, INIT, BinaryState.INACTIVE);
                }
                return this;
            } else {
                throw new IllegalStateException("Activator already synchronized by = " + prev + ".");
            }
        }

        boolean removeOwner(State toRemove) {
            Object prev = OWNER.compareAndExchange(this, toRemove, null);
            if (
                    prev == toRemove
            ) {
                B prevS = state;
                int prevState = prevS.state;
                while (
                        BinaryState.INACTIVE < prevState //QUEUEING or ACTIVE... or INIT
                                && !BinaryState.AA_s.weakCompareAndSet(prevS, prevState, GenericShuttableActivator.OFF)
                ) prevState = prevS.state;

                if (BinaryState.INACTIVE < prevState) state.softDeactivate();
                return true;
            }
            return false;
        }

        public static final int
                OFF = 0,
                INIT = 4;

        GenericShuttableActivator(
                B state) {
            int cur;
            assert (cur = state.state) == INIT : "State must be in an INIT state," +
                    "\n current state = " + BinaryState.valueOf(cur) ;
            this.state = state;
        }

        @Override
        public void shutOff() {
            B l_s = state;
            int prevState;
            do {
                prevState = l_s.state;
            } while (
                    !BinaryState.AA_s.weakCompareAndSet(
                    l_s
                    , prevState, GenericShuttableActivator.OFF)
            );
            if (BinaryState.ACTIVE == prevState) l_s.softDeactivate();
        }

        @Override
        public boolean isActive() { return BinaryState.ACTIVE == (int) BinaryState.AA_s.getOpaque(state); }

        @Override
        public boolean isOff() { return state.state == OFF; }

        @Override
        public void deactivate() { state.deactivate(); }

        @Override
        public Versioned<T> backProp() { return state.backProp(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GenericShuttableActivator<?, ?> that = (GenericShuttableActivator<?, ?>) o;
            Object t_s = that.state;
            return (state == t_s) || (state != null && state.equals(t_s));
        }

        @Override
        public int hashCode() { return Objects.hash(state); }

        @Override
        public String toString() {
            return "GenericShuttableActivator{" +
                    "\n >>> state =" + state +
                    ",\n >>> owner = " + (owner == null ? "[null owner]" : "State@".concat(Integer.toString(owner.hashCode()))) +
                    "\n }@".concat(Integer.toString(hashCode()));
        }
    }

    public static class Collection<K, V extends GenericShuttableActivator<?, ? extends BinaryState<?>>>
            extends AbstractMap<K, V>
            implements StateActivator
    {
        static class Default extends Collection<Object, GenericShuttableActivator<?, BinaryState<?>>> {
            public<T> void add(GenericShuttableActivator<T, BinaryState<T>> value) {
                putReified(value, value);
            }
            @SuppressWarnings("unchecked")
            public<T> void putReified(Object key, GenericShuttableActivator<T, BinaryState<T>> value) {
                put(key, (GenericShuttableActivator<?, BinaryState<?>>)(GenericShuttableActivator<?, ?>) value);
            }
        }

        private final AtomicBoolean interrupt = new AtomicBoolean();
        final ConcurrentHashMap<K, V> activators = new ConcurrentHashMap<>();
        final GenericShuttableActivator<Object, BinaryState<Object>> shuttable = new GenericShuttableActivator<>(
                new BinaryState<>(GenericShuttableActivator.INIT) {
                    @Override
                    Versioned<Object> activate(BooleanSupplier allow, BooleanSupplier onSet) {
                        if (allow.getAsBoolean() && onSet.getAsBoolean()) {
                            java.util.Collection<V> vals = activators.values();
                            if (!vals.isEmpty()) {
                                Iterator<V> vIterable = vals.iterator();
                                while (vIterable.hasNext() && !interrupt.getOpaque()) {
                                    Activators.activate.accept(vIterable.next().state);
                                }
                            }
                            Collection.this.onStateChange(true);
                        }
                        return null;
                    }

                    @Override
                    void softDeactivate() {
                        java.util.Collection<V> vals = activators.values();
                        if (!vals.isEmpty()) {
                            Iterator<V> vIterable = vals.iterator();
                            while (vIterable.hasNext() && !interrupt.getOpaque()) {
                                Activators.deactivate.accept(vIterable.next().state);
                            }
                        }
                        Collection.this.onStateChange(false);

                    }
                }
        );

        @Override
        public boolean isEmpty() { return activators.isEmpty(); }

        @Override
        public java.util.Collection<V> values() { return activators.values(); }

        protected void onStateChange(boolean isActive) {}

        @Override
        public void clear() {
            cur_put = errP;
            if (!interrupt.getAndSet(true)) {
                java.util.Collection<V> vals = activators.values();
                if (!vals.isEmpty()) {
                    Iterator<V> vIterable = vals.iterator();
                    while (vIterable.hasNext()) {
                        vIterable.next().shutOff();
                        vIterable.remove();
                    }
                }
            }
        }

        @Override
        public Set<Entry<K, V>> entrySet() { return activators.entrySet(); }

        public static class RepeatedKeyException extends RuntimeException {
            public RepeatedKeyException(Object k, Map<?, ?> map) {
                super("Key [" + k + "] already present in map = " + map);
            }
        }

        @SuppressWarnings("unchecked")
        final BiFunction<K, V, V> putter = (k, v) -> {
            V prev = activators.put(k, (V) v.setOwner(this));
            if (prev != null) {
                v.removeOwner(this);
                throw new RepeatedKeyException(k, activators);
            }
            return v;
        }, errP = (v, k) -> {
            throw new IllegalStateException("Collection already cleared.");
        };
        volatile BiFunction<K, V, V> cur_put = putter;

        @Override
        public V put(K key, V value) throws RepeatedKeyException {
            return cur_put.apply(key, value);
        }

        @Override
        public V get(Object key) { return activators.get(key); }

        @Override
        public V remove(Object key) {
            V removed = activators.remove(key);
            if (removed != null) {
                removed.removeOwner(this);
                return removed;
            }
            else return null;
        }

        @Override
        public boolean isActive() { return shuttable.isActive(); }

        @Override
        public boolean activate() {
            shuttable.backProp();
            return shuttable.isActive();
        }

        @Override
        public void deactivate() { shuttable.deactivate(); }

        @Override
        public String toString() {
            return "Collection{"
                    + "\n >>> activators=" + activators
                    + ",\n >>> coreActivator=" + shuttable
                    + ",\n >>> interrupt=" + interrupt
                    + "\n }";
        }
    }

    static class SysRegister implements StateActivator {

        static class State implements Activators.State {
            final boolean state;
            final int ver;
            final GenericShuttableActivator<?, ? extends BinaryState<?>> activator;

            boolean same(GenericShuttableActivator<?, ? extends BinaryState<?>> that) { return activator.equals(that); }

            boolean olderThan(int that) { return ver < that; }

            public GenericShuttableActivator<?, ? extends BinaryState<?>> getActivator() { return activator; }

            record StateRefs() {
                static final State
                        defaultFalse = getState(false),
                        defaultTrue = getState(true);
            }

            private static State getState(boolean initialState) {
                return new State(
                        initialState,
                        -1, GenericShuttableActivator.defaultActivator.ref) {
                    @Override
                    public GenericShuttableActivator<Object, BinaryState<Object>> getActivator() { return null; }

                    @Override
                    GenericShuttableActivator<Object, ? extends BinaryState<Object>> unsync(
                    ) {
                        return null;
                    }

                    @Override
                    public boolean isActive() { return initialState; }

                    @Override
                    boolean same(GenericShuttableActivator<?, ? extends BinaryState<?>> that) {
                        return that.isDefault();
                    }

                    @Override
                    void sync() {}

                    @Override
                    State get(
                            boolean newState
                    ) {
                        return newState ? StateRefs.defaultTrue : StateRefs.defaultFalse;
                    }

                    @Override
                    public String toString() {
                        return "<DEFAULT_STATE[state = " + initialState + "]@" + hashCode() + ">";
                    }
                };
            }

            State(
                    boolean initialState,
                    int ver, GenericShuttableActivator<?, ? extends BinaryState<?>> activator) {
                this.state = initialState;
                this.ver = ver;
                this.activator = activator;
            }

            State get(
                    boolean newState
            ) {
                return new State(
                        newState,
                        ver, activator);
            }

            void sync(
            ) {
                BinaryState<?> b_state = activator.state;
                if (state) {
                    if (BinaryState.AA_s.compareAndSet(b_state, GenericShuttableActivator.INIT, BinaryState.QUEUEING)) {
                        b_state.sysActivate();
                    }
                }
                else {
                    BinaryState.AA_s.compareAndSet(b_state, GenericShuttableActivator.INIT, BinaryState.INACTIVE);
                }
            }

            State forNew(int newVer, GenericShuttableActivator<?, ? extends BinaryState<?>> activator) {
                return new State(state, newVer, activator);
            }

            @Override
            public boolean isActive() { return state; }

            GenericShuttableActivator<?, ? extends BinaryState<?>>
            unsync(
            ) {
                activator.shutOff();
                return activator;
            }

            @Override
            public String toString() {
                return "State{" +
                        "\n >>> state=" + state +
                        ",\n >>> ver=" + ver +
                        ",\n >>> activator state =" + activator.state +
                        "\n }State@".concat(Integer.toString(hashCode()));
            }
        }

        public SysRegister() { this.innerState = State.StateRefs.defaultFalse; }

        private volatile State innerState;

        public GenericShuttableActivator<?, ? extends BinaryState<?>> getActivator() {
            return innerState.activator;
        }
        static final VarHandle INNER_STATE;

        static {
            try {
                INNER_STATE = MethodHandles.lookup().findVarHandle(
                        SysRegister.class, "innerState",
                        State.class);
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /**
         * @return previous {@link GenericShuttableActivator} activator
         * */
        @SuppressWarnings("unchecked")
        <T, P extends BinaryState<T>> GenericShuttableActivator<T, P>
        register(
                int newVer
                , IntSupplier volatileCheck
                , GenericShuttableActivator<T, P> gs
        ) {
            assert !gs.isOff() : "GenericShuttableActivator must not be 'off'";
            State prev = innerState, next;

            if (prev.ver < newVer && newVer == volatileCheck.getAsInt()) {
                    next = prev.forNew(newVer, gs);
                    if (WEAK_CAS(prev, next)) {
                        if (newVer != volatileCheck.getAsInt()) {
                            gs.shutOff();
                        }
                        return (GenericShuttableActivator<T, P>) prev.getActivator();
                    } else {
                        State last = prev;
                        prev = innerState;
                        if (prev.ver < newVer) {
                            if (last != prev) {
                                next = prev.forNew(newVer, gs);
                            }
                            //spin
                            do {
                                if (newVer == volatileCheck.getAsInt()) {
                                    if (WEAK_CAS(prev, next)) {
                                        if (newVer != volatileCheck.getAsInt()) {
                                            gs.shutOff();
                                        }
                                        return (GenericShuttableActivator<T, P>) prev.getActivator();
                                    }
                                    last = prev;
                                    prev = innerState;
                                    if (last != prev) {
                                        if (newVer != volatileCheck.getAsInt()) return null;
                                        next = prev.forNew(newVer, gs);
                                    }
                                } else return null;
                            } while (prev.ver < newVer);
                        }
                        return null;
                    }
            } else return null;
        }

        boolean WEAK_CAS(State prev, State next) {
            if (INNER_STATE.weakCompareAndSet(this, prev, next)) {
                prev.unsync();
                if (next == innerState) {
                    next.sync();
                    return true;
                } else return false;
            } else return false;
        }

        public boolean isRegistered() { return !innerState.activator.isDefault(); }

        public GenericShuttableActivator<?, ? extends BinaryState<?>> unregister() {
            State prev = innerState;
            if (prev.state) {
                if (INNER_STATE.compareAndSet(this, prev, State.StateRefs.defaultTrue)) {
                    return prev.unsync();
                } else return null;
            } else {
                if (INNER_STATE.compareAndSet(this, prev, State.StateRefs.defaultFalse)) {
                    return prev.unsync();
                } else return null;
            }
        }

        public boolean unregister(
                GenericShuttableActivator<?, ? extends BinaryState<?>> expect
        ) {
            State prev = innerState;
            if (prev.activator.equals(expect)) {
                if (prev.state) return strongCAS(prev, State.StateRefs.defaultTrue);
                else return strongCAS(prev, State.StateRefs.defaultFalse);
            } else return false;
        }

        public boolean unregister(
                Predicate<BinaryState<?>> expect
        ) {
            State prev = innerState;
            if (expect.test(prev.activator.state)) {
                if (prev.state) return strongCAS(prev, State.StateRefs.defaultTrue);
                else return strongCAS(prev, State.StateRefs.defaultFalse);
            } else return false;
        }

        boolean strongCAS(
                State expect
                , State set
        ) {
            if (INNER_STATE.compareAndSet(this, expect, set)) {
                expect.unsync();
                if (set == INNER_STATE.getOpaque(this)) {
                    set.sync();
                    return true;
                } else return false;
            }
            else return false;
        }

        @Override
        public boolean isActive() { return innerState.isActive(); }

        @Override
        public boolean activate() {
            State prev = innerState, newS;
            while (
                    !prev.state
            ) {
                newS = prev.get(true);
                if (INNER_STATE.weakCompareAndSet(this, prev, newS)) {
                    newS.activator.backProp();
                    if (newS != INNER_STATE.getOpaque(this)) {
                        newS.unsync();
                    }
                    return true;
                }
                prev = innerState;
            }
            return false;
        }

        @Override
        public void deactivate() {
            State prev = innerState, newS;
            while (
                    prev.state
            ) {
                newS = prev.get(false);
                if (INNER_STATE.compareAndSet(this, prev, newS)) {
                    newS.activator.deactivate();
                    return;
                }
                prev = innerState;
            }
        }

        @Override
        public String toString() {
            return "SysRegister{" +
                    "\n >>> innerState = " + innerState +
                    "\n }@".concat(Integer.toString(hashCode()));
        }
    }
}