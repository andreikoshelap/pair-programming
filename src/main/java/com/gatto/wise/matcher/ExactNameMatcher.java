package com.gatto.wise.matcher;

import static com.gatto.wise.matcher.NameUtils.normalize;

public class ExactNameMatcher implements NameMatcher {

    @Override
    public double similarity(String customerName, String listedName) {
        return normalize(customerName).equals(normalize(listedName)) ? 1.0 : 0.0;
    }
}
