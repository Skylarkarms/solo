package com.skylarkarms.solo;

import com.skylarkarms.concur.CopyOnWriteArray;
import com.skylarkarms.concur.Executors;
import com.skylarkarms.concur.LazyHolder;
import com.skylarkarms.concur.Versioned;
import com.skylarkarms.lambdas.BinaryPredicate;
import com.skylarkarms.lambdas.Exceptionals;
import com.skylarkarms.lambdas.Lambdas;
import com.skylarkarms.lambdas.Predicates;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/**
 * <pre>
 * Interface for the {@link Path} class or any of it's derived classes via {@link Impl}.
 * A Path is an intermediate state between entry points and final output.
 * Where entry points includes classes such as:
 *  * {@link In.Consume}
 *  * {@link In.Update}
 *
 * Path will then be the type returned when a data source requires some form of transformation.
 * The 2 basic types being:
 *  * {@link #map(Builder, Function)}
 *  * {@link #switchMap(Builder, Function)}
 *
 * Each Path has an inner state managed by the class {@link Cache}
 * This Cache has 2 types of gates:
 *  * {@link BinaryPredicate} OR {@link Predicate} "excludeIn"
 *  * {@link Predicate} "excludeOut"
 *
 * Where excludeIn will shield the Cache from undesired state swaps when {@code true}, while
 * excludeOut will allow state changes, BUT will block outward dispatches towards other forking paths/observers if {@code true}.
 * </pre>
 * */
