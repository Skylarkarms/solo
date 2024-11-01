package com.skylarkarms.solo;

import com.skylarkarms.lambdas.BinaryPredicate;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.Predicates;
import com.skylarkarms.lambdas.ToStringFunction;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.*;

public class Join<T> extends Path.Impl<T> {
    private final Activators.BinaryState<Object>[] activators;

    /**The {@link BiFunction} is constituted as:
     * <ul>
     *     <li>
     *         first param: the current value in this cache
     *     <li>
     *         second, the parent's emission to be received.
     *     </li>
     * </ul>
     * */
    public record Entry<P, C>(Path<P> path, BiFunction<C, P, C> map) {}

    @SuppressWarnings("unchecked")
    @SafeVarargs
    private Activators.BinaryState<Object>[] load(Entry<?, T>... entries) {
        int length;
        if (entries == null || (length = entries.length) < 1) {
            throw new IllegalStateException("Entries must not be null OR they must have a length greater than 0. array = " + Arrays.toString(entries));
        }
        final Activators.BinaryState<Object>[] content = new Activators.BinaryState[length];
        for (int i = 0; i < length; i++) {
            content[i] = entry(entries[i]);
        }
        return content;
    }

    private <P> Activators.BinaryState<Object> entry(Entry<P, T> entry) {
        return Activators.PathedBinaryState
                .getObjectReif(Activators.BinaryState.INACTIVE,
                        entry.path,
                        cache.getJoinReceiver(entry.map)
                );
    }

    private static final String message = "Initial value array has a length lesser than that of it's entries";

    @SafeVarargs
    public Join(
            T initialValue,
            Entry<?, T>... entries
    ) {
        this(initialValue, Predicates.defaultFalse(), entries);
    }

    /**
     * @param update:
     *              T1 = current state
     *              S = incoming state
     *              return = to replace this state
     * */
    public<S> Join(
            T initialValue,
            Path<S> source,
            BiFunction<T, S, T> update
    ) {
        this(initialValue, Predicates.defaultFalse(),
                new Entry<>(
                        source,
                        update
                )
        );
    }

    @SafeVarargs
    public Join(
            T initialValue,
            Predicate<T> excludeOut,
            Entry<?, T>... entries
    ) {
        this(initialValue,
                (UnaryOperator<Builder<T>>) op -> op.excludeOut(excludeOut),
                entries);
    }

    @SafeVarargs
    public Join(
            T initialValue,
            BinaryPredicate<T> excludeIn,
            Entry<?, T>... entries
    ) {
        this(initialValue,
                (UnaryOperator<Builder<T>>) op -> op.excludeIn(excludeIn),
                entries);
    }

    @SafeVarargs
    public Join(
            T initialValue,
            UnaryOperator<Builder<T>> builder,
            Entry<?, T>... entries
    ) {
        super(builder.apply(Builder.withValue(initialValue)));
        assert initialValue != null;
        Class<?> aClass = initialValue.getClass();
        if (aClass.isArray()) {
            Class<?> comp = aClass.getComponentType();
            if (comp == int.class) {
                assert ((int[]) initialValue).length >= entries.length : message;
            } else if (comp == String.class) {
                assert ((String[]) initialValue).length >= entries.length : message;
            } else assert comp != long.class || ((long[]) initialValue).length >= entries.length : message;
        }
//        if (aClass.isArray()) {
//            Printer.out.print(aClass.getComponentType().toString());
//            switch (initialValue) {
//                case int[] intArray -> assert intArray.length == entries.length : "Initial value array has a different length than";
//                case String[] stringArray -> assert stringArray.length == entries.length : "Initial value array has a different length than";
//                case long[] longArray -> assert longArray.length == entries.length : "Initial value array has a different length than";
//                default -> throw new IllegalArgumentException("Unsupported array type: " + initialValue.getClass());
//            }
//        }
        this.activators = load(entries);
    }

    @Override
    boolean sysActivate() {
        forEach(Activators.activate);
        return true;
    }

    @Override
    void sysDeactivate() {
        forEach(Activators.deactivate);
    }

    private void forEach(Consumer<Activators.BinaryState<?>> action) {
        for (Activators.BinaryState<Object> activator:activators) action.accept(activator);
    }

    public static class str extends Join<String[]>{

