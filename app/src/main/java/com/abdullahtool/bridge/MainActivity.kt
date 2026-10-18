package com.abdullahtool.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var statusBadge: TextView
    private lateinit var ipPortView: TextView
    private lateinit var deviceInfoView: TextView
    private lateinit var logBox: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnGrantStorage: Button

    private var tcpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var isRunning = false
    private val executor = Executors.newCachedThreadPool()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val TCP_PORT = 50555 // منفذ الجسر المخصص السريع
    private val UDP_PORT = 50556 // منفذ الرادار للاكتشاف التلقائي

    private val ACTION_INSTALL_COMMIT = "com.abdullahtool.bridge.INSTALL_COMMIT"

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                    appendLog("📲 تم إظهار نافذة الموافقة على تثبيت التطبيق على الشاشة.")
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                appendLog("✔ تم تثبيت التطبيق بنجاح!")
            } else {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown error"
                appendLog("✖ فشل التثبيت: $msg")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, IntentFilter(ACTION_INSTALL_COMMIT), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(installReceiver, IntentFilter(ACTION_INSTALL_COMMIT))
        }

        buildGeminiDarkUi()
        refreshNetworkDisplay()

        // تشغيل الجسر تلقائياً فور فتح التطبيق لتوفير الوقت
        startBridgeEngine()
    }

    private fun buildGeminiDarkUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 56, 44, 44)
            setBackgroundColor(Color.parseColor("#131314"))
        }

        val title = TextView(this).apply {
            text = "✨ ABDULLAH TOOL BRIDGE"
            textSize = 21f
            setTextColor(Color.parseColor("#A8C7FA"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subTitle = TextView(this).apply {
            text = "الجسر اللاسلكي المباشر فائق السرعة • v2.0"
            textSize = 12f
            setTextColor(Color.parseColor("#8E918F"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 32)
        }

        val cardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1F20"))
            cornerRadius = 32f
            setStroke(2, Color.parseColor("#282A2C"))
        }

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(36, 36, 36, 36)
        }

        statusBadge = TextView(this).apply {
            text = "● الحالة: متوقف"
            textSize = 15f
            setTextColor(Color.parseColor("#FCD34D"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        ipPortView = TextView(this).apply {
            text = "0.0.0.0:$TCP_PORT"
            textSize = 22f
            setTextColor(Color.parseColor("#6DD58C"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 12)
        }

        deviceInfoView = TextView(this).apply {
            text = "الجهاز: ${Build.MODEL} | Android ${Build.VERSION.RELEASE}"
            textSize = 12f
            setTextColor(Color.parseColor("#C4C7C5"))
            gravity = Gravity.CENTER
        }

        infoCard.addView(statusBadge)
        infoCard.addView(ipPortView)
        infoCard.addView(deviceInfoView)

        btnToggle = Button(this).apply {
            text = "إيقاف الجسر المحلي"
            isAllCaps = false
            textSize = 14f
            setBackgroundColor(Color.parseColor("#A8C7FA"))
            setTextColor(Color.parseColor("#062E6F"))
            setPadding(24, 28, 24, 28)
            setOnClickListener {
                if (isRunning) stopBridgeEngine() else startBridgeEngine()
            }
        }

        btnGrantStorage = Button(this).apply {
            text = "🔓 منح صلاحية الوصول الكامل للملفات وتثبيت الحزم"
            isAllCaps = false
            textSize = 13f
            setBackgroundColor(Color.parseColor("#282A2C"))
            setTextColor(Color.parseColor("#E3E3E3"))
            setOnClickListener { requestWidePermissions() }
        }

        val logLabel = TextView(this).apply {
            text = "🖥️ سجل الأوامر المستلمة من AbdullahTool Pro:"
            textSize = 13f
            setTextColor(Color.parseColor("#A8C7FA"))
            setPadding(0, 28, 0, 12)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1F20"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        logBox = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#6DD58C"))
            typeface = Typeface.MONOSPACE
        }
        scroll.addView(logBox)

        val spacer1 = TextView(this).apply { height = 24 }
        val spacer2 = TextView(this).apply { height = 16 }

        root.addView(title)
        root.addView(subTitle)
        root.addView(infoCard)
        root.addView(spacer1)
        root.addView(btnToggle)
        root.addView(spacer2)
        root.addView(btnGrantStorage)
        root.addView(logLabel)
        root.addView(scroll)

        setContentView(root)
    }

    private fun requestWidePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            appendLog("✔ تم فتح إعدادات الصلاحيات الواسعة.")
        } catch (e: Exception) {
            appendLog("⚠ تنبيه صلاحيات: ${e.message}")
        }
    }

    // جلب عنوان الـ IP الدقيق سواء كان متصلاً بـ Wi-Fi أو فاتحاً نقطة اتصال (Hotspot)
    private fun getBestLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var candidateIp = ""
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan") || nif.name.startsWith("ap") || nif.name.startsWith("rndis")) {
                            return ip
                        }
                        if (candidateIp.isEmpty()) candidateIp = ip
                    }
                }
            }
            if (candidateIp.isNotEmpty()) return candidateIp
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun refreshNetworkDisplay() {
        val ip = getBestLocalIpAddress()
        val rooted = checkRootAvailable()
        runOnUiThread {
            ipPortView.text = "$ip:$TCP_PORT"
            deviceInfoView.text = "الطراز: ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | Root: ${if (rooted) "متوفر ✔" else "غير متوفر"}"
        }
    }

    private fun acquireLocks() {
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AbdullahTool::BridgeWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AbdullahTool::BridgeWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
    }

    private fun startBridgeEngine() {
        if (isRunning) return
        isRunning = true
        acquireLocks()
        refreshNetworkDisplay()

        // 1. خادم TCP الرئيسي للأوامر والملفات
        executor.execute {
            try {
                tcpServer = ServerSocket(TCP_PORT).apply { reuseAddress = true }
                runOnUiThread {
                    statusBadge.text = "● الجسر متصل وجاهز للاستقبال (Online)"
                    statusBadge.setTextColor(Color.parseColor("#6DD58C"))
                    btnToggle.text = "إيقاف الجسر المحلي (Stop Bridge)"
                    btnToggle.setBackgroundColor(Color.parseColor("#3C1919"))
                    btnToggle.setTextColor(Color.parseColor("#F2B8B5"))
                }
                appendLog("🚀 بدأ تشغيل خادم الجسر على المنفذ $TCP_PORT + رادار UDP $UDP_PORT")

                while (isRunning) {
                    val client = tcpServer?.accept() ?: break
                    client.keepAlive = true
                    client.tcpNoDelay = true
                    executor.execute { handleToolClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) appendLog("✖ توقف خادم TCP: ${e.message}")
            }
        }

        // 2. رادار UDP للاستجابة الفورية للبحث التلقائي من الكمبيوتر
        executor.execute {
            try {
                udpSocket = DatagramSocket(UDP_PORT).apply { broadcast = true; reuseAddress = true }
                val buf = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet) ?: break
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("AT_DISCOVER")) {
                        val ip = getBestLocalIpAddress()
                        val rootStr = if (checkRootAvailable()) "ROOT" else "NOROOT"
                        val reply = "AT_HERE|$ip:$TCP_PORT|${Build.MODEL}|Android ${Build.VERSION.RELEASE}|$rootStr"
                        val replyBytes = reply.toByteArray(Charsets.UTF_8)
                        val outPacket = DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port)
                        udpSocket?.send(outPacket)
                        appendLog("📡 تم الرد على رادار البحث التلقائي للكمبيوتر (${packet.address.hostAddress})")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopBridgeEngine() {
        isRunning = false
        releaseLocks()
        try { tcpServer?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        tcpServer = null
        udpSocket = null

        runOnUiThread {
            statusBadge.text = "● الحالة: متوقف (Offline)"
            statusBadge.setTextColor(Color.parseColor("#FCD34D"))
            btnToggle.text = "تشغيل الجسر المحلي (Start Bridge)"
            btnToggle.setBackgroundColor(Color.parseColor("#A8C7FA"))
            btnToggle.setTextColor(Color.parseColor("#062E6F"))
        }
        appendLog("⏹ تم إيقاف الجسر المحلي.")
    }

    // قراءة السطر الأول بدقة دون إتلاف البايتات الثنائية للملفات المرسلة بعده
    private fun readHeaderLine(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
        }
        return bos.toString("UTF-8")
    }

    private fun handleToolClient(socket: Socket) {
        try {
            socket.soTimeout = 60000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val header = readHeaderLine(input).trim()
            if (header.isEmpty()) {
                socket.close()
                return
            }

            val parts = header.split("|")
            val cmdType = parts[0].uppercase(Locale.ROOT)

            when (cmdType) {
                "PING" -> {
                    refreshNetworkDisplay()
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val root = if (checkRootAvailable()) "ROOT" else "NOROOT"
                    val resp = "OK|${Build.MODEL}|Android ${Build.VERSION.RELEASE}|SDK ${Build.VERSION.SDK_INT}|$bat%|$root\n"
                    output.write(resp.toByteArray(Charsets.UTF_8))
                    appendLog("✔ فحص اتصال ناجح من AbdullahTool Pro")
                }

                "SHELL" -> {
                    val shellCmd = header.substringAfter("SHELL|")
                    appendLog("💻 تنفيذ أمر: $shellCmd")
                    val res = executeSmartShell(shellCmd)
                    output.write(res.toByteArray(Charsets.UTF_8))
                }

                "LIST_PKGS" -> {
                    appendLog("📋 جلب قائمة الحزم المثبتة عبر PackageManager...")
                    val pkgs = packageManager.getInstalledPackages(0)
                    val sb = StringBuilder()
                    for (p in pkgs) {
                        val vCode = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
                        sb.append("package:${p.packageName} (versionCode=$vCode)\n")
                    }
                    output.write(sb.toString().toByteArray(Charsets.UTF_8))
                }

                "OPEN_APP" -> {
                    val targetPkg = parts.getOrNull(1)?.trim() ?: ""
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        output.write("OK: تم تشغيل الحزمة $targetPkg على شاشة الهاتف.\n".toByteArray(Charsets.UTF_8))
                        appendLog("🚀 تشغيل تطبيق: $targetPkg")
                    } else {
                        output.write("ERROR: لم يتم العثور على واجهة تشغيل للحزمة $targetPkg\n".toByteArray(Charsets.UTF_8))
                    }
                }

                "OPEN_URL" -> {
                    val url = header.substringAfter("OPEN_URL|").trim()
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(browserIntent)
                    output.write("OK: تم فتح الرابط على الهاتف: $url\n".toByteArray(Charsets.UTF_8))
                    appendLog("🌐 فتح رابط: $url")
                }

                "CLIPBOARD_SET" -> {
                    val clipText = header.substringAfter("CLIPBOARD_SET|")
                    runOnUiThread {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AbdullahTool", clipText))
                    }
                    output.write("OK: تم نسخ النص إلى حافظة الهاتف بنجاح.\n".toByteArray(Charsets.UTF_8))
                    appendLog("📋 تم تحديث حافظة الهاتف من الكمبيوتر.")
                }

                "ENABLE_ADBD" -> {
                    appendLog("⚡ محاولة تفعيل منفذ ADB TCP 5555 داخلياً...")
                    val res = executeSmartShell("setprop service.adb.tcp.port 5555 && stop adbd && start adbd")
                    output.write(("OK|${getBestLocalIpAddress()}:5555|$res\n").toByteArray(Charsets.UTF_8))
                }

                "PUSH" -> {
                    // الصيغة: PUSH|<remotePath>|<fileSize>
                    val remotePath = parts.getOrNull(1) ?: "/sdcard/Download/received_file.bin"
                    val expectedSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    appendLog("📥 استقبال ملف ($expectedSize بايت) -> $remotePath")
                    val saved = receiveBinaryStreamToFile(input, remotePath, expectedSize)
                    if (saved) {
                        output.write("OK: تم استلام وحفظ الملف بنجاح في: $remotePath\n".toByteArray(Charsets.UTF_8))
                        appendLog("✔ اكتمل حفظ الملف: $remotePath")
                    } else {
                        output.write("ERROR: تعذر حفظ الملف في المسار المحدد (تحقق من صلاحية التخزين).\n".toByteArray(Charsets.UTF_8))
                    }
                }

                "INSTALL" -> {
                    // الصيغة: INSTALL|<fileName>|<fileSize>
                    val fileName = parts.getOrNull(1) ?: "temp_install.apk"
                    val expectedSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val stagingApk = File(externalCacheDir ?: cacheDir, fileName)
                    appendLog("📦 استلام ملف APK للتثبيت: $fileName ($expectedSize بايت)")

                    if (receiveBinaryStreamToFile(input, stagingApk.absolutePath, expectedSize)) {
                        val installRes = installApkSmart(stagingApk)
                        output.write((installRes + "\n").toByteArray(Charsets.UTF_8))
                    } else {
                        output.write("ERROR: فشل استلام ملف الـ APK عبر الشبكة!\n".toByteArray(Charsets.UTF_8))
                    }
                }

                "PULL" -> {
                    // الصيغة: PULL|<remotePath>
                    val remotePath = header.substringAfter("PULL|").trim()
                    val file = File(remotePath)
                    if (file.exists() && file.canRead()) {
                        sendFileToTool(output, file)
                        appendLog("📤 تم إرسال الملف للكمبيوتر: $remotePath")
                    } else {
                        output.write("ERROR|الملف غير موجود أو لا يمكن قراءته: $remotePath\n".toByteArray(Charsets.UTF_8))
                    }
                }

                "PULL_APK" -> {
                    // الصيغة: PULL_APK|<packageName>
                    val pkg = header.substringAfter("PULL_APK|").trim()
                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val apkFile = File(appInfo.sourceDir)
                        if (apkFile.exists()) {
                            sendFileToTool(output, apkFile)
                            appendLog("📤 تم سحب وإرسال APK الحزمة $pkg إلى الكمبيوتر!")
                        } else {
                            output.write("ERROR|تعذر العثور على ملف APK للحزمة $pkg\n".toByteArray(Charsets.UTF_8))
                        }
                    } catch (e: PackageManager.NameNotFoundException) {
                        output.write("ERROR|الحزمة غير مثبتة على الهاتف: $pkg\n".toByteArray(Charsets.UTF_8))
                    }
                }

"BACKUP_PKG" -> {
                    // سحب بيانات الحزمة مباشرة عبر الواي فاي بدون USB وبدون ADB
                    val pkg = header.substringAfter("BACKUP_PKG|").trim()
                    appendLog("💾 جاري تجهيز وضغط بيانات الحزمة ($pkg) لإرسالها عبر الواي فاي...")
                    val tarFile = File(externalCacheDir ?: cacheDir, "${pkg}_backup.tar")
                    if ( tarFile.exists() ) tarFile.delete()

                    // ضغط بيانات الحزمة من /data/data أو /sdcard/Android/data
                    val cmd = "if [ -d /data/data/$pkg ]; then " +
                              "tar -cf '${tarFile.absolutePath}' -C /data/data/$pkg . 2>/dev/null; " +
                              "elif [ -d /sdcard/Android/data/$pkg ]; then " +
                              "tar -cf '${tarFile.absolutePath}' -C /sdcard/Android/data/$pkg . 2>/dev/null; fi"
                    executeSmartShell(cmd)

                    if (tarFile.exists() && tarFile.length() > 0) {
                        sendFileToTool(output, tarFile)
                        appendLog("✔ تم إرسال بيانات الحزمة ($pkg) إلى الكمبيوتر عبر الواي فاي!")
                    } else {
                        output.write("ERROR|تعذر الوصول لبيانات الحزمة $pkg (تأكد من صلاحية الوصول أو الروت).\n".toByteArray(Charsets.UTF_8))
                    }
                }

                "RESTORE_PKG" -> {
                    // استلام ملف بيانات (.tar) عبر الواي فاي وحقنه مباشرة داخل الحزمة في الهاتف
                    val pkg = parts.getOrNull(1)?.trim() ?: ""
                    val expectedSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val receivedTar = File(externalCacheDir ?: cacheDir, "restore_payload.tar")
                    appendLog("♻️ استلام بيانات الحزمة ($pkg) عبر الواي فاي ($expectedSize بايت)...")

                    if (receiveBinaryStreamToFile(input, receivedTar.absolutePath, expectedSize)) {
                        val stageDir = File(externalCacheDir ?: cacheDir, "stage_$pkg")
                        stageDir.deleteRecursively()
                        stageDir.mkdirs()

                        val restoreCmd = """
                            tar -xf '${receivedTar.absolutePath}' -C '${stageDir.absolutePath}';
                            APP_SRC=$(find '${stageDir.absolutePath}/apps' -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -n 1);
                            if [ -z "${'$'}APP_SRC" ]; then APP_SRC='${stageDir.absolutePath}'; fi;
                            DATA_DIR=/data/data/$pkg;
                            if [ -d "${'$'}DATA_DIR" ]; then
                                APP_UID=$(stat -c %u "${'$'}DATA_DIR");
                                mkdir -p "${'$'}DATA_DIR/databases" "${'$'}DATA_DIR/shared_prefs" "${'$'}DATA_DIR/files";
                                if [ -d "${'$'}APP_SRC/db" ]; then cp -rf "${'$'}APP_SRC/db/." "${'$'}DATA_DIR/databases/"; fi;
                                if [ -d "${'$'}APP_SRC/databases" ]; then cp -rf "${'$'}APP_SRC/databases/." "${'$'}DATA_DIR/databases/"; fi;
                                if [ -d "${'$'}APP_SRC/sp" ]; then cp -rf "${'$'}APP_SRC/sp/." "${'$'}DATA_DIR/shared_prefs/"; fi;
                                if [ -d "${'$'}APP_SRC/shared_prefs" ]; then cp -rf "${'$'}APP_SRC/shared_prefs/." "${'$'}DATA_DIR/shared_prefs/"; fi;
                                if [ -d "${'$'}APP_SRC/f" ]; then cp -rf "${'$'}APP_SRC/f/." "${'$'}DATA_DIR/files/"; fi;
                                if [ -d "${'$'}APP_SRC/files" ]; then cp -rf "${'$'}APP_SRC/files/." "${'$'}DATA_DIR/files/"; fi;
                                chown -R ${'$'}APP_UID:${'$'}APP_UID "${'$'}DATA_DIR/databases" "${'$'}DATA_DIR/shared_prefs" "${'$'}DATA_DIR/files" 2>/dev/null;
                                chmod -R 771 "${'$'}DATA_DIR/databases" "${'$'}DATA_DIR/shared_prefs" "${'$'}DATA_DIR/files" 2>/dev/null;
                                restorecon -R "${'$'}DATA_DIR" 2>/dev/null || true;
                                am force-stop $pkg 2>/dev/null || true;
                                echo "OK: تم حقن واستعادة بيانات الحزمة ($pkg) عبر الواي فاي بنجاح!";
                            else
                                echo "ERROR: تعذر الكتابة المباشرة في مسار الحزمة $pkg";
                            fi;
                            rm -rf '${stageDir.absolutePath}' '${receivedTar.absolutePath}';
                        """.trimIndent()

                        val res = executeSmartShell(restoreCmd)
                        output.write((res + "\n").toByteArray(Charsets.UTF_8))
                        appendLog("✔ اكتملت عملية استعادة البيانات للحزمة: $pkg")
                    } else {
                        output.write("ERROR: فشل استلام ملف البيانات عبر الواي فاي!\n".toByteArray(Charsets.UTF_8))
                    }
                }

                else -> {
                    // التوافق الخلفي: إذا أُرسل أمر نصي مباشر يتم تنفيذه كأمر Shell
                    val res = executeSmartShell(header)
                    output.write(res.toByteArray(Charsets.UTF_8))
                }
            }

            output.flush()
            socket.close()
        } catch (e: Exception) {
            appendLog("⚠ خطأ أثناء معالجة الطلب: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun receiveBinaryStreamToFile(input: InputStream, targetPath: String, expectedSize: Long): Boolean {
        return try {
            val outFile = if (targetPath.endsWith("/")) {
                File(targetPath, "received_file_${System.currentTimeMillis()}")
            } else {
                File(targetPath)
            }
            outFile.parentFile?.mkdirs()

            FileOutputStream(outFile).use { fos ->
                val buf = ByteArray(65536)
                var remaining = expectedSize
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read == -1) break
                    fos.write(buf, 0, read)
                    remaining -= read
                }
                fos.flush()
            }
            true
        } catch (e: Exception) {
            appendLog("✖ خطأ حفظ ملف: ${e.message}")
            false
        }
    }

    private fun sendFileToTool(output: OutputStream, file: File) {
        val header = "OK|${file.length()}\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { fis ->
            val buf = ByteArray(65536)
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
            }
        }
    }

    private fun installApkSmart(apkFile: File): String {
        // 1. إذا كان الجهاز مروتاً (Root): تثبيت صامت ومباشر في الخلفية
        if (checkRootAvailable()) {
            val rootOut = executeSmartShell("pm install -r -g '${apkFile.absolutePath}'")
            if (rootOut.contains("Success", ignoreCase = true)) {
                appendLog("✔ تم تثبيت الـ APK صامتاً عبر صلاحيات Root!")
                return "OK: تم تثبيت التطبيق صامتاً بنجاح عبر Root (Success)!"
            }
        }

        // 2. بدون روت: التثبيت عبر PackageInstaller الرسمي لنظام أندرويد
        return try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            session.openWrite("AbdullahToolInstall", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { fis ->
                    fis.copyTo(out, 65536)
                }
                session.fsync(out)
            }

            val intent = Intent(ACTION_INSTALL_COMMIT).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(this, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
            "OK: تم إرسال التطبيق لمثبت الحزم! يرجى الضغط على (تثبيت) من شاشة الهاتف الآن."
        } catch (e: Exception) {
            "ERROR: تعذر تشغيل مثبت الحزم: ${e.message}"
        }
    }

    private fun checkRootAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun executeSmartShell(cmd: String): String {
        return try {
            val cleanCmd = if (cmd.startsWith("shell ")) cmd.substring(6).trim() else cmd
            val proc = if (checkRootAvailable()) {
                ProcessBuilder("su", "-c", cleanCmd)
            } else {
                ProcessBuilder("sh", "-c", cleanCmd)
            }
            proc.redirectErrorStream(true)
            val p = proc.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (out.isBlank()) "✔ تم تنفيذ الأمر بنجاح (بدون مخرجات نصية).\n" else out
        } catch (e: Exception) {
            "ERROR: ${e.message}\n"
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            logBox.append("[$time] $msg\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installReceiver) } catch (_: Exception) {}
        stopBridgeEngine()
    }
}
