package org.hermes.duplex.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class DuplexUiState {
    IDLE, LISTENING, STREAMING, THINKING, SPEAKING, INTERRUPTED
}

@Composable
fun HermesDuplexScreen() {
    var state by remember { mutableStateOf(DuplexUiState.IDLE) }
    var volume by remember { mutableFloatStateOf(0.1f) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF030712)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Status bar info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hermes Duplex • 24kHz Opus",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Text(
                    text = "Fail-Closed Secured",
                    color = Color(0xFF34D399),
                    fontSize = 12.sp
                )
            }

            // Pulsing Audio Orb
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(1f + (volume * 0.4f))
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFF0F172A))
                        ),
                        shape = CircleShape
                    )
                    .clickable {
                        state = if (state == DuplexUiState.SPEAKING) DuplexUiState.INTERRUPTED else DuplexUiState.LISTENING
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state == DuplexUiState.SPEAKING) "Barge-In" else "Listening",
                    color = Color.White,
                    fontSize = 15.sp
                )
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { state = DuplexUiState.INTERRUPTED },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                ) {
                    Text("Barge-In")
                }

                Button(
                    onClick = {
                        state = if (state == DuplexUiState.LISTENING) DuplexUiState.IDLE else DuplexUiState.LISTENING
                    },
                    modifier = Modifier.weight(1.5f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text(if (state == DuplexUiState.LISTENING) "Stop Mic" else "Start Mic")
                }
            }
        }
    }
}
