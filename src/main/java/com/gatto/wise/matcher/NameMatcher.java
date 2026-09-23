package com.gatto.wise.matcher;

public interface NameMatcher {
    /** Returns similarity in [0.0, 1.0] for two already-normalized names. */
    double similarity(String customerName, String listedName);
}