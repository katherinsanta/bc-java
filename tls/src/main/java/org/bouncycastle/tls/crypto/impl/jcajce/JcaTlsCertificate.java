package org.bouncycastle.tls.crypto.impl.jcajce;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.interfaces.DHPublicKey;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.jcajce.util.JcaJceHelper;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.ConnectionEnd;
import org.bouncycastle.tls.KeyExchangeAlgorithm;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoException;
import org.bouncycastle.tls.crypto.TlsVerifier;

/**
 * Implementation class for a single X.509 certificate based on the JCA.
 */
public class JcaTlsCertificate
    implements TlsCertificate
{
    public static JcaTlsCertificate convert(JcaTlsCrypto crypto, TlsCertificate certificate) throws IOException
    {
        if (certificate instanceof JcaTlsCertificate)
        {
            return (JcaTlsCertificate)certificate;
        }

        return new JcaTlsCertificate(crypto, certificate.getEncoded());
    }

    public static X509Certificate parseCertificate(JcaJceHelper helper, byte[] encoding)
        throws IOException
    {
        try
        {
            /*
             * NOTE: We want to restrict 'encoding' to a binary BER encoding, but
             * CertificateFactory.generateCertificate claims to require DER encoding, and also
             * supports Base64 encodings (in PEM format), which we don't support.
             * 
             * Re-encoding validates as BER and produces DER.
             */
            byte[] derEncoding = Certificate.getInstance(encoding).getEncoded(ASN1Encoding.DER);

            ByteArrayInputStream input = new ByteArrayInputStream(derEncoding);
            X509Certificate certificate = (X509Certificate)helper.createCertificateFactory("X.509").generateCertificate(input);
            if (input.available() != 0)
            {
                throw new IOException("Extra data detected in stream");
            }
            return certificate;
        }
        catch (GeneralSecurityException e)
        {
            throw new TlsCryptoException("unable to decode certificate", e);
        }
    }

    protected final JcaTlsCrypto crypto;
    protected final X509Certificate certificate;

    protected DHPublicKey pubKeyDH = null;
    protected ECPublicKey pubKeyEC = null;
    protected RSAPublicKey pubKeyRSA = null;

    public JcaTlsCertificate(JcaTlsCrypto crypto, byte[] encoding)
        throws IOException
    {
        this(crypto, parseCertificate(crypto.getHelper(), encoding));
    }

    public JcaTlsCertificate(JcaTlsCrypto crypto, X509Certificate certificate)
    {
        this.crypto = crypto;
        this.certificate = certificate;
    }

    public TlsVerifier createVerifier(short signatureAlgorithm) throws IOException
    {
        validateKeyUsage(KeyUsage.digitalSignature);

        switch (signatureAlgorithm)
        {
        case SignatureAlgorithm.dsa:
            return new JcaTlsDSAVerifier(crypto, getPubKeyDSS());

        case SignatureAlgorithm.ecdsa:
            return new JcaTlsECDSAVerifier(crypto, getPubKeyEC());

        case SignatureAlgorithm.ed25519:
            return new JcaTlsEd25519Verifier(crypto, getPubKeyEd25519());

        case SignatureAlgorithm.ed448:
            return new JcaTlsEd448Verifier(crypto, getPubKeyEd448());

        case SignatureAlgorithm.rsa:
            return new JcaTlsRSAVerifier(crypto, getPubKeyRSA());

        default:
            throw new TlsFatalAlert(AlertDescription.certificate_unknown);
        }
    }

    public byte[] getEncoded() throws IOException
    {
        try
        {
            // DER encoding enforced by provider - as defined by JCA for X.509 certificates.
            return certificate.getEncoded();
        }
        catch (CertificateEncodingException e)
        {
            throw new TlsCryptoException("unable to encode certificate: " + e.getMessage(), e);
        }
    }

    public byte[] getExtension(ASN1ObjectIdentifier extensionOID) throws IOException
    {
        byte[] encoding = certificate.getExtensionValue(extensionOID.getId());
        return encoding == null ? null : ((ASN1OctetString)ASN1Primitive.fromByteArray(encoding)).getOctets();
    }

    public BigInteger getSerialNumber()
    {
        return certificate.getSerialNumber();
    }

    public String getSigAlgOID()
    {
        return certificate.getSigAlgOID();
    }

    DHPublicKey getPubKeyDH() throws IOException
    {
        try
        {
            return (DHPublicKey)getPublicKey();
        }
        catch (ClassCastException e)
        {
            throw new TlsFatalAlert(AlertDescription.certificate_unknown, e);
        }
    }

    DSAPublicKey getPubKeyDSS() throws IOException
    {
        try
        {
            return (DSAPublicKey)getPublicKey();
        }
        catch (ClassCastException e)
        {
            throw new TlsFatalAlert(AlertDescription.certificate_unknown, e);
        }
    }

    ECPublicKey getPubKeyEC() throws IOException
    {
        try
        {
            return (ECPublicKey)getPublicKey();
        }
        catch (ClassCastException e)
        {
            throw new TlsFatalAlert(AlertDescription.certificate_unknown, e);
        }
    }

    PublicKey getPubKeyEd25519() throws IOException
    {
        PublicKey publicKey = getPublicKey();
        if (!"Ed25519".equals(publicKey.getAlgorithm()))
        {
            throw new TlsFatalAlert(AlertDescription.certificate_unknown);
        }
        return publicKey;
    }

    PublicKey getPubKeyEd448() throws IOException
    {
        PublicKey publicKey = getPublicKey();
        if (!"Ed448".equals(publicKey.getAlgorithm()))
        {
            throw new TlsFatalAlert(AlertDescription.certificate_unknown);
        }
        return publicKey;
    }

    RSAPublicKey getPubKeyRSA() throws IOException
    {
        try
        {
            return (RSAPublicKey)getPublicKey();
        }
        catch (ClassCastException e)
        {
            throw new TlsFatalAlert(AlertDescription.certificate_unknown, e);
        }
    }

    public short getSignatureAlgorithm() throws IOException
    {
         PublicKey publicKey = getPublicKey();

         try
         {
             validateKeyUsage(KeyUsage.digitalSignature);
         }
         catch (IOException e)
         {
             throw e;
         }
         catch (Exception e)
         {
             throw new TlsFatalAlert(AlertDescription.unsupported_certificate, e);
         }

         /*
          * TODO RFC 5246 7.4.6. The certificates MUST be signed using an acceptable hash/
          * signature algorithm pair, as described in Section 7.4.4. Note that this relaxes the
          * constraints on certificate-signing algorithms found in prior versions of TLS.
          */

         /*
          * RFC 5246 7.4.6. Client Certificate
          */

         /*
          * RSA public key; the certificate MUST allow the key to be used for signing with the
          * signature scheme and hash algorithm that will be employed in the certificate verify
          * message.
          */
         if (publicKey instanceof RSAPublicKey)
         {
             return SignatureAlgorithm.rsa;
         }

         /*
          * DSA public key; the certificate MUST allow the key to be used for signing with the
          * hash algorithm that will be employed in the certificate verify message.
          */
         if (publicKey instanceof DSAPublicKey)
         {
             return SignatureAlgorithm.dsa;
         }

         /*
          * ECDSA-capable public key; the certificate MUST allow the key to be used for signing
          * with the hash algorithm that will be employed in the certificate verify message; the
          * public key MUST use a curve and point format supported by the server.
          */
         if (publicKey instanceof ECPublicKey)
         {
             // TODO Check the curve and point format
             return SignatureAlgorithm.ecdsa;
         }

         /*
          * Ed25519 public key; the certificate MUST allow the key to be used for signing
          */
         if ("Ed25519".equals(publicKey.getAlgorithm()))
         {
             return SignatureAlgorithm.ed25519;
         }

         /*
          * Ed448 public key; the certificate MUST allow the key to be used for signing
          */
         if ("Ed448".equals(publicKey.getAlgorithm()))
         {
             return SignatureAlgorithm.ed448;
         }

         throw new TlsFatalAlert(AlertDescription.unsupported_certificate);
    }

    public TlsCertificate useInRole(int connectionEnd, int keyExchangeAlgorithm) throws IOException
    {
        switch (keyExchangeAlgorithm)
        {
        case KeyExchangeAlgorithm.DH_DSS:
        case KeyExchangeAlgorithm.DH_RSA:
        {
            validateKeyUsage(KeyUsage.keyAgreement);
            this.pubKeyDH = getPubKeyDH();
            return this;
        }

        case KeyExchangeAlgorithm.ECDH_ECDSA:
        case KeyExchangeAlgorithm.ECDH_RSA:
        {
            validateKeyUsage(KeyUsage.keyAgreement);
            this.pubKeyEC = getPubKeyEC();
            return this;
        }
        }

        if (connectionEnd == ConnectionEnd.server)
        {
            switch (keyExchangeAlgorithm)
            {
            case KeyExchangeAlgorithm.RSA:
            case KeyExchangeAlgorithm.RSA_PSK:
            {
                validateKeyUsage(KeyUsage.keyEncipherment);
                this.pubKeyRSA = getPubKeyRSA();
                return this;
            }
            }
        }

        throw new TlsFatalAlert(AlertDescription.certificate_unknown);
    }

    protected PublicKey getPublicKey() throws IOException
    {
        try
        {
            return certificate.getPublicKey();
        }
        catch (RuntimeException e)
        {
            throw new TlsFatalAlert(AlertDescription.unsupported_certificate, e);
        }
    }

    public X509Certificate getX509Certificate()
    {
        return certificate;
    }

    protected void validateKeyUsage(int keyUsageBits)
        throws IOException
    {
        Extensions exts;
        try
        {
            exts = TBSCertificate.getInstance(certificate.getTBSCertificate()).getExtensions();
        }
        catch (CertificateEncodingException e)
        {
            throw new TlsCryptoException("unable to parse certificate extensions: " + e.getMessage(), e);
        }

        if (exts != null)
        {
            KeyUsage ku = KeyUsage.fromExtensions(exts);
            if (ku != null)
            {
                int bits = ku.getBytes()[0] & 0xff;
                if ((bits & keyUsageBits) != keyUsageBits)
                {
                    throw new TlsFatalAlert(AlertDescription.certificate_unknown);
                }
            }
        }
    }
}
