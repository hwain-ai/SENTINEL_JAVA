package io.github.hwainhwang.sentinel.mutation;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Strict PIT 1.30.0 standard XML reader; fullMutationMatrix is not admitted. */
public final class PitReportAdapter {
    private static final int MAX_XML_BYTES = 8 * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("sourceFile", "mutatedClass", "mutatedMethod",
            "methodDescription", "lineNumber", "mutator", "indexes", "blocks", "killingTest", "description");

    private PitReportAdapter() {
        throw new AssertionError("no instances");
    }

    public static PitReport parse(byte[] xml) {
        if (xml == null || xml.length == 0 || xml.length > MAX_XML_BYTES) {
            throw new IllegalArgumentException("pitXmlSizeInvalid");
        }
        Element root = readRoot(xml);
        if (!root.getTagName().equals("mutations")) {
            throw new IllegalArgumentException("pitRootInvalid");
        }
        PitXmlFields.attributes(root, Set.of("partial"));
        boolean partialCoverage = PitXmlFields.bool(root.getAttribute("partial"));
        var results = new ArrayList<PitReport.Result>();
        for (Element mutation : PitXmlFields.children(root)) {
            results.add(readMutation(mutation));
        }
        return new PitReport(partialCoverage, results);
    }

    private static Element readRoot(byte[] xml) {
        try {
            var builder = factory().newDocumentBuilder();
            builder.setErrorHandler(new StrictErrors());
            return builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | java.io.IOException error) {
            // XML parser messages can contain untrusted source text or local paths.
            throw new IllegalArgumentException("pitXmlInvalid");
        }
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "4");
        return factory;
    }

    private static PitReport.Result readMutation(Element mutation) {
        if (!mutation.getTagName().equals("mutation")) {
            throw new IllegalArgumentException("pitElementInvalid");
        }
        PitXmlFields.attributes(mutation, Set.of("detected", "status", "numberOfTestsRun"));
        PitReport.Status status = status(mutation);
        Map<String, Element> fields = PitXmlFields.fields(mutation, FIELDS);
        var identity = new PitReport.Identity(text(fields, "mutatedClass"), text(fields, "mutatedMethod"),
                text(fields, "methodDescription"), text(fields, "mutator"),
                PitXmlFields.numbers(fields.get("indexes"), "index"));
        return new PitReport.Result(identity, text(fields, "sourceFile"),
                PitXmlFields.number(text(fields, "lineNumber")),
                PitXmlFields.numbers(fields.get("blocks"), "block"),
                text(fields, "description"), text(fields, "killingTest"),
                PitXmlFields.number(mutation.getAttribute("numberOfTestsRun")), status);
    }

    private static PitReport.Status status(Element mutation) {
        PitReport.Status status;
        try {
            status = PitReport.Status.valueOf(mutation.getAttribute("status"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("pitStatusInvalid");
        }
        if (status.detected() != PitXmlFields.bool(mutation.getAttribute("detected"))) {
            throw new IllegalArgumentException("pitDetectedMismatch");
        }
        return status;
    }

    private static String text(Map<String, Element> fields, String name) {
        return PitXmlFields.text(fields.get(name));
    }

    private static final class StrictErrors extends DefaultHandler {
        @Override
        public void warning(SAXParseException error) throws SAXException {
            throw error;
        }

        @Override
        public void error(SAXParseException error) throws SAXException {
            throw error;
        }

        @Override
        public void fatalError(SAXParseException error) throws SAXException {
            throw error;
        }
    }
}
