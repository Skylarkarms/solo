package com.skylarkarms.solo;

import com.skylarkarms.concur.LazyHolder;
import com.skylarkarms.lambdas.*;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.*;

/**
 * Model is a class designed to facilitate a structured separation of concerns.
 * <p> The class is able to coordinate multiple Path activations as a whole.
 * */
public class Model extends Activators.SysActivator {

    /**
     * Used by {@link ModelStore}'s {@link Activators.Activator} via the different {@link Type}s.
     * Preventing a cast inference ('instanceOf') by providing a virtual method.
     * There are 2 levels of virtuality since the only Models allowed to be handled by the Store are those which ar defined as 'core' models.
     * */
    @Override
    boolean sysActivate() { return super.sysActivate(); }

    @Override
    void sysDeactivate() { super.sysDeactivate(); }

    /**
     * The last step before a complete Model removal.
     * */
    void sysDestroy() { onDestroy(); }

    /**
     * Callback for when this Model has been removed from its storage, either
     * */
    protected void onDestroy() {}

    void setOwner(Activators.State owner) {}
    void init() {}
    void removeOwner(Activators.State toRemove) {}

    @SafeVarargs
    public static void store(ModelStore.Singleton.Entry<? extends Model>... models) { Settings.load(models); }

    public static <M extends Model> M get(Class<M> modelClass) { return Settings.modelStore.get(modelClass); }

    public enum Type {
        /**
         * The stored {@link Model} is never affected by changes in the Collection {@link Activators.Activator}'s state,
         * unless the Collection is {@link ModelStore#shutdown()} forcing a {@link #sysDeactivate()} on any Model that has been previously {@link #sysActivate()}
         * */
        guest(
                model -> Activators.defaultActivator.ref
        ),
        /**
         * This entry will be synchronized with the {@link LazyHolder.SingletonCollection}'s
         * {@link Activators.Activator}'s phases as soon as possible,
         * forcing a {@code LazyHolder#CREATING_PHASE} on the Model assigned to it, if it hasn't been triggered yet.
         * */
        core(
                model -> new Activators.Activator() {
                    @Override
                    public boolean activate() { return model.get().sysActivate(); }

                    @Override
                    public void deactivate() {
                        final Model m = model.getOpaque();
                        if (m != null) m.sysDeactivate();
                    }
                }
        ),
        /**
         * This type of StoreEntry will ONLY synchronize the Model the moment the user first triggers its initialization via {@link Model#get(Class)}
         * */
        lazy_core(
                modelS -> new Activators.Activator() {
                    @Override
                    public boolean activate() {
                        final Model m = modelS.getOpaque();
                        if (m != null) return m.sysActivate();
                        else return false;
                    }

                    @Override
                    public void deactivate() {
                        final Model m = modelS.getOpaque();
                        if (m != null) m.sysDeactivate();
                    }
                }
        );

        final Function<LazyHolder.Supplier<? extends Model>, Activators.Activator> activ;

        Type(Function<LazyHolder.Supplier<? extends Model>, Activators.Activator> activ) { this.activ = activ; }
    }

    /**
     * See {@link Model}
     * LiveModel is part of the proactive tools of the Singular reactive system.
     *
     * This action will grant "scope coupling", this means Paths observations can be
     * achieved simultaneously, and every emission source will be "synchronized" with the lifecycle of the Model.
     * The Model class grants easy access to proactive coupling classes and/or methods such as:
     * <ul>
     *     <li>
     *         {@link Getter} via its {@link #asGetter(Path)} method.
     *         The Getter class allows the proactive access of cached values.
     *         This Getter class lifecycle will be tied to the Model's lifecycle.
     *     </li>
     *     <li>
     *         Direct scope coupling via {@link #sync(Path, Consumer)}
     *         Which will connect an observer instance tied to the Model's lifecycle.
     *     </li>
     * </ul>
     * */
    public static class Live extends Model implements Activators.StateActivator {

        final Activators.Collection.Default activators = new Activators.Collection.Default() {
            @Override
            protected void onStateChange(boolean isActive) {
                Live.this.onStateChange(isActive);
            }
        };

        final Activators.GenericShuttableActivator<Object, Activators.BinaryState<Object>> activator = activators.shuttable;
        final Activators.BinaryState<Object> innerActivator = activator.state;

        private static final String IModel_error = "IModel must return a ModelComponent non-null object";

        @Override
        boolean sysActivate() {
            activator.backProp();
            return innerActivator.isActive();
        }

