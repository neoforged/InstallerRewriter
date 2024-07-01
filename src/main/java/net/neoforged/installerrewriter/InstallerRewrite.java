package net.neoforged.installerrewriter;

public interface InstallerRewrite extends AutoCloseable {
    void rewrite(Installer installer) throws Exception;

    String name();
}
