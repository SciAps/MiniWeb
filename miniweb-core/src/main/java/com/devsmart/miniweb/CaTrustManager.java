package com.devsmart.miniweb;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

@SuppressWarnings("CustomX509TrustManager")
public class CaTrustManager implements X509TrustManager {
    private final X509Certificate trustedCA;

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
            throw new CertificateException("Empty certificate chain");
        }
        if (authType == null || authType.isEmpty()) {
            throw new CertificateException("Missing authType");
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
                throw new CertificateException("Broken chain at position " + i + ": " + e.getMessage(), e);
            }
        }

        X509Certificate root = chain[chain.length - 1];

        // Ensure provided CA matches the root subject
        if (!root.getSubjectX500Principal().equals(trustedCA.getSubjectX500Principal())) {
            throw new CertificateException("Root subject does not match trusted CA");
        }

        // Ensure provided CA actually signed the presented root
        try {
            root.verify(trustedCA.getPublicKey());
        } catch (Exception e) {
            throw new CertificateException("Root not signed by trusted CA: " + e.getMessage(), e);
        }

        // Optional: ensure CA is self-signed (basic check)
        try {
            trustedCA.verify(trustedCA.getPublicKey());
        } catch (Exception e) {
            throw new CertificateException("Configured CA is not self-signed or invalid: " + e.getMessage(), e);
        }
    }
}
