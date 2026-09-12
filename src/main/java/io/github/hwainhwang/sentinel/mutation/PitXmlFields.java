package io.github.hwainhwang.sentinel.mutation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Exact field readers for the bounded standard PIT XML report. */
final class PitXmlFields {
    private PitXmlFields() {
        throw new AssertionError("no instances");
    }

    static void attributes(Element element, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            actual.add(attributes.item(index).getNodeName());
        }
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("pitAttributesInvalid");
        }
    }

    static List<Element> children(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            } else if (!ignorable(node)) {
                throw new IllegalArgumentException("pitContentInvalid");
            }
        }
        return children;
    }

    private static boolean ignorable(Node node) {
        return node.getNodeType() == Node.COMMENT_NODE
                || node.getNodeType() == Node.TEXT_NODE && node.getTextContent().isBlank();
    }

    static Map<String, Element> fields(Element parent, Set<String> expected) {
        Map<String, Element> fields = new HashMap<>();
        for (Element element : children(parent)) {
            if (fields.put(element.getTagName(), element) != null) {
                throw new IllegalArgumentException("pitFieldDuplicate");
            }
        }
        if (!expected.equals(fields.keySet())) {
            throw new IllegalArgumentException("pitFieldsInvalid");
        }
        return fields;
    }

    static String text(Element element) {
        attributes(element, Set.of());
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.TEXT_NODE && node.getNodeType() != Node.CDATA_SECTION_NODE) {
                throw new IllegalArgumentException("pitFieldContentInvalid");
            }
        }
        return element.getTextContent();
    }

    static List<Integer> numbers(Element parent, String tag) {
        attributes(parent, Set.of());
        List<Integer> values = new ArrayList<>();
        for (Element element : children(parent)) {
            if (!element.getTagName().equals(tag)) {
                throw new IllegalArgumentException("pitElementInvalid");
            }
            values.add(number(text(element)));
        }
        return values;
    }

    static int number(String value) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("pitNumberInvalid");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("pitNumberInvalid");
        }
    }

    static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("pitBooleanInvalid");
        }
        return value.equals("true");
    }
}
