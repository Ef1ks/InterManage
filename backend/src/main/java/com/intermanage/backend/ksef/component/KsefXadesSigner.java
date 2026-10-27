package com.intermanage.backend.component;

import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.security.auth.x500.X500Principal;
import javax.xml.XMLConstants;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLObject;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Dokument {@code AuthTokenRequest} do logowania w KSeF certyfikatem, podpisany XAdES-BES (podpis otaczany,
 * {@code SigningTime} i {@code SigningCertificate}). Klucz EC podpisuje ECDSA-SHA256 (wartość R||S, jak wymaga
 * XMLDSig), RSA - RSA-SHA256.
 */
@Component
public class KsefXadesSigner {

    static final String AUTH_NAMESPACE = "http://ksef.mf.gov.pl/auth/token/2.0";
    private static final String XADES = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String SIGNED_PROPERTIES_TYPE = "http://uri.etsi.org/01903#SignedProperties";

    /** {@code subjectIdentifierType=certificateSubject}: tożsamość z pól certyfikatu (PESEL/NIP osoby). */
    public String signedAuthRequest(String challenge, String nip, X509Certificate certificate, PrivateKey key) {
        try {
            Document document = newDocument();
            Element root = document.createElementNS(AUTH_NAMESPACE, "AuthTokenRequest");
            // Deklaracja przestrzeni nazw jako atrybut DOM - inaczej kanonizacja przy podpisie jej nie widzi,
            // a zapisany dokument ją ma, i skrót by się nie zgadzał.
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", AUTH_NAMESPACE);
            document.appendChild(root);
            append(document, root, "Challenge", challenge);
            Element context = append(document, root, "ContextIdentifier", null);
            append(document, context, "Nip", nip);
            append(document, root, "SubjectIdentifierType", "certificateSubject");
            sign(document, certificate, key);
            return serialize(document);
        } catch (GeneralSecurityException | MarshalException
                | XMLSignatureException | ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("Nie udało się podpisać żądania logowania do KSeF", e);
        }
    }

    private static void sign(Document document, X509Certificate certificate, PrivateKey key)
            throws GeneralSecurityException, MarshalException,
            XMLSignatureException {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        String suffix = UUID.randomUUID().toString();
        String signatureId = "Signature-" + suffix;
        String signedPropertiesId = "SignedProperties-" + suffix;

        DigestMethod sha256 = factory.newDigestMethod(DigestMethod.SHA256, null);
        Transform exclusive = factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null);
        Reference documentReference = factory.newReference("", sha256,
                List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null), exclusive),
                null, null);
        Reference propertiesReference = factory.newReference("#" + signedPropertiesId, sha256, List.of(exclusive),
                SIGNED_PROPERTIES_TYPE, null);
        String signatureMethod = key.getAlgorithm().equals("EC") ? SignatureMethod.ECDSA_SHA256
                : SignatureMethod.RSA_SHA256;
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(signatureMethod, null),
                List.of(documentReference, propertiesReference));

        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newX509Data(List.of(certificate))));

        Element qualifying = qualifyingProperties(document, signatureId, signedPropertiesId, certificate);
        XMLObject object = factory.newXMLObject(List.of(new DOMStructure(qualifying)), null, null, null);
        XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo, List.of(object), signatureId, null);

        DOMSignContext context = new DOMSignContext(key, document.getDocumentElement());
        context.setDefaultNamespacePrefix("ds");
        Element signedProperties = (Element) qualifying.getFirstChild();
        context.setIdAttributeNS(signedProperties, null, "Id");
        signature.sign(context);
    }

    /** XAdES: kiedy podpisano i którym certyfikatem (skrót SHA-256 i wystawca z numerem seryjnym). */
    private static Element qualifyingProperties(Document document, String signatureId, String signedPropertiesId,
            X509Certificate certificate) throws GeneralSecurityException {
        Element qualifying = document.createElementNS(XADES, "xades:QualifyingProperties");
        qualifying.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xades", XADES);
        qualifying.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ds", XMLSignature.XMLNS);
        qualifying.setAttributeNS(null, "Target", "#" + signatureId);

        Element signedProperties = xades(document, qualifying, "SignedProperties");
        signedProperties.setAttributeNS(null, "Id", signedPropertiesId);
        Element signatureProperties = xades(document, signedProperties, "SignedSignatureProperties");
        xades(document, signatureProperties, "SigningTime")
                .setTextContent(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

        Element cert = xades(document, xades(document, signatureProperties, "SigningCertificate"), "Cert");
        Element digest = xades(document, cert, "CertDigest");
        Element digestMethod = ds(document, digest, "DigestMethod");
        digestMethod.setAttributeNS(null, "Algorithm", DigestMethod.SHA256);
        ds(document, digest, "DigestValue").setTextContent(Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        Element issuerSerial = xades(document, cert, "IssuerSerial");
        ds(document, issuerSerial, "X509IssuerName")
                .setTextContent(certificate.getIssuerX500Principal().getName(X500Principal.RFC2253));
        ds(document, issuerSerial, "X509SerialNumber").setTextContent(certificate.getSerialNumber().toString());
        return qualifying;
    }

    private static Element xades(Document document, Element parent, String name) {
        Element element = document.createElementNS(XADES, "xades:" + name);
        parent.appendChild(element);
        return element;
    }

    private static Element ds(Document document, Element parent, String name) {
        Element element = document.createElementNS(XMLSignature.XMLNS, "ds:" + name);
        parent.appendChild(element);
        return element;
    }

    private static Element append(Document document, Element parent, String name, String text) {
        Element element = document.createElementNS(AUTH_NAMESPACE, name);
        if (text != null) {
            element.setTextContent(text);
        }
        parent.appendChild(element);
        return element;
    }

    private static Document newDocument() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().newDocument();
    }

    private static String serialize(Document document) throws TransformerException {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
