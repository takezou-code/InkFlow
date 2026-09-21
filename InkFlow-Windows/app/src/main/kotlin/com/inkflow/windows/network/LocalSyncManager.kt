package com.inkflow.windows.network

import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LocalSyncManager(
    private val port: Int = 8765,
    private val broadcastPort: Int = 8766,
    private val onDeviceDiscovered: (String, String) -> Unit = { _, _ -> },
    private val onDataReceived: (ByteArray) -> Unit = {}
) {
    private var isRunning = false
    private val json = Json { ignoreUnknownKeys = true }
    
    // 廣播設備信息
    fun startBroadcast(deviceId: String, deviceName: String) {
        thread {
            val socket = DatagramSocket()
            socket.broadcast = true
            isRunning = true
            
            val message = json.encodeToString(
                DeviceInfo.serializer(),
                DeviceInfo(deviceId, deviceName, "Windows")
            ).toByteArray()
            
            while (isRunning) {
                try {
                    val broadcastIP = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(message, message.size, broadcastIP, broadcastPort)
                    socket.send(packet)
                    Thread.sleep(3000) // 每 3 秒廣播一次
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            socket.close()
        }
    }
    
    // 監聽設備發現
    fun startDiscovery() {
        thread {
            val socket = DatagramSocket(broadcastPort)
            socket.broadcast = true
            isRunning = true
            
            val buffer = ByteArray(1024)
            
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    val deviceInfo = json.decodeFromString(DeviceInfo.serializer(), message)
                    
                    onDeviceDiscovered(deviceInfo.id, deviceInfo.name)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            socket.close()
        }
    }
    
    // 啟動 TCP 服務器接收數據
    fun startServer() {
        thread {
            try {
                val serverSocket = ServerSocket(port)
                isRunning = true
                
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket.accept()
                        thread {
                            val inputStream = clientSocket.getInputStream()
                            val data = inputStream.readBytes()
                            onDataReceived(data)
                            clientSocket.close()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                serverSocket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // 發送到指定設備
    fun sendToDevice(ipAddress: String, data: ByteArray) {
        thread {
            try {
                val socket = java.net.Socket(ipAddress, port)
                socket.outputStream.write(data)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun stop() {
        isRunning = false
    }
}

@kotlinx.serialization.Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String
)
