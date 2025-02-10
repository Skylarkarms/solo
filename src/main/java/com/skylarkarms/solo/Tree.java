package com.skylarkarms.solo;

import com.skylarkarms.concur.LazyHolder;
import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.Funs;
import com.skylarkarms.lambdas.Predicates;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.*;

public abstract class Tree<T, N extends Tree.Node<T, N>> {
    final Map<String, N> map = new ConcurrentHashMap<>();

    public SimpleTree getSnapshot() { return new SimpleTree(); }

    public class SimpleTree {
        private static <V, V1> Map<String, V1> mapVal(
                Map<String, V> from,
                Function<V, V1> getValue
        ) {
            final Map<String, V1> result = new HashMap<>(from.size());
            for (Map.Entry<String, V> x: from.entrySet()
            ) {
                result.put(
                        x.getKey(),
                        getValue.apply(x.getValue())
                );
            }
            return result;
        }
        final Map<String, Node.Compact<T>> simpleMap = mapVal(
                Tree.this.map,
                Node.Compact::from
        );
        /**
         * Faster than entrySet loop
         * */
        public void forEach(BiConsumer<String, T> action) {
            for (Map.Entry<String, Node.Compact<T>> e:simpleMap.entrySet())
                action.accept(e.getKey(), e.getValue().value);
        }
        public Set<Map.Entry<String, T>> entrySet() {
            return mapVal(
                    simpleMap,
                    Node.Compact::value
            ).entrySet();
        }
        Node.Compact<T> getNode(String at) {
            assert at != null : "key must not be null";
            return Objects.requireNonNull(simpleMap.get(at), () -> "Key [" + at + "] not found in map = " + simpleMap);
        }
        public T get(String at) { return getNode(at).value; }
        public void put(String key, T value) {
            simpleMap.compute(key,
                    (s, tCompact) -> {
                        assert tCompact != null : "This map does not contain the specified key";
                        return tCompact.withNewValue(value);
                    }
            );
        }
        /**
         * Applies the {@link Tree#operator} up to this point.
         * */
        public T resolveAt(String key) {
            T[] branch = branchAt(key);
            int length;
            if (branch != null && (length = branch.length) != 0) {
                T res = branch[0];
                for (int i = 1; i < length; i++)
                    res = operator.apply(res, branch[i]);
                return res;
            } else return null;

        }
        public<S> S[] branchAt(String key, IntFunction<S[]> collector, Function<T, S> map) {
            Node.Compact<T> next = getNode(key);
            int depth = next.depth;
            S[] res = collector.apply(depth);
            res[0] = map.apply(next.value);
            for (int i = depth - 1; i > -1; i--) {
                next = getNode(next.parentKey);
                res[i] = map.apply(next.value);
            }
            return res;
        }

        @SuppressWarnings("unchecked")
        public T[] branchAt(String key) {
            Node.Compact<T> next = getNode(key);
            if (next.value != null) {
                int depth = next.depth;
                T[] res = (T[]) Array.newInstance(next.value.getClass(), depth + 1);
                res[depth] = next.value;
                for (int i = depth - 1; i > -1; i--) {
                    next = getNode(next.parentKey);
                    res[i] = next.value;
                }
                return res;
            } else return null;
        }

        @Override
        public String toString() {
            return "SimpleTree{" +
                    "\n simpleMap=" + simpleMap +
                    "\n }";
        }
    }

    /**
     * A {@link BinaryOperator} that will compute Nodes, from root (bottom) to leaf (margins).
     * <p> T1 = The previous result (Accumulated computations).
     * <p> T2 = The current node value.
     * */
    final BinaryOperator<T> operator;
    final SysForker<T, N> forker;
    final LazyHolder.Supplier<Map<String, N>> atomicCreator = LazyHolder.Supplier.getNew(
            () -> {
                synchronized (map) {
                    if (map.size() > 1) return map;
                    onCreate(Tree.this.root);
                    return map;
                }
            }
    );

    private final N root;

    public boolean isActive() { return root.isActive(); }

    @SuppressWarnings("unused")
    protected Tree(String rootTag, Class<T> tClass, BinaryOperator<T> operator,
                   NodeFactory<T, N> factory

    ) {
        this(rootTag, tClass, null, operator, factory);
    }

    @SuppressWarnings("unused")
    protected Tree(String rootTag, T initialValue, BinaryOperator<T> operator,
                   NodeFactory<T, N> factory

    ) {
        this(rootTag, null, initialValue, operator, factory);
    }

