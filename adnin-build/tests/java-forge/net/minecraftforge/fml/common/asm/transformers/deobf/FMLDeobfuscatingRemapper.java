package net.minecraftforge.fml.common.asm.transformers.deobf;
public final class FMLDeobfuscatingRemapper {
    public static final FMLDeobfuscatingRemapper INSTANCE=new FMLDeobfuscatingRemapper();
    public String map(String name) {
        if ("p".equals(name)) return "net/minecraft/fixture/Parent";
        if ("q".equals(name)) return "net/minecraft/fixture/Child";
        return name;
    }
    public String unmap(String name) {
        if ("net/minecraft/fixture/Parent".equals(name)) return "p";
        if ("net/minecraft/fixture/Child".equals(name)) return "q";
        return name;
    }
    public String mapDesc(String desc) { return desc.replace("Lp;","Lnet/minecraft/fixture/Parent;").replace("Lq;","Lnet/minecraft/fixture/Child;"); }
    public String mapMethodName(String owner,String name,String desc) {
        if ("p".equals(owner) && "a".equals(name)) return "(I)I".equals(desc) ? "func_number" : "func_arrays";
        if ("q".equals(owner) && "b".equals(name)) return "func_static";
        return name;
    }
    public String mapFieldName(String owner,String name,String desc) {
        if ("p".equals(owner) && "x".equals(name)) return "field_number";
        if ("q".equals(owner) && "s".equals(name)) return "field_static";
        return name;
    }
}
