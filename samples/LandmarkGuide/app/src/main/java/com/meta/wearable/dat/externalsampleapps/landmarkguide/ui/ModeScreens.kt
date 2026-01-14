/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tour Mode Screen - Placeholder for future tour guide functionality
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourModeScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🗺️ Tour Mode", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2E7D32)
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1B5E20)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Tour icon
                Text(
                    text = "🏛️",
                    fontSize = 80.sp
                )
                
                // Title
                Text(
                    text = "Tour Guide Mode",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                // Description
                Text(
                    text = "Explore landmarks with AI-powered\naudio descriptions",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Coming soon badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "🚧 Coming Soon",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
                
                // Features list
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeatureItem("📸 Point camera at landmarks")
                    FeatureItem("🎧 Listen to AI descriptions")
                    FeatureItem("🌍 Multi-language support")
                }
            }
        }
    }
}

/**
 * Generic Mode Screen - Default home screen placeholder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenericModeScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🏠 Generic Mode", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF424242)
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF303030)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Home icon
                Text(
                    text = "🤖",
                    fontSize = 80.sp
                )
                
                // Title
                Text(
                    text = "Generic AI Assistant",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                // Description
                Text(
                    text = "General-purpose AI assistant\nfor everyday tasks",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Coming soon badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "🚧 Coming Soon",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
                
                // Features list
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeatureItem("💬 Voice conversations")
                    FeatureItem("❓ Ask any question")
                    FeatureItem("📋 Task assistance")
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = Color.White.copy(alpha = 0.7f)
    )
}
