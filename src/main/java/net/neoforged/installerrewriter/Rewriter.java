package net.neoforged.installerrewriter;

import joptsimple.OptionParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class Rewriter {
    public static final Logger LOG = LogManager.getLogger();

    public Rewriter(List<InstallerRewrite> rewrites) {
        this.rewrites = rewrites;
    }

    public static void main(String[] args) throws Exception {
        final var cmd = new OptionParser();
        final var versionFilter = cmd.accepts("filter", "A version filter").withRequiredArg();
        final var rewriteMaven = cmd.accepts("rewrite-maven", "If true, the artifacts will be rewritten on maven").withRequiredArg().ofType(URI.class);
        final var mavenUser = cmd.accepts("maven-user").requiredIf(rewriteMaven).withRequiredArg().ofType(String.class);
        final var mavenToken = cmd.accepts("maven-token").requiredIf(rewriteMaven).withRequiredArg().ofType(String.class);
        final var mavenPath = cmd.accepts("maven-path", "The path of the installer artifacts").requiredIf(rewriteMaven).withRequiredArg().ofType(String.class);
        final var rewriteDir = cmd.accepts("rewrite-dir", "Sets a directory to rewrite artifacts in").withRequiredArg().ofType(File.class);
        final var backupPath = cmd.accepts("backup", "Sets the backup directory").withRequiredArg().ofType(File.class);

        final var newVersionUpdate = cmd.accepts("installer-version-update", "Update to the latest installer version");
        final var threadLimit = cmd.accepts("thread-limit", "The maximum amount of threads the rewriter can use").withRequiredArg().ofType(Integer.class);

        final var options = cmd.parse(args);
        InstallerProvider provider;
        if (options.has(rewriteDir)) {
            provider = InstallerProvider.fromDir(options.valueOf(rewriteDir).toPath(), options.has(backupPath) ? options.valueOf(backupPath).toPath() : null);
        } else if (options.has(rewriteMaven)) {
            provider = InstallerProvider.fromMaven(options.valueOf(rewriteMaven), options.valueOf(mavenUser), options.valueOf(mavenToken), options.valueOf(mavenPath), options.has(backupPath) ? options.valueOf(backupPath).toPath() : null);
        } else {
            throw new RuntimeException("No provider found");
        }

        final List<InstallerRewrite> rewrites = new ArrayList<>();
        if (options.has(newVersionUpdate)) {
            final var latestVersion = Utils.getURL("https://maven.neoforged.net/api/maven/latest/version/releases/net%2Fneoforged%2Flegacyinstaller?filter=3.&type=json").get("version").getAsString();
            final var latestPath = Path.of("installer-" + latestVersion + ".jar");
            Utils.download("https://maven.neoforged.net/releases/net/neoforged/legacyinstaller/%s/legacyinstaller-%s-shrunk.jar".formatted(latestVersion, latestVersion), latestPath);
            rewrites.add(new NewVersionUpdate(JarContents.loadJar(latestPath.toFile())));
        }

        new Rewriter(rewrites).run(provider, provider.listVersions(options.has(versionFilter) ? options.valueOf(versionFilter) : null), options.has(threadLimit) ? options.valueOf(threadLimit) : null);
    }

    private final List<InstallerRewrite> rewrites;

    public void run(InstallerProvider provider, List<String> versions, @Nullable Integer limit) throws Exception {
        LOG.info("Found {} versions to rewrite.", versions.size());
        LOG.info("Versions: {}", versions.size());

        final var cfs = new ArrayList<CompletableFuture<?>>();
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
        for (final String version : versions) {
            cfs.add(provider.provideInstaller(version, exec)
                    .thenApply(this::proc)
                    .thenAccept(provider::save));
        }
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
        }
        boolean rewritten = installer.jar().changed();
        LOG.info("Processed {}", installer.version() + (rewritten ? "" : ". Skipped."));
        return rewritten ? installer : null;
    }
}
