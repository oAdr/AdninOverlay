package net.minecraft.fixture;

public final class InitializationProbe {
    public static final class Found {
        static { System.setProperty("adnin.fixture.Found", "ready"); }
    }
    public static final class Argument {
        static { System.setProperty("adnin.fixture.Argument", "ready"); }
    }
    public static final class Methods {
        static { System.setProperty("adnin.fixture.Methods", "ready"); }
        public static Argument resolve(Argument value) { return value; }
    }
    public static final class Fields {
        static { System.setProperty("adnin.fixture.Fields", "ready"); }
        public static Argument value;
    }
}