public abstract class Path<T>
        extends Activators.OnStateChange
        implements
        Publisher.Builder<T>,
        Publisher<T>
{

    /**
     * Maps incoming states into the given new state atomically, with automatic retries on contention.
     *
     * @param map The transforming function from T (incoming value) to S (new value).
     * @param builder The Builder of type S to be processed. This is an instance of the Builder design
     *                pattern, which allows for more readable and maintainable code when dealing with multiple parameters.
     * @param <S> The type of the new state after mapping.
     * @return A new 'Path' representing the transformed states.
     *
     * <p><b>Note:</b> The map function should be side-effect-free since it will retry until one of the following conditions is met:
     * <ul>
     *     <li>The swapping is done.</li>
     *     <li>The "excludeIn" test has determined it as unfit to change the cache's state.</li>
     *     <li>A new concurrent signal arrives first.</li>
     * </ul>
     * <p>Set {@link Settings#setDebug_mode(boolean)} = {@code true} to show stackTrace details on exceptions.
     * Object capturing is allowed, as long as it is properly de-referenced (deep copy or cloned) or as long as each of its constituents
     * remain unchanged (final). In fact, some scenarios might encourage the use of object capturing (see the code example below).</p>
     *
     * <b>Example:</b>
     * <pre>{@code
     *
     *     // In this scenario we will use a static final Object called DEFAULT of type MyObject, and we will use it to prevent a value swap only when enum MyTypes.c arrives.
     *     public class SideEffectExcludeInExample {
     *
     *         record MyObject(int anInt) {
     *             static final MyObject DEFAULT = new MyObject(-1);
     *
     *             void assertNotC() {
     *                 assert anInt != MyTypes.c.ordinal();
     *             }
     *
     *             // The BinaryPredicate.Unary operator "excludeIn" will test this function. If true, then the value will be excluded from entering the cache.
     *             boolean excludeOnDefault() {
     *                 return this == DEFAULT;
     *             }
     *         }
     *
     *         enum MyTypes {
     *             a, b, c, d;
     *
     *             // This function will return "DEFAULT" when this type == "c".
     *             MyObject map() {
     *                 if (this == c) return MyObject.DEFAULT;
     *                 return new MyObject(ordinal());
     *             }
     *         }
     *
     *         public static void main(String[] args) {
     *             Source<MyTypes> typesSource = new Source<>();
     *             Path<MyObject> myObjectPath = typesSource.map(
     *                     MyTypes::map,
     *                     MyObject::excludeOnDefault
     *             );
     *
     *             myObjectPath.add(
     *                     myObject -> {
     *                         myObject.assertNotC();
     *                         System.out.println(myObject);
     *                     }
     *             );
     *
     *             for (MyTypes t : MyTypes.values()) {
     *                 try {
     *                     Thread.sleep(800);
     *                     System.out.println("accepting... " + t);
     *                     typesSource.accept(t);
     *                 } catch (InterruptedException e) {
     *                     throw new RuntimeException(e);
     *                 }
     *             }
     *
     *             try {
     *                 Thread.sleep(2000);
     *             } catch (InterruptedException e) {
     *                 throw new RuntimeException(e);
     *             }
     *             Settings.shutDownNow();
     *         }
     *     }
     * }</pre>
     * <p>Results:</p>
     * <pre>{@code
     * accepting... a
     * MyObject[anInt=0]
     * accepting... b
     * MyObject[anInt=1]
     * accepting... c
     * accepting... d
     * MyObject[anInt=3]
     * }</pre>
     */
    public abstract <S> Path<S> map(Builder<S> builder, Function<T, S> map);

    public <S> Path<S> map(UnaryOperator<Builder<S>> builderOp, Function<T, S> map) {
        return map(builderOp.apply(new Builder<>()), map);
    }

    /**
     * Default implementation of {@link #map(Builder, Function)},
     * with the distinction that:
     *  <ul>
     *      <li> Omits the need of a builder.</li>
     *  </ul>

     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @param excludeIn the predicate to determine whether incoming signals should be excluded if true.
     * @return a new observable 'Path' containing the mapped element.
     * @see #map(Builder, Function)
     * */
    public <S> Path<S> map(Function<T, S> map, BinaryPredicate<S> excludeIn) {
        return map(Builder.inExcluded(excludeIn), map);
    }

    /**
     * Default implementation of {@link #map(Function, BinaryPredicate)},
     * with the distinction that:
     *  <ul>
     *      <li> Omits exclusion tests on outward dispatching signals.
     *      Outward signals are unaffected by exclusion criteria, ensuring unobstructed dispatches.</li>
     *      <li>{@param excludeIn} The test that shields the {@link Cache} from undesired states after applying the map function,
     *      now requires to test a single value (the new one to be applied) instead of 2.
     *      </li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @param excludeIn the predicate to determine whether incoming signals should be excluded if true.
     * @return a new observable {@link Impl} containing the mapped element.
     * @see #map(Function, BinaryPredicate)
     * */
    public <S> Path<S> map(Function<T, S> map, BinaryPredicate.Unary<S> excludeIn) {
        return map(map, (BinaryPredicate<S>) excludeIn);
    }

    /**
     * Default implementation of {@link #map(Builder, Function)},
     * with the distinction that:
     *  <ul>
     *      <li> Omits exclusion tests on inward swapping signals.
     *      Inward signals are unaffected by exclusion criteria, ensuring unobstructed swaps to the inner Path's {@link Cache}.
     *      </li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @param excludeOut The test that prevents outward dispatches towards other Path forks or {@link Publisher} observers if true.
     *
     * @return a new observable 'Path' containing the mapped element.
     * @see #map(Builder, Function)
     * */
    public <S> Path<S> map(Function<T, S> map, Predicate<S> excludeOut) {
        return map(Builder.outExcluded(excludeOut), map);
    }

    /**
     * Default implementation of {@link #map(Builder, Function)},
     * with the distinction that:
     *  <ul>
     *      <li> Uses a {@link Builder#getDefault()} as builder object.</li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @return a new observable 'Path' containing the mapped element.
     * @see #map(Builder, Function)
     * */
    public <S> Path<S> map(Function<T, S> map) { return map(Builder.getDefault(), map); }

    /**
     * A version of the {@link #map(Function)} method that supports access to the swapping transaction.
     *
     * @param <S>     The target type to map elements to.
     * @param map     The mapping function to convert elements to the target type.
     * @param onSwap  A callback that broadcasts the outcome of a Compare-and-Swap (CAS) operation.
     *                See {@link OnSwapped}.
     * @return a new observable {@link Impl} containing the mapped element.
     * @see #map(Function)
     */
    public abstract <S> Path<S> openMap(Builder<S> builder, Function<T, S> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onStateChanged);

    public <S> Path<S> openMap(Function<T, S> map, OnSwapped<S> onSwap) {
        return openMap(Builder.getDefault(), map, onSwap, Predicates.OfBoolean.Consumer.getDefault());
    }

    public <S> Path<S> openMap(Function<T, S> map, Predicates.OfBoolean.Consumer onActive) {
        return openMap(Builder.getDefault(), map, OnSwapped.getDefault(), onActive);
    }

    public <S> Path<S> openMap(UnaryOperator<Builder<S>> builderOperator, Function<T, S> map, OnSwapped<S> onSwap) {
        return openMap(builderOperator.apply(new Builder<>()), map, onSwap, Predicates.OfBoolean.Consumer.getDefault());
    }

    /**
     * Maps incoming states of type T to a new observable Path of a desired type.
     * The instance of {@link Impl} created during initialization adopts the outward value given by the
     * newly captured {@link Impl} via registration with the {@link Cache.Receiver} interface.
     * The `switchMap` function connects a {@link Cache.Receiver} observer whenever the resulting Path becomes active.
     * This is similar to a "stateless" {@link Join} operation, where the current state is discarded when a new value is found.
     * Only one mapping {@link Function} can be captured at a time; concurrent competing `switchMaps` will be ignored under newer and more recent contention.
     *
     * @param <S>        The type of the new state after mapping.
     * @param map        The mapping function responsible for defining the new Path eligible for observation.
     * @param builder The Builder of type S to be processed. This is an instance of the Builder design
     *                pattern, which allows for more readable and maintainable code when dealing with multiple parameters.
     * @return A new {@link Impl} representing the newly transformed state.
     *
     * <p><b>Note:</b> The `map` function should be side-effect-free, and the operation will retry until one of the following conditions is met:
     * <ul>
     *     <li>The swapping is completed.</li>
     *     <li>The "excludeIn" test deems the cache's state change unsuitable.</li>
     *     <li>A new concurrent signal arrives first.</li>
     * </ul>
     *
     * Object capturing is allowed. To use dummy paths, {@link Impl#getDummy()} can be employed for improved performance.
     * A dummy path's default inner value is {@code null}, and its dummy status can be determined using {@link Impl#isDummy()}.
     *
     * <p><b>Example:</b></p>
     * <pre>{@code
     * Source<Integer> id = new Source<>();
     *
     * Path<User> res = id.switchMap(integer -> userMap.get(integer),
     *         (BinaryPredicate.Unary<User>) User::notValid,
     *         User::shouldNOTDispatch);
     * }</pre>
     * In this example, the `switchMap` function maps user IDs to user profiles, excluding profiles that are deemed
     * invalid or should not be dispatched.
     */
    public abstract <S> Path<S> switchMap(Builder<S> builder, Function<T, Path<S>> map);

    public <S> Path<S> switchMap(Consumer<Builder<S>> builderOperator, Function<T, Path<S>> map) {
        return switchMap(Builder.getNew(builderOperator), map);
    }

    /**
     * Default implementation of {@link #switchMap(Builder, Function)},
     * with the distinction that:
     *  <ul>
     *      <li> Implicitly builds a Builder object with a {@link BinaryPredicate} input test.</li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @param excludeIn  A test that prevents undesired state changes in the {@link Cache} after applying the map function.
     *                   If the test returns true, the swapping operation is skipped.
     * @return A new {@link Impl} representing the newly transformed state.
     * @see #switchMap(Builder, Function)
     * */
    public <S> Path<S> switchMap(Function<T, Path<S>> map, BinaryPredicate<S> excludeIn) {
        return switchMap(Builder.inExcluded(excludeIn), map);
    }

    /**
     * Default implementation of {@link #switchMap(Function, BinaryPredicate)},
     * with the distinction that:
     *  <ul>
     *      <li> Implicitly builds a Builder object with a {@link BinaryPredicate.Unary} input test.</li>
     *      <li>{@param excludeIn} The test that shields the {@link Cache} from undesired states after applying the map function,
     *      now requires to test a single value (the new one to be applied) instead of 2.
     *      </li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @param excludeIn  A test that prevents undesired state changes in the {@link Cache} after applying the map function.
     *                   If the test returns true, the swapping operation is skipped.
     * @return A new {@link Impl} representing the newly transformed state.
     * @see #switchMap(Function, BinaryPredicate)
     * */
    public <S> Path<S> switchMap(Function<T, Path<S>> map, BinaryPredicate.Unary<S> excludeIn) {
        return switchMap(Builder.inExcluded(excludeIn), map);
    }

    /**
     * Default implementation of {@link #switchMap(Builder, Function)},
     * with the distinction that:
     *  <ul>
     *      <li> Implicitly builds a {@link Publisher.Builder} object with a {@link Predicate} input test.</li>
     *      <li> Omits exclusion tests on inward swapping signals.
     *      Inward signals are unaffected by exclusion criteria, ensuring unobstructed swaps to the inner Path's {@link Cache}.
     *      </li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     * @param excludeOut The test that prevents outward dispatches towards other Path forks or {@link Publisher} observers if true.
     *
     * @return A new {@link Impl} representing the newly transformed state.
     * @see #switchMap(Builder, Function)
     * */
    public <S> Path<S> switchMap(Function<T, Path<S>> map, Predicate<S> excludeOut) {
        return switchMap(Builder.outExcluded(excludeOut), map);
    }

    /**
     * Default implementation of {@link #switchMap(Function, BinaryPredicate)},
     * with the distinction that:
     *  <ul>
     *      <li> Implicitly builds a {@link Builder#getDefault()} object.</li>
     *      <li> Omits exclusion {@link Predicate} tests on outward dispatching signals.
     *      Outward signals are unaffected by exclusion criteria, ensuring unobstructed dispatches.</li>
     *      <li> Omits exclusion {@link BinaryPredicate} tests on inward swapping signals.
     *      Inward signals are unaffected by exclusion criteria, ensuring unobstructed swaps to the inner Path's {@link Cache}.
     *      </li>
     *  </ul>
     *
     * For complete method details and alternative behavior, refer to the original method documentation.
     *
     * @param <S> the target type to map elements to.
     * @param map the mapping function to convert elements to the target type.
     *
     * @return A new {@link Impl} representing the newly transformed state.
     * @see #switchMap(Function, BinaryPredicate)
     * */
    public <S> Path<S> switchMap(Function<T, Path<S>> map) { return switchMap(Builder.getDefault(), map); }

    /**
     * A version of the {@link #switchMap(Function)} method that supports access to the swapping transaction.
     *
     * @param <S>     The target type to map elements to.
     * @param map     The mapping function to convert elements to the target type.
     * @param onSwap  A callback that broadcasts the outcome of a Compare-and-Swap (CAS) operation.
     *                See {@link OnSwapped}.
     *
     * @return A new {@link Impl} representing the newly transformed state.
     *
     * @see #switchMap(Function)
     */
    public abstract <S> Path<S> openSwitchMap(Builder<S> builder, Function<T, Path<S>> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive);

    public <S> Path<S> openSwitchMap(Function<T, Path<S>> map, OnSwapped<S> onSwap) {
        return openSwitchMap(Builder.getDefault(), map, onSwap, Predicates.OfBoolean.Consumer.getDefault());
    }

    public <S> Path<S> openSwitchMap(Consumer<Builder<S>> builderOperator, Function<T, Path<S>> map, OnSwapped<S> onSwap) {
        return openSwitchMap(Builder.getNew(builderOperator), map, onSwap, Predicates.OfBoolean.Consumer.getDefault());
    }

    /**Forks a new Path that will get populated with the incoming emissions If the test returns true.*/
    public Path<T> expect(BinaryPredicate<T> test) { return map(Lambdas.Identities.identity(), test.negate()); }

    /**Forks a new Path that will get populated with the incoming emissions If the test returns true.*/
    public Path<T> expect(BinaryPredicate.Unary<T> test) { return expect((BinaryPredicate<T>) test); }

    public abstract Supplier<T> getCache();

    abstract Versioned<T> isConsumable();

    /**
     * This Path can be assigned to a {@link Ref.Eager} referent, which will grant an additional reference access to this Path.
     * This also allows this path to be retrieved from a {@link Ref.Storage} via {@link Ref#getId()}
     * */
    public Path<T> assign(Ref.Eager<T> referent) { return assign((Ref<T>) referent); }
    abstract Path<T> assign(Ref<T> referent);
    /**De-references the {@link Ref} associated with this Path*/
    public abstract boolean deReference();

    abstract void softDeactivate(Cache.Receiver<T> receiver);
    abstract boolean contains(Cache.Receiver<T> receiver);

    abstract boolean isDiff(Path<?> that);
    /**
     * This method will begin the {@link Activators.Propagator#backProp()} method.
     * @return null if the Path that executes this method has NO value to deliver downstream(default),
     * or if the value did NOT pass the Predicate test
     * */
    abstract Versioned<T> activate(Cache.Receiver<T> receiver, BooleanSupplier allow, BooleanSupplier onSet);

    public abstract String toStringDetailed();

    /**
     * A class that helps with Constructor parameter overloading (Parameter Object Pattern)
     * The default value of this Builder defined at {@link #getDefault()}
     * The parameters defined by this Object are:
     * <ul>
     *     <li>
     * {@link #excludeIn}  The test that shields the {@link Path} from undesired states after applying the map function.
     *                   If the test returns true, the swapping operation will be skipped.
     *                   This test WILL NOT SUPERSEDE reference AND Object equality constraints (see {@link Objects#equals(Object, Object)}) and WILL ONLY be applied ONCE
     *                   their references have been checked as <b>UNEQUAL</b> to the one already inside the {@link Cache}.
     * <p> params:
     * <p>               T1 = The current state present in this {@link Path}
     * <p>               T2 = The incoming state.
     * <p>               @return = The resulting value to swap the current state.
     *     </li>
     *     <li>
     *  {@link #initialValue} The first value to be set to this {@link Path}.
     *                     If the value does not meet the requirements defined by `excludeIn` parameter an error will be thrown.
     *                     If this value is null the Cache will be considered in a default initial state and observers will get emissions once a first value is finally given.
     *     </li>
     *     <li>
     *          {@link #excludeOut} : a {@link Predicate} test defining which emissions should be dispatched to all child nodes.
     *     </li>
     * </ul>
     * */
    public static class Builder<T> {
        Versioned<T> initialValue = Versioned.getDefault();
        BinaryPredicate<T> excludeIn = Lambdas.BinaryPredicates.defaultFalse();
        Predicate<T> excludeOut = Predicates.defaultFalse();

        private void inAssertion() { assert excludeIn.isAlwaysFalse() : inErr; }

        private void outAssertion() {
            assert Boolean.FALSE.equals(Lambdas.Predicates.defaultType(excludeOut)) : outErr;
        }

        private void valueAssertion() { assert initialValue.isDefault() : valErr; }

        private static final String
                inErr = "Insertion test already defined.",
                outErr = "Output test already defined.",
                valErr = "Value is already defined.";
        record defaultBuilder() {
            @SuppressWarnings("unchecked")
            private static<E> Builder<E> ref() {
                return (Builder<E>) ref;
            }
            private static final Builder<?> ref = new Builder<>() {
                @Override
                public Builder<Object> excludeIn(BinaryPredicate<Object> excludeIn) {
                    return throwNonAccessible("excludeIn");
                }

                @Override
                public Builder<Object> excludeIn(BinaryPredicate.Unary<Object> excludeIn) {
                    return throwNonAccessible("excludeIn");
                }

                @Override
                public Builder<Object> excludeOut(Predicate<Object> excludeOut) { return throwNonAccessible("excludeOut"); }

                @Override
                public Builder<Object> expectIn(BinaryPredicate.Unary<Object> expectIn) {
                    return throwNonAccessible("expectIn");
                }

                @Override
                public Builder<Object> expectIn(BinaryPredicate<Object> expectIn) {
                    return throwNonAccessible("expectIn");
                }

                @Override
                public Builder<Object> expectOut(Predicate<Object> expectOut) {
                    return throwNonAccessible("expectOut");
                }

                private Builder<Object> throwNonAccessible(String s) {
                    throw new RuntimeException(
                            new IllegalAccessException(
                                    s.concat(" not accessible from this Default Builder, use a new Builder instead.")
                            )
                    );
                }
            };
        }

        @SuppressWarnings("unchecked")
        public static<T> Builder<T> getNew(Consumer<Builder<T>> op) {
            if (Lambdas.Consumers.isEmpty(op)) {
                return (Builder<T>)defaultBuilder.ref;
            } else {
                Builder<T> b = new Builder<>();
                op.accept(b);
                return b;
            }
        }

        boolean isDefault() { return this == defaultBuilder.ref ||
                this.equals(defaultBuilder.ref);
        }

        private static<T> boolean isDefaultOp(Consumer<Builder<T>> builderOp) {
            return builderOp == null || Lambdas.Consumers.isEmpty(builderOp);
        }

        static<T> Builder<T> resolve(
                T initialValue,
                Consumer<Builder<T>> builderOp
        ) {
            boolean defaultOp = isDefaultOp(builderOp);

            if (defaultOp) {
                if (initialValue == null) return defaultBuilder.ref();
                else return Builder.withValue(initialValue);
            } else {
                if (initialValue == null) {
                    Builder<T> res = new Builder<>();
                    builderOp.accept(res);
                    if (res.isDefault()) return defaultBuilder.ref();
                    else return res;
                } else {
                    Builder<T> res = Builder.withValue(initialValue);
                    builderOp.accept(res);
                    return res;
                }
            }
        }

        /**
         * A default version of the Builder object which contains:
         * <ul>
         *     <li>
         *          T initialValue = null;
         *     </li>
         *     <li>
         *         BinaryPredicate<T> excludeIn = DOES NOT APPLY, emissions are let pass, EXCEPT for those that meet the the conditions defined at:
         *         {@link Objects#equals(Object, Object)} where the first parameter wil be the next new item to enter the {@link Cache}
         *         and the second parameter is the current value already within it.
         *     </li>
         *     <li>
         *         {@link Predicate} excludeOut = DOES NOT APPLY.
         *     </li>
         * </ul>
         * */
        public static<S> Builder<S> getDefault() {
            return defaultBuilder.ref();
        }

        public static<S> Builder<S> inExcluded(BinaryPredicate<S> excludeIn) {
            Builder<S> b = new Builder<>();
            if (excludeIn.isAlwaysFalse()) return b;
            return b.excludeIn(excludeIn);
        }

        public static<S> Builder<S> withValue(S initialValue) {
            Builder<S> b = new Builder<>();
            if (initialValue == null) return b;
            return b.initialValue(initialValue);
        }

        public static<S> Builder<S> inExcluded(BinaryPredicate.Unary<S> excludeIn) {
            Builder<S> b = new Builder<>();
            return b.excludeIn(excludeIn);
        }

        public static<S> Builder<S> outExcluded(Predicate<S> excludeOut) {
            Builder<S> b = new Builder<>();
            return b.excludeOut(excludeOut);
        }

        /**
         * @param excludeIn  The test that shields the {@link Cache} from undesired states after applying the map function.
         *                   If the test returns true, the swapping operation will BE SKIPPED.
         *                   This test WILL NOT SUPERSEDE reference equality constraints and WILL ONLY be applied ONCE
         *                   their references have been checked as <b>UNEQUAL</b> to the one already inside the {@link Cache}.
         **/
        public Builder<T> excludeIn(BinaryPredicate<T> excludeIn) {
            assert !excludeIn.isAlwaysFalse() : "By default the value of this test is `binaryAlwaysFalse()`, so this value is not accepted by this method.";
            inAssertion();
            this.excludeIn = excludeIn;
            return this;
        }

        /**
         * @param excludeIn the predicate to determine whether incoming signals should be EXCLUDED if true.
         * now requires to test a single value (the new one to be applied) instead of 2.
         **/
        public Builder<T> excludeIn(BinaryPredicate.Unary<T> excludeIn) {
            assert !excludeIn.isAlwaysFalse() : "By default the value of this test is `binaryAlwaysFalse()`, so this value is not accepted by this method.";
            inAssertion();
            this.excludeIn = excludeIn;
            return this;
        }

        /**
         * @param initial The initial Value to be contained in this Path
         **/
        public Builder<T> initialValue(T initial) {
            valueAssertion();
            assert initial != null : "An initial value must not be null";
            this.initialValue = Versioned.first(initial);
            return this;
        }

        /**
         * The negation of {@link Builder#excludeIn}
         * */
        public Builder<T> expectIn(BinaryPredicate<T> expectIn) {
            inAssertion();
            this.excludeIn = expectIn.negate();
            return this;
        }

        /**
         * The negation of {@link Builder#excludeIn}
         * */
        public Builder<T> expectIn(BinaryPredicate.Unary<T> expectIn) {
            inAssertion();
            this.excludeIn = expectIn.negate();
            return this;
        }

        /**
         *  @param excludeOut The test that prevents outward dispatch towards other Path forks or {@link Publisher} observers.
         *                    If the test returns true, the dispatch will be blocked.
         **/
        public Builder<T> excludeOut(Predicate<T> excludeOut) {
            outAssertion();
            this.excludeOut = excludeOut;
            return this;
        }

        /**
         * The negation of {@link Builder#excludeOut}
         * */
        public Builder<T> expectOut(Predicate<T> expectOut) {
            outAssertion();
            this.excludeOut = expectOut.negate();
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Builder<?> builder = (Builder<?>) o;
            Versioned<?> i_v = builder.initialValue;
            return ((initialValue == i_v) || (initialValue != null && ((Object) initialValue).equals(i_v)))
                    && Objects.equals(excludeIn, builder.excludeIn)
                    && Objects.equals(excludeOut, builder.excludeOut);
        }

        @Override
        public int hashCode() { return Objects.hash(initialValue, excludeIn, excludeOut); }
    }

    //This dummy Path still needs some code to fulfill default implementations.
    private record DUMMY() {
        private static final Path<?> ref = new Path<>() {

            @Override
            public boolean isActive() { return false; }

            @SuppressWarnings("unchecked")
            @Override
            public <S> Path<S> map(Builder<S> builder, Function<Object, S> map) { return (Path<S>) this; }

            @Override
            public <S> Path<S> openMap(Builder<S> builder, Function<Object, S> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
                return null;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <S> Path<S> switchMap(Builder<S> builder, Function<Object, Path<S>> map) {
                return (Path<S>) this;
            }

            @Override
            public <S> Path<S> openSwitchMap(Builder<S> builder, Function<Object, Path<S>> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
                return null;
            }

            @Override
            public Supplier<Object> getCache() { return null; }

            @Override
            Versioned<Object> isConsumable() { return null; }

            @Override
            Path<Object> assign(Ref<Object> referent) { return null; }

            @Override
            public boolean deReference() { return false; }

            @Override
            void softDeactivate(Cache.Receiver<Object> receiver) {}

            @Override
            boolean contains(Cache.Receiver<Object> receiver) { return false; }

            @Override
            boolean isDiff(Path<?> that) { return that != ref; }

            @Override
            Versioned<Object> activate(Cache.Receiver<Object> receiver, BooleanSupplier allow, BooleanSupplier onSet) { return null; }

            @Override
            public String toStringDetailed() { return null; }

            @Override
            public void add(Consumer<? super Object> observer) {}

            @Override
            public Consumer<? super Object> remove(Object subscriber) {
                return null;
            }

            @Override
            public boolean contains(Object subscriber) { return false; }

            @Override
            public int observerSize() { return 0; }

            @Override
            public Publisher<Object> getPublisher(Executor executor) { return null; }

            @Override
            public String toString() { return "DUMMY PATH @" + hashCode(); }

            @Override
            public boolean isDummy() { return true; }
        };
    }

    public boolean isDummy() { return false; }

    @SuppressWarnings("unchecked")
    public static<S> Path<S> getDummy() { return (Path<S>) DUMMY.ref; }

    public static class Impl<T> extends Path<T> {
        private final boolean concurrent = Settings.concurrent;
        private final boolean debug_mode = Settings.DEBUG_MODE.ref;

        final Cache<T> cache;
        final ReceiversManager receiversManager;
        final Activators.Propagator<T> parentPropagator;

        private volatile Ref<T> ref;
        private static final VarHandle REF;
        static {
            try {
                REF = MethodHandles.lookup().findVarHandle(Impl.class, "ref", Ref.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        boolean deref(Ref<T> ref) { return REF.compareAndSet(this, ref, null); }

        @SuppressWarnings("unchecked")
        @Override
        final Path<T> assign(Ref<T> referent) {
            Object prev = REF.getAndSet(this, referent.sysAlloc(this));
            if (prev != null) {
                ((Ref<T>)prev).removeOwner(this);
                throw new IllegalStateException("This Path already had a reference = " + prev);
            }
            return this;
        }

        protected boolean compareAndSwap(T expect, T set) { return cache.compareAndSwap(expect, set); }

        protected boolean weakSet(T set) { return cache.weakSet(set); }

        private final LazyHolder.Supplier<Publisher<T>> publisherInstance = LazyHolder.Supplier.getNew(
                () -> new PublisherImpl(concurrent ? Settings.getExit_executor() : null)
        );

        @Override
        public Supplier<T> getCache() {
            return new Supplier<>() {
                @Override
                public T get() { return cache.liveGet(); }

                @Override
                public String toString() { return cache.toString(); }
            };
        }

        @Override
        Versioned<T> isConsumable() { return cache.isConsumable(); }

        @Override
        public boolean isActive() { return receiversManager.isActive(); }

        protected void CASAttempt(boolean success, Versioned<T> prev, Versioned<T> current) {}

        public boolean notStored() { return ref == null; }

        @SuppressWarnings({"unchecked", "unused"})
        public boolean deReference() {
            Object prev = REF.getAndSet(this, null);
            return prev != null && ((Ref<T>)prev).removeOwner(this);
        }

        @Override
        public <S> Path<S> map(Path.Builder<S> builder, Function<T, S> map) {
            Function<T, S> finalMap;
            if (debug_mode) {
                finalMap = Exceptionals.exceptional(Exceptionals.DebugConfig.Token.sysDefaults(), map);
            } else { finalMap = map; }
            return new Impl<>(
                    builder, this,
                    finalMap
            );
        }

        @Override
        public <S> Path<S> openMap(Path.Builder<S> builder, Function<T, S> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            boolean notSwap = onSwap.isDefault();
            if (notSwap && onActive.isDefault()) {
                return new Impl<>(builder, this, map);
            } else if (notSwap) { // no swap ... just onActive
                return new Impl<>(
                        builder, this, map){
                    @Override
                    protected void onStateChange(boolean isActive) { onActive.accept(isActive); }
                };
            } else if (onActive.isDefault()) { // no onActive ... just swap
                return new Impl<>(
                        builder, this, map){
                    @Override
                    protected void CASAttempt(boolean success, Versioned<S> prev, Versioned<S> current) {
                        onSwap.attempt(success, prev.value(), current.value());
                    }
                };
            } else { // both
                return new Impl<>(
                        builder, this, map){
                    @Override
                    protected void CASAttempt(boolean success, Versioned<S> prev, Versioned<S> current) {
                        onSwap.attempt(success, prev.value(), current.value());
                    }

                    @Override
                    protected void onStateChange(boolean isActive) { onActive.accept(isActive); }
                };
            }
        }

        private static final String
                defNullPathMsg =
                """
                        
                        A Path returned by function `Function<T, Path<S>> map` of a `switchMap()` method, was `null`,\s
                         turn Settings.debug_mode = true and replay the error to get the exact stackTrace,
                         making this information always available would slow down the system.
                         """,
                solution = """
                         
                         If there is any chance that the Path returned will end up being null, catch `null` before the function returns...:
                          * If you need to pass an empty Path, you could use `Path.getDummy()` instead.
                          * You could also use your own default Path using a Source instance and define its own initial value.
                        """,
                location = """
                         
                         The error may have occurred at one of the switchMaps of Path:
                        """;


        @Override
        public <S> Impl<S> switchMap(Path.Builder<S> builder, Function<T, Path<S>> map) {
            StackTraceElement[] es = debug_mode ? Thread.currentThread().getStackTrace() : null;
            return new SwappableActionablePath<>(builder) {
                final Impl<Path<S>> mapped = new Impl<>(Impl.this, map, Lambdas.BinaryPredicates.defaultFalse(), Predicates.defaultFalse()) {
                    @Override
                    protected void CASAttempt(boolean success, Versioned<Path<S>> prev, Versioned<Path<S>> current) {
                        if (success) {
                            int curr = current.version();
                            if (curr == cache.getAsInt()) {
                                Path<S> currentP = current.value();
                                if (currentP != null) {
                                    if (
                                            Impl.this.receiversManager.isActive()
                                    ) {
                                        Activators.GenericShuttableActivator<S, Activators.PathedBinaryState<?, S>>
                                                prev_gs = set(
                                                curr,
                                                cache,
                                                currentP
                                        );
                                        assert prev_gs == null || prev_gs.isOff() : " >>> GS = " + prev_gs;
                                    }
                                } else {
                                    throw new IllegalStateException(
                                            es != null ?
                                                    "\n Path was null: ".concat(
                                                            Exceptionals.formatStack(0, es)
                                                    ).concat("\n").concat(solution)
                                                    :
                                                    defNullPathMsg.concat(solution).concat(location).concat(Impl.this.toStringDetailed())
                                    );
                                }
                            }
                        }
                    }
                };

                @Override
                boolean sysActivate() {
                    sysRegister.activate();
                    mapped.activate();
                    return true;
                }

                @Override
                void sysDeactivate() {
                    mapped.deactivate();
                    sysRegister.deactivate();
                }
            };
        }

        @Override
        public <S> Impl<S> openSwitchMap(Path.Builder<S> builder, Function<T, Path<S>> map, OnSwapped<S> onSwap, Predicates.OfBoolean.Consumer onActive) {
            return new SwappableActionablePath<>(builder) {
                final Impl<Path<S>> mapped = new Impl<>(
                        Builder.getDefault(), Impl.this, map) {

                    @Override
                    protected void CASAttempt(boolean success, Versioned<Path<S>> prev, Versioned<Path<S>> current) {
                        if (success) {
                            if (current.version() == cache.getAsInt()) {
                                Path<S> currentP = current.value();
                                if (
                                        Impl.this.receiversManager.isActive()
                                ) {
                                    Activators.GenericShuttableActivator<S, Activators.PathedBinaryState<?, S>>
                                            next = set(current.version()
                                            , cache
                                            , currentP);
                                    if (next != null) {
                                        if (current.version() != cache.getAsInt()) {
                                            next.shutOff();
                                        }
                                    }
                                }
                            }
                        }
                    }
                };

                @Override
                protected void CASAttempt(boolean success, Versioned<S> prev, Versioned<S> current) {
                    onSwap.attempt(success, prev.value(), current.value());
                }

                @Override
                boolean sysActivate() {
                    sysRegister.activate();
                    mapped.activate();
                    onActive.accept(true);
                    return true;
                }

                @Override
                void sysDeactivate() {
                    onActive.accept(false);
                    mapped.deactivate();
                    sysRegister.deactivate();
                }
            };
        }

        @Override
        public void add(Consumer<? super T> observer) { getInstance().add(observer); }

        @Override
        public Consumer<? super T> remove(Object subscriber) {
            Publisher<T> p = publisherInstance.getOpaque();
            if (p != null) {
                Consumer<? super T> res = p.remove(subscriber);
                if (res != null) {
                    Ref<T> current = ref;
                    if (current != null) current.removeRefObserver(res);
                }
                return res;
            } else return null;
        }

        @Override
        public boolean contains(Object subscriber) {
            Publisher<T> p = publisherInstance.getOpaque();
            return p != null && p.contains(subscriber);
        }

        @Override
        public int observerSize() {
            Publisher<T> p = publisherInstance.getOpaque();
            return p == null ? 0 : p.observerSize();
        }

        private final LazyHolder.Supplier<Map<Object, PublisherImpl>> publishers = LazyHolder.Supplier.getNew(
                ConcurrentHashMap::new
        );

        private static final Object dummyExecutor = new Object();
        private PublisherImpl getPublisherFor(Executor executor) {
            final Object key = executor == null ? dummyExecutor : executor;
            return publishers.get().computeIfAbsent(
                    key,
                    o -> new PublisherImpl(executor)
            );
        }

        @Override
        public Publisher<T> getPublisher(Executor executor) {
            return
                    concurrent && executor == Settings.getExit_executor() ?
                            getInstance()
                            : getPublisherFor(executor);
        }

        boolean activate() {
            if (receiversManager.defaultActivate()) {
                parentPropagator.backProp();
                sysActivate();
                onStateChange(true);
                return true;
            } else return false;
        }

        void deactivate() {
            if (receiversManager.defaultDeactivate()) {
                onStateChange(false);
                sysDeactivate();
                parentPropagator.deactivate();
            }
        }

        /**@return NON-NULL if CAN dispatch*/
        Versioned<T> commenceActivation(
                Cache.Receiver<T> strategy
        )
        {
            if (receiversManager.addReceiver(strategy)) {
                Versioned<T> ver = parentPropagator.backProp();
                sysActivate();
                onStateChange(true);
                return ver;
            } else return cache.isConsumable();
        }

        @Override
        Versioned<T> activate(Cache.Receiver<T> receiver, BooleanSupplier allow, BooleanSupplier onSet) {
            if (receiversManager.addReceiver(receiver, allow, onSet) == 0) {

                // The sequence of events allow for on-the-fly sequential self removal of observers.
                Versioned<T> ver = parentPropagator.backProp();
                sysActivate();
                onStateChange(true);

                // The double check allows for sequential inner state changes during
                // calls to `onStateChange(true)`
                if (ver == cache.localCache) return ver;
                //Changing the order of the operations above will result in bugs:
                // - Potential `hanged Listener` bug if self-detaching the Observer while receiving data.
                // - Incorrect data propagation versioning when altering cache state during `onStateChange(true)`
                else return null;
            } else return cache.isConsumable();
        }

        /**"I've abandoned my child"*/
        void commenceDeactivation(
                Cache.Receiver<T> strategy
        )
        {
            if (receiversManager.nonContRemove(strategy)) {
                onStateChange(false);
                sysDeactivate();
                parentPropagator.deactivate();
            }
        }

        @Override
        void softDeactivate(Cache.Receiver<T> receiver) {
            if (receiversManager.hardRemove30Throw(receiver)) {
                onStateChange(false);
                sysDeactivate();
                parentPropagator.deactivate();
            }
        }

        @Override
        boolean contains(Cache.Receiver<T> receiver) { return receiversManager.contains(receiver); }

        @Override
        boolean isDiff(Path<?> that) { return that != this; }

        protected T getValue() { return cache.get().value(); }

        Impl(
                BiFunction<Cache<T>, ReceiversManager,
                        Activators.Propagator<T>> parentActivator
        ) {
            this(
                    Path.Builder.getDefault(),
                    parentActivator
            );
        }

        Impl(
                BinaryPredicate<T> excludeIn,
                Predicate<T> excludeOut,
                BiFunction<Cache<T>, ReceiversManager,
                        Activators.Propagator<T>> parentActivator
        ) {
            this(
                    excludeIn,
                    Versioned.getDefault(),
                    excludeOut,
                    parentActivator
            );
        }

        Impl(
                Path.Builder<T> builder,
                BiFunction<Cache<T>, ReceiversManager,
                        Activators.Propagator<T>> parentActivator
        ) {
            this(
                    builder.excludeIn,
                    builder.initialValue,
                    builder.excludeOut,
                    parentActivator
            );
        }

        protected Impl() { this((BiFunction<Cache<T>, ReceiversManager, Activators.Propagator<T>>) null); }

        protected Impl(
                BinaryPredicate<T> excludeIn
        ) {
            this(
                    excludeIn,
                    Versioned.getDefault(),
                    Predicates.defaultFalse()
            );
        }

        protected Impl(
                BinaryPredicate<T> excludeIn,
                Predicate<T> excludeOut
        ) {
            this(
                    excludeIn,
                    Versioned.getDefault(),
                    excludeOut
            );
        }

        Impl(
                BinaryPredicate<T> excludeIn,
                Versioned<T> initialValue,
                Predicate<T> excludeOut
        ) {
            this(
                    excludeIn,
                    initialValue,
                    excludeOut,
                    null
            );
        }

        Impl(
                BinaryPredicate<T> excludeIn,
                Versioned<T> initialValue,
                Predicate<T> excludeOut,
                BiFunction<Cache<T>, ReceiversManager,
                        Activators.Propagator<T>> parentActivator
        ) {
            assert initialValue != null;
            this.cache = new Cache<>(
                    excludeIn,
                    initialValue,
                    excludeOut,
                    () -> Impl.this.receiversManager.getDispatcher().run(),
                    this::CASAttempt
            );
            this.receiversManager = new ReceiversManager();
            this.parentPropagator = parentActivator == null ?
                    new Activators.Propagator<>() {
                        @Override
                        public Versioned<T> backProp() {
                            return cache.isConsumable();
                        }

                        @Override
                        public void deactivate() {

                        }
                        private static final String tag = "Activators.Propagator[Head Propagator]@";

                        @Override
                        public String toString() {
                            return tag.concat(String.valueOf(this.hashCode()));
                        }
                    }
                    :
                    parentActivator.apply(this.cache, receiversManager);
        }

        Impl(
                T initialValue,
                Path.Builder<T> builder
        ) {
            this(builder.excludeIn, initialValue == null ? Versioned.getDefault() : Versioned.first(initialValue), builder.excludeOut, null);
        }

        Impl(
                Path.Builder<T> builder
        ) {
            this(builder.excludeIn, builder.initialValue, builder.excludeOut, null);
        }

        <P> Impl(
                Path<P> parent, Function<P, T> map,
                BinaryPredicate<T> excludeIn,
                Predicate<T> excludeOut
        ) {
            this(
                    excludeIn,
                    excludeOut,
                    (cache, manager) -> {
                        assert parent != null && map != null;
                        return Activators.Propagator.getLinked(cache, map, parent);
                    }
            );
        }

        <P> Impl(
                Path.Builder<T> builder,
                Path<P> parent, Function<P, T> map
        ) {
            this(
                    builder,
                    (cache, manager) -> {
                        assert parent != null && map != null;
                        return Activators.Propagator.getLinked(cache, map, parent);
                    }
            );
        }

        private Publisher<T> getInstance() { return publisherInstance.get(); }

        final class PublisherImpl
                implements Publisher<T>
        {
            @SuppressWarnings({"unchecked", "rawtypes"})
            final CopyOnWriteArray<SubscriberWrapper<T>> subscribers  = new CopyOnWriteArray(SubscriberWrapper.class);

            private final Runnable dispatcher;
            private final Cache.Receiver<T> strategy = tVersioned -> dispatch();
            @Override
            public boolean isActive() { return !subscribers.isEmptyOpaque(); }

            /**Executor must be applied if contention will be met, OR when a specific output Thread is required*/
            PublisherImpl(Executor executor) {
                super();
                boolean nonConcurrent = executor == null;

                if (nonConcurrent) {
                    this.dispatcher = () -> {
                        final Versioned<T> versioned = cache.get();
                        final SubscriberWrapper<T>[] wrappers = subscribers.get();
                        int wl = wrappers.length;
                        for (int i = 0; i < wl; i++) {
                            wrappers[i].accept(versioned);
                        }
                    };
                } else {
                    final Executors.ScopedExecutor retryExecutor = new Executors.ScopedExecutor(
                            executor,
                            () -> {
                                Versioned<T> versioned = cache.get();
                                int next = versioned.version();
                                SubscriberWrapper<T>[] subs = subscribers.get();

                                final int lateLength = subs.length;

                                if (lateLength == 0) return true; //end
                                if (next < cache.getAsInt()) return false; // retry

                                for (int i = 0; i < lateLength; i++) {
                                    subs[i].accept(versioned);
                                }

                                return versioned == cache.getOpaque();
                            }
                    );
                    this.dispatcher = new VersionedExecutor(
                            cache,
                            () -> {
                                if (subscribers.get().length > 0) {
                                    retryExecutor.execute();
                                }
                            }
                    );
                }
            }

            private static final class VersionedExecutor implements Runnable, IntSupplier {
                @SuppressWarnings("FieldMayBeFinal")
                private volatile int dispatchCount = 0;
                private final IntSupplier versionedSupplier;
                private final Runnable performConsumption;
                private static final VarHandle VALUE_HANDLE;
                static {
                    try {
                        VALUE_HANDLE = MethodHandles.lookup().findVarHandle(VersionedExecutor.class, "dispatchCount", int.class);
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        throw new Error(e);
                    }
                }

                private VersionedExecutor(
                        IntSupplier versionedSupplier,
                        Runnable performConsumption) {
                    this.versionedSupplier = versionedSupplier;
                    this.performConsumption = performConsumption;
                }

                @Override
                public void run() {
                    int prev = dispatchCount, next = versionedSupplier.getAsInt();
                    while (next > prev)
                    {
                        if (VALUE_HANDLE.weakCompareAndSet(this, prev, next)) {
                            performConsumption.run();
                            break;
                        } else prev = dispatchCount;
                    }
                }

                @Override
                public int getAsInt() { return dispatchCount; }
            }

            static class SubscriberWrapper<T> implements Consumer<Versioned<T>>{
                final Consumer<? super T> core;

                /**
                 * This does not need to be volatile.
                 * All accesses are done on the same Thread, so writer vs reader side reordering will never be an issue.
                 * Unless, JIT + Processor does a double reordering mess... loading the cache BEFORE the `while`.
                 * */
                volatile int version;
                private static final VarHandle VALUE_HANDLE;
                static {
                    try {
                        VALUE_HANDLE = MethodHandles.lookup()
                                .findVarHandle(SubscriberWrapper.class, "version", int.class);
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        throw new ExceptionInInitializerError(e);
                    }
                }

                SubscriberWrapper(Consumer<? super T> core) {
                    if (core == null) throw new IllegalArgumentException("Core cannot be null");
                    this.core = core;
                }

                /**
                 * The array implementation must perform a proper equality
                 * */
                @Override
                public boolean equals(Object o) {
                    return ((o == core) || (o == this))
                           || (
                                   core.equals(o) // We should obey the doctrine of the CopyOnWriteArray
                                   // class which dictates comparing THIS against the OUTER object first.
                                   // Specially since the Wrapper is hidden within the package so the
                                   // important thing to compare... is the actual `core` field.
                                   ||
                                   (o != null && o.equals(core))
                           );
                }

                @Override
                public int hashCode() { return Objects.hash(core); }

                @Override
                public void accept(Versioned<T> versioned) {
                    int prev = version, next = versioned.version();
                    while (
                            prev < next
                    ) {
                        if (VALUE_HANDLE.weakCompareAndSet(this, prev, next)) {
                            core.accept(versioned.value());
                            return;
                        }
                        prev = version;
                    }
                }
            }

            @Override
            public void add(Consumer<? super T> subscriber) {
                SubscriberWrapper<T> wrapper = new SubscriberWrapper<>(subscriber);
                if (subscribers.add(wrapper) == 0) {
                    Versioned<T> next = commenceActivation(strategy);
                    if (next != null) wrapper.accept(next);
                } else {
                    Versioned<T> versioned = cache.get();
                    if (!versioned.isDefault()) wrapper.accept(versioned);
                }
            }

            @Override
            public Consumer<? super T> remove(Object subscriber) {
                CopyOnWriteArray.Search<SubscriberWrapper<T>> res = subscribers.nonContRemove(subscriber);
                if (res != null) {
                    SubscriberWrapper<T> sub = res.find();
                    if (sub != null) {
                        if (res.size() == 0) commenceDeactivation(strategy);
                        return sub.core;
                    } else return null;
                } else throw new ConcurrentModificationException("Use contentious removal instead.");
            }

            @Override
            public boolean contains(Object subscriber) {
                return subscribers.contains(subscriber);
            }

            @Override
            public int observerSize() {
                return subscribers.size();
            }

            private void dispatch() { if (isActive()) dispatcher.run(); }

            @Override
            public String toString() {
                return "Publisher{" +
                        "\n subscribers=" + subscribers +
                        "\n , receiver=" + strategy +
                        "\n , owner=" + Impl.this +
                        "\n, cache=" + cache +
                        "\n}@" + hashCode();
            }
        }

        class ReceiversManager implements Activators.State {

            @SuppressWarnings({"unchecked", "rawtypes"})
            private final CopyOnWriteArray<Cache.Receiver<T>> receivers = new CopyOnWriteArray(Cache.Receiver.class);

            private static final Cache.Receiver<?>[] defaultReceivers = new Cache.Receiver[]{Cache.Receiver.getDefault()};

            @SuppressWarnings("unchecked")
            static<T> Cache.Receiver<T>[] getDefArr() { return (Cache.Receiver<T>[]) defaultReceivers; }

            private final Runnable optional_dispatcher;
            private volatile Runnable dispatcher = Lambdas.emptyRunnable();

            Runnable getDispatcher() { return dispatcher; }

            public ReceiversManager() {
                final Executors.ScopedExecutor executor = concurrent ? new Executors.ScopedExecutor(
                        Settings.getWork_executor(),
                        () -> {
                            Versioned<T> lateVersioned = cache.get();
                            int versions = lateVersioned.version();
                            Cache.Receiver<T>[] strats = receivers.getSnapshot();
                            final int lateLength = strats.length;
                            if (lateLength == 0) return true; // end
                            if (versions < cache.getAsInt()) return false; //retry;

                            for (int i = 1; i < lateLength; i++) {
                                strats[i].accept(lateVersioned);
                            }

                            receivers.clearSnapshot(strats);

                            return versions == cache.getAsInt();
                        }
                ) : null;
                this.optional_dispatcher = !concurrent ?
                        () -> {
                            Versioned<T> versioned = cache.get();
                            Cache.Receiver<T>[] receivers = getSubscriberStrategies();
                            int rl = receivers.length;
                            for (int i = 0; i < rl; i++) {
                                receivers[i].accept(versioned);
                            }
                        }
                        :
                        () -> {
                            Cache.Receiver<T>[] subs = receivers.takePlainSnapshot();
                            int length = subs.length;
                            if (length > 0) {
                                if (length > 1) executor.execute();
                                subs[0].accept(cache.get());
                            }
                        }
                ;
            }

            private boolean defaultActivate() {
                Cache.Receiver<T>[] prevs = receivers.addAll(getDefArr());
                assert prevs == null : "Should've been NULL!!!... = " + Arrays.toString(prevs) +
                        ",\n defArrs = " + Arrays.toString(getDefArr());
                return true;
            }


            private boolean defaultDeactivate() {
                receivers.removeAll200();
                return true;
            }

            /**@return true if this is the first item to be added*/
            boolean addReceiver(Cache.Receiver<T> receiver) {
                assert receiver != null : " Receiver cannot be null";
                assert !receivers.contains(receiver) : "receiver " + receiver + " already contained in: " + Arrays.toString(getSubscriberStrategies());
                boolean isFirst = receivers.add(receiver) == 0;
                if (isFirst) {
                    this.dispatcher = optional_dispatcher;
                }
                return isFirst;
            }

            /**
             * @see CopyOnWriteArray#add(Object, BooleanSupplier)
             * */
            int addReceiver(Cache.Receiver<T> receiver, BooleanSupplier allow,
                            BooleanSupplier onSet) {
                assert receiver != null : " Receiver cannot be null";
                assert !receivers.contains(receiver) : "receiver " + receiver + " already contained in: " + Arrays.toString(getSubscriberStrategies());
                int index = receivers.add(receiver, allow);

                boolean greater = index > -1;
                if (greater && onSet.getAsBoolean()) {
                    if (index == 0) {
                        this.dispatcher = optional_dispatcher;
                    }
                    return index;
                } else if (greater) {
//                ABA Problem here...
//                int removedIndex = strategies.contentiousRemove_TEST(receiver);
//                if (removedIndex == 0) {
//                  if the array changes between add and remove... and if... the positions moves to be the last (i == 0)
//                  then the receiver will not be removed.
//                    Printer.out.print(Printer.green, TAG, "It will fail... true index = " + index);
//                }
                    receivers.fastContentiousRemove(receiver);
                }
                return -1;
            }

            boolean contains(Cache.Receiver<T> receiver) { return receivers.contains(receiver); }

            boolean hardRemove30Throw(Cache.Receiver<T> receiver) {
                boolean wasLast = receivers.hardRemove30Throw(receiver);
                if (wasLast) {
                    this.dispatcher = Lambdas.emptyRunnable();
                }
                return wasLast;
            }

            /**@return true if this is the last receiver to be removed, this method is non-contentious, and it will try ONCE and not throw*/
            boolean nonContRemove(Cache.Receiver<T> strategy) {
                boolean wasLast = receivers.fastNonContRemove(strategy) == 0;
                if (wasLast) {
                    this.dispatcher = Lambdas.emptyRunnable();
                }
                return wasLast;
            }

            private Cache.Receiver<T>[] getSubscriberStrategies() { return receivers.get(); }

            @Override
            public boolean isActive() { return !receivers.isEmptyOpaque(); }


            @Override
            public String toString() {
                return "ReceiversManager{" +
                        "\n receivers = " + receivers +
                        "\n def = " + Arrays.toString(defaultReceivers) +
                        "}ReceiversManager@" + hashCode() ;
            }
        }

        private String pathAttrs(){
            String type = Impl.this.parentPropagator.isMapped() ? "map" : "input";
            return ",\n type=" + type;
        }

        @Override
        public String toString() {
            return "Path@" + hashCode() + "{" +
                    "\n    ** ||>>> cache=" + cache +
                    pathAttrs() +
                    ",\n   ** ||>>> isActive? =" + isActive() +
                    "\n }";
        }

        @Override
        public String toStringDetailed() {
            return "<Path@" + hashCode() + "{" +
                    "\n    ** ||>>> cache=" + cache.toStringDetailed() +
                    ",\n   ** ||>>> strategiesManager=" + receiversManager +
                    pathAttrs() +
                    ",\n   ** ||>>> isActive? =" + isActive() +
                    "\n }Path@" + hashCode() + "/>";
        }

        public static class Arr<T>
                extends Impl<T[]> {
            Arr(BinaryPredicate<T[]> cacheTest, T[] initialValue, Predicate<T[]> excludeOut) {
                super(cacheTest,
                        Versioned.first(initialValue),
                        excludeOut);
            }

            private static final ArrPredicate<Object> globalEqual = Arrays::equals;

            interface ArrPredicate<T> extends BinaryPredicate<T[]> {}

            @SuppressWarnings("unchecked")
            static<S> ArrPredicate<S> getEquals() { return (ArrPredicate<S>) globalEqual; }
            public static final class Params<R> {
                final IntFunction<R[]> collector;
                final R[] EMPTY;

                private static final String error = "Lambda must not capture any reference and be a pure Constructor reference: e.g.: \"MyType[]::new\"";

                Params(IntFunction<R[]> collector, R[] empty
                ) {
                    this.collector = collector;
                    isAtLeastNonCapturingLambda(collector);
                    EMPTY = empty;
                    assert EMPTY.length == 0: error;
                }

                private static void isAtLeastNonCapturingLambda(IntFunction<?> onSwapped) {
                    try {
                        Class<?> c = onSwapped.getClass();
                        assert
                                c.isSynthetic()
                                        && c.getDeclaredFields().length == 0
                                        && !c.getDeclaredMethod("apply", int.class).isSynthetic();
                    } catch (NoSuchMethodException | Error e) {
                        throw new IllegalStateException(error, e);
                    }
                }

                static final Map<Class<Object[]>, Params<?>> paramsSet = new ConcurrentHashMap<>();

                /**
                 * For better performance an instance should be initialized outside the arrMap operation. <p>
                 * The same instance can be used for multiple mapping operations
                 * */
                @SuppressWarnings("unchecked")
                public static<T> Params<T> get(IntFunction<T[]> collector) {
                    T[] empty = collector.apply(0);
                    return (Params<T>) paramsSet.computeIfAbsent((Class<Object[]>) empty.getClass(), aClass -> new Params<>(
                            collector,
                            empty
                    ));
                }

                <S> Function<S[], R[]> getMap(Function<S, R> map) {
                    return original -> {
                        assert original != null;
                        int length = original.length;
                        if (length == 0) return EMPTY;
                        R[] res = collector.apply(length);
                        assert res.length == length : error;
                        for (int i = length - 1; i >= 0; i--) {
                            res[i] = map.apply(original[i]);
                        }
                        return res;
                    };
                }
            }

            <P> Arr(Impl<P> parent, Function<P, T[]> map, BinaryPredicate<T[]> excludeIn, Predicate<T[]> excludeOut) {
                super(parent, map, excludeIn, excludeOut);
            }

            public <S> Arr<S> arrMap(Params<S> params, Function<T, S> map, Predicate<S[]> excludeOut) {
                final Function<T[], S[]> fun = params.getMap(map);
                return new Arr<>(
                        this, fun, getEquals(), excludeOut
                );
            }

            @SuppressWarnings("unused")
            public <S> Arr<S> arrMap(Function<T[], S[]> map, Predicate<S[]> excludeOut) {
                return new Arr<>(this, map, getEquals(), excludeOut);
            }
        }

        /**
         * A Path with a non-final swappable {@link Activators.Shuttable}.
         * It will call a false immediately after detachment or Path deactivation, and it will call a true if the Path was
         * active during the observer attachment.
         * */
        static class SwappableActivator<T, P extends Activators.BinaryState<T>> extends Impl<T> {
            final Activators.SysRegister sysRegister = new Activators.SysRegister();

            SwappableActivator() { this(Lambdas.BinaryPredicates.defaultFalse(), Lambdas.Predicates.defaultFalse()); }

            SwappableActivator(
                    Consumer<Path.Builder<T>> builder) {
                this(Path.Builder.getNew(builder));
            }

            SwappableActivator(
                    Path.Builder<T> builder) {
                super(builder, null);
            }

            SwappableActivator(
                    T initialValue, Path.Builder<T> builder) {
                super(initialValue, builder);
            }

            SwappableActivator(
                    BinaryPredicate<T> excludeIn, Predicate<T> excludeOut) {
                super(excludeIn,
                        Versioned.getDefault(),
                        excludeOut);
            }

            SwappableActivator(
                    BinaryPredicate<T> cacheTest,
                    T initialValue
            ) {
                super(cacheTest, initialValue == null ? Versioned.getDefault() : Versioned.first(initialValue), Predicates.defaultFalse());
            }


            /**
             * Must shut Off downstream
             * */
            <B extends Activators.BinaryState<?>>boolean unbindActivator(Activators.GenericShuttableActivator<?, B> expect) {
                return sysRegister.unregister(expect);
            }

            public boolean isBound() { return sysRegister.isRegistered(); }
        }

        public static class ObservableState<T>
                extends SwappableActivator<T, Activators.BinaryState.Listenable<T>> {
            private final AtomicInteger counter = new AtomicInteger();
            private final IntSupplier liveCount = counter::get;

            protected ObservableState() { super(Path.Builder.getDefault()); }

            protected ObservableState(BinaryPredicate<T> cacheTest, T initialValue) { super(cacheTest, initialValue); }

            protected ObservableState(BinaryPredicate<T> excludeIn, Predicate<T> excludeOut) {
                super(excludeIn, excludeOut);
            }

            protected ObservableState(BinaryPredicate<T> excludeIn) {
                super(
                        excludeIn, Predicates.defaultFalse());
            }

            protected<O extends Predicates.OfBoolean.Consumer> boolean setListener(O listener) {
                Activators.GenericShuttableActivator<Object, Activators.BinaryState<Object>> next =
                        Activators.GenericShuttableActivator.build(listener);
                sysRegister.register(
                        counter.incrementAndGet(), liveCount,
                        next
                );
                return next == sysRegister.getActivator();
            }

            protected Predicates.OfBoolean removeListener() {
                Activators.GenericShuttableActivator<?, ? extends Activators.BinaryState<?>> res = sysRegister.unregister();
                if (res.state instanceof Activators.BinaryState.Listenable sh) {
                    return sh.getListener();
                } else return null;
            }

            protected boolean removeListener(Predicates.OfBoolean.Consumer listener) {
                return sysRegister.unregister(
                        binaryState -> {
                            if (binaryState instanceof Activators.BinaryState.Listenable l) {
                                return l.equalTo(listener);
                            } else return false;
                        }
                );
            }
            /**@return true - if the Activator was successfully set
             *          false - if the Activator failed to be set.
             *          An Activator will fail to be set if a newer concurrent activation is achieved.*/
            <S extends Activators.GenericShuttableActivator<Object, Activators.BinaryState.Listenable<Object>>>
            boolean set(
                    int version
                    , IntSupplier liveVersion
                    , S next
            ) {
                sysRegister.register(
                        version, liveVersion,
                        next
                );
                return next == sysRegister.getActivator();
            }

            @Override
            boolean sysActivate() { return sysRegister.activate(); }

            @Override
            void sysDeactivate() { sysRegister.deactivate(); }
        }

        static class SwappableActionablePath<T>
                extends SwappableActivator<T,
                Activators.PathedBinaryState<?, T>
                > {

            SwappableActionablePath() {
                this(
                        Path.Builder.getDefault()
                );
            }

            SwappableActionablePath(Path.Builder<T> builder) { super(builder); }

            SwappableActionablePath(BinaryPredicate<T> excludeIn, Predicate<T> excludeOut) {
                super(excludeIn, excludeOut);
            }

            SwappableActionablePath(
                    T initialValue
                    , Path.Builder<T> builder
            ) {
                super(initialValue, builder);
            }

            Activators.GenericShuttableActivator<T,
                    Activators.PathedBinaryState<?, T>
                    >
            set(
                    int version
                    , IntSupplier volatileCheck
                    , Path<T> path
            ) {
                return sysRegister.register(
                        version
                        , volatileCheck
                        , new Activators.GenericShuttableActivator<>(
                                Activators.PathedBinaryState.get(Activators.GenericShuttableActivator.INIT,
                                        path, cache.hierarchicalIdentity()
                                )
                        )
                );
            }
        }
    }
}