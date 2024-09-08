package com.skylarkarms.solo;


import com.skylarkarms.lambdas.Predicates;

/**
 * Listen for state changes within the {@link Path.Impl.ObservableState}.
 * <p> Can be attached via Path.Impl.ObservableState#setListener(Object)
 * <p> Only one listener can be set at any given time.
 * */
public final class ActivationListeners
{

    static Predicates.OfBoolean.Consumer synchronize(Object lock, Predicates.OfBoolean.Consumer listener) {
        return isActive -> {
            synchronized (lock) {
                listener.accept(isActive);
            }
        };
    }

    static Predicates.OfBoolean.Consumer synchronize(Predicates.OfBoolean.Consumer listener) {
        final Object lock = new Object();
        return synchronize(lock, listener);
    }

    public static Predicates.OfBoolean.Consumer from(Activators.Activator activator) {
        return aBoolean -> {
            if (aBoolean) activator.activate();
            else activator.deactivate();
        };
    }
}
