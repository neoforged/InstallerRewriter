package net.neoforged.installerrewriter;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import net.covers1624.quack.util.HashUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class JarContents {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final long DEFAULT_TIME = 1337; // Some JREs don't support time of 0, so use this

    static final String MANIFEST = "META-INF/MANIFEST.MF";

    private static final HashFunction SHA256 = Hashing.sha256();


    static JarContents loadJar(File path) throws IOException {
        Map<String, byte[]> data = new HashMap<>();
        Map<String, Long> timestamps = new HashMap<>();
        if (!path.exists())
            return new JarContents(data, timestamps);


        try (ZipFile zf = new ZipFile(path)) {
            var enu = zf.entries();
            while (enu.hasMoreElements()) {
                ZipEntry ent = enu.nextElement();
                String name = ent.getName();
                timestamps.put(name, ent.getTime());

                if (ent.isDirectory())
                    continue;

                try (InputStream is = zf.getInputStream(ent)) {
                    data.put(name, is.readAllBytes());
                }
            }
        }


        return new JarContents(data, timestamps);

    }


    private final Map<String, byte[]> data;
    private final Map<String, Long> timestamps;
    private boolean changed = false;
    private Manifest manifest;

    private JarContents(Map<String, byte[]> data, Map<String, Long> timestamps) {
        this.data = data;
        this.timestamps = timestamps;
    }

    public Manifest getManifest() throws IOException {
        if (manifest == null) {
            try (var is = getInput(MANIFEST)) {
                manifest = new Manifest(is);
            }
        }
        return manifest;
    }


    static String sanitize(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }


    boolean changed() {
        return this.changed;
    }


    boolean contains(String name) {
        return this.data.containsKey(sanitize(name));
    }


    Set<String> getFiles() {

        return new HashSet<>(this.data.keySet());

    }


    InputStream getInput(String name) {
        byte[] d = this.data.get(sanitize(name));
        return d == null ? null : new ByteArrayInputStream(d);
    }

    String getText(String name) {
        byte[] d = this.data.get(sanitize(name));
        return d == null ? null : new String(d, StandardCharsets.UTF_8);
    }

    long getTime(String name) {
        Long time = this.timestamps.get(sanitize(name));
        return time == null ? DEFAULT_TIME : time;
    }


    void setTime(String name, long time) {
        long old = getTime(name);
        if (old != time) {
            this.timestamps.put(sanitize(name), time);
            if (!name.endsWith("/"))
                changed = true;
        }
    }


    byte[] delete(String name) {
        name = sanitize(name);
        if (contains(name))
            changed = true;
        this.timestamps.remove(name);
        return this.data.remove(name);
    }

    void deleteFolder(String name) {
        final var actualName = sanitize(name) + "/";
        data.keySet().stream()
                .filter(d -> d.startsWith(actualName))
                .toList()
                .forEach(this::delete);
    }

    void write(String name, byte[] data) {
        write(name, data, getTime(name));
    }


    void write(String name, byte[] data, long timestamp) {
        name = sanitize(name);
        this.data.put(name, data);
        this.timestamps.put(name, timestamp);
        changed = true;
    }


    void save(File target) throws IOException {
        if (changed())
            cleanSignatures();


        if (!target.getParentFile().exists())

            target.getParentFile().mkdirs();


        try (FileOutputStream fos = new FileOutputStream(target);

             JarOutputStream out = new JarOutputStream(fos)

        ) {

            List<String> files = new ArrayList<>(this.data.keySet());

            Collections.sort(files, (l, r) -> {

                if (l.equals(MANIFEST))

                    return r.equals(MANIFEST) ? 0 : -1;

                if (r.equals(MANIFEST))

                    return l.equals(MANIFEST) ? 0 : 1;

                return l.compareTo(r);

            });


            Set<String> dirs = new HashSet<String>();

            for (String file : files) {

                makeDirectory(out, dirs, file);

                ZipEntry entry = new ZipEntry(file);

                entry.setTime(getTime(file));

                out.putNextEntry(entry);

                out.write(this.data.get(file));

            }


            out.flush();

        }

    }


    private void makeDirectory(JarOutputStream out, Set<String> added, String path) throws IOException {

        if (added.contains(path))

            return;

        int idx = path.lastIndexOf('/');

        if (idx == path.length() - 1) {

            int sidx = path.lastIndexOf('/', idx - 1);

            if (sidx != -1)

                makeDirectory(out, added, path.substring(0, sidx + 1));


            ZipEntry entry = new ZipEntry(path);

            entry.setTime(getTime(path));

            out.putNextEntry(entry);

            added.add(path);

        } else if (idx != -1) {

            makeDirectory(out, added, path.substring(0, idx + 1));

        }

    }


    void merge(JarContents other, boolean overwrite) {
        for (String file : other.data.keySet()) {
            if (overwrite || !this.data.containsKey(file))
                write(file, other.data.get(file), other.getTime(file));
        }

        for (String file : other.timestamps.keySet()) {
            if (!file.endsWith("/"))
                continue;
            if (overwrite || !this.timestamps.containsKey(file))
                this.setTime(file, other.timestamps.get(file));
        }
    }


    boolean sameData(JarContents other, Set<String> whitelist) throws IOException {

        for (String file : getFiles()) {

            if (whitelist.contains(file))

                continue;


            if (!other.contains(file))

                return false;


            HashCode me = HashUtils.hash(SHA256, this.getInput(file));

            HashCode them = HashUtils.hash(SHA256, other.getInput(file));


            if (!me.equals(them))

                return false;

        }

        return true;

    }


    @SuppressWarnings("deprecation")

    private void cleanSignatures() throws IOException {

        boolean invalid = false;

        if (!contains(MANIFEST))

            return;


        Manifest mf = null;

        try (InputStream is = getInput(MANIFEST)) {

            mf = new Manifest(is);

        }


        for (var entry : mf.getEntries().entrySet()) {

            String name = entry.getKey().toString();


            for (String key : entry.getValue().keySet().stream().map(Object::toString).collect(Collectors.toList())) {

                if (key.endsWith("-Digest")) {

                    if (!contains(name)) // Hashes can exist in the manifest even if the files don't exist.

                        continue;


                    HashFunction func = null;

                    switch (key.substring(0, key.length() - "-Digest".length())) {

                        case "SHA-256":
                            func = Hashing.sha256();
                            break; // Only one i've seen, but might as well support he others.

                        case "SHA-1":
                            func = Hashing.sha1();
                            break;

                        case "SHA-512":
                            func = Hashing.sha512();
                            break;

                        case "MD5":
                            func = Hashing.md5();
                            break;

                        default:
                            throw new IOException("Unknown manifest signature format: " + key);

                    }


                    try (InputStream is = getInput(name)) {

                        String actual = HashUtils.hash(func, is).toString();

                        String expected = HashCode.fromBytes(Base64.getDecoder().decode(entry.getValue().getValue(key))).toString();

                        if (!expected.equals(actual)) {

                            LOGGER.info("Installer manifest hash mismatch, stripping signatures");

                            invalid = true;

                            break;

                        }

                    }

                }

            }


            if (invalid)

                break;

        }


        if (invalid) {

            // cleanup manifest

            var itr = mf.getEntries().entrySet().iterator();

            while (itr.hasNext()) {

                var entry = itr.next();

                Attributes attrs = entry.getValue();

                attrs.keySet().removeIf(e -> e.toString().endsWith("-Digest"));

                if (attrs.isEmpty())

                    itr.remove();

            }

            ByteArrayOutputStream os = new ByteArrayOutputStream();

            mf.write(os);

            os.flush();

            this.write(MANIFEST, os.toByteArray());


            List<String> files = this.data.keySet().stream()

                    .filter(JarContents::isSignature)

                    .collect(Collectors.toList());

            files.forEach(this::delete);

        }

    }


    static boolean isSignature(String s) {

        return s.startsWith("META-INF/") &&

                (s.endsWith(".SF") || s.endsWith(".DSA") || s.endsWith(".RSA") || s.endsWith(".EC"));

    }

}
