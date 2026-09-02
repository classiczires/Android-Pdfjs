package com.zires.pdfjs;

public enum ScrollMode  {
    VERTICAL(0),
    HORIZONTAL(1),
    WRAPPED(2),
    PAGE(3);

    private final int value;

    ScrollMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
