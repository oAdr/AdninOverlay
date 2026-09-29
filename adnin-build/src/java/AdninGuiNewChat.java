/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  ave
 *  avt
 *  eu
 */
import java.util.List;

public class AdninGuiNewChat
extends avt {
    public final avt delegate;

    public AdninGuiNewChat(ave ave2, avt avt2) {
        super(ave2);
        this.delegate = avt2;
    }

    public void a(eu eu2) {
        if (eu2 != null && AdninGuiNewChat.nativeShouldSuppressIncomingChat(eu2)) {
            AdninGuiNewChat.nativeOnIncomingChat(eu2);
            return;
        }
        this.delegate.a(eu2);
        if (eu2 != null) {
            AdninGuiNewChat.nativeOnIncomingChat(eu2);
        }
    }

    public void a(int n) {
        this.delegate.a(n);
    }

    public void a(eu eu2, int n) {
        this.delegate.a(eu2, n);
    }

    public void a(String string) {
        this.delegate.a(string);
    }

    public eu a(int n, int n2) {
        return this.delegate.a(n, n2);
    }

    public void b() {
        this.delegate.b();
    }

    public void b(int n) {
        this.delegate.b(n);
    }

    public List c() {
        return this.delegate.c();
    }

    public void d() {
        this.delegate.d();
    }

    public boolean e() {
        return this.delegate.e();
    }

    public int f() {
        return this.delegate.f();
    }

    public int g() {
        return this.delegate.g();
    }

    public float h() {
        return this.delegate.h();
    }

    public void i() {
        this.delegate.i();
    }

    public void j(int n) {
        this.delegate.j(n);
    }

    public int k() {
        return this.delegate.k();
    }

    private static native void nativeOnIncomingChat(Object var0);

    private static native boolean nativeShouldSuppressIncomingChat(Object var0);
}