        public record StringEntry<T>(Path<T> path, Function<T, String> map){
            public static<T> StringEntry<T> get(Path<T> path, ToStringFunction<T> map) {return new StringEntry<>(path, map);}

            public static<T> StringEntry<T> get(Path<T> path, Function<T, String> map) { return new StringEntry<>(path, map); }

            private boolean isIdentity() { return Lambdas.Identities.isIdentity(map); }

            public static StringEntry<String> get(Path<String> stringPath) {
                return new StringEntry<>(stringPath, Lambdas.Identities.identity());
            }

            static Entry<String, String[]> toEntryString(StringEntry<String> entry, int entryIndex) {
                return new Entry<>(
                        entry.path,
                        (strings, t) -> {
                            if (!t.equals(strings[entryIndex])) {
                                String[] clone = strings.clone();
                                clone[entryIndex] = t;
                                return clone;
                            }
                            return strings;
                        }
                );
            }
            static<T> Entry<T, String[]> toEntry(StringEntry<T> entry, int entryIndex) {
                final Function<T, String> map = entry.map;
                return new Entry<>(
                        entry.path,
                        (strings, t) -> {
                            String mapped = map.apply(t);
                            if (!mapped.equals(strings[entryIndex])) {
                                String[] clone = strings.clone();
                                clone[entryIndex] = mapped;
                                return clone;
                            }
                            return strings;
                        }
                );
            }

            @SuppressWarnings("unchecked")
            static Entry<?, String[]>[] mapEs(StringEntry<?>... entries) {
                int length = entries.length;
                Entry<?, String[]>[] res = new Entry[length];
                for (int i = 0; i < length; i++) {
                    StringEntry<?> entry = entries[i];
                    res[i] =  entry.isIdentity() ? toEntryString((StringEntry<String>) entry, i) : toEntry(entry, i);
                }
                return res;
            }
        }

        /**
         * @param index = the position in the String array.
         * @param map = the object transformation.
         * @param toStringFun the toString function.
         *
         * <p> The entry will apply the toString function IF and ONLY IF the mapping transformation proves
         * <p> that the new mapped Object was NOT EQUAL {@link Objects#equals(Object, Object)} == {@code false}.
         * */
        public record IndexedEntry<T, S>(int index, Function<T, S> map, ToStringFunction<S> toStringFun) {
            static final Comparator<IndexedEntry<?, ?>> comparator = (o1, o2) -> o2.index - o1.index;

            static class Stateful<T, S> {
                S state;
                final Function<T, S> map;
                final ToStringFunction<S> toStringFun;
                final int index;
                Stateful(IndexedEntry<T, S> entry) {
                    this.map = entry.map;
                    this.toStringFun = entry.toStringFun;
                    this.index = entry.index;
                }

                /**
                 * @return null if object was same
                 * */
                String set(T o) {
                    S newO = map.apply(o);
                    if (!Objects.equals(state, newO)) {
                        state = newO;
                        return toStringFun.apply(newO);
                    }
                    return null;
                }

                @SuppressWarnings("unchecked")
                static<S> Stateful<S, ?>[] from(int length, IndexedEntry<S, ?>[] entries) {
                    Stateful<S, ?>[] res = new Stateful[length];
                    for (int i = 0; i < length; i++) {
                        IndexedEntry<S, ?> e = entries[i];
                        res[i] = new Stateful<>(e);
                    }
                    return res;
                }
            }

            /**
             * @param stringLength is to ensure proper length on the assertion.
             * */
            static<T> Entry<T, String[]> toSingleEntry(
                    int stringLength,
                    Path<T> source, IndexedEntry<T, ?>[] entries) {
                int length = entries.length;
                assert length <= stringLength : "Entries length [" + length + "] cannot be greater than or equal in length to the String[] result [" + stringLength + "].";
                assert stringLength != 0 : "String source length cannot be 0";
                Arrays.sort(
                        entries,
                        comparator
                );
                final Stateful<T, ?>[] tempState = Stateful.from(length, entries);
                return new Entry<>(
                        source,
                        (strings, t) -> {
                            String[] clone = strings.clone(); //Maybe optimism is the better option....
                            boolean changed = false;
                            for (int i = 0; i < length; i++) {
                                Stateful<T, ?> statefulE = tempState[i];
                                String newS;
                                if ((newS = statefulE.set(t)) != null) {
                                    clone[statefulE.index] = newS;
                                    changed = true;
                                }
                            }
                            if (changed) return clone;
                            return strings;
                        }
                );
            }
        }

