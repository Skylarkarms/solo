/**
 * Single State Lock-free Reactive Framework.
 *
 * <p>Dependencies:</p>
 * <ul>
 *     <li>io.github.skylarkarms:lambdas:1.0.3</li>
 *     <li>io.github.skylarkarms:concur:1.0.6</li>
 * </ul>
 * <h3>Components:</h3>
 * <ul>
 *   <li>{@link com.skylarkarms.solo.Path} - An intermediate state between entry points and final output.</li>
 *   <li>{@link com.skylarkarms.solo.In} - Defines the entry point of the Singular reactive system.</li>
 *   <li>{@link com.skylarkarms.solo.Join} - A join...</li>
 *   <li>{@link com.skylarkarms.solo.Link} - Allows the creation of detached reactive branches.</li>
 *   <li>{@link com.skylarkarms.solo.Model} - Facilitate a structured separation of concerns.</li>
 *   <li>{@link com.skylarkarms.solo.ModelStore} - Eager and Lazy Storage of Models</li>
 *   <li>{@link com.skylarkarms.solo.Ref} - Object referent that allows the lazy or eager assignment of Paths</li>
 *   <li>{@link com.skylarkarms.solo.Settings} - Settings for the entire reactive system.</li>
 *   <li>{@link com.skylarkarms.solo.Tree} - Implementation of solo on a by-key basis</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * import com.skylarkarms.solo.*;
 * import java.util.function.Consumer;
 *
 * public class Example2 {
 *
 *     public static void main(String[] args) {
 *         Consumer<String> obs = System.out::println;
 *         Settings.load(
 *                 ModelStore.Singleton.Entry.get(
 *                         LiveModelA.class, LiveModelA::new
 *                 )
 *                 , ModelStore.Singleton.Entry.get(
 *                         LiveModelB.class, LiveModelB::new
 *                 )
 *         );
 *         LiveModelB.lazyRef_0.add(obs);
 *         Model.Live.get(LiveModelA.class).source.accept(4);
 *         Model.Live.get(LiveModelB.class).res.remove(obs);
 *         assert !LiveModelB.lazyRef_0.isActive();
 *
 *         Settings.shutDowNow();
 *     }
 *
 *     static class LiveModelA extends Model.Live {
 *         static Ref.Lazy<String> lazyRef = new Ref.Lazy<>(LiveModelA.class, modelA -> modelA.toString);
 *
 *         private static final String TAG = "ModelA";
 *         static {
 *             System.out.println("Building " + LiveModelA.TAG);
 *         }
 *         final In.Consume<Integer> source = new In.Consume<>(13);
 *         final Path<Integer> sourceMapped = source.map(integer -> integer * 5);
 *         final Path<String> toString = sourceMapped.map(String::valueOf);
 *
 *
 *     }
 *
 *     static class LiveModelB extends Model.Live {
 *         private static final String TAG = "ModelB";
 *         static Ref.Lazy<String> lazyRef_0 = new Ref.Lazy<>(LiveModelB.class, LiveModelB::getRes);
 *
 *         static {
 *             System.out.println("Building " + LiveModelB.TAG);
 *         }
 *         final Path<String> refMapped = LiveModelA.lazyRef.map(s -> s.concat(" MIXED!!!"));
 *         final Path<String> res = refMapped.map(s -> s.concat("===> FINISHED!!!"));
 *
 *         Path<String> getRes() { return res; }
 *     }
 * }
 * }</pre>
 *
 * @since 1.0.0
 * @author Andrew Andrade
 */
package com.skylarkarms.solo;
