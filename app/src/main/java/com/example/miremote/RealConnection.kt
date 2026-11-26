package com.example.miremote
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private class PbField(val num: Int, val value: Long, val bytes: ByteArray?)

private object Pb {
    fun varint(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (x and 0x7F.inv() != 0) {
            out.write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
        out.write(x)
        return out.toByteArray()
    }

    fun field(num: Int, bytes: ByteArray): ByteArray =
        varint((num shl 3) or 2) + varint(bytes.size) + bytes

    fun fieldInt(num: Int, v: Int): ByteArray =
        varint(num shl 3) + varint(v)

    fun parse(b: ByteArray): List<PbField> {
        val out = mutableListOf<PbField>()
        var i = 0
        fun readVarint(): Long {
            var r = 0L
            var shift = 0
            while (i < b.size) {
                val x = b[i++].toInt()
                r = r or ((x and 0x7F).toLong() shl shift)
                if ((x and 0x80) == 0) break
                shift += 7
            }
            return r
        }
        while (i < b.size) {
            val tag = readVarint().toInt()
            when (tag and 7) {
                0 -> out.add(PbField(tag shr 3, readVarint(), null))
                2 -> {
                    val len = readVarint().toInt()
                    out.add(PbField(tag shr 3, 0L, b.copyOfRange(i, i + len)))
                    i += len
                }
                1 -> i += 8
                5 -> i += 4
                else -> return out
            }
        }
        return out
    }
}

class RealConnection(context: Context) : TvConnection {

    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val writeLock = Any()
    private val pass = "pw".toCharArray()

    private val identity: Pair<PrivateKey, X509Certificate> = loadOrCreateIdentity()

    @Volatile private var ip: String = ""
    @Volatile private var pairingSocket: SSLSocket? = null
    @Volatile private var serverCert: X509Certificate? = null
    @Volatile private var remoteSocket: SSLSocket? = null
    @Volatile private var remoteOut: OutputStream? = null

    // ---------- API ----------

    override suspend fun connect(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            this@RealConnection.ip = ip.trim()
            closeQuietly(pairingSocket)
            closeQuietly(remoteSocket)
            remoteSocket = null
            remoteOut = null

            val s = sslSocket(6467, 15000)
            pairingSocket = s
            serverCert = s.session.peerCertificates[0] as X509Certificate
            val out = s.outputStream
            val inp = s.inputStream

            // 1) pairing request
            writeMsg(
                out,
                pairingMsg(10, Pb.field(1, "atvremote".toByteArray()) + Pb.field(2, "MiRemote".toByteArray()))
            )
            if (!expectOk(inp)) return@withContext false

            // 2) options (hex, 6 chars, role = input)
            val encoding = Pb.fieldInt(1, 3) + Pb.fieldInt(2, 6)
            writeMsg(out, pairingMsg(20, Pb.field(1, encoding) + Pb.fieldInt(3, 1)))
            if (!expectOk(inp)) return@withContext false

            // 3) configuration
            writeMsg(out, pairingMsg(30, Pb.field(1, encoding) + Pb.fieldInt(2, 1)))
            if (!expectOk(inp)) return@withContext false

            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun submitPairingCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = pairingSocket ?: return@withContext false
            val cert = serverCert ?: return@withContext false
            val clean = code.trim()
            if (clean.length != 6 || !clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
                return@withContext false
            }
            val bytes = ByteArray(3) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

            val clientPub = identity.second.publicKey as RSAPublicKey
            val serverPub = cert.publicKey as RSAPublicKey
            val md = MessageDigest.getInstance("SHA-256")
            md.update(unsigned(clientPub.modulus))
            md.update(unsigned(clientPub.publicExponent))
            md.update(unsigned(serverPub.modulus))
            md.update(unsigned(serverPub.publicExponent))
            md.update(bytes.copyOfRange(1, 3))
            val hash = md.digest()
            if (hash[0] != bytes[0]) return@withContext false

            writeMsg(s.outputStream, pairingMsg(40, Pb.field(1, hash)))
            val resp = readMsg(s.inputStream) ?: return@withContext false
            if (!statusOk(resp)) return@withContext false

            closeQuietly(s)
            pairingSocket = null
            openRemote()
        } catch (e: Exception) {
            false
        }
    }

    override fun sendKey(keyCode: Int) {
        io.execute {
            try {
                if (remoteOut == null && ip.isNotEmpty()) openRemote()
                val out = remoteOut ?: return@execute
                writeMsg(out, Pb.field(10, Pb.fieldInt(1, keyCode) + Pb.fieldInt(2, 3)))
            } catch (e: Exception) {
                remoteOut = null
            }
        }
    }

    // ---------- Remote session (port 6466) ----------

