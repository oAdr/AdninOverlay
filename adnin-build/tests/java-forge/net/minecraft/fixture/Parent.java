package net.minecraft.fixture;
public class Parent {
    private int field_number=17;
    public int func_number(int value) { return value+field_number; }
    public int[] func_arrays(Child[] values) { return new int[]{values.length}; }
}
