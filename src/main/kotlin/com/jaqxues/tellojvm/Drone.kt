package com.jaqxues.tellojvm

import com.github.aakira.napier.Napier
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.aSocket
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.*
import kotlinx.io.core.ByteReadPacket
import kotlinx.io.core.Closeable
import kotlinx.io.core.readBytes
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.max
import kotlin.math.min


/**
 * This file was created by Jacques Hoffmann (jaqxues) in the Project TelloJVM.<br>
 * Date: 21.12.19 - Time 20:31.
 */
@KtorExperimentalAPI
class Drone(
    val localIp: String = "0.0.0.0", val localPort: Int = 8889, val stateInterval: Long = 500L,
    val commandTimeout: Float = 3.0f, val moveTimeout: Float = 15.0f, val telloIp: String = "192.168.10.1"
) : Closeable {

    private val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind(InetSocketAddress(localIp, 8889))
    private val stateSocket = aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind(InetSocketAddress(localIp, 8890))
    private val videoSocket =
        aSocket(ActorSelectorManager(Dispatchers.IO)).udp().bind(InetSocketAddress(localIp, 11111))
    private val telloAddress = InetSocketAddress(telloIp, 8889)
    lateinit var states: Map<String, String>
        private set
    private val supervisor = SupervisorJob()

    init {
        GlobalScope.launch(Dispatchers.IO + supervisor) {
            try {
                while (true) {
                    val data = stateSocket.receive().packet.readText()
                    if (!data.isBlank()) {
                        states = data.replace(";\r\n", "").split(";").map {
                            val tmp = it.split(":")
                            tmp[0] to tmp[1]
                        }.toMap()
                    }
                    delay(stateInterval)
                }
            } catch (ex: SocketTimeoutException) {
                Napier.e("State Socket Error", throwable = ex)
            }
        }
        GlobalScope.launch(Dispatchers.IO + SupervisorJob()) {
            try {
                while (true) {
                    val data = videoSocket.receive().packet.readBytes()
                }
            } catch (ex: SocketTimeoutException) {
                Napier.e("State Socket Error", throwable = ex)
            }
        }
    }

    private fun sendCommand(command: String): String {
        return runBlocking {
            socket.send(Datagram(ByteReadPacket(command.toByteArray()), telloAddress))

            if (command == "emergency") // Emergency command does not emit a response
                return@runBlocking "ok"
            var output = socket.incoming.receive().packet.readText()
            if (command.endsWith("?")) {
                output = output.replace("\r\n", "")
            }
            return@runBlocking output
        }
    }

    private infix fun IntArray.boundBy(range: IntRange) =
        map {
            it boundBy range
        }.joinToString(" ")

    private infix fun Int.boundBy(range: IntRange): Int {
        val tmp = min(this, range.last)
        return max(tmp, range.first)
    }


    fun enterSdkMode() = sendCommand("command")
    fun takeOff() = sendCommand("takeoff")
    fun land() = sendCommand("land")
    fun emergency() = sendCommand("emergency")
    fun startStream() = sendCommand("streamon")
    fun stopStream() = sendCommand("streamoff")

    private fun move(direction: String, distance: Int) = sendCommand("$direction ${distance boundBy 20..500}")
    fun moveForward(distance: Int) = move("forward", distance)
    fun moveBack(distance: Int) = move("back", distance)
    fun moveUp(distance: Int) = move("up", distance)
    fun moveDown(distance: Int) = move("down", distance)
    fun moveRight(distance: Int) = move("right", distance)
    fun moveLeft(distance: Int) = move("left", distance)

    fun goLocation(x: Int, y: Int, z: Int, speed: Int) = sendCommand(
        "go ${intArrayOf(x, y, z) boundBy 20..500} ${speed boundBy 10..100}")
    fun curve(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, speed: Int) = sendCommand(
        "curve ${intArrayOf(x1, y1, z1, x2, y2, z2) boundBy 20..500} ${speed boundBy 10..60}")

    private fun turn(direction: String, degree: Int) = sendCommand("$direction ${degree boundBy 1..3600}")
    fun clockwise(degree: Int) = turn("cw", degree)
    fun counterClockwise(degree: Int) = turn("ccw", degree)

    private fun flip(direction: Char) = sendCommand("flip $direction")
    fun flipLeft() = flip('l')
    fun flipRight() = flip('r')
    fun flipForward() = flip('f')
    fun flipBack() = flip('b')

    fun setSpeed(speed: Int) = sendCommand("speed ${speed boundBy 10..100}")
    fun setRc(a: Int, b: Int, c: Int, d: Int) = sendCommand("rc ${intArrayOf(a, b, c, d) boundBy -100..100}")
    fun setWifiPassword(ssid: String, password: String) = sendCommand("wifi $ssid $password")

    fun getSpeed() = sendCommand("speed?")
    fun getBattery() = sendCommand("battery?")
    fun getFlightTime() = sendCommand("time?")
    fun getHeight() = sendCommand("height?")
    fun getTemp() = sendCommand("temp?")
    fun getAttitude() = sendCommand("attitude?")
    fun getBarometer() = sendCommand("baro?")
    fun getAcceleration() = sendCommand("acceleration?")
    fun getTofDistance() = sendCommand("tof?")
    fun getWifiSNR() = sendCommand("wifi?")


    override fun close() {
        supervisor.cancel()
        socket.close()
        stateSocket.close()
        videoSocket.close()
    }
}

inline fun <T : Closeable> T.perform(crossinline func: T.() -> Unit) {
    this.use { it.func() }
}

@KtorExperimentalAPI
fun main() {
    runBlocking {
        Drone().perform {
            enterSdkMode()
            takeOff()
            land()
        }
    }
}