    private fun openRemote(): Boolean {
        return try {
            closeQuietly(remoteSocket)
            val s = sslSocket(6466, 0)
            remoteSocket = s
            remoteOut = s.outputStream
            Thread { remoteLoop(s) }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            remoteOut = null
            false
        }
    }

    private fun remoteLoop(s: SSLSocket) {
        try {
            val inp = s.inputStream
            val out = s.outputStream
            while (true) {
                val msg = readMsg(inp) ?: break
                for (f in Pb.parse(msg)) {
                    when (f.num) {
                        1 -> { // configure
                            val info = Pb.field(1, "MiRemote".toByteArray()) +
                                Pb.field(2, "Tekkpoint".toByteArray()) +
                                Pb.fieldInt(3, 1) +
                                Pb.field(4, "1".toByteArray()) +
                                Pb.field(5, "tekkpoint.remote".toByteArray()) +
                                Pb.field(6, "1.0.0".toByteArray())
                            writeMsg(out, Pb.field(1, Pb.fieldInt(1, 622) + Pb.field(2, info)))
                        }
                        2 -> { // set active
                            writeMsg(out, Pb.field(2, Pb.fieldInt(1, 622)))
                        }
                        8 -> { // ping
                            val v = f.bytes?.let { Pb.parse(it) }?.firstOrNull { it.num == 1 }?.value ?: 0L
                            writeMsg(out, Pb.field(9, Pb.fieldInt(1, v.toInt())))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // connexion tسكّرت
        } finally {
            if (remoteSocket === s) remoteOut = null
            closeQuietly(s)
        }
    }

    // ---------- Pairing helpers ----------

    private fun pairingMsg(fieldNum: Int, inner: ByteArray): ByteArray =
        Pb.fieldInt(1, 2) + Pb.fieldInt(2, 200) + Pb.field(fieldNum, inner)

    private fun statusOk(msg: ByteArray): Boolean =
        Pb.parse(msg).any { it.num == 2 && it.value == 200L }

    private fun expectOk(inp: InputStream): Boolean {
        val m = readMsg(inp) ?: return false
        return statusOk(m)
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    // ---------- IO helpers ----------

    private fun readMsg(inp: InputStream): ByteArray? {
        var len = 0
        var shift = 0
        while (true) {
            val x = inp.read()
            if (x < 0) return null
            len = len or ((x and 0x7F) shl shift)
            if ((x and 0x80) == 0) break
            shift += 7
        }
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) return null
            off += n
        }
        return buf
    }

    private fun writeMsg(out: OutputStream, msg: ByteArray) {
        synchronized(writeLock) {
            out.write(Pb.varint(msg.size) + msg)
            out.flush()
        }
    }

    private fun closeQuietly(s: Socket?) {
        try { s?.close() } catch (e: Exception) { }
    }

    private fun sslSocket(port: Int, timeoutMs: Int): SSLSocket {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("client", identity.first, pass, arrayOf(identity.second))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        val plain = Socket()
        plain.connect(InetSocketAddress(ip, port), 6000)
        val s = ctx.socketFactory.createSocket(plain, ip, port, true) as SSLSocket
        s.soTimeout = timeoutMs
        s.startHandshake()
        return s
    }

    // ---------- Client certificate (self-signed) ----------

    private fun loadOrCreateIdentity(): Pair<PrivateKey, X509Certificate> {
        val keyFile = File(appContext.filesDir, "client.key")
        val certFile = File(appContext.filesDir, "client.crt")
        val cf = CertificateFactory.getInstance("X.509")
        if (keyFile.exists() && certFile.exists()) {
            try {
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                return Pair(key, cert)
            } catch (e: Exception) {
                // nعاود نصنع
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        val der = buildSelfSignedCert(kp)
        val cert = cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(der)
        return Pair(kp.private, cert)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 128 -> out.write(n)
            n < 256 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun buildSelfSignedCert(kp: KeyPair): ByteArray {
        val sigAlg = der(
            0x30,
            byteArrayOf(
                0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
                0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
            ) + byteArrayOf(0x05, 0x00)
        )
        val name = der(
            0x30,
            der(
                0x31,
                der(
                    0x30,
                    byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03) + der(0x0C, "atvremote".toByteArray())
                )
            )
        )
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val now = System.currentTimeMillis()
        val notBefore = der(0x17, fmt.format(Date(now - 86400000L)).toByteArray())
        val notAfter = der(0x17, fmt.format(Date(now + 3650L * 86400000L)).toByteArray())

        val serial = ByteArray(8)
        SecureRandom().nextBytes(serial)
        serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()

        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))) +
                der(0x02, serial) +
                sigAlg +
                name +
                der(0x30, notBefore + notAfter) +
                name +
                kp.public.encoded
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(tbs)
        val signature = sig.sign()
        return der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0) + signature))
    }
}
