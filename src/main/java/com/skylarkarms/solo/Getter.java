package com.skylarkarms.solo;

import com.skylarkarms.concur.SequentialDeque;
import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.Lambdas;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Class that allows the proactive loading of values present throughout the reactive tree.
 * <p> Each Getter class must call {@link #activate()} to get the most recent
 * <p> value of it's source {@link Path}.
 * <p> 'T' values can then be retrieved via different methods:
 * <ul>
 *     <li>
 *  {@link #get()} This method can only be used while {@link #isActive()} == {@code true},
 * <p> It will bring any value that may be present at the moment...
 * <p> even null if no value has been set yet.
 *     </li>
 *     <li>
 * {@link #first(Consumer)}:
 * <p> This method can only be used while {@link #isActive()} == {@code true},
 * <p> It will bring the most recent value... OR it will queue a consumption if NO VALUE has been set on {@link Path} source.
 * <p> All enqueued consumers will be cleared if the Getter becomes inactive and NO initial value has triggered a flush.
 *     </li>
 *     <li>
 *  {@link #passiveGet()} This method can be used EVEN if the class is not active.
 *     </li>
 * </ul>
 * <p> The propagation speed from 'source' to this Getter is defined by the order of its activation in relation to other source's children.
 * <p> To ensure a fast propagation this Getter should be:
 * <ul>
 *     <li>
 *         The first object initialized immediately AFTER the source initialization.
 *     </li>
 *     <li>
 *         The first object activated before ANY other 'source' children activation.
 *     </li>
 * </ul>
 * <p> This will ensure that this Getter is the first children in its source register collection ({@link Path.Impl.ReceiversManager}), receiving the emission sequentially... immediately after {@link In} consumption.
 * <p> This also depends on the type of {@link In.Config.Consume} being sequential (aka.: {@link In.Config.Consume#NON_CONT}).
 * <p> Any other position in the register collection will be affected by:
 * <ul>
 *     <li>
 *         The speed of task execution ({@link Settings#getWork_executor()}) (nanos - millis)
 *     </li>
 *     <li>
 *         It's position in it's 'source' register collection ({@link Path.Impl.ReceiversManager})... the higher it's position, the more it will take for the emission to reach this class.
 *     </li>
 * </ul>
 * */
public class Getter<T>
        extends
        Activators.StatefulActivator
        implements Supplier<T>
{
    private final Cache<T> cache = Cache.getDefault(
            this::attempt
    );
    private final Activators.PathedBinaryState<Object, Object> activator;

    private final SequentialDeque enqueued = new SequentialDeque(Settings.getExit_executor());

    /**{@link OnSwapped}*/
    final void attempt(boolean success, Versioned<T> prev, Versioned<T> next) {
        if (success && prev.isDefault()) {
            enqueued.beginFlush();
        }
        CASAttempt(success, prev, next);
    }

    protected void CASAttempt(boolean success, Versioned<T> prev, Versioned<T> current) {}

    protected void onStateChange(boolean isActive) {}

    public Getter(Path<T> parent) { this(parent, Lambdas.Identities.identity()); }
    private final Function<?, T> map;

    public<P> Getter(Path<P> parent, Function<P, T> map) { this(INACTIVE, parent, map); }

    <P> Getter(int initialState, Path<P> parent, Function<P, T> map) {
        super(initialState);
        activator = Activators.PathedBinaryState.getObjectReif(INACTIVE,
                parent,
                cache.forMapped(map)
        );
        this.map = map;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Getter<?> getter = (Getter<?>) o;
        return !activator.isDiff(getter.activator.path())
                && Objects.equals(map, getter.map);
    }

    @Override
    public int hashCode() { return Objects.hash(activator.path(), map); }

    @Override
    public T get() {
        nonActiveException();
        return passiveGet();
    }

    public static class NonActiveException extends IllegalStateException {
        private static final String message = "Getter should be active before a call to get(), use passiveGet instead, " +
                "\n OR first() to enqueue consumptions until a first value is emitted.";
        public NonActiveException() { super(message); }
    }

    /**
     * This enforces an explicit lifecycle in which the user MUST call activate before attempting a 'first' callback, or 'get' return.
     * To read this value while NOT active, use passiveGet instead.
     * */
    private void nonActiveException() { if (!isActive()) throw new NonActiveException(); }

    /**
     * This method can only be used while {@link #isActive()} == {@code true},
     * @see #passiveFirst(Consumer)
     * */
    public boolean first(Consumer<? super T> consumer) {
        nonActiveException();
        return passiveFirst(consumer);
    }

    /**
     * This method will bring the most recent value... OR it will queue a consumption if NO VALUE has been set on {@link Path} source.
     * <p> All enqueued consumers will be cleared if the Getter becomes inactive and NO initial value has triggered a flush.
     * @param consumer: will enqueue a consumption if there is no value yet, OR
     * @return `{@code true}` if the value was able to be retrieved sequentially-immediately,
     *      <p>`{@code false}` if the value was not present and the call needed to be enqueued.
     *
     * @apiNote Will not throw {@link #nonActiveException()} if this Getter is not active ({@link #isActive()} == {@code false}).
     * <p> The {@link Consumer} will last until next {@link #isActive()} == '{@code false}' when it gets removed.
     * */
    public boolean passiveFirst(Consumer<? super T> consumer) {
        Versioned<T> current = cache.getOpaque();
        if (!current.isDefault()) {
            consumer.accept(current.value());
            return true;
        } else {
            enqueued.push(
                    () -> consumer.accept(cache.liveGet())
            );
            return false;
        }
    }

    /**
     * This method will bring any value available if this Getter is in an active state... OR it will queue a consumption if NO VALUE has been set OR if the Getter is on an INACTIVE state.
     * <p> All enqueued consumers will be cleared if the Getter becomes inactive and NO initial value has triggered a flush.
     * @param consumer: will enqueue a consumption if there is no value yet, OR
     * @return `{@code true}` if the value was able to be retrieved sequentially-immediately,
     *      <p>`{@code false}` if the value was not present and the call needed to be enqueued.
     *
     * @apiNote Will not throw {@link #nonActiveException()} if this Getter is not active ({@link #isActive()} == {@code false}).
     * <p> The {@link Consumer} will last until next {@link #isActive()} == '{@code false}' when it gets removed.

     * */
    public boolean passiveNext(Consumer<? super T> consumer) {
        Versioned<T> current = cache.getOpaque();
        if (isActive() && !current.isDefault()) {
            consumer.accept(current.value());
            return true;
        } else {
            enqueued.push(
                    () -> consumer.accept(cache.liveGet())
            );
            return false;
        }
    }

    public T passiveGet() { return cache.get().value(); }

    @Override
    void sysOnActive() {
        activator.backProp();
        onStateChange(true);
    }

    @Override
    void sysOnDeactive() {
        activator.deactivate();
        enqueued.clear();
        onStateChange(false);
    }

    public boolean hasValue() { return cache.notDefault(); }

    @Override
    public String toString() {
        return "Getter{" +
                "\n >>> cache=" + cache +
                ",\n >>> activator=" + activator +
                ",\n >>> enqueued=" + enqueued +
                ",\n >>> has value? " + hasValue() +
                "\n }@" + hashCode();
    }
}