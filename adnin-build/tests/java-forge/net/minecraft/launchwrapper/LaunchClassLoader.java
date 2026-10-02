package net.minecraft.launchwrapper;

import java.net.URL;
import java.net.URLClassLoader;

public final class LaunchClassLoader extends URLClassLoader {
    public LaunchClassLoader(URL[] urls) {
        super(urls, ClassLoader.getSystemClassLoader());
    }
}
