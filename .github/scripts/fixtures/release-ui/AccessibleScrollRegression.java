package com.artflow.qualification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Tests actual container/action dispatch against the failed attempt's observed geometry. */
public final class AccessibleScrollRegression {
    public static void main(String[] args) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(Path.of(args[1]).toFile());
        List<Element> containers = new ArrayList<>();
        Element scroll = null;
        Element slider = null;
        for (int index = 0; index < document.getElementsByTagName("node").getLength(); index++) {
            Element node = (Element) document.getElementsByTagName("node").item(index);
            if (!"com.artflow.studio".equals(node.getAttribute("package"))) continue;
            if ("true".equals(node.getAttribute("scrollable"))) containers.add(node);
            if ("android.widget.ScrollView".equals(node.getAttribute("class"))) scroll = node;
            if ("android.widget.SeekBar".equals(node.getAttribute("class"))) slider = node;
        }
        require(scroll != null && slider != null, "Missing retained scroll/slider evidence");
        List<Element> performedOn = new ArrayList<>();
        List<String> performedActions = new ArrayList<>();
        switch (args[0]) {
            case "scroll-retained-slider":
                Matcher origin = Pattern.compile("Pause\\(point=Point\\((\\d+), (\\d+)\\)")
                        .matcher(Files.readString(Path.of(args[2])));
                require(origin.find(), "Missing actual failed gesture origin");
                int x = Integer.parseInt(origin.group(1)), y = Integer.parseInt(origin.group(2));
                int[] bounds = bounds(slider);
                require(x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3],
                        "The recorded gesture no longer reproduces the embedded slider collision");
                require(request(containers, "forward", true, true, performedOn, performedActions),
                        "The advertised container action was not dispatched");
                require(performedOn.equals(List.of(scroll)) && performedActions.equals(List.of("forward")),
                        "Scrolling must target the parent once, never the slider or horizontal strip");
                break;
            case "scroll-backward":
                require(request(containers, "backward", true, true, performedOn, performedActions),
                        "Backward action was not dispatched");
                require(performedOn.equals(List.of(scroll)) && performedActions.equals(List.of("backward")),
                        "Backward request changed direction or target");
                break;
            case "scroll-unadvertised":
                require(!request(containers, "forward", false, true, performedOn, performedActions),
                        "An unadvertised action was accepted");
                require(performedOn.isEmpty(), "Do not synthesize an unsupported action");
                break;
            case "scroll-rejected":
                require(!request(containers, "forward", true, false, performedOn, performedActions),
                        "A rejected action was reported as successful");
                require(performedOn.equals(List.of(scroll)) && performedActions.size() == 1,
                        "A rejected action must not be retried or redirected");
                break;
            case "scroll-short":
                containers.remove(scroll);
                require(!request(containers, "forward", true, true, performedOn, performedActions),
                        "The short horizontal strip was selected for vertical page navigation");
                require(performedOn.isEmpty(), "An ineligible strip received an action");
                break;
            case "scroll-empty":
                require(!request(List.of(), "forward", true, true, performedOn, performedActions),
                        "No visible container must mean no scrolling");
                require(performedOn.isEmpty(), "No target should receive an action");
                break;
            default:
                throw new AssertionError("Unknown regression case: " + args[0]);
        }
        System.out.println("PASS " + args[0]);
    }

    private static boolean request(List<Element> candidates, String action, boolean advertised, boolean accepted,
            List<Element> performedOn, List<String> performedActions) {
        return AccessibleScroll.request(candidates, node -> bounds(node)[3] - bounds(node)[1], 640 / 4,
                action, (node, requested) -> advertised, (node, requested) -> {
                    performedOn.add(node);
                    performedActions.add(requested);
                    return accepted;
                });
    }

    private static int[] bounds(Element node) {
        Matcher match = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
                .matcher(node.getAttribute("bounds"));
        require(match.matches(), "Invalid retained bounds");
        return new int[]{Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4))};
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
