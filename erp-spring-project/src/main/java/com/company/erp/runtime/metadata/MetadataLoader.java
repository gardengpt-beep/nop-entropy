package com.company.erp.runtime.metadata;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Parses the XML metadata that ships with the project. The implementation only
 * needs a small subset of the full schema to drive the in-memory engine.
 */
public final class MetadataLoader {

    private static final String ORM_RESOURCE = "/_vfs/com/company/erp/orm/app.orm.xml";

    private MetadataLoader() {
    }

    public static EntityDefinition loadProductEntity() {
        try (InputStream input = MetadataLoader.class.getResourceAsStream(ORM_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource " + ORM_RESOURCE);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(input);
            NodeList entityNodes = document.getElementsByTagName("entity");
            for (int i = 0; i < entityNodes.getLength(); i++) {
                Node node = entityNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    if ("erp.ErpProduct".equals(element.getAttribute("name"))) {
                        return parseEntity(element);
                    }
                }
            }
            throw new IllegalStateException("Entity erp.ErpProduct not declared in metadata");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + ORM_RESOURCE, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + ORM_RESOURCE, e);
        }
    }

    private static EntityDefinition parseEntity(Element entityElement) {
        EntityDefinition definition = new EntityDefinition(entityElement.getAttribute("name"));
        NodeList columns = entityElement.getElementsByTagName("column");
        for (int i = 0; i < columns.getLength(); i++) {
            Node node = columns.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element columnElement = (Element) node;
            String name = columnElement.getAttribute("name");
            boolean mandatory = "true".equalsIgnoreCase(columnElement.getAttribute("mandatory"));
            boolean unique = "true".equalsIgnoreCase(columnElement.getAttribute("unique"));
            String defaultValue = emptyToNull(columnElement.getAttribute("defaultValue"));
            String tagSet = columnElement.getAttribute("tagSet");
            boolean sequence = tagSet != null && tagSet.toLowerCase(Locale.ROOT).contains("seq");
            definition.addColumn(new ColumnDefinition(name, mandatory, unique, defaultValue, sequence));
        }
        return definition;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