        @Override
        void sysDeactivate() { innerActivator.deactivate(); }

        private final Runnable normal_deact = innerActivator::deactivate;
        /**
         * This lambda action will get called by {@link #activate()} whenever an owner has not been set.
         * When this occurs, the Model will be assured as not be 'owned' by any external {@link Activators.Activator}.
         * All {@link Live} instances, by default, begin in an {@link Activators.GenericShuttableActivator#INIT} state,
         * which allows a proper atomic synchronization with its owner Activator (see {@link Activators.GenericShuttableActivator}).
         * To allow for this activation to properly swap the state, the CAS should include the {@link Activators.GenericShuttableActivator#INIT} state.
         * */
        private final BooleanSupplier normal_act = () -> {
            int prev;
            if (
                    (
                            Activators.BinaryState.INACTIVE == (prev = (int) Activators.BinaryState.AA_s.getOpaque(innerActivator))
                                    || Activators.GenericShuttableActivator.INIT == prev
                    )
                            && Activators.BinaryState.AA_s.compareAndSet(innerActivator, prev, Activators.BinaryState.QUEUEING)) {
                innerActivator.sysActivate();
                return Activators.BinaryState.ACTIVE == (int) Activators.BinaryState.AA_s.getOpaque(innerActivator);
            } else return false;
        };
        volatile BooleanSupplier act = normal_act;
        volatile Runnable deact = normal_deact;

        @Override
        void setOwner(Activators.State owner) {
            activator.setOwner(owner);
            act = () -> {
                throw new IllegalStateException("Model already owned by " + owner);
            };
            deact = () -> {
                throw new IllegalStateException("Model already owned by " + owner);
            };
        }

        @Override
        void init() { activator.init(); }

        @Override
        void removeOwner(Activators.State toRemove) {
            activator.removeOwner(toRemove);
            act = normal_act;
            deact = normal_deact;
        }

        @Override
        public boolean activate() { return act.getAsBoolean(); }

        @Override
        public void deactivate() { deact.run(); }

        @Override
        public boolean isActive() { return activator.isActive(); }

        /**
         * When a class cannot extend the Model class directly this interface
         * facilitates the implementation of a ModelComponent.
         * <p> I know Goetz would never like this btw...
         * */
        public interface ILiveModel {

            Live getComponent();

            static ILiveModel construct(Live liveModel) {
                assert liveModel != null;
                return () -> liveModel;
            }

            static ILiveModel construct() {
                final Live liveModel = new Live();
                return () -> liveModel;
            }

            private Live getModel() {
                return Objects.requireNonNull(getComponent(),
                        IModel_error);
            }
            default  <T, P extends Publisher<T>> P sync(P path, Consumer<? super T> consumer) {
                return getComponent().sync(path, consumer);
            }
            default <T, S> Getter<S> asGetter(Path<T> path, Function<T, S> map) {
                return getComponent().asGetter(path, map);
            }
            default <T> Getter<T> asGetter(Path<T> path) { return getComponent().asGetter(path); }

            default void clear() { getComponent().clear(); }
        }

        protected Live() { this((Live) null); }

        /**
         * Gets a new instance that will use the {@link #Live(Live)} constructor.
         * */
        public static Live getNewComponent(Live liveModel) { return new Live(liveModel); }

        private Consumer<
                Activators.GenericShuttableActivator<Object, Activators.BinaryState<Object>>
                > unSync = Lambdas.Consumers.getDefaultEmpty();

        /**
         * Gets a new Model instance.
         * This component can override it's {@link #onStateChange(boolean)} method to listen for activation and deActivation callbacks.
         * */
        public Live(
                Live parent
        ) {
            if (parent != null) {
                parent.add(this.activator, this.activator);
                unSync = parent::remove;
            }
        }

        public Live(
                ILiveModel parent
        ) {
            if (parent != null) {
                parent.getModel().add(this.activator, this.activator);
                unSync = parent.getModel()::remove;
            }
        }

        public static<Event> ILiveModel getInstance(
                Producer<Event> lifecycleProducer, Event ON, Event OFF, Event DESTROY
        ) {
            return ILiveModel.construct(
                    new Live(lifecycleProducer, ON, OFF, DESTROY)
            );
        }

        private static final String
                noDebugModeErr_1 = "\n Set Settings.debug_mode = true for more information.";

