package net.neoforged.installerrewriter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;
import picocli.CommandLine;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class Rewriter {
    public static final Logger LOG = LogManager.getLogger();

    public Rewriter(List<InstallerRewrite> rewrites) {
        this.rewrites = rewrites;
    }

    public static class Args {
        @CommandLine.Option(names = "--filter", description = "A version filter")
        public String filter;

        @CommandLine.Option(names = "--backup", description = "The directory to backup files to")
        public Path backupDir;

        @CommandLine.ArgGroup(multiplicity = "1")
        public Provider provider;

        public static class Provider {

            @CommandLine.ArgGroup(exclusive = false)
            public Maven maven;

            @CommandLine.ArgGroup(exclusive = false)
            public Directory directory;

            public static class Maven {
                @CommandLine.Option(names = "--maven-url", description = "The URL of the maven repository")
                public URI url;

                @CommandLine.Option(names = "--maven-user")
                public String user;

                @CommandLine.Option(names = "--maven-password")
                public String password;

                @CommandLine.Option(names = "--maven-path")
                public String mavenPath;
            }

            public static class Directory {
                @CommandLine.Option(names = "--rewrite-directory")
                public File dir;
            }
        }

        @CommandLine.Option(names = "--installer-version-update", description = "Update to the latest installer version", negatable = true)
        public boolean updateVersion;

        @CommandLine.Option(names = "--thread-limit", description = "The maximum amount of threads the rewriter can use")
        public int threadLimit = -1;

        @CommandLine.Option(names = "--dry")
        public boolean dry;
    }

    public static void main(String[] args) throws Exception {
        var arguments = new Args();
        new CommandLine(arguments).parseArgs(args);

        InstallerProvider provider;
        if (arguments.provider.directory != null) {
            provider = InstallerProvider.fromDir(arguments.provider.directory.dir.toPath(), arguments.backupDir);
        } else {
            var prov = arguments.provider.maven;
            provider = InstallerProvider.fromMaven(prov.url, prov.user, prov.password, prov.mavenPath, arguments.backupDir);
        }

        final List<InstallerRewrite> rewrites = new ArrayList<>();
        if (arguments.updateVersion) {
            final var latestVersion = Utils.getURL("https://maven.neoforged.net/api/maven/latest/version/releases/net%2Fneoforged%2Flegacyinstaller?filter=3.&type=json").get("version").getAsString();
            final var latestPath = Path.of("installer-" + latestVersion + ".jar");
            Utils.download("https://maven.neoforged.net/releases/net/neoforged/legacyinstaller/%s/legacyinstaller-%s-shrunk.jar".formatted(latestVersion, latestVersion), latestPath);
            rewrites.add(new NewVersionUpdate(JarContents.loadJar(latestPath.toFile())));
        }

        if (arguments.dry) {
            var versions = provider.listVersions(arguments.filter);
            LOG.info("Found {} versions to rewrite.", versions.size());
            LOG.info("Versions: {}", versions);
        } else {
            new Rewriter(rewrites).run(provider, provider.listVersions(arguments.filter), arguments.threadLimit > 0 ? arguments.threadLimit : null);
        }
    }

    private final List<InstallerRewrite> rewrites;

    public void run(InstallerProvider provider, List<String> versions, @Nullable Integer limit) throws Exception {
        LOG.info("Found {} versions to rewrite.", versions.size());
        LOG.info("Versions: {}", versions);

        final var cfs = new ArrayList<CompletableFuture<Installer>>();
        final Executor exec;
        final AutoCloseable closeableExec;

        var factory = Thread.ofVirtual()
                .uncaughtExceptionHandler((t, e) -> LOG.error("Failed to run rewriter: ", e))
                .name("installer-rewriter", 0).factory();
        if (limit == null) {
            closeableExec = (AutoCloseable) (exec = Executors.newThreadPerTaskExecutor(factory));
        } else {
            final var threaded = Executors.newThreadPerTaskExecutor(factory);
            closeableExec = threaded;
            final var semaphore = new Semaphore(limit);
            exec = command -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try {
                    threaded.execute(command);
                } finally {
                    semaphore.release();
                }
            };
        }

        // First process and provide all versions
        for (final String version : versions) {
            cfs.add(provider.provideInstaller(version, exec).thenApply(this::proc));
        }

        var installers = Utils.allOf(cfs).join().stream().filter(Objects::nonNull).toList();

        LOG.info("Versions to upload: {}", installers);

        // Then upload them
        cfs.clear();
        for (Installer installer : installers) {
            if (installer == null) continue;
            cfs.add(CompletableFuture.supplyAsync(() -> {
                provider.save(installer);
                return installer;
            }, exec));
        }

        // And wait for the upload to complete
        CompletableFuture.allOf(cfs.toArray(CompletableFuture[]::new)).join();

        closeableExec.close();
        for (InstallerRewrite rewrite : rewrites) {
            rewrite.close();
        }

        LOG.info("Finished rewriting!");
    }

    public Installer proc(Installer installer) {
        try {
            return process(installer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public Installer process(Installer installer) throws Exception {
        LOG.info("Processing installer {} ({}):", installer.version(), installer.path());
        for (var rewrite : rewrites) {
            LOG.info("Rewriting {} with {}", installer.version(), rewrite.name());
            rewrite.rewrite(installer);
        }
        boolean rewritten = installer.jar().changed();
        LOG.info("Processed {}", installer.version() + (rewritten ? "" : ". Skipped."));
        return rewritten ? installer : null;
    }
}
