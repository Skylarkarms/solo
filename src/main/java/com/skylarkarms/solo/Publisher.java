package com.skylarkarms.solo;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

public interface Publisher<T> extends Activators.State {
    /**IF this method is used directly in the Path class, it will create
     * a Path with the default parameters defined at Settings*/
    void add(Consumer<? super T> subscriber);
    void remove(Consumer<? super T> subscriber);
    Consumer<? super T> remove(Object subscriber);
    boolean contains(Consumer<? super T> subscriber);

    @FunctionalInterface
    interface Builder<T> {

        /**
         * The delivery will be performed sequentially to all {@link Consumer}s
         * if null is used as {@link Executor}
         * */
        Publisher<T> getPublisher(Executor executor);

        /**Will default to system default {@link Settings#getExit_executor()} if true, null if false*/
        default Publisher<T> getPublisher(boolean contentious) {
            return getPublisher(contentious ? Settings.getExit_executor() : null);
        }

        /**Will default to system {@link Settings#concurrent} settings*/
        default Publisher<T> getPublisher() {
            return getPublisher(Settings.concurrent);
        }
    }
}
