package solo;

import com.skylarkarms.lambdas.BinaryPredicate;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import com.skylarkarms.solo.Settings;

public class SideEffectExcludeInExample {
    public static void main(String[] args) {
        In.Consume<MyTypes> typesSource = new In.Consume<>();
        Path<MyObject> myObjectPath = typesSource.map(
                MyTypes::map,
                (BinaryPredicate.Unary<MyObject>) MyObject::excludeOnDefault
        );

        myObjectPath.add(
                myObject -> {
                    myObject.assertNotC();
                    System.out.println(myObject);
                }
        );

        for (MyTypes t:MyTypes.values()
             ) {
            try {
                Thread.sleep(800);
                System.out.println("accepting... " + t);
                typesSource.accept(t);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Settings.shutdownNow();
    }

    record MyObject(int anInt){
        static final MyObject DEFAULT = new MyObject(-1);

        void assertNotC() { assert anInt != MyTypes.c.ordinal(); }

        boolean excludeOnDefault() { return this == DEFAULT; }
    }

    enum MyTypes {
        a, b, c, d;

        MyObject map() {
            if (this == c) return MyObject.DEFAULT;
            return new MyObject(ordinal());
        }
    }
}
