package com.gatto.wise.braker;

@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}