    @SuppressWarnings("unchecked")
    protected Tree(
            String rootTag, Class<T> tClass, T initialValue, BinaryOperator<T> operator,
            NodeFactory<T, N> factory
    ) {
        boolean nullClass = tClass == null;
        assert !nullClass || initialValue != null : "Either class is null OR initialValue is null, but not both simultaneously.";
        T[] template = (T[]) Array.newInstance(nullClass ? initialValue.getClass() : tClass, 2);
        this.operator = operator;
        this.forker = new SysForker<>() {
            @Override
            public N fork(N parent, String tag, T value) {
                N newNode = factory.getNew(
                        tag,
                        parent, value, this, template, operator);
                if (map.put(tag, newNode) != null) {
                    throw new IllegalStateException("A node is already registered under this tag = " + tag +
                            ", at this map = " + map);
                }
                return newNode;
            }
        };
        this.root = factory.getNew(
                rootTag,
                null,
                initialValue, forker, template, operator);
        map.put(rootTag, root);
    }

    private Map<String, N> build() {
        if (map.size() > 1) return map;
        return atomicCreator.get();
    }

    protected abstract void onCreate(N root);

    public N get(String tag) {
        Map<String, N> model = build();
        return nonNullNode(tag, model);
    }

    public<S> S[] branchAt(String key, IntFunction<S[]> collector, Function<N, S> map) {
        N next = get(key);
        if (next != null) {
            int depth = next.depth;
            S[] res = collector.apply(depth);
            for (int i = depth - 1; i > -1; i--) {
                res[i] = map.apply(next);
                next = get(next.parentKey);
            }
            return res;
        } else return null;
    }

    @SuppressWarnings("unchecked")
    public N[] branchAt(String key) {
        N next = get(key);
        if (next != null) {
            int depth = next.depth;
            N[] res = (N[]) Array.newInstance(next.getClass(), depth);
            for (int i = depth - 1; i > -1; i--) {
                res[i] = next;
                next = get(next.parentKey);
            }
            return res;
        } else return null;
    }

    private N nonNullNode(String tag, Map<String, N> model) {
        return Objects.requireNonNull(model.get(tag));
    }

    @SuppressWarnings("unused")
    public Getter<T> getter(String tag) {
        Map<String, N> model = build();
        return new Getter<>(nonNullNode(tag, model).core);
    }

    /**returns N last node in the parameter*/
    @SafeVarargs
    // Used for table transactions across multiple Nodes at once.
    public final N transaction(Entry<T>... toUpdate) {
        int length = toUpdate.length;
        if (length == 0) return null;
        Entry<T> lastE = toUpdate[length - 1];
        synchronized (transactionLock) {
            N
                    ret = get(lastE.tag),
                    deepest = ret;

            ret.silentSwap.accept(lastE.value);

            for (int i = toUpdate.length - 2; i >= 0; i--) {
                Entry<T> tEntry = toUpdate[i];
                String tag = tEntry.tag;
                T val = tEntry.value;
                N curr = get(tag);
                curr.silentSwap.accept(val);
                if (curr.depth < deepest.depth) {
                    deepest = curr;
                }
            }
            deepest.forceDispatch();
            return ret;
        }
    }

    final Object transactionLock = new Object();

    public static class Entry<T> {
        final String tag;
        final T value;

        public Entry(String tag, T value) {
            this.tag = tag;
            this.value = value;
        }

        @SuppressWarnings("unused")
        public static<T> Entry<T> emptyValue(String tag) { return new Entry<>(tag, null); }
    }

    @FunctionalInterface
    public interface SysForker<T, N extends Node<T, N>>{
        N fork(N parent, String tag, T value);
    }

    @FunctionalInterface
    public interface Forkable<T, N extends Node<T, N>>{
        N fork(String tag, T value);

        @SuppressWarnings("unused")
        default  <E extends Entry<T>> N fork(E entry) { return fork(entry.tag, entry.value); }

        default void append(String tag, T value) { fork(tag, value); }
        default <E extends Entry<T>> void append(E entry) { fork(entry); }
    }

    public interface INode<T, N extends Node<T, N>> extends Forkable<T, N>, Publisher<T>, Consumer<T>, Supplier<T> {
    }

    @FunctionalInterface
    public interface NodeFactory<T, N extends Node<T, N>> {
        N getNew(
                String thisKey,
                Node<T, N> parent, T nodeValue, SysForker<T, N> forker, T[] template, BinaryOperator<T> operator);
    }

