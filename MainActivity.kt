package com.abdullahtool.bridge.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit classStatus: TextView
    private lateinit ipPortText: TextView
    private lateinit btnToggle: Button
    
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val PORT = 5555 // المنفذ الثابت للجسر المحلي

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تصميم واجهة التطبيق الوسيط (برمجياً ليكون جاهزاً فوراً دون ملفات XML إضافية)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#131314"))
        }

        val title = TextView(this).apply {
            text = "✨ AbdullahTool Local Bridge"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#A8C7FA"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }

        classStatus = TextView(this).apply {
            text = "● الحالة: متوقف (Offline)"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#FCD34D"))
            setPadding(0, 40, 0, 20)
            gravity = android.view.Gravity.CENTER
        }

        ipPortText = TextView(this).apply {
            text = "عنوان الجسر: --:--"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#6DD58C"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 20, 0, 40)
            gravity = android.view.Gravity.CENTER
        }

        btnToggle = Button(this).apply {
            text = "تشغيل الجسر المحلي (Start Bridge)"
            setBackgroundColor(android.graphics.Color.parseColor("#A8C7FA"))
            setTextColor(android.graphics.Color.parseColor("#062E6F"))
            setPadding(20, 30, 20, 30)
        }

        layout.addView(title)
        layout.addView(classStatus)
        layout.addView(ipPortText)
        layout.addView(btnToggle)
        setContentView(layout)

        // جلب عرض الـ IP الحالي على الشبكة المحلية
        val localIp = getLocalIpAddress()
        ipPortText.text = "$localIp:$PORT"

        btnToggle.setOnClickListener {
            if (!isRunning) {
                startBridgeServer()
            } else {
                stopBridgeServer()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return InetAddress.getByAddress(
            byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt ushr 8 and 0xff).toByte(),
                (ipInt ushr 16 and 0xff).toByte(),
                (ipInt ushr 24 and 0xff).toByte()
            )
        ).hostAddress ?: "127.0.0.1"
    }

    private fun startBridgeServer() {
        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                isRunning = true
                
                runOnUiThread {
                    classStatus.text = "● الحالة: يعمل وجاهز للاتصال (Online)"
                    classStatus.setTextColor(android.graphics.Color.parseColor("#6DD58C"))
                    btnToggle.text = "إيقاف الجسر (Stop Bridge)"
                    btnToggle.setBackgroundColor(android.graphics.Color.parseColor("#3C1919"))
                    btnToggle.setTextColor(android.graphics.Color.parseColor("#F2B8B5"))
                }

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor.execute { handleClientConnection(clientSocket) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    classStatus.text = "● خطأ في الخادم: ${e.message}"
                    classStatus.setTextColor(android.graphics.Color.parseColor("#F2B8B5"))
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = java.io.PrintWriter(socket.getOutputStream(), true)
            
            val command = reader.readLine() ?: return
            
            // تنفيذ الأمر المستلم من AbdullahTool Pro عبر الشل المحلي للهاتف
            val process = Runtime.getRuntime().exec(command)
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val response = StringBuilder()
            var line: String?
            while (outputReader.readLine().also { line = it } != null) {
                response.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                response.append(line).append("\n")
            }
            
            writer.println(response.toString())
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopBridgeServer() {
        try {
            isRunning = false
            serverSocket?.close()
            serverSocket = null
            
            runOnUiThread {
                classStatus.text = "● الحالة: متوقف (Offline)"
                classStatus.setTextColor(android.graphics.Color.parseColor("#FCD34D"))
                btnToggle.text = "تشغيل الجسر المحلي (Start Bridge)"
                btnToggle.setBackgroundColor(android.graphics.Color.parseColor("#A8C7FA"))
                btnToggle.setTextColor(android.graphics.Color.parseColor("#062E6F"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBridgeServer()
    }
}