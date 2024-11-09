package com.skylarkarms.solo;

import com.skylarkarms.concur.LazyHolder;
import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.Exceptionals;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.Predicates;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Class that serves as object referent.
 * The {@link Ref} class has an 'injective' relationship (one-to-one) between {@link Path} and {@link Ref},
 * where only one Path can be {@link Path#assign(Eager)} to one Ref,
 * and\or one Ref can be {@link Eager#allocate(Impl)}-ed to one Path.
 * This class will adopt the value of a given {@link Impl} implementation whenever it is either:
 * <ul>
 *     <li>
 *         {@link Path#assign(Eager)} from the {@link Path}'s perspective.
 *     </li>
 *     OR
 *     <li>
 *         {@link Eager#allocate(Impl)} from the {@link Ref}'s perspective.
 *     </li>
 * </ul>
 * This class also supports a 'lazy' version {@link Lazy} which can lazily adopt the value of
 * the designated {@link Path} delivered in the constructor via:
 * <ul>
 *     <li>
 *         {@link Lazy#Lazy(Storage, Class, Function)} which is capable of building or retrieving {@link Model.Live}s
 *         loaded via {@link Settings#load(ModelStore.Singleton.Entry[])}
 *     </li>
 *     <li>
 *         {@link Lazy#Lazy(Storage, Supplier)} constructor capable of lazily supplying a Path instance to the referent.
 *     </li>
 * </ul>
 * Again only one Path can be assigned to any given {@link Ref.Lazy} and vice-versa.
 *
 * This Ref class will keep track of all {@link Consumer} observers added to the referenced Path.
 * Once the reference is cleared via {@link Path#deReference()}
 * */
public abstract class Ref<T>
        extends Path<T>
{
    final StackTraceElement[] es;
    private final Set<Consumer<? super T>> observers = ConcurrentHashMap.newKeySet();

    public final boolean hasObservers() { return !observers.isEmpty(); }

    /**
     * A reimplementation of the super.deReference() method, to work directly with this 'Ref' class
     * */
    @Override
    public abstract boolean deReference();

    final boolean refContains(Consumer<? super T> observer) {
        if (!isAssigned()) return false;
        return observers.contains(observer);
    }

    /**
     * @return true if this Ref has:
     * <ul>
     *     <li>
     *         For {@link Eager}: a Path has been assigned via {@link #assign(Eager)}
     *     </li>
     *     <li>
     *         For {@link Lazy}: a Path supplied via ({@link Supplier<Path>}) in any of the constructors.
     *         has been triggered via {@link Lazy#map(Function)} or {@link Lazy#switchMap(Function)}
     *         or any of the associated methods.
     *     </li>
     * </ul>
     * */
    public abstract boolean isAssigned();

    abstract Impl<T> getPath();

    @Override
    public String toString() {
        return
                "Ref{" +
                        "\n >>> id=" + id +
                        ",\n >>> observers [size]=" + observers.size() +
                        "\n }";
    }

    public String toStringDetailed() {
        String stack = es != null ?
                ",\n >>> es=" + Exceptionals.formatStack(0, es)
                :
                ",\n >>> Set Settings.setDebug_mode(boolean = true) for more information";

        return
                "Ref{" +
                        "\n >>> id=" + id +
                        ",\n >>> observers=" + observers
                        + stack +
                        "\n }";
    }

    public abstract boolean clearObservers();

    final boolean addRefObs(Consumer<? super T> observer) {
        if (observers.add(observer)) return true;
        else throw new IllegalStateException("Observer already present in Reference");
    }

    final boolean removeRefObserver(Consumer<? super T> observer) {
        return observers.remove(observer);
    }

    final Iterator<Consumer<? super T>> iterator() { return observers.iterator(); }

    @SuppressWarnings("unchecked")
    final boolean completeRemoval(Impl<T> removed) {
        removed.deref(this);
        Consumer<? super T>[] clone = observers.toArray(new Consumer[0]);
        observers.clear();
        for (Consumer<? super T> c:clone
        ) {
            removed.remove(c);
        }
        return true;
    }

    final UUID id = UUID.randomUUID();

    static final String
            owner_error = "The Reference does not have an assigned owner"
            , already_owned_error = "This token is already owned"
            , non_t = "For stack-trace information, set Settings.debug_mode = true"
                    ;

    /**
     * Uses the {@link Ref#Ref(Storage)}
     * with {@link Settings#storage} as default storage.
     * */
    private Ref() { this(Settings.storage); }

    String resolveStack() {
        return es == null ?
                non_t : Exceptionals.formatStack(0, es);

    }

    private Ref(Storage storage) {
        es = Settings.debug_mode ? Thread.currentThread().getStackTrace() : null;
        storage.add(this);
    }

    public final UUID getId() { return id; }

    abstract <R extends Ref<T>> R sysAlloc(Impl<T> owner);

    abstract <R extends Ref<T>> boolean removeOwner(Impl<T> owner);

    abstract Class<T> componentType();

    @Override
    Path<T> assign(Ref<T> referent) { throw new IllegalStateException("Cannot assign a Ref.class"); }


    public static final class Eager<T>
            extends Ref<T> {

        volatile Impl<T> owner;

        @SuppressWarnings("unchecked")
        @Override
        Class<T> componentType() {
            T res;
            return owner != null
                    && (res = owner.cache.liveGet()) != null ?
                    (Class<T>) res.getClass() : null;
        }

        public static final VarHandle OWNER;
        static {
            try {
                OWNER = MethodHandles.lookup().findVarHandle(Eager.class, "owner", Impl.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public Path<T> forkAlloc() { return getPath().map(Lambdas.Identities.identity()); }

        @SuppressWarnings("unchecked")
        <R extends Ref<T>> R sysAlloc(Impl<T> owner) {
            Impl<?> prev = (Impl<?>) OWNER.getAndSet(this, owner);
            assert prev == null
                    && owner.notStored()
                    : already_owned_error.concat(resolveStack());
            return (R) this;
        }

        @SuppressWarnings("unchecked")
        public boolean deReference() {
            Impl<T> removed;
            if ((removed = (Impl<T>) OWNER.getAndSet(this, null)) != null) {
                return completeRemoval(removed);
            }
            return false;
        }

        @Override
        <R extends Ref<T>> boolean removeOwner(Impl<T> owner) {
            assert owner != null;
            if (OWNER.compareAndSet(this, owner, null)) {
                completeRemoval(owner);
                return true;
            }
            return true;
        }

        public Path<T> allocate(Impl<T> owner) { return owner.assign(sysAlloc(owner)); }

        @Override
        public boolean isAssigned() { return owner != null; }

        @Override
        public Supplier<T> getCache() {
            return
                    () ->
                            getPath().getCache().get();
        }

        /**This method will detach all observers connected via {@link Ref} instance,
         * <p> BUT WILL NOT disassociate the reference from the owner {@link Path}.
         * <p> For complete disassociation use {@link #deReference()} instead.*/
        @SuppressWarnings("unused")
        @Override
        public boolean clearObservers() {
            Impl<T> current = owner;
            if (current == null) return false;
            Iterator<Consumer<? super T>> iter = iterator();
            while (iter.hasNext()) {
                Consumer<? super T> value = iter.next();
                current.remove(value);
                iter.remove();
            }
            return true;
        }

        @Override
        public boolean isActive() { return isAssigned() && owner.isActive(); }

        @Override
        public void add(Consumer<? super T> observer) {
            if (addRefObs(observer)) {
                getPath().add(observer);
            }
        }

        @Override
        public void remove(Consumer<? super T> observer) {
            if (removeRefObserver(observer)) {
                getPath().remove(observer);
            }
        }

        @Override
        public boolean contains(Consumer<? super T> subscriber) { return refContains(subscriber); }

        @Override
        public <S> Path<S> map(Builder<S> builder, Function<T, S> map) { return getPath().map(builder, map); }

        @Override
        public <S> Path<S> openMap(Builder<S> builder, Function<T, S> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return getPath().openMap(builder, map, onSwap, onActive);
        }

        Impl<T> getPath() {
            if (owner == null)
                throw new NullPointerException(
                        owner_error.concat(resolveStack())
                );
            return owner;
        }

        @Override
        public <S> Path<S> switchMap(Builder<S> builder, Function<T, Path<S>> map) {
            return getPath().switchMap(builder, map);
        }

        @Override
        public <S> Path<S> openSwitchMap(Builder<S> builder, Function<T, Path<S>> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return getPath().openSwitchMap(builder, map, onSwap, onActive);
        }

        @Override
        boolean sysActivate() { return getPath().sysActivate(); }

        @Override
        void sysDeactivate() { getPath().sysDeactivate(); }

        @Override
        Versioned<T> isConsumable() { return getPath().isConsumable(); }

        @Override
        void softDeactivate(Cache.Receiver<T> receiver) { getPath().softDeactivate(receiver); }

        @Override
        boolean contains(Cache.Receiver<T> receiver) { return getPath().contains(receiver); }

        @Override
        boolean isDiff(Path<?> that) { return that.isDiff(getPath()); }

        @Override
        Versioned<T> activate(Cache.Receiver<T> receiver, BooleanSupplier allow, BooleanSupplier onSet) {
            return getPath().activate(receiver, allow, onSet);
        }

        @Override
        public String toString() {
            String hash = Integer.toString(hashCode());
            return "Eager@".concat(hash).concat("{" +
                    "\n >>> owner=" + owner +
                    "\n >>> ref=" + super.toString().indent(3) +
                    "\n }@").concat(hash);
        }

        public String toStringDetailed() {
            String hash = Integer.toString(hashCode());
            return "Eager@".concat(hash).concat("{" +
                    "\n >>> owner=" + owner.toStringDetailed().indent(3) +
                    "\n >>> ref=" + super.toStringDetailed().indent(3) +
                    "\n }@").concat(hash);
        }

        @Override
        public Publisher<T> getPublisher(Executor executor) { return getPath().getPublisher(executor); }
    }

    /**
     * This class creates a cache that allows, with the usage of a {@link UUID},
     * the storage and retrieval of {@link Ref} type classes.
     * */
    public static final class Storage {
        final Map<UUID, ? super Ref<?>> ACTIVE_REFS = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        public<T, R extends Ref<T>> R getRef(UUID id, Class<R> refClass, Class<T> componentType) {
            Ref<?> res = (Ref<?>) ACTIVE_REFS.get(id);
            if (res == null) throw new IllegalStateException("Ref not found with id [" + id + "] in map = " + ACTIVE_REFS);
            if (!refClass.isInstance(res)) {
                throw new ClassCastException("Wrong type argument... the Ref class " + res.getClass() + " does not match the " +
                        "Ref type [" + refClass + "] of id = " + id);
            } else {
                R ref = (R) res;
                Class<T> realCT = ref.componentType();
                if (realCT != null && !componentType.isAssignableFrom(realCT)) {
                    throw new ClassCastException("Wrong type argument... the componentType class " + componentType + " does not match the " +
                            "Ref type [" + realCT + "] of id = " + id + " " + res.resolveStack());
                } else return ref;
            }
        }

        @SuppressWarnings("unchecked")
        public<T> Ref<T> getRef(UUID id, Class<T> componentType) {
            Ref<?> res = (Ref<?>) ACTIVE_REFS.get(id);
            if (res == null) throw new IllegalStateException("Ref not found with id [" + id + "] in map = " + ACTIVE_REFS);
            Class<?> realCT = res.componentType();
            if (realCT != null && !componentType.isAssignableFrom(realCT)) {
                throw new ClassCastException("Wrong type argument... the componentType class " + componentType + " does not match the " +
                        "Ref type [" + realCT + "] of id = " + id + " " + res.resolveStack());
            } else return (Ref<T>) res;
        }

        /**
         * Only 'owned' References will be found.
         * If this reference has not being assigned an owner yet, an exception will throw
         * */
        @SuppressWarnings("unchecked")
        public <S> Eager<S> getEagerRef(UUID id, Class<S> componentType) {
            return getRef(id, Eager.class, componentType);
        }

        /**
         * Only 'owned' References will be found.
         * If this reference has not being assigned an owner yet, an exception will throw
         * */
        @SuppressWarnings("unchecked")
        public <S> Lazy<S> getLazyRef(UUID id, Class<S> componentType) {
            return getRef(id, Lazy.class, componentType);
        }

        private <T, R extends Ref<T>> void add(R reference) {
            if (ACTIVE_REFS.put(reference.id, reference) != null) {
                throw new IllegalStateException("Reference already stored.");
            }
        }
        private <T, R extends Ref<T>> void remove(R reference) {
            if (ACTIVE_REFS.remove(reference.id) == null) {
                throw new IllegalStateException("Reference not stored.");
            }
        }

        @SuppressWarnings("unchecked")
        public void clearAll() {
            Iterator<Ref<?>> iter = (Iterator<Ref<?>>) ACTIVE_REFS.values().iterator();
            while (iter.hasNext()) {
                Ref<?> nextRef = iter.next();
                nextRef.deReference();
                iter.remove(); // removes from collection...
            }
        }
    }

    /**Allows the retrieval of Paths within classes extending the Model class.
     * <p> These Models should be defined in the {@link Settings#modelStore} storage class
     * <p> This class can be defined prior to calling {@link ModelStore#populate(ModelStore.ModelEntry[])}.
     * <p> BUT ModelStore should NOT BE NULL BEFORE calling any method of this {@link Lazy} class.
     * */
    public static final class Lazy<T>
            extends Ref<T>
    {

        @SuppressWarnings("unchecked")
        @Override
        <R extends Ref<T>> R sysAlloc(Impl<T> owner) { return (R) this; }

        @Override
        Versioned<T> isConsumable() { return getPath().isConsumable(); }

        @Override
        <R extends Ref<T>> boolean removeOwner(Impl<T> owner) {
            if (pathSupplier.destroy(owner)) {
                completeRemoval(owner);
                return true;
            }
            return false;
        }

        @Override
        public boolean isAssigned() { return !pathSupplier.isNull(); }

        @Override
        public boolean deReference() {
            Impl<T> impl;
            if ((impl = pathSupplier.getAndDestroy()) != null) {
                impl.deref(this);
                completeRemoval(impl);
                return true;
            }
            return false;
        }

        private final LazyHolder.Supplier<Impl<T>> pathSupplier;

        /**
         * Default implementation of {@link Lazy(Storage, Class, Function)}
         * which uses {@link Settings#storage} as default value for {@link Storage}.
         * */
        public<M extends Model> Lazy(
                Class<M> modelClass,
                Function<M, Path<T>> pathFun
        ) {
            this(Settings.storage, modelClass, pathFun);
        }

        /**
         * @see Lazy#Lazy(com.skylarkarms.solo.Ref.Storage, com.skylarkarms.concur.LazyHolder.SpinnerConfig, java.util.function.Supplier)
         * */
        public<M extends Model> Lazy(
                LazyHolder.SpinnerConfig config,
                Class<M> modelClass,
                Function<M, Path<T>> pathFun
        ) {
            this(Settings.storage, config, modelClass, pathFun);
        }

        /**
         * Default constructor for {@link Lazy( Storage, Supplier)}
         * that retrieves a {@param modelClass} from the {@link ModelStore} in {@link Settings}
         * by lazily initializing it, or getting its initialized instance.
         * */
        public<M extends Model> Lazy(
                Storage storage,
                Class<M> modelClass,
                Function<M, Path<T>> pathFun
        ) {
            this(
                    storage,
                    () -> pathFun.apply(
                            Settings.modelStore.get(modelClass)
                    )
            );
        }

        /**
         * @see Lazy#Lazy(com.skylarkarms.solo.Ref.Storage, com.skylarkarms.concur.LazyHolder.SpinnerConfig, java.util.function.Supplier)
         * */
        public<M extends Model> Lazy(
                Storage storage,
                LazyHolder.SpinnerConfig config,
                Class<M> modelClass,
                Function<M, Path<T>> pathFun
        ) {
            this(
                    storage,
                    config,
                    () -> pathFun.apply(
                            Settings.modelStore.get(modelClass)
                    )
            );
        }

        /**
         * Lazily supplies a {@link Path} to this reference, and the references adopts the value of the Path supplied.
         * The Path is considered assigned OR associated ({@link Path#assign(Ref)}) to this {@link Ref}.
         * Only one Ref can be assigned to one Path, or a {@link RuntimeException} will throw.
         * A {@link Ref} cannot be supplied to this Ref or an {@link IllegalAccessException} will throw.
         * */
        public<M extends Model.Live> Lazy(
                Storage storage,
                Supplier<Path<T>> pathSupplier
        ) {
            super(storage);
            this.pathSupplier = new LazyHolder.Supplier<>(
                    () -> {
                        Path<T> p = pathSupplier.get();
                        p.assign(this);
                        return (Impl<T>) p;
                    }
            );
        }

        /**
         * Added functionality for {@link LazyHolder.SpinnerConfig}
         * @see Lazy#Lazy(com.skylarkarms.solo.Ref.Storage, java.util.function.Supplier)
         * */
        public<M extends Model.Live> Lazy(
                Storage storage,
                LazyHolder.SpinnerConfig config,
                Supplier<Path<T>> pathSupplier
        ) {
            super(storage);
            this.pathSupplier = new LazyHolder.Supplier<>(
                    config,
                    () -> {
                        Path<T> p = pathSupplier.get();
                        p.assign(this);
                        return (Impl<T>) p;
                    }
            );
        }

        /**
         * Default implementation of {@link Lazy( Storage, Supplier)} where
         * {@link Storage} = {@link Settings#storage}
         * */
        public<M extends Model.Live> Lazy(
                Supplier<Path<T>> pathSupplier
        ) {
            this(Settings.storage, pathSupplier);
        }

        /**This method will detach all observers connected via {@link Ref} instance,
         * <p> BUT WILL NOT disassociate the reference from the owner {@link Path}.
         * <p> For complete disassociation use {@link #deReference()} instead.*/
        @SuppressWarnings("unused")
        @Override
        public boolean clearObservers() {
            Impl<T> liveGet = pathSupplier.getOpaque();
            if (liveGet == null) return false;
            Iterator<Consumer<? super T>> iter = iterator();
            while (iter.hasNext()) {
                Consumer<? super T> value = iter.next();
                liveGet.remove(value);
                iter.remove();
            }
            return true;
        }

        Impl<T> getPath() { return pathSupplier.get(); }

        @Override
        public <S> Path<S> map(Builder<S> builder, Function<T, S> map) { return getPath().map(builder, map); }

        @Override
        public <S> Path<S> openMap(Builder<S> builder, Function<T, S> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return getPath().openMap(builder, map, onSwap, onActive);
        }

        @Override
        public boolean isActive() { return isAssigned() && getPath().isActive(); }

        @Override
        public void add(Consumer<? super T> observer) {
            if (addRefObs(observer)) {
                getPath().add(observer);
            }
        }

        @Override
        public void remove(Consumer<? super T> observer) {
            if (removeRefObserver(observer)) {
                getPath().remove(observer);
            }
        }

        @Override
        public boolean contains(Consumer<? super T> subscriber) { return refContains(subscriber); }

        @Override
        public <S> Path<S> switchMap(Builder<S> builder, Function<T, Path<S>> map) {
            return getPath().switchMap(builder, map);
        }

        @Override
        public <S> Path<S> openSwitchMap(Builder<S> builder, Function<T, Path<S>> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return getPath().openSwitchMap(builder, map, onSwap, onActive);
        }

        @Override
        public Supplier<T> getCache() { return getPath().getCache(); }

        @Override
        void softDeactivate(Cache.Receiver<T> receiver) { getPath().softDeactivate(receiver); }

        @Override
        boolean contains(Cache.Receiver<T> receiver) { return getPath().contains(receiver); }

        @Override
        boolean isDiff(Path<?> that) { return that.isDiff(getPath()); }

        @Override
        Versioned<T> activate(Cache.Receiver<T> receiver, BooleanSupplier allow, BooleanSupplier onSet) {
            return getPath().activate(receiver, allow, onSet);
        }

        @Override
        public String toString() {
            String hash = Integer.toString(hashCode());
            return "Lazy@".concat(hash).concat("{"+
                    "\n >>> pathSupplier=" + pathSupplier.toString().concat(",").indent(3) +
                    " >>> Ref=" + super.toString().indent(3) +
                    "}@").concat(hash);
        }

        @Override
        public String toStringDetailed() {
            String hash = Integer.toString(hashCode());
            return "Lazy@".concat(hash).concat("{"+
                    "\n >>> pathSupplier =\n" + pathSupplier.toString().concat(",").indent(3) +
                    " >>> Ref=" + super.toStringDetailed().indent(3) +
                    "}@").concat(hash);
        }

        @Override
        boolean sysActivate() { return getPath().sysActivate(); }

        @Override
        void sysDeactivate() { getPath().sysDeactivate(); }

        @Override
        public Publisher<T> getPublisher(Executor executor) { return getPath().getPublisher(executor); }

        @Override
        @SuppressWarnings("unchecked")
        Class<T> componentType() {
            Impl<T> impl;
            T val;
            return (impl = this.pathSupplier.getOpaque()) != null
                    && (val = impl.cache.liveGet()) != null ?
                    (Class<T>) val.getClass()
                    : null;
        }
    }
}