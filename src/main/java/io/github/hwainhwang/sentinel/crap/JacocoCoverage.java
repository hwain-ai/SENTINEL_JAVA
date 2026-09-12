package io.github.hwainhwang.sentinel.crap;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Strict JaCoCo XML parser and exact method-level coverage join. */
public final class JacocoCoverage {
    private static final byte[] DOCTYPE_PREFIX = "<!DOCTYPE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PINNED_JACOCO_DOCTYPE = ("<!DOCTYPE report PUBLIC "
            + "\"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Set<String> COUNTER_TYPES = Set.of(
            "INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS");
    private static final Map<String, Set<String>> ELEMENT_PARENTS = Map.of(
            "sessioninfo", Set.of("report"),
            "group", Set.of("report", "group"),
            "package", Set.of("report", "group"),
            "class", Set.of("package"),
            "method", Set.of("class"),
            "sourcefile", Set.of("package"),
            "line", Set.of("sourcefile"),
            "counter", Set.of("report", "group", "package", "class", "method", "sourcefile"));

    private JacocoCoverage() {
        throw new AssertionError("no instances");
    }

    public static Report parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw new CoverageFormatException("coverageXmlMissing");
        }
        try {
            var builder = builderFactory().newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }

                @Override
                public void error(org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            Document document = builder.parse(new ByteArrayInputStream(stripPinnedDoctype(xml)));
            Element root = document.getDocumentElement();
            if (root == null || !"report".equals(root.getTagName())) {
                throw new CoverageFormatException("coverageRootInvalid");
            }
            validateStructure(root);
            validateAllCounters(root);
            return Report.present(readMethods(root));
        } catch (CoverageFormatException error) {
            throw error;
        } catch (ParserConfigurationException | SAXException | java.io.IOException error) {
            throw new CoverageFormatException("coverageXmlInvalid", error);
        }
    }

    private static byte[] stripPinnedDoctype(byte[] xml) {
        int doctype = indexOf(xml, DOCTYPE_PREFIX, 0);
        if (doctype < 0) {
            return xml;
        }
        if (indexOf(xml, PINNED_JACOCO_DOCTYPE, 0) != doctype
                || indexOf(xml, DOCTYPE_PREFIX, doctype + DOCTYPE_PREFIX.length) >= 0) {
            throw new CoverageFormatException("coverageDoctypeInvalid");
        }
        byte[] stripped = new byte[xml.length - PINNED_JACOCO_DOCTYPE.length];
        System.arraycopy(xml, 0, stripped, 0, doctype);
        System.arraycopy(
                xml,
                doctype + PINNED_JACOCO_DOCTYPE.length,
                stripped,
                doctype,
                stripped.length - doctype);
        return stripped;
    }

    private static int indexOf(byte[] source, byte[] expected, int start) {
        int last = source.length - expected.length;
        for (int index = start; index <= last; index++) {
            int offset = 0;
            while (offset < expected.length && source[index + offset] == expected[offset]) {
                offset++;
            }
            if (offset == expected.length) {
                return index;
            }
        }
        return -1;
    }

    public static List<Models.CallableMetric> measure(
            List<Models.CallableDefinition> definitions, Report report) {
        if (definitions == null || report == null) {
            throw new IllegalArgumentException("coverageJoinInputMissing");
        }
        List<Models.CallableMetric> metrics = new ArrayList<>(definitions.size());
        for (Models.CallableDefinition definition : definitions) {
            metrics.add(measureOne(definition, report));
        }
        return List.copyOf(metrics);
    }

    private static Models.CallableMetric measureOne(
            Models.CallableDefinition definition, Report report) {
        if (definition == null) {
            throw new IllegalArgumentException("callableMissing");
        }
        if (!report.present()) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.REPORT_MISSING);
        }
        if (definition.identity().kind() == Models.CallableKind.LAMBDA) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.LAMBDA_MAPPING_UNAVAILABLE);
        }
        if (definition.jacocoClassName() == null) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.CLASSFILE_MAPPING_UNAVAILABLE);
        }
        MethodKey key = new MethodKey(
                definition.jacocoClassName(),
                definition.jacocoMethodName(),
                definition.identity().descriptor());
        List<MethodCoverage> matches = report.methods().getOrDefault(key, List.of());
        return metricFromMatches(definition, matches);
    }

    private static Models.CallableMetric metricFromMatches(
            Models.CallableDefinition definition, List<MethodCoverage> matches) {
        if (matches.isEmpty()) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.METHOD_MISSING);
        }
        if (matches.size() != 1) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.METHOD_AMBIGUOUS);
        }
        MethodCoverage coverage = matches.get(0);
        if (coverage.line() < definition.declarationLine()
                || coverage.line() > definition.sourceEndLine()) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.METHOD_MISSING);
        }
        if (coverage.instructions().size() != 1) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.COUNTER_AMBIGUOUS);
        }
        Counts counts = coverage.instructions().get(0);
        if (counts.total() == 0) {
            return Models.CallableMetric.unknown(
                    definition, Models.CoverageUnknownReason.ZERO_INSTRUCTIONS);
        }
        return Models.CallableMetric.known(definition, counts.covered(), counts.total());
    }

    private static DocumentBuilderFactory builderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static Map<MethodKey, List<MethodCoverage>> readMethods(Element root) {
        Map<MethodKey, List<MethodCoverage>> methods = new HashMap<>();
        NodeList classes = root.getElementsByTagName("class");
        for (int index = 0; index < classes.getLength(); index++) {
            Element classElement = asElement(classes.item(index), "class");
            String className = requiredAttribute(classElement, "name");
            requireParent(classElement, "package");
            validateInternalName(className);
            readClassMethods(classElement, className, methods);
        }
        return immutable(methods);
    }

    private static void readClassMethods(
            Element classElement,
            String className,
            Map<MethodKey, List<MethodCoverage>> methods) {
        for (Node child = classElement.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && "method".equals(element.getTagName())) {
                String methodName = requiredAttribute(element, "name");
                String descriptor = requiredAttribute(element, "desc");
                validateMethodName(methodName);
                validateMethodDescriptor(methodName, descriptor);
                MethodKey key = new MethodKey(
                        className,
                        methodName,
                        descriptor);
                List<MethodCoverage> matches = methods.get(key);
                if (matches == null) {
                    matches = new ArrayList<>();
                    methods.put(key, matches);
                }
                matches.add(new MethodCoverage(
                        positiveCount(element, "line"),
                        readInstructionCounters(element)));
            }
        }
    }

    private static List<Counts> readInstructionCounters(Element method) {
        List<Counts> counters = new ArrayList<>();
        for (Node child = method.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element)) {
                continue;
            }
            if (!"counter".equals(element.getTagName())) {
                throw new CoverageFormatException("coverageStructureInvalid");
            }
            String type = requiredAttribute(element, "type");
            if (!COUNTER_TYPES.contains(type)) {
                throw new CoverageFormatException("coverageCounterTypeInvalid");
            }
            Counts counts = new Counts(count(element, "covered"), count(element, "missed"));
            if ("INSTRUCTION".equals(type)) {
                counters.add(counts);
            }
        }
        return List.copyOf(counters);
    }

    private static void validateStructure(Element root) {
        NodeList elements = root.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = asElement(elements.item(index));
            Set<String> parents = ELEMENT_PARENTS.get(element.getTagName());
            Node parent = element.getParentNode();
            if (parents == null
                    || !(parent instanceof Element parentElement)
                    || !parents.contains(parentElement.getTagName())) {
                throw new CoverageFormatException("coverageStructureInvalid");
            }
        }
    }

    private static void validateAllCounters(Element root) {
        NodeList counters = root.getElementsByTagName("counter");
        for (int index = 0; index < counters.getLength(); index++) {
            Element counter = asElement(counters.item(index), "counter");
            String type = requiredAttribute(counter, "type");
            if (!COUNTER_TYPES.contains(type)) {
                throw new CoverageFormatException("coverageCounterTypeInvalid");
            }
            new Counts(count(counter, "covered"), count(counter, "missed"));
        }
    }

    private static void requireParent(Element element, String expected) {
        Node parent = element.getParentNode();
        if (!(parent instanceof Element parentElement)
                || !expected.equals(parentElement.getTagName())) {
            throw new CoverageFormatException("coverageStructureInvalid");
        }
    }

    private static void validateInternalName(String value) {
        if (hasInvalidInternalNameShape(value)) {
            throw new CoverageFormatException("coverageClassNameInvalid");
        }
        for (String segment : value.split("/", -1)) {
            if (isInvalidInternalNameSegment(segment)) {
                throw new CoverageFormatException("coverageClassNameInvalid");
            }
        }
    }

    private static boolean hasInvalidInternalNameShape(String value) {
        return value.startsWith("/") || value.endsWith("/") || value.contains("//")
                || value.contains(".") || value.contains(";") || value.contains("[")
                || value.contains("\\");
    }

    private static boolean isInvalidInternalNameSegment(String segment) {
        return segment.isEmpty() || segment.equals(".") || segment.equals("..");
    }

    private static void validateMethodName(String value) {
        if (hasInvalidMethodNameShape(value) || hasInvalidSpecialMethodName(value)) {
            throw new CoverageFormatException("coverageMethodNameInvalid");
        }
    }

    private static boolean hasInvalidMethodNameShape(String value) {
        return value.isEmpty() || value.contains(".") || value.contains(";")
                || value.contains("[") || value.contains("/");
    }

    private static boolean hasInvalidSpecialMethodName(String value) {
        boolean containsAngle = value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
        return containsAngle && !value.equals("<init>") && !value.equals("<clinit>");
    }

    private static void validateMethodDescriptor(String methodName, String value) {
        int returnStart = descriptorReturnStart(value);
        int end = descriptorTypeEnd(value, returnStart, true);
        if (end != value.length()) {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
        validateSpecialMethodDescriptor(methodName, value, returnStart);
    }

    private static int descriptorReturnStart(String value) {
        if (!value.startsWith("(")) {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
        int cursor = 1;
        while (cursor < value.length() && value.charAt(cursor) != ')') {
            cursor = descriptorTypeEnd(value, cursor, false);
        }
        if (cursor >= value.length() || value.charAt(cursor) != ')') {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
        return cursor + 1;
    }

    private static void validateSpecialMethodDescriptor(
            String methodName, String value, int returnStart) {
        if ("<init>".equals(methodName) && value.charAt(returnStart) != 'V') {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
        if ("<clinit>".equals(methodName) && !"()V".equals(value)) {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
    }

    private static int descriptorTypeEnd(String value, int start, boolean allowVoid) {
        if (start >= value.length()) {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
        char symbol = value.charAt(start);
        if (isSimpleDescriptor(symbol, allowVoid)) {
            return start + 1;
        }
        return complexDescriptorEnd(value, start, symbol);
    }

    private static boolean isSimpleDescriptor(char symbol, boolean allowVoid) {
        if ("BCDFIJSZ".indexOf(symbol) >= 0) {
            return true;
        }
        return allowVoid && symbol == 'V';
    }

    private static int complexDescriptorEnd(String value, int start, char symbol) {
        if (symbol == '[') {
            return descriptorTypeEnd(value, start + 1, false);
        }
        requireObjectDescriptor(symbol);
        return objectDescriptorEnd(value, start);
    }

    private static void requireObjectDescriptor(char symbol) {
        if (symbol != 'L') {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
    }

    private static int objectDescriptorEnd(String value, int start) {
        int end = value.indexOf(';', start + 1);
        if (end < 0) {
            throw new CoverageFormatException("coverageMethodDescriptorInvalid");
        }
        validateInternalName(value.substring(start + 1, end));
        return end + 1;
    }

    private static long count(Element element, String attribute) {
        String raw = requiredAttribute(element, attribute);
        if (!raw.matches("0|[1-9][0-9]*")) {
            throw new CoverageFormatException("coverageCountInvalid");
        }
        try {
            long value = Long.parseLong(raw);
            if (value > Models.MAX_SAFE_INTEGER) {
                throw new CoverageFormatException("coverageCountInvalid");
            }
            return value;
        } catch (NumberFormatException error) {
            throw new CoverageFormatException("coverageCountInvalid", error);
        }
    }

    private static long positiveCount(Element element, String attribute) {
        long value = count(element, attribute);
        if (value < 1) {
            throw new CoverageFormatException("coverageCountInvalid");
        }
        return value;
    }

    private static String requiredAttribute(Element element, String name) {
        if (!element.hasAttribute(name) || element.getAttribute(name).isEmpty()) {
            throw new CoverageFormatException("coverageAttributeMissing:" + name);
        }
        String value = element.getAttribute(name);
        SemanticSite.utf8(value);
        return value;
    }

    private static Element asElement(Node node, String expected) {
        if (!(node instanceof Element element) || !expected.equals(element.getTagName())) {
            throw new CoverageFormatException("coverageStructureInvalid");
        }
        return element;
    }

    private static Element asElement(Node node) {
        if (!(node instanceof Element element)) {
            throw new CoverageFormatException("coverageStructureInvalid");
        }
        return element;
    }

    private static Map<MethodKey, List<MethodCoverage>> immutable(
            Map<MethodKey, List<MethodCoverage>> source) {
        Map<MethodKey, List<MethodCoverage>> copy = new HashMap<>();
        for (Map.Entry<MethodKey, List<MethodCoverage>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    public record Report(boolean present, Map<MethodKey, List<MethodCoverage>> methods) {
        public Report {
            if (methods == null || (!present && !methods.isEmpty())) {
                throw new IllegalArgumentException("coverageReportInvalid");
            }
            methods = Map.copyOf(methods);
        }

        public static Report missing() {
            return new Report(false, Map.of());
        }

        private static Report present(Map<MethodKey, List<MethodCoverage>> methods) {
            return new Report(true, methods);
        }
    }

    public record MethodKey(String className, String methodName, String descriptor) {
        public MethodKey {
            if (className == null || methodName == null || descriptor == null) {
                throw new IllegalArgumentException("coverageMethodKeyInvalid");
            }
        }
    }

    public record MethodCoverage(long line, List<Counts> instructions) {
        public MethodCoverage {
            if (line < 1 || line > Models.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("coverageMethodLineInvalid");
            }
            instructions = List.copyOf(instructions);
        }
    }

    public record Counts(long covered, long missed) {
        public Counts {
            if (covered < 0 || missed < 0 || covered > Models.MAX_SAFE_INTEGER
                    || missed > Models.MAX_SAFE_INTEGER - covered) {
                throw new CoverageFormatException("coverageCountInvalid");
            }
        }

        public long total() {
            return covered + missed;
        }
    }
}
