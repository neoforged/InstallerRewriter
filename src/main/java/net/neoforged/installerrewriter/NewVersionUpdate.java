package net.neoforged.installerrewriter;

import java.io.IOException;
import java.util.Objects;

public record NewVersionUpdate(JarContents newVersion, String version) implements InstallerRewrite {
    public NewVersionUpdate(JarContents newVersion) throws IOException {
        this(newVersion, newVersion.getManifest().getMainAttributes().getValue("Implementation-Version"));
    }

    @Override
    public void rewrite(Installer installer) throws Exception {
        if (Objects.equals(installer.jar().getManifest().getMainAttributes().getValue("Implementation-Version"), version)) return;

        // Remove previous classes
        installer.jar().deleteFolder("net");
        installer.jar().deleteFolder("com");
        installer.jar().deleteFolder("joptsimple");
        installer.jar().deleteFolder("neoforged");
        installer.jar().deleteFolder("META-INF/maven");
        installer.jar().delete("META-INF/NEOFORGE.SF");
        installer.jar().delete("META-INF/NEOFORGE.RSA");

        installer.jar().merge(newVersion, true);
    }

    @Override
    public String name() {
        return "New version update";
    }

    @Override
    public void close() throws Exception {
    }
}
