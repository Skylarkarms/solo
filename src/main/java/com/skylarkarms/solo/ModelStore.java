package com.skylarkarms.solo;

import com.skylarkarms.concur.LazyHolder;
import com.skylarkarms.concur.Locks;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.ToStringFunction;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ModelStore<K> extends Activators.StatefulActivator {

    static volatile Locks.ExceptionConfig<RuntimeException> globalSpinnerConfig = Locks.ExceptionConfig.runtime_5();
    static volatile boolean grabbed;
    record MODEL_CONFIG(){
        static {grabbed = true;}
        static final Locks.ExceptionConfig<RuntimeException> ref = globalSpinnerConfig;
    }

    public static synchronized void setTimeoutConfig(Locks.ExceptionConfig<RuntimeException> newConfig) {
        if (newConfig == null) throw new IllegalArgumentException("Cannot pass a null configuration.");
        if (globalSpinnerConfig != Locks.ExceptionConfig.runtime_5()) throw new IllegalArgumentException("Only one set per application.");
        if (grabbed) throw new IllegalStateException("A configuration instance has already being initialized by a Model");
        globalSpinnerConfig = newConfig;
    }

    public static synchronized void setUnbridled() {
        setTimeoutConfig(Locks.ExceptionConfig.unbridled());
    }

    static final class ModelSupplier<M extends Model>
            extends LazyHolder.Supplier<M>
            implements
            Activators.Activator
    {

        final Activators.Activator activator;
        Activators.State storeState;
        final Consumer<Activators.State> lazy_sync;

        @Override
        public boolean activate() { return activator.activate(); }

        @Override
        public void deactivate() { activator.deactivate(); }

        @SuppressWarnings("unchecked")
        public ModelSupplier(
                Locks.ExceptionConfig<RuntimeException> config
                , Model.Type type,
                java.util.function.Supplier<M> mSupplier
        ) {
            this(new Consumer[1], config, type, mSupplier);
        }

        private ModelSupplier(
                final Consumer<M>[] lazy_assign,
                Locks.ExceptionConfig<RuntimeException> config
                , Model.Type type,
                java.util.function.Supplier<M> mSupplier
        ) {
            super(config
                    ,
                    () -> {
                        M res = mSupplier.get();
                        lazy_assign[0].accept(res);
                        return res;
                    }
            );
            this.activator = type.activ.apply(this);
            if (type == Model.Type.guest) {
                lazy_sync = Lambdas.Consumers.getDefaultEmpty();
                lazy_assign[0] = Model::init;
            } else {
                lazy_sync = state -> this.storeState = state;
                lazy_assign[0] = m -> m.setOwner(storeState);
            }
        }

        public ModelSupplier(
                Consumer<Locks.Config.Builder> params
                , Model.Type type
                , java.util.function.Supplier<M> mSupplier
        ) {
            this(Locks.ExceptionConfig.runtime(globalSpinnerConfig.getConfig(), params), type, mSupplier);
        }

        final void sync(Activators.State state) {
            // only if {@link lazy_core}
            lazy_sync.accept(state);
        }

        @Override
        public M getAndClear() {
            M destroyed =  super.getAndClear();
            if (destroyed != null && storeState != null) destroyed.removeOwner(storeState);
            if (destroyed != null) {
                if (storeState != null) {
                    destroyed.removeOwner(storeState);
                }
                destroyed.sysDestroy();
            }
            return null;
        }

        public ModelSupplier(
                Model.Type type,
                java.util.function.Supplier<M> mSupplier
        ) {
            this(MODEL_CONFIG.ref, type, mSupplier);
        }

        public ModelSupplier(Supplier<M> mSupplier) { this(Model.Type.guest, mSupplier); }
    }

    final LazyHolder.KeyedCollection<
            K
            , ModelSupplier<? extends Model>
            > modelStore;

    public <M extends Model> LazyHolder.Supplier<M> put(
            K key
            , Consumer<Locks.Config.Builder> paramBuilder
            , Supplier<M> supplier
    ) {
        return sysPut(
                key
                , Locks.ExceptionConfig.runtime(globalSpinnerConfig.getConfig(), paramBuilder)
                , Model.Type.guest
                , supplier
        );
    }
    public <M extends Model> LazyHolder.Supplier<M> put(
            K key
            , Locks.ExceptionConfig<RuntimeException> config
            , Supplier<M> supplier
    ) {
        return sysPut(
                key
                , config
                , Model.Type.guest
                , supplier
        );
    }
    public <M extends Model> LazyHolder.Supplier<M> put(
            K key
            , Supplier<M> supplier
    ) {
        return sysPut(
                key
                , MODEL_CONFIG.ref
                , Model.Type.guest
                , supplier
        );
    }
    public <M extends Model.Live> LazyHolder.Supplier<M> put(
            K key
            , Consumer<Locks.Config.Builder> paramBuilder
            , Model.Type type
            , Supplier<M> supplier

    ) {
        return sysPut(
                key
                , Locks.ExceptionConfig.runtime(globalSpinnerConfig.getConfig(), paramBuilder)
                , type
                , supplier
        );
    }
    private <M extends Model> LazyHolder.Supplier<M> sysPut(
            K key
            , Locks.ExceptionConfig<RuntimeException> config
            , Model.Type type
            , Supplier<M> supplier

    ) {
        final ModelSupplier<M> ms = new ModelSupplier<>(
                config
                , type
                , supplier
        );
        modelStore.put(
                key
                , ms
        );
        return ms;
    }

    public static final class ModelEntry<K, M extends Model> extends LazyHolder.KeyedCollection.SupplierEntry<M, K, ModelSupplier<M>> {

        @SuppressWarnings("unchecked")
        public static<K> ModelEntry<K, Model> get(
                K key
                , Consumer<Locks.Config.Builder> paramBuilder
                , Supplier<? extends Model> supplier
        ) {
            return new ModelEntry<>(
                    key
                    , paramBuilder
                    , Model.Type.guest
                    , (Supplier<Model>) supplier
            );
        }

        public static<K, M extends Model.Live> ModelEntry<K, M> get(
                K key
                , Consumer<Locks.Config.Builder> paramBuilder
                , Model.Type type
                , LazyHolder.Supplier<M> supplier
        ) {
            return new ModelEntry<>(
                    key
                    , paramBuilder
                    , type
                    , supplier
            );
        }

        private ModelEntry(K key
                , Consumer<Locks.Config.Builder> paramBuilder
                , Model.Type type
                , Supplier<M> supplier
        ) {
            super(key,
                    new ModelSupplier<>(
                            Locks.ExceptionConfig.runtime(globalSpinnerConfig.getConfig(), paramBuilder)
                            , type
                            , supplier
                    )
            );
        }
    }

    @SafeVarargs
    public static<K> ModelStore<K> populate(
            ModelEntry<K, ? extends Model>... models) { return populate(true, models); }

    @SuppressWarnings("unchecked")
    @SafeVarargs
    public static<K> ModelStore<K> populate(
            boolean unmodifiable
            , ModelEntry<K, ? extends Model>... models
    ) {
        return new ModelStore(unmodifiable
                , models
        );
    }

    /**
     * Offers a pre-populated Collection.
     * */
    @SuppressWarnings("unchecked")
    @SafeVarargs
    <E extends LazyHolder.KeyedCollection.SupplierEntry<? extends Model, K, ModelSupplier<? extends Model>>>
    ModelStore(
            boolean unmodifiable
            , E... entries
    ) {
        if (Objects.requireNonNull(entries).length == 0) throw new IllegalStateException("models length is 0");
        modelStore = new LazyHolder.KeyedCollection(
                unmodifiable, entries) {
            @Override
            protected void onAdded(LazyHolder.Supplier value) {
                ((ModelSupplier<? extends Model>) value).sync(ModelStore.this);
            }
        };
    }

    public ModelStore(
    ) {
        modelStore = new LazyHolder.KeyedCollection<>() {
            @Override
            protected void onAdded(ModelSupplier<? extends Model> value) {
                value.sync(ModelStore.this);
            }
        };
    }

    private final ToStringFunction<K> notFoundErr =
            key -> "Key [" + key + "] not found in collection: " + ModelStore.this.modelStore;

    @SuppressWarnings("unchecked")
    public <M extends Model> M remove(K key){
        ModelSupplier<M> ms = (ModelSupplier<M>) modelStore.remove(key);
        if (ms == null) throw new NullPointerException(notFoundErr.apply(key));
        return ms.getAndClear();
    }

    boolean terminated;

    @Override
    void sysOnActive() {
        if (terminated) return;
        modelStore.forEach(
                Activators.Acts.active
        );
    }

    @Override
    void sysOnDeactive() {
        if (terminated) return;
        modelStore.forEach(
                Activators.Acts.inactive
        );
    }

    public void shutdown() {
        if (isActive()) deactivate();
        terminated = true;
        modelStore.forEach(
                (k, modelSupplier) -> modelSupplier.getAndClear()
        );
    }

    /**
     * As opposed to {@link #lazyGet(Object)} this call will {@code eagerly} retrieve and build
     * the {@link Model} on the site of call if it hasn't been built yet, then perform a cast.
     * */
    @SuppressWarnings("unchecked")
    public <M extends Model> M get(K key) {
        final ModelSupplier<M> ms = (ModelSupplier<M>) modelStore.get(key);
        if (ms == null) throw new NullPointerException(notFoundErr.apply(key));
        return ms.get();
    }

    /**
     * Will get a {@link LazyHolder.Supplier} associated wit the key.
     * <p>
     * */
    @SuppressWarnings("unchecked")
    public <M extends Model> LazyHolder.Supplier<M> lazyGet(K key) {
        ModelSupplier<M> ms = (ModelSupplier<M>) modelStore.get(key);
        if (ms == null) throw new NullPointerException(notFoundErr.apply(key));
        return ms;
    }

    public static final class Singleton extends ModelStore<Class<? extends Model>> {

        private final String tag;

        @SafeVarargs
        <E extends LazyHolder.KeyedCollection.SupplierEntry<? extends Model, Class<? extends Model>, ModelSupplier<? extends Model>>>
        Singleton(boolean unmodifiable, E... entries) {
            super(unmodifiable, entries);
            tag = "Singleton.";
        }
        @SafeVarargs
        <E extends LazyHolder.KeyedCollection.SupplierEntry<? extends Model, Class<? extends Model>, ModelSupplier<? extends Model>>>
        Singleton(String tag, boolean unmodifiable, E... entries) {
            super(unmodifiable, entries);
            this.tag = tag;
        }

        public Singleton() {
            super();
            tag = "Singleton.";
        }

        public static class Entry<M extends Model>
                extends LazyHolder.KeyedCollection.SupplierEntry<M, Class<M>, ModelSupplier<M>> {

            private Entry(
                    Model.Type type
                    , Locks.ExceptionConfig<RuntimeException> config
                    , Class<M> key
                    , Supplier<M> value) {
                super(key, new ModelSupplier<>( config, type, value));
            }

            public static<LM extends Model.Live> Entry<LM> get(
                    Model.Type type
                    , Class<LM> key
                    , Supplier<LM> value) {
                return new Entry<>(type, MODEL_CONFIG.ref, key, value);
            }

            /**
             * Defaults to type {@link Model.Type#guest}
             * */
            public static<M extends Model> Entry<M> get(
                    Class<M> key
                    , Supplier<M> value) {
                return new Entry<>(Model.Type.guest, MODEL_CONFIG.ref, key, value);
            }
        }

        @SafeVarargs
        public static Singleton populate(
                String tag,
                Entry<? extends Model>... entries
        ) {
            return populate(tag, true, entries);
        }

        @SuppressWarnings("unchecked")
        @SafeVarargs
        public static Singleton populate(
                boolean immutable
                , Entry<? extends Model>... entries
        ) {
            return new Singleton(
                    immutable
                    , (LazyHolder.KeyedCollection.SupplierEntry[])entries
            );
        }

        @SuppressWarnings("unchecked")
        @SafeVarargs
        public static Singleton populate(
                String tag,
                boolean immutable
                , Entry<? extends Model>... entries
        ) {
            return new Singleton(
                    tag,
                    immutable
                    , (LazyHolder.KeyedCollection.SupplierEntry[])entries
            );
        }

        @Override
        public String toString() { return tag.concat(super.toString()); }
    }

    @Override
    public String toString() {
        return "ModelStore{" +
                "\n >>> modelStore=" + modelStore +
                ",\n >>> terminated=" + terminated +
                "}@".concat(Integer.toString(hashCode()));
    }
}