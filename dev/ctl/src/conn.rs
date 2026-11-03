//! One connection to devd: TLS 1.3, our client certificate, devd's
//! certificate pinned by SHA-256 (or recorded, while pairing), then
//! length-prefixed JSON frames.

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, ring as provider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme, StreamOwned};
use serde_json::{Value, json};

use crate::store::Identity;

/// The largest reply accepted (devd's frame limit).
const MAX_FRAME: usize = 4 * 1024 * 1024;

pub fn sha256_hex(bytes: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, bytes)
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// The six-digit code devd shows while pairing (see devd's Tls.pairingCode).
pub fn pairing_code(server_cert: &[u8], client_cert: &[u8]) -> String {
    let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
    ctx.update(server_cert);
    ctx.update(client_cert);
    let d = ctx.finish();
    let n = u32::from_be_bytes([d.as_ref()[0], d.as_ref()[1], d.as_ref()[2], d.as_ref()[3]]);
    format!("{:06}", n % 1_000_000)
}

#[derive(Debug)]
struct Pinned {
    expected: Option<String>,
    seen: Mutex<Option<Vec<u8>>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let got = sha256_hex(end_entity);
        if let Some(want) = &self.expected
            && *want != got
        {
            return Err(rustls::Error::General(format!(
                "devd presented certificate {got}, but {want} is pinned for this address. If devd \
                 was reinstalled, pair again with --repin; otherwise something is in the middle."
            )));
        }
        *self.seen.lock().unwrap_or_else(|e| e.into_inner()) = Some(end_entity.to_vec());
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(rustls::Error::General("TLS 1.2 is not used".into()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

pub struct Conn {
    stream: StreamOwned<ClientConnection, TcpStream>,
    next_id: u64,
    pub server_cert: Vec<u8>,
}

/// An error reply from devd, or a local failure.
#[derive(Debug)]
pub struct CallError {
    pub code: String,
    pub message: String,
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

fn local(message: impl Into<String>) -> CallError {
    CallError { code: "local".into(), message: message.into() }
}

impl Conn {
    /// Connects to `addr`. With `pin` None, any devd certificate is accepted
    /// and recorded (pairing); otherwise it must match.
    pub fn open(addr: &str, id: &Identity, pin: Option<String>) -> Result<Conn, CallError> {
        let provider = Arc::new(provider::default_provider());
        let verifier =
            Arc::new(Pinned { expected: pin, seen: Mutex::new(None), provider: provider.clone() });
        let config = ClientConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|e| local(format!("TLS setup: {e}")))?
            .dangerous()
            .with_custom_certificate_verifier(verifier.clone())
            .with_client_auth_cert(
                vec![CertificateDer::from(id.cert_der.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(id.key_der.clone())),
            )
            .map_err(|e| local(format!("TLS client certificate: {e}")))?;
        let sockaddr = addr
            .to_socket_addrs()
            .map_err(|e| local(format!("{addr}: {e}")))?
            .next()
            .ok_or_else(|| local(format!("{addr}: no address")))?;
        let tcp = TcpStream::connect_timeout(&sockaddr, Duration::from_secs(10)).map_err(|e| {
            local(format!("cannot connect to {addr}: {e} (is devd's listener on?)"))
        })?;
        tcp.set_read_timeout(Some(Duration::from_secs(15 * 60))).ok();
        tcp.set_nodelay(true).ok();
        let name = ServerName::try_from("koi-devd").map_err(|e| local(e.to_string()))?;
        let conn = ClientConnection::new(Arc::new(config), name)
            .map_err(|e| local(format!("TLS: {e}")))?;
        let mut stream = StreamOwned::new(conn, tcp);
        while stream.conn.is_handshaking() {
            stream
                .conn
                .complete_io(&mut stream.sock)
                .map_err(|e| local(format!("TLS handshake with {addr} failed: {e}")))?;
        }
        let server_cert = verifier
            .seen
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .ok_or_else(|| local("no server certificate"))?;
        Ok(Conn { stream, next_id: 1, server_cert })
    }

    /// Sets the socket read timeout, e.g. for bounded recovery polling.
    pub fn set_read_timeout(&mut self, d: Duration) {
        let _ = self.stream.sock.set_read_timeout(Some(d));
    }

    /// Calls `method` and returns its result, or devd's error.
    pub fn call(&mut self, method: &str, params: Value) -> Result<Value, CallError> {
        self.send(method, params)?;
        self.recv()
    }

    /// Sends a request without waiting for its reply. devd answers the
    /// requests on one connection in order, so several can be in flight; read
    /// each reply with [`Conn::recv`].
    pub fn send(&mut self, method: &str, params: Value) -> Result<(), CallError> {
        let id = self.next_id;
        self.next_id += 1;
        let req = json!({ "id": id, "method": method, "params": params });
        let bytes = serde_json::to_vec(&req).map_err(|e| local(e.to_string()))?;
        let len = u32::try_from(bytes.len()).map_err(|_| local("request too large"))?;
        let io = |e: std::io::Error| local(format!("connection: {e}"));
        self.stream.write_all(&len.to_be_bytes()).map_err(io)?;
        self.stream.write_all(&bytes).map_err(io)?;
        self.stream.flush().map_err(io)
    }

    /// Reads the next reply: its result, or devd's error.
    pub fn recv(&mut self) -> Result<Value, CallError> {
        let io = |e: std::io::Error| local(format!("connection: {e}"));
        let mut hdr = [0u8; 4];
        self.stream.read_exact(&mut hdr).map_err(io)?;
        let n = u32::from_be_bytes(hdr) as usize;
        if n > MAX_FRAME {
            return Err(local(format!("reply of {n} bytes is too large")));
        }
        let mut buf = vec![0u8; n];
        self.stream.read_exact(&mut buf).map_err(io)?;
        let mut reply: Value =
            serde_json::from_slice(&buf).map_err(|e| local(format!("bad reply: {e}")))?;
        if let Some(err) = reply.get("error") {
            return Err(CallError {
                code: err["code"].as_str().unwrap_or("error").to_string(),
                message: err["message"].as_str().unwrap_or("").to_string(),
            });
        }
        Ok(reply["result"].take())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_known_vector() {
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn pairing_code_is_six_digits_and_order_sensitive() {
        let a = pairing_code(b"server", b"client");
        let b = pairing_code(b"client", b"server");
        assert_eq!(a.len(), 6);
        assert!(a.chars().all(|c| c.is_ascii_digit()));
        assert_ne!(a, b);
    }
}

#[cfg(test)]
mod transport_tests {
    use super::*;
    use crate::store::Identity;
    use rcgen::generate_simple_self_signed;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    use rustls::{ServerConfig, ServerConnection, StreamOwned};
    use std::net::TcpListener;
    use std::thread;

    fn server() -> (std::net::SocketAddr, String, std::thread::JoinHandle<()>) {
        let cert = generate_simple_self_signed(vec!["koi-devd".into()]).unwrap();
        let cert_der = cert.cert.der().to_vec();
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der()));
        let config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![CertificateDer::from(cert_der.clone())], key)
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let pin = sha256_hex(&cert_der);
        let handle = thread::spawn(move || {
            let (sock, _) = listener.accept().unwrap();
            let conn = ServerConnection::new(std::sync::Arc::new(config)).unwrap();
            let mut tls = StreamOwned::new(conn, sock);
            let mut hdr = [0u8; 4];
            tls.read_exact(&mut hdr).unwrap();
            let n = u32::from_be_bytes(hdr) as usize;
            let mut buf = vec![0u8; n];
            tls.read_exact(&mut buf).unwrap();
            let req: serde_json::Value = serde_json::from_slice(&buf).unwrap();
            let reply = serde_json::json!({"id": req["id"], "result": {"ok": true}});
            let bytes = serde_json::to_vec(&reply).unwrap();
            tls.write_all(&(bytes.len() as u32).to_be_bytes()).unwrap();
            tls.write_all(&bytes).unwrap();
            tls.flush().unwrap();
        });
        (addr, pin, handle)
    }

    fn identity() -> Identity {
        let cert = generate_simple_self_signed(vec!["laptop".into()]).unwrap();
        Identity { cert_der: cert.cert.der().to_vec(), key_der: cert.signing_key.serialize_der() }
    }

    #[test]
    fn pinned_tls_round_trip() {
        let (addr, pin, handle) = server();
        let mut c =
            Conn::open(&format!("127.0.0.1:{}", addr.port()), &identity(), Some(pin)).unwrap();
        let v = c.call("devd.hello", serde_json::json!({})).unwrap();
        assert_eq!(v["ok"], true);
        handle.join().unwrap();
    }

    #[test]
    fn wrong_pin_is_refused() {
        let (addr, _pin, handle) = server();
        let err = match Conn::open(
            &format!("127.0.0.1:{}", addr.port()),
            &identity(),
            Some("00".repeat(32)),
        ) {
            Ok(_) => panic!("a wrong pin must be refused"),
            Err(e) => e,
        };
        assert!(err.message.contains("pinned"), "{err}");
        let _ = handle.join();
    }
}
