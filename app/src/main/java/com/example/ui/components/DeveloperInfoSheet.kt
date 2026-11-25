package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.localization.AppStrings
import com.example.ui.theme.CallEmerald
import com.example.ui.theme.CinemaBackground
import com.example.ui.theme.CinemaBorder
import com.example.ui.theme.CinemaSurface
import com.example.ui.theme.CinemaSurfaceHigh
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.UrduCleanFontFamily
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WhatsAppGreen

const val DEVELOPER_WHATSAPP_URL = "https://wa.me/923299931199"
const val DEVELOPER_CALL_URI = "tel:+923299931199"
const val DEVELOPER_PHONE_DISPLAY = "+92 329 9931199"

fun openWhatsAppDirect(context: Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_WHATSAPP_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Unable to open WhatsApp ($DEVELOPER_PHONE_DISPLAY)", Toast.LENGTH_SHORT).show()
    }
}

fun openDialerDirect(context: Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse(DEVELOPER_CALL_URI)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Unable to open dialer ($DEVELOPER_PHONE_DISPLAY)", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperInfoSheet(
    strings: AppStrings,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CinemaSurface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.developerCardBadge,
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricCyan,
                    letterSpacing = 1.sp
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_developer_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = strings.closeButton,
                        tint = TextSecondary
                    )
                }
            }

            DeveloperInfoContent(strings = strings, isModal = true)
        }
    }
}

@Composable
fun DeveloperFooterCard(
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("developer_footer_card")
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(ElectricCyan.copy(alpha = 0.35f), CinemaBorder, ElectricBlue.copy(alpha = 0.35f))
                ),
                shape = RoundedCornerShape(22.dp)
            ),
        shape = RoundedCornerShape(22.dp),
        color = CinemaSurface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ElectricBlue.copy(alpha = 0.12f),
                            CinemaSurface
                        )
                    )
                )
                .padding(18.dp)
        ) {
            DeveloperInfoContent(strings = strings, isModal = false)
        }
    }
}

@Composable
private fun DeveloperInfoContent(
    strings: AppStrings,
    isModal: Boolean
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_zaid_tv_emblem),
                contentDescription = "Zaid Live TV Logo",
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Zaid Live TV",
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = TextPrimary
                )
                Text(
                    text = "زید لائیو ٹی وی",
                    fontFamily = UrduCleanFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = ElectricCyan
                )
            }
        }

        // Developer Identity Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CinemaSurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(ElectricCyan, ElectricBlue))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ZA",
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = CinemaBackground,
                    fontSize = 15.sp
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.developerRole,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = strings.developerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = DEVELOPER_PHONE_DISPLAY,
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // Offline Cache Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ElectricCyan.copy(alpha = 0.08f))
                .border(1.dp, ElectricCyan.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.OfflineBolt,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = strings.offlineCacheReady,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )
        }

        // Direct WhatsApp + Direct Call Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { openWhatsAppDirect(context) },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag(if (isModal) "modal_whatsapp_button" else "footer_whatsapp_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhatsAppGreen,
                    contentColor = Color(0xFF041A0C)
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Chat,
                    contentDescription = "WhatsApp +92 329 9931199",
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.whatsappAction,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { openDialerDirect(context) },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag(if (isModal) "modal_call_button" else "footer_call_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CallEmerald,
                    contentColor = Color(0xFF031A12)
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = "Call +92 329 9931199",
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.callAction,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
