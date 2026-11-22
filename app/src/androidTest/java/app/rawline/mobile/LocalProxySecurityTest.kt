package app.rawline.mobile

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.core.mobile.Mobile
import app.rawline.core.mobile.StartOptions
import app.rawline.mobile.bg.CoreRuntime
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.database.SettingsRegistry
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.ui.MainActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Exercise the bundled native cores: loopback must not grant another app an anonymous proxy. */
@RunWith(AndroidJUnit4::class)
class LocalProxySecurityTest {
    @Test fun bothCoreBridgesRejectAnonymousClientsAndAcceptTheirPrivateCredentials() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        CoreRuntime.setup(application)
        for (xray in listOf(false, true)) {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val password = UUID.randomUUID().toString().replace("-", "")
            val singConfig = """{"log":{"level":"error"},"inbounds":[{"type":"socks","listen":"127.0.0.1","listen_port":$port,"users":[{"username":"bridge","password":"$password"}]}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
            val nativeXrayJson = """{"log":{"loglevel":"error"},"inbounds":[{"protocol":"socks","listen":"127.0.0.1","port":$port,"settings":{"auth":"password","udp":true,"accounts":[{"user":"bridge","pass":"$password"}]}}],"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
            val instance = Mobile.newInstance(CoreRuntime.platform, StartOptions().apply {
                coreConfig = if (xray) """{"outbounds":[{"type":"direct","tag":"direct"}]}""" else singConfig
                needXray = xray
                xrayConfig = if (xray) nativeXrayJson else ""
            })
            try {
                instance.start()
                connect(port) { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    assertArrayEquals("Anonymous client accepted by ${if (xray) "Xray" else "sing-box"}", byteArrayOf(5, -1), read(socket, 2))
                }
                connect(port) { socket ->
                    socket.getOutputStream().write(byteArrayOf(5, 1, 2))
                    assertArrayEquals(byteArrayOf(5, 2), read(socket, 2))
                    val user = "bridge".toByteArray(Charsets.UTF_8)
                    val pass = password.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                    assertArrayEquals("Authenticated bridge cannot connect", byteArrayOf(1, 0), read(socket, 2))
                }
            } finally { instance.close() }
        }
    }

    @Test fun firstProxyStartCreatesCredentialsWithoutOpeningSettings() {
        val store = DataStore.configurationStore
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val settings = mapOf(
            Key.SERVICE_MODE to Key.MODE_PROXY,
            SettingsRegistry.INBOUND_ADDRESS.key to "127.0.0.1",
            SettingsRegistry.INBOUND_SOCKS_PORT.key to localPort.toString(),
            SettingsRegistry.RANDOM_INBOUND_PORT.key to "false",
            SettingsRegistry.EXIT_COUNTRY_LOOKUP.key to "false",
        )
        val credentials = listOf(SettingsRegistry.INBOUND_AUTH.key, SettingsRegistry.INBOUND_USER.key, SettingsRegistry.INBOUND_PASS.key)
        val previous = (settings.keys + credentials + Key.PROFILE_ID).associateWith(store::getRaw)
        val group = ProxyGroup(name = "Local proxy security test")
        val groupId = SagerDatabase.groupDao.insert(group)
        val profileId = SagerDatabase.proxyDao.insert(listOf(ProxyEntity(groupId = groupId).putOutbound(Socks().apply {
            name = "Loopback handshake test"
            server = "192.0.2.1"
            serverPort = 1080
        }))).single()
        try {
            store.putAll(settings)
            credentials.forEach(store::remove)
            DataStore.selectedProxy = profileId
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { SagerNet.startService() }
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
                    var listening = false
                    while (!listening && android.os.SystemClock.elapsedRealtime() < deadline) {
                        listening = runCatching { connect(localPort) {} }.isSuccess
                        if (!listening) Thread.sleep(100)
                    }
                    check(listening) { "The proxy-only service did not open the configured port" }
                    val testUser = DataStore.inboundUser
                    val testPassword = DataStore.inboundPass
                    check(DataStore.inboundAuth && testUser.isNotEmpty() && testPassword.length >= 32) {
                        "The proxy started without initializing credentials"
                    }
                    connect(localPort) { socket ->
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        assertArrayEquals(byteArrayOf(5, -1), read(socket, 2))
                    }
                    connect(localPort) { socket ->
                        socket.getOutputStream().write(byteArrayOf(5, 1, 2))
                        assertArrayEquals(byteArrayOf(5, 2), read(socket, 2))
                        val user = testUser.toByteArray(Charsets.UTF_8)
                        val pass = testPassword.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                        assertArrayEquals(byteArrayOf(1, 0), read(socket, 2))
                    }
                } finally {
                    scenario.onActivity { SagerNet.stopService() }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (android.os.SystemClock.elapsedRealtime() < deadline && runCatching { connect(localPort) {} }.isSuccess) {
                        Thread.sleep(100)
                    }
                }
            }
        } finally {
            previous.forEach { (key, value) -> store.putString(key, value) }
            SagerDatabase.proxyDao.deleteById(profileId)
            SagerDatabase.groupDao.deleteById(groupId)
        }
    }

    private fun connect(port: Int, block: (Socket) -> Unit) {
        Socket("127.0.0.1", port).use { socket -> socket.soTimeout = 5000; block(socket) }
    }

    private fun read(socket: Socket, count: Int): ByteArray = ByteArray(count).also { bytes ->
        java.io.DataInputStream(socket.getInputStream()).readFully(bytes)
    }
}
