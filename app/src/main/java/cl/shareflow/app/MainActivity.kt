package cl.shareflow.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.widget.*
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: EditText
    private var server: ServerSocket? = null
    private val port = 8765
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,60,40,24); setBackgroundColor(Color.rgb(245,248,252)) }
        fun title(t:String, size:Float): TextView = TextView(this).apply { text=t; textSize=size; setTextColor(Color.rgb(20,45,77)); setPadding(0,8,0,16) }
        fun button(t:String, action:()->Unit) { root.addView(Button(this).apply { text=t; setOnClickListener { action() } }, ViewGroup.LayoutParams(-1,-2)) }
        root.addView(title("ShareFlow",32f))
        root.addView(title("Comparte archivos en tu red Wi-Fi local",16f))
        status = title("Preparando receptor...",15f); root.addView(status)
        address = EditText(this).apply { hint="IP del teléfono receptor (ej. 192.168.1.25)"; setSingleLine(true) }; root.addView(address)
        button("Seleccionar y enviar archivo") { val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }; startActivityForResult(intent,100) }
        button("Mostrar mi dirección") { status.text="Para recibir: ${localIp()}:$port\nAmbos teléfonos deben estar en la misma red Wi-Fi." }
        root.addView(title("Los archivos recibidos se guardan en el almacenamiento privado de ShareFlow.\nVersión inicial: transferencia de un archivo por vez.",13f))
        setContentView(ScrollView(this).apply { addView(root) })
        startServer()
    }
    private fun localIp(): String {
        return try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "IP no disponible" } catch (_:Exception) { "IP no disponible" }
    }
    private fun startServer() { thread {
        try { server=ServerSocket(port); runOnUiThread { status.text="Listo para recibir en ${localIp()}:$port" }
            while (!server!!.isClosed) { val socket=server!!.accept(); thread {
                socket.use { s -> try {
                    s.soTimeout=30000
                    val input=s.getInputStream(); val header=StringBuilder()
                    while (true) { val b=input.read(); if(b<0) break; header.append(b.toChar()); if(header.endsWith("\r\n\r\n") || header.length>8192) break }
                    val line=header.toString().lineSequence().firstOrNull().orEmpty()
                    val filename=java.net.URLDecoder.decode(line.substringAfter("/upload/", "archivo.bin").substringBefore(" HTTP"),"UTF-8").substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"),"_").take(100).ifBlank { "archivo.bin" }
                    val length=Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.toLongOrNull() ?: -1
                    if (!line.startsWith("POST /upload/") || length<0 || length>1_000_000_000L) { s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray()); return@use }
                    val dir=File(filesDir,"received").apply { mkdirs() }; var dest=File(dir,filename); if(dest.exists()) dest=File(dir,"${System.currentTimeMillis()}_$filename")
                    dest.outputStream().use { out -> val buffer=ByteArray(65536); var left=length; while(left>0) { val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt()); if(n<0) throw Exception("Transferencia incompleta"); out.write(buffer,0,n); left-=n } }
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                    runOnUiThread { status.text="Recibido: ${dest.name}\nGuardado en la carpeta privada de la aplicación." }
                } catch(e:Exception) { runOnUiThread { status.text="Error al recibir: ${e.message}" } } }
            } }
        } catch(e:Exception) { runOnUiThread { status.text="No se pudo iniciar receptor: ${e.message}" } }
    } }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=100 || resultCode!=RESULT_OK) return
        val uri:Uri=data?.data ?: return; val host=address.text.toString().trim()
        if(!Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(host)) { status.text="Escribe una dirección IP válida del receptor"; return }
        thread { try {
            val name=contentResolver.query(uri,null,null,null,null)?.use { c -> val i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if(c.moveToFirst() && i>=0) c.getString(i) else "archivo.bin" } ?: "archivo.bin"
            val size=contentResolver.openFileDescriptor(uri,"r")?.use { it.statSize } ?: -1
            if(size<0) throw Exception("No se pudo determinar el tamaño del archivo")
            val conn=URL("http://$host:$port/upload/${URLEncoder.encode(name,"UTF-8")}").openConnection() as HttpURLConnection
            conn.requestMethod="POST"; conn.doOutput=true; conn.connectTimeout=10000; conn.readTimeout=60000; conn.setFixedLengthStreamingMode(size)
            contentResolver.openInputStream(uri)!!.use { source -> conn.outputStream.use { dest -> source.copyTo(dest,65536) } }
            val code=conn.responseCode; conn.disconnect(); runOnUiThread { status.text=if(code==200) "Enviado correctamente: $name" else "Error del receptor: HTTP $code" }
        } catch(e:Exception) { runOnUiThread { status.text="Error de envío: ${e.message}" } } }
    }
    override fun onDestroy() { server?.close(); super.onDestroy() }
}