        /**
         * This constructor will call {@link #clear()} which will remove all
         * inner {@link Activators} inside the Model2 extension.
         * @implNote A state in which this Model2 is the child of a parent Model2 will never occur
         * */
        public <Event> Live(
                Producer<Event> lifecycleProducer, Event ON, Event OFF, Event DESTROY
        ) {
            final Supplier<String> provSup = Settings.DEBUG_MODE.ref ? new Supplier<>() {
                final StackTraceElement[] es = Thread.currentThread().getStackTrace();
                final Supplier<String> mem = new InstanceSupplier.Impl.Synchronized<>(es,
                        () -> Exceptionals.formatStack(0, es)
                );

                @Override
                public String get() {
                    return mem.get();
                }
            } : () -> noDebugModeErr_1;
            final Activators.SimpleStateActivator state = new Activators.SimpleStateActivator() {
                @Override
                protected boolean sysActivate() {
                    innerActivator.backProp();
                    return true;
                }

                @Override
                protected void sysDeactivate() { innerActivator.deactivate(); }

                @Override
                public String toString() {
                    return super.toString().concat(
                            "\n [Synchronized Constructor]." + provSup.get()
                    );
                }
            };
            setOwner(state);
            lifecycleProducer.register(
                    event -> {
                        if (ON == event) {
                            state.activate();
                        } else if (OFF == event) {
                            state.deactivate();
                        } else if (DESTROY == event) {
                            clear();
                            removeOwner(state);
                        }
                    }
            );
        }

        private <V, T extends Activators.GenericShuttableActivator<V, Activators.BinaryState<V>>> T add(Object key, T t) {
            try {
                activators.putReified(key, t);
            } catch (Exception | Error e) {
                if (e instanceof Activators.Collection.RepeatedKeyException) {
                    throw new IllegalStateException(
                            "Key[" + t + "] " +
                                    "\n already synchronized to this Model[" + this + "]."
                            , e
                    );
                }
                else throw e;

            }
            return t;
        }

        private <S extends Activators.StatefulActivator> S add(S s) { return add(s, s); }
        private <S extends Activators.StatefulActivator> S add(Object key, S s) {
            try {
                activators.putReified(key, Activators.GenericShuttableActivator.build((Activators.BinaryState<Object>) s));
            } catch (Exception | Error e) {
                if (e instanceof Activators.Collection.RepeatedKeyException) {
                    throw new IllegalStateException(
                            "StatefulActivator[" + s + "] " +
                                    "\n already synchronized to this Model[" + this + "]."
                            , e
                    );
                }
                throw e;
            }
            return s;
        }

        private <S extends Activators.StatefulActivator> Activators.BinaryState<?> remove(Object key) {
            Activators.GenericShuttableActivator<?, Activators.BinaryState<?>> gs = activators.remove(key);
            if (gs != null) {
                gs.removeOwner(this);
                return activator.state;
            } else return null;
        }

        protected boolean contains(Object key) { return activators.containsKey(key); }

        @SuppressWarnings("unchecked")
        protected<S, T, P1 extends Path<S>> Path<T> sync(
                Executor executor, P1 path, Function<S, T> map, Consumer<? super T> consumer) {
            return sync(executor,
                    Lambdas.Identities.isIdentity(map) ? (Path<T>) path : path.map(map),
                    consumer);
        }

        protected<T, P1 extends Path<T>> Path<T> sync(Executor executor, P1 path, Consumer<? super T> consumer) {
            add(
                    consumer,
                    Activators.GenericShuttableActivator.build(
                            Activators.getBaseActivator(
                                    executor, path, consumer
                            )
                    )
            );
            return path;
        }

        protected<T, P1 extends Path<T>> Path<T> sync(P1 path, Consumer<? super T> consumer) {
            return sync(null, path, consumer);
        }

        /**
         * @param consumer will be assigned as key.
         * */
        protected<T, P extends Publisher<T>> P sync(P publisher, Consumer<? super T> consumer) {
            add(consumer,
                    Activators.GenericShuttableActivator.build(
                            new Activators.Activator() {
                                @Override
                                public boolean activate() {
                                    publisher.add(consumer);
                                    // the consumer may get detached during addition.
                                    return publisher.contains(consumer);
                                }

                                @Override
                                public void deactivate() { publisher.remove(consumer); }
                            }
                    )
            );
            return publisher;
        }

