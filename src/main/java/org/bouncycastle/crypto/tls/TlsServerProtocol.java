package org.bouncycastle.crypto.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

public class TlsServerProtocol extends TlsProtocol {

    protected TlsServer tlsServer = null;
    protected TlsServerContextImpl tlsServerContext = null;

    protected TlsKeyExchange keyExchange = null;
    protected CertificateRequest certificateRequest = null;

    public TlsServerProtocol(InputStream is, OutputStream os, SecureRandom sr) {
        super(is, os, sr);
    }

    /**
     * Receives a TLS handshake in the role of server
     * 
     * @param tlsServer
     * @throws IOException
     *             If handshake was not successful.
     */
    public void accept(TlsServer tlsServer) throws IOException {

        if (tlsServer == null) {
            throw new IllegalArgumentException("'tlsServer' cannot be null");
        }
        if (this.tlsServer != null) {
            throw new IllegalStateException("accept can only be called once");
        }

        this.tlsServer = tlsServer;

        this.securityParameters = new SecurityParameters();
        this.securityParameters.serverRandom = new byte[32];
        random.nextBytes(securityParameters.serverRandom);
        TlsUtils.writeGMTUnixTime(securityParameters.serverRandom, 0);

        this.tlsServerContext = new TlsServerContextImpl(random, securityParameters);
        this.tlsServer.init(tlsServerContext);
        this.rs.init(tlsServerContext);

        /*
         * We will now read data, until we have completed the handshake.
         */
        while (this.connection_state != CS_SERVER_FINISHED) {
            safeReadRecord();
        }

        enableApplicationData();
    }

    protected TlsContext getContext() {
        return tlsServerContext;
    }

    protected void handleChangeCipherSpecMessage() throws IOException {

        switch (this.connection_state) {
        case CS_CLIENT_KEY_EXCHANGE: {
            skipCertificateVerifyMessage();
            // NB: Fall through to next case label
        }
        case CS_CERTIFICATE_VERIFY: {

            // TODO Make sure that rs.decidedWriteCipherSpec is called before this
            rs.receivedReadCipherSpec();

            this.connection_state = CS_SERVER_CHANGE_CIPHER_SPEC;
            break;
        }
        default: {
            this.failWithError(AlertLevel.fatal, AlertDescription.handshake_failure);
        }
        }
    }

    protected void handleHandshakeMessage(short type, byte[] data) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(data);

