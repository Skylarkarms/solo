package solo;

import com.skylarkarms.print.Print;
import com.skylarkarms.solo.In;
import com.skylarkarms.solo.Path;
import com.skylarkarms.solo.Settings;

public class SwitchTest2 {
    public static void main(String[] args) {
        Settings.setDebug_mode(true);

        Model m = new Model();
        m.integerSource.accept(4);
    }

    static class Model {
        In.Consume<Integer> integerSource = new In.Consume<>(3);
        Path<String> stringPath = integerSource.switchMap(
                integer -> {
                    if (integer == 3) return Path.getDummy();
                    else return null;
                }
        );

        {
            stringPath.add(
                    Print.purple::print
            );
        }
    }
}