        /**
         * @param consumer {@link BiConsumer} will be assigned as key.
         * @implNote
         * {@link BiConsumer} will give a result IF and ONLY IF both results are NOT {@code null}
         *
         * */
        protected<
                T, P extends Path<T>
                , S, P2 extends Path<S>
                > void sync(
                P path,
                P2 path2,
                BiConsumer<
                        ? super T
                        , ? super S
                        > consumer) {
            add(consumer,
                    Activators.GenericShuttableActivator.build(
                            new Activators.Activator() {
                                record Tuple<T, S>(T t, S s){}
                                final Path<Tuple<T, S>> res = path.switchMap(
                                        t -> path2.map(
                                                s -> new Tuple<>(t, s)
                                        )
                                );
                                final Consumer<Tuple<T, S>>
                                        finalConsumer = tsTuple ->
                                        consumer.accept(tsTuple.t, tsTuple.s);
                                @Override
                                public boolean activate() {
                                    res.add(finalConsumer);
                                    // the consumer could never be detached during addition.
                                    return true;
                                }

                                @Override
                                public void deactivate() { res.remove(finalConsumer); }
                            }
                    )
            );
        }

        protected<Key, O extends Predicates.OfBoolean> O syncToKey(Key key, O listener) {
            add(key,
                    Activators.GenericShuttableActivator.build(listener)
            );
            return listener;
        }

        protected<O extends Predicates.OfBoolean> O sync(O listener) {
            add(listener,
                    Activators.GenericShuttableActivator.build(listener)
            );
            return listener;
        }

        protected<T> Getter<T> asGetter(Path<T> path) { return asGetter(path, Lambdas.Identities.identity()); }

        /**
         * This method will use both:
         * @param path the source to be proactively listened. and
         * @param map the mapping function.
         *
         * <p> AS comparable keys.
         * <p> This means that If 2 '#sync(Path, Function)' are executed in the same scope, with the exact same parameters, an exception will throw.
         * <p> This may ALSO take effect when using lambda functions (method references)... if the VM decides to reuse the 'map' synthetic lambda function (as it does during later optimization stages).
         * <p> This is a desired behavior since BOTH sync will be effectively equal.
         * */
        protected<T, S> Getter<S> asGetter(Path<T> path, Function<T, S> map) {
            Getter<S> res;
            return add(res = new Getter<>(Activators.GenericShuttableActivator.INIT, path, map) {
                @Override
                public S get() {
                    try {
                        return super.get();
                    } catch (Exception e) {
                        throw getRuntimeException(e);
                    }
                }

                private RuntimeException getRuntimeException(Exception e) {
                    final boolean activeMod;
                    if (e instanceof NonActiveException) {
                        return new RuntimeException(
                                "Either this model, or the Getter itself needs to be activated."
                                        + "\n Model = " + Live.this.getClass().getSimpleName() + "@" + Live.this.hashCode()
                                        + "\n isActive ? = " + (activeMod = Live.this.isActive())
                                        + "\n If the activation signal is getting too long to reach this Getter [" + activeMod + "], "
                                        + "\n use .passiveFirst(Consumer<? super T> consumer) or passiveGet() instead."
                                , e);
                    } else {
                        return new RuntimeException(e);
                    }
                }

                @Override
                public boolean first(Consumer<? super S> consumer) {
                    try {
                        return super.first(consumer);
                    } catch (Exception e) {
                        throw getRuntimeException(e);
                    }
                }
            }, res);
        }

        protected<S extends Activators.StatefulActivator>  S sync(S s) { return add(s, s); }

        @SuppressWarnings("unchecked")
        protected<S extends Activators.StatefulActivator>  S unSync(S s) { return (S) remove(s); }

        protected void onStateChange(boolean isActive) {}

        private void clear() {
            activators.clear();
            final Consumer<Activators.GenericShuttableActivator<Object, Activators.BinaryState<Object>>> unsyncr = unSync;
            unSync = Lambdas.Consumers.getDefaultEmpty();
            unsyncr.accept(this.activator);
        }

        @Override
        void sysDestroy() {
            if (activator.isActive()) deact.run();
            clear();
            super.sysDestroy();
        }

        volatile boolean buildingS = false;
        @Override
        public String toString() {
            if (buildingS) return "[Self-Contained]".concat(
                    "Model.".concat(getClass().getSimpleName()).concat(
                            "@" + hashCode()
                    )
            );
            buildingS = true;
            String res = "Model.".concat(getClass().getSimpleName()).concat("{" +
                    "\n >>> activators size=" + activators.size() +
                    "\n >>> activators=" + activators +
                    ",\n >>> superClass =" + super.toString() +
                    "\n }");
            buildingS = false;
            return res;
        }
    }
}