        public str(Path<?>... paths) { this(Lambdas.Predicates.nullChecker(), mapFrom(paths)); }

        public str(Predicate<String[]> excludeOut, Path<?>... paths) { this(excludeOut, mapFrom(paths)); }

        static StringEntry<?>[] mapFrom(Path<?>... paths) {
            int length = paths.length;
            final StringEntry<?>[] stringEntries = new StringEntry[length];
            for (int i = 0; i < length; i++) {
                stringEntries[i] = StringEntry.get(paths[i].map(ToStringFunction.valueOf()));
            }
            return stringEntries;
        }
        public str(Predicate<String[]> excludeOut, StringEntry<?>... entries) {
            this(new String[entries.length],
                    excludeOut,
                    entries
            );
        }
        public str(String[] initialValue, Predicate<String[]> excludeOut, StringEntry<?>... entries) {
            super(initialValue,
                    excludeOut,
                    StringEntry.mapEs(entries)
            );
        }
        /**
         * {@link IndexedEntry} maps multiple {@link ToStringFunction} from the same {@link Path} source.
         * <p> Each mapping entry will only apply the function if the mapped Object has changed.
         * */
        @SafeVarargs
        public<T> str(String[] initialValue, Predicate<String[]> excludeOut, Path<T> source, IndexedEntry<T, ?>... entries) {
            super(initialValue,
                    excludeOut,
                    IndexedEntry.toSingleEntry(initialValue.length, source, entries)
            );
        }

        /**
         * Default implementation of {@link str#str(String[], Predicate, Path, IndexedEntry[])} where
         * {@link Predicate} 'excludeIn' is set to {@link com.skylarkarms.lambdas.Lambdas.Predicates#nullChecker()}
         * */
        @SafeVarargs
        public<T> str(String[] initialValue, Path<T> source, IndexedEntry<T, ?>... entries) {
            this(initialValue,
                    Lambdas.Predicates.nullChecker(),
                    source,
                    entries
            );
        }
    }

    public static class Updatable<T>
            extends Join<T>
            implements In.InStrategy.Updater<T>
            , Supplier<T>
            , Cache.CAS<T>
    {
        private final In.InStrategy.Updater<T> updater;

        public<S> Updatable(T initialValue, Path<S> path, BiFunction<T, S, T> update) {
            this(initialValue,
                    Lambdas.Identities.identity(),
                    path, update);
        }

        public<S> Updatable(T initialValue,
                            UnaryOperator<Builder<T>> builder,
                            Path<S> path, BiFunction<T, S, T> update) {
            this(In.Config.Update.FORTH.ref, initialValue, builder, new Entry<>(path, update));
        }

        public<S> Updatable(T initialValue, Predicate<T> excludeOut, Path<S> path, BiFunction<T, S, T> update) {
            this(
                    In.Config.Update.FORTH.ref,
                    initialValue,
                    op -> op.excludeOut(excludeOut),
                    new Entry<>(path, update));
        }

        @SafeVarargs
        public Updatable(T initialValue, Entry<?, T>... entries) {
            this(
                    In.Config.Update.FORTH.ref,
                    initialValue, Lambdas.Identities.identity(), entries);
        }

        @SafeVarargs
        public Updatable(
                T initialValue, Predicate<T> excludeOut, Entry<?, T>... entries) {
            this(
                    In.Config.Update.FORTH.ref,
                    initialValue,
                    op -> op.excludeOut(excludeOut),
                    entries);
        }

        @SafeVarargs
        public Updatable(T initialValue, BinaryPredicate<T> excludeIn, Entry<?, T>... entries) {
            this(
                    In.Config.Update.FORTH.ref,
                    initialValue,
                    op -> op.excludeIn(excludeIn),
                    entries);
        }

        @SafeVarargs
        public Updatable(
                In.Config.Update config,
                T initialValue,
                UnaryOperator<Builder<T>> builder,
                Entry<?, T>... entries) {
            super(initialValue,
                    builder,
                    entries);
            updater = config.apply(cache);
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


        @Override
        public T get() {
            if (!isActive()) throw new Getter.NonActiveException();
            return cache.liveGet();
        }

        public T passiveGet() { return cache.liveGet(); }
    }
}
