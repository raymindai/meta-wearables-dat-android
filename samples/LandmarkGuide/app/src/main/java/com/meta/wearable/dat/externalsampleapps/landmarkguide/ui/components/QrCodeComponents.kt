/*
 * QR Code utilities for room sharing in Majlis
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generate QR code bitmap from content string.
 */
suspend fun generateQrCode(content: String, size: Int = 512): Bitmap? {
    return withContext(Dispatchers.Default) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(
                        x, y,
                        if (bitMatrix[x, y]) android.graphics.Color.BLACK 
                        else android.graphics.Color.WHITE
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("QrCode", "Failed to generate QR: ${e.message}")
            null
        }
    }
}

/**
 * QR Code dialog showing room invite link.
 */
@Composable
fun QrCodeDialog(
    roomId: String,
    roomName: String,
    onDismiss: () -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Deep link format: landmarkguide://room/{roomId}
    val deepLink = "landmarkguide://room/$roomId"
    
    // Generate QR code
    LaunchedEffect(roomId) {
        qrBitmap = generateQrCode(deepLink)
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "📱 QR로 초대하기",
                    fontSize = 20.sp,
                    color = Color.Black
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    roomName,
                    fontSize = 16.sp,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // QR Code Image
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "다른 기기에서 스캔하여 입장",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(onClick = onDismiss) {
                    Text("닫기")
                }
            }
        }
    }
}
