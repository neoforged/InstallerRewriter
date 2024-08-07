package net.neoforged.installerrewriter;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    default boolean exists(String version) throws IOException {
        return false;
    }

    default void backup(String version) throws IOException {

    }

    default URL resolveUrl(String version) throws MalformedURLException {
        return null;
    }

    default void updateChecksums(URL url) throws Exception {

    }

    void save(@Nullable Installer installer);

    static InstallerProvider fromMaven(URI url, String user, String token, String artifactPath, @Nullable Path backup) {
        var splitPath = artifactPath.split(":");
        var baseName = splitPath[1];
        var artifactFolder = splitPath[0].replace('.', '/') + "/" + baseName;
        return new InstallerProvider() {
            @Override
            public List<String> listVersions(@Nullable String filter) throws IOException {
                var res = Utils.getLatestFromMavenMetadata(url.resolve(artifactFolder + "/maven-metadata.xml"));
                return filter == null ? res : res.stream().filter(str -> str.startsWith(filter)).toList();
            }

            @Override
            public CompletableFuture<Installer> provideInstaller(String version, Executor executor) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        var conn = (HttpURLConnection) url.resolve(artifactFolder + "/" + version + "/" + baseName + "-" + version + "-installer.jar").toURL().openConnection();
                        conn.connect();
                        if (conn.getResponseCode() == 404) return null;
                        var path = Files.createTempFile(baseName + "-" + version + "-installer", ".jar");
                        Files.deleteIfExists(path);
                        Files.copy(conn.getInputStream(), path);
                        conn.getInputStream().close();
                        var ins = new Installer(artifactFolder + "/" + baseName + "-" + version + "-installer.jar", version, JarContents.loadJar(path.toFile()));
                        Files.delete(path);
                        return ins;
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }, executor);
            }

            @Override
            public URL resolveUrl(String version) throws MalformedURLException {
                return url.resolve(artifactFolder + "/" + version + "/" + baseName + "-" + version + "-installer.jar").toURL();
            }

            @Override
            public void updateChecksums(URL url) throws Exception {
                var conn = (HttpURLConnection) url.openConnection();
                conn.connect();
                if (conn.getResponseCode() != 200) return;

                byte[] bytes;
                try (var is = conn.getInputStream()) {
                    bytes = is.readAllBytes();
                }
                for (var entry : HASHERS.entrySet()) {
                    var uri = URI.create(url + "." + entry.getKey());
                    write(uri, entry.getValue().hashBytes(bytes).toString().getBytes(StandardCharsets.UTF_8), false);
                }
            }

            @Override
            public boolean exists(String version) throws IOException {
                var conn = (HttpURLConnection) resolveUrl(version).openConnection();
                conn.setRequestMethod("HEAD");
                conn.connect();
                return conn.getResponseCode() == 200;
            }

            private final HttpClient client = HttpClient.newBuilder()
                    .authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(user, token.toCharArray());
                        }
                    }).build();

            private void write(URI uri, byte[] content, boolean genChecksum) throws Exception {
                final Runnable delete = () -> {
                    try {
                        var conn = (HttpURLConnection) uri.toURL().openConnection();
                        conn.setRequestMethod("HEAD");
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            var res = client.send(HttpRequest.newBuilder()
                                    .uri(uri)
                                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                            Rewriter.LOG.info("Deleted from " + res.uri() + ": " + res.statusCode());
                        }
                    } catch (Exception exception) {

                    }
                };

                int statusCode;
                while ((statusCode = client.send(HttpRequest.newBuilder()
                        .uri(uri)
                        .header("X-Generate-Checksums", Boolean.toString(genChecksum))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()) != 200) {
                    delete.run();
                }
                Rewriter.LOG.info("Uploaded to " + uri + ": " + statusCode);
            }

            @Override
            public void backup(String version) throws IOException {
                final var path = url.resolve(artifactFolder + "/" + version + "/" + baseName + "-" + version + "-installer.jar");
                if (backup != null) {
                    var bpath = backup.resolve(artifactFolder).resolve(version).resolve(baseName + "-" + version + "-installer.jar");
                    Files.createDirectories(bpath.getParent());
                    var conn = (HttpURLConnection) path.toURL().openConnection();
                    conn.connect();
                    if (conn.getResponseCode() == 404) return;
                    try (final var stream = conn.getInputStream()) {
                        Files.copy(stream, bpath);
                    }
                }
            }

            @Override
            public void save(Installer installer) {
                if (installer == null) return;

                try {
                    final var path = url.resolve(artifactFolder + "/" + installer.version() + "/" + baseName + "-" + installer.version() + "-installer.jar");
                    backup(installer.version());

                    var tempPath = Files.createTempFile(baseName + "-" + installer.version() + "-installer", ".jar");
                    Files.deleteIfExists(tempPath);
                    installer.jar().save(tempPath.toFile());
                    write(path, Files.readAllBytes(tempPath), true);
                    Rewriter.LOG.debug("Saved to {}", tempPath.toFile());
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
