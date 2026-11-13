package core.amazake.systems;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The class loader of one enable of one system: the .azs file, with the server's class loader as parent (so the
 * Bukkit/Paper API and the systems API resolve to the server's classes). A new one per enable; closed on disable,
 * after which nothing the core knows of refers to the system's classes any more.
 *
 * <p>Classes are defined with a protection domain that does not point back to this loader. A thread started while
 * a system's code is on the stack (a lazily started pool thread of the server, say) snapshots the protection domains
 * of that stack for its whole life; with the default domain that snapshot would keep the loader, and every class of
 * the system, alive after disable.</p>
 */
public final class SystemClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String systemName;
    private final JarFile jar;
    private final ProtectionDomain domain;

    SystemClassLoader(final String systemName, final Path azs, final ClassLoader parent) throws IOException {
        super("AmazakeSystem/" + systemName, new URL[]{azs.toUri().toURL()}, parent);
        this.systemName = systemName;
        this.jar = new JarFile(azs.toFile());
        this.domain = new ProtectionDomain(new CodeSource(azs.toUri().toURL(), (CodeSigner[]) null), null);
    }

    public String systemName() {
        return this.systemName;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final JarEntry entry = this.jar.getJarEntry(name.replace('.', '/') + ".class");
        if (entry == null) {
            throw new ClassNotFoundException(name);
        }
        final byte[] bytes;
        try (InputStream in = this.jar.getInputStream(entry)) {
            bytes = in.readAllBytes();
        } catch (final IOException | IllegalStateException e) {
            throw new ClassNotFoundException(name, e);
        }
        final int dot = name.lastIndexOf('.');
        if (dot > 0) {
            final String pkg = name.substring(0, dot);
            if (this.getDefinedPackage(pkg) == null) {
                try {
                    this.definePackage(pkg, null, null, null, null, null, null, null);
                } catch (final IllegalArgumentException alreadyDefined) {
                    // defined by another thread meanwhile
                }
            }
        }
        return this.defineClass(name, bytes, 0, bytes.length, this.domain);
    }

    /** A resource of the .azs itself (not of the server), or null. */
    public InputStream ownResource(final String path) throws IOException {
        final URL url = this.findResource(path.startsWith("/") ? path.substring(1) : path);
        if (url == null) {
            return null;
        }
        // No URL cache: the stream owns its own handle on the file, so nothing stays open after it is closed.
        final java.net.URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    @Override
    public void close() throws IOException {
        try {
            this.jar.close();
        } finally {
            super.close();
        }
    }

    @Override
    public String toString() {
        return "SystemClassLoader[" + this.systemName + "]";
    }
}
