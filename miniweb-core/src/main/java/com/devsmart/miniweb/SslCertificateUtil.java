package com.devsmart.miniweb;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static java.io.File.separator;
import static java.lang.System.getProperty;

public class SslCertificateUtil {

    public static X509Certificate getCaCertificate() throws Exception {
        try (InputStream caInput = SslCertificateUtil.class.getResourceAsStream("/ca.crt")) {
            if (caInput == null) {
                throw new Exception("ca.crt not found in resources.");
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(caInput);
        }
    }

    public static char[] getCertificatePassword() {
        return "SciApsN@SC@R".toCharArray();
    }

    public static String getPbCertificatePath() {
        String osName = getProperty("os.name", "Windows");
        if (osName.contains("Windows")) {
            return "C:" + separator + "sciaps" + separator + "client.p12";
        } else if ("Mac OS X".equals(osName)) {
            return getProperty("user.home") + separator + "sciaps" + separator + "client.p12";
        } else {
            throw new UnsupportedOperationException("If you are seeing this message, you must be on a Linux system. Sorry...");
        }
    }

}