    public static class Node<T,N extends Node<T, N>>
            extends Path<T>
            implements INode<T, N> {
        final Supplier<T> sup;
        final Consumer<T> tConsumer, silentSwap;
        final Runnable forceDispatch;
        final Path<T> core;
        final int depth;

        record Compact<T>(String parentKey, int depth, T value) {
            static <T, N extends Node<T, N>> Compact<T> from(Node<T, N> node) {
                return new Compact<>(
                        node.parentKey,
                        node.depth,
                        node.sup.get()
                );
            }
            Compact<T> withNewValue(T newValue) {
                return new Compact<>(
                        parentKey, depth, newValue
                );
            }
        }

        final String parentKey, thisKey;

        final SysForker<T, N> forker;

        Node(
                String thisKey,
                Node<T, N> parent, T nodeValue, SysForker<T, N> forker, T[] template, BinaryOperator<T> operator) {
            boolean isRoot = parent == null;
            this.thisKey = thisKey;
            this.forker = forker;
            final T[] first = template.clone();
            first[1] = nodeValue;
            if (isRoot) {
                parentKey = null;
                this.depth = 0;
                final In.Update<T[]> tSource = new In.Update<>(first);
                this.core = tSource.openMap(
                        ts -> ts[1],
                        this::onStateChange
                );
                sup = () -> tSource.cache.liveGet()[1];
                tConsumer = t -> tSource.update(
                        ts -> {
                            if (!t.equals(ts[1])) {
                                T[] clone;
                                (clone = ts.clone())[1] = t;
                                return clone;
                            } else return ts;
                        }
                );
                silentSwap = t -> tSource.cache.liveGet()[1] = t;
                this.forceDispatch = () -> tSource.update(
                        ts -> ts.clone()
                );
            } else {
                parentKey = parent.thisKey;
                depth = parent.depth + 1;
                final Join<T[]> join = new Join<>(
                        first,
                        (Predicate<T[]>) ts -> ts[0] == null || ts[1] == null,
                        new Join.Entry<>(
                                parent.core, (strings, s) -> func(s, 0).apply(strings)
                        )
                );
                silentSwap = t -> join.cache.liveGet()[1] = t;
                this.sup = () -> join.cache.liveGet()[1];
                final In.ForUpdater<T[]> updater = join.cache.getUpdater();
                this.tConsumer = t -> updater.up(
                        func(t, 1)
                );
                this.core = join.openMap(
                        strings -> operator.apply(strings[0], strings[1]),
                        this::onStateChange
                );
                this.forceDispatch = () -> updater.up(ts -> ts.clone());
            }
        }

        @Override
        public boolean isActive() { return core.isActive(); }

        @Override
        public void add(Consumer<? super T> observer) { core.add(observer); }

        @Override
        public Consumer<? super T> remove(Object subscriber) { return core.remove(subscriber); }

        @Override
        public boolean contains(Object subscriber) { return core.contains(subscriber); }

        @Override
        public int observerSize() { return core.observerSize(); }

        private UnaryOperator<T[]> func(T s, int forIndex) {
            return strings -> {
                if (!s.equals(strings[forIndex])) {
                    final T[] res;
                    (res = strings.clone())[forIndex] = s;
                    return res;
                }
                return strings;
            };
        }

        @Override
        public void accept(T s) {
            assert s != null : "Cannot accept null values.";
            tConsumer.accept(s);
        }

        void forceDispatch() { forceDispatch.run(); }


        @Override
        public T get() { return sup.get(); }

        public T result() { return core.getCache().get(); }

        @SuppressWarnings("unchecked")
        N getThis() { return (N) this; }

        @Override
        public N fork(String tag, T value) { return forker.fork(getThis(), tag, value); }

        @Override
        public <S> Path<S> map(Path.Builder<S> builder, Function<T, S> map) { return core.map(builder, map); }

        @Override
        public <S> Path<S> openMap(Path.Builder<S> builder, Function<T, S> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return core.openMap(builder, map, onSwap, onActive);
        }

        @Override
        public <S> Path<S> switchMap(Path.Builder<S> builder, Function<T, Path<S>> map) {
            return core.switchMap(builder, map);
        }

        @Override
        public <S> Path<S> openSwitchMap(Path.Builder<S> builder, Function<T, Path<S>> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return core.openSwitchMap(builder, map, onSwap, onActive);
        }

        @Override
        public Supplier<T> getCache() { return core.getCache(); }

        @Override
        Versioned<T> isConsumable() { return core.isConsumable(); }

        @Override
        public Path<T> assign(Ref<T> referent) { return core.assign(referent); }

        @Override
        public boolean deReference() { return core.deReference(); }

        @Override
        void softDeactivate(Cache.Receiver<T> receiver) { core.softDeactivate(receiver); }

        @Override
        boolean contains(Cache.Receiver<T> receiver) { return core.contains(receiver); }

        @Override
        boolean isDiff(Path<?> that) { return core.isDiff(that); }

        @Override
        Versioned<T> activate(Cache.Receiver<T> receiver, BooleanSupplier allow, BooleanSupplier onSet) {
            return core.activate(receiver, allow, onSet);
        }

        @Override
        public String toStringDetailed() { return core.toStringDetailed(); }

        @Override
        public String toString() {
            return "Node{" +
                    "\n val=" + sup.get() +
                    ",\n core=" + core.toStringDetailed() +
                    "\n}";
        }

        @Override
        public Publisher<T> getPublisher(Executor executor) { return core.getPublisher(executor); }
    }

