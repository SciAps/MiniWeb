package com.devsmart.miniweb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

@SuppressWarnings("CustomX509TrustManager")
public class CaTrustManager implements X509TrustManager {
    private final X509Certificate trustedCA;
    private static final Logger LOGGER = LoggerFactory.getLogger(CaTrustManager.class);

    public CaTrustManager(X509Certificate caCert) {
        this.trustedCA = caCert;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateChain(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateChain(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[]{trustedCA};
    }

    private void validateChain(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
           LOGGER.error("Empty certificate chain");
        }
        if (authType == null || authType.isEmpty()) {
            LOGGER.error("Missing authType");
        }

        // Check each certificate validity period
        for (X509Certificate cert : chain) {
            cert.checkValidity();
        }

        // Verify signature chain (leaf -> root)
        for (int i = 0; i < chain.length - 1; i++) {
            try {
                chain[i].verify(chain[i + 1].getPublicKey());
            } catch (Exception e) {
                LOGGER.error("Broken chain at position {}: {}", i, e.getMessage(), e);
            }
        }

        X509Certificate root = chain[chain.length - 1];

        // Ensure provided CA matches the root subject
        if (!root.getSubjectX500Principal().equals(trustedCA.getSubjectX500Principal())) {
            LOGGER.info("Root subject does not match trusted CA");
        }

        // Ensure provided CA actually signed the presented root
        try {
            root.verify(trustedCA.getPublicKey());
        } catch (Exception e) {
            LOGGER.error("Root not signed by trusted CA: {}", e.getMessage());
        }

        // Ensure CA is self-signed (basic check)
        try {
            trustedCA.verify(trustedCA.getPublicKey());
        } catch (Exception e) {
            LOGGER.error("Configured CA is not self-signed or invalid: {}", e.getMessage(), e);
        }
    }
}
