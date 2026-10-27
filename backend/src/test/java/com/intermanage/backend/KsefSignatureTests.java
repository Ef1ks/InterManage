package com.intermanage.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.ksef.component.KsefCredentials;
import com.intermanage.backend.ksef.component.KsefXadesSigner;

/** Certyfikat testowy (EC P-256, klucz PBES2/AES-256 jak z KSeF) i podpis XAdES żądania logowania. */
class KsefSignatureTests {

    private static final String PASSWORD = "haslo-testowe";

    private final KsefCredentials credentials = new KsefCredentials();

    @Test
    void decryptsKeyAndSignsAuthRequestWithValidXadesSignature() throws Exception {
        KsefCredentials.Loaded loaded = credentials.load(resource("test.crt"), resource("test.key"), PASSWORD);

        String xml = new KsefXadesSigner().signedAuthRequest("20250625-CR-20F5EE4000-DA48AE4124-46", "5265877635",
                loaded.certificate(), loaded.privateKey());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("http://ksef.mf.gov.pl/auth/token/2.0", document.getDocumentElement().getNamespaceURI());
        assertEquals("5265877635", document.getElementsByTagNameNS("*", "Nip").item(0).getTextContent());

        NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertEquals(1, signatures.getLength());
        Element signedProperties = (Element) document
                .getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", "SignedProperties").item(0);
        DOMValidateContext context = new DOMValidateContext(loaded.certificate().getPublicKey(), signatures.item(0));
        context.setIdAttributeNS(signedProperties, null, "Id");
        XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
        assertTrue(signature.validate(context), "podpis i oba skróty (dokument, SignedProperties) muszą się zgadzać");
        assertEquals(2, signature.getSignedInfo().getReferences().size());
    }

    @Test
    void rejectsWrongPasswordAndKeyOfAnotherCertificate() throws Exception {
        ApiException wrongPassword = assertThrows(ApiException.class,
                () -> credentials.load(resource("test.crt"), resource("test.key"), "zle-haslo"));
        assertEquals("Złe hasło do klucza", wrongPassword.getMessage());

        ApiException mismatch = assertThrows(ApiException.class,
                () -> credentials.load(resource("test.crt"), resource("other-plain.key"), null));
        assertTrue(mismatch.getMessage().contains("nie pasuje"));

        assertThrows(ApiException.class, () -> credentials.certificate("nie certyfikat".getBytes()));
    }

    private static String resource(String name) throws IOException {
        return new ClassPathResource("ksef/" + name).getContentAsString(StandardCharsets.US_ASCII);
    }
}
