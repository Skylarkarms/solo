package com.skylarkarms.solo;

/**
 * A callback that broadcasts the outcome of a Compare-and-Swap (CAS) operation.
 * <p>
 * This callback is invoked to indicate whether a CAS process was successful or not.
 * Regardless of the result, the response will include the previous witnessed value (prev)
 * and the value that was attempted to be set (next).
 * <p>
 * When the boolean 'success' is true, the 'next' value should be considered the current
 * actual state of the associated Cache.
 * <p>
 * A 'false' value indicates that the CAS process failed, and the 'next' value was not set.
 * If 'prev' is null, it signifies the FIRST swap to occur on this Cache instance.
 *
 * @param <T> The type of the values involved in the CAS operation.
 */
@FunctionalInterface
public interface OnSwapped<T> {
    /**
     * This method is called after a Compare-and-Swap operation to provide information
     * about the outcome of the operation.
     *
     * @param success Whether the CAS operation was successful.
     * @param prev    The previously witnessed value.
     * @param next    The value that was attempted to be set.
     */
    void attempt(boolean success, T prev, T next);

    record DEFAULT() {
        static final OnSwapped<?> ref = new OnSwapped<>() {
            @Override
            public void attempt(boolean success, Object prev, Object next) {}

            @Override
            public boolean isDefault() {
                return true;
            }

            @Override
            public String toString() {
                return "[DEFAULT]@".concat(Integer.toString(hashCode()));
            }
        };
    }


    default boolean isDefault() {
        return false;
    }

    @SuppressWarnings("unchecked")
    static<S> OnSwapped<S> getDefault() {
        return (OnSwapped<S>) DEFAULT.ref;
    }
}
