package net.neoforged.installerrewriter;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

// TODO - this class needs a bit of cleanup
public interface InstallerProvider {
    Map<String, HashFunction> HASHERS = Map.of(
            "md5", Hashing.md5(),
            "sha1", Hashing.sha1(),
            "sha256", Hashing.sha256(),
            "sha512", Hashing.sha512()
    );

    List<String> listVersions(@Nullable String filter) throws IOException;

    CompletableFuture<Installer> provideInstaller(String version, Executor executor);

    void save(@Nullable Installer installer);

    static InstallerProvider fromMaven(URI url, String user, String token, String artifactPath, @Nullable Path backup) {
        var splitPath = artifactPath.split(":");
        var baseName = splitPath[1];
        var artifactFolder = splitPath[0].replace('.', '/') + "/" + baseName;
        return new InstallerProvider() {
            @Override
            public List<String> listVersions(@Nullable String filter) throws IOException {
                var res = Utils.getLatestFromMavenMetadata(url.resolve("maven-metadata.xml"));
                return filter == null ? res : res.stream().filter(str -> str.startsWith(filter)).toList();
            }

            @Override
            public CompletableFuture<Installer> provideInstaller(String version, Executor executor) {
                return CompletableFuture.supplyAsync(() -> {
                    try (final var stream = url.resolve(version + "/" + baseName + "-" + version + "-installer.jar").toURL().openStream()) {
                        var path = Files.createTempFile(baseName + "-" + version + "-installer", ".jar");
                        Files.deleteIfExists(path);
                        Files.copy(stream, path);
                        var ins = new Installer(artifactFolder + "/" + baseName + "-" + version + "-installer.jar", version, JarContents.loadJar(path.toFile()));
                        Files.delete(path);
                        return ins;
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, executor);
            }

            private final HttpClient client = HttpClient.newBuilder()
                    .authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(user, token.toCharArray());
                        }
                    }).build();

            private void write(URI uri, byte[] content) throws Exception {
                try {
                   uri.toURL().openStream().close();
                   var res = client.send(HttpRequest.newBuilder()
                            .uri(uri)
                            .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                   Rewriter.LOG.debug("Deleted from " + res.uri() + ": " + res.statusCode());
                } catch (Exception exception) {

                }

                var res = client.send(HttpRequest.newBuilder()
                        .uri(uri)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.ofString());
                Rewriter.LOG.debug("Uploaded to " + res.uri() + ": " + res.statusCode());
            }

            @Override
            public void save(Installer installer) {
                if (installer == null) return;

                try {
                    final var path = url.resolve(installer.version() + "/" + baseName + "-" + installer.version() + "-installer.jar");
                    final var backupDir = backup == null ? null : backup.resolve(installer.version());
                    if (backupDir != null) {
                        var bpath = backupDir.resolve(installer.path());
                        Files.createDirectories(bpath.getParent());
                        try (final var stream = path.toURL().openStream()) {
                            Files.copy(stream, bpath);
                        }
                    }

                    var tempPath = Files.createTempFile(baseName + "-" + installer.version() + "-installer", ".jar");
                    Files.deleteIfExists(tempPath);
                    installer.jar().save(tempPath.toFile());
                    write(path, Files.readAllBytes(tempPath));
                    Rewriter.LOG.info("Saved to {}", tempPath.toFile());
                    final var bytes = Files.readAllBytes(tempPath);

                    final String name = baseName + "-" + installer.version() + "-installer";

                    for (var entry : HASHERS.entrySet()) {
                        var uri = url.resolve(installer.version() + "/" + name + "." + entry.getKey());
                        write(uri, entry.getValue().hashBytes(bytes).asBytes());
                    }

                    Files.delete(tempPath);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        };
    }

    static InstallerProvider fromDir(Path root, @Nullable Path backup) {
        return new InstallerProvider() {
            @Override
            public List<String> listVersions(@Nullable String filter) throws IOException {
                try (final var files = Files.find(root, Integer.MAX_VALUE, (path, basicFileAttributes) -> path.toString().endsWith("-installer.jar"))) {
                    return files.map(p -> p.getFileName().toString()).map(s -> s.replace("neoforge-", "").replace("forge-", "").replace("-installer.jar", "").replace("installer.jar", ""))
                            .filter(s -> filter == null || s.startsWith(filter))
                            .toList();
                }
            }

            @Override
            public CompletableFuture<Installer> provideInstaller(String version, Executor executor) {
                return CompletableFuture.supplyAsync(() -> {
                    try (final var files = Files.find(root, Integer.MAX_VALUE, (path, basicFileAttributes) -> path.toString().endsWith(version + "-installer.jar"))) {
                        final var path = files.findFirst().orElseThrow();
                        return new Installer(root.relativize(path).toString(), version, JarContents.loadJar(path.toFile()));
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, executor);
            }

            @Override
            public void save(Installer installer) {
                if (installer == null) return;

                try {
                    final var path = root.resolve(installer.path()).toAbsolutePath();
                    final var backupDir = backup == null ? null : backup.resolve(root.relativize(root.resolve(installer.path()).getParent()).toString());
                    if (backupDir != null) {
                        Files.createDirectories(backupDir);
                        Files.copy(path, backup.resolve(installer.path()));
                    }

                    Files.createDirectories(path.getParent());
                    installer.jar().save(path.toFile());
                    Rewriter.LOG.info("Saved to {}", path.toFile());
                    final var bytes = Files.readAllBytes(path);

                    final List<String> toRemove = new ArrayList<>();
                    final String name = path.getFileName().toString();
                    final Path parent = path.getParent();
                    for (final var suffix : List.of(
                           "md5", "sha1", "sha256", "sha512"
                    )) {
                        toRemove.add(name + "." + suffix);
                        toRemove.add(name + ".asc." + suffix);
                    }
                    toRemove.add(name + ".asc");

                    for (var trm : toRemove) {
                        final var p = parent.resolve(trm);
                        if (Files.exists(p)) {
                            if (backup != null) {
                                Files.copy(p, backupDir.resolve(trm));
                            }
                            Files.delete(p);
                        }
                    }

                    for (var entry : HASHERS.entrySet()) {
                        Files.writeString(parent.resolve(name + "." + entry.getKey()), entry.getValue().hashBytes(bytes).toString());
                    }
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        };
    }
}
