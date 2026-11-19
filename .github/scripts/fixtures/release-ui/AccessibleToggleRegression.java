package com.artflow.qualification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Runs the driver's resolver against actual retained accessibility rows, without Android. */
public final class AccessibleToggleRegression {
    private static final String APP = "com.artflow.studio";

    public static void main(String[] args) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(Path.of(args[1]).toFile());
        Element label = labels(document, "text", "High contrast").get(0);
        Element row = (Element) label.getParentNode();
        switch (args[0]) {
            case "retained-rows":
                for (String title : List.of("High contrast", "Reduce motion", "Larger touch targets")) {
                    Element text = labels(document, "text", title).get(0);
                    require(!"true".equals(text.getAttribute("checkable")), "The old selector unexpectedly matches");
                    Element resolved = resolve(labels(document, "text", title));
                    require(resolved == text.getParentNode(), "Did not choose the actual labeled switch row");
                    require("true".equals(resolved.getAttribute("clickable")), "Row is not clickable");
                    require("false".equals(resolved.getAttribute("checked")), "Lost the switch state");
                }
                break;
            case "direct-description":
                row.setAttribute("content-desc", "Flatten onto white");
                row.setAttribute("checked", "true");
                require(resolve(labels(document, "content-desc", "Flatten onto white")) == row,
                        "A directly labeled toggle must continue to resolve");
                break;
            case "repeated-label-in-one-row":
                row.appendChild(label.cloneNode(true));
                require(resolve(labels(document, "text", "High contrast")) == row,
                        "Two label nodes in one control must resolve to that same control");
                break;
            case "ambiguous-controls":
                row.getParentNode().appendChild(row.cloneNode(true));
                try {
                    resolve(labels(document, "text", "High contrast"));
                    throw new AssertionError("Two separate labeled controls were accepted");
                } catch (AssertionError expected) {
                    require(expected.getMessage().contains("Ambiguous labeled toggle"), "Wrong failure: " + expected);
                }
                break;
            case "unrelated-sibling":
                row.setAttribute("checkable", "false");
                require(resolve(labels(document, "text", "High contrast")) == null,
                        "The label must not select a different row's switch");
                break;
            case "package-boundary":
                row.setAttribute("package", "unrelated.package");
                require(resolve(labels(document, "text", "High contrast")) == null,
                        "Traversal must not cross the application boundary");
                break;
            case "hidden-label":
                label.setAttribute("visible-to-user", "false");
                require(resolve(labels(document, "text", "High contrast")) == null,
                        "Hidden labels must not qualify a visible control");
                break;
            case "missing-label":
                require(resolve(labels(document, "text", "Missing setting")) == null,
                        "An absent label must not choose another switch");
                break;
            default:
                throw new AssertionError("Unknown regression case: " + args[0]);
        }
        System.out.println("PASS " + args[0]);
    }

    private static Element resolve(List<Element> labels) {
        return AccessibleToggle.resolve(labels,
                node -> node.getParentNode() instanceof Element ? (Element) node.getParentNode() : null,
                node -> APP.equals(node.getAttribute("package"))
                        && !"false".equals(node.getAttribute("visible-to-user")),
                node -> "true".equals(node.getAttribute("checkable")));
    }

    private static List<Element> labels(Document document, String attribute, String value) {
        List<Element> result = new ArrayList<>();
        for (int index = 0; index < document.getElementsByTagName("node").getLength(); index++) {
            Node node = document.getElementsByTagName("node").item(index);
            Element element = (Element) node;
            if (APP.equals(element.getAttribute("package")) && value.equals(element.getAttribute(attribute))) {
                result.add(element);
            }
        }
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
