# solo

[latest version] = `1.1.0`

[internal dependencies]: 
 - [`io.github.skylarkarms:lambdas:1.0.4`](https://github.com/Skylarkarms/Lambdas)
 - [`io.github.skylarkarms:concur:1.1.5`](https://github.com/Skylarkarms/Concur)

[test dependencies]:
 - [`io.github.skylarkarms:lambdaprofiler:1.1.0`](https://github.com/Skylarkarms/LambdaProfiler)
 - [`io.github.skylarkarms:print:1.0.8`](https://github.com/Skylarkarms/Print)

[found in]:
 - [`io.github.skylarkarms:requestpoolhttp:`](https://github.com/Skylarkarms/RequestPoolHTTP)

<p align="center">
  <img src="solo_logo.svg" width="300" height="300">
</p>

<p align="center">
Single State Lock-free Reactive Framework.
</p>

### Implementation
In your `build.gradle` file
```groovy
repositories {
   mavenCentral()
}

dependencies {
   implementation 'io.github.skylarkarms:solo:[latest version]'
}
```

or in your `POM.xml`
```xml
<dependencies>
   <dependency>
      <groupId>io.github.skylarkarms</groupId>
      <artifactId>solo</artifactId>
      <version>[latest version]</version>
   </dependency>
</dependencies>
```

### Example Usage

```java
import com.skylarkarms.solo.*;
import java.util.function.Consumer;

public class Example2 {

    public static void main(String[] args) {
        Consumer<String> obs = System.out::println;
        Settings.load(
                ModelStore.Singleton.Entry.get(
                        LiveModelA.class, LiveModelA::new
                )
                , ModelStore.Singleton.Entry.get(
                        LiveModelB.class, LiveModelB::new
                )
        );
        LiveModelB.lazyRef_0.add(obs);
        Model.Live.get(LiveModelA.class).source.accept(4);
        Model.Live.get(LiveModelB.class).res.remove(obs);
        assert !LiveModelB.lazyRef_0.isActive();

        Settings.shutDowNow();
    }

    static class LiveModelA extends Model.Live {
        static Ref.Lazy<String> lazyRef = new Ref.Lazy<>(LiveModelA.class, modelA -> modelA.toString);

        private static final String TAG = "ModelA";
        static {
            System.out.println("Building " + LiveModelA.TAG);
        }
        final In.Consume<Integer> source = new In.Consume<>(13);
        final Path<Integer> sourceMapped = source.map(integer -> integer * 5);
        final Path<String> toString = sourceMapped.map(String::valueOf);


    }

    static class LiveModelB extends Model.Live {
        private static final String TAG = "ModelB";
        static Ref.Lazy<String> lazyRef_0 = new Ref.Lazy<>(LiveModelB.class, LiveModelB::getRes);

        static {
            System.out.println("Building " + LiveModelB.TAG);
        }
        final Path<String> refMapped = LiveModelA.lazyRef.map(s -> s.concat(" MIXED!!!"));
        final Path<String> res = refMapped.map(s -> s.concat("===> FINISHED!!!"));

        Path<String> getRes() { return res; }
    }
}
```