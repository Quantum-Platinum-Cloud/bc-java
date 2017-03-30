package org.bouncycastle.tls;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.tls.crypto.DHGroup;
import org.bouncycastle.tls.crypto.DHStandardGroups;
import org.bouncycastle.tls.crypto.TlsCipher;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.TlsDHConfig;
import org.bouncycastle.tls.crypto.TlsECConfig;
import org.bouncycastle.util.Arrays;

public abstract class AbstractTlsServer
    extends AbstractTlsPeer
    implements TlsServer
{
    protected TlsKeyExchangeFactory keyExchangeFactory;

    protected TlsServerContext context;

    protected ProtocolVersion clientVersion;
    protected int[] offeredCipherSuites;
    protected short[] offeredCompressionMethods;
    protected Hashtable clientExtensions;

    protected boolean encryptThenMACOffered;
    protected short maxFragmentLengthOffered;
    protected boolean truncatedHMacOffered;
    protected Vector supportedSignatureAlgorithms;
    protected int[] namedCurves;
    protected short[] clientECPointFormats, serverECPointFormats;

    protected ProtocolVersion serverVersion;
    protected int selectedCipherSuite;
    protected short selectedCompressionMethod;
    protected Hashtable serverExtensions;

    public AbstractTlsServer(TlsCrypto crypto)
    {
        this(crypto, new DefaultTlsKeyExchangeFactory());
    }

    public AbstractTlsServer(TlsCrypto crypto, TlsKeyExchangeFactory keyExchangeFactory)
    {
        super(crypto);

        this.keyExchangeFactory = keyExchangeFactory;
    }

    protected boolean allowEncryptThenMAC()
    {
        return true;
    }

    protected boolean allowTruncatedHMac()
    {
        return false;
    }

    protected Hashtable checkServerExtensions()
    {
        return this.serverExtensions = TlsExtensionsUtils.ensureExtensionsInitialised(this.serverExtensions);
    }

    protected abstract int[] getCipherSuites();

    protected short[] getCompressionMethods()
    {
        return new short[]{CompressionMethod._null};
    }

    protected DHGroup getDHParameters()
    {
        return DHStandardGroups.rfc3526_2048;
    }

    protected ProtocolVersion getMaximumVersion()
    {
        return ProtocolVersion.TLSv12;
    }

    protected ProtocolVersion getMinimumVersion()
    {
        return ProtocolVersion.TLSv10;
    }

    protected int getMaximumNegotiableCurveBits()
    {
        // NOTE: BC supports all the current set of point formats so we don't check them here

        if (namedCurves == null)
        {
            /*
             * RFC 4492 4. A client that proposes ECC cipher suites may choose not to include these
             * extensions. In this case, the server is free to choose any one of the elliptic curves
             * or point formats [...].
             */
            return NamedCurve.getMaximumCurveBits();
        }

        int maxBits = 0;
        for (int i = 0; i < namedCurves.length; ++i)
        {
            maxBits = Math.max(maxBits, NamedCurve.getCurveBits(namedCurves[i]));
        }
        return maxBits;
    }

    protected int selectCurve(int minimumCurveBits)
    {
        if (namedCurves == null)
        {
            return selectDefaultCurve(minimumCurveBits);
        }

        // Try to find a supported named curve of the required size from the client's list.
        for (int i = 0; i < namedCurves.length; ++i)
        {
            int namedCurve = namedCurves[i];
            if (NamedCurve.getCurveBits(namedCurve) >= minimumCurveBits)
            {
                return namedCurve;
            }
        }

        return -1;
    }

    protected int selectDefaultCurve(int minimumCurveBits)
    {
        // Note: this must all have a co-factor of 1 to qualify for FIPS ECDH.
        return minimumCurveBits <= 256 ? NamedCurve.secp256r1
            :  minimumCurveBits <= 384 ? NamedCurve.secp384r1
            :  minimumCurveBits <= 521 ? NamedCurve.secp521r1
            :  -1;
    }

    protected TlsDHConfig selectDHConfig()
    {
        return TlsDHUtils.selectDHConfig(getDHParameters()); 
    }

    protected TlsECConfig selectECConfig() throws IOException
    {
        int minimumCurveBits = TlsECCUtils.getMinimumCurveBits(selectedCipherSuite);

        int namedCurve = selectCurve(minimumCurveBits);
        if (namedCurve < 0)
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        boolean compressed = TlsECCUtils.isCompressionPreferred(clientECPointFormats, namedCurve);

        TlsECConfig ecConfig = new TlsECConfig();
        ecConfig.setNamedCurve(namedCurve);
        ecConfig.setPointCompression(compressed);
        return ecConfig;
    }
    
    public void init(TlsServerContext context)
    {
        this.context = context;
    }

    public void notifyClientVersion(ProtocolVersion clientVersion)
        throws IOException
    {
        this.clientVersion = clientVersion;
    }

    public void notifyFallback(boolean isFallback) throws IOException
    {
        /*
         * RFC 7507 3. If TLS_FALLBACK_SCSV appears in ClientHello.cipher_suites and the highest
         * protocol version supported by the server is higher than the version indicated in
         * ClientHello.client_version, the server MUST respond with a fatal inappropriate_fallback
         * alert [..].
         */
        if (isFallback && getMaximumVersion().isLaterVersionOf(clientVersion))
        {
            throw new TlsFatalAlert(AlertDescription.inappropriate_fallback);
        }
    }

    public void notifyOfferedCipherSuites(int[] offeredCipherSuites)
        throws IOException
    {
        this.offeredCipherSuites = offeredCipherSuites;
    }

    public void notifyOfferedCompressionMethods(short[] offeredCompressionMethods)
        throws IOException
    {
        this.offeredCompressionMethods = offeredCompressionMethods;
    }

    public void processClientExtensions(Hashtable clientExtensions)
        throws IOException
    {
        this.clientExtensions = clientExtensions;

        if (clientExtensions != null)
        {
            this.encryptThenMACOffered = TlsExtensionsUtils.hasEncryptThenMACExtension(clientExtensions);

            this.maxFragmentLengthOffered = TlsExtensionsUtils.getMaxFragmentLengthExtension(clientExtensions);
            if (maxFragmentLengthOffered >= 0 && !MaxFragmentLength.isValid(maxFragmentLengthOffered))
            {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }

            this.truncatedHMacOffered = TlsExtensionsUtils.hasTruncatedHMacExtension(clientExtensions);

            this.supportedSignatureAlgorithms = TlsUtils.getSignatureAlgorithmsExtension(clientExtensions);
            if (this.supportedSignatureAlgorithms != null)
            {
                /*
                 * RFC 5246 7.4.1.4.1. Note: this extension is not meaningful for TLS versions prior
                 * to 1.2. Clients MUST NOT offer it if they are offering prior versions.
                 */
                if (!TlsUtils.isSignatureAlgorithmsExtensionAllowed(clientVersion))
                {
                    throw new TlsFatalAlert(AlertDescription.illegal_parameter);
                }
            }

            this.namedCurves = TlsECCUtils.getSupportedEllipticCurvesExtension(clientExtensions);
            this.clientECPointFormats = TlsECCUtils.getSupportedPointFormatsExtension(clientExtensions);
        }

        /*
         * RFC 4429 4. The client MUST NOT include these extensions in the ClientHello message if it
         * does not propose any ECC cipher suites.
         * 
         * NOTE: This was overly strict as there may be ECC cipher suites that we don't recognize.
         * Also, draft-ietf-tls-negotiated-ff-dhe will be overloading the 'elliptic_curves'
         * extension to explicitly allow FFDHE (i.e. non-ECC) groups.
         */
//        if (!this.eccCipherSuitesOffered && (this.namedCurves != null || this.clientECPointFormats != null))
//        {
//            throw new TlsFatalAlert(AlertDescription.illegal_parameter);
//        }
    }

    public ProtocolVersion getServerVersion()
        throws IOException
    {
        if (getMinimumVersion().isEqualOrEarlierVersionOf(clientVersion))
        {
            ProtocolVersion maximumVersion = getMaximumVersion();
            if (clientVersion.isEqualOrEarlierVersionOf(maximumVersion))
            {
                return serverVersion = clientVersion;
            }
            if (clientVersion.isLaterVersionOf(maximumVersion))
            {
                return serverVersion = maximumVersion;
            }
        }
        throw new TlsFatalAlert(AlertDescription.protocol_version);
    }

    public int getSelectedCipherSuite()
        throws IOException
    {
        /*
         * RFC 5246 7.4.3. In order to negotiate correctly, the server MUST check any candidate
         * cipher suites against the "signature_algorithms" extension before selecting them. This is
         * somewhat inelegant but is a compromise designed to minimize changes to the original
         * cipher suite design.
         */
        Vector sigAlgs = TlsUtils.getUsableSignatureAlgorithms(supportedSignatureAlgorithms);

        /*
         * RFC 4429 5.1. A server that receives a ClientHello containing one or both of these
         * extensions MUST use the client's enumerated capabilities to guide its selection of an
         * appropriate cipher suite. One of the proposed ECC cipher suites must be negotiated only
         * if the server can successfully complete the handshake while using the curves and point
         * formats supported by the client [...].
         */
        int availCurveBits = getMaximumNegotiableCurveBits();

        int[] cipherSuites = getCipherSuites();
        for (int i = 0; i < cipherSuites.length; ++i)
        {
            int cipherSuite = cipherSuites[i];

            if (Arrays.contains(this.offeredCipherSuites, cipherSuite)
                && TlsUtils.isValidCipherSuiteForVersion(cipherSuite, serverVersion)
                && availCurveBits >= TlsECCUtils.getMinimumCurveBits(cipherSuite)
                && TlsUtils.isValidCipherSuiteForSignatureAlgorithms(cipherSuite, sigAlgs))
            {
                return this.selectedCipherSuite = cipherSuite;
            }
        }
        throw new TlsFatalAlert(AlertDescription.handshake_failure);
    }

    public short getSelectedCompressionMethod()
        throws IOException
    {
        short[] compressionMethods = getCompressionMethods();
        for (int i = 0; i < compressionMethods.length; ++i)
        {
            if (Arrays.contains(offeredCompressionMethods, compressionMethods[i]))
            {
                return this.selectedCompressionMethod = compressionMethods[i];
            }
        }
        throw new TlsFatalAlert(AlertDescription.handshake_failure);
    }

    // Hashtable is (Integer -> byte[])
    public Hashtable getServerExtensions()
        throws IOException
    {
        if (this.encryptThenMACOffered && allowEncryptThenMAC())
        {
            /*
             * RFC 7366 3. If a server receives an encrypt-then-MAC request extension from a client
             * and then selects a stream or Authenticated Encryption with Associated Data (AEAD)
             * ciphersuite, it MUST NOT send an encrypt-then-MAC response extension back to the
             * client.
             */
            if (TlsUtils.isBlockCipherSuite(this.selectedCipherSuite))
            {
                TlsExtensionsUtils.addEncryptThenMACExtension(checkServerExtensions());
            }
        }

        if (this.maxFragmentLengthOffered >= 0 && MaxFragmentLength.isValid(maxFragmentLengthOffered))
        {
            TlsExtensionsUtils.addMaxFragmentLengthExtension(checkServerExtensions(), this.maxFragmentLengthOffered);
        }

        if (this.truncatedHMacOffered && allowTruncatedHMac())
        {
            TlsExtensionsUtils.addTruncatedHMacExtension(checkServerExtensions());
        }

        if (this.clientECPointFormats != null && TlsECCUtils.isECCipherSuite(this.selectedCipherSuite))
        {
            /*
             * RFC 4492 5.2. A server that selects an ECC cipher suite in response to a ClientHello
             * message including a Supported Point Formats Extension appends this extension (along
             * with others) to its ServerHello message, enumerating the point formats it can parse.
             */
            this.serverECPointFormats = new short[]{ ECPointFormat.uncompressed,
                ECPointFormat.ansiX962_compressed_prime, ECPointFormat.ansiX962_compressed_char2, };

            TlsECCUtils.addSupportedPointFormatsExtension(checkServerExtensions(), serverECPointFormats);
        }

        return serverExtensions;
    }

    public Vector getServerSupplementalData()
        throws IOException
    {
        return null;
    }

    public CertificateStatus getCertificateStatus()
        throws IOException
    {
        return null;
    }

    public CertificateRequest getCertificateRequest()
        throws IOException
    {
        return null;
    }

    public void processClientSupplementalData(Vector clientSupplementalData)
        throws IOException
    {
        if (clientSupplementalData != null)
        {
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }
    }

    public void notifyClientCertificate(Certificate clientCertificate)
        throws IOException
    {
        throw new TlsFatalAlert(AlertDescription.internal_error);
    }

    public TlsCompression getCompression()
        throws IOException
    {
        switch (selectedCompressionMethod)
        {
        case CompressionMethod._null:
            return new TlsNullCompression();

        default:
            /*
             * Note: internal error here; we selected the compression method, so if we now can't
             * produce an implementation, we shouldn't have chosen it!
             */
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
    }

    public TlsCipher getCipher()
        throws IOException
    {
        int encryptionAlgorithm = TlsUtils.getEncryptionAlgorithm(selectedCipherSuite);
        int macAlgorithm = TlsUtils.getMACAlgorithm(selectedCipherSuite);

        if (encryptionAlgorithm < 0 || macAlgorithm < 0)
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        return context.getSecurityParameters().getMasterSecret().createCipher(new TlsCryptoParameters(context), encryptionAlgorithm, macAlgorithm);
    }

    public NewSessionTicket getNewSessionTicket()
        throws IOException
    {
        /*
         * RFC 5077 3.3. If the server determines that it does not want to include a ticket after it
         * has included the SessionTicket extension in the ServerHello, then it sends a zero-length
         * ticket in the NewSessionTicket handshake message.
         */
        return new NewSessionTicket(0L, TlsUtils.EMPTY_BYTES);
    }
}