        switch (type) {
        case HandshakeType.client_hello: {
            switch (this.connection_state) {
            case CS_START: {
                receiveClientHelloMessage(buf);
                this.connection_state = CS_CLIENT_HELLO;

                // TODO Send ServerHello
                this.connection_state = CS_SERVER_HELLO;

                // TODO Will probably need to be moved
                this.keyExchange = tlsServer.getKeyExchange();

                TlsCredentials serverCredentials = tlsServer.getCredentials();
                if (serverCredentials == null) {
                    this.keyExchange.skipServerCertificate();
                } else {
                    Certificate serverCertificate = serverCredentials.getCertificate();
                    this.keyExchange.processServerCertificate(serverCertificate);
                    sendCertificateMessage(serverCertificate);
                }
                this.connection_state = CS_SERVER_CERTIFICATE;

                // TODO Send ServerKeyExchange
                this.connection_state = CS_SERVER_KEY_EXCHANGE;

                if (serverCredentials != null) {
                    this.certificateRequest = tlsServer.getCertificateRequest();
                    if (this.certificateRequest != null) {
                        this.keyExchange.validateCertificateRequest(certificateRequest);
                        sendCertificateRequestMessage(certificateRequest);
                    }
                }
                this.connection_state = CS_CERTIFICATE_REQUEST;

                sendServerHelloDoneMessage();
                this.connection_state = CS_SERVER_HELLO_DONE;

                break;
            }
            default: {
                this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
            }
            }
            break;
        }
        case HandshakeType.certificate: {
            switch (this.connection_state) {
            case CS_SERVER_HELLO_DONE: {
                if (this.certificateRequest == null) {
                    this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
                }
                receiveCertificateMessage(buf);
                this.connection_state = CS_CLIENT_CERTIFICATE;
                break;
            }
            default: {
                this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
            }
            }
            break;
        }
        case HandshakeType.client_key_exchange: {
            switch (this.connection_state) {
            case CS_SERVER_HELLO_DONE: {
                this.keyExchange.skipClientCredentials();
                // NB: Fall through to next case label
            }
            case CS_CLIENT_CERTIFICATE: {
                receiveClientKeyExchangeMessage(buf);
                this.connection_state = CS_CLIENT_KEY_EXCHANGE;
                break;
            }
            default: {
                this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
            }
            }
            break;
        }
        case HandshakeType.certificate_verify: {
            switch (this.connection_state) {
            case CS_CLIENT_KEY_EXCHANGE: {
                // TODO Check there is a client Certificate with signing capability
                receiveCertificateVerifyMessage(buf);
                this.connection_state = CS_CERTIFICATE_VERIFY;
                break;
            }
            default: {
                this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
            }
            }
            break;
        }
        case HandshakeType.finished: {
            switch (this.connection_state) {
            case CS_CLIENT_CHANGE_CIPHER_SPEC:
                processFinishedMessage(buf);
                this.connection_state = CS_CLIENT_FINISHED;

                sendChangeCipherSpecMessage();
                this.connection_state = CS_SERVER_CHANGE_CIPHER_SPEC;

                sendFinishedMessage();
                this.connection_state = CS_SERVER_FINISHED;
                break;
            default:
                this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
            }
            break;
        }
        case HandshakeType.hello_request:
        case HandshakeType.hello_verify_request:
        case HandshakeType.server_hello:
        case HandshakeType.server_key_exchange:
        case HandshakeType.certificate_request:
        case HandshakeType.server_hello_done:
        default:
            // We do not support this!
            this.failWithError(AlertLevel.fatal, AlertDescription.unexpected_message);
            break;
        }
    }

    protected void handleWarningMessage(short description) {
        switch (description) {
        case AlertDescription.no_certificate: {
            if (tlsServerContext.getServerVersion().isSSL()) {
                // TODO In SSLv3, this is an alternative to client Certificate message
            }
            break;
        }
        default: {
            super.handleWarningMessage(description);
        }
        }
    }

    protected void receiveCertificateMessage(ByteArrayInputStream buf) throws IOException {

        Certificate clientCertificate = Certificate.parse(buf);

        assertEmpty(buf);

        // TODO
//        this.keyExchange.processClientCredentials(clientCredentials);
    }

    protected void receiveCertificateVerifyMessage(ByteArrayInputStream buf) throws IOException {
        // TODO

        assertEmpty(buf);
    }

    protected void receiveClientHelloMessage(ByteArrayInputStream buf) throws IOException {
        // TODO

        assertEmpty(buf);
    }

    protected void receiveClientKeyExchangeMessage(ByteArrayInputStream buf) throws IOException {
        // TODO

        assertEmpty(buf);
    }

    protected void sendCertificateRequestMessage(CertificateRequest certificateRequest)
        throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TlsUtils.writeUint8(HandshakeType.certificate_request, buf);

        // Reserve space for length
        TlsUtils.writeUint24(0, buf);

        certificateRequest.encode(buf);
        byte[] message = buf.toByteArray();

        // Patch actual length back in
        TlsUtils.writeUint24(message.length - 4, message, 1);

        safeWriteRecord(ContentType.handshake, message, 0, message.length);
    }

    protected void sendServerHelloDoneMessage() throws IOException {

        byte[] message = new byte[4];
        TlsUtils.writeUint8(HandshakeType.server_hello_done, message, 0);
        TlsUtils.writeUint24(0, message, 1);

        safeWriteRecord(ContentType.handshake, message, 0, message.length);
    }

    protected void skipCertificateVerifyMessage() {
        // TODO
    }
}
