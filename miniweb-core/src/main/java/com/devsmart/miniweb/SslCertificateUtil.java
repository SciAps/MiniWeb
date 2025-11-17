package com.devsmart.miniweb;

import org.slf4j.Logger;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static java.io.File.separator;
import static java.lang.System.getProperty;

public class SslCertificateUtil {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SslCertificateUtil.class);

    public static X509Certificate getCaCertificate() throws Exception {
        try (InputStream caInput = SslCertificateUtil.class.getResourceAsStream("/ca.crt")) {
            if (caInput == null) {
                LOGGER.error("ca.crt not found in resources.");
                return null;
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
            LOGGER.error("Unsupported OS: {}", osName);
            return "";
        }
    }

}