    public static abstract class Str extends Tree<String, Str.Node> {
        public static class Node extends Tree.Node<String, Node> {
            Node(
                    String thisKey,
                    Tree.Node<String, Node> parent, String nodeValue, SysForker<String, Node> forker, String[] template, BinaryOperator<String> operator) {
                super(
                        thisKey,
                        parent, nodeValue, forker, template, operator);
            }

            public Node fork(String tagValue) { return super.fork(tagValue, tagValue); }

            public void append(String tagValue) { this.fork(tagValue); }
        }

        public static class Entry extends Tree.Entry<String> {
            public Entry(String tag, String value) { super(tag, value); }
            @SuppressWarnings("unused")
            public static Entry tagValue(String tagValue) { return new Entry(tagValue, tagValue); }
        }

        /**
         * See {@link Tree#operator}.
         * */
        static final Funs.From.String<BinaryOperator<String>> fun = divisor -> (s, s2) -> s.concat(divisor).concat(s2);
        private static final NodeFactory<String, Node> factory = Node::new;

        @SuppressWarnings("unused")
        protected Str(Entry root, String divisor) {
            super(root.tag, String.class, root.value, fun.apply(divisor), factory);
        }

        protected Str(String tagValue, String divisor) {
            super(tagValue, String.class, tagValue, fun.apply(divisor), factory);
        }
    }

    /**A version of "Str"(String) tree, that accepts a generic type Node: <p>
     * <b>Example:</b>
     *<pre>{@code private static final String
     *             A = "A",
     *                 B = "B",
     *                     C = "C",
     *                 D = "D",
     *                     E = "E",
     *                         F = "F";
     *
     *     static class MyNode extends Tree.TypedStr.TypedNode<MyNode> {
     *
     *          //The node must implement this protected Constructor, and be left as is.
     *         protected MyNode(Tree.Node<String, MyNode> parent, String nodeValue, Tree.SysForker<String, MyNode> forker, String[] template, BinaryOperator<String> operator) {
     *             super(parent, nodeValue, forker, template, operator);
     *         }
     *
     *         void print() {
     *             System.out.println("FROM NODE: " + result());
     *         }
     *     }
     *
     *     private static final Tree.TypedStr<MyNode> str = new Tree.TypedStr<>(A, "/", MyNode::new) {
     *         @Override
     *         protected void onCreate(MyNode root) {
     *             MyNode bNode = root.fork(B);
     *                 bNode.concat(C);
     *             MyNode dNode = root.fork(D);
     *             MyNode eNode = dNode.fork(E);
     *                     eNode.concat(F);
     *         }
     *     };}</pre>
     * */
    public static abstract class TypedStr<N extends Node<String, N>> extends Tree<String, N> {

        public static class TypedNode<N extends Node<String, N>> extends Node<String, N> {

            protected TypedNode(
                    String thisKey,
                    Node<String, N> parent, String nodeValue, SysForker<String, N> forker, String[] template, BinaryOperator<String> operator) {
                super(
                        thisKey,
                        parent, nodeValue, forker, template, operator);
            }

            public N fork(String tagValue) { return super.fork(tagValue, tagValue); }

            public void append(String tagValue) { this.fork(tagValue); }
        }

        public static class Entry extends Tree.Entry<String> {
            public Entry(String tag, String value) { super(tag, value); }

            @SuppressWarnings("unused")
            public static Entry tagValue(String tagValue) { return new Entry(tagValue, tagValue); }
        }

        @SuppressWarnings("unused")
        protected TypedStr(Entry root, String divisor, NodeFactory<String, N> factory) {
            super(root.tag, String.class, root.value, Str.fun.apply(divisor), factory);
        }

        protected TypedStr(String tagValue, String divisor, NodeFactory<String, N> factory) {
            super(tagValue, String.class, tagValue, Str.fun.apply(divisor), factory);
        }
    }
}
