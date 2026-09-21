package com.vic.inkflow.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import mu.KotlinLogging
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Sync data packet for transferring documents and strokes between devices.
 */
data class SyncPacket(
    val type: String, // "document", "strokes", "full_sync"
    val documentUri: String,
    val displayName: String? = null,
    val lastOpenedAt: Long? = null,
    val lastPageIndex: Int? = null,
    val isFavorite: Boolean? = null,
    val folderId: String? = null,
    val strokes: List<StrokeWithPoints>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = getDeviceId()
)

/**
 * Generate a unique device ID based on hostname and user.
 */
fun getDeviceId(): String {
    val hostname = InetAddress.getLocalHost().hostName
    val user = System.getProperty("user.name")
    return "${hostname}_${user}_${System.currentTimeMillis()}".hashCode().toString(16)
}

/**
 * Local network sync manager using UDP broadcast for device discovery
 * and TCP for data transfer.
 */
class LocalSyncManager(
    private val databaseManager: DatabaseManager,
    private val appDataDir: String,
    private val broadcastPort: Int = 53530,
    private val transferPort: Int = 53531
) {
    
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newFixedThreadPool(4)
    private val connectedDevices = mutableSetOf<String>()
    
    var isConnected: Boolean = false
        private set
    
    private val gson = Gson()
    
    /**
     * Start listening for sync requests and broadcasts.
     */
    fun startListening() {
        if (isRunning) return
        
        isRunning = true
        isConnected = true
        
        // Start UDP listener for device discovery
        executor.submit { startUdpListener() }
        
        // Start TCP server for data transfer
        executor.submit { startTcpServer() }
        
        // Broadcast presence every 30 seconds
        java.util.Timer().scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (isRunning) broadcastPresence()
            }
        }, 5000, 30000)
        
        logger.info { "Local sync manager started" }
    }
    
    /**
     * Stop the sync service.
     */
    fun stopListening() {
        isRunning = false
        isConnected = false
        
        try {
            udpSocket?.close()
            tcpServer?.close()
            executor.shutdown()
            logger.info { "Local sync manager stopped" }
        } catch (e: Exception) {
            logger.error(e) { "Error stopping sync manager" }
        }
    }
    
    /**
     * Broadcast sync request to all devices on local network.
     */
    fun broadcastSync() {
        executor.submit {
            try {
                val message = gson.toJson(mapOf("type" to "sync_request", "deviceId" to getDeviceId()))
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(
                    message.toByteArray(),
                    message.length,
                    broadcastAddress,
                    broadcastPort
                )
                udpSocket?.send(packet)
                logger.info { "Broadcast sync request sent" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to broadcast sync request" }
            }
        }
    }
    
    /**
     * Broadcast device presence to local network.
     */
    private fun broadcastPresence() {
        try {
            val message = gson.toJson(mapOf(
                "type" to "presence",
                "deviceId" to getDeviceId(),
                "hostname" to InetAddress.getLocalHost().hostName
            ))
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(
                message.toByteArray(),
                message.length,
                broadcastAddress,
                broadcastPort
            )
            udpSocket?.send(packet)
            logger.debug { "Presence broadcast sent" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to broadcast presence" }
        }
    }
    
    /**
     * Start UDP listener for device discovery.
     */
    private fun startUdpListener() {
        try {
            udpSocket = DatagramSocket(broadcastPort)
            udpSocket?.broadcast = true
            logger.info { "UDP listener started on port $broadcastPort" }
            
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    handleUdpMessage(message, packet.address)
                } catch (e: SocketException) {
                    if (isRunning) {
                        logger.error(e) { "UDP socket error" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to start UDP listener" }
            isConnected = false
        }
    }
    
    /**
     * Handle incoming UDP messages.
     */
    private fun handleUdpMessage(message: String, senderAddress: InetAddress) {
        try {
            val data = gson.fromJson(message, Map::class.java)
            val type = data["type"] as? String ?: return
            
            when (type) {
                "presence" -> {
                    val deviceId = data["deviceId"] as? String ?: return
                    connectedDevices.add(deviceId)
                    logger.info { "Device discovered: $deviceId from ${senderAddress.hostAddress}" }
                }
                "sync_request" -> {
                    val deviceId = data["deviceId"] as? String ?: return
                    logger.info { "Sync request from $deviceId" }
                    // Respond with sync data
                    sendSyncData(senderAddress.hostAddress)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling UDP message" }
        }
    }
    
    /**
     * Start TCP server for data transfer.
     */
    private fun startTcpServer() {
        try {
            tcpServer = ServerSocket(transferPort)
            logger.info { "TCP server started on port $transferPort" }
            
            while (isRunning) {
                try {
                    val clientSocket = tcpServer?.accept()
                    if (clientSocket != null) {
                        executor.submit { handleClientConnection(clientSocket) }
                    }
                } catch (e: SocketException) {
                    if (isRunning) {
                        logger.error(e) { "TCP accept error" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to start TCP server" }
            isConnected = false
        }
    }
    
    /**
     * Handle incoming TCP client connection.
     */
    private fun handleClientConnection(socket: Socket) {
        try {
            socket.soTimeout = 30000 // 30 second timeout
            
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = PrintWriter(socket.getOutputStream(), true)
            
            val request = input.readLine() ?: return
            logger.info { "Received TCP request: $request" }
            
            // Parse request and send appropriate data
            val data = gson.fromJson(request, Map::class.java)
            val type = data["type"] as? String ?: return
            
            when (type) {
                "get_documents" -> sendAllDocuments(output)
                "get_strokes" -> {
                    val documentUri = data["documentUri"] as? String ?: return
                    val pageIndex = (data["pageIndex"] as? Double)?.toInt() ?: 0
                    sendStrokesForPage(documentUri, pageIndex, output)
                }
                "receive_document" -> {
                    val json = input.readLine() ?: return
                    receiveDocument(json)
                    output.println("{\"status\":\"ok\"}")
                }
                "receive_strokes" -> {
                    val json = input.readLine() ?: return
                    receiveStrokes(json)
                    output.println("{\"status\":\"ok\"}")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling client connection" }
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }
    
    /**
     * Send sync data to a specific IP address.
     */
    private fun sendSyncData(targetIp: String) {
        executor.submit {
            try {
                val socket = Socket(targetIp, transferPort)
                val output = PrintWriter(socket.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                
                // Request list of documents from target
                output.println(gson.toJson(mapOf("type" to "get_documents")))
                
                // Read response
                val response = input.readLine()
                logger.info { "Received sync response: $response" }
                
                socket.close()
            } catch (e: Exception) {
                logger.error(e) { "Failed to send sync data to $targetIp" }
            }
        }
    }
    
    /**
     * Send all documents to client.
     */
    private fun sendAllDocuments(output: PrintWriter) {
        val documents = databaseManager.getAllDocuments()
        val response = gson.toJson(mapOf(
            "type" to "documents",
            "data" to documents
        ))
        output.println(response)
        logger.info { "Sent ${documents.size} documents" }
    }
    
    /**
     * Send strokes for a specific page.
     */
    private fun sendStrokesForPage(documentUri: String, pageIndex: Int, output: PrintWriter) {
        val strokes = databaseManager.getStrokesForPage(documentUri, pageIndex)
        val response = gson.toJson(mapOf(
            "type" to "strokes",
            "data" to strokes
        ))
        output.println(response)
        logger.info { "Sent ${strokes.size} strokes for page $pageIndex" }
    }
    
    /**
     * Receive and save document data.
     */
    private fun receiveDocument(json: String) {
        try {
            val document = gson.fromJson(json, DocumentEntity::class.java)
            databaseManager.saveDocument(document)
            logger.info { "Received document: ${document.displayName}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to receive document" }
        }
    }
    
    /**
     * Receive and save stroke data.
     */
    private fun receiveStrokes(json: String) {
        try {
            val type = object : TypeToken<List<StrokeWithPoints>>() {}.type
            val strokes = gson.fromJson<List<StrokeWithPoints>>(json, type)
            
            for (strokeWithPoints in strokes) {
                databaseManager.saveStroke(strokeWithPoints.stroke, strokeWithPoints.points)
            }
            logger.info { "Received ${strokes.size} strokes" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to receive strokes" }
        }
    }
}
