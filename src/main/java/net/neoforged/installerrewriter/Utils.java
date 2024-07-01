package net.neoforged.installerrewriter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Utils {
    public static final Gson GSON = new Gson();
    public static JsonObject getURL(final String url) {
        try {
            final var path = URI.create(url).toURL();
            try (final var is = path.openStream()) {
                return GSON.fromJson(new InputStreamReader(is), JsonObject.class);
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    public static List<String> getLatestFromMavenMetadata(URI url) throws IOException {
        final InputStream stream = url.toURL().openStream();
        try (stream) {
            if (stream == null) {
                return List.of();
            }
            final var doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(stream);
            final XPathExpression expr = XPathFactory.newInstance()
                    .newXPath()
                    .compile("/metadata/versioning/versions/version/text()");
            final var list = ((NodeList)expr.evaluate(doc, XPathConstants.NODESET));
            final var res = new ArrayList<String>();
            for (int i = 0; i < list.getLength(); i++) {
                res.add(list.item(i).getTextContent());
            }
            return res;
        } catch (ParserConfigurationException | SAXException | XPathExpressionException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void download(String url, Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.deleteIfExists(path);
            try (final var is = URI.create(url).toURL().openStream()) {
                Files.copy(is, path);
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
