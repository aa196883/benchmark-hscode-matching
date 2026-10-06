package com.semsoft.lestr.tradeanalysis.domain.model;

public enum HSVersion {
    V_2017("2017"),
    V_2022("2022");

    private final String name;

    HSVersion(String name) {
        this.name = name;
    }

    public static HSVersion fromString(String name) {
        for (HSVersion value : values()) {
            if (value.name.equals(name)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Undefined version with name " + name);
    }

    @Override
    public String toString() {
        return name;
    }
}
