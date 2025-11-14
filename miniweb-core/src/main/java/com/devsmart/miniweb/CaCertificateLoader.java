package com.devsmart.miniweb;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class CaCertificateLoader {

    public static X509Certificate getCaCertificate() throws Exception {
        try (InputStream caInput = CaCertificateLoader.class.getResourceAsStream("/ca.crt")) {
            if (caInput == null) {
                throw new Exception("ca.crt not found in resources.");
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(caInput);
        }
    }

    public static char[] getCertificatePassword() {
        // temp super secure password
        return "password".toCharArray();
    }

}
