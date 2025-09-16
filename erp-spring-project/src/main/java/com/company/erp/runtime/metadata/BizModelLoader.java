package com.company.erp.runtime.metadata;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the business action metadata needed by the simplified engine. Only the
 * status filter for the {@code active_findPage} query is required.
 */
public final class BizModelLoader {

    private static final String BIZ_RESOURCE = "/_vfs/com/company/erp/model/ErpProduct/ErpProduct.xbiz";

    private BizModelLoader() {
    }

    public static String loadActiveStatusCode() {
        try (InputStream input = BizModelLoader.class.getResourceAsStream(BIZ_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource " + BIZ_RESOURCE);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(input);
            NodeList eqNodes = document.getElementsByTagName("eq");
            for (int i = 0; i < eqNodes.getLength(); i++) {
                Node node = eqNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                if ("status".equals(element.getAttribute("name"))) {
                    String value = element.getAttribute("value");
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
            throw new IllegalStateException("active_findPage filter not declared in " + BIZ_RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + BIZ_RESOURCE, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + BIZ_RESOURCE, e);
        }
    }
}
